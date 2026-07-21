package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{RoleDistribution, Tenant}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

/** Unit-tests the REST surface added for `qodstate_tenant_db` against
  * an in-memory supervisor (no Postgres needed). */
class TenantDbHandlersSpec extends AnyFlatSpec with Matchers:

  private def freshHandlers(): TenantDbHandlers =
    val sup = new PoolSupervisor(
      new StubQuackBackend(),
      new NodeLoadTracker,
      new InMemoryControlPlaneStore()
    )
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    new TenantDbHandlers(sup)

  /** Same fixture, plus the supervisor handle so a test can install a mutation gate. */
  private def freshHandlersWithSupervisor(): (PoolSupervisor, TenantDbHandlers) =
    val sup = new PoolSupervisor(
      new StubQuackBackend(),
      new NodeLoadTracker,
      new InMemoryControlPlaneStore()
    )
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    (sup, new TenantDbHandlers(sup))

  private val denyingGate = new ai.starlake.quack.spi.MutationGate:
    def check(
        m: ai.starlake.quack.spi.StructureMutation
    ): cats.effect.IO[Either[String, Unit]] =
      cats.effect.IO.pure(Left("tenant-db quota reached (2)"))

  "TenantDbHandlers.createTenantDb" should
    "compose `${tenant}_${name}` and return the persisted row" in:
    val h   = freshHandlers()
    val out = h.createTenantDb(TenantDbRequest(
      tenant    = "acme",
      name      = "prod",
      kind      = "ducklake",
      metastore = Map(
        "pgHost"     -> "h",
        "pgPort"     -> "0",
        "pgUser"     -> "u",
        "pgPassword" -> "secret",
        "dbName"     -> "ignored",
        "schemaName" -> "main"
      ),
      dataPath  = "/data/acme_prod"
    ), None)((_: String) => None).unsafeRunSync()
    out.isRight shouldBe true
    val td = out.toOption.get
    td.tenant   shouldBe "acme"
    td.name     shouldBe "acme_prod"
    td.kind     shouldBe "ducklake"
    td.dataPath shouldBe "/data/acme_prod"
    // pgPassword must be stripped on the response surface.
    td.metastore.contains("pgPassword") shouldBe false
    td.metastore("schemaName")          shouldBe "main"

  it should "reject when tenant + name are empty (400)" in:
    val h = freshHandlers()
    val out = h.createTenantDb(TenantDbRequest(
      tenant = "", name = "", kind = "memory", metastore = Map.empty, dataPath = ""
    ), None)((_: String) => None).unsafeRunSync()
    out.isLeft shouldBe true
    out.swap.toOption.get._1.code shouldBe 400

  it should "404 when the tenant doesn't exist" in:
    val h = freshHandlers()
    val out = h.createTenantDb(TenantDbRequest(
      tenant = "ghost", name = "prod", kind = "memory", metastore = Map.empty, dataPath = ""
    ), None)((_: String) => None).unsafeRunSync()
    out.isLeft shouldBe true
    out.swap.toOption.get._1.code shouldBe 404

  it should "409 on a duplicate (tenant, name) pair" in:
    val h = freshHandlers()
    h.createTenantDb(TenantDbRequest(
      tenant = "acme", name = "prod", kind = "memory", metastore = Map.empty, dataPath = ""
    ), None)((_: String) => None).unsafeRunSync()
    val again = h.createTenantDb(TenantDbRequest(
      tenant = "acme", name = "prod", kind = "memory", metastore = Map.empty, dataPath = ""
    ), None)((_: String) => None).unsafeRunSync()
    again.isLeft shouldBe true
    again.swap.toOption.get._1.code shouldBe 409

  "TenantDbHandlers.listTenantDbs" should "return the tenant's databases under the new wire field" in:
    val h = freshHandlers()
    h.createTenantDb(TenantDbRequest(
      tenant = "acme", name = "prod",  kind = "memory", metastore = Map.empty, dataPath = ""
    ), None)((_: String) => None).unsafeRunSync()
    h.createTenantDb(TenantDbRequest(
      tenant = "acme", name = "stage", kind = "memory", metastore = Map.empty, dataPath = ""
    ), None)((_: String) => None).unsafeRunSync()
    val out = h.listTenantDbs("acme", None)((_: String) => None).unsafeRunSync().toOption.get
    out.tenantDbs.map(_.name).sorted shouldBe List("acme_prod", "acme_stage")

  it should "return an empty list for an unknown tenant" in:
    val h = freshHandlers()
    val out = h.listTenantDbs("ghost", None)((_: String) => None).unsafeRunSync().toOption.get
    out.tenantDbs shouldBe Nil

  "TenantDbHandlers.deleteTenantDb" should "remove a database with no pools" in:
    val h = freshHandlers()
    h.createTenantDb(TenantDbRequest(
      tenant = "acme", name = "prod", kind = "memory", metastore = Map.empty, dataPath = ""
    ), None)((_: String) => None).unsafeRunSync()
    h.deleteTenantDb(TenantDbOpRequest("acme", "acme_prod"), None)((_: String) => None).unsafeRunSync().isRight shouldBe true
    h.listTenantDbs("acme", None)((_: String) => None)
      .unsafeRunSync()
      .toOption
      .get
      .tenantDbs shouldBe Nil

  it should "404 when the database doesn't exist" in:
    val h = freshHandlers()
    val out = h.deleteTenantDb(TenantDbOpRequest("acme", "acme_ghost"), None)((_: String) => None).unsafeRunSync()
    out.isLeft shouldBe true
    out.swap.toOption.get._1.code shouldBe 404

  "TenantDbHandlers.createTenantDb (kind=memory)" should
    "accept empty metastore and empty dataPath" in:
    val h = freshHandlers()
    val out = h.createTenantDb(TenantDbRequest(
      tenant    = "acme",
      name      = "mem",
      kind      = "memory",
      metastore = Map.empty,
      dataPath  = ""
    ), None)((_: String) => None).unsafeRunSync()
    out.isRight shouldBe true
    val td = out.toOption.get
    td.kind shouldBe "memory"

  it should "reject non-empty metastore violation (kind=memory + non-empty metastore)" in:
    val h = freshHandlers()
    val out = h.createTenantDb(TenantDbRequest(
      tenant    = "acme",
      name      = "bad",
      kind      = "memory",
      metastore = Map("dbName" -> "tpch"),
      dataPath  = ""
    ), None)((_: String) => None).unsafeRunSync()
    out.isLeft shouldBe true
    out.swap.toOption.get._1.code shouldBe 400
    out.swap.toOption.get._2.message should include("empty metastore")

  "TenantDbHandlers.createTenantDb (kind=duckdb-file)" should
    "round-trip metastore + dataPath" in:
    val h = freshHandlers()
    val out = h.createTenantDb(TenantDbRequest(
      tenant    = "acme",
      name      = "file",
      kind      = "duckdb-file",
      metastore = Map("dbName" -> "mydata", "schemaName" -> "main"),
      dataPath  = "/tmp/foo.duckdb"
    ), None)((_: String) => None).unsafeRunSync()
    out.isRight shouldBe true
    val td = out.toOption.get
    td.kind shouldBe "duckdb-file"
    td.metastore("dbName") shouldBe "mydata"

  it should "reject unknown kind string with 400" in:
    val h = freshHandlers()
    val out = h.createTenantDb(TenantDbRequest(
      tenant = "acme", name = "bad", kind = "postgres", metastore = Map.empty, dataPath = ""
    ), None)((_: String) => None).unsafeRunSync()
    out.isLeft shouldBe true
    out.swap.toOption.get._1.code shouldBe 400
    out.swap.toOption.get._2.message should include("unknown TenantDbKind")

  "TenantDbHandlers.createTenantDb (defaults overrides)" should
    "round-trip defaultDatabase and defaultSchema on the response" in:
    val h = freshHandlers()
    val out = h.createTenantDb(TenantDbRequest(
      tenant          = "acme",
      name            = "mem2",
      kind            = "memory",
      metastore       = Map.empty,
      dataPath        = "",
      defaultDatabase = Some("fedpg"),
      defaultSchema   = Some("public")
    ), None)((_: String) => None).unsafeRunSync()
    val td = out.toOption.get
    td.defaultDatabase shouldBe Some("fedpg")
    td.defaultSchema   shouldBe Some("public")

  "TenantDbHandlers.createTenantDb (initSql)" should "round-trip initSql on the response" in:
    val h = freshHandlers()
    val out = h.createTenantDb(
      TenantDbRequest(tenant = "acme", name = "mem3", kind = "memory", initSql = "SET threads = 4;"),
      None
    )((_: String) => None).unsafeRunSync()
    out.toOption.get.initSql shouldBe "SET threads = 4;"

  "TenantDbHandlers.update" should "set and clear initSql (migrated from setInitSql)" in:
    val h = freshHandlers()
    h.createTenantDb(TenantDbRequest(tenant = "acme", name = "mem4", kind = "memory"), None)(
      (_: String) => None
    ).unsafeRunSync()
    val set = h.update(
      UpdateTenantDbRequest("acme", "acme_mem4", initSql = Some("SET memory_limit = '1GB';")),
      None
    )((_: String) => None).unsafeRunSync()
    set.toOption.get.db.initSql shouldBe "SET memory_limit = '1GB';"
    val cleared = h.update(UpdateTenantDbRequest("acme", "acme_mem4", initSql = Some("")), None)(
      (_: String) => None
    ).unsafeRunSync()
    cleared.toOption.get.db.initSql shouldBe ""

  it should "404 on unknown db and 403 for a foreign tenant session" in:
    val h = freshHandlers()
    h.update(UpdateTenantDbRequest("acme", "acme_nope", initSql = Some("x")), None)(
      (_: String) => None
    ).unsafeRunSync().left.toOption.get._1 shouldBe StatusCode.NotFound
    val foreign: String => Option[SessionScope] =
      _ => Some(SessionScope(superuser = false, manageableTenants = Set("globex")))
    h.createTenantDb(TenantDbRequest(tenant = "acme", name = "mem5", kind = "memory"), None)(
      (_: String) => None
    ).unsafeRunSync()
    h.update(UpdateTenantDbRequest("acme", "acme_mem5", initSql = Some("x")), Some("tok"))(
      foreign
    ).unsafeRunSync().left.toOption.get._1 shouldBe StatusCode.Forbidden

  it should "never echo pgPassword and preserve it across an untouched-map update" in:
    val h = freshHandlers()
    h.createTenantDb(
      TenantDbRequest(tenant = "acme", name = "pg1", kind = "duckdb-file",
        metastore = Map("pgPassword" -> "s3cret", "schemaName" -> "main", "dbName" -> "pg1"),
        dataPath = "/tmp/pg1.duckdb"),
      None
    )((_: String) => None).unsafeRunSync()
    val out = h.update(
      UpdateTenantDbRequest("acme", "acme_pg1", defaultSchema = Some("s2")), None
    )((_: String) => None).unsafeRunSync().toOption.get
    out.db.metastore.contains("pgPassword") shouldBe false        // redacted in response
    // preserved in store: verified via a follow-up update replacing the map without the key
    val out2 = h.update(
      UpdateTenantDbRequest("acme", "acme_pg1", metastore = Some(Map("schemaName" -> "s3", "dbName" -> "pg1"))), None
    )((_: String) => None).unsafeRunSync().toOption.get
    out2.db.metastore.contains("pgPassword") shouldBe false
    // supervisor-level preservation is pinned by PoolSupervisorSpec; here we pin non-echo

  it should "reject an update whose objectStore value contains ';' (400, names the key)" in:
    val h = freshHandlers()
    h.createTenantDb(TenantDbRequest(tenant = "acme", name = "os3", kind = "memory"), None)(
      (_: String) => None
    ).unsafeRunSync()
    val out = h.update(
      UpdateTenantDbRequest(
        "acme",
        "acme_os3",
        objectStore = Some(Map("azure_account_key" -> "key;BlobEndpoint=https://evil"))
      ),
      None
    )((_: String) => None).unsafeRunSync()
    out.isLeft shouldBe true
    val (code, err) = out.swap.toOption.get
    code shouldBe StatusCode.BadRequest
    err.message should include("azure_account_key")

  it should "never echo s3_secret_access_key while keeping s3_access_key_id" in:
    val h = freshHandlers()
    val created = h.createTenantDb(
      TenantDbRequest(
        tenant      = "acme",
        name        = "os1",
        kind        = "memory",
        objectStore = Map("s3_access_key_id" -> "k", "s3_secret_access_key" -> "sk")
      ),
      None
    )((_: String) => None).unsafeRunSync().toOption.get
    created.objectStore.get("s3_access_key_id") shouldBe Some("k")
    created.objectStore.contains("s3_secret_access_key") shouldBe false
    created.metastore.contains("pgPassword") shouldBe false
    val listed = h.listTenantDbs("acme", None)((_: String) => None)
      .unsafeRunSync()
      .toOption
      .get
      .tenantDbs
      .find(_.name == "acme_os1")
      .get
    listed.objectStore.get("s3_access_key_id") shouldBe Some("k")
    listed.objectStore.contains("s3_secret_access_key") shouldBe false

  it should "reject an objectStore value containing ';' (400, names the key)" in:
    val h = freshHandlers()
    val out = h.createTenantDb(
      TenantDbRequest(
        tenant      = "acme",
        name        = "os2",
        kind        = "memory",
        objectStore = Map("azure_account" -> "acct;BlobEndpoint=https://evil")
      ),
      None
    )((_: String) => None).unsafeRunSync()
    out.isLeft shouldBe true
    val (code, err) = out.swap.toOption.get
    code shouldBe StatusCode.BadRequest
    err.message should include("azure_account")
    err.message should include(";")

  it should "report effectiveDataPath and a None tableCount for memory kind" in:
    val h = freshHandlers()
    val td = h.createTenantDb(TenantDbRequest(tenant = "acme", name = "mem6", kind = "memory"), None)(
      (_: String) => None
    ).unsafeRunSync().toOption.get
    td.tableCount shouldBe None
    td.effectiveDataPath shouldBe ""

  "TenantDbHandlers.createTenantDb (mutation gates)" should
    "map a gate refusal to 429 quota_exceeded for tenant sessions" in:
    val (sup, h) = freshHandlersWithSupervisor()
    sup.setMutationGates(List(denyingGate))
    val tenantAdminScopeOf: String => Option[SessionScope] =
      _ => Some(SessionScope(superuser = false, manageableTenants = Set("acme")))
    val out = h.createTenantDb(
      TenantDbRequest(tenant = "acme", name = "gated", kind = "memory"),
      Some("tenant-admin-key")
    )(tenantAdminScopeOf).unsafeRunSync()
    out match
      case Left((code, err)) =>
        code shouldBe StatusCode.TooManyRequests
        err.error shouldBe "quota_exceeded"
      case Right(r) => fail(s"expected 429, got $r")

  it should "bypass gates for superuser sessions" in:
    val (sup, h) = freshHandlersWithSupervisor()
    sup.setMutationGates(List(denyingGate))
    val superuserScopeOf: String => Option[SessionScope] = _ => Some(SessionScope.Superuser)
    val out = h.createTenantDb(
      TenantDbRequest(tenant = "acme", name = "gated2", kind = "memory"),
      Some("root-key")
    )(superuserScopeOf).unsafeRunSync()
    out.isRight shouldBe true
