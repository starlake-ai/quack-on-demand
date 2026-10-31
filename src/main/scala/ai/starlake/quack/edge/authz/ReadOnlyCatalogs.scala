package ai.starlake.quack.edge.authz

import ai.starlake.quack.model.{FederatedAlias, FederatedSource, PoolKey}

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
  * ([[FederatedAlias.readOnlySet]]), cached per pool for `ttlMs` and dropped at once by a
  * federated-source write ([[FederatedSourceCaches]]: [[invalidate]] on the serving replica,
  * [[invalidateAll]] on every peer's restore), so a changed flag reaches the next handle. Disabled
  * sources count: a disabled source's alias stays ATTACHed on running nodes until the pool
  * recycles, so its flag keeps applying until then. The source lookup is [[wire]]d once the
  * supervisor exists; until then [[of]] throws, so no handle is minted without the set. A lookup
  * that throws fails its caller the same way: a statement fails rather than run without its pool's
  * read-only catalogs. The node alone enforces the flag (the gateway's read-only screen went with
  * spec decision 29).
  */
final class FederatedReadOnlyCatalogs(
    clock: () => Long = () => System.currentTimeMillis(),
    ttlMs: Long = 60_000L
) extends ReadOnlyCatalogs:

  private val sourcesOf = new AtomicReference[Option[PoolKey => List[FederatedSource]]](None)
  private val sets      = new PoolSetCache(clock, ttlMs)

  /** The federated sources of a pool's tenant-db ([[FederatedReadOnlyCatalogs.sourcesOf]]). */
  def wire(sources: PoolKey => List[FederatedSource]): Unit = sourcesOf.set(Some(sources))

  def of(key: PoolKey): Set[String] =
    sets.get(key) {
      sourcesOf.get() match
        case None =>
          throw new IllegalStateException("the read-only federated catalogs are not loaded yet")
        case Some(sources) => FederatedAlias.readOnlySet(sources(key))
    }

  /** Drops the set of every pool `keys` accepts: the next handle reads the sources again. */
  def invalidate(keys: PoolKey => Boolean): Unit = sets.invalidate(keys)

  /** Drops every pool's set. */
  def invalidateAll(): Unit = sets.invalidateAll()

object FederatedReadOnlyCatalogs:

  /** The source lookup Main wires: the federated sources of the pool's tenant-db, by its id
    * (`tenantDbIdOf`), from the manager's federation store (`listSources`); none without a store. A
    * tenant-db `tenantDbIdOf` does not know (a cache race, a deleted database) throws rather than
    * answering "no read-only catalog": [[FederatedReadOnlyCatalogs.of]] caches only a lookup that
    * returned, so the statement fails and the next one asks again (fail closed, since the node
    * alone enforces the flag).
    */
  def sourcesOf(
      tenantDbIdOf: PoolKey => Option[String],
      listSources: Option[String => List[FederatedSource]]
  ): PoolKey => List[FederatedSource] = key =>
    listSources match
      case None       => Nil
      case Some(list) =>
        tenantDbIdOf(key) match
          case Some(id) => list(id)
          case None     =>
            throw new NoSuchElementException(s"no tenant-db for ${key.tenant}/${key.tenantDb}")
