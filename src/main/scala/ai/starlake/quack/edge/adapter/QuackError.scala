package ai.starlake.quack.edge.adapter

/** Why a node call failed. The message is DuckDB's (or the transport's) whole text, which can quote
  * the statement: answer the client with it, never log it for a client's statement
  * ([[QuackResponse]]).
  */
enum QuackError:

  /** Retryable (5xx, connect refused, timeout). `notSent`: the statement certainly never reached
    * the node (no connection, a refused session handle, a draining node), so another node may run
    * it. False means it may have run before its answer was lost: only a statement that is safe to
    * repeat may be sent again. False is the default, the side that never repeats a write.
    */
  case Transient(message: String, notSent: Boolean = false)
  case Permanent(message: String) // not retryable (4xx, auth, parse error)
