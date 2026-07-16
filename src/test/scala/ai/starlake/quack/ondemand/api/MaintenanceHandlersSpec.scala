package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{DbAdmin, InMemoryControlPlaneStore}
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

class MaintenanceHandlersSpec extends AnyFlatSpec with Matchers:

  // build sup + store + handlers; mirrors TagHandlersSpec's private setup pattern.
  private var tenantDbName: String                                      = ""
  private def setup(): (MaintenanceHandlers, InMemoryControlPlaneStore) =
    val store = new InMemoryControlPlaneStore()
    val admin = new DbAdmin:
      def createDatabase(name: String): Either[String, Unit] = Right(())
      def dropDatabase(name: String): Either[String, Unit]   = Right(())
    val backend = new StubQuackBackend()
    val sup = new PoolSupervisor(
      backend,
      new NodeLoadTracker,
      store,
      dbAdmin = admin
    )
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    val td = sup
      .createTenantDb(
        "acme",
        "db1",
        TenantDbKind.DuckLake,
        Map(
          "pgHost"     -> "127.0.0.1",
          "pgPort"     -> "0",
          "pgUser"     -> "u",
          "pgPassword" -> "p",
          "dbName"     -> "ignored",
          "schemaName" -> "main"
        ),
        "/tmp/qod-maint-test"
      )
      .unsafeRunSync()
      .toOption
      .get
    tenantDbName = td.name
    // A non-DuckLake tenant-db to exercise the invalid_kind gate.
    sup
      .createTenantDb(
        "acme",
        "mem1",
        TenantDbKind.InMemory,
        Map.empty,
        ""
      )
      .unsafeRunSync()
    val handlers = new MaintenanceHandlers(sup, store)
    (handlers, store)

  /** Claims and finishes whatever run is currently queued/running for acme/tenantDbName, so a
    * subsequent `triggerRun` in the same test doesn't 409 `run_active` (F3). Tests that fire
    * several manual runs back-to-back use this to simulate them completing in sequence rather than
    * piling up concurrently.
    */
  private def drainActiveRun(store: InMemoryControlPlaneStore): Unit =
    store.claimQueuedMaintenanceRun().foreach { r =>
      store.finishMaintenanceRun(r.id, "succeeded", ai.starlake.quack.model.RunCounters(), None)
      ()
    }

  private def upsertReq(
      name: String = tenantDbName,
      scopeKind: String = "tenantdb",
      scopeSchema: Option[String] = None,
      scopeTable: Option[String] = None,
      retentionDays: Option[Int] = Some(7),
      cron: Option[String] = Some("0 3 * * *")
  ) =
    MaintenancePolicyUpsertRequest(
      tenant = "acme",
      tenantDb = name,
      scopeKind = scopeKind,
      scopeSchema = scopeSchema,
      scopeTable = scopeTable,
      enabled = Some(true),
      retentionDays = retentionDays,
      compactionEnabled = Some(true),
      targetFileSize = Some("auto"),
      smallFileMinCount = Some(12),
      rewriteDeleteThreshold = Some(0.2),
      cleanupGraceDays = Some(1),
      orphanMinAgeDays = Some(1),
      cron = cron
    )

  "upsertPolicy" should "404 an unknown tenant, 400 a bad scopeKind, 400 a bad cron" in {
    val (h, _) = setup()

    h.upsertPolicy(upsertReq().copy(tenant = "ghost"), None)(_ => None)
      .unsafeRunSync()
      .left
      .toOption
      .get
      ._1 shouldBe StatusCode.NotFound

    h.upsertPolicy(upsertReq(scopeKind = "bogus"), None)(_ => None)
      .unsafeRunSync()
      .left
      .toOption
      .get
      ._1 shouldBe StatusCode.BadRequest

    h.upsertPolicy(upsertReq(cron = Some("not a cron")), None)(_ => None)
      .unsafeRunSync()
      .left
      .toOption
      .get
      ._1 shouldBe StatusCode.BadRequest
  }

  it should "reject a scopeKind whose matching scope fields are missing (invalid_scope)" in {
    val (h, _) = setup()
    val out    = h
      .upsertPolicy(upsertReq(scopeKind = "schema", scopeSchema = None), None)(_ => None)
      .unsafeRunSync()
    out.left.toOption.get._1 shouldBe StatusCode.BadRequest
    out.left.toOption.get._2.error shouldBe "invalid_scope"

    val out2 = h
      .upsertPolicy(
        upsertReq(scopeKind = "table", scopeSchema = Some("s"), scopeTable = None),
        None
      )(_ => None)
      .unsafeRunSync()
    out2.left.toOption.get._1 shouldBe StatusCode.BadRequest
    out2.left.toOption.get._2.error shouldBe "invalid_scope"
  }

  it should "reject a retentionDays < 1 (invalid_value)" in {
    val (h, _) = setup()
    val out    = h.upsertPolicy(upsertReq(retentionDays = Some(0)), None)(_ => None).unsafeRunSync()
    out.left.toOption.get._1 shouldBe StatusCode.BadRequest
    out.left.toOption.get._2.error shouldBe "invalid_value"
  }

  it should "create then replace a policy row on the same scope" in {
    val (h, store) = setup()
    val created    = h.upsertPolicy(upsertReq(), None)(_ => None).unsafeRunSync()
    created.isRight shouldBe true
    created.toOption.get.retentionDays shouldBe Some(7)

    val replaced =
      h.upsertPolicy(upsertReq(retentionDays = Some(14)), None)(_ => None).unsafeRunSync()
    replaced.isRight shouldBe true
    replaced.toOption.get.retentionDays shouldBe Some(14)

    // Still a single row for the (tenant, tenantDb, tenantdb-scope) tuple.
    store.listMaintenancePolicies("acme", tenantDbName).size shouldBe 1
  }

  it should "persist normalized scope fields, not the raw request's stray scopeSchema (F1)" in {
    val (h, store) = setup()
    // scopeKind=tenantdb with a stray scopeSchema must be normalized away before persisting,
    // both times, so the two upserts collide on the same canonical tenantdb-scope row instead
    // of creating a second distinct row under the COALESCE(scope_schema,'') unique index.
    val first = h
      .upsertPolicy(upsertReq(scopeKind = "tenantdb", scopeSchema = Some("junk")), None)(_ => None)
      .unsafeRunSync()
    first.isRight shouldBe true
    first.toOption.get.scopeSchema shouldBe None

    val second = h
      .upsertPolicy(
        upsertReq(scopeKind = "tenantdb", scopeSchema = Some("junk"), retentionDays = Some(14)),
        None
      )(_ => None)
      .unsafeRunSync()
    second.isRight shouldBe true
    second.toOption.get.scopeSchema shouldBe None

    val rows = store.listMaintenancePolicies("acme", tenantDbName)
    rows.size shouldBe 1
    rows.head.scopeSchema shouldBe None
  }

  "deletePolicy" should "404 an unknown id and resolve the owning tenant for the scope gate" in {
    val (h, _) = setup()
    h.deletePolicy(MaintenancePolicyDeleteRequest("nope"), None)(_ => None)
      .unsafeRunSync()
      .left
      .toOption
      .get
      ._1 shouldBe StatusCode.NotFound

    val created = h.upsertPolicy(upsertReq(), None)(_ => None).unsafeRunSync().toOption.get
    // A scope check that always rejects proves the tenant is resolved from the row
    // (not just accepted blindly) before the gate runs.
    val rejectAll = (_: String) =>
      Some(
        ai.starlake.quack.ondemand.auth
          .SessionScope(superuser = false, manageableTenants = Set.empty)
      )
    h.deletePolicy(MaintenancePolicyDeleteRequest(created.id), Some("tok"))(rejectAll)
      .unsafeRunSync()
      .left
      .toOption
      .get
      ._1 shouldBe StatusCode.Forbidden

    h.deletePolicy(MaintenancePolicyDeleteRequest(created.id), None)(_ => None)
      .unsafeRunSync()
      .isRight shouldBe true
  }

  "listPolicies" should "return rows plus the effective tenantdb policy" in {
    val (h, _) = setup()
    h.upsertPolicy(upsertReq(retentionDays = Some(21)), None)(_ => None).unsafeRunSync()
    val out = h.listPolicies("acme", tenantDbName, None)(_ => None).unsafeRunSync()
    out.isRight shouldBe true
    val body = out.toOption.get
    body.rows.size shouldBe 1
    body.rows.head.retentionDays shouldBe Some(21)
    body.effective.retentionDays shouldBe 21
  }

  it should "404 an unknown tenant-db" in {
    val (h, _) = setup()
    h.listPolicies("acme", "no-such-db", None)(_ => None)
      .unsafeRunSync()
      .left
      .toOption
      .get
      ._1 shouldBe StatusCode.NotFound
  }

  "triggerRun" should "enqueue a manual run and 400 an invalid operations csv" in {
    val (h, store) = setup()
    val req        = MaintenanceRunRequest("acme", tenantDbName, None, None)
    val out        = h.triggerRun(req, None)(_ => None).unsafeRunSync()
    out.isRight shouldBe true
    store.listMaintenanceRuns("acme", tenantDbName, 10, None).size shouldBe 1

    val bad = h
      .triggerRun(
        MaintenanceRunRequest("acme", tenantDbName, None, Some("flush,bogus")),
        None
      )(_ => None)
      .unsafeRunSync()
    bad.left.toOption.get._1 shouldBe StatusCode.BadRequest
    bad.left.toOption.get._2.error shouldBe "invalid_operations"
  }

  it should "accept every csv operation individually" in {
    val (h, store) = setup()
    List("flush", "expire", "merge", "rewrite", "cleanup", "orphans").foreach { op =>
      val out = h
        .triggerRun(MaintenanceRunRequest("acme", tenantDbName, None, Some(op)), None)(_ => None)
        .unsafeRunSync()
      withClue(s"operation=$op")(out.isRight shouldBe true)
      // Drain the run so the next trigger doesn't 409 run_active (F3): each iteration in this
      // loop is a fresh manual trigger, not a concurrent one.
      drainActiveRun(store)
    }
  }

  it should "reject malformed scopes with 400 invalid_scope" in {
    val (h, _) = setup()
    List("table:noDot", "schema:sales", "table:a.b.c").foreach { scope =>
      val out = h
        .triggerRun(MaintenanceRunRequest("acme", tenantDbName, Some(scope), None), None)(_ => None)
        .unsafeRunSync()
      withClue(s"scope=$scope") {
        out.left.toOption.get._1 shouldBe StatusCode.BadRequest
        out.left.toOption.get._2.error shouldBe "invalid_scope"
      }
    }
  }

  it should "reject a scope carrying a quote/SQL-injection payload (F1)" in {
    val (h, _) = setup()
    val out    = h
      .triggerRun(
        MaintenanceRunRequest("acme", tenantDbName, Some("table:s.t'); drop--"), None),
        None
      )(_ => None)
      .unsafeRunSync()
    out.left.toOption.get._1 shouldBe StatusCode.BadRequest
    out.left.toOption.get._2.error shouldBe "invalid_scope"
  }

  it should "reject a scope with interior whitespace (T7)" in {
    val (h, _) = setup()
    val out    = h
      .triggerRun(MaintenanceRunRequest("acme", tenantDbName, Some("table: a.b"), None), None)(_ =>
        None
      )
      .unsafeRunSync()
    out.left.toOption.get._1 shouldBe StatusCode.BadRequest
    out.left.toOption.get._2.error shouldBe "invalid_scope"
  }

  "triggerRun" should "409 run_active when a run is already queued or running" in {
    val (h, _) = setup()
    val first  =
      h.triggerRun(MaintenanceRunRequest("acme", tenantDbName, None, None), None)(_ => None)
        .unsafeRunSync()
    first.isRight shouldBe true

    val second =
      h.triggerRun(MaintenanceRunRequest("acme", tenantDbName, None, None), None)(_ => None)
        .unsafeRunSync()
    second.left.toOption.get._1 shouldBe StatusCode.Conflict
    second.left.toOption.get._2.error shouldBe "run_active"
  }

  it should "accept the tenantdb and table:<schema>.<table> scope forms" in {
    val (h, store) = setup()
    List("tenantdb", "table:tpch1.region").foreach { scope =>
      val out = h
        .triggerRun(MaintenanceRunRequest("acme", tenantDbName, Some(scope), None), None)(_ => None)
        .unsafeRunSync()
      withClue(s"scope=$scope")(out.isRight shouldBe true)
      drainActiveRun(store)
    }
    store
      .listMaintenanceRuns("acme", tenantDbName, 10, None)
      .map(_.scope)
      .toSet shouldBe Set("tenantdb", "table:tpch1.region")
  }

  it should "400 a non-ducklake tenant-db (invalid_kind, matching TagHandlers)" in {
    val (h, _) = setup()
    val out    = h
      .triggerRun(MaintenanceRunRequest("acme", "acme_mem1", None, None), None)(_ => None)
      .unsafeRunSync()
    out.left.toOption.get._1 shouldBe StatusCode.BadRequest
    out.left.toOption.get._2.error shouldBe "invalid_kind"
  }

  it should "404 an unknown tenant" in {
    val (h, _) = setup()
    h.triggerRun(MaintenanceRunRequest("ghost", tenantDbName, None, None), None)(_ => None)
      .unsafeRunSync()
      .left
      .toOption
      .get
      ._1 shouldBe StatusCode.NotFound
  }

  "listRuns" should "page newest-first with the before cursor" in {
    val (h, store) = setup()
    val ids        = (1 to 5).map { _ =>
      val id = h
        .triggerRun(MaintenanceRunRequest("acme", tenantDbName, None, None), None)(_ => None)
        .unsafeRunSync()
        .toOption
        .get
        .id
      drainActiveRun(store)
      id
    }

    val page1 = h.listRuns("acme", tenantDbName, Some(2), None, None)(_ => None).unsafeRunSync()
    page1.isRight shouldBe true
    val rows1 = page1.toOption.get
    rows1.map(_.id) shouldBe List(ids(4), ids(3))

    val page2 =
      h.listRuns("acme", tenantDbName, Some(2), Some(rows1.last.id), None)(_ => None)
        .unsafeRunSync()
        .toOption
        .get
    page2.map(_.id) shouldBe List(ids(2), ids(1))
  }

  it should "404 an unknown tenant-db" in {
    val (h, _) = setup()
    h.listRuns("acme", "no-such-db", None, None, None)(_ => None)
      .unsafeRunSync()
      .left
      .toOption
      .get
      ._1 shouldBe StatusCode.NotFound
  }

  it should "clamp the limit to [1, 500]" in {
    val (h, store) = setup()
    (1 to 3).foreach { _ =>
      h.triggerRun(MaintenanceRunRequest("acme", tenantDbName, None, None), None)(_ => None)
        .unsafeRunSync()
        .isRight shouldBe true
      drainActiveRun(store)
    }
    // limit=0 clamps up to 1: exactly one row comes back, not zero and not all three.
    h.listRuns("acme", tenantDbName, Some(0), None, None)(_ => None)
      .unsafeRunSync()
      .toOption
      .get
      .size shouldBe 1
    // limit=9999 clamps down to 500; with only 3 rows this returns all 3 (and would cap at 500).
    h.listRuns("acme", tenantDbName, Some(9999), None, None)(_ => None)
      .unsafeRunSync()
      .toOption
      .get
      .size shouldBe 3
  }
