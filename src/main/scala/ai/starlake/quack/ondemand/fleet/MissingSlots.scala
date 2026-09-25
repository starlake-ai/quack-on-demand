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
    val highest =
      present.flatMap(_.nodeId.split('-').lastOption.flatMap(_.toIntOption)).maxOption.getOrElse(0)
    val base = math.max(highest, present.size)
    deficit.zipWithIndex.map { case (role, i) => (base + i + 1, role) }
