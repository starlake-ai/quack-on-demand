// src/test/scala/ai/starlake/quack/security/HandlerAuditSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{FederatedSecret, FederatedSource, NodeSpec, RunningNode}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.*
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.{FederatedSourceOps, UserStore}
import ai.starlake.quack.ondemand.telemetry.{
  AuditEvent,
  AuditQuery,
  AuditRecorder,
  AuditRow,
  TelemetryStore
}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import java.time.Instant

/** Handler-level audit spec. Tests that [[RoleHandlers]] records control-plane audit events on the
  * two key outcomes:
  *   - `ok`: successful mutation with actor, tenant, target and detail populated.
  *   - `denied`: scope-gate rejection records the attempt before returning 403.
  *
  * Uses the same in-memory fixture stack as [[RbacTenantScopeSpec]] (no HTTP, no Postgres).
  */
class HandlerAuditSpec extends AnyFlatSpec with Matchers:

  // ---------------------------------------------------------------------------
  // Minimal TelemetryStore that collects events for assertion.
  // ---------------------------------------------------------------------------

  private class RecordingStore extends TelemetryStore:
    val enabled                                                 = true
    val events: scala.collection.mutable.ListBuffer[AuditEvent] =
      scala.collection.mutable.ListBuffer.empty
    def appendAudit(es: List[AuditEvent]): Unit  = events ++= es
    def listAudit(q: AuditQuery): List[AuditRow] = Nil
    def purgeAudit(olderThan: Instant): Int      = 0

  // ---------------------------------------------------------------------------
  // Stub QuackBackend: no real child processes (mirrors ManagerServerHarness).
  // ---------------------------------------------------------------------------

  private def stubBackend: QuackBackend = new QuackBackend:
    def start(s: NodeSpec): IO[RunningNode] =
      IO.pure(
        RunningNode(
          s.nodeId,
          s.poolKey,
          s.role,
          "127.0.0.1",
          21000,
          "tok",
          Some(1L),
          None,
          Instant.EPOCH,
          maxConcurrent = s.maxConcurrent
        )
      )
    def stop(id: String)    = IO.unit
    def isAlive(id: String) = true
    def discoverExisting()  = IO.pure(Nil)
    def cleanup()           = IO.unit

  // ---------------------------------------------------------------------------
  // DuckDB-backed UserStore (mirrors ManagerServerHarness.makeDuckDbUserStore).
  // ---------------------------------------------------------------------------

  private def makeDuckDbUserStore(): UserStore =
    Class.forName("org.duckdb.DuckDBDriver")
    val tmpFile = java.nio.file.Files.createTempFile("qod-audit-test-users", ".duckdb")
    tmpFile.toFile.delete()
    tmpFile.toFile.deleteOnExit()
    val jdbcUrl = s"jdbc:duckdb:${tmpFile.toAbsolutePath}"
    val c       = DriverManager.getConnection(jdbcUrl)
    try
      c.createStatement()
        .execute(
          """CREATE TABLE IF NOT EXISTS qodstate_user (
          |  id            TEXT PRIMARY KEY,
          |  tenant        TEXT,
          |  username      TEXT NOT NULL,
          |  password_hash TEXT NOT NULL,
          |  role          TEXT NOT NULL DEFAULT 'user',
          |  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          |  updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
          |)""".stripMargin
        )
    finally c.close()
    new UserStore(jdbcUrl, "", "")

  // ---------------------------------------------------------------------------
  // Build a RoleHandlers over the SecurityFixtures in-memory store.
  // Returns (handlers, tenantId).
  // ---------------------------------------------------------------------------

  private def buildRoleHandlers(audit: AuditRecorder): (RoleHandlers, String) =
    val fix     = SecurityFixtures.freshStore()
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(stubBackend, tracker, fix.store)
    sup.restore()
    val userStore    = makeDuckDbUserStore()
    val userHandlers = new UserHandlers(sup, userStore)
    val roles        = new RoleHandlers(sup, userHandlers, audit)
    (roles, SecurityFixtures.TenantId)

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  "createRole" should "audit ok with actor, action, tenant, and target on success" in {
    val store        = new RecordingStore
    val audit        = new AuditRecorder(store, _ => None)
    val (roles, tid) = buildRoleHandlers(audit)
    // Static-key token: scopeOf returns None (no session row) => TenantScopeCheck admits.
    roles
      .createRole(RoleCreateRequest(tid, "auditors", None), Some("any-static-key"))(_ => None)
      .unsafeRunSync()
    store.events should not be empty
    val e = store.events.head
    e.family shouldBe "control-plane"
    e.action shouldBe "role.create"
    e.outcome shouldBe "ok"
    e.tenant shouldBe Some(tid)
    e.actor shouldBe "static-key"
    e.target.isDefined shouldBe true
    e.detail.get("name") shouldBe Some("auditors")
  }

  it should "audit denied when the caller's session scope excludes the target tenant" in {
    val store        = new RecordingStore
    val audit        = new AuditRecorder(store, _ => None)
    val (roles, tid) = buildRoleHandlers(audit)
    // Non-superuser scope scoped to a DIFFERENT tenant: TenantScopeCheck rejects.
    val foreignScope = SessionScope(superuser = false, manageableTenants = Set("other-tenant"))
    roles
      .createRole(
        RoleCreateRequest(tid, "auditors", None),
        Some("alice-session-token")
      )(_ => Some(foreignScope))
      .unsafeRunSync()
    store.events should not be empty
    val e = store.events.head
    e.action shouldBe "role.create"
    e.outcome shouldBe "denied"
    e.tenant shouldBe Some(tid)
  }

  // ---------------------------------------------------------------------------
  // In-memory FederatedSourceOps stub for federation audit tests.
  // ---------------------------------------------------------------------------

  private class InMemoryFederatedSourceOps extends FederatedSourceOps:
    private val sources = scala.collection.mutable.Map.empty[String, FederatedSource]
    private val secrets = scala.collection.mutable.Map.empty[String, FederatedSecret]

    def upsertSource(s: FederatedSource): Unit                                = sources(s.id) = s
    def deleteSource(id: String): Unit                                        = sources -= id
    def getSource(tenantDbId: String, alias: String): Option[FederatedSource] =
      sources.values.find(s => s.tenantDbId == tenantDbId && s.alias == alias)
    def listSources(tenantDbId: String): List[FederatedSource] =
      sources.values.filter(_.tenantDbId == tenantDbId).toList.sortBy(_.alias)
    def upsertSecret(s: FederatedSecret): Unit             = secrets(s.id) = s
    def deleteSecret(sourceId: String, name: String): Unit =
      secrets.filterInPlace((_, s) => !(s.federatedSourceId == sourceId && s.name == name))
    def getSecret(sourceId: String, name: String): Option[FederatedSecret] =
      secrets.values.find(s => s.federatedSourceId == sourceId && s.name == name)
    def listSecrets(sourceId: String): List[FederatedSecret] =
      secrets.values.filter(_.federatedSourceId == sourceId).toList.sortBy(_.name)

  // ---------------------------------------------------------------------------
  // Build FederatedSourceHandlers over the in-memory store.
  // ---------------------------------------------------------------------------

  private def buildFederatedHandlers(audit: AuditRecorder): FederatedSourceHandlers =
    val memStore = new InMemoryFederatedSourceOps
    val tid      = SecurityFixtures.TenantId
    val tdId     = SecurityFixtures.TenantDbId
    val resolver = (tn: String, tdn: String) =>
      if tn == SecurityFixtures.TenantName && tdn == SecurityFixtures.TenantDbName then Some(tdId)
      else None
    val tenantIdResolver = (tn: String) =>
      if tn == SecurityFixtures.TenantName then Some(tid) else None
    new FederatedSourceHandlers(memStore, resolver, tenantIdResolver, audit)

  // ---------------------------------------------------------------------------
  // Federation audit tests
  // ---------------------------------------------------------------------------

  "FederatedSourceHandlers.createSource" should
    "audit ok with actor 'static-key' and tenant id on success" in {
      val store = new RecordingStore
      val audit = new AuditRecorder(store, _ => None)
      val h     = buildFederatedHandlers(audit)
      h.createSource(
        SecurityFixtures.TenantName,
        SecurityFixtures.TenantDbName,
        FederatedSourceCreateRequest(alias = "ext-s3", setupSql = "ATTACH ...", None, false),
        Some("any-static-key")
      ).unsafeRunSync()
      store.events should not be empty
      val e = store.events.head
      e.family shouldBe "control-plane"
      e.action shouldBe "federation.source.upsert"
      e.outcome shouldBe "ok"
      e.actor shouldBe "static-key"
      e.tenant shouldBe Some(SecurityFixtures.TenantId)
      e.target shouldBe Some("ext-s3")
    }
