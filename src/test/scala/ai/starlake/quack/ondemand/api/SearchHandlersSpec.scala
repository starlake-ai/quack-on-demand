package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.RouterFailure
import ai.starlake.quack.model.{PoolKey, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.{PatAuthenticator, TokenRestriction}
import ai.starlake.quack.ondemand.search.{SearchCatalog, SearchIndexService}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.state.{
  InMemoryControlPlaneStore,
  LiquibaseRunner,
  PatStore,
  SearchIndexStore,
  UserGrant,
  UserStore
}
import ai.starlake.quack.ondemand.telemetry.AuditRecorder
import ai.starlake.quack.security.{ManagerServerHarness, SecurityFixtures, SecurityHttpHelpers}
import cats.effect.IO
import io.circe.parser.parse
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger
import scala.util.Try

/** The registry's REST gate, end to end through `apiKeyGuard` (`RbacTenantScopeSpec`'s pattern): a
  * tenant admin's session and PAT on their own tenant, `403 tenant_forbidden` on another, `403
  * admin_required` for a profile session, a superuser everywhere, `bundleRoot` for superusers only,
  * the build superuser only, and one `404` for a database whether or not another tenant holds it.
  * The PAT store is real Postgres (`qodstate_pat`'s key on `qodstate_user`); cancelled when it is
  * unreachable.
  */
class SearchHandlersSpec
    extends AnyFlatSpec
    with Matchers
    with SecurityHttpHelpers
    with BeforeAndAfterAll:

  TestPostgres.dropStrayTestDatabases("qodsrch")

  private val dbName = s"qodsrch_test_${System.nanoTime()}"

  private var users: UserStore = null
  private var pats: PatStore   = null

  override def beforeAll(): Unit =
    if TestPostgres.reachable then
      TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      users = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      pats = new PatStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
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

  private val Acme   = SecurityFixtures.TenantId
  private val Globex = SecurityFixtures.GlobexTenantId

  /** Every live table is `main.pages` (table id 41) with a VARCHAR `title`. */
  private val catalog: SearchCatalog = new SearchCatalog:
    def liveTableColumns(td: TenantDb, schema: String, table: String) =
      IO.pure(
        Option.when(schema == "main" && table == "pages")(
          ai.starlake.quack.ondemand.catalog
            .LiveTable(41L, "main", "pages", List(("id", 1L, "int64"), ("title", 2L, "varchar")))
        )
      )
    def liveTableName(td: TenantDb, tableId: Long) =
      IO.pure(Option.when(tableId == 41L)(("main", "pages")))

  private final class Fixture:
    val store    = SecurityFixtures.freshStore().store
    val executed = new AtomicInteger(0)
    SecurityFixtures.addTenantB(SecurityFixtures.Fixture(store, "", "", ""))
    List(Acme -> "acme_lake", Acme -> "acme_logs", Globex -> "globex_lake").foreach { (t, name) =>
      store.upsertTenantDb(
        TenantDb(s"td-$name", t, name, TenantDbKind.DuckLake, Map.empty, s"s3://lake/$name/")
      )
    }
    store.upsertPool(
      ai.starlake.quack.model.Pool(
        id = "p-lakebi",
        tenantId = Acme,
        tenantDbId = "td-acme_lake",
        name = "bi",
        size = 1,
        distribution = ai.starlake.quack.model.RoleDistribution(0, 0, 1),
        suspended = true
      )
    )
    def handlers(sup: PoolSupervisor, callerOf: RestCaller): SearchHandlers =
      new SearchHandlers(
        service = new SearchIndexService(
          tenantDbOf = sup.findTenantDb,
          tenantDbsOf = sup.listTenantDbsByTenant,
          metastoreOf = td => sup.effectiveMetastoreFor(td.tenantId, td.name),
          catalog = catalog,
          store = SearchIndexStore.over(store)
        ),
        store = SearchIndexStore.over(store),
        poolExists = key => sup.snapshot(key).nonEmpty,
        aliasOf = _.name,
        executor = (_, _, _) =>
          IO(executed.incrementAndGet()).as(Left(RouterFailure.Unavailable("no node here"))),
        audit = AuditRecorder.noop,
        callerOf = callerOf,
        databaseRefusal = TokenScope.databaseRefusal(sup, _, _, _)
      )

  private def withHarness(body: (ManagerServerHarness.Harness, Fixture) => Unit): Unit =
    TestPostgres.ensureReachable()
    val f = new Fixture
    val h = ManagerServerHarness.boot(
      f.store,
      patStore = Some(pats),
      patUserOf =
        Some((tenant, username) => users.userIdOf(tenant, username).flatMap(users.userById)),
      patAuth =
        Some(new PatAuthenticator(pats, users.userById, u => List(UserGrant(u.tenant, u.kind)))),
      searchOf = Some(f.handlers(_, _))
    )
    try body(h, f)
    finally h.shutdown()

  private def alice(h: ManagerServerHarness.Harness): String =
    h.mintToken(SecurityFixtures.AliceUsername, SecurityFixtures.AlicePassword, Some(Acme))

  private def alicePat(r: TokenRestriction = TokenRestriction.Unrestricted): String =
    patOf(Some(Acme), SecurityFixtures.AliceUsername, r)

  private def patOf(tenant: Option[String], username: String, r: TokenRestriction): String =
    val uid = users.userIdOf(tenant, username).getOrElse(fail(s"$username not found"))
    pats.mint(uid, "search-spec", r, None, 0)._2

  private def call(h: ManagerServerHarness.Harness, op: String, body: String, key: String) =
    post(h.httpClient, s"${h.baseUrl}/api/search/index/$op", body, apiKey = Some(key))

  private def createBody(tenant: String, database: String) =
    s"""{"tenant":"$tenant","database":"$database","table":"pages","columns":["title"]}"""

  private def field(body: String, path: String*): Option[io.circe.Json] =
    parse(body).toOption.flatMap(j =>
      path.foldLeft(Option(j))((o, k) => o.flatMap(_.\\(k).headOption))
    )

  "a tenant admin's session" should "create on its own tenant and be refused on another" in
    withHarness { (h, _) =>
      val token = alice(h)
      val own   = call(h, "create", createBody(Acme, "acme_lake"), token)
      withClue(own.body())(own.statusCode() shouldBe 200)
      val other = call(h, "create", createBody(Globex, "globex_lake"), token)
      withClue(other.body()) {
        other.statusCode() shouldBe 403
        errorCode(other.body()) shouldBe Some("tenant_forbidden")
      }
      val list = call(h, "list", s"""{"tenant":"$Globex"}""", token)
      errorCode(list.body()) shouldBe Some("tenant_forbidden")
      val drop =
        call(
          h,
          "delete",
          s"""{"tenant":"$Globex","database":"globex_lake","table":"pages"}""",
          token
        )
      errorCode(drop.body()) shouldBe Some("tenant_forbidden")
    }

  "a profile session" should "read 403 admin_required" in withHarness { (h, _) =>
    val bob = h.mintToken(SecurityFixtures.BobUsername, SecurityFixtures.BobPassword, Some(Acme))
    List(
      "create" -> createBody(Acme, "acme_lake"),
      "list"   -> s"""{"tenant":"$Acme"}"""
    ).foreach { (op, body) =>
      val r = call(h, op, body, bob)
      withClue(s"$op: ${r.body()}") {
        r.statusCode() shouldBe 403
        errorCode(r.body()) shouldBe Some("admin_required")
      }
    }
  }

  "a tenant admin's PAT" should "work like its session" in withHarness { (h, _) =>
    val pat = alicePat()
    val r   = call(h, "create", createBody(Acme, "acme_lake"), pat)
    withClue(r.body())(r.statusCode() shouldBe 200)
    val listed = call(h, "list", s"""{"tenant":"$Acme"}""", pat)
    withClue(listed.body())(listed.statusCode() shouldBe 200)
    field(listed.body(), "indexes").flatMap(_.asArray).map(_.size) shouldBe Some(1)
    errorCode(call(h, "create", createBody(Globex, "globex_lake"), pat).body()) shouldBe
      Some("tenant_forbidden")
  }

  "a superuser" should "create on both tenants and see bundleRoot; a tenant admin sees null" in
    withHarness { (h, _) =>
      val su = h.superuserSession()
      List(Acme -> "acme_lake", Globex -> "globex_lake").foreach { (t, d) =>
        val r = call(h, "create", createBody(t, d), su)
        withClue(r.body()) {
          r.statusCode() shouldBe 200
          field(r.body(), "bundleRoot").flatMap(_.asString) shouldBe Some(s"s3://lake/$d/")
        }
      }
      val mine = call(h, "list", s"""{"tenant":"$Acme","database":"acme_lake"}""", alice(h))
      withClue(mine.body()) {
        mine.statusCode() shouldBe 200
        val ix = field(mine.body(), "indexes").flatMap(_.asArray).flatMap(_.headOption).get
        ix.hcursor.downField("bundleRoot").focus.exists(_.isNull) shouldBe true
        mine.body() should not include "s3://lake"
      }
      val drop = call(
        h,
        "delete",
        s"""{"tenant":"$Globex","database":"globex_lake","table":"pages"}""",
        su
      )
      withClue(drop.body())(drop.body() shouldBe """{"deleted":true}""")
    }

  "the build" should "be superuser only, running nothing for anyone else" in withHarness { (h, f) =>
    val body = s"""{"tenant":"$Acme","database":"acme_lake","table":"pages","pool":"bi"}"""
    call(h, "create", createBody(Acme, "acme_lake"), h.superuserSession()).statusCode() shouldBe 200
    val refused = call(h, "build", body, alice(h))
    withClue(refused.body()) {
      refused.statusCode() shouldBe 403
      errorCode(refused.body()) shouldBe Some("superuser_required")
    }
    errorCode(call(h, "build", body, alicePat()).body()) shouldBe Some("superuser_required")
    f.executed.get() shouldBe 0
    // A superuser reaches the executor (which answers it has no node, a 502 here).
    val ran = call(h, "build", body, h.superuserSession())
    withClue(ran.body())(errorCode(ran.body()) shouldBe Some("search_build_failed"))
    f.executed.get() should be > 0
  }

  "an unknown database" should "be 404 for a tenant admin whether or not another tenant holds it" in
    withHarness { (h, _) =>
      val token = alice(h)
      val held  = call(h, "create", createBody(Acme, "globex_lake"), token)
      val none  = call(h, "create", createBody(Acme, "no_such_db"), token)
      List(held, none).foreach { r =>
        withClue(r.body()) {
          r.statusCode() shouldBe 404
          errorCode(r.body()) shouldBe Some("not_found")
        }
      }
      // The same answer, but for the name asked.
      held.body().replace("globex_lake", "X") shouldBe none.body().replace("no_such_db", "X")
      val list = call(h, "list", s"""{"tenant":"$Acme","database":"globex_lake"}""", token)
      errorCode(list.body()) shouldBe Some("not_found")
    }

  "a create body naming k1, b or options" should "be 400 invalid_search_options" in
    withHarness { (h, _) =>
      val su = h.superuserSession()
      List(""""options":{"k1":1.2,"b":0.75}""", """"k1":1.5""", """"b":0.5""").foreach { extra =>
        val body =
          s"""{"tenant":"$Acme","database":"acme_lake","table":"pages","columns":["title"],$extra}"""
        val r = call(h, "create", body, su)
        withClue(r.body()) {
          r.statusCode() shouldBe 400
          errorCode(r.body()) shouldBe Some("invalid_search_options")
        }
      }
    }

  "a PAT limited to one database" should "create, list and drop only there (security I1)" in
    withHarness { (h, _) =>
      val su = h.superuserSession()
      List("acme_lake", "acme_logs").foreach { d =>
        call(h, "create", createBody(Acme, d), su).statusCode() shouldBe 200
      }
      val pat = alicePat(TokenRestriction.Unrestricted.copy(databases = Some(Set("acme_lake"))))
      List(
        "create" -> createBody(Acme, "acme_logs"),
        "list"   -> s"""{"tenant":"$Acme","database":"acme_logs"}""",
        "delete" -> s"""{"tenant":"$Acme","database":"acme_logs","table":"pages"}"""
      ).foreach { (op, body) =>
        val r = call(h, op, body, pat)
        withClue(s"$op: ${r.body()}") {
          r.statusCode() shouldBe 403
          errorCode(r.body()) shouldBe Some("token_scope")
        }
      }
      val listed = call(h, "list", s"""{"tenant":"$Acme"}""", pat)
      withClue(listed.body()) {
        listed.statusCode() shouldBe 200
        field(listed.body(), "indexes")
          .flatMap(_.asArray)
          .map(_.flatMap(_.hcursor.get[String]("database").toOption).toList) shouldBe
          Some(List("acme_lake"))
      }
      // The index on acme_logs is still there for an unrestricted caller.
      field(call(h, "list", s"""{"tenant":"$Acme"}""", su).body(), "indexes")
        .flatMap(_.asArray)
        .map(_.size) shouldBe Some(2)
    }

  "a superuser PAT limited to one pool" should "build only on that pool (security I1)" in
    withHarness { (h, f) =>
      val su = h.superuserSession()
      call(h, "create", createBody(Acme, "acme_lake"), su).statusCode() shouldBe 200
      val pat = patOf(
        None,
        SecurityFixtures.RootUsername,
        TokenRestriction.Unrestricted.copy(pools = Some(Set("etl")))
      )
      val body = s"""{"tenant":"$Acme","database":"acme_lake","table":"pages","pool":"bi"}"""
      val r    = call(h, "build", body, pat)
      withClue(r.body()) {
        r.statusCode() shouldBe 403
        errorCode(r.body()) shouldBe Some("token_scope")
      }
      val dbPat = patOf(
        None,
        SecurityFixtures.RootUsername,
        TokenRestriction.Unrestricted.copy(databases = Some(Set("acme_logs")))
      )
      errorCode(call(h, "build", body, dbPat).body()) shouldBe Some("token_scope")
      f.executed.get() shouldBe 0
    }

  "a delete" should "name exactly one of table and indexId" in withHarness { (h, _) =>
    val su = h.superuserSession()
    List(
      s"""{"tenant":"$Acme","database":"acme_lake"}""",
      s"""{"tenant":"$Acme","database":"acme_lake","table":"pages","indexId":"x"}"""
    ).foreach { body =>
      val r = call(h, "delete", body, su)
      withClue(r.body())(errorCode(r.body()) shouldBe Some("invalid_search_target"))
    }
    val made = call(h, "create", createBody(Acme, "acme_lake"), su)
    val id   = field(made.body(), "indexId").flatMap(_.asString).get
    val drop =
      call(h, "delete", s"""{"tenant":"$Acme","database":"acme_lake","indexId":"$id"}""", su)
    withClue(drop.body())(drop.body() shouldBe """{"deleted":true}""")
  }
