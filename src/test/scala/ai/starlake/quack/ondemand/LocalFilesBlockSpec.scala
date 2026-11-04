package ai.starlake.quack.ondemand

import ai.starlake.quack.Banner
import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{PoolKey, RoleDistribution, RunningNode, Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.api.{
  CreatePoolRequest,
  NodeHandlers,
  NodeOpRequest,
  PoolHandlers,
  ResumePoolRequest,
  ScalePoolRequest,
  TenantDbHandlers,
  TenantDbRequest,
  UpdateTenantDbRequest
}
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.ha.StateChangePublisher
import ai.starlake.quack.ondemand.manifest.{
  ConfigManifest,
  ExportedFrom,
  ManifestImporter,
  ManifestPool,
  ManifestRoleDistribution,
  ManifestTenant,
  ManifestTenantDb
}
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import ai.starlake.quack.ondemand.telemetry.AuditActor
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** What the supervisor does with a database whose data path is local while nodes have local files
  * off (`QOD_NODE_ALLOW_LOCAL_FILES=false`): such a row is blocked and starts no node, a create
  * that would make one is refused for every caller, and a manifest still imports one. Local files
  * on, nothing of this happens.
  */
class LocalFilesBlockSpec extends AnyFlatSpec with Matchers:

  private val Setting = "QOD_NODE_ALLOW_LOCAL_FILES"

  private val Defaults = Map(
    "pgHost"     -> "localhost",
    "pgPort"     -> "1",
    "pgUser"     -> "postgres",
    "pgPassword" -> "pw",
    "dbName"     -> "qod",
    "schemaName" -> "main",
    "dataPath"   -> "/data/ducklake"
  )
  private val RemoteDefaults = Defaults.updated("dataPath", "s3://bucket/ducklake")

  /** A backend counting the nodes it was asked to start. */
  private final class Counting(allow: Boolean) extends QuackBackend:
    private val inner                              = StubQuackBackend.noop()
    val started                                    = new AtomicInteger(0)
    override def allowsLocalFiles: Boolean         = allow
    def start(s: ai.starlake.quack.model.NodeSpec) =
      started.incrementAndGet()
      inner.start(s)
    def stop(k: PoolKey, id: String)                          = inner.stop(k, id)
    def isAlive(id: String)                                   = inner.isAlive(id)
    def discoverExisting(): cats.effect.IO[List[RunningNode]] = inner.discoverExisting()
    def cleanup()                                             = inner.cleanup()

  private final class Fixture(allow: Boolean = false, defaults: Map[String, String] = Defaults):
    val backend = new Counting(allow)
    val store   = new InMemoryControlPlaneStore()
    val tracker = new NodeLoadTracker
    val inits   = new AtomicInteger(0)
    val sup     = new PoolSupervisor(
      backend,
      tracker,
      store,
      defaultMetastore = defaults,
      duckLakeInitializer = (_, _, _) =>
        inits.incrementAndGet()
        ()
    )
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    val pools     = new PoolHandlers(sup, tracker)
    val tenantDbs = new TenantDbHandlers(sup, requireEncryption = false)
    val nodes     = new NodeHandlers(sup, tracker, store, StateChangePublisher.noop)

    /** Rows as a manifest writes them: no create path, no check, and the pool's nodes left to
      * reconcile.
      */
    def imported(rows: (String, String, String, Map[String, String])*): Unit =
      val manifest = ConfigManifest(
        ConfigManifest.ApiVersion,
        ConfigManifest.Kind,
        Instant.EPOCH,
        ExportedFrom("test", "test"),
        tenants = List(
          ManifestTenant(
            name = "acme",
            tenantDbs = rows.toList.map { (name, kind, path, meta) =>
              ManifestTenantDb(name = s"acme_$name", kind = kind, dataPath = path, metastore = meta)
            },
            pools = rows.toList.map { (name, _, _, _) =>
              ManifestPool(
                name = name,
                tenantDb = s"acme_$name",
                roleDistribution = ManifestRoleDistribution(0, 0, 1)
              )
            }
          )
        )
      )
      ManifestImporter
        .apply(manifest, store, requireEncryption = false, tenantFilesRoot = None) shouldBe
        Right(())
      sup.restore()

    def create(
        name: String,
        kind: String,
        path: String = "",
        meta: Map[String, String] = Map.empty,
        scope: SessionScope = SessionScope.Superuser
    ) =
      tenantDbs
        .createTenantDbAs(
          TenantDbRequest(
            tenant = "acme",
            name = name,
            kind = kind,
            dataPath = path,
            metastore = meta
          ),
          scope,
          AuditActor.System
        )
        .unsafeRunSync()

    def rowsNamed = store.snapshot().tenantDbs.map(_.name)

  private val Lake     = Map.empty[String, String]
  private val FileMeta = Map("dbName" -> "cat", "schemaName" -> "main")

  /** Every form of a row a node with local files off cannot open. */
  private val LocalRows = List(
    ("abs", "ducklake", "/data/abs/", Lake),
    ("rel", "ducklake", "./ducklake/rel", Lake),
    ("url", "ducklake", "file:///data/url/", Lake),
    ("derived", "ducklake", "", Lake),
    ("file", "duckdb-file", "/data/file.duckdb", FileMeta),
    ("mem", "memory", "/data/mem/", Lake)
  )

  /** Every form it can. */
  private val RemoteRows = List(
    ("s3lake", "ducklake", "s3://bucket/s3lake/", Lake),
    ("azlake", "ducklake", "az://container/azlake/", Lake),
    ("s3file", "duckdb-file", "s3://bucket/s3file.duckdb", FileMeta),
    ("bare", "memory", "", Lake),
    ("scoped", "memory", "s3://bucket/scoped/", Lake)
  )

  "a row a node cannot open" should "start no node, and be reported on every surface" in {
    val f = new Fixture
    f.imported(LocalRows*)
    f.sup.reconcile().unsafeRunSync()
    f.backend.started.get() shouldBe 0

    val problems = f.sup.startupProblems()
    problems.map(_.database).sorted shouldBe LocalRows.map(r => s"acme_${r._1}").sorted
    problems.foreach { p =>
      p.kind shouldBe PoolSupervisor.StartupProblem.Kind.Blocked
      p.reason should include(Setting)
      p.pools should have size 1
      p.fixes.head should include("object storage")
      p.fixes(1) should include(s"unset $Setting")
    }
    problems.find(_.database == "acme_abs").map(_.reason).get should include("/data/abs/")

    val banner = Banner.startup(
      meta = Map("dbName" -> "qod", "pgHost" -> "localhost", "pgPort" -> "5432"),
      restHost = "localhost",
      restPort = 1,
      flightHost = "localhost",
      flightPort = 2,
      tlsEnabled = false,
      problems = problems
    )
    banner should include(s"is up, with ${LocalRows.size} problem(s)")
    banner should include("PROBLEMS")
    banner should include("BLOCKED")

    val listed =
      f.pools.listPools(None)(_ => SessionScope.Superuser).unsafeRunSync().toOption.get.pools
    listed should have size LocalRows.size.toLong
    listed.foreach { p =>
      p.status shouldBe "blocked"
      p.statusDetail.get should include(Setting)
      p.statusDetail.get should include("object storage")
    }
  }

  it should "not get a DuckLake catalog initialized, which would record its local path" in {
    val f = new Fixture
    f.imported(LocalRows*)
    f.sup.ensureDuckLakeInitialized().unsafeRunSync()
    f.inits.get() shouldBe 0
  }

  it should "start no ephemeral node either" in {
    val f = new Fixture
    f.imported(LocalRows*)
    f.sup.maintenanceNodeSpec("acme", "acme_abs") shouldBe None
    f.sup.mergeNodeSpec(
      "acme",
      "acme_abs",
      "acme_abs__br_01234567",
      "/data/abs__br_01234567/"
    ) shouldBe
      None
  }

  "a row a node can open" should "start, whatever its kind, with nothing to report" in {
    val f = new Fixture(defaults = RemoteDefaults)
    f.imported(RemoteRows*)
    f.sup.startupProblems() shouldBe Nil
    f.sup.reconcile().unsafeRunSync()
    f.backend.started.get() shouldBe RemoteRows.size
    f.sup.ensureDuckLakeInitialized().unsafeRunSync()
    f.inits.get() should be > 0
    f.sup.maintenanceNodeSpec("acme", "acme_s3lake") should not be empty
    f.sup.poolBlockReason(PoolKey("acme", "acme_s3lake", "s3lake")) shouldBe None
  }

  it should "take its path from the default when it names none" in {
    val f = new Fixture(defaults = RemoteDefaults)
    f.imported(("derived", "ducklake", "", Lake))
    f.sup.startupProblems() shouldBe Nil
    f.sup.reconcile().unsafeRunSync()
    f.backend.started.get() shouldBe 1
  }

  "with local files on" should "change nothing for a local row" in {
    val f = new Fixture(allow = true)
    f.imported(LocalRows*)
    f.sup.startupProblems() shouldBe Nil
    f.sup.reconcile().unsafeRunSync()
    f.backend.started.get() shouldBe LocalRows.size
    f.sup.ensureDuckLakeInitialized().unsafeRunSync()
    f.inits.get() should be > 0
    f.sup.maintenanceNodeSpec("acme", "acme_abs") should not be empty
    val again = new Fixture(allow = true)
    again.create("fresh", "ducklake", "/data/fresh/").isRight shouldBe true
  }

  "a create" should "be refused for a superuser, naming the setting, with nothing written" in {
    val f = new Fixture
    for (name, kind, path, meta) <- LocalRows do
      withClue(s"$name: ") {
        val (status, err) = f.create(name, kind, path, meta).left.toOption.get
        status.code shouldBe 400
        err.error shouldBe "local_files_off"
        err.message should include(Setting)
        err.message should include("object storage")
      }
    f.rowsNamed shouldBe Nil
    f.inits.get() shouldBe 0
    f.store.snapshot().tenantDbs shouldBe Nil
  }

  it should "pass for a path a node can open" in {
    val f = new Fixture(defaults = RemoteDefaults)
    for (name, kind, path, meta) <- RemoteRows do
      withClue(s"$name: ")(f.create(name, kind, path, meta).isRight shouldBe true)
    f.create("placed", "ducklake").isRight shouldBe true
    f.sup.findTenantDb("acme", "acme_placed").map(_.dataPath.startsWith("s3://bucket/")) shouldBe
      Some(true)
  }

  it should "be refused in the supervisor, for a caller that goes around the handlers" in {
    val f = new Fixture
    f.sup
      .createTenantDb(
        "acme",
        "direct",
        TenantDbKind.DuckLake,
        Map.empty,
        "/data/direct/",
        gateBypass = true,
        writerTrusted = true
      )
      .unsafeRunSync() match
      case Left(SupervisorError.LocalFilesOff(m)) => m should include(Setting)
      case other                                  => fail(s"expected the refusal, got $other")
  }

  // The order of the two rules: who may name a path comes first, then whether a node can open it.
  it should "read superuser_required for a tenant admin naming a local path" in {
    val f      = new Fixture
    val admin  = SessionScope(superuser = false, manageableTenants = Set("acme"))
    val (s, e) = f.create("named", "ducklake", "/data/named/", scope = admin).left.toOption.get
    s.code shouldBe 403
    e.error shouldBe "superuser_required"
    f.store.snapshot().tenantDbs shouldBe Nil
    // The same order in the supervisor, for a caller that goes around the handlers.
    f.sup
      .createTenantDb(
        "acme",
        "direct",
        TenantDbKind.DuckLake,
        Map.empty,
        "/data/direct/",
        gateBypass = true,
        writerTrusted = false
      )
      .unsafeRunSync() should matchPattern { case Left(SupervisorError.SuperuserChoice(_)) => }
  }

  it should "read local_files_off for anyone on the local path QoD would place" in {
    val admin = SessionScope(superuser = false, manageableTenants = Set("acme"))
    for scope <- List(SessionScope.Superuser, admin) do
      val f      = new Fixture
      val (s, e) = f.create("placed", "ducklake", scope = scope).left.toOption.get
      withClue(s"superuser=${scope.superuser}: ") {
        s.code shouldBe 400
        e.error shouldBe "local_files_off"
      }
      f.store.snapshot().tenantDbs shouldBe Nil
  }

  "an update" should "be refused when it would put a row on a local path" in {
    val f = new Fixture(defaults = RemoteDefaults)
    // A row whose path lies in its metastore, the one an update can change.
    f.imported(("moving", "ducklake", "", Map("dataPath" -> "s3://bucket/moving/")))
    f.sup.poolBlockReason(PoolKey("acme", "acme_moving", "moving")) shouldBe None
    val (status, err) = f.tenantDbs
      .update(
        UpdateTenantDbRequest(
          "acme",
          "acme_moving",
          metastore = Some(Map("dataPath" -> "/data/moved/"))
        ),
        None
      )(_ => SessionScope.Superuser)
      .unsafeRunSync()
      .left
      .toOption
      .get
    status.code shouldBe 400
    err.error shouldBe "local_files_off"
    f.sup.findTenantDb("acme", "acme_moving").map(_.metastore.get("dataPath")) shouldBe
      Some(Some("s3://bucket/moving/"))
  }

  it should "stay open on a blocked row, and its move to object storage ends the block" in {
    val f = new Fixture
    f.imported(("stuck", "ducklake", "", Lake))
    val key = PoolKey("acme", "acme_stuck", "stuck")
    f.sup.poolBlockReason(key) should not be empty
    f.tenantDbs
      .update(
        UpdateTenantDbRequest("acme", "acme_stuck", defaultSchema = Some("main")),
        None
      )(_ => SessionScope.Superuser)
      .unsafeRunSync()
      .isRight shouldBe true
    f.sup.poolBlockReason(key) should not be empty
    f.tenantDbs
      .update(
        UpdateTenantDbRequest(
          "acme",
          "acme_stuck",
          metastore = Some(Map("dataPath" -> "s3://bucket/stuck/"))
        ),
        None
      )(_ => SessionScope.Superuser)
      .unsafeRunSync()
      .isRight shouldBe true
    f.sup.poolBlockReason(key) shouldBe None
    f.sup.reconcile().unsafeRunSync()
    f.backend.started.get() shouldBe 1
  }

  "a node the health probe holds out of routing" should "be named in the pool's statusDetail" in {
    val f = new Fixture(defaults = RemoteDefaults)
    f.imported(("lake", "ducklake", "s3://bucket/lake/", Lake))
    f.sup.reconcile().unsafeRunSync()
    val key = PoolKey("acme", "acme_lake", "lake")
    f.sup.scale(key, 2, RoleDistribution(0, 0, 2), force = true).unsafeRunSync()
    val List(one, two) = f.sup.get(key).get.nodes.map(_.nodeId): @unchecked
    def listed         =
      f.pools.listPools(None)(_ => SessionScope.Superuser).unsafeRunSync().toOption.get.pools.head

    listed.status shouldBe "ready"
    listed.statusDetail shouldBe None

    f.tracker.setLocalFilesNotOff(one, Some(s"$one says why."))
    listed.status shouldBe "ready"
    listed.statusDetail shouldBe Some(s"$one says why.")

    f.tracker.setLocalFilesNotOff(two, Some(s"$two says why."))
    listed.statusDetail shouldBe Some(s"$one says why. $two says why.")

    f.tracker.setLocalFilesNotOff(one, None)
    f.tracker.setLocalFilesNotOff(two, None)
    listed.statusDetail shouldBe None
  }

  it should "give way to what blocks its database" in {
    val f = new Fixture(defaults = RemoteDefaults)
    f.imported(("was", "ducklake", "s3://bucket/was/", Lake))
    f.sup.reconcile().unsafeRunSync()
    val key = PoolKey("acme", "acme_was", "was")
    f.tracker.setLocalFilesNotOff(f.sup.get(key).get.nodes.head.nodeId, Some("held"))
    f.store.upsertTenantDb(f.store.snapshot().tenantDbs.head.copy(dataPath = "/data/was/"))
    f.sup.restore()
    val listed =
      f.pools.listPools(None)(_ => SessionScope.Superuser).unsafeRunSync().toOption.get.pools.head
    listed.status shouldBe "blocked"
    listed.statusDetail.get should include("/data/was/")
    listed.statusDetail.get should not include "held"
  }

  "a pool of a blocked row" should "be refused its create, with nothing written" in {
    val f = new Fixture
    f.imported(("stuck", "ducklake", "/data/stuck/", Lake))
    val key           = PoolKey("acme", "acme_stuck", "other")
    val (status, err) = f.pools
      .createPool(
        CreatePoolRequest(
          tenant = "acme",
          tenantDb = "acme_stuck",
          pool = "other",
          size = 1,
          roleDistribution = RoleDistribution(0, 0, 1)
        ),
        None
      )(_ => SessionScope.Superuser)
      .unsafeRunSync()
      .left
      .toOption
      .get
    status.code shouldBe 400
    err.error shouldBe "local_files_off"
    err.message should include(Setting)
    f.sup.get(key) shouldBe None
    f.backend.started.get() shouldBe 0
  }

  it should "be refused a scale up and a resume, and stay as it is" in {
    val f = new Fixture
    f.imported(("stuck", "ducklake", "/data/stuck/", Lake))
    val key           = PoolKey("acme", "acme_stuck", "stuck")
    val (status, err) = f.pools
      .scalePool(
        ScalePoolRequest("acme", "acme_stuck", "stuck", 2, RoleDistribution(0, 0, 2)),
        None
      )(_ => SessionScope.Superuser)
      .unsafeRunSync()
      .left
      .toOption
      .get
    status.code shouldBe 400
    err.error shouldBe "local_files_off"
    f.sup.get(key).map(_.distribution) shouldBe Some(RoleDistribution(0, 0, 1))

    f.store.upsertPool(f.store.snapshot().pools.head.copy(suspended = true))
    f.sup.restore()
    val (resumeStatus, resumeErr) = f.pools
      .resumePool(ResumePoolRequest("acme", "acme_stuck", "stuck"), None)(_ =>
        SessionScope.Superuser
      )
      .unsafeRunSync()
      .left
      .toOption
      .get
    resumeStatus.code shouldBe 400
    resumeErr.error shouldBe "local_files_off"
    f.sup.get(key).map(_.suspended) shouldBe Some(true)
    f.backend.started.get() shouldBe 0
  }

  it should "keep the nodes it holds when a scale would start one, and let a scale start none" in {
    // Nodes from before the setting: their rows are here, and a replacement could only fail.
    val f = new Fixture(defaults = RemoteDefaults)
    f.imported(("was", "ducklake", "s3://bucket/was/", Lake))
    val key = PoolKey("acme", "acme_was", "was")
    f.sup.reconcile().unsafeRunSync()
    f.sup.scale(key, 2, RoleDistribution(0, 0, 2), force = true).unsafeRunSync()
    val before = f.sup.get(key).get.nodes.map(_.nodeId)
    before should have size 2
    f.store.upsertTenantDb(f.store.snapshot().tenantDbs.head.copy(dataPath = "/data/was/"))
    f.sup.restore()
    val started = f.backend.started.get()

    def scale(size: Int, dist: RoleDistribution) =
      f.pools
        .scalePool(ScalePoolRequest("acme", "acme_was", "was", size, dist), None)(_ =>
          SessionScope.Superuser
        )
        .unsafeRunSync()

    // One leaves and one of another role arrives: the one leaving stays.
    val (status, err) = scale(2, RoleDistribution(1, 0, 1)).left.toOption.get
    status.code shouldBe 400
    err.error shouldBe "local_files_off"
    f.sup.get(key).get.nodes.map(_.nodeId) shouldBe before
    f.sup.get(key).get.distribution shouldBe RoleDistribution(0, 0, 2)
    f.store.snapshot().pools.head.distribution shouldBe RoleDistribution(0, 0, 2)
    before.foreach(f.backend.isAlive(_) shouldBe true)

    scale(1, RoleDistribution(0, 0, 1)).isRight shouldBe true
    f.sup.get(key).get.nodes should have size 1
    f.sup.get(key).get.distribution shouldBe RoleDistribution(0, 0, 1)
    f.backend.started.get() shouldBe started
  }

  it should "keep a node it holds when asked to restart it" in {
    // A node from before the setting: its row is here, and stopping it could start nothing.
    val f = new Fixture(defaults = RemoteDefaults)
    f.imported(("was", "ducklake", "s3://bucket/was/", Lake))
    f.sup.reconcile().unsafeRunSync()
    val key = PoolKey("acme", "acme_was", "was")
    val id  = f.sup.get(key).get.nodes.head.nodeId
    f.store.upsertTenantDb(f.store.snapshot().tenantDbs.head.copy(dataPath = "/data/was/"))
    f.sup.restore()
    val (status, err) = f.nodes
      .restartNode(NodeOpRequest("acme", "acme_was", "was", id), None)(_ => SessionScope.Superuser)
      .unsafeRunSync()
      .left
      .toOption
      .get
    status.code shouldBe 400
    err.error shouldBe "local_files_off"
    f.backend.started.get() shouldBe 1
    f.backend.isAlive(id) shouldBe true
  }
