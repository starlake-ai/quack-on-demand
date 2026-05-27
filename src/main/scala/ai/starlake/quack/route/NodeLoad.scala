package ai.starlake.quack.route

final case class NodeLoad(
    inFlight: Int,
    ewmaMs: Double,
    healthy: Boolean = true,
    draining: Boolean = false,
    // Total successful + failed statements completed since manager start.
    // Monotonic; resets only on manager restart.
    totalServed: Long = 0L
):
  def routable: Boolean = healthy && !draining

object NodeLoad:
  val empty: NodeLoad = NodeLoad(0, 0.0)