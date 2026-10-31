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
    quarantined: Boolean = false,
    // Node connections the native front door holds open on this node: a statement whose result
    // the client still reads, an open client transaction. Not load: routing ignores it. A drain
    // waits for it as for `inFlight` (see `idle`).
    held: Int = 0
):
  def routable: Boolean = healthy && !draining && !quarantined

  /** Nothing in flight and no front-door connection held: what a non-force drain waits for. */
  def idle: Boolean = inFlight == 0 && held == 0

object NodeLoad:
  val empty: NodeLoad = NodeLoad(0, 0.0)
