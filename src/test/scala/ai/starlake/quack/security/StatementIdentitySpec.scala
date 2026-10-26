// src/test/scala/ai/starlake/quack/security/StatementIdentitySpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.boot.RoutedExecutor
import ai.starlake.quack.edge.RouterFailure
import ai.starlake.quack.edge.auth.AuthenticatedProfile
import ai.starlake.quack.edge.authz.NodePrincipal
import ai.starlake.quack.model.{Pool, PoolKey, RoleDistribution, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.api.ExecCaller
import ai.starlake.quack.ondemand.auth.{PatAuthenticator, SessionScope, TokenRestriction}
import ai.starlake.quack.ondemand.catalog.{DroppedTableEntry, DuckLakeCatalogReader}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PatStore, UserGrant, UserStore}
import ai.starlake.quack.route.StatementClassifier
import cats.effect.IO
import io.circe.parser.parse
import io.circe.{HCursor, Json}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.net.http.{HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import scala.util.Try

/** Statement identity on the REST and MCP data paths: a statement runs as the caller who sent it.
  * The routed executor resolves a session or a PAT through the FlightSQL handshake gate
  * (`PoolSupervisor.authorizeHandshakeDetailed`: kill switches, user registered and enabled, pool
  * access), and `FlightSqlRouter` turns the effective set it gets into the statement's in-engine
  * principal (`NodePrincipal.of`, carried by the session handle of every node PREPARE). Only the
  * manager's own work (the restore dry run, the branch change counter) runs as
  * `NodePrincipal.System`.
  *
  * The executor is the real [[RoutedExecutor]] over the harness's own supervisor (`executorOf`, the
  * way Main binds it to the supervisor that serves REST), with a recording router leg in place of
  * `FlightSqlRouter.execute`: what it records is what the router would receive, and the principal
  * is computed by the router's own `NodePrincipal.of`. `TenantSignInIsolationSpec` pins which row a
  * credential's realm may bind; this spec pins the refusals at the gate and the principal a
  * statement that passes it runs as.
  *
  * PATs live in Postgres (`qodstate_pat` references `qodstate_user`), so each PAT owner exists
  * twice: in the Postgres user table the PAT authenticator reads, and in the fixture store the
  * handshake reads. Production reads one table for both, and `PatAuthenticator.resolve` refuses a
  * disabled owner's PAT at the guard; disabling the owner in the fixture store alone stands for a
  * disable that lands between the guard and the executor. A session JWT is stateless, so a user
  * disabled after login reaches the handshake gate in production too.
  */
class StatementIdentitySpec
    extends AnyFlatSpec
    with Matchers
    with SecurityHttpHelpers
    with BeforeAndAfterAll:

  TestPostgres.dropStrayTestDatabases("qodstmtid")

  private val dbName = s"qodstmtid_test_${System.nanoTime()}"

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
      // Row-only grants, as Main wires it: a PAT is bound to its owner's row.
      patAuth = new PatAuthenticator(pats, users.userById, u => List(UserGrant(u.tenant, u.kind)))
      users.upsertUser(None, SecurityFixtures.RootUsername, SecurityFixtures.RootPassword, "admin")
      users.upsertUser(
        Some(SecurityFixtures.TenantId),
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        "admin"
      )

  override def afterAll(): Unit =
    if pats != null then pats.close()
    if users != null then users.close()
    Try(TestPostgres.dropDatabase(dbName))
    ()

  // A DuckLake tenant-db and pool in tenant acme: a preview refuses any other kind (400
  // invalid_kind), and a pool row is enough for the handler's pool pick and the handshake's pool
  // lookup (no node needed: the router leg is recorded, never run).
  private val DuckLakeDbId   = "td-stmtid01"
  private val DuckLakeDb     = "acme_stmtid"
  private val DuckLakePool   = "stmtid"
  private val DuckLakePoolId = "p-stmtid01"

  /** One statement as it reached the router leg, with the in-engine principal it would carry. */
  private final case class Routed(caller: ExecCaller, poolKey: PoolKey, principal: NodePrincipal)

  /** A catalog where `main.orders` is live (table 7, last touched at snapshot 40) and resolves at
    * snapshot 39, and `main.doomed` was dropped at 40 (last live at 39); snapshots 12, 39 and 42
    * exist, 42 is the latest. It carries an MCP restore and undrop past every catalog check to the
    * executor.
    */
  private val recoveryReader: DuckLakeCatalogReader =
    new DuckLakeCatalogReader(null):
      private val doomed =
        DroppedTableEntry("main", "doomed", 40L, 39L, Some("2026-07-14T00:00:00Z"), true)
      override def snapshotExists(id: Long): Boolean = id == 12L || id == 39L || id == 42L
      override def maxSnapshotId(): Option[Long]     = Some(42L)
      override def currentTableInfo(schema: String, table: String): Option[(Long, Long)] =
        Option.when(schema == "main" && table == "orders")((7L, 40L))
      override def latestTableSnapshot(schema: String, table: String): Option[Long] =
        Option.when(schema == "main" && table == "orders")(40L)
      override def tableExistsAt(schema: String, table: String, snapshotId: Long): Boolean =
        schema == "main" && table == "orders"
      override def listDroppedTables(limit: Int): List[DroppedTableEntry] =
        List(doomed).take(limit)
      override def findDroppedTable(schema: String, table: String): Option[DroppedTableEntry] =
        Option.when(schema == "main" && table == "doomed")(doomed)

  private def withWorld(
      body: (SecurityFixtures.Fixture, ManagerServerHarness.Harness, () => Vector[Routed]) => Unit
  ): Unit = withWorldReading(None)(body)

  private def withWorldReading(reader: Option[DuckLakeCatalogReader])(
      body: (SecurityFixtures.Fixture, ManagerServerHarness.Harness, () => Vector[Routed]) => Unit
  ): Unit =
    TestPostgres.ensureReachable()
    val fix = SecurityFixtures.freshStore()
    fix.store.upsertTenantDb(
      TenantDb(
        id = DuckLakeDbId,
        tenantId = SecurityFixtures.TenantId,
        name = DuckLakeDb,
        kind = TenantDbKind.DuckLake,
        metastore = Map(
          "pgHost"     -> "127.0.0.1",
          "pgPort"     -> "5432",
          "pgUser"     -> "u",
          "pgPassword" -> "p",
          "dbName"     -> DuckLakeDb,
          "schemaName" -> "main"
        ),
        dataPath = "/tmp/qod-statement-identity-test"
      )
    )
    fix.store.upsertPool(
      Pool(
        id = DuckLakePoolId,
        tenantId = SecurityFixtures.TenantId,
        tenantDbId = DuckLakeDbId,
        name = DuckLakePool,
        size = 1,
        distribution = RoleDistribution(writeonly = 0, readonly = 0, dual = 1)
      )
    )
    val routed = new AtomicReference(Vector.empty[Routed])
    val h      = ManagerServerHarness.boot(
      fix.store,
      catalogReader = reader.map(r => (_, _) => r),
      restoreCatalogReader = reader,
      patStore = Some(pats),
      patUserOf =
        Some((tenant, username) => users.userIdOf(tenant, username).flatMap(users.userById)),
      patAuth = Some(patAuth),
      mcpEnabled = true,
      // The handshake reads the store through the harness's own supervisor, exactly as the one
      // Main hands its routed executor.
      executorOf = Some(sup =>
        RoutedExecutor(
          sup,
          StatementClassifier.default,
          (caller, poolKey, _, effectiveSet, _) =>
            IO {
              routed.updateAndGet(v => v :+ Routed(caller, poolKey, NodePrincipal.of(effectiveSet)))
              Left(RouterFailure.Unavailable("recorded"))
            }
        )(recordExecution = false)
      )
    )
    try body(fix, h, () => routed.get())
    finally h.shutdown()

  /** A live PAT for `(tenant, username)`, minted against the store: `(patId, raw token)`. */
  private def mintPat(tenant: Option[String], username: String): (String, String) =
    val uid = users.userIdOf(tenant, username).getOrElse(fail(s"fixture user $username missing"))
    val (rec, raw) = pats.mint(uid, s"stmtid-$username", TokenRestriction.Unrestricted, None, 0)
    (rec.id, raw)

  /** Turns `enabled` off on the control-plane row the handshake reads, keeping its password. */
  private def disableInControlPlane(
      fix: SecurityFixtures.Fixture,
      tenant: Option[String],
      username: String
  ): Unit =
    val hash = fix.store.getPasswordHash(tenant, username).getOrElse(fail(s"no hash for $username"))
    fix.store.upsertUserWithHash(tenant, username, hash, "admin", enabled = false)
    ()

  private def previewUrl(h: ManagerServerHarness.Harness): String =
    s"${h.baseUrl}/api/catalog/tenant/${SecurityFixtures.TenantId}/database/$DuckLakeDb" +
      "/schemas/main/tables/region/preview"

  private def messageOf(body: String): Option[String] =
    parse(body).toOption.flatMap(_.hcursor.get[String]("message").toOption)

  /** `tools/call` of `tool` with `arguments` over `POST /mcp` as `bearer`: the call's `result`. */
  private def mcpCall(
      h: ManagerServerHarness.Harness,
      bearer: String,
      tool: String,
      arguments: (String, Json)*
  ): HCursor =
    val call = Json.obj(
      "jsonrpc" -> Json.fromString("2.0"),
      "id"      -> Json.fromInt(1),
      "method"  -> Json.fromString("tools/call"),
      "params"  -> Json.obj(
        "name"      -> Json.fromString(tool),
        "arguments" -> Json.obj(arguments*)
      )
    )
    val req = HttpRequest
      .newBuilder(URI.create(s"${h.baseUrl}/mcp"))
      .header("Content-Type", "application/json")
      .header("Authorization", s"Bearer $bearer")
      .timeout(java.time.Duration.ofSeconds(10))
      .POST(HttpRequest.BodyPublishers.ofString(call.noSpaces, StandardCharsets.UTF_8))
      .build()
    val resp = h.httpClient.send(req, HttpResponse.BodyHandlers.ofString())
    withClue(s"$tool body: ${resp.body()}") {
      resp.statusCode() shouldBe 200
    }
    parse(resp.body())
      .fold(_ => fail(s"$tool answered no JSON"), _.hcursor.downField("result").success)
      .getOrElse(fail(s"$tool answered no result"))

  /** The text of an MCP tool result's first content item. */
  private def resultText(result: HCursor): String =
    result.downField("content").downN(0).get[String]("text").getOrElse("")

  private def superuserPrincipal(fix: SecurityFixtures.Fixture): NodePrincipal =
    NodePrincipal(
      fix.rootUserId,
      SecurityFixtures.RootUsername,
      None,
      superuser = true,
      system = false
    )

  /** What a superuser PAT's statement must carry to the router: the owner's name, never the system
    * flag, the token's id, and the owner's realm (tenant-less, so superuser-admissible).
    */
  private def callerShape(r: Routed): (String, Boolean, Option[String], Boolean) =
    (r.caller.identity, r.caller.system, r.caller.patId, r.caller.superuserAdmissible)

  "a REST preview sent with the PAT of a user the control plane has disabled" should
    "be refused at the handshake gate, before the router" in withWorld { (fix, h, routed) =>
      val (_, raw) = mintPat(Some(SecurityFixtures.TenantId), SecurityFixtures.AliceUsername)
      disableInControlPlane(fix, Some(SecurityFixtures.TenantId), SecurityFixtures.AliceUsername)
      val resp = get(h.httpClient, previewUrl(h), Some(raw))
      withClue(s"preview body: ${resp.body()}") {
        resp.statusCode() shouldBe 403
        errorCode(resp.body()) shouldBe Some("acl_denied")
        messageOf(resp.body()) shouldBe Some(
          s"user '${SecurityFixtures.AliceUsername}' is disabled"
        )
      }
      routed() shouldBe empty
    }

  "a REST preview sent with the session of a user disabled after login" should
    "be refused at the handshake gate, before the router" in withWorld { (fix, h, routed) =>
      val session = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      disableInControlPlane(fix, Some(SecurityFixtures.TenantId), SecurityFixtures.AliceUsername)
      val resp = get(h.httpClient, previewUrl(h), Some(session))
      withClue(s"preview body: ${resp.body()}") {
        resp.statusCode() shouldBe 403
        errorCode(resp.body()) shouldBe Some("acl_denied")
        messageOf(resp.body()) shouldBe Some(
          s"user '${SecurityFixtures.AliceUsername}' is disabled"
        )
      }
      routed() shouldBe empty
    }

  "a REST preview sent with a superuser session that has no qodstate_user row" should
    "be refused at the handshake gate, before the router" in withWorld { (_, h, routed) =>
      // What a system-scope OIDC login whose IdP identity has no row of its own (its grant came
      // from an email-keyed row) holds: a superuser scope, but no user to run a statement as.
      val session = h.tokens.mintWithScope(
        AuthenticatedProfile("oidc-only-admin", "admin", Set.empty, Map.empty, "oidc"),
        SessionScope.Superuser
      )
      val resp = get(h.httpClient, previewUrl(h), Some(session))
      withClue(s"preview body: ${resp.body()}") {
        resp.statusCode() shouldBe 403
        errorCode(resp.body()) shouldBe Some("acl_denied")
        messageOf(resp.body()) shouldBe
          Some(s"user 'oidc-only-admin' is not registered in tenant '${SecurityFixtures.TenantId}'")
      }
      routed() shouldBe empty
    }

  "a REST preview sent with a superuser's PAT" should
    "run in-engine as that superuser's principal, not System" in withWorld { (fix, h, routed) =>
      val (patId, raw) = mintPat(None, SecurityFixtures.RootUsername)
      val resp         = get(h.httpClient, previewUrl(h), Some(raw))
      withClue(s"preview body: ${resp.body()}") {
        // The recording router leg answers Unavailable, which the handler maps to 502.
        resp.statusCode() shouldBe 502
        errorCode(resp.body()) shouldBe Some("preview_failed")
      }
      val r = routed()
      r.map(_.poolKey) shouldBe Vector(PoolKey(SecurityFixtures.TenantId, DuckLakeDb, DuckLakePool))
      r.map(callerShape) shouldBe Vector((SecurityFixtures.RootUsername, false, Some(patId), true))
      r.map(_.principal) shouldBe Vector(superuserPrincipal(fix))
      r.map(_.principal) should not contain NodePrincipal.System
    }

  "an MCP run_sql sent with a superuser's PAT" should
    "run in-engine as that superuser's principal, not System" in withWorld { (fix, h, routed) =>
      val (patId, raw) = mintPat(None, SecurityFixtures.RootUsername)
      mcpCall(
        h,
        raw,
        "run_sql",
        "tenant"   -> Json.fromString(SecurityFixtures.TenantId),
        "database" -> Json.fromString(DuckLakeDb),
        "sql"      -> Json.fromString("SELECT 1")
      )
      val r = routed()
      r.map(callerShape) shouldBe Vector((SecurityFixtures.RootUsername, false, Some(patId), true))
      r.map(_.principal) shouldBe Vector(superuserPrincipal(fix))
      r.map(_.principal) should not contain NodePrincipal.System
    }

  "an MCP undrop_table sent with a superuser's PAT" should
    "pass the handshake gate and run in-engine as that superuser's principal" in
    withWorldReading(Some(recoveryReader)) { (fix, h, routed) =>
      val (patId, raw) = mintPat(None, SecurityFixtures.RootUsername)
      val result       = mcpCall(
        h,
        raw,
        "undrop_table",
        "tenant"   -> Json.fromString(SecurityFixtures.TenantId),
        "database" -> Json.fromString(DuckLakeDb),
        "schema"   -> Json.fromString("main"),
        "table"    -> Json.fromString("doomed")
      )
      // The CTAS reached the recording router leg (its Unavailable is undrop's 502), so the gate
      // admitted it: a refusal there would read `acl_denied`.
      resultText(result) shouldBe "undrop_failed: recorded"
      val r = routed()
      r.map(_.poolKey) shouldBe Vector(PoolKey(SecurityFixtures.TenantId, DuckLakeDb, DuckLakePool))
      r.map(callerShape) shouldBe Vector((SecurityFixtures.RootUsername, false, Some(patId), true))
      r.map(_.principal) shouldBe Vector(superuserPrincipal(fix))
    }

  "an MCP restore_snapshot sent with a superuser's PAT" should
    "pass the handshake gate and run in-engine as that superuser's principal" in
    withWorldReading(Some(recoveryReader)) { (fix, h, routed) =>
      val (patId, raw) = mintPat(None, SecurityFixtures.RootUsername)
      val result       = mcpCall(
        h,
        raw,
        "restore_snapshot",
        "tenant"   -> Json.fromString(SecurityFixtures.TenantId),
        "database" -> Json.fromString(DuckLakeDb),
        "schema"   -> Json.fromString("main"),
        "table"    -> Json.fromString("orders"),
        "to"       -> Json.fromString("39")
      )
      // The CREATE OR REPLACE reached the recording router leg (its Unavailable is restore's
      // 502), so the gate admitted it: a refusal there would read `acl_denied`.
      resultText(result) shouldBe "restore_failed: recorded"
      val r = routed()
      r.map(_.poolKey) shouldBe Vector(PoolKey(SecurityFixtures.TenantId, DuckLakeDb, DuckLakePool))
      r.map(callerShape) shouldBe Vector((SecurityFixtures.RootUsername, false, Some(patId), true))
      r.map(_.principal) shouldBe Vector(superuserPrincipal(fix))
    }
