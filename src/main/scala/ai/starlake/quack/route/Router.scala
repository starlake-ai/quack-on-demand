package ai.starlake.quack.route

import ai.starlake.quack.model.{Role, StatementKind}

object Router:

  def pick(
      snapshot: PoolSnapshot,
      kind: StatementKind,
      pinned: Option[String]
  ): RoutingDecision =
    pinned match
      case Some(id) =>
        if snapshot.nodes.exists(_.nodeId == id) then RoutingDecision.Use(id)
        else RoutingDecision.PinnedNodeGone(id)

      case None =>
        if snapshot.nodes.isEmpty then RoutingDecision.Unavailable("pool is empty")
        else
          val routable       = snapshot.nodes.filter(n => snapshot.loadOf(n.nodeId).routable)
          val availableRoles = routable.map(_.role).toSet
          val acceptable     = RoleMatcher.fallback(kind, availableRoles)
          if acceptable.isEmpty then
            val want = RoleMatcher.preferred(kind).map(_.toString.toUpperCase).mkString(" or ")
            RoutingDecision.Unavailable(s"no node with role $want")
          else
            val roleCompatible = routable.filter(n => acceptable.contains(n.role))
            val withCapacity   = roleCompatible.filter { n =>
              val l = snapshot.loadOf(n.nodeId)
              n.maxConcurrent == 0 || l.inFlight < n.maxConcurrent
            }
            if withCapacity.isEmpty then
              RoutingDecision.Unavailable("all compatible nodes at capacity")
            else
              val best = withCapacity.minBy { n =>
                val l = snapshot.loadOf(n.nodeId)
                (l.inFlight, l.ewmaMs)
              }
              RoutingDecision.Use(best.nodeId)
