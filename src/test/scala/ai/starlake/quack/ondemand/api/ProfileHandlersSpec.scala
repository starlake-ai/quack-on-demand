package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.auth.AuthenticatedProfile
import ai.starlake.quack.edge.{StatementHistoryStore, StatementRecord}
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.telemetry._
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/** Unit-level pin on the self-scoping contract of `/api/profile/usage` and
  * `/api/profile/statements`: identity comes from the verified session ONLY, and the rows a caller
  * sees are exactly the rows recorded for that (tenant, username). No server boot, no Postgres --
  * the telemetry store and the tenant lookup are stubs.
  */
class ProfileHandlersSpec extends AnyFlatSpec with Matchers:

  private val Now = Instant.parse("2026-08-14T12:00:00Z")

  // Records land in telemetry under the tenant id OR its display name depending
  // on the shape the router carried at execution time, so the stub holds both.
  private val T1 = Tenant(id = "t1", displayName = "acme")
  private val T2 = Tenant(id = "t2", displayName = "globex")

  private def day(statements: Long): UsageDay =
    UsageDay(day = Now, statements = statements, errors = 0L, engineMs = statements * 10L)

  private def group(tenant: String, user: String, statements: Long): UsageGroup =
    UsageGroup(
      tenant = tenant,
      pool = None,
      username = Some(user),
      statements = statements,
      errors = 1L,
      denied = 2L,
      engineMs = statements * 10L,
      days = List(day(statements))
    )

  /** Telemetry stub: returns the canned groups verbatim and records the last query, so a test can
    * assert on the window the handler computed. Deliberately ignores `q.tenants` -- the handler's
    * own filter is what the security contract rests on.
    */
  private final class StubTelemetry(groups: List[UsageGroup]) extends TelemetryStore:
    @volatile var lastQuery: Option[UsageQuery]                                               = None
    val enabled                                                                               = true
    def appendAudit(events: List[AuditEvent]): Unit                                           = ()
    def listAudit(q: AuditQuery): List[AuditRow]                                              = Nil
    def purgeAudit(olderThan: Instant): Int                                                   = 0
    override def appendStatements(events: List[StatementEvent]): Unit                         = ()
    override def searchStatements(q: StatementQuery): List[StatementRow]                      = Nil
    override def purgeStatements(olderThan: Instant): Int                                     = 0
    override def rollupWatermark(): Option[Instant]                                           = None
    override def recomputeRollups(fromExclusive: Option[Instant], toInclusive: Instant): Unit = ()
    override def advanceRollupWatermark(to: Instant): Unit                                    = ()
    override def queryRollups(q: RollupQuery): List[RollupBucket]                             = Nil
    override def purgeRollups(granularity: String, olderThan: Instant): Int                   = 0
    override def queryUsage(q: UsageQuery): UsageResult                                       =
      lastQuery = Some(q)
      UsageResult(groups, Some(Now.minusSeconds(86400L * 90)))

  private def tenantById(id: String): Option[Tenant] =
    List(T1, T2).find(_.id == id)

  private def record(user: String, tenant: String, sql: String): StatementRecord =
    StatementRecord(
      ts = Now,
      user = user,
      tenant = tenant,
      pool = "bi",
      nodeId = "n1",
      sql = sql,
      durationMs = 5L,
      status = "ok",
      error = None
    )

  private def handlers(
      tokens: SessionTokenStore,
      telemetry: TelemetryStore = new StubTelemetry(Nil),
      history: StatementHistoryStore = new StatementHistoryStore()
  ): ProfileHandlers =
    new ProfileHandlers(tokens.get, telemetry, history, tenantById, () => Now)

  private def sessionFor(
      tokens: SessionTokenStore,
      user: String,
      tenant: Option[String],
      role: String = "user",
      scope: SessionScope = SessionScope(superuser = false, manageableTenants = Set.empty)
  ): String =
    tokens.mintWithScope(
      AuthenticatedProfile(
        username = user,
        role = role,
        groups = Set.empty,
        claims = Map.empty,
        authMethod = "db",
        tenant = tenant
      ),
      scope
    )

  // ------------------------------------------------------------------
  // profile usage
  // ------------------------------------------------------------------

  "profile usage" should "return only the session user's groups" in {
    val tokens    = new SessionTokenStore()
    val telemetry = new StubTelemetry(
      List(group("t1", "carol", 10L), group("t1", "bob", 99L))
    )
    val token = sessionFor(tokens, "carol", Some("t1"))
    val out   = handlers(tokens, telemetry = telemetry).usage(None, Some(token)).unsafeRunSync()

    val resp = out.getOrElse(fail(s"expected a usage response, got $out"))
    resp.groupBy shouldBe "user"
    resp.groups.map(_.username) shouldBe List(Some("carol"))
    resp.groups.head.statements shouldBe 10L
    resp.groups.head.errors shouldBe 1L
    resp.groups.head.denied shouldBe 2L
    resp.groups.head.engineMs shouldBe 100L
    resp.groups.head.days.map(_.statements) shouldBe List(10L)
    resp.dataStart shouldBe Some(Now.minusSeconds(86400L * 90).toString)
  }

  it should "match rollup rows recorded under the tenant display name" in {
    val tokens    = new SessionTokenStore()
    val telemetry = new StubTelemetry(
      List(
        group("acme", "carol", 7L), // display-name shape
        group("t1", "carol", 3L),   // id shape
        group("globex", "carol", 1000L)
      )
    )
    val token = sessionFor(tokens, "carol", Some("t1"))
    val resp  = handlers(tokens, telemetry = telemetry)
      .usage(None, Some(token))
      .unsafeRunSync()
      .getOrElse(fail("expected a usage response"))

    resp.groups.map(_.statements).sorted shouldBe List(3L, 7L)
    resp.groups.map(_.tenant).toSet shouldBe Set("acme", "t1")
  }

  it should "cap days at 365 and default to 30" in {
    val tokens = new SessionTokenStore()
    val token  = sessionFor(tokens, "carol", Some("t1"))

    val defaulted = new StubTelemetry(Nil)
    handlers(tokens, telemetry = defaulted).usage(None, Some(token)).unsafeRunSync()
    val d = defaulted.lastQuery.getOrElse(fail("no usage query issued"))
    d.to shouldBe Now
    d.from shouldBe Now.minusSeconds(30L * 86400L)
    d.groupBy shouldBe "user"

    val capped = new StubTelemetry(Nil)
    handlers(tokens, telemetry = capped).usage(Some(9999), Some(token)).unsafeRunSync()
    capped.lastQuery
      .getOrElse(fail("no usage query issued"))
      .from shouldBe Now.minusSeconds(365L * 86400L)

    val floored = new StubTelemetry(Nil)
    handlers(tokens, telemetry = floored).usage(Some(0), Some(token)).unsafeRunSync()
    floored.lastQuery
      .getOrElse(fail("no usage query issued"))
      .from shouldBe Now.minusSeconds(1L * 86400L)
  }

  it should "answer 400 no_session_identity for an unknown token" in {
    val tokens = new SessionTokenStore()
    val out    = handlers(tokens).usage(None, Some("static-key-not-a-jwt")).unsafeRunSync()
    out.isLeft shouldBe true
    val (status, err) = out.swap.getOrElse(fail("expected an error"))
    status.code shouldBe 400
    err.error shouldBe "no_session_identity"

    val absent = handlers(tokens).usage(None, None).unsafeRunSync()
    absent.swap.getOrElse(fail("expected an error"))._2.error shouldBe "no_session_identity"
  }

  // ------------------------------------------------------------------
  // profile statements
  // ------------------------------------------------------------------

  "profile statements" should "return only the session user's rows, newest first" in {
    val tokens  = new SessionTokenStore()
    val history = new StatementHistoryStore()
    history.record(record("carol", "t1", "select 1"))
    history.record(record("bob", "t1", "select 2"))
    history.record(record("carol", "acme", "select 3"))

    val token = sessionFor(tokens, "carol", Some("t1"))
    val resp  = handlers(tokens, history = history)
      .statements(None, Some(token))
      .unsafeRunSync()
      .getOrElse(fail("expected a statements response"))

    resp.statements.map(_.sql) shouldBe List("select 3", "select 1")
    resp.statements.map(_.user).distinct shouldBe List("carol")
  }

  it should "not leak another tenant's rows for the same username" in {
    val tokens  = new SessionTokenStore()
    val history = new StatementHistoryStore()
    history.record(record("carol", "t2", "t2 secret"))
    history.record(record("carol", "globex", "t2 secret by display name"))
    history.record(record("carol", "t1", "mine"))

    val token = sessionFor(tokens, "carol", Some("t1"))
    val resp  = handlers(tokens, history = history)
      .statements(None, Some(token))
      .unsafeRunSync()
      .getOrElse(fail("expected a statements response"))

    resp.statements.map(_.sql) shouldBe List("mine")
  }

  it should "cap limit at 500" in {
    val tokens  = new SessionTokenStore()
    val history = new StatementHistoryStore(capacity = 600)
    // 3 rows for another user first: the filter must run over the whole window,
    // not eat the caller's budget.
    (1 to 3).foreach(i => history.record(record("bob", "t1", s"bob $i")))
    (1 to 520).foreach(i => history.record(record("carol", "t1", s"carol $i")))

    val token = sessionFor(tokens, "carol", Some("t1"))
    val h     = handlers(tokens, history = history)

    val capped = h
      .statements(Some(9999), Some(token))
      .unsafeRunSync()
      .getOrElse(fail("expected a statements response"))
    capped.statements.size shouldBe 500
    capped.statements.map(_.user).distinct shouldBe List("carol")

    val defaulted = h
      .statements(None, Some(token))
      .unsafeRunSync()
      .getOrElse(fail("expected a statements response"))
    defaulted.statements.size shouldBe 50

    val floored = h
      .statements(Some(0), Some(token))
      .unsafeRunSync()
      .getOrElse(fail("expected a statements response"))
    floored.statements.size shouldBe 1
  }

  it should "answer 400 no_session_identity for an unknown token" in {
    val tokens = new SessionTokenStore()
    val out    = handlers(tokens).statements(None, Some("static-key-not-a-jwt")).unsafeRunSync()
    out.isLeft shouldBe true
    val (status, err) = out.swap.getOrElse(fail("expected an error"))
    status.code shouldBe 400
    err.error shouldBe "no_session_identity"
  }

  // ------------------------------------------------------------------
  // tenant scope comes from the SESSION SCOPE, not profile.tenant
  //
  // An OIDC tenant-scoped login mints profile.tenant = None with
  // superuser = false and manageableTenants = {A}. Keying the filter off
  // profile.tenant would drop the tenant predicate entirely and expose every
  // tenant's rows for a shared username.
  // ------------------------------------------------------------------

  private val carolEverywhere = List(
    group("t1", "carol", 3L),
    group("acme", "carol", 7L),
    group("t2", "carol", 1000L),
    group("globex", "carol", 2000L)
  )

  private def historyEverywhere(): StatementHistoryStore =
    val history = new StatementHistoryStore()
    history.record(record("carol", "t2", "t2 secret"))
    history.record(record("carol", "globex", "t2 secret by display name"))
    history.record(record("carol", "t1", "mine by id"))
    history.record(record("carol", "acme", "mine by display name"))
    history

  "a tenant-scoped session with no profile tenant" should "still see only its own tenant's usage" in {
    val tokens    = new SessionTokenStore()
    val telemetry = new StubTelemetry(carolEverywhere)
    val token     = sessionFor(
      tokens,
      "carol",
      None,
      scope = SessionScope(superuser = false, manageableTenants = Set("t1"))
    )
    val resp = handlers(tokens, telemetry = telemetry)
      .usage(None, Some(token))
      .unsafeRunSync()
      .getOrElse(fail("expected a usage response"))

    resp.groups.map(_.tenant).toSet shouldBe Set("t1", "acme")
    resp.groups.map(_.statements).sorted shouldBe List(3L, 7L)
    // the store-side hint is narrowed too, not just the in-memory filter
    telemetry.lastQuery.flatMap(_.tenants) shouldBe Some(Set("t1", "acme"))
  }

  it should "still see only its own tenant's statements" in {
    val tokens = new SessionTokenStore()
    val token  = sessionFor(
      tokens,
      "carol",
      None,
      scope = SessionScope(superuser = false, manageableTenants = Set("t1"))
    )
    val resp = handlers(tokens, history = historyEverywhere())
      .statements(None, Some(token))
      .unsafeRunSync()
      .getOrElse(fail("expected a statements response"))

    resp.statements.map(_.sql).toSet shouldBe Set("mine by id", "mine by display name")
  }

  it should "see nothing at all when it has no resolvable tenant (fail closed)" in {
    val tokens    = new SessionTokenStore()
    val telemetry = new StubTelemetry(carolEverywhere)
    val token     = sessionFor(tokens, "carol", None)

    val usage = handlers(tokens, telemetry = telemetry)
      .usage(None, Some(token))
      .unsafeRunSync()
      .getOrElse(fail("expected a usage response"))
    usage.groups shouldBe empty
    telemetry.lastQuery.flatMap(_.tenants) shouldBe Some(Set.empty[String])

    val stmts = handlers(tokens, history = historyEverywhere())
      .statements(None, Some(token))
      .unsafeRunSync()
      .getOrElse(fail("expected a statements response"))
    stmts.statements shouldBe empty
  }

  "a superuser session" should "see the same username across every tenant" in {
    val tokens    = new SessionTokenStore()
    val telemetry = new StubTelemetry(carolEverywhere)
    val token = sessionFor(tokens, "carol", None, role = "admin", scope = SessionScope.Superuser)

    val usage = handlers(tokens, telemetry = telemetry)
      .usage(None, Some(token))
      .unsafeRunSync()
      .getOrElse(fail("expected a usage response"))
    usage.groups.map(_.tenant).toSet shouldBe Set("t1", "acme", "t2", "globex")
    telemetry.lastQuery.flatMap(_.tenants) shouldBe None

    val stmts = handlers(tokens, history = historyEverywhere())
      .statements(None, Some(token))
      .unsafeRunSync()
      .getOrElse(fail("expected a statements response"))
    stmts.statements.map(_.sql).toSet shouldBe Set(
      "t2 secret",
      "t2 secret by display name",
      "mine by id",
      "mine by display name"
    )
  }

  "a non-superuser session with an explicit profile tenant" should "see only that tenant" in {
    val tokens    = new SessionTokenStore()
    val telemetry = new StubTelemetry(carolEverywhere)
    // manageableTenants deliberately names the OTHER tenant: profile.tenant wins
    // for a scoped session, and it must not widen to the manageable set.
    val token = sessionFor(
      tokens,
      "carol",
      Some("t1"),
      scope = SessionScope(superuser = false, manageableTenants = Set("t2"))
    )

    val usage = handlers(tokens, telemetry = telemetry)
      .usage(None, Some(token))
      .unsafeRunSync()
      .getOrElse(fail("expected a usage response"))
    usage.groups.map(_.tenant).toSet shouldBe Set("t1", "acme")

    val stmts = handlers(tokens, history = historyEverywhere())
      .statements(None, Some(token))
      .unsafeRunSync()
      .getOrElse(fail("expected a statements response"))
    stmts.statements.map(_.sql).toSet shouldBe Set("mine by id", "mine by display name")
  }
