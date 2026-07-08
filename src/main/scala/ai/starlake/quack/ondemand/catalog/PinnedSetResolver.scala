package ai.starlake.quack.ondemand.catalog

import ai.starlake.quack.ondemand.state.ControlPlaneStore

/** EPIC P2 shared primitive: the pin-set any maintenance/expiry run MUST consult before expiring
  * snapshots or deleting files. Nothing is materialized: snapshot pins are a control-plane read;
  * file pins are computed on demand from the DuckLake catalog with the same visibility predicate
  * the AS OF browser uses (validated against the engine).
  *
  * Consumers (planned): Spec 09 managed maintenance; later Spec 05 clone and Spec 08 sharing add
  * their own reference sources behind this same interface.
  */
final class PinnedSetResolver(
    store: ControlPlaneStore,
    resolveReader: (String, String) => DuckLakeCatalogReader
):

  /** Snapshot ids pinned by protected tags in (tenant, tenantDb). */
  def pinnedSnapshots(tenant: String, tenantDb: String): Set[Long] =
    store.listSnapshotTags(tenant, tenantDb).filter(_.isProtected).map(_.snapshotId).toSet

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
