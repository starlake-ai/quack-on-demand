// src/test/scala/ai/starlake/quack/security/ManagerRestSecuritySpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.RestLimitsConfig
import ai.starlake.quack.edge.{BoundedBody, ConnectionBudget}
import ai.starlake.quack.observability.metrics.MetricsEndpoint
import ai.starlake.quack.ondemand.api.{BranchHandlers, ExecCaller}
import ai.starlake.quack.ondemand.branch.{
  BranchService,
  ChangeCounter,
  MergeFence,
  TableChange,
  ZeroRowsMergeExecutor
}
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import cats.effect.IO
import io.circe.parser._
import io.micrometer.prometheusmetrics.{PrometheusConfig, PrometheusMeterRegistry}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets.UTF_8

/** End-to-end security tests for the ManagerServer REST API.
  *
  * Each test boots a fresh ManagerServer on an ephemeral port backed by the in-memory fixture store
  * from [[SecurityFixtures]]. There is no shared state between cases. The harness is torn down in a
  * try/finally block immediately after each test so ports are released promptly.
  *
  * Groups: A. apiKeyGuard (sessions and personal access tokens only) B. Public paths that bypass
  * the guard C. Login admin gate (AuthHandlers.login) D. Superuser gate on /api/config/server and
  * /api/manifest/export|import
  */
