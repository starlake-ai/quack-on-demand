package ai.starlake.quack.ondemand.telemetry

import ai.starlake.quack.ondemand.state.LiquibaseRunner
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.state.testkit.TestPostgres.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.util.Try

class PostgresTelemetryStoreSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodtel")

  private def withStore(test: PostgresTelemetryStore => Unit): Unit =
    if !TestPostgres.reachable then cancel(s"local Postgres not reachable")
    val dbName = s"qodtel_test_${System.nanoTime()}"
    psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = dbUrl(dbName)
      new LiquibaseRunner(url, pgUser, pgPass).run()
      val store = new PostgresTelemetryStore(url, pgUser, pgPass)
      try test(store)
      finally store.close()
    finally Try(psql("postgres", s"""DROP DATABASE IF EXISTS "$dbName" WITH (FORCE)"""))

  private def ev(
      action: String,
      tenant: Option[String] = Some("t-a"),
      family: String = "control-plane",
      actor: String = "admin",
      ts: Instant = Instant.parse("2026-07-06T10:00:00Z"),
      detail: Map[String, String] = Map("name" -> "x")
  ) = AuditEvent(ts, family, actor, "system", tenant, action, Some("tgt-1"), "ok", "rest", detail)

  "appendAudit/listAudit" should "round-trip a batch newest-first" in withStore { s =>
    s.appendAudit(List(ev("role.create"), ev("role.delete"), ev("pool.scale")))
    val rows = s.listAudit(AuditQuery())
    rows.map(_.event.action) shouldBe List("pool.scale", "role.delete", "role.create")
    rows.head.event.detail shouldBe Map("name" -> "x")
    rows.head.event.tenant shouldBe Some("t-a")
  }

  it should "filter by family, tenant set, actor, action, and time range" in withStore { s =>
    s.appendAudit(
      List(
        ev("auth.login", tenant = None, family = "auth", actor = "root"),
        ev("role.create", tenant = Some("t-a")),
        ev("role.create", tenant = Some("t-b"), ts = Instant.parse("2026-07-06T12:00:00Z"))
      )
    )
    s.listAudit(AuditQuery(family = Some("auth"))).map(_.event.action) shouldBe List("auth.login")
    s.listAudit(AuditQuery(tenants = Some(Set("t-a")), includeNullTenant = false))
      .map(_.event.tenant) shouldBe List(Some("t-a"))
    s.listAudit(AuditQuery(actor = Some("root"))).map(_.event.actor) shouldBe List("root")
    s.listAudit(AuditQuery(action = Some("role.create"))).size shouldBe 2
    s.listAudit(AuditQuery(from = Some(Instant.parse("2026-07-06T11:00:00Z"))))
      .map(_.event.tenant) shouldBe List(Some("t-b"))
    s.listAudit(AuditQuery(to = Some(Instant.parse("2026-07-06T11:00:00Z")))).size shouldBe 2
  }

  it should "hide null-tenant rows when includeNullTenant=false, show them for superusers" in withStore {
    s =>
      s.appendAudit(
        List(ev("node.restart", tenant = None), ev("role.create", tenant = Some("t-a")))
      )
      s.listAudit(AuditQuery(tenants = Some(Set("t-a")), includeNullTenant = false)).size shouldBe 1
      s.listAudit(AuditQuery()).size shouldBe 2
  }

  it should "paginate with the keyset cursor" in withStore { s =>
    s.appendAudit((1 to 5).toList.map(i => ev(s"a.$i")))
    val page1 = s.listAudit(AuditQuery(limit = 2))
    page1.size shouldBe 2
    val page2 = s.listAudit(AuditQuery(limit = 2, beforeId = Some(page1.last.id)))
    page2.size shouldBe 2
    (page1 ++ page2).map(_.event.action) shouldBe List("a.5", "a.4", "a.3", "a.2")
  }

  it should "match q as a substring of action or target" in withStore { s =>
    s.appendAudit(List(ev("role.permission.grant"), ev("pool.scale")))
    s.listAudit(AuditQuery(q = Some("permission"))).size shouldBe 1
    s.listAudit(AuditQuery(q = Some("tgt-"))).size shouldBe 2
  }

  "purgeAudit" should "delete only rows past the cutoff" in withStore { s =>
    s.appendAudit(
      List(
        ev("old.event", ts = Instant.parse("2026-01-01T00:00:00Z")),
        ev("new.event", ts = Instant.parse("2026-07-06T10:00:00Z"))
      )
    )
    s.purgeAudit(Instant.parse("2026-06-01T00:00:00Z")) shouldBe 1
    s.listAudit(AuditQuery()).map(_.event.action) shouldBe List("new.event")
  }
