package ai.starlake.quack.model

/** One sub-group of nodes inside a pool that share a single node-scheduling [[NodePlacement]]. A
  * pool with `cohorts = Nil` is treated as a single empty-placement cohort whose distribution
  * equals the pool's overall `RoleDistribution`, i.e. every node is scheduled with no placement
  * constraint.
  *
  * Example: a pool of 4 nodes pinned as "2 writers on tag X, 1 reader + 1 writer on tag Y" becomes
  * cohorts = List( PoolCohort(placement = NodePlacement(Map("disk"->"ssd")), distribution =
  * RoleDistribution(writeonly=2, readonly=0, dual=0)), PoolCohort(placement =
  * NodePlacement(Map("disk"->"hdd")), distribution = RoleDistribution(writeonly=1, readonly=1,
  * dual=0)) )
  */
final case class PoolCohort(
    placement: NodePlacement = NodePlacement.empty,
    distribution: RoleDistribution
)

object PoolCohort:
  /** Synthesize a single placement-less cohort from a flat distribution. The supervisor falls back
    * to this when a pool persists with `cohorts = Nil`.
    */
  def singleton(dist: RoleDistribution): PoolCohort =
    PoolCohort(NodePlacement.empty, dist)
