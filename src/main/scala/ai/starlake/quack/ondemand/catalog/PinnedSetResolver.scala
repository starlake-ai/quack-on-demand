package ai.starlake.quack.ondemand.catalog

import ai.starlake.quack.model.BranchStatus
import ai.starlake.quack.ondemand.state.ControlPlaneStore

/** EPIC P2 shared primitive: the pin-set any maintenance/expiry run MUST consult before expiring
  * snapshots or deleting files. Nothing is materialized: snapshot pins are a control-plane read;
  * file pins are computed on demand from the DuckLake catalog with the same visibility predicate
  * the AS OF browser uses (validated against the engine).
  *
  * Reference sources: protected snapshot tags (Spec 06) and, since Epic 1, the fork snapshot of
  * every LIVE branch of the tenant-db (a branch reads the parent's files as of its fork, so that
  * snapshot must outlive the branch). Spec 08 sharing is the remaining planned source.
  */
final class PinnedSetResolver(
    store: ControlPlaneStore,
    resolveReader: (String, String) => DuckLakeCatalogReader
):

  /** Snapshot ids pinned by protected tags in (tenant, tenantDb), unioned with the fork snapshots
    * of its live branches. `tenant` is the tenant id and `tenantDb` the tenant-db name, as
    * everywhere in the maintenance path.
    */
  def pinnedSnapshots(tenant: String, tenantDb: String): Set[Long] =
    val tagged =
      store.listSnapshotTags(tenant, tenantDb).filter(_.isProtected).map(_.snapshotId).toSet
    val forks = store
      .listTenantDbs(tenant)
      .find(_.name == tenantDb)
      .map(td => store.listBranches(td.id, BranchStatus.Live).map(_.forkSnapshot).toSet)
      .getOrElse(Set.empty)
    tagged ++ forks

  /** Every file path referenced by any pinned snapshot of (tenant, tenantDb): data files and delete
    * files visible at each pinned snapshot, unioned. Empty when nothing is pinned, WITHOUT touching
    * the catalog.
    */
  def pinnedFiles(tenant: String, tenantDb: String): Set[String] =
    val snaps = pinnedSnapshots(tenant, tenantDb)
    if snaps.isEmpty then Set.empty
    else
      val reader = resolveReader(tenant, tenantDb)
      snaps.flatMap(reader.filesReferencedAt)
