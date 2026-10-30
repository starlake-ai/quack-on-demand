package ai.starlake.quack.edge.authz

import ai.starlake.quack.model.{FederatedAlias, FederatedSource, PoolKey}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Each pool's read-only federated catalogs (decision 24): the aliases of its tenant-db's sources
  * flagged `readOnly`, folded with `Locale.ROOT`. Every session handle but the system principal's
  * carries them ([[NodeKeys.handle]]), and qod-node refuses any principal's write, DDL, ATTACH or
  * DETACH into one, superusers included.
  */
trait ReadOnlyCatalogs:
  def of(key: PoolKey): Set[String]

object ReadOnlyCatalogs:

  /** No catalog read-only on any pool: for components a test builds without federated sources. */
  val none: ReadOnlyCatalogs = _ => Set.empty

/** [[ReadOnlyCatalogs]] from the federated sources of each pool's tenant-db
  * ([[FederatedAlias.readOnlySet]]), cached per pool for `ttlMs`, so a changed flag reaches handles
  * within that window. Disabled sources count: a disabled source's alias stays ATTACHed on running
  * nodes until the pool recycles, so its flag keeps applying until then. The source lookup is
  * [[wire]]d once the supervisor exists; until then [[of]] throws, so no handle is minted without
  * the set. A lookup that throws fails its caller the same way: a statement fails rather than run
  * without its pool's read-only catalogs. The router's `CatalogWriteScreen` reads the same instance
  * until it goes.
  */
final class FederatedReadOnlyCatalogs(
    clock: () => Long = () => System.currentTimeMillis(),
    ttlMs: Long = 60_000L
) extends ReadOnlyCatalogs:

  private val sourcesOf = new AtomicReference[Option[PoolKey => List[FederatedSource]]](None)
  private val cache     = new ConcurrentHashMap[PoolKey, (Long, Set[String])]()

  /** The federated sources of a pool's tenant-db (none for a pool it does not know). */
  def wire(sources: PoolKey => List[FederatedSource]): Unit = sourcesOf.set(Some(sources))

  def of(key: PoolKey): Set[String] =
    val now = clock()
    Option(cache.get(key)).collect { case (at, set) if now - at < ttlMs => set }.getOrElse {
      sourcesOf.get() match
        case None =>
          throw new IllegalStateException("the read-only federated catalogs are not loaded yet")
        case Some(sources) =>
          val set = FederatedAlias.readOnlySet(sources(key))
          cache.put(key, (now, set))
          set
    }
