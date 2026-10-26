// src/test/scala/ai/starlake/quack/mcp/McpEndToEndSpec.scala
package ai.starlake.quack.mcp

import ai.starlake.quack.ondemand.auth.{PatAuthenticator, TokenRestriction}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PatStore, UserGrant, UserStore}
import ai.starlake.quack.security.{ManagerServerHarness, SecurityFixtures}
import io.circe.Json
import io.circe.parser.parse
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.net.http.{HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import scala.util.Try

/** Full-wire MCP contract: real HTTP against the harness-booted manager with a Postgres PAT store,
  * covering auth arms, tool tiering per principal, and the enabled=false unmount.
  */
class McpEndToEndSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  TestPostgres.dropStrayTestDatabases("qodmcpe2e")

  private val dbName = s"qodmcpe2e_test_${System.nanoTime()}"

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
      users.upsertUser(
        Some(SecurityFixtures.TenantId),
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        "admin"
      )
      users.upsertUser(
        Some(SecurityFixtures.TenantId),
        SecurityFixtures.BobUsername,
        SecurityFixtures.BobPassword,
        "user"
      )

  override def afterAll(): Unit =
    if pats != null then pats.close()
    if users != null then users.close()
    Try(TestPostgres.dropDatabase(dbName))
    ()

  private def mintPat(tenant: Option[String], username: String): String =
    val uid = users.userIdOf(tenant, username).getOrElse(fail(s"user $username missing"))
    pats.mint(uid, s"mcp-$username", TokenRestriction.Unrestricted, None, 0)._2

  private def withHarness(
      mcpEnabled: Boolean = true
  )(body: ManagerServerHarness.Harness => Unit): Unit =
    TestPostgres.ensureReachable()
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(
      fix.store,
      patStore = Some(pats),
      patUserOf =
        Some((tenant, username) => users.userIdOf(tenant, username).flatMap(users.userById)),
      patAuth = Some(patAuth),
      mcpEnabled = mcpEnabled
    )
    try body(h)
    finally h.shutdown()

  private def rpc(method: String, id: Int = 1, params: Json = Json.obj()): String =
    Json
      .obj(
        "jsonrpc" -> Json.fromString("2.0"),
        "id"      -> Json.fromInt(id),
        "method"  -> Json.fromString(method),
        "params"  -> params
      )
      .noSpaces

  private def postMcp(
      h: ManagerServerHarness.Harness,
      body: String,
      bearer: Option[String]
  ): HttpResponse[String] =
    val b = HttpRequest
      .newBuilder(URI.create(s"${h.baseUrl}/mcp"))
      .header("Content-Type", "application/json")
      .timeout(java.time.Duration.ofSeconds(10))
      .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
    bearer.foreach(t => b.header("Authorization", s"Bearer $t"))
    h.httpClient.send(b.build(), HttpResponse.BodyHandlers.ofString())

  private def toolNames(body: String): List[String] =
    parse(body).toOption
      .flatMap(_.hcursor.downField("result").downField("tools").as[List[Json]].toOption)
      .getOrElse(Nil)
      .flatMap(_.hcursor.get[String]("name").toOption)

  // ------------------------------------------------------------------

  "POST /mcp" should "answer initialize for an admin PAT" in withHarness() { h =>
    val resp = postMcp(h, rpc("initialize"), Some(mintPat(None, SecurityFixtures.RootUsername)))
    withClue(s"body: ${resp.body()}") {
      resp.statusCode() shouldBe 200
      parse(resp.body()).toOption.get.hcursor
        .downField("result")
        .downField("serverInfo")
        .get[String]("name")
        .toOption shouldBe Some("quack-on-demand")
    }
  }

  it should "tier tools/list by principal" in withHarness() { h =>
    val adminTools =
      toolNames(
        postMcp(
          h,
          rpc("tools/list"),
          Some(mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.AliceUsername))
        ).body()
      )
    adminTools should contain allOf (
      "run_sql",
      "list_databases",
      "scale_pool",
      "kill_statement",
      "create_tenant",
      "create_user",
      "add_membership",
      "grant_role_permission",
      "create_column_policy",
      "grant_pool_permission",
      "create_pool",
      "create_database",
      "upsert_maintenance_policy",
      "delete_tag",
      "restore_snapshot",
      "upsert_federated_source",
      "manifest_export",
      "create_pat",
      "get_config",
      "statement_history"
    )

    val userTools =
      toolNames(
        postMcp(
          h,
          rpc("tools/list"),
          Some(mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername))
        ).body()
      )
    userTools should contain("run_sql")
    userTools should not contain "scale_pool"
  }

  it should "answer list_databases for a tenant-admin PAT with the seeded tenant-db" in
    withHarness() { h =>
      val alice  = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.AliceUsername)
      val params = Json.obj(
        "name"      -> Json.fromString("list_databases"),
        "arguments" -> Json.obj()
      )
      val resp = postMcp(h, rpc("tools/call", params = params), Some(alice))
      withClue(s"body: ${resp.body()}") {
        resp.statusCode() shouldBe 200
        val result = parse(resp.body()).toOption.get.hcursor.downField("result")
        result.get[Boolean]("isError").toOption shouldBe Some(false)
        val text = result.downField("content").downN(0).get[String]("text").toOption.getOrElse("")
        text should include(SecurityFixtures.TenantDbName)
      }
    }

  it should "refuse scale_pool for a role=user PAT with -32602" in withHarness() { h =>
    val bob    = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.BobUsername)
    val params = Json.obj(
      "name"      -> Json.fromString("scale_pool"),
      "arguments" -> Json.obj(
        "database" -> Json.fromString(SecurityFixtures.TenantDbName),
        "pool"     -> Json.fromString(SecurityFixtures.PoolName),
        "dual"     -> Json.fromInt(2)
      )
    )
    val resp = postMcp(h, rpc("tools/call", params = params), Some(bob))
    withClue(s"body: ${resp.body()}") {
      parse(resp.body()).toOption.get.hcursor
        .downField("error")
        .get[Int]("code")
        .toOption shouldBe Some(-32602)
    }
  }

  it should "401 a session JWT" in withHarness() { h =>
    val session = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword)
    postMcp(h, rpc("ping"), Some(session)).statusCode() shouldBe 401
  }

  it should "401 any non-PAT bearer, and a missing one" in
    withHarness() { h =>
      postMcp(h, rpc("ping"), Some("some-random-value")).statusCode() shouldBe 401
      postMcp(h, rpc("ping"), None).statusCode() shouldBe 401
    }

  it should "405 a GET" in withHarness() { h =>
    val req = HttpRequest
      .newBuilder(URI.create(s"${h.baseUrl}/mcp"))
      .timeout(java.time.Duration.ofSeconds(10))
      .GET()
      .build()
    h.httpClient.send(req, HttpResponse.BodyHandlers.ofString()).statusCode() shouldBe 405
  }

  it should "404 when mcp is disabled" in withHarness(mcpEnabled = false) { h =>
    val resp =
      postMcp(h, rpc("ping"), Some(mintPat(None, SecurityFixtures.RootUsername)))
    resp.statusCode() shouldBe 404
  }

  "mcp admin surface" should "drive the full provisioning flow" in withHarness() { h =>
    val root = mintPat(None, SecurityFixtures.RootUsername)

    // Issues one tools/call, asserts isError:false (withClue prints the body on failure), and
    // returns the tool's result payload parsed back to Json (every tool's "content[0].text" is
    // itself a JSON-encoded object/array).
    def call(name: String, args: (String, Json)*): Json =
      val params = Json.obj(
        "name"      -> Json.fromString(name),
        "arguments" -> Json.obj(args*)
      )
      val resp   = postMcp(h, rpc("tools/call", params = params), Some(root))
      val result = parse(resp.body()).toOption.get.hcursor.downField("result")
      withClue(s"$name -> ${resp.body()}") {
        result.get[Boolean]("isError").toOption shouldBe Some(false)
      }
      val text = result.downField("content").downN(0).get[String]("text").toOption.getOrElse("")
      parse(text).toOption.getOrElse(fail(s"$name: non-JSON result text: $text"))

    def field(json: Json, name: String): String =
      json.hcursor.get[String](name).toOption.getOrElse(fail(s"missing '$name' in $json"))

    // Names.normalizeOrError forbids the hyphen a display slug like "e2e-corp" would use;
    // underscore is the closest allowed stand-in.
    val tenant = "e2e_corp"

    call("create_tenant", "id" -> Json.fromString(tenant))

    // create_database's "name" argument is a SUFFIX; the supervisor composes the stored
    // tenant-db name as "<tenant>_<suffix>" (Names.normalizeTenantDbName), so create_pool
    // below must address it by the response's "name", not the suffix we passed in.
    val db = call(
      "create_database",
      "tenant" -> Json.fromString(tenant),
      "name"   -> Json.fromString("e2e_db"),
      "kind"   -> Json.fromString("memory")
    )
    val dbName = field(db, "name")

    call(
      "create_pool",
      "tenant"   -> Json.fromString(tenant),
      "database" -> Json.fromString(dbName),
      "pool"     -> Json.fromString("bi"),
      "dual"     -> Json.fromInt(1)
    )

    val role = call(
      "create_role",
      "tenant" -> Json.fromString(tenant),
      "name"   -> Json.fromString("reader")
    )
    val roleId = field(role, "id")

    val user = call(
      "create_user",
      "tenant"   -> Json.fromString(tenant),
      "username" -> Json.fromString("dana"),
      "password" -> Json.fromString("s3cret-s3cret")
    )
    val userId = field(user, "id")

    call(
      "grant_role_permission",
      "role_id" -> Json.fromString(roleId),
      "verb"    -> Json.fromString("RO")
    )

    call(
      "add_membership",
      "kind"    -> Json.fromString("user_role"),
      "user_id" -> Json.fromString(userId),
      "role_id" -> Json.fromString(roleId)
    )

    call(
      "create_column_policy",
      "role_id"       -> Json.fromString(roleId),
      "column_name"   -> Json.fromString("c_phone"),
      "action"        -> Json.fromString("mask"),
      "transform_sql" -> Json.fromString("'***'")
    )

    val effective = call("user_effective_permissions", "id" -> Json.fromString(userId))
    effective.hcursor.downField("tablePerms").values.get should not be empty
  }
