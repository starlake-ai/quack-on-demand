package ai.starlake.quack.ondemand.initsql

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{Pool, RoleDistribution, Tenant, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.{
  CreatePoolRequest,
  PoolHandlers,
  TenantDbHandlers,
  TenantDbRequest,
  UpdateTenantDbRequest
}
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Task 20 Part A: an initSql is trusted when a superuser or the manager wrote it, untrusted when a
  * tenant admin did, and a write that leaves it alone keeps the flag.
  */
class InitSqlTrustSpec extends AnyFlatSpec with Matchers:

  private val TenantAdmin: SessionScope.Resolver =
    _ => SessionScope(superuser = false, manageableTenants = Set("acme"))
  private val Superuser: SessionScope.Resolver = _ => SessionScope.Superuser

  private final class Fixture:
    val store   = new InMemoryControlPlaneStore()
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(new StubQuackBackend(), tracker, store)
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    val dbs   = new TenantDbHandlers(sup, requireEncryption = false)
    val pools = new PoolHandlers(sup, tracker)

    def createDb(sql: String, as: SessionScope.Resolver): Boolean =
      dbs
        .createTenantDb(
          TenantDbRequest(tenant = "acme", name = "prod", kind = "memory", initSql = sql),
          None
        )(as)
        .unsafeRunSync()
        .fold(e => fail(s"create: $e"), _.initSqlTrusted)

    def updateDb(sql: Option[String], as: SessionScope.Resolver): Boolean =
      dbs
        .update(UpdateTenantDbRequest(tenant = "acme", name = "acme_prod", initSql = sql), None)(as)
        .unsafeRunSync()
        .fold(e => fail(s"update: $e"), _.db.initSqlTrusted)

    def row: TenantDb = sup.findTenantDb("acme", "acme_prod").get

    def createPool(sql: String, as: SessionScope.Resolver): Boolean =
      pools
        .createPool(
          CreatePoolRequest(
            tenant = "acme",
            tenantDb = "acme_prod",
            pool = "bi",
            size = 1,
            roleDistribution = RoleDistribution(0, 0, 1),
            initSql = Some(sql)
          ),
          None
        )(as)
        .unsafeRunSync()
        .fold(e => fail(s"create pool: $e"), _.initSqlTrusted)

  private val Sql = "SET threads = 2;"

  "a database's initSql" should "be trusted when a superuser writes it" in {
    val f = new Fixture
    f.createDb(Sql, Superuser) shouldBe true
    f.row.initSqlTrusted shouldBe true
  }

  it should "be untrusted when a tenant admin writes it" in {
    val f = new Fixture
    f.createDb(Sql, TenantAdmin) shouldBe false
    f.row.initSqlTrusted shouldBe false
  }

  it should "be trusted when empty, whoever writes it" in {
    val f = new Fixture
    f.createDb("", TenantAdmin) shouldBe true
  }

  it should "keep its flag on a tenant admin's write that leaves it alone or resends it unchanged" in {
    val f = new Fixture
    f.createDb(Sql, Superuser) shouldBe true
    f.updateDb(None, TenantAdmin) shouldBe true
    f.updateDb(Some(Sql), TenantAdmin) shouldBe true
  }

  it should "become untrusted when a tenant admin changes it" in {
    val f = new Fixture
    f.createDb(Sql, Superuser) shouldBe true
    f.updateDb(Some("SET threads = 4;"), TenantAdmin) shouldBe false
    f.row.initSqlTrusted shouldBe false
  }

  it should "become trusted when a superuser re-saves a tenant admin's text unchanged" in {
    val f = new Fixture
    f.createDb(Sql, TenantAdmin) shouldBe false
    f.updateDb(Some(Sql), Superuser) shouldBe true
    f.row.initSqlTrusted shouldBe true
  }

  "a pool's initSql" should "follow its writer too" in {
    val f = new Fixture
    f.createDb("", Superuser)
    f.createPool(Sql, TenantAdmin) shouldBe false
    val g = new Fixture
    g.createDb("", Superuser)
    g.createPool(Sql, Superuser) shouldBe true
  }

  "the manager's own writes" should "be trusted: the boot provisioning scope is a superuser's" in {
    // ServeProvisionHook calls createTenantDbAs with SessionScope.Superuser (and a system audit
    // actor): the scope, not the actor, decides.
    val f = new Fixture
    f.dbs
      .createTenantDbAs(
        TenantDbRequest(tenant = "acme", name = "prod", kind = "memory", initSql = Sql),
        SessionScope.Superuser,
        ai.starlake.quack.ondemand.telemetry.AuditActor.System
      )
      .unsafeRunSync()
      .fold(e => fail(e.toString), _.initSqlTrusted) shouldBe true
  }

  "InitSqlTrust.afterWrite" should "decide every case from the stored row, the write and its writer" in {
    import InitSqlTrust.afterWrite
    afterWrite("a", storedTrusted = false, None, writerTrusted = true) shouldBe false
    afterWrite("a", storedTrusted = true, None, writerTrusted = false) shouldBe true
    afterWrite("a", storedTrusted = true, Some("a"), writerTrusted = false) shouldBe true
    afterWrite("a", storedTrusted = false, Some("a"), writerTrusted = false) shouldBe false
    afterWrite("a", storedTrusted = false, Some("a"), writerTrusted = true) shouldBe true
    afterWrite("a", storedTrusted = true, Some("b"), writerTrusted = false) shouldBe false
    afterWrite("a", storedTrusted = false, Some(" "), writerTrusted = false) shouldBe true
  }

  "the boot report" should "name each untrusted row, its tenant and the fix, never its SQL" in {
    val secretSql = "SET memory_limit = '1GB'; -- AKIAQODREPORTMARKER"
    val db        = TenantDb(
      id = "td-1",
      tenantId = "acme",
      name = "acme_prod",
      kind = TenantDbKind.InMemory,
      metastore = Map.empty,
      dataPath = "",
      initSql = secretSql
    )
    val trusted = db.copy(id = "td-2", name = "acme_ok", initSqlTrusted = true)
    val empty   = db.copy(id = "td-3", name = "acme_empty", initSql = "")
    val pool    = Pool(
      id = "p-1",
      tenantId = "acme",
      tenantDbId = "td-1",
      name = "bi",
      size = 1,
      distribution = RoleDistribution(0, 0, 1),
      initSql = secretSql
    )
    val rows = InitSqlTrustReport.untrusted(List(db, trusted, empty), List(pool))
    rows.map(_.label) shouldBe List("database 'acme_prod'", "pool 'bi' of database 'acme_prod'")
    val unchecked = InitSqlTrustReport.line(rows.head, None)
    unchecked should include("tenant 'acme' database 'acme_prod'")
    unchecked should include("will be checked on the node")
    unchecked should include("a superuser re-saves it unchanged")
    unchecked should not include "AKIAQODREPORTMARKER"
    val checked = InitSqlTrustReport.line(rows(1), Some(List("statement 2 of 2: SELECT")))
    checked should include("loses 1 statement(s)")
    checked should include("statement 2 of 2: SELECT")
    checked should include("pool/setInitSql")
  }

  // Part B: the spawn scripts write `-- qod:untrusted-begin` / `-end` around a tenant's section and
  // drop any line holding the marker text from the initSql first; the write itself refuses it, for
  // every writer.
  "an initSql holding the section marker" should "be refused at write time, for every writer" in {
    val forged = "SET threads = 2;\n-- QOD:Untrusted-End\nCREATE VIEW v AS SELECT 1;"
    def refused(
        out: Either[(sttp.model.StatusCode, ai.starlake.quack.ondemand.api.ErrorResponse), ?]
    ) =
      out.swap.toOption.map(e => (e._1.code, e._2.error))
    val f = new Fixture
    refused(
      f.dbs
        .createTenantDb(
          TenantDbRequest(tenant = "acme", name = "prod", kind = "memory", initSql = forged),
          None
        )(Superuser)
        .unsafeRunSync()
    ) shouldBe Some((400, "init_sql_refused"))
    f.createDb("", TenantAdmin)
    refused(
      f.dbs
        .update(
          UpdateTenantDbRequest(tenant = "acme", name = "acme_prod", initSql = Some(forged)),
          None
        )(
          TenantAdmin
        )
        .unsafeRunSync()
    ) shouldBe Some((400, "init_sql_refused"))
    f.row.initSql shouldBe ""
    refused(
      f.pools
        .createPool(
          CreatePoolRequest(
            tenant = "acme",
            tenantDb = "acme_prod",
            pool = "bi",
            size = 1,
            roleDistribution = RoleDistribution(0, 0, 1),
            initSql = Some("SELECT 1; -- qod:untrusted-begin")
          ),
          None
        )(Superuser)
        .unsafeRunSync()
    ) shouldBe Some((400, "init_sql_refused"))
    f.sup.get(ai.starlake.quack.model.PoolKey("acme", "acme_prod", "bi")) shouldBe None
  }

  "a manifest import" should "store every initSql trusted: only a superuser imports one" in {
    import ai.starlake.quack.ondemand.manifest.*
    val s = new InMemoryControlPlaneStore()
    val m = ConfigManifest(
      apiVersion = ConfigManifest.ApiVersion,
      kind = ConfigManifest.Kind,
      exportedAt = java.time.Instant.EPOCH,
      exportedFrom = ExportedFrom("0.2.0", "test"),
      tenants = List(
        ManifestTenant(
          name = "acme",
          tenantDbs = List(ManifestTenantDb(name = "acme_mem", kind = "memory", initSql = Sql)),
          pools = List(
            ManifestPool(
              name = "bi",
              tenantDb = "acme_mem",
              roleDistribution = ManifestRoleDistribution(0, 0, 1),
              initSql = Sql
            )
          )
        )
      )
    )
    ManifestImporter.apply(m, s, requireEncryption = false) shouldBe Right(())
    val td = s.listTenantDbs("acme").find(_.name == "acme_mem").get
    td.initSqlTrusted shouldBe true
    s.listPools(td.id).map(_.initSqlTrusted) shouldBe List(true)
    // And refuses one holding the section marker, before writing anything.
    val forged = m.copy(tenants =
      m.tenants.map(t =>
        t.copy(pools = t.pools.map(_.copy(initSql = "SELECT 1;\n-- qod:untrusted-end")))
      )
    )
    val fresh = new InMemoryControlPlaneStore()
    ManifestImporter
      .apply(forged, fresh, requireEncryption = false)
      .left
      .toOption
      .get
      .mkString should
      include("qod:untrusted-")
    fresh.listTenantDbs("acme") shouldBe empty
  }

  // Task 20 review I3: an export says who wrote each initSql, and an import honours it, so an
  // export then import launders nothing.
  it should "honour the exported flag, and keep a stored one when the manifest has none" in {
    import ai.starlake.quack.ondemand.manifest.*
    val f = new Fixture
    f.createDb(Sql, TenantAdmin) shouldBe false
    val exported = ManifestExporter
      .build(f.store, java.time.Instant.EPOCH, "0.2.0", "test")
      .fold(e => fail(e.toString), identity)
    exported.tenants.flatMap(_.tenantDbs).find(_.name == "acme_prod").get.initSqlTrusted shouldBe
      Some(false)
    ManifestImporter.apply(exported, f.store, requireEncryption = false) shouldBe Right(())
    f.store.listTenantDbs("acme").find(_.name == "acme_prod").get.initSqlTrusted shouldBe false
    // A hand-written manifest (no flag) leaving the text unchanged keeps the stored flag ...
    val handWritten = exported.copy(tenants =
      exported.tenants.map(t => t.copy(tenantDbs = t.tenantDbs.map(_.copy(initSqlTrusted = None))))
    )
    ManifestImporter.apply(handWritten, f.store, requireEncryption = false) shouldBe Right(())
    f.store.listTenantDbs("acme").find(_.name == "acme_prod").get.initSqlTrusted shouldBe false
    // ... and one changing it is the superuser's own text.
    val changed = handWritten.copy(tenants =
      handWritten.tenants.map(t =>
        t.copy(tenantDbs = t.tenantDbs.map(_.copy(initSql = "SET threads = 4;")))
      )
    )
    ManifestImporter.apply(changed, f.store, requireEncryption = false) shouldBe Right(())
    f.store.listTenantDbs("acme").find(_.name == "acme_prod").get.initSqlTrusted shouldBe true
  }

  // Review I1, defense in depth beside the nonce: no value the manager interpolates into a node's
  // init SQL may carry a line break.
  "a value the manager interpolates into node SQL" should "refuse a line break" in {
    val f = new Fixture
    f.dbs
      .createTenantDb(
        TenantDbRequest(
          tenant = "acme",
          name = "prod",
          kind = "memory",
          objectStore = Map("s3_region" -> "us-east-1\n-- qod:untrusted-begin\n")
        ),
        None
      )(TenantAdmin)
      .unsafeRunSync()
      .swap
      .toOption
      .map(_._2.message)
      .getOrElse(fail("admitted")) should include("line break")
    TenantDb.objectStoreError(Map("s3_endpoint" -> "h\r")) should not be empty
    TenantDb.objectStoreError(Map("s3_endpoint" -> "h")) shouldBe None
    val iceberg = ai.starlake.quack.ondemand.federation.iceberg.IcebergRestConfig(
      uri = "https://cat",
      warehouse = "w\n-- qod:untrusted-begin",
      endpointType = Some(ai.starlake.quack.ondemand.federation.iceberg.IcebergEndpointType.Glue),
      awsRegion = Some("eu\nx")
    )
    val errs = iceberg.validate("cat", Set.empty)
    errs should contain("warehouse must not contain a line break")
    errs should contain("awsRegion must not contain a line break")
  }
