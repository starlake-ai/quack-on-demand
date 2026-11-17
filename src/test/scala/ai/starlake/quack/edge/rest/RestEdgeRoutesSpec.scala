package ai.starlake.quack.edge.rest

import ai.starlake.quack.RestEdgeConfig
import ai.starlake.quack.ondemand.api.ErrorResponse
import cats.effect.{Deferred, IO}
import cats.effect.unsafe.implicits.global
import io.circe.{ACursor, Json}
import org.http4s.{Header as H4sHeader, Headers, Method, Request, Response, Uri}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIString
import sttp.model.{Header, StatusCode}
import sttp.tapir.server.ServerEndpoint

import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration.*

/** The REST data edge's routes on the manager's listener, driven as an `HttpRoutes` (no socket):
  * which requests the edge claims, the gates, the in-flight cap, the headers every response
  * carries, and how a page body leaves. The transport (Ember limits, TLS) is the manager's and is
  * covered by its own specs. The handlers are canned server logic over the real
  * [[RestEdgeEndpoints]].
  */
class RestEdgeRoutesSpec extends AnyFlatSpec with Matchers:

  private def cfg(maxConcurrentRequests: Int = 8) = RestEdgeConfig(
    enabled = true,
    defaultLimit = 10,
    maxRows = 100,
    maxResponseBytes = 1L << 20,
    stmtTimeoutSec = 5,
    maxConcurrentRequests = maxConcurrentRequests
  )

  /** What a handler received, as JSON. */
  private def echo(tenant: String, req: RestRequest): Json = Json.obj(
    "tenant"        -> Json.fromString(tenant),
    "rawQuery"      -> Json.fromString(req.rawQuery),
    "requestId"     -> Json.fromString(req.requestId),
    "authorization" -> Json.fromValues(req.authorization.map(Json.fromString)),
    "accept"        -> req.accept.fold(Json.Null)(Json.fromString),
    "note"          -> Json.fromString("é漢😀")
  )

  private def ok(body: String) =
    RestEdgeRoutes.toTapir(
      Right(RestOk(List(Header("Content-Type", "application/json")), BodyBytes.of(body)))
    )

  /** `/rows` echoes what the handler received; `/schemas` answers a handler-made 404. */
  private val canned: List[ServerEndpoint[Any, IO]] = List(
    RestEdgeEndpoints.readRows.serverLogic { case (tenant, _, _, _, req) =>
      IO.pure(ok(echo(tenant, req).noSpaces))
    },
    RestEdgeEndpoints.listSchemas.serverLogic { _ =>
      IO.pure(
        Left(
          (StatusCode.NotFound, List(Header("Retry-After", "5")), ErrorResponse("not_found", "x"))
        )
      )
    },
    RestEdgeEndpoints.listTables.serverLogic { _ =>
      IO.raiseError(new IllegalStateException("secret-detail"))
    }
  )

  private val edge = new RestEdgeRoutes(cfg(), canned)

  private val RowsPath = "/data/v1/tenant/acme/database/acme_lake/schemas/main/tables/t/rows"

  private def request(
      path: String,
      method: Method = Method.GET,
      headers: Seq[(String, String)] = Nil
  ): Request[IO] =
    // A target starting with `//` would parse as an authority; the server sees a path whose
    // first segments are empty (what Ember makes of `////data/...` on the wire), so build that.
    val uri =
      if path.startsWith("//") then Uri(path = Uri.Path.unsafeFromString(path))
      else Uri.unsafeFromString(path)
    Request[IO](
      method,
      uri,
      headers = Headers(headers.map((k, v) => H4sHeader.Raw(CIString(k), v)).toList)
    )

  /** The edge's answer to a request it claims. */
  private def run(
      req: Request[IO],
      routes: RestEdgeRoutes = edge
  ): (Response[IO], String) =
    val resp = routes.routes.orNotFound.run(req).unsafeRunSync()
    (resp, new String(resp.body.compile.to(Array).unsafeRunSync(), UTF_8))

  private def get(path: String, headers: (String, String)*): (Response[IO], String) =
    run(request(path, headers = headers))

  private def header(resp: Response[IO], name: String): Option[String] =
    resp.headers.get(CIString(name)).map(_.head.value)

  private def json(body: String): ACursor =
    io.circe.parser.parse(body).fold(e => fail(s"not JSON: $body ($e)"), _.hcursor)

  private val SecurityHeaders = List(
    "X-Content-Type-Options"  -> "nosniff",
    "Content-Security-Policy" -> "default-src 'none'; frame-ancestors 'none'",
    "Referrer-Policy"         -> "no-referrer"
  )

  private def assertEdgeHeaders(resp: Response[IO]): Unit =
    SecurityHeaders.foreach((k, v) => header(resp, k) shouldBe Some(v))
    header(resp, "X-Request-Id").getOrElse("") should fullyMatch regex "[0-9a-f-]{36}"
    header(resp, "Server") shouldBe None
    header(resp, "Strict-Transport-Security") shouldBe None

  // ---- what the edge claims ---------------------------------------------------------------------

  "a request outside /data/" should "not be claimed, whatever its method" in {
    for
      path   <- List("/api/tenant/list", "/mcp", "/ui/", "/", "/datax/v1", "/api/data/v1/x", "/d")
      method <- List(Method.GET, Method.POST)
    do
      withClue(s"$method $path") {
        edge.routes.run(request(path, method)).value.unsafeRunSync() shouldBe None
      }
  }

  "a spelling of a manager path under /data/" should "be answered by the edge, never fall through" in {
    for path <- List(
        "/data/../api/auth/whoami",
        "/data/%2e%2e/api/auth/whoami",
        "/data/%2E%2E/api/tenant/list",
        "//data/v1/x",
        "/%64ata/v1/x",
        "/data",
        "/data/"
      )
    do
      withClue(path) {
        RestEdgeRoutes.claims(request(path)) shouldBe true
        val resp = edge.routes.run(request(path)).value.unsafeRunSync()
        resp.map(_.status.code) shouldBe Some(404)
        val r    = resp.get
        val body = new String(r.body.compile.to(Array).unsafeRunSync(), UTF_8)
        json(body).get[String]("error") shouldBe Right("not_found")
        assertEdgeHeaders(r)
      }
  }

  it should "reach a data route when it decodes to one" in {
    for path <- List(s"/$RowsPath", RowsPath.replaceFirst("^/data", "/%64ata")) do
      withClue(path) {
        val (resp, body) = get(path)
        resp.status.code shouldBe 200
        json(body).get[String]("tenant") shouldBe Right("acme")
      }
  }

  // ---- routing and the raw request --------------------------------------------------------------

  "a GET on a route" should "reach the handler with the raw query, every Authorization and a fresh id" in {
    val (resp, body) = get(
      s"$RowsPath?c=eq.1+2&d=%2B&e=a%20b",
      "Authorization" -> "Bearer qod_pat_x",
      "Authorization" -> "Bearer qod_pat_y",
      "X-Request-Id"  -> "client-chosen-id",
      "Accept"        -> "text/csv"
    )
    resp.status.code shouldBe 200
    header(resp, "Content-Type") shouldBe Some("application/json")
    val c = json(body)
    c.get[String]("rawQuery") shouldBe Right("c=eq.1+2&d=%2B&e=a%20b")
    c.get[List[String]]("authorization") shouldBe Right(
      List("Bearer qod_pat_x", "Bearer qod_pat_y")
    )
    c.get[String]("accept") shouldBe Right("text/csv")
    val rid = header(resp, "X-Request-Id").getOrElse("")
    c.get[String]("requestId") shouldBe Right(rid)
    rid should not be "client-chosen-id"
    resp.headers.get(CIString("X-Request-Id")).map(_.size) shouldBe Some(1)
    assertEdgeHeaders(resp)
    header(resp, "Vary") shouldBe Some("Authorization")
    header(resp, "Cache-Control") shouldBe Some("private, no-cache")
    // A buffered body goes out whole, with its length in bytes, not characters.
    header(resp, "Content-Length") shouldBe Some(body.getBytes(UTF_8).length.toString)
    c.get[String]("note") shouldBe Right("é漢😀")
  }

  it should "give every request its own id" in {
    val ids = List.fill(3)(header(get(RowsPath)._1, "X-Request-Id").getOrElse(""))
    ids.distinct.size shouldBe 3
  }

  "a session cookie" should "never reach the handler as a credential" in {
    val (alone, aloneBody) = get(RowsPath, "Cookie" -> "qod_session=s3cr3t")
    alone.status.code shouldBe 200
    json(aloneBody).get[List[String]]("authorization") shouldBe Right(Nil)
    aloneBody should not include "s3cr3t"
    val (both, bothBody) =
      get(RowsPath, "Cookie" -> "qod_session=s3cr3t", "Authorization" -> "Bearer qod_pat_x")
    both.status.code shouldBe 200
    json(bothBody).get[List[String]]("authorization") shouldBe Right(List("Bearer qod_pat_x"))
  }

  it should "keep the handler's own error headers and body" in {
    val (resp, body) = get("/data/v1/tenant/acme/database/acme_lake/schemas")
    resp.status.code shouldBe 404
    header(resp, "Retry-After") shouldBe Some("5")
    json(body).get[String]("error") shouldBe Right("not_found")
    assertEdgeHeaders(resp)
  }

  "an unknown path under /data/" should "get the JSON 404 with every edge header" in {
    for path <- List("/data/v1/nope", "/data/v2/tenant/acme/database/acme_lake/schemas") do
      withClue(path) {
        val (resp, body) = get(path)
        resp.status.code shouldBe 404
        header(resp, "Content-Type") shouldBe Some("application/json")
        json(body).get[String]("error") shouldBe Right("not_found")
        assertEdgeHeaders(resp)
        header(resp, "Cache-Control") shouldBe Some("private, no-cache")
      }
  }

  "a handler that fails" should "get a 500 with every edge header and no detail" in {
    val (resp, body) = get("/data/v1/tenant/acme/database/acme_lake/schemas/main/tables")
    resp.status.code shouldBe 500
    body should not include "secret-detail"
    assertEdgeHeaders(resp)
  }

  // ---- gates ------------------------------------------------------------------------------------

  "a method other than GET" should "get 405 with Allow: GET" in {
    for
      m <- List(Method.POST, Method.HEAD, Method.PUT, Method.DELETE, Method.OPTIONS, Method.PATCH)
      path <- List(RowsPath, "/data/v1/nope")
    do
      withClue(s"$m $path") {
        val (resp, _) = run(request(path, m))
        resp.status.code shouldBe 405
        header(resp, "Allow") shouldBe Some("GET")
        assertEdgeHeaders(resp)
      }
  }

  "a GET that carries a body" should "get 400 with Connection: close, the body unread" in {
    val reached = new java.util.concurrent.atomic.AtomicInteger()
    val counted = new RestEdgeRoutes(
      cfg(),
      List(RestEdgeEndpoints.readRows.serverLogic { case (t, _, _, _, req) =>
        IO(reached.incrementAndGet()) *> IO.pure(ok(echo(t, req).noSpaces))
      })
    )
    for framing <- List("Content-Length" -> "1000000000", "Transfer-Encoding" -> "chunked") do
      withClue(framing) {
        val (resp, body) = run(request(RowsPath, headers = List(framing)), counted)
        resp.status.code shouldBe 400
        header(resp, "Connection").map(_.toLowerCase) shouldBe Some("close")
        json(body).get[String]("error") shouldBe Right("invalid_request")
        assertEdgeHeaders(resp)
      }
    reached.get() shouldBe 0
    // An empty body announced as such is no body.
    run(request(RowsPath, headers = List("Content-Length" -> "0")), counted)._1.status.code shouldBe
      200
  }

  // ---- in-flight cap ----------------------------------------------------------------------------

  "the in-flight cap" should "answer 503 edge_busy past it, and free its permit once a response is made" in {
    val (entered, gate) =
      (Deferred.unsafe[IO, Unit], Deferred.unsafe[IO, Unit])
    val blocking = new RestEdgeRoutes(
      cfg(maxConcurrentRequests = 1),
      List(RestEdgeEndpoints.readRows.serverLogic { case (t, _, _, _, req) =>
        entered.complete(()).attempt *> gate.get *> IO.pure(ok(echo(t, req).noSpaces))
      })
    )
    val app     = blocking.routes.orNotFound
    val program =
      for
        first <- app.run(request(RowsPath)).start
        _     <- entered.get.timeout(5.seconds)
        busy  <- app.run(request(RowsPath))
        busyB <- busy.body.compile.to(Array)
        _     <- gate.complete(())
        // The first response is produced; its body is never pulled.
        firstResp <- first.joinWithNever.timeout(5.seconds)
        third     <- app.run(request(RowsPath)).timeout(5.seconds)
      yield (busy, new String(busyB, UTF_8), firstResp, third)
    val (busy, busyBody, firstResp, third) = program.unsafeRunSync()
    busy.status.code shouldBe 503
    json(busyBody).get[String]("error") shouldBe Right("edge_busy")
    header(busy, "Retry-After") shouldBe Some(RestResponses.RetryAfterSec.toString)
    assertEdgeHeaders(busy)
    firstResp.status.code shouldBe 200
    third.status.code shouldBe 200
  }

  it should "not count a refused request against the cap" in {
    val one = new RestEdgeRoutes(cfg(maxConcurrentRequests = 1), canned)
    (1 to 3).foreach(_ => run(request(RowsPath, Method.POST), one)._1.status.code shouldBe 405)
    run(request(RowsPath), one)._1.status.code shouldBe 200
  }

  // ---- the page body ----------------------------------------------------------------------------

  "a page body" should "reach the stream in chunks no bigger than WriteChunkBytes, whole" in {
    // fs2's TLS write buffer grows to the largest chunk written and never shrinks: one chunk per
    // page would leave every keep-alive connection holding a page's worth of heap after the page
    // budget had given it back.
    val page = "x" * (3 * RestEdgeRoutes.WriteChunkBytes + 17)
    val big  = new RestEdgeRoutes(
      cfg(),
      List(RestEdgeEndpoints.readRows.serverLogic(_ => IO.pure(ok(page))))
    )
    val chunks = big.routes.orNotFound
      .run(request(RowsPath))
      .flatMap(_.body.chunks.map(_.toArray).compile.toList)
      .unsafeRunSync()
    chunks.map(_.length).sum shouldBe page.length
    chunks.map(_.length).max should be <= RestEdgeRoutes.WriteChunkBytes
    new String(chunks.flatten.toArray, UTF_8) shouldBe page
  }

  it should "give its page budget back once the body has been written, not before" in {
    val budget = RestPageBudget.forHeap(1024L, 64L * 1024 * 1024, 1.hour)
    val held   = new RestEdgeRoutes(
      cfg(),
      List(RestEdgeEndpoints.readRows.serverLogic { case (t, _, _, _, req) =>
        budget.reserve(req.requestId, 1.second).as(ok(echo(t, req).noSpaces))
      }),
      pageBudget = budget
    )
    val resp = held.routes.orNotFound.run(request(RowsPath)).unsafeRunSync()
    val rid  = header(resp, "X-Request-Id").getOrElse("")
    budget.holds(rid) shouldBe true
    resp.body.compile.drain.unsafeRunSync()
    budget.holds(rid) shouldBe false
  }
