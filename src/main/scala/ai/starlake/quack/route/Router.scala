package ai.starlake.quack.route

import ai.starlake.quack.model.{Role, StatementKind}

object Router:

  def pick(
      snapshot: PoolSnapshot,
      kind: StatementKind,
      pinned: Option[String]
  ): RoutingDecision = pick(snapshot, kind, pinned, placement = None)

  /** Placement-aware pick. With `placement = None` this is exactly the historical least-loaded
    * choice (the config kill-switch and every non-eligible statement take that path). With a
    * PlacementRequest, role/health/capacity filtering is unchanged; only the final choice among
    * eligible nodes swaps minBy(load) for the overlap score, with least-loaded as tie-break and a
    * per-candidate load cap c x max(1, avg inFlight of the other routable nodes) bounding skew
    * (spec 2026-07-27, section 3).
    */
  def pick(
      snapshot: PoolSnapshot,
      kind: StatementKind,
      pinned: Option[String],
      placement: Option[PlacementRequest]
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
              val best = placement match
                case Some(p) if p.tables.nonEmpty && p.assignments.nonEmpty =>
                  // Per-candidate cap: c x max(1, avg inFlight of the OTHER routable nodes).
                  // Excluding the candidate from its own average keeps the cap meaningful when
                  // the candidate carries most of the pool's load (a pool-wide average lets a
                  // 2-node pool's busy home inflate its own cap and never overflow).
                  val inFlightOf =
                    routable.map(n => n.nodeId -> snapshot.loadOf(n.nodeId).inFlight).toMap
                  val total                          = inFlightOf.values.sum
                  def capFor(nodeId: String): Double =
                    val others =
                      if routable.size <= 1 then 0.0
                      else (total - inFlightOf.getOrElse(nodeId, 0)).toDouble / (routable.size - 1)
                    p.loadCapFactor * math.max(1.0, others)
                  val underCap =
                    withCapacity.filter(n => inFlightOf.getOrElse(n.nodeId, 0) <= capFor(n.nodeId))
                  val candidates = if underCap.nonEmpty then underCap else withCapacity
                  def score(nodeId: String): Int =
                    p.tables.iterator.map { t =>
                      p.assignments.get(t) match
                        case Some(a) =>
                          a.homes.find(_.nodeId == nodeId) match
                            case Some(h) => if h.warmEpoch == a.currentEpoch then 2 else 1
                            case None    => 0
                        case None => 0
                    }.sum
                  candidates.minBy { n =>
                    val l = snapshot.loadOf(n.nodeId)
                    (-score(n.nodeId), l.inFlight, l.ewmaMs)
                  }
                case _ =>
                  withCapacity.minBy { n =>
                    val l = snapshot.loadOf(n.nodeId)
                    (l.inFlight, l.ewmaMs)
                  }
              RoutingDecision.Use(best.nodeId)
