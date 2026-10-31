package ai.starlake.quack.edge.adapter

/** Why a node call failed. The message is DuckDB's (or the transport's) whole text, which can quote
  * the statement: answer the client with it, never log it for a client's statement
  * ([[QuackResponse]]).
  */
enum QuackError:
  case Transient(message: String) // retryable (5xx, connect refused, timeout)
  case Permanent(message: String) // not retryable (4xx, auth, parse error)
