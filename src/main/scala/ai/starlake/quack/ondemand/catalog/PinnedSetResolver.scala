package ai.starlake.quack.ondemand.catalog

import ai.starlake.quack.model.BranchStatus
import ai.starlake.quack.ondemand.state.ControlPlaneStore

/** EPIC P2 shared primitive: the snapshots any maintenance/expiry run MUST NOT expire. Nothing is
  * materialized: pins are a control-plane read.
  *
  * Reference sources: protected snapshot tags (Spec 06) and, since Epic 1, the fork snapshot of
  * every LIVE branch of the tenant-db (a branch reads the parent's files as of its fork, so that
  * snapshot must outlive the branch). Spec 08 sharing is the remaining planned source.
  *
  * Beyond the pins, [[expiryFloor]] bounds a range: nothing at or after the oldest live branch's
  * fork may expire, since a merge reads the parent's changes over `(fork, head]`.
  *
  * There are no file pins. A per-file pin set derived from the parent cannot protect a branch:
  * merge_adjacent_files deletes the rows of the files it merges away, so the files visible at the
  * fork resolve to the merged file only (audit F25). Maintenance keeps a branch's files by skipping
  * merge and cleanup while [[hasLiveBranch]] holds; a protected tag needs nothing more, since time
  * travel at the tag reads the merged file.
  */
final class PinnedSetResolver(store: ControlPlaneStore):

  /** Snapshot ids pinned by protected tags in (tenant, tenantDb), unioned with the fork snapshots
    * of its live branches. `tenant` is the tenant id and `tenantDb` the tenant-db name, as
    * everywhere in the maintenance path.
    */
  def pinnedSnapshots(tenant: String, tenantDb: String): Set[Long] =
    val tagged =
      store.listSnapshotTags(tenant, tenantDb).filter(_.isProtected).map(_.snapshotId).toSet
    tagged ++ liveBranches(tenant, tenantDb).map(_.forkSnapshot)

  /** Whether (tenant, tenantDb) has a live branch. A branch reads the parent's Parquet files by
    * absolute path, and the parent's merge hard-deletes the rows of the files it merges away, so no
    * pin derived from the parent sees what a later cleanup would delete under the branch (audit
    * F25): maintenance skips merge and cleanup on the parent while this holds.
    */
  def hasLiveBranch(tenant: String, tenantDb: String): Boolean =
    liveBranches(tenant, tenantDb).nonEmpty

  /** Every snapshot at or after this must outlive the live branches: a three-way merge reads main's
    * changes over `(fork, head]` and the tables at the fork, and a snapshot gone from
    * `[fork, head]` hides its table touches from the merge, which refuses `history_gap` (design
    * section 9.1). None without a live branch. Cheap: merge and cleanup are already skipped while a
    * branch is live, so this keeps catalog rows, and a branch's TTL, when it has one, bounds how
    * long: with no TTL, expiry above that fork stops until a merge or a discard.
    */
  def expiryFloor(tenant: String, tenantDb: String): Option[Long] =
    liveBranches(tenant, tenantDb).map(_.forkSnapshot).minOption

  private def liveBranches(tenant: String, tenantDb: String): List[ai.starlake.quack.model.Branch] =
    store
      .listTenantDbs(tenant)
      .find(_.name == tenantDb)
      .map(td => store.listBranches(td.id, BranchStatus.Live))
      .getOrElse(Nil)
