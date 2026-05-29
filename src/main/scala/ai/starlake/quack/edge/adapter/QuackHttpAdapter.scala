package ai.starlake.quack.edge.adapter

import ai.starlake.quack.model.RunningNode
import cats.effect.IO

final class QuackHttpAdapter(client: QuackHttpClient, tracker: NodeLoadTracker):

  /** Send `sql` to `node`. Records load, EWMA, and health side-effects on the
    * tracker. Returns the raw response - the caller is responsible for closing
    * any streaming reader inside [[QuackResponse.Ok]]. */
  def send(node: RunningNode, sql: String, session: Option[String]): IO[QuackResponse] =
    // Quack's URI scheme; DuckDB's `quack_query` parses it.
    val endpoint = s"quack:${node.host}:${node.port}"
    IO.delay(tracker.onStart(node.nodeId)) *>
      client.query(endpoint, node.token, sql, session).flatMap { resp =>
        IO.delay {
          val latency = resp match
            case QuackResponse.Ok(_, l, _)  => l
            case QuackResponse.Failed(_, l) => l
          tracker.onFinish(node.nodeId, latency)
          resp match
            case QuackResponse.Ok(_, _, _) =>
              tracker.setHealthy(node.nodeId, true)
            case QuackResponse.Failed(QuackError.Transient(_), _) =>
              tracker.setHealthy(node.nodeId, false)
            case _ => ()
          resp
        }
      }

  /** Fire-and-forget liveness probe. Performs the same wire round-trip as
    * [[send]] but skips all tracker bookkeeping (inFlight, totalServed,
    * EWMA, p50/p95/p99) so background health checks don't inflate the
    * UI counters or skew the latency percentiles. Closes any streaming
    * reader on success. Returns `true` iff the query came back Ok. */
  def probe(node: RunningNode, sql: String = "SELECT 1"): IO[Boolean] =
    val endpoint = s"quack:${node.host}:${node.port}"
    client.query(endpoint, node.token, sql, None).map {
      case QuackResponse.Ok(_, _, close) => close(); true
      case _                             => false
    }