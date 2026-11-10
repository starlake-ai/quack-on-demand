// src/test/scala/ai/starlake/quack/security/ReadOnlyCeilingTokenSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.mcp.McpRoutes
import ai.starlake.quack.ondemand.auth.{PatAuthenticator, TokenRestriction}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PatStore, UserGrant, UserStore}
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditQuery, AuditRecorder}
import io.circe.parser.parse
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.net.http.{HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets

/** `verbCeiling: RO` means read-only everywhere: SQL as before, and now the admin REST routes and
  * the MCP tools too. Reads stay open; every write is refused, whatever its spelling.
  */
class ReadOnlyCeilingTokenSpec
    extends AnyFlatSpec
    with Matchers
    with SecurityHttpHelpers
    with BeforeAndAfterAll:

  TestPostgres.dropStrayTestDatabases("qodpatceil")

  private val dbName = s"qodpatceil_test_${System.nanoTime()}"

  private var users: UserStore          = null
  private var pats: PatStore            = null
  private var patAuth: PatAuthenticator = null

  override def beforeAll(): Unit =
    if TestPostgres.reachable then
      TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      users = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      pats = new PatStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      patAuth = new PatAuthenticator(pats, users.userById, u => List(UserGrant(u.tenant, u.kind)))
      users.upsertUser(None, SecurityFixtures.RootUsername, SecurityFixtures.RootPassword, "admin")

  override def afterAll(): Unit =
    if pats != null then pats.close()
    if users != null then users.close()

  private def ceiling(verb: String) =
    TokenRestriction.Unrestricted.copy(verbCeiling = Some(verb))

  private def mint(restriction: TokenRestriction): String =
    val uid = users.userIdOf(None, SecurityFixtures.RootUsername).get
    pats.mint(uid, s"ceil-${System.nanoTime()}", restriction, None, 0)._2

  private def withHarness(
      telemetry: RecordingTelemetryStore = new RecordingTelemetryStore
  )(body: ManagerServerHarness.Harness => Unit): Unit =
    TestPostgres.ensureReachable()
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(
      fix.store,
      audit = new AuditRecorder(telemetry, _ => None),
      telemetryStore = telemetry,
      patStore = Some(pats),
      patUserOf =
        Some((tenant, username) => users.userIdOf(tenant, username).flatMap(users.userById)),
      patAuth = Some(patAuth),
      mcpEnabled = true
    )
    try body(h)
    finally h.shutdown()

  private def send(
      h: ManagerServerHarness.Harness,
      method: String,
      path: String,
      token: String
  ): HttpResponse[String] =
    val request = HttpRequest
      .newBuilder(URI.create(s"${h.baseUrl}$path"))
      .header("Content-Type", "application/json")
      .header("X-API-Key", token)
      .method(method, HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
      .timeout(RequestTimeout)
      .build()
    h.httpClient.send(request, HttpResponse.BodyHandlers.ofString())

  private def mcp(h: ManagerServerHarness.Harness, token: String, body: String) =
    val request = HttpRequest
      .newBuilder(URI.create(s"${h.baseUrl}/mcp"))
      .header("Content-Type", "application/json")
      .header("Authorization", s"Bearer $token")
      .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
      .timeout(RequestTimeout)
      .build()
    h.httpClient.send(request, HttpResponse.BodyHandlers.ofString())

  private def toolNames(h: ManagerServerHarness.Harness, token: String): Set[String] =
    val body = mcp(h, token, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""").body()
    parse(body).toOption
      .flatMap(_.hcursor.downField("result").downField("tools").values)
      .map(_.flatMap(_.hcursor.get[String]("name").toOption).toSet)
      .getOrElse(Set.empty)

  private def assertRefused(resp: HttpResponse[String]): Unit =
    withClue(resp.body()) {
      resp.statusCode() shouldBe 403
      errorCode(resp.body()) shouldBe Some("token_read_only")
    }

  private def assertNotRefused(resp: HttpResponse[String]): Unit =
    withClue(resp.body()) {
      errorCode(resp.body()) should not be Some("token_read_only")
      resp.statusCode() should not be 401
      resp.statusCode() should not be 403
    }

  private val Writes = List(
    "POST"   -> "/api/manifest/import",
    "POST"   -> "/api/role/create",
    "POST"   -> "/api/role/delete",
    "POST"   -> "/api/tenant/create",
    "POST"   -> "/api/user/create",
    "POST"   -> "/api/pool/scale",
    "POST"   -> "/api/auth/pat/create",
    "POST"   -> "/api/auth/pat/revoke",
    "POST"   -> "/api/statement/kill",
    "POST"   -> "/api/branch/create",
    "POST"   -> "/api/catalog/undrop",
    "POST"   -> "/api/profile/undrop",
    "POST"   -> "/api/profile/s3/add",
    "POST"   -> "/api/maintenance/run",
    "DELETE" -> "/api/tenants/acme/tenant-dbs/acme_main/federated-sources/x",
    "PUT"    -> "/api/tenants/acme/tenant-dbs/acme_main/federated-sources/x/secrets"
  )

  Writes.foreach { (method, path) =>
    "An RO token" should s"be refused on $method $path" in withHarness() { h =>
      assertRefused(send(h, method, path, mint(ceiling("RO"))))
    }
  }

  private val Reads = List(
    "GET"  -> "/api/config/server",
    "GET"  -> "/api/user/list",
    "GET"  -> "/api/tenant/list",
    "GET"  -> "/api/manifest/export",
    "GET"  -> "/api/auth/whoami",
    "POST" -> "/api/auth/pat/list"
  )

  Reads.foreach { (method, path) =>
    it should s"keep $method $path" in withHarness() { h =>
      assertNotRefused(send(h, method, path, mint(ceiling("RO"))))
    }
  }

  it should "be refused on an encoded spelling of a write" in withHarness() { h =>
    assertRefused(send(h, "POST", "/%61pi/manifest/import", mint(ceiling("RO"))))
  }

  it should "be refused whatever the case of the stored ceiling" in withHarness() { h =>
    assertRefused(send(h, "POST", "/api/role/create", mint(ceiling("ro"))))
  }

  it should "leave an audit row when refused" in {
    val telemetry = new RecordingTelemetryStore
    withHarness(telemetry) { h =>
      assertRefused(send(h, "POST", "/api/role/create", mint(ceiling("RO"))))
      telemetry.listAudit(AuditQuery()).map(_.event.action) should contain(
        AuditActions.AuthTokenReadOnly
      )
    }
  }

  "A token with a wider ceiling" should "not be refused as read-only" in withHarness() { h =>
    List("RW", "DDL", "ALL").foreach { verb =>
      val resp = send(h, "POST", "/api/role/create", mint(ceiling(verb)))
      withClue(s"$verb: ${resp.body()}") {
        errorCode(resp.body()) should not be Some("token_read_only")
      }
    }
  }

  "The MCP tool list of an RO token" should "offer reads and SQL" in withHarness() { h =>
    val names = toolNames(h, mint(ceiling("RO")))
    names should contain allOf ("run_sql", "list_roles", "manifest_export", "audit_search")
  }

  it should "hide every write" in withHarness() { h =>
    val names = toolNames(h, mint(ceiling("RO")))
    names should contain noneOf (
      "manifest_import",
      "create_role",
      "delete_tenant",
      "scale_pool",
      "create_pat",
      "create_branch",
      "undrop_table"
    )
  }

  it should "offer nothing outside the read-only list" in withHarness() { h =>
    (toolNames(h, mint(ceiling("RO"))) -- McpRoutes.ReadOnlyTools) shouldBe empty
  }

  it should "name only tools that exist" in withHarness() { h =>
    val all = toolNames(h, mint(TokenRestriction.Unrestricted))
    // The harness does not mount McpBranchTools, where these three are defined.
    val branchTools = Set("list_branches", "branch_changes", "diff")
    (McpRoutes.ReadOnlyTools -- all -- branchTools) shouldBe empty
  }

  it should "still leave every tool to an unrestricted token" in withHarness() { h =>
    toolNames(h, mint(TokenRestriction.Unrestricted)) should contain("manifest_import")
  }

  "An MCP call by an RO token" should "not run a write tool" in withHarness() { h =>
    val body =
      """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"manifest_import","arguments":{"yaml":"roles: []"}}}"""
    val resp = mcp(h, mint(ceiling("RO")), body)
    withClue(resp.body()) {
      // The JSON-RPC error an unknown tool gets, not a tool result: the tool never ran.
      parse(resp.body()).toOption.map(_.hcursor.downField("error").succeeded) shouldBe Some(true)
    }
  }
