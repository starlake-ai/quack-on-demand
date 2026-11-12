package ai.starlake.quack.route

/** Why a node is unhealthy, as the pool listing shows it: a fixed `code`, one line of `detail` and
  * `sinceMs`, the epoch milliseconds of the first failure of the run it belongs to (a later failure
  * of another kind keeps it, so it says how long the node has been out).
  *
  * The detail is written by the manager from what it knows of the failure: the node's own address,
  * which the listing shows anyway, and the kind of failure. It never quotes a node's error text: a
  * tenant admin reads it, and an error can name QoD's Postgres server, which only superusers are
  * shown.
  */
final case class NodeUnhealthy(code: String, detail: String, sinceMs: Long)

object NodeUnhealthy:

  /** The kind of failure and its line, before a time is put on it. */
  final case class Cause(code: String, detail: String)

  /** Nothing answered at the node's address: no connection, or no session handle could be sent. */
  def unreachable(host: String, port: Int): Cause =
    Cause("unreachable", s"no connection to $host:$port: nothing answers there")

  /** The probe statement left and its answer was lost. */
  val ProbeLost: Cause =
    Cause("probe_lost", "the health probe's statement was sent and its answer was lost")

  /** The node answered the probe statement with an error. */
  val ProbeRefused: Cause =
    Cause(
      "probe_refused",
      "the node answered the health probe's statement with an error: its catalog may be " +
        "unreachable or refused at start"
    )

  /** The node refuses the manager's session handles: no statement runs on it. */
  val HandleRefused: Cause =
    Cause("handle_refused", "the node refuses the manager's session handles")

  /** The handle refusal says the node holds a key from another node-key secret. */
  val HandleKeyMismatch: Cause =
    Cause(
      "handle_refused",
      "the node refuses the manager's session handles: it holds a key derived from another " +
        "node-key secret; make QOD_NODE_KEY_SECRET the same on every replica (or unset on all), " +
        "then restart the node"
    )

  /** The handle refusal says the manager's and the node's clocks disagree. */
  val HandleClock: Cause =
    Cause(
      "handle_refused",
      "the node refuses the manager's session handles: the manager's and the node's clocks " +
        "disagree by about the handle lifetime or more; check clock sync"
    )

  /** The node ran a statement that carried no session handle. */
  val Unenforced: Cause =
    Cause(
      "unenforced",
      "the node ran a statement with no session handle: it does not enforce authorization, so " +
        "it is never routed to; restart it, or upgrade its image or fleet server"
    )

  /** The manager runs its nodes with local files off and this one did not show it. */
  def localFilesOn(reason: String): Cause = Cause("local_files_on", reason)

  /** The probe itself raised before it had an answer. */
  val ProbeError: Cause =
    Cause("probe_error", "the health probe could not run; the manager log has the error")

  /** A statement sent to the node failed in transport (the send path, not the probe). */
  val StatementLost: Cause =
    Cause("statement_failed", "a statement sent to the node failed in transport")
