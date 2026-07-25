package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{
  NodeSpec,
  PoolKey,
  RoleDistribution,
  RunningNode,
  Tenant,
  TenantDbKind
}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import ai.starlake.quack.ondemand.telemetry.AuditRecorder
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

import java.time.Instant
import scala.collection.concurrent.TrieMap

/** Handler-level spec for `POST /api/pool/setLockdown`, modeled on the `setPodTemplate` cases in
  * [[PoolHandlersSpec]]: SuperuserCheck gate, invalid-input 400, not-found 404, and the persist +
  * restart + echo happy path. Uses a local capturing backend (same shape as `PoolSupervisorSpec`'s)
  * so the restart side effect (stop + fresh start per node) is observable.
  */
class PoolLockdownHandlerSpec extends AnyFlatSpec with Matchers:

  private final class CapturingBackend extends QuackBackend:
    private val nodes                                         = TrieMap.empty[String, RunningNode]
    val stopped: scala.collection.mutable.Set[String]         = scala.collection.mutable.Set.empty
    val starts: scala.collection.mutable.ListBuffer[NodeSpec] =
      scala.collection.mutable.ListBuffer.empty

    def start(spec: NodeSpec): IO[RunningNode] = IO {
      starts += spec
      val n = RunningNode(
        spec.nodeId,
        spec.poolKey,
        spec.role,
        "127.0.0.1",
        21000 + nodes.size,
        "tok-" + spec.nodeId,
        Some(1L),
        None,
        Instant.EPOCH,
        maxConcurrent = spec.maxConcurrent
      )
      nodes.put(spec.nodeId, n); n
    }
    def stop(id: String): IO[Unit]                = IO { stopped += id; nodes.remove(id); () }
    def isAlive(id: String): Boolean              = nodes.contains(id)
    def discoverExisting(): IO[List[RunningNode]] = IO.pure(nodes.values.toList)
    def cleanup(): IO[Unit]                       = IO(nodes.clear())

  /** Supervisor + tenant `acme` + tenant-db `acme_default`, backed by a [[CapturingBackend]] so
    * tests can assert on the restart side effect.
    */
  private def freshHandlers(
      lockdownEnabled: Boolean = false
  ): (PoolHandlers, PoolSupervisor, CapturingBackend, RecordingTelemetryStore) =
    val backend = new CapturingBackend
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(
      backend,
      tracker,
      new InMemoryControlPlaneStore(),
      lockdownEnabled = lockdownEnabled
    )
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup.createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, "").unsafeRunSync()
    val auditStore = new RecordingTelemetryStore
    val audit      = new AuditRecorder(auditStore, _ => None)
    (new PoolHandlers(sup, tracker, audit = audit), sup, backend, auditStore)

  private def req(
      pool: String = "sales",
      size: Int = 1,
      dist: RoleDistribution = RoleDistribution(0, 0, 1)
  ): CreatePoolRequest =
    CreatePoolRequest(
      tenant = "acme",
      tenantDb = "acme_default",
      pool = pool,
      size = size,
      roleDistribution = dist
    )

  private def tenantScope(tenant: String): SessionScope =
    SessionScope(superuser = false, manageableTenants = Set(tenant))

  "pool/setLockdown" should "403 a tenant-admin session before any state change" in:
    val (h, sup, _, auditStore) = freshHandlers()
    h.createPool(req(), None)((_: String) => None).unsafeRunSync()
    val key = PoolKey("acme", "acme_default", "sales")
    val out = h
      .setLockdown(
        SetPoolLockdownRequest("acme", "acme_default", "sales", "on"),
        Some("tok")
      )(_ => Some(tenantScope("acme")))
      .unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.Forbidden)
    sup.effectiveLockdown(key) shouldBe false
    // The denied audit event fires before any state change (same gate shape
    // as createPool's lockdown gate).
    auditStore.events should not be empty
    val denied = auditStore.events.last
    denied.action shouldBe "pool.setLockdown"
    denied.outcome shouldBe "denied"
    denied.tenant shouldBe Some("acme")

  it should "400 on an invalid tri-state value" in:
    val (h, _, _, _) = freshHandlers()
    h.createPool(req(), None)((_: String) => None).unsafeRunSync()
    val out = h
      .setLockdown(
        SetPoolLockdownRequest("acme", "acme_default", "sales", "banana"),
        None
      )((_: String) => None)
      .unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.BadRequest)
    out.left.toOption.map(_._2.error) shouldBe Some("invalid")

  it should "404 on an unknown pool for a superuser" in:
    val (h, _, _, _) = freshHandlers()
    val out          = h
      .setLockdown(
        SetPoolLockdownRequest("acme", "acme_default", "missing", "on"),
        Some("tok")
      )(_ => Some(SessionScope.Superuser))
      .unsafeRunSync()
    out.left.toOption.map(_._1) shouldBe Some(StatusCode.NotFound)

  it should "persist the override, restart the pool's nodes, and echo it in PoolResponse" in:
    val (h, sup, backend, auditStore) = freshHandlers(lockdownEnabled = false)
    h.createPool(req(), None)((_: String) => None).unsafeRunSync()
    val key           = PoolKey("acme", "acme_default", "sales")
    val nodeIdsBefore = sup.get(key).map(_.nodes.map(_.nodeId)).getOrElse(Nil)
    nodeIdsBefore should not be empty

    val out = h
      .setLockdown(
        SetPoolLockdownRequest("acme", "acme_default", "sales", "on"),
        Some("tok")
      )(_ => Some(SessionScope.Superuser))
      .unsafeRunSync()

    out shouldBe a[Right[?, ?]]
    val Right(resp) = out: @unchecked
    resp.lockdown shouldBe "on"
    resp.lockdownEffective shouldBe true
    sup.effectiveLockdown(key) shouldBe true
    nodeIdsBefore.foreach(id => backend.stopped should contain(id))
    // The respawn half must be stamped with the engine lockdown block at spawn
    // time, not merely flip the persisted flag: the pool started with lockdown
    // off (empty lockdownSql), so the only NodeSpec carrying the engine block
    // is the one respawned after setLockdown("on").
    backend.starts.last.lockdownSql should include(
      "SET autoinstall_known_extensions = false"
    )
    // The ok arm audits the action with the lockdown value in detail and the
    // pool key (tenant/tenantDb/pool) as the target.
    auditStore.events should not be empty
    val ok = auditStore.events.last
    ok.action shouldBe "pool.setLockdown"
    ok.outcome shouldBe "ok"
    ok.tenant shouldBe Some("acme")
    ok.target shouldBe Some(key.toString)
    ok.detail.get("lockdown") shouldBe Some("on")

  it should "map inherit back to the global flag in lockdownEffective" in:
    val (h, sup, _, _) = freshHandlers(lockdownEnabled = true)
    h.createPool(req(), None)((_: String) => None).unsafeRunSync()
    val out = h
      .setLockdown(
        SetPoolLockdownRequest("acme", "acme_default", "sales", "inherit"),
        Some("tok")
      )(_ => Some(SessionScope.Superuser))
      .unsafeRunSync()
    out shouldBe a[Right[?, ?]]
    val Right(resp) = out: @unchecked
    resp.lockdown shouldBe "inherit"
    resp.lockdownEffective shouldBe true
