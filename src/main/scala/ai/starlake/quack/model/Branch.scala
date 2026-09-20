package ai.starlake.quack.model

import java.time.Instant

/** Lifecycle of a branch. `Open` and `Proposed` are the live states (compute + catalog exist); the
  * other three are terminal history rows whose catalog and pool have been torn down.
  */
enum BranchStatus(val wire: String):
  case Open      extends BranchStatus("open")
  case Proposed  extends BranchStatus("proposed")
  case Merged    extends BranchStatus("merged")
  case Discarded extends BranchStatus("discarded")
  case Expired   extends BranchStatus("expired")

  def isLive: Boolean = this == Open || this == Proposed

object BranchStatus:
  val Live: Set[BranchStatus] = Set(Open, Proposed)

  def fromWire(s: String): Either[String, BranchStatus] =
    values.find(_.wire == s).toRight(s"unknown BranchStatus: '$s'")

/** A writable branch of a DuckLake tenant-db (Epic 1). The branch is its own DuckLake catalog
  * (Postgres database `tenantDbName`, data prefix `dataPath`) registered as a `TenantDb` row
  * (`tenantDbId`, `branchOf = parentDbId`) and served by one auto-provisioned pool (`poolName`).
  * `forkSnapshot` is the parent snapshot the branch was cloned at; it is pinned against expiry on
  * the parent for as long as the branch is live (see PinnedSetResolver).
  */
final case class Branch(
    id: String,
    tenant: String,
    parentDbId: String,
    parentDbName: String,
    name: String,
    tenantDbId: String,
    tenantDbName: String,
    poolName: String,
    dataPath: String,
    forkSnapshot: Long,
    ownerUser: String,
    status: BranchStatus,
    expiresAt: Option[Instant],
    createdAt: Option[Instant] = None,
    updatedAt: Option[Instant] = None,
    purgedAt: Option[Instant] = None
)

enum BranchMergeStatus(val wire: String):
  case Proposed  extends BranchMergeStatus("proposed")
  case Merged    extends BranchMergeStatus("merged")
  case Failed    extends BranchMergeStatus("failed")
  case Abandoned extends BranchMergeStatus("abandoned")

object BranchMergeStatus:
  def fromWire(s: String): Either[String, BranchMergeStatus] =
    values.find(_.wire == s).toRight(s"unknown BranchMergeStatus: '$s'")

/** A merge request on a branch. `summaryJson` / `conflictsJson` are the serialized change set and
  * conflict list computed at propose time (informational; merge recomputes both). `tagName` is the
  * snapshot tag stamped on the parent's merge snapshot.
  */
final case class BranchMerge(
    id: String,
    branchId: String,
    proposer: String,
    approver: Option[String],
    status: BranchMergeStatus,
    summaryJson: String,
    conflictsJson: String,
    mainSnapshotAtPropose: Long,
    mainSnapshotAfter: Option[Long] = None,
    tagName: Option[String] = None,
    error: Option[String] = None,
    createdAt: Option[Instant] = None,
    decidedAt: Option[Instant] = None
)
