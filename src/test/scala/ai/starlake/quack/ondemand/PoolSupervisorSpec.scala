package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{NodeSpec, PoolKey, Role, RoleDistribution, RunningNode, Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.{ControlPlaneStore, DbAdmin, InMemoryControlPlaneStore, RbacRole, RbacUser, RoleColumnPolicy}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.DurationInt

class PoolSupervisorSpec extends AnyFlatSpec with Matchers:

  "PoolSupervisor.nodeId" should "replace underscores in tenant-db with hyphens (RFC 1123)" in {
    // tenant-db is the composed `${tenant}_${tenantDb}` Postgres name, which
    // legitimately carries an underscore. K8s pod + service names cannot,
    // so the node-id surface must hyphenize it.
    val k = PoolKey("tpch", "tpch_tpch1", "sales")
    val id = PoolSupervisor.nodeId(k, 1)
    id shouldBe "quack-tpch-tpch-tpch1-sales-1"
    id should fullyMatch regex "[a-z0-9]([-a-z0-9]*[a-z0-9])?"
  }

  it should "leave already-hyphenated tenant-db names alone" in {
    val k = PoolKey("acme", "acme-default", "sales")
    PoolSupervisor.nodeId(k, 7) shouldBe "quack-acme-acme-default-sales-7"
  }

  "PoolSupervisor.replaceLastSegment" should "derive sibling paths for filesystem roots" in {
    PoolSupervisor.replaceLastSegment("./ducklake/tpch", "acme_tpch")  shouldBe "./ducklake/acme_tpch"
    PoolSupervisor.replaceLastSegment("/var/data/tpch", "acme_tpch")   shouldBe "/var/data/acme_tpch"
    PoolSupervisor.replaceLastSegment("tpch", "acme_tpch")             shouldBe "acme_tpch"
  }

  it should "preserve the scheme separator for URI-style roots (DuckLake __ducklake_metadata.data_path match)" in {
    // Regression: `java.nio.file.Paths.get` collapses `s3://...` to `s3:/...`,
    // which made DuckLake refuse to re-ATTACH the catalog at the loader's
    // canonical `s3://...` form. See run-local-stack-k8s.sh failure.
    PoolSupervisor.replaceLastSegment("s3://qod-ducklake/tpch", "acme_tpch") shouldBe
      "s3://qod-ducklake/acme_tpch"
    PoolSupervisor.replaceLastSegment("gs://bucket/tpch", "acme_tpch")       shouldBe
      "gs://bucket/acme_tpch"
    PoolSupervisor.replaceLastSegment("azure://acct/ctr/tpch", "acme_tpch")  shouldBe
      "azure://acct/ctr/acme_tpch"
  }

  it should "fall back gracefully when the URI has only a bucket segment" in {
    PoolSupervisor.replaceLastSegment("s3://qod-ducklake", "acme_tpch") shouldBe "s3://acme_tpch"
  }

  private val key: PoolKey = PoolKey("acme", "acme_default", "sales")
  private val ms           = Map("pgHost" -> "localhost")

  /** Captures NodeSpecs as the backend sees them - used to assert the
    * metastore that PoolSupervisor passes through. */
  private final class CapturingBackend extends QuackBackend:
    private val nodes = TrieMap.empty[String, RunningNode]
    val specs   = scala.collection.mutable.ListBuffer.empty[NodeSpec]
    val stopped = scala.collection.mutable.Set.empty[String]
    def start(spec: NodeSpec): IO[RunningNode] = IO {
      specs += spec
      val n = RunningNode(spec.nodeId, spec.poolKey, spec.role,
        "127.0.0.1", 21000 + nodes.size, "tok-" + spec.nodeId,
        Some(1L), None, Instant.EPOCH, maxConcurrent = spec.maxConcurrent)
      nodes.put(spec.nodeId, n); n
    }
    def stop(id: String): IO[Unit] = IO { stopped += id; nodes.remove(id); () }
    def isAlive(id: String): Boolean = nodes.contains(id)
    def discoverExisting(): IO[List[RunningNode]] = IO.pure(nodes.values.toList)
    def cleanup(): IO[Unit] = IO { nodes.clear() }

  private def fakeBackend(): QuackBackend = new CapturingBackend

  /** Supervisor + tenant `acme` + tenant-db `acme_default` carrying the
    * test metastore. Pool tests can call `createPool(key, ...)` directly. */
  private def freshSupervisor() =
    val sup = new PoolSupervisor(fakeBackend(), new NodeLoadTracker, new InMemoryControlPlaneStore())
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, dataPath = "").unsafeRunSync()
    sup

  private def freshSupervisorWithBackend(): (PoolSupervisor, CapturingBackend) =
    val b   = new CapturingBackend
    val sup = new PoolSupervisor(b, new NodeLoadTracker, new InMemoryControlPlaneStore())
    (sup, b)

  // ---------- createPool / scale / setMaxConcurrent / stopPool ----------

  "PoolSupervisor.createPool" should "start N nodes matching the role distribution" in:
    val sup = freshSupervisor()
    val nodes = sup.createPool(key, RoleDistribution(0, 2, 1)).unsafeRunSync()
    nodes.map(_.role).sortBy(_.toString) shouldBe List(Role.Dual, Role.ReadOnly, Role.ReadOnly)
    sup.list().map(_.key) shouldBe List(key)

  it should "reject distribution that doesn't sum" in:
    val sup = freshSupervisor()
    intercept[IllegalArgumentException](
      sup.createPool(key, RoleDistribution(-1, 2, 0)).unsafeRunSync()
    )

  it should "apply pool-level maxConcurrentPerNode to every node at create" in:
    val sup = freshSupervisor()
    val nodes = sup.createPool(key, RoleDistribution(0, 1, 1),
                               maxConcurrentPerNode = 4).unsafeRunSync()
    nodes.forall(_.maxConcurrent == 4) shouldBe true

  it should "fail with IllegalStateException when the tenant-db does not exist" in:
    val (sup, _) = freshSupervisorWithBackend()
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    // No createTenantDb -> createPool should refuse.
    intercept[IllegalStateException](
      sup.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()
    )

  // ---- initSql: free-form per-pool SQL prepended to the federation blob ----
  //
  // Operators set things like `SET memory_limit='8GB';` or `INSTALL httpfs;`
  // here. spawn-quack-node.sh just dumps $extraSetupSql into the init pipe, so
  // the per-pool initSql rides the same env var; PoolSupervisor concatenates
  // initSql FIRST then the resolved federation blob so PRAGMAs are in effect
  // before any federation ATTACH runs.

  it should "prepend initSql to NodeSpec.extraSetupSql when no federation blob is present" in:
    val (sup, backend) = freshSupervisorWithBackend()
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, dataPath = "")
      .unsafeRunSync()
    val pragma = "SET memory_limit='8GB';"
    sup.createPool(key, RoleDistribution(0, 0, 1), initSql = pragma).unsafeRunSync()
    backend.specs.size shouldBe 1
    backend.specs.head.extraSetupSql.trim shouldBe pragma

  it should "expose initSql on the PoolState so the UI can render it later" in:
    val (sup, _) = freshSupervisorWithBackend()
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, dataPath = "")
      .unsafeRunSync()
    sup.createPool(key, RoleDistribution(0, 0, 1),
                   initSql = "SET threads=2;").unsafeRunSync()
    sup.get(key).map(_.initSql) shouldBe Some("SET threads=2;")

  it should "default initSql to the empty string for backward compat" in:
    val (sup, backend) = freshSupervisorWithBackend()
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, dataPath = "")
      .unsafeRunSync()
    sup.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()
    sup.get(key).map(_.initSql) shouldBe Some("")
    backend.specs.head.extraSetupSql shouldBe ""

  "PoolSupervisor.scale" should "add nodes when target > current" in:
    val sup = freshSupervisor()
    sup.createPool(key, RoleDistribution(0, 1, 0)).unsafeRunSync()
    sup.scale(key, targetSize = 3, RoleDistribution(0, 2, 1), force = false).unsafeRunSync()
    sup.get(key).get.nodes.size shouldBe 3

  it should "add the role the caller asked for, not a positional slice" in:
    // Regression: scaling a Dual-only pool up to {readonly:1, dual:1} used to
    // spawn a second Dual, because `asRoleList.drop(size)` skipped past the new
    // ReadOnly entry ([... ReadOnly, Dual]) and landed on the trailing Dual.
    val sup = freshSupervisor()
    sup.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()
    sup.scale(key, targetSize = 2, RoleDistribution(0, 1, 1), force = false).unsafeRunSync()
    val roles = sup.get(key).get.nodes.map(_.role)
    roles.count(_ == Role.ReadOnly) shouldBe 1
    roles.count(_ == Role.Dual) shouldBe 1

  it should "swap a node's role in place when the size is unchanged" in:
    // {readonly:1, dual:1} -> {writeonly:1, dual:1}: same size, but the ReadOnly
    // must be replaced by a WriteOnly rather than left untouched.
    val sup = freshSupervisor()
    sup.createPool(key, RoleDistribution(0, 1, 1)).unsafeRunSync()
    sup.scale(key, targetSize = 2, RoleDistribution(1, 0, 1), force = true).unsafeRunSync()
    val roles = sup.get(key).get.nodes.map(_.role)
    roles.count(_ == Role.WriteOnly) shouldBe 1
    roles.count(_ == Role.ReadOnly) shouldBe 0
    roles.count(_ == Role.Dual) shouldBe 1

  it should "remove nodes when target < current (graceful by default)" in:
    val sup = freshSupervisor()
    sup.createPool(key, RoleDistribution(0, 3, 0)).unsafeRunSync()
    sup.scale(key, 1, RoleDistribution(0, 1, 0), force = false).unsafeRunSync()
    sup.get(key).get.nodes.size shouldBe 1

  "PoolSupervisor.setMaxConcurrent" should "mutate one node's cap" in:
    val sup = freshSupervisor()
    sup.createPool(key, RoleDistribution(0, 0, 2), maxConcurrentPerNode = 2).unsafeRunSync()
    val firstId = sup.get(key).get.nodes.head.nodeId
    val updated = sup.setMaxConcurrent(key, firstId, 7).unsafeRunSync()
    updated.map(_.maxConcurrent) shouldBe Some(7)
    sup.get(key).get.nodes.head.maxConcurrent shouldBe 7

  it should "return None for unknown node" in:
    val sup = freshSupervisor()
    sup.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()
    sup.setMaxConcurrent(key, "nope", 5).unsafeRunSync() shouldBe None

  "PoolSupervisor.stopPool" should "stop all nodes but keep the pool (scaled to 0)" in:
    val sup = freshSupervisor()
    sup.createPool(key, RoleDistribution(0, 1, 1)).unsafeRunSync()
    sup.stopPool(key, force = true).unsafeRunSync()
    // Pool survives; it is just scaled down to zero nodes.
    sup.get(key).map(_.nodes) shouldBe Some(Nil)
    sup.get(key).map(_.distribution) shouldBe Some(RoleDistribution(0, 0, 0))

  "PoolSupervisor.deletePool" should "stop all nodes and forget the pool" in:
    val sup = freshSupervisor()
    sup.createPool(key, RoleDistribution(0, 1, 1)).unsafeRunSync()
    sup.deletePool(key, force = true).unsafeRunSync()
    sup.get(key) shouldBe None

  // ---------- restartNode ----------

  "PoolSupervisor.restartNode" should "stop and respawn the node with the same id and clear quarantine" in {
    val store   = new InMemoryControlPlaneStore()
    val tracker = new NodeLoadTracker
    val backend = new CapturingBackend
    val sup     = new PoolSupervisor(backend, tracker, store)
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, dataPath = "").unsafeRunSync()
    sup.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()
    val nodeId = sup.get(key).get.nodes.head.nodeId
    store.setNodeQuarantined(nodeId, true)
    tracker.setQuarantined(nodeId, true)
    sup.restartNode(key, nodeId).unsafeRunSync() shouldBe Right(())
    backend.stopped should contain(nodeId)
    sup.get(key).get.nodes.map(_.nodeId) should contain(nodeId)
    tracker.snapshot(nodeId).quarantined shouldBe false
    store.listQuarantinedNodeIds() should not contain nodeId
  }

  it should "return Left for an unknown node" in {
    val sup = freshSupervisor()
    sup.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()
    sup.restartNode(key, "no-such-node").unsafeRunSync().isLeft shouldBe true
  }

  // ---------- reconcileLoop ----------

  "PoolSupervisor.reconcileLoop" should "run reconcile repeatedly, respawning a node that stays dead" in {
    // CapturingBackend nodes carry pid=Some(1) with an unreachable socket, so
    // isReachable returns false every pass: each reconcile tick finds the node
    // dead and respawns it, appending one NodeSpec. Watching specs grow past the
    // initial spawn proves the loop fired reconcile more than once.
    val (sup, b) = freshSupervisorWithBackend()
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup
      .createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, dataPath = "")
      .unsafeRunSync()
    sup.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()
    val before = b.specs.size // 1: the initial createPool spawn

    val fiber      = sup.reconcileLoop(20.millis).start.unsafeRunSync()
    val deadlineMs = System.currentTimeMillis() + 3000
    while (b.specs.size < before + 2 && System.currentTimeMillis() < deadlineMs) Thread.sleep(10)
    fiber.cancel.unsafeRunSync()

    b.specs.size should be >= before + 2
  }

  "PoolSupervisor.scale" should "clear a stale draining flag when a drained node id is respawned" in {
    // Repro for "node stuck in draining after drain + rescale": draining a node
    // (force=false) sets draining=true and deletes its store row but leaves the
    // tracker entry behind. Rescaling up reuses the freed node id, so the fresh
    // node must NOT inherit the old draining=true flag. scale's spawn path has to
    // reset the tracker entry the way createPool/reconcile do.
    val backend = new CapturingBackend
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(backend, tracker, new InMemoryControlPlaneStore())
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup
      .createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, dataPath = "")
      .unsafeRunSync()
    val created = sup.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()
    val nodeId  = created.head.nodeId

    sup.stopPool(key, force = false).unsafeRunSync() // drain the node

    val respawned = sup.scale(key, 1, RoleDistribution(0, 0, 1), force = false).unsafeRunSync()
    respawned.map(_.nodeId) shouldBe List(nodeId)    // the drained id is reused
    tracker.snapshot(nodeId).draining shouldBe false // fresh node must start clean
  }

  it should "remove the tracker entry of a drained node once it is stopped" in {
    // The drained node's row leaves the store; its tracker entry must go too,
    // otherwise snapshotAll accumulates phantom draining=true entries for every
    // node id ever drained.
    val backend = new CapturingBackend
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(backend, tracker, new InMemoryControlPlaneStore())
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup
      .createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, dataPath = "")
      .unsafeRunSync()
    val nodeId = sup.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync().head.nodeId

    sup.stopPool(key, force = false).unsafeRunSync()
    tracker.snapshotAll.keySet should not contain nodeId
  }

  "PoolSupervisor.deletePool" should "remove the tracker entries of its drained nodes" in {
    val backend = new CapturingBackend
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(backend, tracker, new InMemoryControlPlaneStore())
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup
      .createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, dataPath = "")
      .unsafeRunSync()
    val ids = sup.createPool(key, RoleDistribution(0, 1, 1)).unsafeRunSync().map(_.nodeId)

    sup.deletePool(key, force = false).unsafeRunSync()
    ids.foreach(id => tracker.snapshotAll.keySet should not contain id)
  }

  // ---------- Tenant CRUD ----------

  "PoolSupervisor.createTenant" should "register a new tenant" in:
    val (sup, _) = freshSupervisorWithBackend()
    val res = sup.createTenant(Tenant("foo")).unsafeRunSync()
    res.isRight shouldBe true
    val t = res.toOption.get
    t.id        shouldBe "foo"
    t.displayName shouldBe "foo"
    t.id            should not be empty
    sup.getTenant("foo").map(_.displayName) shouldBe Some("foo")
    sup.listTenants().map(_.displayName)    shouldBe List("foo")

  it should "reject a duplicate tenant name" in:
    val (sup, _) = freshSupervisorWithBackend()
    sup.createTenant(Tenant("foo")).unsafeRunSync()
    val res = sup.createTenant(Tenant("foo")).unsafeRunSync()
    res.left.toOption.map(_.message).getOrElse("") should include("already exists")

  "PoolSupervisor.deleteTenant" should "remove a tenant with no tenant-dbs" in:
    val (sup, _) = freshSupervisorWithBackend()
    sup.createTenant(Tenant("foo")).unsafeRunSync()
    sup.deleteTenant("foo").unsafeRunSync() shouldBe Right(())
    sup.getTenant("foo") shouldBe None

  it should "refuse to delete a tenant that still has pools" in:
    val sup = freshSupervisor()
    sup.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()
    val out = sup.deleteTenant("acme").unsafeRunSync()
    out.left.toOption.map(_.message).getOrElse("") should include("active pool")
    sup.getTenant("acme") shouldBe defined

  it should "cascade-delete tenant-dbs (no pools) when deleting the tenant" in:
    val (sup, _) = freshSupervisorWithBackend()
    sup.createTenant(Tenant("foo")).unsafeRunSync()
    sup.createTenantDb("foo", "default", TenantDbKind.InMemory, Map.empty, "").unsafeRunSync()
    sup.deleteTenant("foo").unsafeRunSync() shouldBe Right(())
    sup.listTenantDbsByTenant("foo") shouldBe empty
    sup.getTenant("foo")             shouldBe None

  it should "return Left for an unknown tenant" in:
    val (sup, _) = freshSupervisorWithBackend()
    sup.deleteTenant("ghost").unsafeRunSync().isLeft shouldBe true

  // ---------- Explicit createTenantDb + bootstrap chain ----------

  "PoolSupervisor.createTenantDb" should
    "compose `${tenant}_${suffix}` and persist the tenant-db row" in:
    val (sup, _) = freshSupervisorWithBackend()
    sup.createTenant(Tenant("tpch")).unsafeRunSync()
    val out = sup.createTenantDb(
      tenantName  = "tpch",
      suffix      = "tpch1",
      kind        = TenantDbKind.InMemory,
      metastore   = Map.empty,
      dataPath    = ""
    ).unsafeRunSync()
    out.isRight shouldBe true
    out.toOption.get.name shouldBe "tpch_tpch1"
    sup.listTenantDbsByTenant("tpch").map(_.name) shouldBe List("tpch_tpch1")

  it should "reject a duplicate tenant-db inside the same tenant" in:
    val (sup, _) = freshSupervisorWithBackend()
    sup.createTenant(Tenant("tpch")).unsafeRunSync()
    sup.createTenantDb("tpch", "tpch1", TenantDbKind.InMemory, Map.empty, "").unsafeRunSync()
    val again = sup.createTenantDb("tpch", "tpch1", TenantDbKind.InMemory, Map.empty, "").unsafeRunSync()
    again.isLeft shouldBe true
    again.swap.toOption.get.message should include("already exists")

  it should "reject a tenant-db when the tenant doesn't exist" in:
    val (sup, _) = freshSupervisorWithBackend()
    val out = sup.createTenantDb("ghost", "prod", TenantDbKind.InMemory, Map.empty, "").unsafeRunSync()
    out.isLeft shouldBe true

  "bootstrap chain" should "thread createTenant -> createTenantDb -> createPool" in:
    val (sup, backend) = freshSupervisorWithBackend()
    sup.createTenant(Tenant("tpch")).unsafeRunSync()
    sup.createTenantDb("tpch", "tpch1",
      kind      = TenantDbKind.DuckDbFile,
      metastore = Map("dbName" -> "tpch_tpch1", "schemaName" -> "main"),
      dataPath  = "/data/tpch_tpch1"
    ).unsafeRunSync()
    val poolKey = PoolKey("tpch", "tpch_tpch1", "sales")
    sup.createPool(poolKey, RoleDistribution(0, 1, 1)).unsafeRunSync()
    backend.specs.size shouldBe 2
    backend.specs.head.metastore("schemaName") shouldBe "main"
    sup.listTenantDbsByTenant("tpch").map(_.name) shouldBe List("tpch_tpch1")

  // ---------- DbAdmin invocation ----------

  /** Capturing DbAdmin: every CREATE / DROP call is recorded so tests
    * can assert ordering + name composition without a live Postgres. */
  private final class RecordingDbAdmin extends DbAdmin:
    val created = scala.collection.mutable.ListBuffer.empty[String]
    val dropped = scala.collection.mutable.ListBuffer.empty[String]
    def createDatabase(name: String): Either[String, Unit] = { created += name; Right(()) }
    def dropDatabase(name: String):   Either[String, Unit] = { dropped += name; Right(()) }

  private def supWithAdmin(): (PoolSupervisor, RecordingDbAdmin) =
    val admin = new RecordingDbAdmin
    val sup   = new PoolSupervisor(
      fakeBackend(), new NodeLoadTracker, new InMemoryControlPlaneStore(),
      defaultMetastore = Map.empty, dbAdmin = admin
    )
    (sup, admin)

  "PoolSupervisor.createTenantDb" should "invoke DbAdmin.createDatabase with the composed name" in:
    val (sup, admin) = supWithAdmin()
    sup.createTenant(Tenant("tpch")).unsafeRunSync()
    val out = sup.createTenantDb("tpch", "tpch1",
      TenantDbKind.DuckLake,
      Map("pgHost" -> "h", "pgPort" -> "0", "pgUser" -> "u",
          "pgPassword" -> "s", "dbName" -> "ignored", "schemaName" -> "main"),
      "/data/tpch_tpch1"
    ).unsafeRunSync()
    out.isRight shouldBe true
    admin.created.toList shouldBe List("tpch_tpch1")
    admin.dropped.toList shouldBe Nil

  it should "auto-populate metastore.dbName with the composed name" in:
    val (sup, _) = supWithAdmin()
    sup.createTenant(Tenant("tpch")).unsafeRunSync()
    val td = sup.createTenantDb("tpch", "prod",
      TenantDbKind.DuckLake,
      Map("pgHost" -> "h", "pgPort" -> "0", "pgUser" -> "u",
          "pgPassword" -> "s", "dbName" -> "ignored", "schemaName" -> "main"),
      "/data/tpch_prod"
    ).unsafeRunSync().toOption.get
    td.metastore("dbName")     shouldBe "tpch_prod"
    td.metastore("schemaName") shouldBe "main"

  "PoolSupervisor.deleteTenantDb" should "invoke DbAdmin.dropDatabase after the row is gone" in:
    val (sup, admin) = supWithAdmin()
    sup.createTenant(Tenant("tpch")).unsafeRunSync()
    sup.createTenantDb("tpch", "tpch1", TenantDbKind.InMemory, Map.empty, "").unsafeRunSync()
    sup.deleteTenantDb("tpch", "tpch_tpch1").unsafeRunSync() shouldBe Right(())
    admin.dropped.toList shouldBe List("tpch_tpch1")
    sup.listTenantDbsByTenant("tpch") shouldBe empty

  it should "refuse the delete when a pool still points at the tenant-db" in:
    val (sup, admin) = supWithAdmin()
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, "").unsafeRunSync()
    sup.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()
    val out = sup.deleteTenantDb("acme", "acme_default").unsafeRunSync()
    out.isLeft shouldBe true
    out.swap.toOption.get.message should include("active pool")
    admin.dropped.toList shouldBe Nil

  // ---------- Per-tenant-db metastore + dataPath ----------

  "PoolSupervisor.createPool" should "pass the tenant-db's metastore into the NodeSpec" in:
    val (sup, backend) = freshSupervisorWithBackend()
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb("acme", "default",
      TenantDbKind.DuckDbFile,
      Map("pgHost" -> "tenant-host", "pgPort" -> "5432",
          "shared" -> "tenant-val", "dbName" -> "acme_default", "schemaName" -> "main"),
      dataPath = "/data/acme_default"
    ).unsafeRunSync()
    sup.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()
    val spec = backend.specs.head
    spec.metastore("pgHost") shouldBe "tenant-host"
    spec.metastore("pgPort") shouldBe "5432"
    spec.metastore("shared") shouldBe "tenant-val"

  "PoolSupervisor.effectiveMetastoreFor" should
    "return the tenant-db's dataPath when multiple tenant-dbs coexist" in:
    val sup = new PoolSupervisor(
      new CapturingBackend, new NodeLoadTracker, new InMemoryControlPlaneStore(),
      defaultMetastore = Map("dataPath" -> "/data/global")
    )
    sup.createTenant(Tenant("eu")).unsafeRunSync()
    sup.createTenant(Tenant("us")).unsafeRunSync()
    sup.createTenantDb("eu", "default",
      TenantDbKind.DuckDbFile,
      Map("dataPath" -> "/data/eu-west", "dbName" -> "eu_default", "schemaName" -> "main"),
      "/data/eu-west"
    ).unsafeRunSync()
    sup.createTenantDb("us", "default",
      TenantDbKind.DuckDbFile,
      Map("dataPath" -> "s3://us-east-data/", "dbName" -> "us_default", "schemaName" -> "main"),
      "s3://us-east-data/"
    ).unsafeRunSync()
    sup.effectiveMetastoreFor("eu", "eu_default")("dataPath") shouldBe "/data/eu-west"
    sup.effectiveMetastoreFor("us", "us_default")("dataPath") shouldBe "s3://us-east-data/"

  it should "fall back to the global default when the tenant-db has no override" in:
    val sup = new PoolSupervisor(
      new CapturingBackend, new NodeLoadTracker, new InMemoryControlPlaneStore(),
      defaultMetastore = Map("dataPath" -> "/data/global")
    )
    sup.createTenant(Tenant("legacy")).unsafeRunSync()
    sup.createTenantDb("legacy", "default", TenantDbKind.InMemory, Map.empty, "").unsafeRunSync()
    sup.effectiveMetastoreFor("legacy", "legacy_default")("dataPath") shouldBe "/data/global"

  // ---------- NodeSpec.objectStoreSql (per-db object-store CREATE SECRET) ----------

  "PoolSupervisor.createPool" should
    "produce a node spec with objectStoreSql for a tenant-db with objectStore + s3 dataPath" in:
    val (sup, backend) = freshSupervisorWithBackend()
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb("acme", "objstore",
      TenantDbKind.DuckDbFile,
      Map("dataPath" -> "s3://bucket/db", "dbName" -> "acme_objstore", "schemaName" -> "main"),
      dataPath = "s3://bucket/db",
      objectStore = Map(
        "s3_access_key_id" -> "k",
        "s3_secret_access_key" -> "s",
        "s3_region" -> "us-east-1"
      )
    ).unsafeRunSync()
    val objKey = PoolKey("acme", "acme_objstore", "sales")
    sup.createPool(objKey, RoleDistribution(0, 0, 1)).unsafeRunSync()
    val spec = backend.specs.head
    spec.objectStoreSql should include("CREATE OR REPLACE SECRET qod_db_store")
    spec.objectStoreSql should include("SCOPE 's3://bucket/db'")

  it should "produce an empty objectStoreSql for a tenant-db with no objectStore" in:
    val (sup, backend) = freshSupervisorWithBackend()
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, dataPath = "")
      .unsafeRunSync()
    sup.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()
    backend.specs.head.objectStoreSql shouldBe ""

  // ---------- effectiveSetForUser: column policies ----------

  "effectiveSetForUser" should "include column policies attached to the user's roles" in:
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(fakeBackend(), new NodeLoadTracker, store)
    val t     = sup.createTenant(Tenant("acme")).unsafeRunSync().toOption.get
    // createRole registers the role in the rbacResolver so effectiveSetForUser can resolve it.
    val role  = sup.createRole(t.id, "analyst").unsafeRunSync().toOption.get
    // Insert the user directly (createUser requires a UserStore; for this test we bypass it).
    val user  = RbacUser(id = "u-cp1", tenant = Some(t.id), username = "alice", role = "user")
    store.upsertUserIdentity(user)
    // addUserRole goes through the supervisor so the effective-set cache is cleared.
    sup.addUserRole(user.id, role.id).unsafeRunSync()
    store.insertColumnPolicy(
      RoleColumnPolicy(
        id           = "cp-1",
        roleId       = role.id,
        catalogName  = "*",
        schemaName   = "tpch1",
        tableName    = "customer",
        columnName   = "c_email",
        action       = "mask",
        transformSql = Some("'***'")
      )
    )
    val eff = sup.effectiveSetForUser(user.id)
    eff.map(_.columnPolicies.map(_.columnName)) shouldBe Some(List("c_email"))

  // ---------- column policy mutators + cache invalidation ----------

  it should "invalidate the EffectiveSet cache when a column policy is created" in:
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(fakeBackend(), new NodeLoadTracker, store)
    val t     = sup.createTenant(Tenant("acme")).unsafeRunSync().toOption.get
    val role  = sup.createRole(t.id, "analyst").unsafeRunSync().toOption.get
    val user  = RbacUser(id = "u-cp2", tenant = Some(t.id), username = "bob", role = "user")
    store.upsertUserIdentity(user)
    sup.addUserRole(user.id, role.id).unsafeRunSync()

    // Warm the cache with no policies.
    sup.effectiveSetForUser(user.id).map(_.columnPolicies) shouldBe Some(Nil)

    // Create via the supervisor (the path that must invalidate the cache).
    val created = sup
      .createColumnPolicy(role.id, "*", "tpch1", "customer", "c_email", "mask", Some("'***'"))
      .unsafeRunSync()
    created shouldBe a[Right[?, ?]]

    // Without cache invalidation we would still see Nil; with it the new policy is visible.
    sup.effectiveSetForUser(user.id).map(_.columnPolicies.map(_.columnName)) shouldBe
      Some(List("c_email"))

  // ---------- restore() propagates deletions (HA peer-delete convergence) ----------

  "restore()" should "drop a pool whose rows a peer deleted directly in the store" in:
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(fakeBackend(), new NodeLoadTracker, store)
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup
      .createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, dataPath = "")
      .unsafeRunSync()
    val poolKey = PoolKey("acme", "acme_default", "sales")
    sup.createPool(poolKey, RoleDistribution(0, 0, 1)).unsafeRunSync()
    sup.get(poolKey).isDefined shouldBe true

    // Simulate a peer's delete: remove the pool's node rows then the pool row
    // straight through the store (FK RESTRICT requires nodes first).
    val pid = sup.poolId(poolKey).get
    store.listNodes(pid).foreach(n => store.deleteNode(n.nodeId))
    store.deletePool(pid)

    sup.restore()
    sup.get(poolKey) shouldBe None
    sup.list().map(_.key) should not contain poolKey

  it should "drop a tenant a peer deleted directly in the store" in:
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(fakeBackend(), new NodeLoadTracker, store)
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenant(Tenant("ghost")).unsafeRunSync()
    sup.getTenantById("ghost").isDefined shouldBe true

    // Peer deletes the childless tenant directly in the store.
    store.deleteTenant("ghost")

    sup.restore()
    sup.getTenantById("ghost") shouldBe None
    sup.listTenants().map(_.id) should not contain "ghost"

  "restore" should "seed operator quarantine flags into the load tracker" in:
    val store   = new InMemoryControlPlaneStore()
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(fakeBackend(), tracker, store)
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup
      .createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, dataPath = "")
      .unsafeRunSync()
    val poolKey = PoolKey("acme", "acme_default", "sales")
    sup.createPool(poolKey, RoleDistribution(0, 0, 1)).unsafeRunSync()
    val nodeId = sup.get(poolKey).get.nodes.head.nodeId
    store.setNodeQuarantined(nodeId, true)
    sup.restore()
    tracker.snapshot(nodeId).quarantined shouldBe true
    store.setNodeQuarantined(nodeId, false)
    sup.restore()
    tracker.snapshot(nodeId).quarantined shouldBe false

  // ---------- membership: cross-tenant edges are rejected ----------

  "addUserRole" should "reject attaching a tenant-B role to a tenant-A user" in:
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(fakeBackend(), new NodeLoadTracker, store)
    val a     = sup.createTenant(Tenant("acme")).unsafeRunSync().toOption.get
    val b     = sup.createTenant(Tenant("globex")).unsafeRunSync().toOption.get
    val roleB = sup.createRole(b.id, "analyst").unsafeRunSync().toOption.get
    val userA = RbacUser(id = "u-a1", tenant = Some(a.id), username = "alice", role = "user")
    store.upsertUserIdentity(userA)
    val res = sup.addUserRole(userA.id, roleB.id).unsafeRunSync()
    res.isLeft shouldBe true
    res.left.toOption.get.message should include("cross-tenant")
    sup.effectiveSetForUser(userA.id).map(_.permissions).getOrElse(Nil) shouldBe Nil

  it should "accept a same-tenant role membership" in:
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(fakeBackend(), new NodeLoadTracker, store)
    val a     = sup.createTenant(Tenant("acme")).unsafeRunSync().toOption.get
    val roleA = sup.createRole(a.id, "analyst").unsafeRunSync().toOption.get
    val userA = RbacUser(id = "u-a2", tenant = Some(a.id), username = "alice", role = "user")
    store.upsertUserIdentity(userA)
    sup.addUserRole(userA.id, roleA.id).unsafeRunSync() shouldBe Right(())

  it should "reject attaching a tenant-scoped role to a superuser" in:
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(fakeBackend(), new NodeLoadTracker, store)
    val a     = sup.createTenant(Tenant("acme")).unsafeRunSync().toOption.get
    val roleA = sup.createRole(a.id, "analyst").unsafeRunSync().toOption.get
    val root  = RbacUser(id = "u-root", tenant = None, username = "root", role = "admin")
    store.upsertUserIdentity(root)
    val res = sup.addUserRole(root.id, roleA.id).unsafeRunSync()
    res.isLeft shouldBe true
    res.left.toOption.get.message should include("cross-tenant")

  "addUserGroup" should "reject attaching a tenant-B group to a tenant-A user" in:
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(fakeBackend(), new NodeLoadTracker, store)
    val a      = sup.createTenant(Tenant("acme")).unsafeRunSync().toOption.get
    val b      = sup.createTenant(Tenant("globex")).unsafeRunSync().toOption.get
    val groupB = sup.createGroup(b.id, "team").unsafeRunSync().toOption.get
    val userA  = RbacUser(id = "u-a3", tenant = Some(a.id), username = "alice", role = "user")
    store.upsertUserIdentity(userA)
    val res = sup.addUserGroup(userA.id, groupB.id).unsafeRunSync()
    res.isLeft shouldBe true
    res.left.toOption.get.message should include("cross-tenant")

  it should "accept a same-tenant group membership" in:
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(fakeBackend(), new NodeLoadTracker, store)
    val a      = sup.createTenant(Tenant("acme")).unsafeRunSync().toOption.get
    val groupA = sup.createGroup(a.id, "team").unsafeRunSync().toOption.get
    val userA  = RbacUser(id = "u-a4", tenant = Some(a.id), username = "alice", role = "user")
    store.upsertUserIdentity(userA)
    sup.addUserGroup(userA.id, groupA.id).unsafeRunSync() shouldBe Right(())

  "addGroupRole" should "reject attaching a tenant-B role to a tenant-A group" in:
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(fakeBackend(), new NodeLoadTracker, store)
    val a      = sup.createTenant(Tenant("acme")).unsafeRunSync().toOption.get
    val b      = sup.createTenant(Tenant("globex")).unsafeRunSync().toOption.get
    val groupA = sup.createGroup(a.id, "team").unsafeRunSync().toOption.get
    val roleB  = sup.createRole(b.id, "analyst").unsafeRunSync().toOption.get
    val res    = sup.addGroupRole(groupA.id, roleB.id).unsafeRunSync()
    res.isLeft shouldBe true
    res.left.toOption.get.message should include("cross-tenant")

  it should "accept a same-tenant group-role edge" in:
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(fakeBackend(), new NodeLoadTracker, store)
    val a      = sup.createTenant(Tenant("acme")).unsafeRunSync().toOption.get
    val groupA = sup.createGroup(a.id, "team").unsafeRunSync().toOption.get
    val roleA  = sup.createRole(a.id, "analyst").unsafeRunSync().toOption.get
    sup.addGroupRole(groupA.id, roleA.id).unsafeRunSync() shouldBe Right(())

  // ---------- reconcile: quarantine flag must survive respawn ----------

  "PoolSupervisor.reconcileLoop" should "preserve operator quarantine when a quarantined node is respawned" in {
    val store   = new InMemoryControlPlaneStore()
    val tracker = new NodeLoadTracker
    val backend = new CapturingBackend
    val sup     = new PoolSupervisor(backend, tracker, store)
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, dataPath = "").unsafeRunSync()
    sup.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()
    val nodeId       = sup.get(key).get.nodes.head.nodeId
    val beforeRespawn = backend.specs.size

    // Quarantine in both store and tracker, mirroring what the operator endpoint does.
    store.setNodeQuarantined(nodeId, true)
    tracker.setQuarantined(nodeId, true)

    // Let reconcile run; the node's socket is unreachable so it will be respawned.
    val fiber    = sup.reconcileLoop(20.millis).start.unsafeRunSync()
    val deadline = System.currentTimeMillis() + 3000L
    while (backend.specs.size < beforeRespawn + 1 && System.currentTimeMillis() < deadline) Thread.sleep(10)
    fiber.cancel.unsafeRunSync()

    backend.specs.size should be >= beforeRespawn + 1
    // After reconcile respawn the quarantine flag must still be set in the tracker
    // so the respawned node remains excluded from routing.
    tracker.snapshot(nodeId).quarantined shouldBe true
  }

  // ---------- per-database boot SQL (NodeSpec.dbInitSql) ----------
  //
  // The tenant-db initSql executes BEFORE the quack extension loads (and after
  // the proxy http settings), so it must NOT be folded into extraSetupSql,
  // which spawn-quack-node.sh runs after LOAD quack + the catalog ATTACH. It
  // rides its own NodeSpec.dbInitSql field / dbInitSql env var instead.

  "joinInitAndBlob" should "join pool initSql and federation blob, skipping blanks" in {
    PoolSupervisor.joinInitAndBlob("SET b=2;", "ATTACH x;") shouldBe "SET b=2;\nATTACH x;"
    PoolSupervisor.joinInitAndBlob("SET b=2;", "") shouldBe "SET b=2;"
    PoolSupervisor.joinInitAndBlob("", "ATTACH x;") shouldBe "ATTACH x;"
    PoolSupervisor.joinInitAndBlob("  ", "") shouldBe ""
  }

  "createPool" should "ship the tenant-db initSql on NodeSpec.dbInitSql, not in extraSetupSql" in {
    val b   = new CapturingBackend
    val sup = new PoolSupervisor(b, new NodeLoadTracker, new InMemoryControlPlaneStore())
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb(
      tenantName = "acme", suffix = "mem1", kind = TenantDbKind.InMemory,
      metastore = Map.empty, dataPath = "", initSql = "SET memory_limit = '2GB';"
    ).unsafeRunSync()
    val poolKey2 = PoolKey("acme", "acme_mem1", "bi")
    sup.createPool(poolKey2, RoleDistribution(0, 0, 1), initSql = "SET threads = 2;").unsafeRunSync()
    val spec = b.specs.head
    spec.dbInitSql shouldBe "SET memory_limit = '2GB';"
    spec.extraSetupSql should include("SET threads = 2;")
    spec.extraSetupSql should not include "memory_limit"
  }

  "restore" should "carry the tenant-db initSql into PoolState.dbInitSql" in {
    val store2 = new InMemoryControlPlaneStore()
    val sup2   = new PoolSupervisor(fakeBackend(), new NodeLoadTracker, store2)
    sup2.createTenant(Tenant("acme")).unsafeRunSync()
    sup2.createTenantDb(
      tenantName = "acme", suffix = "default", kind = TenantDbKind.InMemory,
      metastore = Map.empty, dataPath = "", initSql = "SET x=1;"
    ).unsafeRunSync()
    sup2.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()
    sup2.restore()
    sup2.get(key).get.dbInitSql shouldBe "SET x=1;"
  }

  it should "carry the tenant-db session defaults into PoolState (demo regression)" in {
    // Omitting these degraded every restored pool's SQL validation / policy
    // rewrite context to the metastore schemaName ("main"), so grants keyed on
    // the tenant-db's declared defaultSchema (e.g. tpch1) stopped matching
    // after a restart or NOTIFY-driven rehydration.
    val store2 = new InMemoryControlPlaneStore()
    val sup2   = new PoolSupervisor(fakeBackend(), new NodeLoadTracker, store2)
    sup2.createTenant(Tenant("acme")).unsafeRunSync()
    sup2.createTenantDb(
      tenantName = "acme", suffix = "default", kind = TenantDbKind.InMemory,
      metastore = Map.empty, dataPath = "",
      defaultDatabase = Some("acme_default"), defaultSchema = Some("tpch1")
    ).unsafeRunSync()
    sup2.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()
    sup2.restore()
    sup2.get(key).get.defaultDatabase shouldBe Some("acme_default")
    sup2.get(key).get.defaultSchema shouldBe Some("tpch1")
  }

  // KNOWN GAP (ignored below): restore() drops kindWire and extraSetupSql.
  //
  // PoolSupervisor.restore() (PoolSupervisor.scala, the `pools.put(key,
  // PoolState(...))` block around line 234) rebuilds PoolState explicitly
  // field-by-field but never passes `kindWire` or `extraSetupSql`, so both
  // silently fall back to the PoolState case-class defaults ("ducklake" and
  // "" respectively - see PoolState.scala lines 14-15). createPool, by
  // contrast, sets `kindWire = td.kind.wireValue` (e.g. "memory" for an
  // InMemory tenant-db) and `extraSetupSql = fedBlob` (the resolved
  // federation blob, injected via the `federationBlobOf` constructor hook).
  //
  // Practical impact: any respawn driven by restore() - manager restart, an
  // HA replica rehydrating off a qod_topology NOTIFY - loses the federation
  // ATTACH blob and mis-tags a memory-kind pool's nodes as "ducklake" wire
  // kind, which changes what spawn-quack-node.sh does at boot.
  //
  // This test creates a pool on an InMemory tenant-db (kindWire should be
  // "memory") with a non-empty federation blob (via federationBlobOf),
  // snapshots the pre-restore PoolState, then calls restore() on the SAME
  // supervisor (mirroring the two tests above) and asserts kindWire and
  // extraSetupSql survive unchanged. It currently fails with
  // kindWire "ducklake" != "memory" (see .superpowers/sdd/pin-tests-report.md
  // for the captured run output). Un-ignore when fixing.
  ignore should "preserve kindWire and extraSetupSql across restore() (KNOWN GAP)" in {
    val store2 = new InMemoryControlPlaneStore()
    val sup2   = new PoolSupervisor(
      fakeBackend(),
      new NodeLoadTracker,
      store2,
      federationBlobOf = _ => IO.pure(Some("ATTACH 'fed.db' AS fedx;"))
    )
    sup2.createTenant(Tenant("acme")).unsafeRunSync()
    sup2.createTenantDb(
      tenantName = "acme", suffix = "default", kind = TenantDbKind.InMemory,
      metastore = Map.empty, dataPath = ""
    ).unsafeRunSync()
    sup2.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()

    val before = sup2.get(key).get
    before.kindWire shouldBe "memory"
    before.extraSetupSql should include("ATTACH 'fed.db' AS fedx;")

    sup2.restore()
    val after = sup2.get(key).get

    after.kindWire shouldBe before.kindWire
    after.extraSetupSql shouldBe before.extraSetupSql
    // The already-fixed defaultDatabase/defaultSchema carry-through (see the
    // test above) should also hold here.
    after.defaultDatabase shouldBe before.defaultDatabase
    after.defaultSchema shouldBe before.defaultSchema
  }

  // ---------- updateTenantDb ----------

  /** Fresh supervisor + tenant acme + DuckDbFile tenant-db with pgPassword in metastore
    * + one pool with 2 dual nodes. Returns (supervisor, backend, composed db name).
    */
  private def updateTenantDbFixture(): (PoolSupervisor, CapturingBackend, String) =
    val b   = new CapturingBackend
    val sup = new PoolSupervisor(b, new NodeLoadTracker, new InMemoryControlPlaneStore())
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb(
      tenantName  = "acme",
      suffix      = "secret",
      kind        = TenantDbKind.DuckDbFile,
      metastore   = Map("dbName" -> "acme_secret", "schemaName" -> "main", "pgPassword" -> "secret1"),
      dataPath    = "/tmp/test"
    ).unsafeRunSync()
    val dbPoolKey = PoolKey("acme", "acme_secret", "bi")
    sup.createPool(dbPoolKey, RoleDistribution(0, 0, 2)).unsafeRunSync()
    (sup, b, "acme_secret")

  "updateTenantDb" should "merge the patch, preserve redacted keys, and skip restart for metadata-only edits" in {
    val (sup, backend, dbName) = updateTenantDbFixture()
    val before = backend.stopped.size
    val out = sup.updateTenantDb("acme", dbName, TenantDbPatch(
      defaultDatabase = Some("fedpg"), defaultSchema = Some("")
    )).unsafeRunSync().toOption.get
    out.td.defaultDatabase shouldBe Some("fedpg")
    out.td.defaultSchema shouldBe None            // empty string clears
    out.td.metastore.get("pgPassword") shouldBe Some("secret1") // untouched section preserved
    out.restartedNodes shouldBe Nil               // metadata-only: no restart
    backend.stopped.size shouldBe before
  }

  it should "replace an edited map but preserve pgPassword when the incoming map lacks it" in {
    val (sup, _, dbName) = updateTenantDbFixture()
    // Send the full required-key set (dbName + schemaName); pgPassword is omitted so it is preserved.
    val out = sup.updateTenantDb("acme", dbName, TenantDbPatch(
      metastore = Some(Map("dbName" -> "acme_secret", "schemaName" -> "s2", "applicationName" -> "qod"))
    )).unsafeRunSync().toOption.get
    out.td.metastore.get("schemaName") shouldBe Some("s2")
    out.td.metastore.get("pgPassword") shouldBe Some("secret1") // preserved
    out.td.metastore.contains("applicationName") shouldBe true
  }

  it should "remove pgPassword when explicitly sent empty, and restart all nodes of the db" in {
    val (sup, backend, dbName) = updateTenantDbFixture()
    val before = backend.stopped.size
    // Send the full required-key set; pgPassword empty removes it explicitly.
    val out = sup.updateTenantDb("acme", dbName, TenantDbPatch(
      metastore = Some(Map("dbName" -> "acme_secret", "schemaName" -> "s2", "pgPassword" -> ""))
    )).unsafeRunSync().toOption.get
    out.td.metastore.contains("pgPassword") shouldBe false
    out.restartedNodes.size shouldBe 2            // both nodes of the db's pool restarted
    out.failedRestarts shouldBe Nil
    backend.stopped.size shouldBe before + 2
  }

  it should "Left on unknown tenant-db" in {
    val (sup, _, _) = updateTenantDbFixture()
    sup.updateTenantDb("acme", "acme_nope", TenantDbPatch()).unsafeRunSync().isLeft shouldBe true
  }

  it should "reject a patch that drops a required metastore key" in {
    // DuckDbFile requires dbName and schemaName; sending only pgPassword loses both.
    val (sup, _, dbName) = updateTenantDbFixture()
    val out = sup.updateTenantDb("acme", dbName, TenantDbPatch(
      metastore = Some(Map("pgPassword" -> "x"))
    )).unsafeRunSync()
    out.isLeft shouldBe true
    out.swap.toOption.get.message should include("drops required")
  }

  it should "succeed when a patch drops only a non-required custom key" in {
    // A DuckDbFile db with an extra custom key; dropping it must not be rejected.
    val b   = new CapturingBackend
    val sup = new PoolSupervisor(b, new NodeLoadTracker, new InMemoryControlPlaneStore())
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb(
      tenantName = "acme", suffix = "custom", kind = TenantDbKind.DuckDbFile,
      metastore  = Map("dbName" -> "acme_custom", "schemaName" -> "main", "appName" -> "qod"),
      dataPath   = "/tmp/custom"
    ).unsafeRunSync()
    // Send the full required set; omit the non-required "appName" key.
    val out = sup.updateTenantDb("acme", "acme_custom", TenantDbPatch(
      metastore = Some(Map("dbName" -> "acme_custom", "schemaName" -> "s2"))
    )).unsafeRunSync()
    out.isRight shouldBe true
    out.toOption.get.td.metastore.get("appName") shouldBe None
    out.toOption.get.td.metastore("schemaName")  shouldBe "s2"
  }

  // ---------- setPoolResources / setPoolTemplate ----------

  "PoolSupervisor.setPoolResources" should "persist cpu/memory and refresh PoolState" in {
    val sup = freshSupervisor()
    sup.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()
    val result = sup.setPoolResources(key, "500m", "2Gi").unsafeRunSync()
    result.toOption.get.cpu shouldBe "500m"
    result.toOption.get.memory shouldBe "2Gi"
    sup.get(key).map(_.cpu) shouldBe Some("500m")
    sup.get(key).map(_.memory) shouldBe Some("2Gi")
  }

  "PoolSupervisor.setPoolTemplate" should "persist the template" in {
    val sup = freshSupervisor()
    sup.createPool(key, RoleDistribution(0, 0, 1)).unsafeRunSync()
    val y = "apiVersion: v1\nkind: Pod\nspec:\n  containers:\n    - name: quack\n      image: x"
    val result = sup.setPoolTemplate(key, y).unsafeRunSync()
    result.toOption.get.podTemplateYaml shouldBe y
    sup.get(key).map(_.podTemplateYaml) shouldBe Some(y)
  }

  // E3: threading - createPool with cpu/memory must flow through to NodeSpec and PoolState

  "PoolSupervisor.createPool" should "thread cpu/memory/podTemplateYaml into every NodeSpec" in {
    val backend = new CapturingBackend
    val sup     = new PoolSupervisor(backend, new NodeLoadTracker, new InMemoryControlPlaneStore())
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, dataPath = "")
      .unsafeRunSync()
    val tmpl = "apiVersion: v1\nkind: Pod\nspec:\n  containers:\n    - name: quack\n      image: x"
    sup.createPool(
      key, RoleDistribution(0, 0, 2),
      cpu = "500m", memory = "2Gi", podTemplateYaml = tmpl
    ).unsafeRunSync()
    backend.specs.size shouldBe 2
    backend.specs.foreach { spec =>
      spec.cpu             shouldBe Some("500m")
      spec.memory          shouldBe Some("2Gi")
      spec.podTemplateYaml shouldBe Some(tmpl)
    }
  }

  it should "expose cpu/memory/podTemplateYaml on the PoolState after createPool" in {
    val (sup, _) = freshSupervisorWithBackend()
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, dataPath = "")
      .unsafeRunSync()
    val tmpl = "apiVersion: v1\nkind: Pod\nspec:\n  containers:\n    - name: quack\n      image: x"
    sup.createPool(
      key, RoleDistribution(0, 0, 1),
      cpu = "250m", memory = "1Gi", podTemplateYaml = tmpl
    ).unsafeRunSync()
    sup.get(key).map(_.cpu)             shouldBe Some("250m")
    sup.get(key).map(_.memory)          shouldBe Some("1Gi")
    sup.get(key).map(_.podTemplateYaml) shouldBe Some(tmpl)
  }

  // ---------- withCacheRecovery: caches converge to store on failed mutations ----------

  /** Delegates everything to an [[InMemoryControlPlaneStore]] but lets a test arm two seams:
    *   - `failAfterUpsertRole`: the role row IS persisted, then the call throws. Simulates a store
    *     write that lands followed by a failure BEFORE the supervisor's cache update runs (the
    *     cache would silently miss the persisted row without recovery).
    *   - `failUpsertNode`: the node write is refused outright. `setMaxConcurrent` updates the pools
    *     cache BEFORE its store write, so this leaves the cache AHEAD of the store without
    *     recovery.
    */
  private final class FaultInjectingStore extends ControlPlaneStore:
    val inner = new InMemoryControlPlaneStore()
    export inner.{upsertRole as _, upsertNode as _, *}

    @volatile var failAfterUpsertRole = false
    @volatile var failUpsertNode      = false

    def upsertRole(r: RbacRole): Unit =
      inner.upsertRole(r)
      if failAfterUpsertRole then throw new RuntimeException("boom: post-write failure")

    def upsertNode(n: RunningNode, poolId: String): Unit =
      if failUpsertNode then throw new RuntimeException("boom: node write refused")
      inner.upsertNode(n, poolId)

  "PoolSupervisor cache recovery" should
    "rebuild the RBAC caches from the store when a mutator fails after its store write" in {
      val store = new FaultInjectingStore
      val sup   = new PoolSupervisor(fakeBackend(), new NodeLoadTracker, store)
      sup.createTenant(Tenant("acme")).unsafeRunSync()
      val tenantId = sup.getTenant("acme").get.id

      store.failAfterUpsertRole = true
      val boom = intercept[RuntimeException] {
        sup.createRole(tenantId, "analyst").unsafeRunSync()
      }
      boom.getMessage should include("post-write failure")

      // The store write landed before the failure; recovery must have rebuilt the
      // resolver from the store so the persisted role is visible, not silently
      // missing from the cache until the next restart.
      val persisted = store.findRole(tenantId, "analyst")
      persisted shouldBe defined
      sup.rbacResolver.role(persisted.get.id) shouldBe defined
    }

  it should "roll the pool cache back to the store when the store write fails after the cache update" in {
    val store = new FaultInjectingStore
    val sup   = new PoolSupervisor(fakeBackend(), new NodeLoadTracker, store)
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, dataPath = "")
      .unsafeRunSync()
    sup.createPool(key, RoleDistribution(0, 0, 1), maxConcurrentPerNode = 2).unsafeRunSync()
    val nodeId = sup.get(key).get.nodes.head.nodeId

    store.failUpsertNode = true
    val boom = intercept[RuntimeException] {
      sup.setMaxConcurrent(key, nodeId, 99).unsafeRunSync()
    }
    boom.getMessage should include("node write refused")

    // setMaxConcurrent puts the patched node list into the pools cache BEFORE the
    // store write; the recovery must converge the cache back to the store value.
    sup.get(key).get.nodes.find(_.nodeId == nodeId).get.maxConcurrent shouldBe 2
  }

  it should "leave the happy path of a wrapped mutator unchanged" in {
    val store = new FaultInjectingStore
    val sup   = new PoolSupervisor(fakeBackend(), new NodeLoadTracker, store)
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    val tenantId = sup.getTenant("acme").get.id
    val created  = sup.createRole(tenantId, "analyst").unsafeRunSync()
    created.isRight shouldBe true
    sup.rbacResolver.role(created.toOption.get.id) shouldBe defined
    store.findRole(tenantId, "analyst") shouldBe defined
  }
