package ai.starlake.quack.security

import ai.starlake.quack.RestEdgeConfig
import ai.starlake.quack.edge.rest.{BodyBytes, RestEdgeEndpoints, RestEdgeRoutes, RestOk}
import cats.effect.IO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.Header

import java.io.ByteArrayOutputStream
import java.net.{InetSocketAddress, Socket, SocketTimeoutException, URI}
import java.nio.charset.StandardCharsets.UTF_8

/** The REST data edge mounted on the real manager listener ([[ManagerServerHarness]]): every
  * request under `/data/` is the edge's, whatever it spells after the prefix, so no `/data/..` form
  * ever reaches an admin route, and the manager's own routes still answer beside it. Raw sockets
  * send each request target verbatim, as a client that does not normalise paths would.
  */
class ManagerDataEdgeMountSpec extends AnyFlatSpec with Matchers with SecurityHttpHelpers:

  private val cfg = RestEdgeConfig(
    enabled = true,
    defaultLimit = 10,
    maxRows = 100,
    maxResponseBytes = 1L << 20,
    stmtTimeoutSec = 5,
    maxConcurrentRequests = 8
  )

  /** A canned `/rows` that names itself, over the real endpoint definitions. */
  private val edge = new RestEdgeRoutes(
    cfg,
    List(RestEdgeEndpoints.readRows.serverLogic { _ =>
      val ok =
        RestOk(List(Header("Content-Type", "application/json")), BodyBytes.of("""["edge"]"""))
      val length = Header("Content-Length", ok.bytes.length.toString)
      IO.pure(Right((ok.headers :+ length, ok.bytes.buffer)))
    })
  )

  /** Sends `target` verbatim and reads the response until the server closes or 5 s pass. */
  private def raw(
      base: String,
      method: String,
      target: String,
      headers: (String, String)*
  ): (Int, String, String) =
    val uri = URI.create(base)
    val s   = new Socket()
    s.connect(new InetSocketAddress(uri.getHost, uri.getPort), 2000)
    s.setSoTimeout(5000)
    try
      val head = headers.map((k, v) => s"$k: $v\r\n").mkString
      val body = if method == "POST" then "Content-Length: 2\r\n\r\n{}" else "\r\n"
      s.getOutputStream.write(
        s"$method $target HTTP/1.1\r\nHost: x\r\nConnection: close\r\n$head$body".getBytes(UTF_8)
      )
      s.getOutputStream.flush()
      val out = new ByteArrayOutputStream()
      val buf = new Array[Byte](8192)
      var n   = 0
      try while { n = s.getInputStream.read(buf); n >= 0 } do out.write(buf, 0, n)
      catch case _: SocketTimeoutException => ()
      val text            = new String(out.toByteArray, UTF_8)
      val (rawHead, rest) = text.split("\r\n\r\n", 2) match
        case Array(h, b) => (h, b)
        case Array(h)    => (h, "")
      (rawHead.split(" ", 3)(1).toInt, rawHead, rest)
    finally s.close()

  private def hasHeader(head: String, name: String): Boolean =
    head.split("\r\n").exists(_.toLowerCase.startsWith(name.toLowerCase + ":"))

  "a manager with the data edge mounted" should "keep every /data/.. spelling of an admin route on the edge" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, dataEdgeRoutes = Some(edge.routes))
    try
      val root = h.superuserSession()
      // The admin route answers through the manager, with the credential.
      val (direct, _, directBody) = raw(h.baseUrl, "GET", "/api/tenant/list", "X-API-Key" -> root)
      withClue(directBody)(direct shouldBe 200)
      directBody should include(SecurityFixtures.TenantId)
      for
        target <- List(
          "/data/../api/tenant/list",
          "/data/%2e%2e/api/tenant/list",
          "/data/%2E%2E/api/tenant/list",
          "////data/../api/tenant/list",
          "/%64ata/../api/tenant/list",
          "/data/..%2fapi%2ftenant%2flist"
        )
        method <- List("GET", "POST")
      do
        withClue(s"$method $target") {
          val (status, head, body) = raw(h.baseUrl, method, target, "X-API-Key" -> root)
          withClue(head + body)(status should (be(404) or be(405)))
          withClue(head + "\n" + body)(
            errorCode(body) should (contain("not_found") or contain("method_not_allowed"))
          )
          // The edge's own headers: the answer is the edge's, not a manager route's.
          hasHeader(head, "X-Request-Id") shouldBe true
          head should include("Content-Security-Policy: default-src 'none'")
          body should not include SecurityFixtures.TenantId
        }
      // `//data/..` is an authority (`data`) and the path `/../api/tenant/list`: not the edge's,
      // and no manager route either.
      val (authority, _, authorityBody) =
        raw(h.baseUrl, "GET", "//data/../api/tenant/list", "X-API-Key" -> root)
      authority shouldBe 404
      authorityBody should not include SecurityFixtures.TenantId
    finally h.shutdown()
  }

  it should "serve the edge's routes and leave the manager's to the manager" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, dataEdgeRoutes = Some(edge.routes))
    try
      val rows = "/data/v1/tenant/acme/database/acme_lake/schemas/main/tables/t/rows"
      val (status, head, body) = raw(h.baseUrl, "GET", rows)
      withClue(head)((status, body) shouldBe (200, """["edge"]"""))
      hasHeader(head, "X-Request-Id") shouldBe true
      // A buffered page goes out whole with its length, not chunked.
      head should include("Content-Length: 8")
      // A manager path is not the edge's: no edge request id, the manager's own guard answers.
      val (unauth, unauthHead, _) = raw(h.baseUrl, "GET", "/api/tenant/list")
      unauth shouldBe 401
      hasHeader(unauthHead, "X-Request-Id") shouldBe false
      raw(h.baseUrl, "GET", "/health")._1 shouldBe 200
    finally h.shutdown()
  }
