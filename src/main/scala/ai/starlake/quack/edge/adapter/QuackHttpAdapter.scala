package ai.starlake.quack.edge.adapter

import ai.starlake.quack.edge.authz.{NodeHandles, NodeKeys, NodePrincipal}
import ai.starlake.quack.model.RunningNode
import cats.effect.{IO, Outcome}
import com.typesafe.scalalogging.LazyLogging

final class QuackHttpAdapter(
    client: QuackHttpClient,
    tracker: NodeLoadTracker,
    keys: NodeKeys = NodeKeys.ephemeral()
) extends LazyLogging:

  /** Send `sql` to `node` for `principal`: every PREPARE of the call follows a fresh session handle
    * ([[authorizer]]). Records load, EWMA, and health side-effects on the tracker. Returns the raw
    * response - the caller is responsible for closing any streaming reader inside
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
      principal: NodePrincipal,
      session: Option[String],
      recordLoad: Boolean = true,
      stampPrelude: Option[String] = None
  ): IO[QuackResponse] =
    // Quack's URI scheme; QuackProtocol maps it to http://host:port/quack.
    val endpoint  = s"quack:${node.host}:${node.port}"
    val authorize = authorizer(node, principal)
    val call      = stampPrelude match
      case Some(p) => client.queryStamped(endpoint, node.token, authorize, p, sql)
      case None    => client.query(endpoint, node.token, sql, session, authorize)
    withLoad(node, recordLoad)(call)(NodeOutcome.fromQuackResponse)

  /** The prelude producer for statements of `principal` on `node`: a fresh session handle each
    * call, so a PREPARE sent long after another (a COMMIT) still carries a valid one.
    */
  def authorizer(node: RunningNode, principal: NodePrincipal): () => String =
    () => keys.prelude(node, principal)

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

  /** Liveness and enforcement probe, as the system principal: true iff `sql` runs AND the node
    * refuses a statement with no session handle (the canary). A node that runs the canary does not
    * enforce authorization: it is reported once (ERROR) and stays unhealthy, so it is never routed
    * to. A canary that fails for another reason leaves the verdict to `sql`. Skips all tracker
    * bookkeeping, like the send it mirrors.
    */
  def probe(node: RunningNode, sql: String = "SELECT 1"): IO[Boolean] =
    val endpoint = s"quack:${node.host}:${node.port}"
    client.query(endpoint, node.token, sql, None, authorizer(node, NodePrincipal.System)).flatMap {
      case QuackResponse.Ok(_, _, close) => IO(close()) *> canary(node)
      case _                             => IO.pure(false)
    }

  /** Node ids whose canary ran: reported once until they enforce again. */
  private val unenforced = scala.collection.concurrent.TrieMap.empty[String, Unit]

  private def canary(node: RunningNode): IO[Boolean] =
    client
      .queryWithoutHandle(s"quack:${node.host}:${node.port}", node.token, "SELECT 1")
      .map {
        case QuackResponse.Ok(_, _, close) =>
          close()
          if unenforced.putIfAbsent(node.nodeId, ()).isEmpty then
            logger.error(
              s"node ${node.nodeId} ran a statement that carried no session handle: it does not " +
                "enforce authorization, so it is marked unhealthy and never routed to"
            )
          false
        case QuackResponse.Failed(QuackError.Permanent(m), _)
            if m.contains(NodeHandles.NoPrincipalMarker) =>
          unenforced.remove(node.nodeId)
          true
        case QuackResponse.Failed(err, _) =>
          logger.debug(s"canary on ${node.nodeId} inconclusive: $err")
          true
      }

  /** Scrape the node's DuckDB engine internals (buffer-manager memory, temp storage, spill files)
    * in one round-trip via [[EngineStats.sql]]. Same policy as [[probe]]: skips ALL tracker
    * bookkeeping so the background scrape is invisible to the load/latency stats. None when the
    * node fails or the result has an unexpected shape - never throws.
    */
  def engineStats(node: RunningNode): IO[Option[EngineStats]] =
    val endpoint = s"quack:${node.host}:${node.port}"
    client
      .query(endpoint, node.token, EngineStats.sql, None, authorizer(node, NodePrincipal.System))
      .map {
        case QuackResponse.Ok(rows, _, close) =>
          val out =
            try EngineStats.fromReader(rows)
            finally close()
          if out.isEmpty then
            logger.debug(s"engineStats ${node.nodeId}: result decoded to no sample")
          out
        case QuackResponse.Failed(err, _) =>
          logger.debug(s"engineStats ${node.nodeId}: scrape failed: $err")
          None
      }
