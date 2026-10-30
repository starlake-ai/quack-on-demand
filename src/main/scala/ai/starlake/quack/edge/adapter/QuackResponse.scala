package ai.starlake.quack.edge.adapter

import org.apache.arrow.vector.ipc.ArrowReader

/** Outcome of a single forwarded query to a Quack node.
  *
  * [[ai.starlake.quack.edge.adapter.QuackResponse.Ok Ok]] carries the streaming `ArrowReader` the
  * native Quack wire client decodes from the node. The caller is responsible for fully consuming
  * the reader and then calling `close()` to release the node connection and its allocator buffers.
  * [[ai.starlake.quack.edge.adapter.QuackResponse.Failed Failed]] is non-streaming and carries no
  * resources to release.
  *
  * `Failed`'s message is DuckDB's error as the node raised it, whole: a binder or parser error
  * quotes the statement (its literals included, a user's data or a secret in a CREATE SECRET), and
  * a refusal names tables and locations. For a client's statement it goes back to the client that
  * sent it. A caller must not log it, nor put it in a metric label, an audit detail or a module
  * event: log the error class (`Transient` / `Permanent`), the node and the latency. The router's
  * history row is the one place that keeps it, beside the client's own statement. The manager's own
  * statements (health probes, stats reads, maintenance, catalog reads) quote no client text, and
  * their failures may be logged.
  */
enum QuackResponse:
  case Ok(rows: ArrowReader, latencyMs: Long, close: () => Unit)
  case Failed(error: QuackError, latencyMs: Long = 0L)
