package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.edge.authz.{
  AttachedCatalogs,
  FederatedReadOnlyCatalogs,
  FederatedSourceCaches,
  HandleInspector,
  NodeKeys,
  NodePrincipal,
  PolicyVersions
}
import ai.starlake.quack.model.{PoolKey, Role, RunningNode, Tenant, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.ha.StateChangePublisher
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, InMemoryFederatedSourceStore}
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** Task 11 addendum 7: a federated source's read-only flag and its alias apply on the next
  * statement after the write, on the replica that served it and, once their restore runs (the
  * `qod_topology` NOTIFY the handler sends, or the periodic refresh), on its peers. The clock never
  * moves in these cases: the caches' TTL plays no part.
  */
class FederatedSourceCachesSpec extends AnyFlatSpec with Matchers:

  private val Pool  = PoolKey("acme", "acme_db", "bi")
  private val Other = PoolKey("acme", "other_db", "bi")
  private val Alice = NodePrincipal("u-1", "alice", Some("acme"), superuser = false, system = false)

  private def node(key: PoolKey) =
    RunningNode("quack-1", key, Role.Dual, "127.0.0.1", 21900, "tok", None, None, Instant.EPOCH)

  /** One replica: its supervisor over the shared control plane, its own caches over the shared
    * federation store, wired as Main wires them, and the handler that writes sources.
    */
  private final class Replica(store: InMemoryControlPlaneStore, fed: InMemoryFederatedSourceStore):
    val publishes = new AtomicInteger(0)
    val publisher = new StateChangePublisher:
      def topologyChanged(): Unit = publishes.incrementAndGet()
      def rbacChanged(): Unit     = ()
    val sup      = new PoolSupervisor(StubQuackBackend.noop(), new NodeLoadTracker, store)
    val readOnly = new FederatedReadOnlyCatalogs(clock = () => 0L)
    readOnly.wire(
      FederatedReadOnlyCatalogs.sourcesOf(
        k => sup.findTenantDb(k.tenant, k.tenantDb).map(_.id),
        Some(fed.listSources)
      )
    )
    val attached = new AttachedCatalogs(
      k =>
        sup
          .findTenantDb(k.tenant, k.tenantDb)
          .toList
          .flatMap(td => fed.listSources(td.id))
          .map(_.alias)
          .toSet,
      clock = () => 0L
    )
    val caches = new FederatedSourceCaches(readOnly, attached, sup.getTenantDbById)
    sup.onRestore(() => caches.clear())
    sup.restore()
    val handlers = new FederatedSourceHandlers(
      fed,
      (t, d) => sup.findTenantDb(t, d).map(_.id),
      scopeOf = _ => SessionScope.Superuser,
      catalogAliasOf = _ => None,
      invalidateSources = caches.sourcesChanged,
      publish = publisher
    )
    val keys = new NodeKeys(
      "s" * 32,
      "r",
      readOnlyCatalogs = readOnly,
      versions = PolicyVersions.zero
    )

    def handleReadOnly(key: PoolKey): List[String] =
      HandleInspector
        .payload(keys.handle(node(key), Alice))
        .hcursor
        .get[List[String]]("readOnly")
        .fold(e => fail(e.getMessage), identity)

    def put(alias: String, readOnly: Boolean): Unit =
      handlers
        .createSource(
          "acme",
          "acme_db",
          FederatedSourceCreateRequest(
            alias = alias,
            setupSql = Some(s"ATTACH ':memory:' AS $alias"),
            readOnly = Some(readOnly)
          ),
          Some("super")
        )
        .unsafeRunSync()
        .isRight shouldBe true

  private def controlPlane(): InMemoryControlPlaneStore =
    val store = new InMemoryControlPlaneStore
    store.upsertTenant(Tenant("acme"))
    List("td-1" -> "acme_db", "td-2" -> "other_db").foreach { (id, name) =>
      store.upsertTenantDb(
        TenantDb(
          id = id,
          tenantId = "acme",
          name = name,
          kind = TenantDbKind.InMemory,
          metastore = Map.empty,
          dataPath = ""
        )
      )
    }
    store

  "a federated source's read-only flag" should "reach the next handle of the serving replica" in {
    val r = new Replica(controlPlane(), new InMemoryFederatedSourceStore)
    r.put("crm", readOnly = false)
    r.handleReadOnly(Pool) shouldBe Nil
    r.handleReadOnly(Other) shouldBe Nil
    r.put("crm", readOnly = true)
    r.handleReadOnly(Pool) shouldBe List("crm")
    r.put("crm", readOnly = false)
    r.handleReadOnly(Pool) shouldBe Nil
  }

  it should "reach a peer's next handle once its restore ran" in {
    val store = controlPlane()
    val fed   = new InMemoryFederatedSourceStore
    val one   = new Replica(store, fed)
    val two   = new Replica(store, fed)
    one.put("crm", readOnly = false)
    two.handleReadOnly(Pool) shouldBe Nil
    one.put("crm", readOnly = true)
    // The NOTIFY has not reached the peer yet: it still holds the set it read.
    two.handleReadOnly(Pool) shouldBe Nil
    two.sup.restore()
    two.handleReadOnly(Pool) shouldBe List("crm")
  }

  "a federated source's alias" should "be attached on the next statement, and gone after a delete" in {
    val r = new Replica(controlPlane(), new InMemoryFederatedSourceStore)
    r.attached.of(Pool) shouldBe empty
    r.put("crm", readOnly = false)
    r.attached.of(Pool) shouldBe Set("crm")
    r.attached.of(Other) shouldBe empty
    r.handlers
      .deleteSource("acme", "acme_db", "crm", Some("super"))
      .unsafeRunSync()
      .isRight shouldBe
      true
    r.attached.of(Pool) shouldBe empty
  }

  "the handler" should "tell the peers after every stored write, and not after a refused one" in {
    val r = new Replica(controlPlane(), new InMemoryFederatedSourceStore)
    r.put("crm", readOnly = false)
    r.publishes.get shouldBe 1
    r.put("crm", readOnly = true)
    r.publishes.get shouldBe 2
    r.handlers
      .deleteSource("acme", "acme_db", "nope", Some("super"))
      .unsafeRunSync()
      .isLeft shouldBe
      true
    r.publishes.get shouldBe 2
    r.handlers
      .deleteSource("acme", "acme_db", "crm", Some("super"))
      .unsafeRunSync()
      .isRight shouldBe
      true
    r.publishes.get shouldBe 3
  }

  // The invalidation and the publisher are never defaulted: a construction that dropped either would
  // leave a source flagged read-only writable, or a new alias unknown, for the caches' TTL. Each
  // refusal is paired with the same construction given both, so it cannot pass for another reason.
  it should "need the invalidation and the publisher" in {
    """new FederatedSourceHandlers(
      new InMemoryFederatedSourceStore,
      (_, _) => None,
      scopeOf = _ => SessionScope.Superuser,
      catalogAliasOf = _ => None,
      publish = StateChangePublisher.noop
    )""" shouldNot typeCheck
    """new FederatedSourceHandlers(
      new InMemoryFederatedSourceStore,
      (_, _) => None,
      scopeOf = _ => SessionScope.Superuser,
      catalogAliasOf = _ => None,
      invalidateSources = _ => ()
    )""" shouldNot typeCheck
    """new FederatedSourceHandlers(
      new InMemoryFederatedSourceStore,
      (_, _) => None,
      scopeOf = _ => SessionScope.Superuser,
      catalogAliasOf = _ => None,
      invalidateSources = _ => (),
      publish = StateChangePublisher.noop
    )""" should compile
  }
