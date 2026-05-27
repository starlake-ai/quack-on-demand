package ai.starlake.quack.edge.adapter

import org.apache.arrow.vector.ipc.ArrowReader

/** Outcome of a single forwarded query to a Quack node.
  *
  * [[Ok]] carries the streaming [[ArrowReader]] returned by DuckDB's
  * `quack_query()` extension. The caller is responsible for fully consuming
  * the reader and then calling `close()` to release the JDBC ResultSet and
  * its allocator buffers. [[Failed]] is non-streaming and carries no
  * resources to release. */
enum QuackResponse:
  case Ok(rows: ArrowReader, latencyMs: Long, close: () => Unit)
  case Failed(error: QuackError, latencyMs: Long = 0L)