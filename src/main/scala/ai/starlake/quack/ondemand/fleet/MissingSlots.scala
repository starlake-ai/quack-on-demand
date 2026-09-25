package ai.starlake.quack.ondemand.fleet

import ai.starlake.quack.model.{Role, RoleDistribution, RunningNode}

object MissingSlots:
  /** (nodeIndex, role) slots to spawn so `present` reaches `distribution`. Indices start above the
    * highest index parsed from present ids (trailing `-<n>`), never below present.size + 1.
    */
  def compute(distribution: RoleDistribution, present: List[RunningNode]): List[(Int, Role)] =
    val deficit: List[Role] = RoleDistribution.spawnOrder.flatMap { role =>
      List.fill((distribution.countFor(role) - present.count(_.role == role)).max(0))(role)
    }
    val base = nextIndexBase(present)
    deficit.zipWithIndex.map { case (role, i) => (base + i + 1, role) }

  /** Index after which fresh node ids are numbered: the highest trailing `-<n>` parsed from
    * `present` ids, never below `present.size`. Fresh ids are `base + 1, base + 2, ...`, so they
    * never collide with a present node even when a mid-index row was dropped.
    */
  def nextIndexBase(present: List[RunningNode]): Int =
    val highest =
      present.flatMap(_.nodeId.split('-').lastOption.flatMap(_.toIntOption)).maxOption.getOrElse(0)
    math.max(highest, present.size)
