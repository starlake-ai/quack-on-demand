package ai.starlake.quack.edge.adapter

import ai.starlake.quack.model.RunningNode
import cats.effect.{IO, Outcome}
import com.typesafe.scalalogging.LazyLogging

final class QuackHttpAdapter(client: QuackHttpClient, tracker: NodeLoadTracker) extends LazyLogging:

  /** Send `sql` to `node`. Records load, EWMA, and health side-effects on the tracker. Returns the
    * raw response - the caller is responsible for closing any streaming reader inside
    * [[QuackResponse.Ok]].
    *
    * `recordLoad=false` suppresses ALL NodeLoadTracker side effects (inFlight, totalServed, ewmaMs,
    * latency-ring percentiles, healthy flag). Used by the FlightSQL Prepare-time LIMIT-0 probe so
    * the internal schema-probe round-trip is invisible to the per-node load/latency stats surfaced
    * on the UI dashboard (Total Served, QPS, p50/p95/p99). Mirrors [[probe]]'s policy: a controlled
    * internal call shouldn't bookkeep against capacity or trip the health flag.
    *
    * `stampPrelude` when supplied routes to [[QuackHttpClient.queryStamped]] to run the prelude and
    * sql as two PREPARE statements on one wire connection; when None, routes to
    * [[QuackHttpClient.query]].
    */
  def send(
      node: RunningNode,
      sql: String,
      session: Option[String],
      recordLoad: Boolean = true,
      stampPrelude: Option[String] = None
  ): IO[QuackResponse] =
    // Quack's URI scheme; DuckDB's `quack_query` parses it.
    val endpoint = s"quack:${node.host}:${node.port}"
    val call     = stampPrelude match
      case Some(p) => client.queryStamped(endpoint, node.token, p, sql)
      case None    => client.query(endpoint, node.token, sql, session)
    withLoad(node, recordLoad)(call)(NodeOutcome.fromQuackResponse)

  /** Run any node call with the same load, latency and health bookkeeping as [[send]]. This is what
    * the native Quack relay wraps its per-statement node connection in, so both transports feed the
    * per-node dashboard stats identically. `recordLoad=false` skips every side effect, as for
    * [[send]].
    */
  def tracked[A](node: RunningNode, recordLoad: Boolean)(
      call: IO[NodeOutcome[A]]
  ): IO[NodeOutcome[A]] =
    withLoad(node, recordLoad)(call)(identity)

  /** The in-flight bracket around one node call. `onStart` and the release are the acquire and
    * release of a `bracketCase`, so a cancelled or raised call still gives its slot back: it is
    * released without a latency sample, since there is no outcome to book.
    */
  private def withLoad[A](node: RunningNode, recordLoad: Boolean)(
      call: IO[A]
  )(outcome: A => NodeOutcome[?]): IO[A] =
    if !recordLoad then call
    else
      IO.delay(tracker.onStart(node.nodeId)).bracketCase(_ => call) {
        case (_, Outcome.Succeeded(fa)) => fa.flatMap(a => IO.delay(bookkeep(node, outcome(a))))
        case (_, _)                     => IO.delay(tracker.onAbort(node.nodeId))
      }

  private def bookkeep(node: RunningNode, out: NodeOutcome[?]): Unit =
    tracker.onFinish(node.nodeId, out.latency)
    out match
      case NodeOutcome.Ok(_, _, _)     => tracker.setHealthy(node.nodeId, true)
      case NodeOutcome.Transient(_, _) => tracker.setHealthy(node.nodeId, false)
      case NodeOutcome.Permanent(_, _) => ()

  /** Fire-and-forget liveness probe. Performs the same wire round-trip as [[send]] but skips all
    * tracker bookkeeping (inFlight, totalServed, EWMA, p50/p95/p99) so background health checks
    * don't inflate the UI counters or skew the latency percentiles. Closes any streaming reader on
    * success. Returns `true` iff the query came back Ok.
    */
  def probe(node: RunningNode, sql: String = "SELECT 1"): IO[Boolean] =
    val endpoint = s"quack:${node.host}:${node.port}"
    client.query(endpoint, node.token, sql, None).map {
      case QuackResponse.Ok(_, _, close) => close(); true
      case _                             => false
    }

  /** Scrape the node's DuckDB engine internals (buffer-manager memory, temp storage, spill files)
    * in one round-trip via [[EngineStats.sql]]. Same policy as [[probe]]: skips ALL tracker
    * bookkeeping so the background scrape is invisible to the load/latency stats. None when the
    * node fails or the result has an unexpected shape - never throws.
    */
  def engineStats(node: RunningNode): IO[Option[EngineStats]] =
    val endpoint = s"quack:${node.host}:${node.port}"
    client.query(endpoint, node.token, EngineStats.sql, None).map {
      case QuackResponse.Ok(rows, _, close) =>
        val out =
          try EngineStats.fromReader(rows)
          finally close()
        if out.isEmpty then logger.debug(s"engineStats ${node.nodeId}: result decoded to no sample")
        out
      case QuackResponse.Failed(err, _) =>
        logger.debug(s"engineStats ${node.nodeId}: scrape failed: $err")
        None
    }
