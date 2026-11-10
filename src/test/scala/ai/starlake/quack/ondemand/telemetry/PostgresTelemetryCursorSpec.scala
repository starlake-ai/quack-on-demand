package ai.starlake.quack.ondemand.telemetry

import ai.starlake.quack.ondemand.state.LiquibaseRunner
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.state.testkit.TestPostgres.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.{Connection, DriverManager}
import java.time.Instant
import java.util.concurrent.{CompletableFuture, TimeUnit}
import scala.util.Try

/** The ascending cursor on real SQL, and the writer serialization that makes it safe: ids become
  * visible in order because appends to one table commit one at a time.
  */
class PostgresTelemetryCursorSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodcur")

  private def withStore(test: (PostgresTelemetryStore, String) => Unit): Unit =
    if !TestPostgres.reachable then cancel("local Postgres not reachable")
    val dbName = s"qodcur_test_${System.nanoTime()}"
    psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = dbUrl(dbName)
      new LiquibaseRunner(url, pgUser, pgPass).run()
      val store = new PostgresTelemetryStore(url, pgUser, pgPass)
      try test(store, url)
      finally store.close()
    finally Try(psql("postgres", s"""DROP DATABASE IF EXISTS "$dbName" WITH (FORCE)"""))

  private val Ts = Instant.parse("2026-07-06T10:00:00Z")

  private def audit(action: String, tenant: String = "t-a") =
    AuditEvent(
      Ts,
      "control-plane",
      "admin",
      "system",
      Some(tenant),
      action,
      None,
      "ok",
      "rest",
      Map()
    )

  private def statement(sql: String, tenant: String = "t-a") =
    StatementEvent(Ts, "alice", tenant, "bi", "n1", sql, 1L, None, "ok", None)

  /** Holds the append lock in an open transaction, as an in-flight writer would. */
  private def holdingLock(url: String, key: Int)(test: Connection => Unit): Unit =
    val holder = DriverManager.getConnection(url, pgUser, pgPass)
    try
      holder.setAutoCommit(false)
      val ps = holder.prepareStatement("SELECT pg_advisory_xact_lock(?, ?)")
      try
        ps.setInt(1, PostgresTelemetryStore.AppendLockClass)
        ps.setInt(2, key)
        ps.execute()
      finally ps.close()
      test(holder)
    finally holder.close()

  "listAudit with afterId" should "return later rows only, oldest first" in withStore { (s, _) =>
    s.appendAudit(List(audit("a"), audit("b"), audit("c")))
    val ids   = s.listAudit(AuditQuery()).map(_.id).sorted
    val after = s.listAudit(AuditQuery(afterId = Some(ids.head)))
    after.map(_.id) shouldBe ids.tail
    after.map(_.event.action) shouldBe List("b", "c")
  }

  it should "apply the limit to the oldest rows" in withStore { (s, _) =>
    s.appendAudit(List(audit("a"), audit("b"), audit("c")))
    s.listAudit(AuditQuery(afterId = Some(0L), limit = 2)).map(_.event.action) shouldBe
      List("a", "b")
  }

  it should "still honor the tenant filter" in withStore { (s, _) =>
    s.appendAudit(List(audit("a"), audit("b", tenant = "t-b"), audit("c")))
    s.listAudit(AuditQuery(afterId = Some(0L), tenants = Some(Set("t-a"))))
      .map(_.event.action) shouldBe
      List("a", "c")
  }

  it should "leave the newest-first order alone without a cursor" in withStore { (s, _) =>
    s.appendAudit(List(audit("a"), audit("b")))
    s.listAudit(AuditQuery()).map(_.event.action) shouldBe List("b", "a")
  }

  "searchStatements with afterId" should "return later rows only, oldest first" in withStore {
    (s, _) =>
      s.appendStatements(List(statement("q1"), statement("q2"), statement("q3")))
      val ids   = s.searchStatements(StatementQuery()).map(_.id).sorted
      val after = s.searchStatements(StatementQuery(afterId = Some(ids.head)))
      after.map(_.id) shouldBe ids.tail
      after.map(_.event.sql) shouldBe List("q2", "q3")
  }

  "appendAudit" should "wait until an in-flight writer has committed" in withStore { (s, url) =>
    holdingLock(url, PostgresTelemetryStore.AuditAppendLock) { holder =>
      val pending = CompletableFuture.runAsync(() => s.appendAudit(List(audit("waiting"))))
      Try(pending.get(700, TimeUnit.MILLISECONDS))
      withClue("the append must block while the lock is held: ") {
        pending.isDone shouldBe false
      }
      s.listAudit(AuditQuery()) shouldBe empty
      holder.commit()
      pending.get(10, TimeUnit.SECONDS)
    }
    s.listAudit(AuditQuery()).map(_.event.action) shouldBe List("waiting")
  }

  "appendStatements" should "wait until an in-flight writer has committed" in withStore {
    (s, url) =>
      holdingLock(url, PostgresTelemetryStore.StatementAppendLock) { holder =>
        val pending =
          CompletableFuture.runAsync(() => s.appendStatements(List(statement("waiting"))))
        Try(pending.get(700, TimeUnit.MILLISECONDS))
        withClue("the append must block while the lock is held: ") {
          pending.isDone shouldBe false
        }
        holder.commit()
        pending.get(10, TimeUnit.SECONDS)
      }
      s.searchStatements(StatementQuery()).map(_.event.sql) shouldBe List("waiting")
  }

  "a failed append" should "leave nothing behind and release the lock" in withStore { (s, _) =>
    val invalid = audit("x").copy(action = null)
    Try(s.appendAudit(List(invalid))).isFailure shouldBe true
    s.appendAudit(List(audit("after-failure")))
    s.listAudit(AuditQuery()).map(_.event.action) shouldBe List("after-failure")
  }
