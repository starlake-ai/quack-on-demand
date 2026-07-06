package ai.starlake.quack.ondemand.telemetry

import ai.starlake.quack.ondemand.state.LiquibaseRunner
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.state.testkit.TestPostgres.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.util.Try

class PostgresStatementStoreSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodstmt")

  private def withStore(test: PostgresTelemetryStore => Unit): Unit =
    if !TestPostgres.reachable then cancel("local Postgres not reachable")
    val dbName = s"qodstmt_test_${System.nanoTime()}"
    psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = dbUrl(dbName)
      new LiquibaseRunner(url, pgUser, pgPass).run()
      val store = new PostgresTelemetryStore(url, pgUser, pgPass)
      try test(store)
      finally store.close()
    finally Try(psql("postgres", s"""DROP DATABASE IF EXISTS "$dbName" WITH (FORCE)"""))

  private val t0 = Instant.parse("2026-07-06T10:00:00Z")
  private val t1 = Instant.parse("2026-07-06T11:00:00Z")
  private val t2 = Instant.parse("2026-07-06T12:00:00Z")

  private def ev(
      sql: String = "SELECT 1",
      username: String = "alice",
      tenant: String = "acme",
      pool: String = "bi",
      nodeId: String = "n1",
      durationMs: Long = 42L,
      prepareMs: Option[Long] = None,
      status: String = "ok",
      error: Option[String] = None,
      ts: Instant = t0
  ) = StatementEvent(ts, username, tenant, pool, nodeId, sql, durationMs, prepareMs, status, error)

  // ---------------------------------------------------------------------------
  // Round-trip and individual filters
  // ---------------------------------------------------------------------------

  "appendStatements / searchStatements" should "round-trip newest-first" in withStore { s =>
    val e1 = ev("SELECT 1", prepareMs = Some(5L), error = None)
    val e2 = ev("SELECT 2", prepareMs = None, error = Some("boom"), status = "error")
    val e3 = ev("SELECT 3", prepareMs = Some(0L), ts = t1)
    s.appendStatements(List(e1, e2, e3))
    val rows = s.searchStatements(StatementQuery())
    rows.size shouldBe 3
    rows.map(_.event.sql) shouldBe List("SELECT 3", "SELECT 2", "SELECT 1")
    val r1 = rows.find(_.event.sql == "SELECT 1").get
    r1.event.prepareMs shouldBe Some(5L)
    r1.event.error shouldBe None
    val r2 = rows.find(_.event.sql == "SELECT 2").get
    r2.event.prepareMs shouldBe None
    r2.event.error shouldBe Some("boom")
    r2.event.status shouldBe "error"
    val r3 = rows.find(_.event.sql == "SELECT 3").get
    r3.event.prepareMs shouldBe Some(0L)
  }

  it should "filter by tenant set" in withStore { s =>
    s.appendStatements(List(ev(tenant = "acme"), ev(tenant = "globex")))
    val acmeOnly = s.searchStatements(StatementQuery(tenants = Some(Set("acme"))))
    acmeOnly.map(_.event.tenant) shouldBe List("acme")
    s.searchStatements(StatementQuery(tenants = Some(Set("acme", "globex")))).size shouldBe 2
  }

  it should "return nothing for an empty tenant set" in withStore { s =>
    s.appendStatements(List(ev(tenant = "acme"), ev(tenant = "globex")))
    s.searchStatements(StatementQuery(tenants = Some(Set.empty))).size shouldBe 0
  }

  it should "filter by pool" in withStore { s =>
    s.appendStatements(List(ev(pool = "bi"), ev(pool = "etl")))
    val result = s.searchStatements(StatementQuery(pool = Some("bi")))
    result.map(_.event.pool) shouldBe List("bi")
  }

  it should "filter by user" in withStore { s =>
    s.appendStatements(List(ev(username = "alice"), ev(username = "bob")))
    val result = s.searchStatements(StatementQuery(user = Some("alice")))
    result.map(_.event.username) shouldBe List("alice")
  }

  it should "filter by status" in withStore { s =>
    s.appendStatements(List(ev(status = "ok"), ev(status = "error")))
    val result = s.searchStatements(StatementQuery(status = Some("error")))
    result.map(_.event.status) shouldBe List("error")
  }

  it should "filter by sql substring" in withStore { s =>
    s.appendStatements(List(ev("SELECT count(*) FROM orders"), ev("SELECT 1")))
    s.searchStatements(StatementQuery(q = Some("orders"))).size shouldBe 1
    s.searchStatements(StatementQuery(q = Some("SELECT"))).size shouldBe 2
  }

  it should "apply from (inclusive) and to (exclusive) time bounds" in withStore { s =>
    s.appendStatements(List(ev(ts = t0), ev(ts = t1), ev(ts = t2)))
    s.searchStatements(StatementQuery(from = Some(t1))).size shouldBe 2
    s.searchStatements(StatementQuery(to = Some(t1))).size shouldBe 1
    s.searchStatements(StatementQuery(from = Some(t1), to = Some(t2))).size shouldBe 1
  }

  // ---------------------------------------------------------------------------
  // Keyset pagination
  // ---------------------------------------------------------------------------

  "searchStatements keyset" should
    "have no overlap and page2 strictly older than page1" in withStore { s =>
      s.appendStatements((1 to 6).toList.map(i => ev(s"SELECT $i")))
      val page1 = s.searchStatements(StatementQuery(limit = 3))
      page1.size shouldBe 3
      val minId1 = page1.map(_.id).min
      val page2  = s.searchStatements(StatementQuery(limit = 3, beforeId = Some(minId1)))
      page2.size shouldBe 3
      val maxId2 = page2.map(_.id).max
      maxId2 should be < minId1
      (page1.map(_.id) ++ page2.map(_.id)).distinct.size shouldBe 6
    }

  // ---------------------------------------------------------------------------
  // Limit clamping
  // ---------------------------------------------------------------------------

  "searchStatements limit" should "clamp limit=0 to return exactly 1 row" in withStore { s =>
    s.appendStatements(List(ev("A"), ev("B"), ev("C")))
    s.searchStatements(StatementQuery(limit = 0)).size shouldBe 1
  }

  it should "clamp limit=9999 to at most 500" in withStore { s =>
    s.appendStatements(List(ev("A"), ev("B"), ev("C")))
    s.searchStatements(StatementQuery(limit = 9999)).size should be <= 500
  }

  // ---------------------------------------------------------------------------
  // Purge
  // ---------------------------------------------------------------------------

  "purgeStatements" should "delete only rows older than the cutoff and return the count" in
    withStore { s =>
      s.appendStatements(
        List(
          ev(ts = Instant.parse("2026-01-01T00:00:00Z")),
          ev(ts = Instant.parse("2026-03-01T00:00:00Z")),
          ev(ts = t0)
        )
      )
      val deleted = s.purgeStatements(Instant.parse("2026-06-01T00:00:00Z"))
      deleted shouldBe 2
      val remaining = s.searchStatements(StatementQuery())
      remaining.size shouldBe 1
      remaining.head.event.ts shouldBe t0
    }
