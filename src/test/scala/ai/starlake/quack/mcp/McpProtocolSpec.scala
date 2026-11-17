// src/test/scala/ai/starlake/quack/mcp/McpProtocolSpec.scala
package ai.starlake.quack.mcp

import ai.starlake.quack.{McpConfig, RestLimitsConfig}
import ai.starlake.quack.ondemand.auth.{PatPrincipal, SessionScope, TokenRestriction}
import ai.starlake.quack.ondemand.state.RbacUser
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import io.circe.parser.parse
import org.http4s.{HttpApp, Method, Request, Response, Status, Uri}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

/** Route-level contract of the MCP endpoint: the one auth arm (a live PAT as Bearer), the JSON-RPC
  * envelope, and tool tiering. No server boot, no Postgres: `resolvePat` is an injected lookup and
  * the tools are fakes.
  */
class McpProtocolSpec extends AnyFlatSpec with Matchers:

  private val AdminPat = "qod_pat_admin"
  private val UserPat  = "qod_pat_user"

  private def principal(role: String, admin: Boolean): PatPrincipal =
    PatPrincipal(
      user = RbacUser(id = "u1", tenant = Some("acme"), username = "alice", kind = role),
      patId = "pat-1",
      scope = SessionScope(
        superuser = false,
        manageableTenants = if admin then Set("acme") else Set.empty
      ),
      isAdmin = admin,
      restriction = TokenRestriction.Unrestricted
    )

  private val resolvePat: String => Option[PatPrincipal] =
    case AdminPat              => Some(principal("admin", admin = true))
    case UserPat               => Some(principal("user", admin = false))
    case McpSuperuserPat.Token => Some(McpSuperuserPat.Principal)
    case _                     => None

  private val echoTool = McpToolDef(
    name = "echo",
    description = "echo the arguments back",
    inputSchema = Json.obj("type" -> Json.fromString("object")),
    adminOnly = false,
    run = (_, args) => IO.pure(Right(Json.fromJsonObject(args)))
  )

  private val failTool = McpToolDef(
    name = "always_fails",
    description = "returns a tool-level error",
    inputSchema = Json.obj("type" -> Json.fromString("object")),
    adminOnly = false,
    run = (_, _) => IO.pure(Left("the pool is waking from suspend; retry in a few seconds"))
  )

  /** A tool whose handler answers `status`, with a text the routes read as naming QoD's Postgres.
    */
  private def namingTool(name: String, status: sttp.model.StatusCode) = McpToolDef(
    name = name,
    description = "returns an error naming QoD's Postgres",
    inputSchema = Json.obj("type" -> Json.fromString("object")),
    adminOnly = false,
    run = (_, _) =>
      IO.pure(
        McpToolArgs.bridge[Json](
          Left((status, ai.starlake.quack.ondemand.api.ErrorResponse("code", "names-the-server")))
        )
      )
  )

  private val namingTools = List(
    namingTool("failed_500", sttp.model.StatusCode.InternalServerError),
    namingTool("failed_502", sttp.model.StatusCode.BadGateway),
    namingTool("conflict_409", sttp.model.StatusCode.Conflict),
    namingTool("refused_400", sttp.model.StatusCode.BadRequest),
    namingTool("refused_403", sttp.model.StatusCode.Forbidden),
    namingTool("refused_404", sttp.model.StatusCode.NotFound),
    McpToolDef(
      name = "refused_plain",
      description = "returns a refusal of its own",
      inputSchema = Json.obj("type" -> Json.fromString("object")),
      adminOnly = false,
      run = (_, _) => IO.pure(Left("names-the-server"))
    )
  )

  private val adminTool = McpToolDef(
    name = "scale_pool",
    description = "admin only",
    inputSchema = Json.obj("type" -> Json.fromString("object")),
    adminOnly = true,
    run = (_, _) => IO.pure(Right(Json.obj("scaled" -> Json.True)))
  )

  private val routes =
    new McpRoutes(
      cfg = McpConfig(),
      resolvePat = resolvePat,
      tools = List(echoTool, failTool, adminTool) ++ namingTools,
      serverVersion = "test",
      limits = RestLimitsConfig(),
      textForTenant = t => if t.contains("names-the-server") then "withheld" else t
    ).routes.orNotFound

  private def postWith(body: String, authorization: Option[String]): Response[IO] =
    val base = Request[IO](Method.POST, Uri.unsafeFromString("/mcp")).withEntity(body)
    val req  = authorization.fold(base)(v =>
      base.putHeaders(org.http4s.Header.Raw(org.typelevel.ci.CIString("Authorization"), v))
    )
    routes.run(req).unsafeRunSync()

  private def post(body: String, bearer: Option[String]): Response[IO] =
    postWith(body, bearer.map(t => s"Bearer $t"))

  private def bodyJson(resp: Response[IO]): Json =
    parse(new String(resp.body.compile.toVector.unsafeRunSync().toArray)).toOption
      .getOrElse(Json.Null)

  private def rpc(method: String, id: Int = 1, params: Json = Json.obj()): String =
    Json
      .obj(
        "jsonrpc" -> Json.fromString("2.0"),
        "id"      -> Json.fromInt(id),
        "method"  -> Json.fromString(method),
        "params"  -> params
      )
      .noSpaces

  private def toolNames(resp: Response[IO]): List[String] =
    bodyJson(resp).hcursor
      .downField("result")
      .downField("tools")
      .as[List[Json]]
      .getOrElse(Nil)
      .flatMap(_.hcursor.get[String]("name").toOption)

  // ------------------------------------------------------------------
  // auth: a live PAT as Bearer, nothing else
  // ------------------------------------------------------------------

  "POST /mcp" should "401 with no Authorization header" in {
    post(rpc("ping"), bearer = None).status shouldBe Status.Unauthorized
  }

  it should "401 a non-Bearer Authorization header" in {
    postWith(rpc("ping"), Some(s"Basic $AdminPat")).status shouldBe Status.Unauthorized
  }

  it should "401 a session-JWT-looking bearer" in {
    post(rpc("ping"), bearer = Some("eyJhbGciOiJIUzI1NiJ9.e30.sig")).status shouldBe
      Status.Unauthorized
  }

  it should "401 an empty bearer" in {
    post(rpc("ping"), bearer = Some("")).status shouldBe Status.Unauthorized
  }

  it should "401 any bearer that is not a live PAT" in
    List("sk", "static-key-1", "qod_pat_revoked").foreach { t =>
      withClue(s"bearer $t: ") {
        post(rpc("tools/list"), bearer = Some(t)).status shouldBe Status.Unauthorized
      }
    }

  it should "admit a live PAT" in {
    post(rpc("ping"), bearer = Some(UserPat)).status shouldBe Status.Ok
  }

  // ------------------------------------------------------------------
  // protocol envelope
  // ------------------------------------------------------------------

  it should "answer initialize with serverInfo and the tools capability" in {
    val resp = post(rpc("initialize"), bearer = Some(AdminPat))
    resp.status shouldBe Status.Ok
    val result = bodyJson(resp).hcursor.downField("result")
    result.get[String]("protocolVersion").toOption shouldBe Some("2025-06-18")
    result.downField("serverInfo").get[String]("name").toOption shouldBe Some("quack-on-demand")
    result.downField("capabilities").downField("tools").succeeded shouldBe true
  }

  it should "accept notifications/initialized with 202 and no body" in {
    val n = Json
      .obj(
        "jsonrpc" -> Json.fromString("2.0"),
        "method"  -> Json.fromString("notifications/initialized")
      )
      .noSpaces
    val resp = post(n, bearer = Some(AdminPat))
    resp.status shouldBe Status.Accepted
  }

  it should "answer ping with an empty result" in {
    val resp = post(rpc("ping"), bearer = Some(UserPat))
    bodyJson(resp).hcursor.downField("result").focus shouldBe Some(Json.obj())
  }

  it should "answer an unknown method with -32601" in {
    val resp = post(rpc("resources/list"), bearer = Some(AdminPat))
    bodyJson(resp).hcursor.downField("error").get[Int]("code").toOption shouldBe Some(-32601)
  }

  it should "answer malformed JSON with -32700 and a null id" in {
    val resp = post("{not json", bearer = Some(AdminPat))
    val c    = bodyJson(resp).hcursor
    c.downField("error").get[Int]("code").toOption shouldBe Some(-32700)
    c.downField("id").focus shouldBe Some(Json.Null)
  }

  it should "405 a GET" in {
    val req = Request[IO](Method.GET, Uri.unsafeFromString("/mcp"))
    routes.run(req).unsafeRunSync().status shouldBe Status.MethodNotAllowed
  }

  // ------------------------------------------------------------------
  // tool tiering
  // ------------------------------------------------------------------

  "tools/list" should "show both tiers to an admin PAT and only the data tier to a user PAT" in {
    toolNames(post(rpc("tools/list"), bearer = Some(AdminPat))) should
      contain allOf ("echo", "always_fails", "scale_pool")
    val userTools = toolNames(post(rpc("tools/list"), bearer = Some(UserPat)))
    userTools should contain allOf ("echo", "always_fails")
    userTools should not contain "scale_pool"
  }

  "tools/call" should "refuse an admin tool for a user PAT with -32602" in {
    val params = Json.obj("name" -> Json.fromString("scale_pool"), "arguments" -> Json.obj())
    val resp   = post(rpc("tools/call", params = params), bearer = Some(UserPat))
    bodyJson(resp).hcursor.downField("error").get[Int]("code").toOption shouldBe Some(-32602)
  }

  it should "refuse an unknown tool with -32602" in {
    val params = Json.obj("name" -> Json.fromString("nope"), "arguments" -> Json.obj())
    val resp   = post(rpc("tools/call", params = params), bearer = Some(AdminPat))
    bodyJson(resp).hcursor.downField("error").get[Int]("code").toOption shouldBe Some(-32602)
  }

  it should "run a tool and wrap Right as non-error text content" in {
    val params = Json.obj(
      "name"      -> Json.fromString("echo"),
      "arguments" -> Json.obj("x" -> Json.fromInt(7))
    )
    val resp   = post(rpc("tools/call", params = params), bearer = Some(UserPat))
    val result = bodyJson(resp).hcursor.downField("result")
    result.get[Boolean]("isError").toOption shouldBe Some(false)
    val text = result.downField("content").downN(0).get[String]("text").toOption.getOrElse("")
    parse(text).toOption.flatMap(_.hcursor.get[Int]("x").toOption) shouldBe Some(7)
  }

  private def toolText(tool: String, token: String) =
    val params = Json.obj("name" -> Json.fromString(tool), "arguments" -> Json.obj())
    bodyJson(post(rpc("tools/call", params = params), bearer = Some(token))).hcursor
      .downField("result")
      .downField("content")
      .downN(0)
      .get[String]("text")
      .toOption

  it should "withhold what would be a 5xx or a 409 naming QoD's Postgres, from who is not a superuser" in
    List("failed_500", "failed_502", "conflict_409").foreach { tool =>
      withClue(tool) {
        toolText(tool, UserPat) shouldBe Some("withheld")
        toolText(tool, McpSuperuserPat.Token) shouldBe Some("code: names-the-server")
      }
    }

  it should "leave a refusal of the request as it is, whatever it quotes" in {
    List("refused_400", "refused_403", "refused_404").foreach { tool =>
      withClue(tool)(toolText(tool, UserPat) shouldBe Some("code: names-the-server"))
    }
    toolText("refused_plain", UserPat) shouldBe Some("names-the-server")
  }

  it should "wrap Left as isError text content" in {
    val params = Json.obj("name" -> Json.fromString("always_fails"), "arguments" -> Json.obj())
    val resp   = post(rpc("tools/call", params = params), bearer = Some(UserPat))
    val result = bodyJson(resp).hcursor.downField("result")
    result.get[Boolean]("isError").toOption shouldBe Some(true)
    result.downField("content").downN(0).get[String]("text").toOption.getOrElse("") should
      include("retry in a few seconds")
  }

  // ------------------------------------------------------------------
  // request limits: the body is read only once the PAT resolved, and off the compute threads
  // ------------------------------------------------------------------

  private def routesWith(
      limits: RestLimitsConfig,
      resolve: String => Option[PatPrincipal] = resolvePat
  ): HttpApp[IO] =
    new McpRoutes(
      cfg = McpConfig(),
      resolvePat = resolve,
      tools = List(echoTool),
      serverVersion = "test",
      limits = limits,
      textForTenant = identity
    ).routes.orNotFound

  private def mcpRequest(bearer: String): Request[IO] =
    Request[IO](Method.POST, Uri.unsafeFromString("/mcp"))
      .putHeaders(
        org.http4s.Header.Raw(org.typelevel.ci.CIString("Authorization"), s"Bearer $bearer")
      )

  private def postTo(app: HttpApp[IO], body: String, bearer: String): Response[IO] =
    app.run(mcpRequest(bearer).withEntity(body)).unsafeRunSync()

  private def closesConnection(resp: Response[IO]): Boolean =
    resp.headers
      .get(org.typelevel.ci.CIString("Connection"))
      .exists(_.exists(_.value.equalsIgnoreCase("close")))

  "POST /mcp" should "answer 413 for a body over the limit, once the PAT resolved" in {
    val app  = routesWith(RestLimitsConfig(maxBodyBytes = 64))
    val big  = rpc("ping") + " " * 64
    val resp = postTo(app, big, AdminPat)
    resp.status shouldBe Status.PayloadTooLarge
    // The rest of the body stays unread, so the connection must not be reused.
    closesConnection(resp) shouldBe true
    postTo(app, big, "qod_pat_unknown").status shouldBe Status.Unauthorized
  }

  it should "answer a body within the limit" in {
    val app  = routesWith(RestLimitsConfig(maxBodyBytes = 4096))
    val resp = postTo(app, rpc("ping"), AdminPat)
    resp.status shouldBe Status.Ok
    bodyJson(resp).hcursor.downField("result").focus shouldBe Some(Json.obj())
  }

  it should "answer JSON nested too deep, once the PAT resolved" in {
    val app  = routesWith(RestLimitsConfig())
    val deep = "[" * 257 + "]" * 257
    val resp = postTo(app, deep, AdminPat)
    resp.status shouldBe Status.Ok
    resp.as[String].unsafeRunSync() should include("request nests too deeply")
    postTo(app, deep, "qod_pat_unknown").status shouldBe Status.Unauthorized
  }

  it should "answer 408 for a body that does not arrive in time, once the PAT resolved" in {
    val app = routesWith(RestLimitsConfig(bodyTimeoutSec = 1))
    // Bounded here too: without the route's own deadline this body would hold the test forever.
    val resp = app
      .run(mcpRequest(AdminPat).withBodyStream(fs2.Stream.never[IO]))
      .timeout(10.seconds)
      .unsafeRunSync()
    resp.status shouldBe Status.RequestTimeout
    closesConnection(resp) shouldBe true
  }

  it should "not pull the body of a request whose PAT does not resolve" in {
    val app    = routesWith(RestLimitsConfig())
    val pulled = new java.util.concurrent.atomic.AtomicBoolean(false)
    val body   = fs2.Stream.exec(IO(pulled.set(true)))
    val resp   = app.run(mcpRequest("qod_pat_unknown").withBodyStream(body)).unsafeRunSync()
    resp.status shouldBe Status.Unauthorized
    pulled.get shouldBe false
    // The body stays unread, so the connection must not be reused.
    closesConnection(resp) shouldBe true
  }

  it should "resolve the PAT off the compute threads" in {
    val thread = new java.util.concurrent.atomic.AtomicReference[String]("")
    val app    = routesWith(
      RestLimitsConfig(),
      t => {
        thread.set(Thread.currentThread().getName)
        resolvePat(t)
      }
    )
    postTo(app, rpc("ping"), AdminPat).status shouldBe Status.Ok
    thread.get should not be empty
    thread.get should not fullyMatch regex("io-compute-\\d+")
  }

  "the tool registry" should "refuse a tool named after a reserved PAT tool name such as rest" in {
    // `rest` is the REST data edge's name on the PAT tools axis: an MCP tool of that name would
    // make one allowlist entry admit two different surfaces.
    TokenRestriction.ReservedToolNames should contain("rest")
    val restTool = echoTool.copy(name = "rest")
    val thrown   = intercept[IllegalArgumentException] {
      new McpRoutes(
        McpConfig(),
        resolvePat,
        List(echoTool, restTool),
        "test",
        RestLimitsConfig(),
        identity
      )
    }
    thrown.getMessage should include("rest")
  }
