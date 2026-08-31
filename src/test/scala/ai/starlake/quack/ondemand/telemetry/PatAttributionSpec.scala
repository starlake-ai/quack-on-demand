package ai.starlake.quack.ondemand.telemetry

import ai.starlake.quack.ondemand.state.LiquibaseRunner
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.util.Try

/** Which credential acted, not only which human owns it (changelog 0034). `pat_id` is nullable and
  * additive on both `qodstate_stmt_history` and `qodstate_audit`: session-authenticated and
  * static-key callers keep writing NULL, and a PAT-authenticated caller's row carries the acting
  * token id.
  */
class PatAttributionSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodatt")

  private def withFreshDb(test: PostgresTelemetryStore => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodatt_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val store = new PostgresTelemetryStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try test(store)
      finally store.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  private def stmt(username: String, patId: Option[String]) = StatementEvent(
    ts = Instant.parse("2026-07-06T10:00:00Z"),
    username = username,
    tenant = "acme",
    pool = "default",
    nodeId = "n1",
    sql = "SELECT 1",
    durationMs = 5L,
    prepareMs = None,
    status = "ok",
    error = None,
    patId = patId
  )

  private def audit(actor: String, patId: Option[String]) = AuditEvent(
    ts = Instant.parse("2026-07-06T10:00:00Z"),
    family = "data-write",
    actor = actor,
    actorRealm = "tenant",
    tenant = Some("acme"),
    action = "sql.write",
    target = None,
    outcome = "ok",
    origin = "flightsql",
    detail = Map.empty,
    patId = patId
  )

  "statement history" should "record the acting token id for a PAT-executed statement" in
    withFreshDb { store =>
      store.appendStatements(List(stmt("alice-agent", patId = Some("pat-1"))))
      val rows = store.searchStatements(StatementQuery())
      rows.map(_.event.patId) shouldBe List(Some("pat-1"))
    }

  it should "leave pat_id NULL for a session-executed statement" in
    withFreshDb { store =>
      store.appendStatements(List(stmt("alice", patId = None)))
      val rows = store.searchStatements(StatementQuery())
      rows.map(_.event.patId) shouldBe List(None)
    }

  it should "distinguish a PAT row from a session row in the same batch" in
    withFreshDb { store =>
      store.appendStatements(
        List(
          stmt("alice", patId = None),
          stmt("alice-agent", patId = Some("pat-2"))
        )
      )
      val rows = store.searchStatements(StatementQuery())
      // newest-first: the second append is first.
      rows.map(r => (r.event.username, r.event.patId)) shouldBe List(
        ("alice-agent", Some("pat-2")),
        ("alice", None)
      )
    }

  "audit" should "record the acting token id for a PAT-executed action" in
    withFreshDb { store =>
      store.appendAudit(List(audit("alice-agent", patId = Some("pat-3"))))
      val rows = store.listAudit(AuditQuery())
      rows.map(_.event.patId) shouldBe List(Some("pat-3"))
    }

  it should "leave pat_id NULL for a static-key-executed action" in
    withFreshDb { store =>
      store.appendAudit(List(audit("static-key", patId = None)))
      val rows = store.listAudit(AuditQuery())
      rows.map(_.event.patId) shouldBe List(None)
    }