class ManagerRestSecuritySpec extends AnyFlatSpec with Matchers with SecurityHttpHelpers:

  /** Reads one HTTP/1.1 response from `in` (its headers, then a Content-Length or chunked body) and
    * answers its status; -1 when the server closed the connection first.
    */
  private def rawStatus(in: java.io.InputStream): Int = rawResponse(in)._1

  /** Status line and lower-cased head a raw socket gets for `request`, the socket read to its end.
    */
  private def raw(baseUrl: String, request: String): (String, String) =
    val uri  = java.net.URI.create(baseUrl)
    val sock = new java.net.Socket(uri.getHost, uri.getPort)
    try
      sock.setSoTimeout(15000)
      sock.getOutputStream.write(request.getBytes(UTF_8))
      sock.getOutputStream.flush()
      val text = new String(sock.getInputStream.readAllBytes(), UTF_8)
      (text.takeWhile(_ != '\r'), text.takeWhile(_ != '{').toLowerCase)
    finally sock.close()

  /** [[rawStatus]], with the response's headers (names lowercased) and its body, whole (empty when
    * it has no Content-Length and is not chunked).
    */
  private def rawResponse(in: java.io.InputStream): (Int, Map[String, String], Array[Byte]) =
    def line(): Option[String] =
      val sb = new StringBuilder
      var c  = in.read()
      while c != -1 && c != '\n' do
        if c != '\r' then sb.append(c.toChar)
        c = in.read()
      if c == -1 && sb.isEmpty then None else Some(sb.toString)
    line() match
      case None         => (-1, Map.empty, Array.emptyByteArray)
      case Some(status) =>
        val headers = Iterator
          .continually(line().getOrElse(""))
          .takeWhile(_.nonEmpty)
          .map { h =>
            val i = h.indexOf(':')
            h.take(i).trim.toLowerCase -> h.drop(i + 1).trim
          }
          .toMap
        val body = headers.get("content-length") match
          case Some(n) => in.readNBytes(n.toInt)
          case None    =>
            if headers.get("transfer-encoding").exists(_.equalsIgnoreCase("chunked")) then
              val chunks = Iterator
                .continually(Integer.parseInt(line().getOrElse("0").takeWhile(_ != ';').trim, 16))
                .map { size =>
                  val data = in.readNBytes(size)
                  line()
                  (size, data)
                }
                .takeWhile((size, _) => size > 0)
                .map((_, data) => data)
                .toList
              Array.concat(chunks*)
            else Array.emptyByteArray
        (status.split(' ')(1).toInt, headers, body)

  // ------------------------------------------------------------------
  // A. apiKeyGuard: sessions and PATs only
  // ------------------------------------------------------------------

  "apiKeyGuard" should "refuse an unauthenticated /api call; a session admits" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val resp = get(h.httpClient, s"${h.baseUrl}/api/tenant/list")
      withClue(s"GET /api/tenant/list body: ${resp.body()}") {
        resp.statusCode() shouldBe 401
      }
      val token  = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val authed = get(h.httpClient, s"${h.baseUrl}/api/tenant/list", apiKey = Some(token))
      withClue(s"session GET /api/tenant/list body: ${authed.body()}") {
        authed.statusCode() shouldBe 200
      }
    finally h.shutdown()
  }

  it should "refuse an empty X-API-Key like an absent one" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val keyless = get(h.httpClient, s"${h.baseUrl}/api/tenant/list")
      withClue(s"keyless body: ${keyless.body()}") {
        keyless.statusCode() shouldBe 401
      }
      val emptyKey = get(h.httpClient, s"${h.baseUrl}/api/tenant/list", apiKey = Some(""))
      withClue(s"empty-key body: ${emptyKey.body()}") {
        emptyKey.statusCode() shouldBe 401
      }
    finally h.shutdown()
  }

  it should "keep public endpoints keyless" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val resp = get(h.httpClient, s"${h.baseUrl}/api/config/client")
      withClue(s"GET /api/config/client body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
      }
    finally h.shutdown()
  }

  it should "refuse a credential that is neither a session nor a PAT, on every transport" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      // What a static API key used to be: an opaque string the manager compared against its
      // configuration. The manager knows no such credential: every transport answers 401.
      List("k1", "wrong", "qod_pat_unknown").foreach { bogus =>
        val header = get(h.httpClient, s"${h.baseUrl}/api/tenant/list", apiKey = Some(bogus))
        withClue(s"X-API-Key $bogus: ")(header.statusCode() shouldBe 401)
        val cookie = getWithCookie(h.httpClient, s"${h.baseUrl}/api/tenant/list", bogus)
        withClue(s"qod_session cookie $bogus: ")(cookie.statusCode() shouldBe 401)
        val scimUrl = s"${h.baseUrl}/api/scim/v2/${SecurityFixtures.TenantId}/Users"
        val bearer  = h.httpClient.send(
          java.net.http.HttpRequest
            .newBuilder(java.net.URI.create(scimUrl))
            .header("Authorization", s"Bearer $bogus")
            .GET()
            .timeout(RequestTimeout)
            .build(),
          java.net.http.HttpResponse.BodyHandlers.ofString()
        )
        withClue(s"SCIM Bearer $bogus: ")(bearer.statusCode() shouldBe 401)
      }
    finally h.shutdown()
  }

  it should "allow GET /api/tenant/list when X-API-Key is a valid session token" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(h.httpClient, s"${h.baseUrl}/api/tenant/list", apiKey = Some(token))
      withClue(s"GET /api/tenant/list body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
      }
    finally h.shutdown()
  }

  it should "not admit a percent-encoded /api prefix with no key" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      // The guard tests the request path for the `/api/` prefix while tapir
      // routes on DECODED segments. If the two ever disagree, `/%61pi/...`
      // ("a" percent-encoded) skips the guard yet still matches the tenant-list
      // route -- a full auth bypass. Any non-2xx answer proves it did not; the
      // guard's own 401 is what actually answers today. Asserted as "not 2xx"
      // rather than the exact code so a stack that rejects the encoded form
      // upstream (404) stays green: only leaking data is the failure.
      val resp = get(h.httpClient, s"${h.baseUrl}/%61pi/tenant/list")
      withClue(s"GET /%61pi/tenant/list status=${resp.statusCode()} body: ${resp.body()}") {
        (resp.statusCode() >= 200 && resp.statusCode() < 300) shouldBe false
      }
    finally h.shutdown()
  }

  it should "not admit a slash-prefixed /api path with no key" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      // Tapir drops EVERY leading slash before segmenting, so `////api/...`
      // routes to the tenant-list endpoint. A guard that keeps the leading
      // empty segment sees `//api/tenant/list`, decides it is outside the
      // guarded namespace and waves the request through unauthenticated --
      // the exact bypass this pins shut. Two spellings so a fix that only
      // normalizes one extra slash cannot pass.
      val four = get(h.httpClient, s"${h.baseUrl}////api/tenant/list")
      withClue(s"GET ////api/tenant/list status=${four.statusCode()} body: ${four.body()}") {
        (four.statusCode() >= 200 && four.statusCode() < 300) shouldBe false
      }
      val five = get(h.httpClient, s"${h.baseUrl}/////api/pool/list")
      withClue(s"GET /////api/pool/list status=${five.statusCode()} body: ${five.body()}") {
        (five.statusCode() >= 200 && five.statusCode() < 300) shouldBe false
      }
    finally h.shutdown()
  }

  it should "demote a non-admin session to the profile allowlist" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      // bob is a role=user principal in acme: a tenant-scoped login mints a
      // profile-only session (no admin grant, empty manageableTenants).
      val token = h.mintToken(
        SecurityFixtures.BobUsername,
        SecurityFixtures.BobPassword,
        tenant = Some(SecurityFixtures.TenantId)
      )

      // An admin endpoint answers 403 with a distinguishable code -- NOT the
      // bare 401 an unknown token gets. The session is real; the grant isn't.
      val denied = get(h.httpClient, s"${h.baseUrl}/api/pool/list", apiKey = Some(token))
      withClue(s"GET /api/pool/list body: ${denied.body()}") {
        denied.statusCode() shouldBe 403
        errorCode(denied.body()) should contain("admin_required")
      }

      // Allowlisted self-service paths reach their handlers.
      val whoami = get(h.httpClient, s"${h.baseUrl}/api/auth/whoami", apiKey = Some(token))
      withClue(s"GET /api/auth/whoami body: ${whoami.body()}") {
        whoami.statusCode() shouldBe 200
      }
      val logout = post(h.httpClient, s"${h.baseUrl}/api/auth/logout", "", apiKey = Some(token))
      withClue(s"POST /api/auth/logout body: ${logout.body()}") {
        logout.statusCode() shouldBe 200
      }
    finally h.shutdown()
  }

  it should "keep a tenant's authorized locations to admins: 403 for a profile session" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val body = s"""{"tenant":"${SecurityFixtures.TenantId}"}"""
      val url  = s"${h.baseUrl}/api/tenant/location/list"
      val su   = post(h.httpClient, url, body, apiKey = Some(h.superuserSession()))
      withClue(s"superuser body: ${su.body()}")(su.statusCode() shouldBe 200)
      val bob = h.mintToken(
        SecurityFixtures.BobUsername,
        SecurityFixtures.BobPassword,
        tenant = Some(SecurityFixtures.TenantId)
      )
      val denied = post(h.httpClient, url, body, apiKey = Some(bob))
      withClue(s"profile session body: ${denied.body()}") {
        denied.statusCode() shouldBe 403
        errorCode(denied.body()) should contain("admin_required")
      }
      post(h.httpClient, url, body).statusCode() shouldBe 401
    finally h.shutdown()
  }

  it should "let a profile session manage its own S3 credentials, and keep the user routes to admins" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val bob = h.mintToken(
        SecurityFixtures.BobUsername,
        SecurityFixtures.BobPassword,
        tenant = Some(SecurityFixtures.TenantId)
      )
      val add = post(
        h.httpClient,
        s"${h.baseUrl}/api/profile/s3/add",
        """{"scope":"s3://bob-etl/","accessKeyId":"AKIABOB","secretAccessKey":"bob-sekr3t"}""",
        apiKey = Some(bob)
      )
      withClue(s"add body: ${add.body()}")(add.statusCode() shouldBe 200)
      add.body() should not include "bob-sekr3t"
      val mine = post(h.httpClient, s"${h.baseUrl}/api/profile/s3/list", "{}", apiKey = Some(bob))
      mine.statusCode() shouldBe 200
      mine.body() should include("s3://bob-etl/")
      mine.body() should not include "bob-sekr3t"
      val userBody = s"""{"userId":"${fix.bobUserId}"}"""
      val denied   =
        post(h.httpClient, s"${h.baseUrl}/api/user/s3/list", userBody, apiKey = Some(bob))
      withClue(s"profile session body: ${denied.body()}") {
        denied.statusCode() shouldBe 403
        errorCode(denied.body()) should contain("admin_required")
      }
      val su = post(
        h.httpClient,
        s"${h.baseUrl}/api/user/s3/list",
        userBody,
        apiKey = Some(h.superuserSession())
      )
      su.statusCode() shouldBe 200
      su.body() should include("s3://bob-etl/")
      su.body() should not include "bob-sekr3t"
      post(h.httpClient, s"${h.baseUrl}/api/profile/s3/list", "{}").statusCode() shouldBe 401
    finally h.shutdown()
  }

  it should "refuse a call to /api/internal/ on the REST port" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      // Nodes call these on the internal listener; here the guard refuses the path like any other.
      post(h.httpClient, s"${h.baseUrl}/api/internal/authz/credentials", """{"handle":"x"}""")
        .statusCode() shouldBe 401
      post(h.httpClient, s"${h.baseUrl}/api/internal/authz/decide", """{"handle":"x"}""")
        .statusCode() shouldBe 401
    finally h.shutdown()
  }

  it should "keep a keep-alive connection usable after refusing a POST whose body came late" in {
    // The guard answers before reading the body. Ember then closed the connection it had just
    // answered as keep-alive whenever the body had not arrived with the headers, and the JDK
    // client's next POST on it failed with "header parser received no bytes". A raw socket makes
    // the late body certain: headers, a pause, then the body.
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val uri  = java.net.URI.create(h.baseUrl)
      val sock = new java.net.Socket(uri.getHost, uri.getPort)
      try
        sock.setSoTimeout(10000)
        val out  = sock.getOutputStream
        val in   = new java.io.BufferedInputStream(sock.getInputStream)
        val body = s"""{"tenant":"${SecurityFixtures.TenantId}"}""".getBytes(UTF_8)
        out.write(
          ("POST /api/tenant/location/list HTTP/1.1\r\nHost: localhost\r\n" +
            s"Content-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n")
            .getBytes(UTF_8)
        )
        out.flush()
        Thread.sleep(300)
        out.write(body)
        out.flush()
        rawStatus(in) shouldBe 401
        out.write("GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(UTF_8))
        out.flush()
        rawStatus(in) shouldBe 200
      finally sock.close()
    finally h.shutdown()
  }

  it should "say Connection: close when it refuses a request whose body it left unread" in {
    // A chunked body is refused unread, and Ember then closes the connection: the answer must not
    // invite a pooling client to reuse it (Phase C ledger T5-R4). An announced small body is read
    // first, and a GET has none: both keep the connection. Each answer is followed by a second
    // request on the same socket, which only a kept connection serves: what a pooling client
    // relies on when it trusts the header.
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val uri  = java.net.URI.create(h.baseUrl)
      val json = s"""{"tenant":"${SecurityFixtures.TenantId}"}"""
      val next = "GET /api/tenant/list HTTP/1.1\r\nHost: localhost\r\n\r\n"
      // The status and headers of the answer to `request`, and whether the same socket then
      // served `next` (false when the server closed it: the write or the read fails, or the read
      // ends at once). A read timeout is no answer either way and fails the case.
      def answer(request: String): (Int, Map[String, String], Boolean) =
        val sock = new java.net.Socket(uri.getHost, uri.getPort)
        try
          sock.setSoTimeout(10000)
          val in              = new java.io.BufferedInputStream(sock.getInputStream)
          def send(r: String) =
            sock.getOutputStream.write(r.getBytes(UTF_8))
            sock.getOutputStream.flush()
          send(request)
          val (status, headers, _) = rawResponse(in)
          val reused               =
            try
              send(next)
              rawResponse(in)._1 > 0
            catch
              case e: java.net.SocketTimeoutException => throw e
              case _: java.io.IOException             => false
          (status, headers, reused)
        finally sock.close()
      val post = "POST /api/tenant/location/list HTTP/1.1\r\nHost: localhost\r\n" +
        "Content-Type: application/json\r\n"
      val chunked = answer(
        post + "Transfer-Encoding: chunked\r\n\r\n" +
          s"${Integer.toHexString(json.length)}\r\n$json\r\n0\r\n\r\n"
      )
      chunked._1 shouldBe 401
      withClue("the connection after a chunked refusal: ")(chunked._3 shouldBe false)
      chunked._2.get("connection").map(_.toLowerCase) shouldBe Some("close")
      val announced = answer(post + s"Content-Length: ${json.length}\r\n\r\n$json")
      announced._1 shouldBe 401
      withClue("the connection after an announced body's refusal: ")(announced._3 shouldBe true)
      announced._2.get("connection").map(_.toLowerCase) should not be Some("close")
      val get = answer(next)
      get._1 shouldBe 401
      withClue("the connection after a GET's refusal: ")(get._3 shouldBe true)
      get._2.get("connection").map(_.toLowerCase) should not be Some("close")
    finally h.shutdown()
  }

  it should "let a non-admin session reach both /api/profile routes" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val token = h.mintToken(
        SecurityFixtures.BobUsername,
        SecurityFixtures.BobPassword,
        tenant = Some(SecurityFixtures.TenantId)
      )
      // 200 (not 401/403) proves the guard's allowlist arm handed the request
      // to the real handler: these two paths 404'd until the routes existed,
      // which is why the case was deferred out of the guard's own task.
      val usage = get(h.httpClient, s"${h.baseUrl}/api/profile/usage", apiKey = Some(token))
      withClue(s"GET /api/profile/usage body: ${usage.body()}") {
        usage.statusCode() shouldBe 200
      }
      val stmts = get(h.httpClient, s"${h.baseUrl}/api/profile/statements", apiKey = Some(token))
      withClue(s"GET /api/profile/statements body: ${stmts.body()}") {
        stmts.statusCode() shouldBe 200
      }

      // The same session on an admin route stays sealed -- the allowlist widened
      // by exactly two paths, not by a namespace.
      val denied = get(h.httpClient, s"${h.baseUrl}/api/pool/list", apiKey = Some(token))
      withClue(s"GET /api/pool/list body: ${denied.body()}") {
        denied.statusCode() shouldBe 403
        errorCode(denied.body()) should contain("admin_required")
      }
    finally h.shutdown()
  }

  it should "match the profile allowlist exactly, never by prefix" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val token = h.mintToken(
        SecurityFixtures.BobUsername,
        SecurityFixtures.BobPassword,
        tenant = Some(SecurityFixtures.TenantId)
      )
      // A path that merely EXTENDS an allowlisted one must stay sealed. A
      // prefix-based predicate would admit it (and answer 404 from the router)
      // -- and would likewise leak every SPI module prefix mounted under /api.
      val resp = get(h.httpClient, s"${h.baseUrl}/api/auth/whoami/extra", apiKey = Some(token))
      withClue(s"GET /api/auth/whoami/extra body: ${resp.body()}") {
        resp.statusCode() shouldBe 403
        errorCode(resp.body()) should contain("admin_required")
      }
    finally h.shutdown()
  }

  // ---- the branch routes: any principal who may connect may branch, the handlers' gate decides ----

  /** The harness with the branch routes mounted: a branch service that never gets past its gates,
    * since no fixture database is a DuckLake one and no fixture user but alice holds a pool grant.
    */
  private def withBranchRoutes(body: ManagerServerHarness.Harness => Unit): Unit =
    withBranchRoutesLimited(RestLimitsConfig())(body)

  private def withBranchRoutesLimited(limits: RestLimitsConfig)(
      body: ManagerServerHarness.Harness => Unit
  ): Unit =
    val fix = SecurityFixtures.freshStore()
    SecurityFixtures.addTenantB(fix)
    val h = ManagerServerHarness.boot(
      fix.store,
      restLimits = limits,
      branchesOf = Some((sup, preview, callers) =>
        new BranchHandlers(
          sup,
          new BranchService(
            cfg = ai.starlake.quack.BranchingConfig(),
            sup = sup,
            store = fix.store,
            resolveReader = (_, _) => new DuckLakeCatalogReader(null),
            cloneCatalog = (_, _, _, _) => Left("not in this spec"),
            mergeExecutor = ZeroRowsMergeExecutor,
            mergeFence = new MergeFence:
              def arm(
                  m: Map[String, String],
                  msg: String,
                  base: Long,
                  ids: Set[Long],
                  hold: Option[Int]
              ) =
                Right(())
              def disarm(meta: Map[String, String], message: String) = ()
            ,
            counter = new ChangeCounter:
              def count(
                  caller: ExecCaller,
                  t: String,
                  b: ai.starlake.quack.model.Branch,
                  a: String,
                  c: TableChange,
                  f: Long,
                  h: Long
              ) = IO.pure(Right((0L, 0L, 0L)))
            ,
            purgeFiles = (_, _) => Right(())
          ),
          preview,
          callers
        )
      )
    )
    try body(h)
    finally h.shutdown()

  private def bobOf(h: ManagerServerHarness.Harness): String =
    h.mintToken(
      SecurityFixtures.BobUsername,
      SecurityFixtures.BobPassword,
      tenant = Some(SecurityFixtures.TenantId)
    )

  /** A request of any method, with an empty JSON body when it takes one. */
  private def send(
      h: ManagerServerHarness.Harness,
      method: String,
      path: String,
      token: Option[String]
  ): java.net.http.HttpResponse[String] =
    val hasBody = method != "GET" && method != "DELETE"
    val request = java.net.http.HttpRequest
      .newBuilder(java.net.URI.create(s"${h.baseUrl}$path"))
      .timeout(RequestTimeout)
      .method(
        method,
        if hasBody then java.net.http.HttpRequest.BodyPublishers.ofString("{}")
        else java.net.http.HttpRequest.BodyPublishers.noBody()
      )
    if hasBody then request.header("Content-Type", "application/json")
    token.foreach(t => request.header("X-API-Key", t))
    h.httpClient.send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString())

  private val BranchBase = s"/api/branch/tenant/${SecurityFixtures.TenantId}/database/" +
    s"${SecurityFixtures.TenantDbName}/branches"

  it should "let a plain session reach exactly the branch routes, whose gate then answers it" in
    withBranchRoutes { h =>
      val bob = bobOf(h)
      // bob holds no pool grant, so the answer is the handlers' gate's: past the guard, and refused
      // by the rule meant for him.
      val body =
        s"""{"tenant":"${SecurityFixtures.TenantId}","tenantDb":"${SecurityFixtures.TenantDbName}","""
      List(
        ("POST", "/api/branch/create", body + """"name":"x"}"""),
        ("POST", "/api/branch/propose", body + """"branch":"x"}"""),
        ("POST", "/api/branch/discard", body + """"branch":"x"}""")
      ).foreach { (_, path, json) =>
        val resp = post(h.httpClient, s"${h.baseUrl}$path", json, apiKey = Some(bob))
        withClue(s"POST $path body: ${resp.body()}") {
          resp.statusCode() shouldBe 403
          errorCode(resp.body()) should contain("no_pool_access")
        }
      }
      List(
        BranchBase,
        s"$BranchBase/b",
        s"$BranchBase/b/changes",
        s"$BranchBase/b/diff?schema=main&table=t",
        s"$BranchBase/b/schema-diff?schema=main&table=t"
      ).foreach { path =>
        val resp = get(h.httpClient, s"${h.baseUrl}$path", apiKey = Some(bob))
        withClue(s"GET $path body: ${resp.body()}") {
          resp.statusCode() shouldBe 403
          errorCode(resp.body()) should contain("no_pool_access")
        }
      }
    }

  // Skipping the guard's tenant check is all a listed route skips: its body is read like any other
  // admitted caller's.
  it should "hold a plain session on the branch routes to an admitted caller's body limit" in
    withBranchRoutesLimited(RestLimitsConfig(maxBodyBytes = 4096)) { h =>
      val bob  = bobOf(h)
      val json =
        s"""{"tenant":"${SecurityFixtures.TenantId}","tenantDb":"${SecurityFixtures.TenantDbName}",""" +
          """"name":"x"}"""
      List("/api/branch/create", "/api/branch/propose", "/api/branch/discard").foreach { path =>
        val over = post(h.httpClient, s"${h.baseUrl}$path", json + " " * 4097, apiKey = Some(bob))
        withClue(s"POST $path body: ${over.body()}") {
          over.statusCode() shouldBe 413
          errorCode(over.body()) should contain("payload_too_large")
        }
      }
      val within = post(h.httpClient, s"${h.baseUrl}/api/branch/create", json, apiKey = Some(bob))
      withClue(s"POST create body: ${within.body()}") {
        within.statusCode() shouldBe 403
        errorCode(within.body()) should contain("no_pool_access")
      }
    }

  it should "keep merge and everything else about a branch to admins, on whole segments" in
    withBranchRoutes { h =>
      val bob = bobOf(h)
      List(
        // The one route a plain user must never reach, in the spellings the guard has met before.
        "POST" -> "/api/branch/merge",
        "POST" -> "/%61pi/branch/merge",
        "POST" -> "////api/branch/merge",
        "POST" -> "/api/branch/%6Derge",
        // What only looks like a listed route.
        "POST" -> "/api/branch/create/extra",
        "POST" -> "/api/branch/create/",
        "POST" -> "/api/branch/create%2Fextra",
        "POST" -> "/api/branch/merge%2F..%2Fcreate",
        "POST" -> "/api/branch//create",
        "POST" -> "/api/branchx/create",
        "POST" -> "/api/branch/created",
        "POST" -> "/api/branch/later-route",
        "GET"  -> "/api/branch/later-route",
        "GET"  -> "/api/branch",
        "GET"  -> "/api/branch/",
        "GET"  -> s"/api/branch/tenant/${SecurityFixtures.TenantId}",
        "GET"  -> s"/api/branch/tenant/${SecurityFixtures.TenantId}/database/d",
        "GET"  -> "/api/branch/tenant//database/d/branches",
        "GET"  -> s"/api/branch/tenant/${SecurityFixtures.TenantId}/database//branches",
        "GET"  -> s"$BranchBase/",
        "GET"  -> s"$BranchBase//changes",
        "GET"  -> s"$BranchBase/b/merge",
        "GET"  -> s"$BranchBase/b/other",
        "GET"  -> s"$BranchBase/b/changes/",
        "GET"  -> s"$BranchBase/b/changes/extra",
        "GET"  -> s"$BranchBase/b/diff/extra",
        "GET"  -> s"$BranchBase/b/schema-diff/extra",
        // A listed path under the wrong method.
        "GET"    -> "/api/branch/create",
        "GET"    -> "/api/branch/propose",
        "GET"    -> "/api/branch/discard",
        "DELETE" -> "/api/branch/discard",
        "PUT"    -> "/api/branch/create",
        "POST"   -> BranchBase,
        "POST"   -> s"$BranchBase/b",
        "DELETE" -> s"$BranchBase/b",
        "POST"   -> s"$BranchBase/b/changes",
        "PUT"    -> s"$BranchBase/b/diff",
        "POST"   -> s"$BranchBase/b/schema-diff"
      ).foreach { (method, path) =>
        val resp = send(h, method, path, Some(bob))
        withClue(s"$method $path body: ${resp.body()}") {
          resp.statusCode() shouldBe 403
          errorCode(resp.body()) should contain("admin_required")
        }
      }
    }

  it should "refuse no credential on the branch routes, in every spelling" in
    withBranchRoutes { h =>
      List(
        "POST" -> "/api/branch/create",
        "POST" -> "/api/branch/merge",
        "GET"  -> BranchBase,
        "POST" -> "/%61pi/branch/create",
        "POST" -> "////api/branch/create",
        "GET"  -> s"/%61pi${BranchBase.stripPrefix("/api")}",
        "GET"  -> s"////$BranchBase"
      ).foreach { (method, path) =>
        val resp = send(h, method, path, None)
        withClue(s"$method $path body: ${resp.body()}") {
          resp.statusCode() shouldBe 401
        }
      }
    }

  it should "leave another tenant's branch path to the handlers' gate, which refuses it" in
    withBranchRoutes { h =>
      val bob   = bobOf(h)
      val alice = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        tenant = Some(SecurityFixtures.TenantId)
      )
      val globex = s"/api/branch/tenant/${SecurityFixtures.GlobexTenantId}/database/" +
        s"${SecurityFixtures.GlobexTenantDbName}/branches"
      val body = s"""{"tenant":"${SecurityFixtures.GlobexTenantId}",""" +
        s""""tenantDb":"${SecurityFixtures.GlobexTenantDbName}","""
      // A plain member of acme, and an admin of acme: neither has anything to do with globex.
      List(bob, alice).foreach { token =>
        List(globex, s"$globex/b", s"$globex/b/changes").foreach { path =>
          val resp = get(h.httpClient, s"${h.baseUrl}$path", apiKey = Some(token))
          withClue(s"GET $path body: ${resp.body()}") {
            resp.statusCode() shouldBe 403
            errorCode(resp.body()) should contain("tenant_forbidden")
          }
        }
        val resp = post(
          h.httpClient,
          s"${h.baseUrl}/api/branch/create",
          body + """"name":"x"}""",
          apiKey = Some(token)
        )
        withClue(s"POST create body: ${resp.body()}") {
          resp.statusCode() shouldBe 403
          errorCode(resp.body()) should contain("tenant_forbidden")
        }
      }
      // A tenant that does not exist reads the same to a plain member.
      val ghost = get(
        h.httpClient,
        s"${h.baseUrl}/api/branch/tenant/ghost/database/d/branches",
        apiKey = Some(bob)
      )
      withClue(s"ghost body: ${ghost.body()}") {
        ghost.statusCode() shouldBe 403
        errorCode(ghost.body()) should contain("tenant_forbidden")
        // The very body another tenant gets, in words that say nothing of an admin grant.
        val other = get(h.httpClient, s"${h.baseUrl}$globex", apiKey = Some(bob))
        ghost.body().replace("ghost", "<t>") shouldBe
          other.body().replace(SecurityFixtures.GlobexTenantId, "<t>")
        ghost.body() should include("this credential cannot act on tenant 'ghost'")
        // An admin of acme is refused in the words that are true of them.
        val asAlice = get(h.httpClient, s"${h.baseUrl}$globex", apiKey = Some(alice))
        asAlice.body() should include("no admin grant")
      }
    }

  it should "still answer an admin of the tenant through the same gate, and a merge" in
    withBranchRoutes { h =>
      val alice = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        tenant = Some(SecurityFixtures.TenantId)
      )
      // The fixture database is no DuckLake one: past the guard, past the tenant, to the kind.
      val listed = get(h.httpClient, s"${h.baseUrl}$BranchBase", apiKey = Some(alice))
      withClue(s"list body: ${listed.body()}") {
        listed.statusCode() shouldBe 400
        errorCode(listed.body()) should contain("invalid_kind")
      }
      val merged = post(
        h.httpClient,
        s"${h.baseUrl}/api/branch/merge",
        s"""{"tenant":"${SecurityFixtures.TenantId}","tenantDb":"${SecurityFixtures.TenantDbName}",""" +
          """"branch":"x","proposalId":"mg"}""",
        apiKey = Some(alice)
      )
      withClue(s"merge body: ${merged.body()}") {
        merged.statusCode() shouldBe 400
        errorCode(merged.body()) should contain("invalid_kind")
      }
    }

  it should "keep admin sessions unrestricted" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(h.httpClient, s"${h.baseUrl}/api/pool/list", apiKey = Some(token))
      withClue(s"GET /api/pool/list body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
      }
    finally h.shutdown()
  }

  // ------------------------------------------------------------------
  // B. Public paths bypass the guard
  // ------------------------------------------------------------------

  "Public paths" should "allow POST /api/auth/login without a credential" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val body =
        s"""{"username":"${SecurityFixtures.RootUsername}","password":"${SecurityFixtures.RootPassword}"}"""
      val resp = post(h.httpClient, s"${h.baseUrl}/api/auth/login", body)
      // The request must reach the handler (not be blocked by apiKeyGuard).
      // A 200 login success satisfies this -- we don't require a specific
      // status beyond proving the guard didn't fire (which would be 401).
      withClue(s"POST /api/auth/login body: ${resp.body()}") {
        resp.statusCode() should not be 401
      }
    finally h.shutdown()
  }

  it should "allow POST /api/auth/change-password without a credential" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val body =
        s"""{"username":"${SecurityFixtures.RootUsername}","currentPassword":"${SecurityFixtures.RootPassword}","newPassword":"new-password"}"""
      val resp = post(h.httpClient, s"${h.baseUrl}/api/auth/change-password", body)
      // The harness never wires a changePasswordStore, so a request that
      // reaches the handler always answers 503 auth_disabled -- a JSON
      // body with an error code, unlike apiKeyGuard's bare, bodyless 401.
      // Asserting that exact handler-level shape (rather than merely "not
      // 401") proves the guard's isPublicApi arm actually let the request
      // through instead of coincidentally matching a would-be 401.
      withClue(s"POST /api/auth/change-password body: ${resp.body()}") {
        resp.statusCode() shouldBe 503
        errorCode(resp.body()) should contain("auth_disabled")
      }
    finally h.shutdown()
  }

  it should "allow POST /api/auth/forgot-password without a credential" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      // Anti-enumeration: a nonexistent account still answers 200 (the row lookup
      // misses and nothing is mailed). A 200 -- not the guard's bare 401 -- proves
      // the isPublicApi arm let the request reach the handler.
      val body = """{"username":"ghost-nobody"}"""
      val resp = post(h.httpClient, s"${h.baseUrl}/api/auth/forgot-password", body)
      withClue(s"POST /api/auth/forgot-password body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
      }
    finally h.shutdown()
  }

  it should "allow POST /api/auth/reset-password without a credential" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      // A garbage token reaches the handler and comes back 400 invalid_token --
      // a JSON error body, unlike apiKeyGuard's bare bodyless 401. Asserting that
      // handler-level shape proves the guard's isPublicApi arm actually admitted it.
      val body = """{"token":"garbage.not.a.jwt","newPassword":"whatever"}"""
      val resp = post(h.httpClient, s"${h.baseUrl}/api/auth/reset-password", body)
      withClue(s"POST /api/auth/reset-password body: ${resp.body()}") {
        resp.statusCode() shouldBe 400
        errorCode(resp.body()) should contain("invalid_token")
      }
    finally h.shutdown()
  }

  it should "allow GET /api/config/client without a credential" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val resp = get(h.httpClient, s"${h.baseUrl}/api/config/client")
      withClue(s"GET /api/config/client body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
      }
    finally h.shutdown()
  }

  it should "keep POST /api/fleet/heartbeat behind the guard outside fleet mode" in {
    val fix = SecurityFixtures.freshStore()
    // The harness boots runtimeType=local: the heartbeat is public at the guard only when
    // runtimeType=fleet, so an anonymous call here must get the guard's 401, not a 404 from
    // an unmounted route or anything the handler would answer.
    val h = ManagerServerHarness.boot(fix.store)
    try
      val body = """{"name":"srv-1","advertiseHost":"10.0.0.1","nodePort":21900,""" +
        """"node":{"assignmentEpoch":0,"state":"none"}}"""
      val resp = post(h.httpClient, s"${h.baseUrl}/api/fleet/heartbeat", body)
      withClue(s"POST /api/fleet/heartbeat body: ${resp.body()}") {
        resp.statusCode() shouldBe 401
      }
    finally h.shutdown()
  }

  it should "answer 400 fleet_disabled (not 404) on the fleet routes outside fleet mode" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val list  = get(h.httpClient, s"${h.baseUrl}/api/fleet/servers", apiKey = Some(token))
      withClue(s"GET /api/fleet/servers body: ${list.body()}") {
        list.statusCode() shouldBe 400
        parse(list.body()).toOption.flatMap(_.hcursor.get[String]("error").toOption) shouldBe
          Some("fleet_disabled")
      }
      // A superuser session passes the guard but must not be able to write a server row.
      val body = """{"name":"srv-1","advertiseHost":"10.0.0.1","nodePort":21900,""" +
        """"node":{"assignmentEpoch":0,"state":"none"}}"""
      val hb =
        post(h.httpClient, s"${h.baseUrl}/api/fleet/heartbeat", body, apiKey = Some(token))
      withClue(s"POST /api/fleet/heartbeat body: ${hb.body()}") {
        hb.statusCode() shouldBe 400
        parse(hb.body()).toOption.flatMap(_.hcursor.get[String]("error").toOption) shouldBe
          Some("fleet_disabled")
      }
    finally h.shutdown()
  }

  // ------------------------------------------------------------------
  // C. Login admin gate (AuthHandlers.login)
  // ------------------------------------------------------------------

  "AuthHandlers.login" should "return 200 with token + superuser=true for root" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val body =
        s"""{"username":"${SecurityFixtures.RootUsername}","password":"${SecurityFixtures.RootPassword}"}"""
      val resp = post(h.httpClient, s"${h.baseUrl}/api/auth/login", body)
      withClue(s"POST /api/auth/login body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        val cursor = parse(resp.body()).toOption.get.hcursor
        cursor.get[String]("token").toOption.getOrElse("") should not be empty
        cursor.get[Boolean]("superuser").toOption should contain(true)
        // role intentionally NOT in the response anymore; whoami carries it.
        cursor.get[String]("role").toOption shouldBe None
      }
    finally h.shutdown()
  }

  // The former "403 admin_required for bob" case is gone: a tenant-scoped login
  // by a role=user principal now mints a profile-only session (below). The
  // login gate's remaining admin_required arms (system login, OIDC scopes, a
  // tenant the principal holds no grant on) are unreachable over the wire for a
  // db tenant user -- authentication itself fails first with invalid_credentials
  // -- so they stay pinned at unit level in AuthHandlersSpec / AuthHandlersOidcSpec.
  it should "mint a non-admin session for bob on his own tenant" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val body =
        s"""{"username":"${SecurityFixtures.BobUsername}","password":"${SecurityFixtures.BobPassword}","tenant":"${SecurityFixtures.TenantId}"}"""
      val resp = post(h.httpClient, s"${h.baseUrl}/api/auth/login", body)
      withClue(s"POST /api/auth/login body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        val cursor = parse(resp.body()).toOption.get.hcursor
        cursor.get[String]("token").toOption.getOrElse("") should not be empty
        cursor.get[Boolean]("admin").toOption should contain(false)
        cursor.get[Boolean]("superuser").toOption should contain(false)
      }
    finally h.shutdown()
  }

  it should "return 401 invalid_credentials for a disabled user presenting the CORRECT password" in {
    val fix = SecurityFixtures.freshStore()
    val s   = fix.store
    // Disable root: re-upsert with the existing hash and enabled = false.
    val rootHash = s.getPasswordHash(None, SecurityFixtures.RootUsername).get
    s.upsertUserWithHash(
      tenant = None,
      username = SecurityFixtures.RootUsername,
      passwordHash = rootHash,
      kind = "admin",
      enabled = false
    )
    val h = ManagerServerHarness.boot(s)
    try
      val goodPw =
        s"""{"username":"${SecurityFixtures.RootUsername}","password":"${SecurityFixtures.RootPassword}"}"""
      val badPw =
        s"""{"username":"${SecurityFixtures.RootUsername}","password":"wrong-password"}"""
      val respGood = post(h.httpClient, s"${h.baseUrl}/api/auth/login", goodPw)
      val respBad  = post(h.httpClient, s"${h.baseUrl}/api/auth/login", badPw)
      withClue(s"disabled-user login body: ${respGood.body()}") {
        respGood.statusCode() shouldBe 401
        errorCode(respGood.body()) should contain("invalid_credentials")
      }
      // Same failure shape as a bad password: identical status + code, so the
      // response does not reveal whether the account is disabled or the
      // password was wrong.
      respBad.statusCode() shouldBe respGood.statusCode()
      errorCode(respBad.body()) shouldBe errorCode(respGood.body())
    finally h.shutdown()
  }

  it should "return 400 with code invalid_credentials for blank password" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val body = s"""{"username":"${SecurityFixtures.RootUsername}","password":""}"""
      val resp = post(h.httpClient, s"${h.baseUrl}/api/auth/login", body)
      withClue(s"POST /api/auth/login body: ${resp.body()}") {
        resp.statusCode() shouldBe 400
        errorCode(resp.body()) should contain("invalid_credentials")
      }
    finally h.shutdown()
  }

  it should "refuse a password login as invalid credentials when no provider is configured" in {
    // A booted manager always has a provider (boot refuses an empty chain): a service without one
    // takes no password, like any password nobody verifies.
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, enableProviders = false)
    try
      val body =
        s"""{"username":"${SecurityFixtures.RootUsername}","password":"${SecurityFixtures.RootPassword}"}"""
      val resp = post(h.httpClient, s"${h.baseUrl}/api/auth/login", body)
      withClue(s"POST /api/auth/login body: ${resp.body()}") {
        resp.statusCode() shouldBe 401
        errorCode(resp.body()) should contain("invalid_credentials")
      }
    finally h.shutdown()
  }

  // ------------------------------------------------------------------
  // D. Superuser gate on /api/config/server and /api/manifest/*
  // ------------------------------------------------------------------

  "ConfigHandlers /api/config/server" should "admit root's superuser session" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(h.httpClient, s"${h.baseUrl}/api/config/server", apiKey = Some(token))
      withClue(s"GET /api/config/server body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
      }
    finally h.shutdown()
  }

  it should "deny alice's tenant-scoped session with 403 superuser_required" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        tenant = Some(SecurityFixtures.TenantId)
      )
      val resp = get(h.httpClient, s"${h.baseUrl}/api/config/server", apiKey = Some(token))
      withClue(s"GET /api/config/server body: ${resp.body()}") {
        resp.statusCode() shouldBe 403
        errorCode(resp.body()) should contain("superuser_required")
      }
    finally h.shutdown()
  }

  /** A harness whose PAT routes run on a throwaway Postgres database (`qodstate_pat` has an FK to
    * `qodstate_user`), dropped afterwards; cancelled when Postgres is unreachable. For the security
    * audit, which is outside `EndpointModules.all` and so outside SuperuserPatCompletenessSpec.
    */
  private def withAuditPatHarness(body: ManagerServerHarness.Harness => Unit): Unit =
    import ai.starlake.quack.ondemand.state.testkit.TestPostgres
    import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PatStore, UserGrant, UserStore}
    TestPostgres.ensureReachable()
    TestPostgres.dropStrayTestDatabases("qodauditpat")
    val dbName = s"qodauditpat_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val users = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      val pats  = new PatStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try
        users.upsertUser(
          None,
          SecurityFixtures.RootUsername,
          SecurityFixtures.RootPassword,
          "admin"
        )
        val fix = SecurityFixtures.freshStore()
        val h   = ManagerServerHarness.boot(
          fix.store,
          patStore = Some(pats),
          patUserOf =
            Some((tenant, username) => users.userIdOf(tenant, username).flatMap(users.userById)),
          patAuth = Some(
            new ai.starlake.quack.ondemand.auth.PatAuthenticator(
              pats,
              users.userById,
              u => List(UserGrant(u.tenant, u.kind))
            )
          )
        )
        try body(h)
        finally h.shutdown()
      finally
        pats.close()
        users.close()
    finally
      scala.util.Try(TestPostgres.dropDatabase(dbName))
      ()

  "SecurityAuditHandlers /api/security/audit" should "answer root's superuser session with every check" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(h.httpClient, s"${h.baseUrl}/api/security/audit", apiKey = Some(token))
      withClue(s"body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        val body     = io.circe.parser.parse(resp.body()).toOption.get.hcursor
        val findings = body.downField("findings").values.get.toList
        val ids      = findings.flatMap(_.hcursor.get[String]("id").toOption)
        ids.toSet shouldBe ai.starlake.quack.ondemand.security.checks.SecurityChecks.all
          .map(_.id)
          .toSet
        body.get[String]("generatedAt").toOption.exists(_.nonEmpty) shouldBe true
        body.get[String]("replica").toOption.exists(_.nonEmpty) shouldBe true
        body.get[String]("version").toOption.exists(_.nonEmpty) shouldBe true
        val summary = body.downField("summary")
        val counted = List("critical", "warn", "ok", "na", "error")
          .map(k => summary.get[Int](k).toOption.get)
          .sum
        counted shouldBe findings.size
      }
    finally h.shutdown()
  }

  it should "answer root's session sent as the qod_session cookie" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = getWithCookie(h.httpClient, s"${h.baseUrl}/api/security/audit", token)
      withClue(s"body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
      }
    finally h.shutdown()
  }

  it should "answer a superuser PAT" in withAuditPatHarness { h =>
    val resp =
      get(h.httpClient, s"${h.baseUrl}/api/security/audit", apiKey = Some(h.superuserPat()))
    withClue(s"body: ${resp.body()}") {
      resp.statusCode() shouldBe 200
    }
  }

  it should "deny a superuser PAT minted with dropAdmin with 403" in withAuditPatHarness { h =>
    val resp = get(
      h.httpClient,
      s"${h.baseUrl}/api/security/audit",
      apiKey = Some(h.superuserPat(dropAdmin = true))
    )
    withClue(s"body: ${resp.body()}") {
      resp.statusCode() shouldBe 403
      errorCode(resp.body()) should contain("admin_required")
    }
  }

  it should "deny alice's tenant-scoped session with 403 superuser_required" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        tenant = Some(SecurityFixtures.TenantId)
      )
      val resp = get(h.httpClient, s"${h.baseUrl}/api/security/audit", apiKey = Some(token))
      withClue(s"body: ${resp.body()}") {
        resp.statusCode() shouldBe 403
        errorCode(resp.body()) should contain("superuser_required")
      }
    finally h.shutdown()
  }

  it should "refuse a request with no credential with 401" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try get(h.httpClient, s"${h.baseUrl}/api/security/audit").statusCode() shouldBe 401
    finally h.shutdown()
  }

  "ManifestHandlers /api/manifest/export" should "admit root's superuser session" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      val resp  = get(h.httpClient, s"${h.baseUrl}/api/manifest/export", apiKey = Some(token))
      withClue(s"GET /api/manifest/export body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
      }
    finally h.shutdown()
  }

  it should "deny alice's tenant-scoped session with 403 superuser_required" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        tenant = Some(SecurityFixtures.TenantId)
      )
      val resp = get(h.httpClient, s"${h.baseUrl}/api/manifest/export", apiKey = Some(token))
      withClue(s"GET /api/manifest/export body: ${resp.body()}") {
        resp.statusCode() shouldBe 403
        errorCode(resp.body()) should contain("superuser_required")
      }
    finally h.shutdown()
  }

  "ManifestHandlers /api/manifest/import" should "admit root's superuser session (200 or 400, not 403)" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val token = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
      // Minimal valid-shape manifest body. The handler may accept or reject
      // the body content -- we only care that the security check passes (no 403).
      val yamlBody =
        """apiVersion: quack-on-demand/v1
          |kind: ConfigManifest
          |exportedAt: '2026-06-10T00:00:00Z'
          |exportedFrom: { managerVersion: test, hostname: localhost }
          |""".stripMargin
      val resp = post(
        h.httpClient,
        s"${h.baseUrl}/api/manifest/import",
        yamlBody,
        contentType = "application/yaml",
        apiKey = Some(token)
      )
      withClue(s"POST /api/manifest/import body: ${resp.body()}") {
        resp.statusCode() should not be 403
      }
    finally h.shutdown()
  }

  it should "deny alice's tenant-scoped session with 403 before parsing the body" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        tenant = Some(SecurityFixtures.TenantId)
      )
      // Syntactically invalid body -- the 403 must fire before the parser runs.
      val invalidYaml = "this is not valid yaml: : :"
      val resp        = post(
        h.httpClient,
        s"${h.baseUrl}/api/manifest/import",
        invalidYaml,
        contentType = "application/yaml",
        apiKey = Some(token)
      )
      withClue(s"POST /api/manifest/import body: ${resp.body()}") {
        resp.statusCode() shouldBe 403
        errorCode(resp.body()) should contain("superuser_required")
      }
    finally h.shutdown()
  }

  // ------------------------------------------------------------------
  // Session expiring between the guard's check and a handler's re-check
  // ------------------------------------------------------------------
  //
  // The guard admits a live tenant-admin session; every later lookup of the same token (the
  // guard's own second lookup, then the handler's) runs after expiry. An unresolvable token used
  // to read as "static key" (scope None = unrestricted) at those later checks.

  /** A session signer whose clock, once `armed.set(n)`, answers `n` more live reads and then jumps
    * past every session's expiry.
    */
  private final class ExpiringClock:
    private val t0                   = java.time.Instant.parse("2026-09-27T10:00:00Z")
    val armed                        = new java.util.concurrent.atomic.AtomicInteger(-1)
    val now: () => java.time.Instant = () =>
      if armed.getAndUpdate(n => if n > 0 then n - 1 else n) == 0 then t0.plusSeconds(24 * 3600)
      else t0

  "a tenant-admin session expiring mid-request" should
    "not reach the superuser-only manifest export" in {
      val fix   = SecurityFixtures.freshStore()
      val clock = new ExpiringClock
      val h     = ManagerServerHarness.boot(
        fix.store,
        sessions = new ai.starlake.quack.ondemand.api.SessionTokenStore(clock = clock.now)
      )
      try
        val token = h.mintToken(
          SecurityFixtures.AliceUsername,
          SecurityFixtures.AlicePassword,
          tenant = Some(SecurityFixtures.TenantId)
        )
        // The guard's first lookup sees the live session; everything after sees it expired.
        clock.armed.set(1)
        val resp = get(h.httpClient, s"${h.baseUrl}/api/manifest/export", apiKey = Some(token))
        withClue(s"GET /api/manifest/export body: ${resp.body()}") {
          resp.statusCode() should (be(401) or be(403))
          resp.body() should not include "tenants:"
        }
      finally h.shutdown()
    }

  it should "not widen a tenant-filtered listing to every tenant" in {
    val fix = SecurityFixtures.freshStore()
    SecurityFixtures.addGlobexTenant(fix.store)
    val clock = new ExpiringClock
    val h     = ManagerServerHarness.boot(
      fix.store,
      sessions = new ai.starlake.quack.ondemand.api.SessionTokenStore(clock = clock.now)
    )
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        tenant = Some(SecurityFixtures.TenantId)
      )
      // Control: with the session live, alice sees her own tenant only.
      val live = get(h.httpClient, s"${h.baseUrl}/api/tenant/list", apiKey = Some(token))
      live.body() should include(SecurityFixtures.TenantName)
      live.body() should not include SecurityFixtures.GlobexTenantName
      clock.armed.set(1)
      val resp = get(h.httpClient, s"${h.baseUrl}/api/tenant/list", apiKey = Some(token))
      withClue(s"GET /api/tenant/list body: ${resp.body()}") {
        resp.body() should not include SecurityFixtures.GlobexTenantName
      }
    finally h.shutdown()
  }

  // ------------------------------------------------------------------
  // Request body limits: in size and in time, per route
  // ------------------------------------------------------------------

  "The REST body limits" should "refuse a public body over its limit with 413" in {
    val fix = SecurityFixtures.freshStore()
    val h   =
      ManagerServerHarness.boot(fix.store, restLimits = RestLimitsConfig(maxPublicBodyBytes = 1024))
    try
      val resp = post(h.httpClient, s"${h.baseUrl}/api/auth/login", "x" * 1025)
      resp.statusCode() shouldBe 413
      errorCode(resp.body()) should contain("payload_too_large")
    finally h.shutdown()
  }

  it should "let an admitted caller send more than a public route takes, up to its own limit" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(
      fix.store,
      restLimits = RestLimitsConfig(maxBodyBytes = 4096, maxPublicBodyBytes = 1024)
    )
    try
      val session = Some(h.superuserSession())
      val within = post(h.httpClient, s"${h.baseUrl}/api/tenant/list", " " * 2048, apiKey = session)
      within.statusCode() should not be 413
      val over = post(h.httpClient, s"${h.baseUrl}/api/tenant/list", " " * 4097, apiKey = session)
      over.statusCode() shouldBe 413
      errorCode(over.body()) should contain("payload_too_large")
    finally h.shutdown()
  }

  it should "give a module's public route a limit of its own" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(
      fix.store,
      modulePublicPrefixes = Set("/api/hosted/signup"),
      restLimits = RestLimitsConfig(
        maxBodyBytes = 16384,
        maxPublicBodyBytes = 1024,
        maxModulePublicBodyBytes = 4096
      )
    )
    try
      // Over a core public route's limit, within the module's.
      post(h.httpClient, s"${h.baseUrl}/api/hosted/signup", "x" * 2048)
        .statusCode() should not be 413
      post(h.httpClient, s"${h.baseUrl}/api/auth/login", "x" * 2048).statusCode() shouldBe 413
      // Over the module's, though within an admitted caller's.
      post(h.httpClient, s"${h.baseUrl}/api/hosted/signup", "x" * 4097).statusCode() shouldBe 413
    finally h.shutdown()
  }

  it should "limit a module's public route to 1 MiB by default, not to an admitted caller's 16 MiB" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, modulePublicPrefixes = Set("/api/hosted/signup"))
    try
      val oneMiB = 1048576
      // Over a core public route's 64 KiB, within the module's limit.
      post(h.httpClient, s"${h.baseUrl}/api/hosted/signup", "x" * oneMiB)
        .statusCode() should not be 413
      val over = post(h.httpClient, s"${h.baseUrl}/api/hosted/signup", "x" * (oneMiB + 1))
      over.statusCode() shouldBe 413
      errorCode(over.body()) should contain("payload_too_large")
    finally h.shutdown()
  }

  it should "answer 408 when a public body stops arriving" in {
    val fix = SecurityFixtures.freshStore()
    val h = ManagerServerHarness.boot(fix.store, restLimits = RestLimitsConfig(bodyTimeoutSec = 1))
    try
      val (status, head) = raw(
        h.baseUrl,
        "POST /api/auth/login HTTP/1.1\r\nHost: l\r\nContent-Type: application/json\r\n" +
          "Content-Length: 100\r\n\r\n{"
      )
      status should include("408")
      head should include("connection: close")
    finally h.shutdown()
  }

  it should "limit a body however the client frames it" in {
    val fix = SecurityFixtures.freshStore()
    val h   =
      ManagerServerHarness.boot(fix.store, restLimits = RestLimitsConfig(maxPublicBodyBytes = 16))
    try
      val body = "x" * 32
      List(
        s"Content-Length: 32\r\n\r\n$body",
        s"Content-Length: +32\r\n\r\n$body",
        s"Content-Length: 0\r\nContent-Length: 32\r\n\r\n$body"
      ).foreach { framing =>
        val (status, _) = raw(h.baseUrl, s"POST /api/auth/login HTTP/1.1\r\nHost: l\r\n$framing")
        withClue(framing.takeWhile(_ != '\r'))(status should include("413"))
      }
      // Chunked framing costs CPU per read before a limit counts anything: refused unread.
      val chunked = s"\r\n\r\n20\r\n$body\r\n0\r\n\r\n"
      List("chunked", "foo, chunked", "xchunked").foreach { coding =>
        val (status, head) =
          raw(
            h.baseUrl,
            s"POST /api/auth/login HTTP/1.1\r\nHost: l\r\nTransfer-Encoding: $coding$chunked"
          )
        withClue(coding) {
          status should include("411")
          head should include("connection: close")
        }
      }
    finally h.shutdown()
  }

  it should "limit an admitted caller's chunked body too" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, restLimits = RestLimitsConfig(maxBodyBytes = 16))
    try
      val body        = "x" * 32
      val session     = h.superuserSession()
      val (status, _) = raw(
        h.baseUrl,
        s"POST /api/tenant/list HTTP/1.1\r\nHost: l\r\nX-API-Key: $session\r\n" +
          s"Transfer-Encoding: chunked\r\n\r\n20\r\n$body\r\n0\r\n\r\n"
      )
      status should include("413")
    finally h.shutdown()
  }

  it should "answer 408 when a body the headers hide stops arriving" in {
    val fix = SecurityFixtures.freshStore()
    val h = ManagerServerHarness.boot(fix.store, restLimits = RestLimitsConfig(bodyTimeoutSec = 1))
    try
      val (status, _) = raw(
        h.baseUrl,
        "POST /api/auth/login HTTP/1.1\r\nHost: l\r\nContent-Length: +100\r\n\r\n{"
      )
      status should include("408")
    finally h.shutdown()
  }

  it should "still serve a request with no body" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val session = Some(h.superuserSession())
      get(h.httpClient, s"${h.baseUrl}/api/tenant/list", apiKey = session).statusCode() shouldBe 200
    finally h.shutdown()
  }

  it should "not wait long for the body of a refused request" in {
    val fix = SecurityFixtures.freshStore()
    val h = ManagerServerHarness.boot(fix.store, restLimits = RestLimitsConfig(bodyTimeoutSec = 1))
    try
      val start          = System.nanoTime()
      val (status, head) = raw(
        h.baseUrl,
        "POST /api/tenant/list HTTP/1.1\r\nHost: l\r\nContent-Length: 100\r\n\r\n{"
      )
      status should include("401")
      head should include("connection: close")
      (System.nanoTime() - start) / 1000000000L should be < 5L
    finally h.shutdown()
  }

  it should "refuse a huge body on a guarded path without reading it" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val start          = System.nanoTime()
      val (status, head) = raw(
        h.baseUrl,
        "POST /api/tenant/list HTTP/1.1\r\nHost: l\r\nContent-Length: 268435456\r\n\r\n"
      )
      status should include("401")
      head should include("connection: close")
      (System.nanoTime() - start) / 1000000000L should be < 4L
    finally h.shutdown()
  }

  it should "leave unread a refused body whose length it cannot trust" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      List("Content-Length: 1\r\nContent-Length: 100", "Content-Length: +100").foreach { framing =>
        val start          = System.nanoTime()
        val (status, head) =
          raw(h.baseUrl, s"POST /api/tenant/list HTTP/1.1\r\nHost: l\r\n$framing\r\n\r\n{")
        withClue(framing) {
          status should include("401")
          head should include("connection: close")
          // A drain would wait its 5 s for a body that never comes.
          (System.nanoTime() - start) / 1000000000L should be < 4L
        }
      }
    finally h.shutdown()
  }

  it should "wait at most 5 s for a refused body, whatever the body timeout" in {
    val fix = SecurityFixtures.freshStore()
    val h   =
      ManagerServerHarness.boot(fix.store, restLimits = RestLimitsConfig(bodyTimeoutSec = 30))
    try
      val start          = System.nanoTime()
      val (status, head) = raw(
        h.baseUrl,
        "POST /api/tenant/list HTTP/1.1\r\nHost: l\r\nContent-Length: 100\r\n\r\n{"
      )
      status should include("401")
      head should include("connection: close")
      (System.nanoTime() - start) / 1000000000L should be < 10L
    finally h.shutdown()
  }

  it should "hand an admitted caller's chunked body to its route" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val session = h.superuserSession()
      val yaml    =
        "apiVersion: quack-on-demand/v1\nkind: ConfigManifest\nexportedAt: '2026-06-10T00:00:00Z'\n" +
          "exportedFrom: { managerVersion: test, hostname: localhost }\n"
      val head = s"POST /api/manifest/import HTTP/1.1\r\nHost: l\r\nX-API-Key: $session\r\n" +
        "Content-Type: application/yaml\r\nConnection: close\r\n"
      val (plain, _)   = raw(h.baseUrl, head + s"Content-Length: ${yaml.length}\r\n\r\n$yaml")
      val (chunked, _) = raw(
        h.baseUrl,
        head +
          s"Transfer-Encoding: chunked\r\n\r\n${Integer.toHexString(yaml.length)}\r\n$yaml\r\n0\r\n\r\n"
      )
      plain should include("200")
      chunked shouldBe plain
    finally h.shutdown()
  }

  /** One `GET /health` on `sock`, left unanswered by this call. */
  private def sendHealth(sock: java.net.Socket): Unit =
    sock.getOutputStream.write("GET /health HTTP/1.1\r\nHost: l\r\n\r\n".getBytes(UTF_8))
    sock.getOutputStream.flush()

  "The REST connection limit" should "hold a client until a connection leaves, then answer it" in {
    val fix = SecurityFixtures.freshStore()
    val h = ManagerServerHarness.boot(fix.store, restLimits = RestLimitsConfig(maxConnections = 1))
    try
      val uri = java.net.URI.create(h.baseUrl)

      // One slot, held by a connection that is answered and then kept open.
      val holder = new java.net.Socket(uri.getHost, uri.getPort)
      try
        holder.setSoTimeout(15000)
        sendHealth(holder)
        rawStatus(new java.io.BufferedInputStream(holder.getInputStream)) shouldBe 200
        val client = new java.net.Socket(uri.getHost, uri.getPort)
        try
          sendHealth(client)
          // The client connects and sends its request, and is not answered while the slot is taken.
          client.setSoTimeout(1500)
          a[java.net.SocketTimeoutException] should be thrownBy client.getInputStream.read()
          // Once the holder leaves, the slot is free and the client is answered.
          holder.close()
          client.setSoTimeout(15000)
          rawStatus(new java.io.BufferedInputStream(client.getInputStream)) shouldBe 200
        finally client.close()
      finally holder.close()
    finally h.shutdown()
  }

  it should "take no more connections than a fifth of the heap holds, however many it is set to" in {
    // A public body limit of 2 GiB makes a connection able to hold more than a fifth of any test
    // heap, so the listener takes the one connection `fit` leaves it of the 1024 it is set to.
    // That is ConnectionBudget.slots at work: handed the 1024 itself, it would answer them all.
    val limits = RestLimitsConfig(maxConnections = 1024, maxPublicBodyBytes = Int.MaxValue - 8L)
    val slots  = ConnectionBudget.fit(
      1024,
      ConnectionBudget.heldBytes(limits.maxHeaderBytes, limits.maxPublicBodyBytes, tls = false),
      Runtime.getRuntime.maxMemory
    )
    slots should be < 64
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store, restLimits = limits)
    try
      val uri = java.net.URI.create(h.baseUrl)
      // Every slot, taken by a connection that is answered and then kept open.
      val holders = List.fill(slots) {
        val holder = new java.net.Socket(uri.getHost, uri.getPort)
        holder.setSoTimeout(15000)
        sendHealth(holder)
        rawStatus(new java.io.BufferedInputStream(holder.getInputStream)) shouldBe 200
        holder
      }
      try
        val client = new java.net.Socket(uri.getHost, uri.getPort)
        try
          sendHealth(client)
          client.setSoTimeout(1500)
          a[java.net.SocketTimeoutException] should be thrownBy client.getInputStream.read()
          // Once a holder leaves, a slot is free and the client is answered.
          holders.head.close()
          client.setSoTimeout(15000)
          rawStatus(new java.io.BufferedInputStream(client.getInputStream)) shouldBe 200
        finally client.close()
      finally holders.foreach(_.close())
    finally h.shutdown()
  }

  /** Whether a further client is answered while `held` connections, each answered and kept open,
    * hold the slots of the server at `baseUrl`.
    */
  private def answeredWhileHolding(baseUrl: String, held: Int): Boolean =
    val uri     = java.net.URI.create(baseUrl)
    val holders = List.fill(held) {
      val holder = new java.net.Socket(uri.getHost, uri.getPort)
      holder.setSoTimeout(15000)
      sendHealth(holder)
      rawStatus(new java.io.BufferedInputStream(holder.getInputStream)) shouldBe 200
      holder
    }
    try
      val client = new java.net.Socket(uri.getHost, uri.getPort)
      try
        sendHealth(client)
        client.setSoTimeout(1500)
        try
          client.getInputStream.read()
          true
        catch case _: java.net.SocketTimeoutException => false
      finally client.close()
    finally holders.foreach(_.close())

  it should "count the larger public body limit of a module and the core when a module has public routes, else the core's" in {
    def slotsAt(bodyBytes: Long, limits: RestLimitsConfig): Int =
      ConnectionBudget.fit(
        limits.maxConnections,
        ConnectionBudget.heldBytes(limits.maxHeaderBytes, bodyBytes, tls = false),
        Runtime.getRuntime.maxMemory
      )
    def answered(limits: RestLimitsConfig, modulePublic: Set[String], held: Int): Boolean =
      val h = ManagerServerHarness.boot(
        SecurityFixtures.freshStore().store,
        restLimits = limits,
        modulePublicPrefixes = modulePublic
      )
      try answeredWhileHolding(h.baseUrl, held)
      finally h.shutdown()
    val huge    = Int.MaxValue - 8L
    val module  = Set("/api/hosted/signup")
    val bigMod  = RestLimitsConfig(maxConnections = 1024, maxModulePublicBodyBytes = huge)
    val bigCore = RestLimitsConfig(maxConnections = 1024, maxPublicBodyBytes = huge)

    // A module has public routes and its limit is the larger: a connection may hold 2 GiB.
    val modSlots = slotsAt(bigMod.maxModulePublicBodyBytes, bigMod)
    modSlots should be < 64
    answered(bigMod, module, modSlots) shouldBe false
    // The core's limit is the larger: the same.
    val coreSlots = slotsAt(bigCore.maxPublicBodyBytes, bigCore)
    coreSlots should be < 64
    answered(bigCore, module, coreSlots) shouldBe false
    // No module has public routes: no route reads the module's limit, so it counts for nothing.
    answered(bigMod, Set.empty, modSlots) shouldBe true
  }

  // /metrics is public (a scraper holds no credential) and is mounted from the harness's own
  // endpoint, so these cases pin the mount outside the guard, the interpreter's options, and the
  // Accept answer through the real server: none of them is visible to a spec of the endpoint alone.
  "The REST metrics route" should "answer a scraper that holds no credential with the whole scrape, its length and its media type" in {
    val prom = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    prom.counter("statements_total", "tenant", "acme", "pool", "sales", "status", "ok").increment()
    val expected = prom.scrape().getBytes(UTF_8)
    val scrapes  = new java.util.concurrent.atomic.AtomicInteger(0)
    val fix      = SecurityFixtures.freshStore()
    val h        = ManagerServerHarness.boot(
      fix.store,
      metricsEndpoint = new MetricsEndpoint(Some(prom), () => scrapes.incrementAndGet())
    )
    try
      val uri  = java.net.URI.create(h.baseUrl)
      val sock = new java.net.Socket(uri.getHost, uri.getPort)
      try
        sock.setSoTimeout(15000)
        val in = new java.io.BufferedInputStream(sock.getInputStream)
        // Two scrapes on one connection, as Prometheus keeps one, with no credential and the
        // Accept header of the scrapers that ask for plain text.
        val answers = List.fill(2) {
          sock.getOutputStream.write(
            "GET /metrics HTTP/1.1\r\nHost: l\r\nAccept: text/plain\r\n\r\n".getBytes(UTF_8)
          )
          sock.getOutputStream.flush()
          rawResponse(in)
        }
        answers.foreach { (status, headers, body) =>
          status shouldBe 200
          headers("content-type") shouldBe "text/plain; version=0.0.4; charset=utf-8"
          headers("content-length") shouldBe expected.length.toString
          headers.get("transfer-encoding") shouldBe None
          body shouldBe expected
        }
        // The two scrapes shared one.
        scrapes.get() shouldBe 1
      finally sock.close()
    finally h.shutdown()
  }

  /** What the server sent back for a request, and whether it then closed the connection. */
  private final case class Reply(text: String, closed: Boolean)

  /** Everything the server sends back for `request`, written whole to a raw socket and read to the
    * end of the connection, or to a 15 s timeout (`closed` is then false). A close that reaches a
    * client still writing can arrive as a reset: what came before it is kept. `halfClose` shuts the
    * client's sending side after the request, as a client that hung up mid-request does, and leaves
    * it able to read the answer.
    */
  private def rawAll(baseUrl: String, request: String, halfClose: Boolean = false): Reply =
    val uri  = java.net.URI.create(baseUrl)
    val sock = new java.net.Socket(uri.getHost, uri.getPort)
    try
      sock.setSoTimeout(15000)
      sock.getOutputStream.write(request.getBytes(UTF_8))
      sock.getOutputStream.flush()
      if halfClose then sock.shutdownOutput()
      val received = new java.io.ByteArrayOutputStream()
      val buffer   = new Array[Byte](4096)
      val in       = sock.getInputStream
      val closed   =
        try
          Iterator
            .continually(in.read(buffer))
            .takeWhile(_ >= 0)
            .foreach(received.write(buffer, 0, _))
          true
        catch
          case _: java.net.SocketTimeoutException               => false
          case _: java.net.SocketException if received.size > 0 => true
      Reply(new String(received.toByteArray, UTF_8), closed)
    finally sock.close()

  /** `GET /health` with a request line and one extra header of `pad` bytes each, asking for the
    * connection to be closed after the answer.
    */
  private def healthCall(path: String = "/health", pad: Int = 0): String =
    s"GET $path HTTP/1.1\r\nHost: l\r\n" + (if pad > 0 then s"X-Pad: ${"a" * pad}\r\n" else "") +
      "Connection: close\r\n\r\n"

  // What bounds a request's line and headers (RestLimitsConfig.maxHeaderBytes): Ember parses both
  // into objects before any route runs and holds them for the whole request, and a client needs no
  // credential to send them.
  "The REST header limit" should "answer a header block over it 431 and close, and serve one just inside" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val limit = RestLimitsConfig().maxHeaderBytes
      val over  = rawAll(h.baseUrl, healthCall(pad = limit))
      over.text should startWith("HTTP/1.1 431")
      over.text.toLowerCase should include("connection: close")
      over.text should include(""""error":"headers_too_large"""")
      over.text should include(
        s"request line or headers exceed $limit bytes (QOD_REST_MAX_HEADER_BYTES)"
      )
      over.closed shouldBe true
      rawAll(h.baseUrl, healthCall(pad = limit - 512)).text should startWith("HTTP/1.1 200")
    finally h.shutdown()
  }

  it should "answer a request line over it 431 and close, and serve one just inside" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val limit = RestLimitsConfig().maxHeaderBytes
      val over  = rawAll(h.baseUrl, healthCall(path = "/health?x=" + "a" * limit))
      over.text should startWith("HTTP/1.1 431")
      over.text.toLowerCase should include("connection: close")
      over.text should include(""""error":"headers_too_large"""")
      over.text should include("(QOD_REST_MAX_HEADER_BYTES)")
      over.closed shouldBe true
      val inside = rawAll(h.baseUrl, healthCall(path = "/health?x=" + "a" * (limit - 512)))
      inside.text should startWith("HTTP/1.1 200")
    finally h.shutdown()
  }

  it should "take the limit from its configuration" in {
    val fix = SecurityFixtures.freshStore()
    val h   =
      ManagerServerHarness.boot(fix.store, restLimits = RestLimitsConfig(maxHeaderBytes = 2048))
    try
      rawAll(h.baseUrl, healthCall(pad = 3000)).text should startWith("HTTP/1.1 431")
      rawAll(h.baseUrl, healthCall(pad = 1000)).text should startWith("HTTP/1.1 200")
    finally h.shutdown()
  }

  it should "answer an error it does not match as Ember does, a bare 500" in {
    // The request fails to parse with an error that is not the limit's: the client hangs up in
    // the middle of its headers, or sends a Content-Length that is no number. Ember applies a
    // partial error handler to it with no guard: one that matched only the limit would drop the
    // connection with no answer, where Ember with no handler answers 500.
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val hungUp = rawAll(h.baseUrl, "GET /health HTTP/1.1\r\nHost: l\r\nX-Pa", halfClose = true)
      hungUp.text should startWith("HTTP/1.1 500")
      hungUp.text.toLowerCase should include("content-length: 0")
      hungUp.closed shouldBe true
      val unreadable =
        rawAll(h.baseUrl, "GET /health HTTP/1.1\r\nHost: l\r\nContent-Length: abc\r\n\r\n")
      unreadable.text should startWith("HTTP/1.1 500")
      unreadable.closed shouldBe true
    finally h.shutdown()
  }

  // A depth that overflows the stack must never go through this harness: a regression would then
  // shut down the test JVM's cats-effect runtime for every later spec. These cases use the depth of
  // the limit, so a regression fails them and halts nothing.
  "A request body nested too deep" should "be refused before any route decodes it" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val deep = "[" * (BoundedBody.MaxNesting + 1) + "]" * (BoundedBody.MaxNesting + 1)
      List(
        (s"${h.baseUrl}/api/auth/login", deep, None),
        (s"${h.baseUrl}/api/auth/login", s"""{"username":$deep,"password":"p"}""", None),
        (s"${h.baseUrl}/api/tenant/create", deep, Some(h.superuserSession()))
      ).foreach { (url, body, apiKey) =>
        val resp = post(h.httpClient, url, body, apiKey = apiKey)
        withClue(url) {
          resp.statusCode() shouldBe 400
          resp.body() should include("json_too_deep")
        }
      }
      get(h.httpClient, s"${h.baseUrl}/health").statusCode() shouldBe 200
    finally h.shutdown()
  }

  it should "leave a body within the limit to its route" in {
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(fix.store)
    try
      val login = s"${h.baseUrl}/api/auth/login"
      val deep  = "[" * BoundedBody.MaxNesting + "]" * BoundedBody.MaxNesting
      post(h.httpClient, login, deep).body() should not include "json_too_deep"
      // Brackets in a string are no nesting: this is only a wrong password.
      val inString = s"""{"username":"nobody","password":"${"[" * 1000}"}"""
      post(h.httpClient, login, inString).statusCode() shouldBe 401
    finally h.shutdown()
  }
