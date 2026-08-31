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
      patAuth = new PatAuthenticator(pats, users.userById, u => List(UserGrant(u.tenant, u.role)))
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
      staticApiKey: Option[String] = Some("static-key-1"),
      mcpEnabled: Boolean = true
  )(body: ManagerServerHarness.Harness => Unit): Unit =
    TestPostgres.ensureReachable()
    val fix = SecurityFixtures.freshStore()
    val h   = ManagerServerHarness.boot(
      fix.store,
      staticApiKey = staticApiKey,
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
    adminTools should contain allOf ("run_sql", "list_databases", "scale_pool", "kill_statement")

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

  it should "401 any non-PAT bearer when no static key is configured" in
    withHarness(staticApiKey = None) { h =>
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
