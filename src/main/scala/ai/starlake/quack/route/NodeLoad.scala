package ai.starlake.quack.route

final case class NodeLoad(
    inFlight: Int,
    ewmaMs: Double,
    healthy: Boolean = true,
    draining: Boolean = false,
    // Total successful + failed statements completed since manager start.
    // Monotonic; resets only on manager restart.
    totalServed: Long = 0L,
    // Operator-initiated quarantine. Persisted in qodstate_node and only cleared by
    // an explicit un-quarantine; the health probe and the send path never write it.
    quarantined: Boolean = false
):
  def routable: Boolean = healthy && !draining && !quarantined

object NodeLoad:
  val empty: NodeLoad = NodeLoad(0, 0.0)
