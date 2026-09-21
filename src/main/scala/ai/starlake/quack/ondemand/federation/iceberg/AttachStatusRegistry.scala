package ai.starlake.quack.ondemand.federation.iceberg

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

/** One catalog that is declared for a pool but is not attached on a node, with the DuckDB error
  * that explains why.
  */
final case class CatalogAttachFailure(
    alias: String,
    error: String,
    at: Instant,
    attempts: Int
)

/** Live attach state per `(nodeId, alias)`, plus the retry backoff.
  *
  * Deliberately NOT persisted: it describes live process state, and a manager restart re-probes
  * every node anyway. Under HA each replica verifies the nodes it tracks, so this is replica-local
  * (the same trade-off already accepted for the revoked-jti denylist).
  *
  * Bounded by construction: one entry per (live node, declared alias).
  */
final class AttachStatusRegistry(
    baseBackoffMs: Long = 30000L,
    maxBackoffMs: Long = 300000L
):

  private final case class Entry(error: String, at: Instant, attempts: Int, lastAttemptMs: Long)

  private val failures = new ConcurrentHashMap[(String, String), Entry]()
  private val attached = ConcurrentHashMap.newKeySet[(String, String)]()
  private val complete = ConcurrentHashMap.newKeySet[String]()

  def recordAttached(nodeId: String, alias: String): Unit =
    failures.remove((nodeId, alias))
    attached.add((nodeId, alias))

  /** Returns true when this failure is NEW or its error text CHANGED, which is the only time the
    * caller should emit a WARN. A permanently broken catalog then costs one log line, not one per
    * health tick.
    */
  def recordFailure(
      nodeId: String,
      alias: String,
      error: String,
      nowMs: Long = System.currentTimeMillis()
  ): Boolean =
    attached.remove((nodeId, alias))
    val prev       = Option(failures.get((nodeId, alias)))
    val attempts   = prev.map(_.attempts + 1).getOrElse(1)
    val noteworthy = prev.forall(_.error != error)
    failures.put(
      (nodeId, alias),
      Entry(error, Instant.ofEpochMilli(nowMs), attempts, nowMs)
    )
    complete.remove(nodeId)
    noteworthy

  /** Exponential backoff per `(nodeId, alias)`: the health-tick interval, doubling to a ceiling. A
    * never-attempted pair always retries.
    */
  def shouldRetry(
      nodeId: String,
      alias: String,
      nowMs: Long = System.currentTimeMillis()
  ): Boolean =
    Option(failures.get((nodeId, alias))) match
      case None    => true
      case Some(e) =>
        val shift = math.min(e.attempts - 1, 20)
        val delay = math.min(baseBackoffMs * (1L << shift), maxBackoffMs)
        nowMs - e.lastAttemptMs >= delay

  def latched(nodeId: String): Boolean  = complete.contains(nodeId)
  def markLatched(nodeId: String): Unit = complete.add(nodeId)

  /** Drop every trace of a node that is gone, so a scaled-down pool leaves nothing behind. */
  def forgetNode(nodeId: String): Unit =
    complete.remove(nodeId)
    failures.keySet().asScala.filter(_._1 == nodeId).foreach(failures.remove)
    attached.asScala.filter(_._1 == nodeId).toList.foreach(attached.remove)

  def failuresFor(nodeId: String): List[CatalogAttachFailure] =
    failures.asScala.toList
      .collect {
        case ((n, alias), e) if n == nodeId =>
          CatalogAttachFailure(alias, e.error, e.at, e.attempts)
      }
      .sortBy(_.alias)

  /** Operator-facing summary of one alias across a pool's nodes: `attached`, `unknown`, or
    * `failed on N of M nodes`.
    */
  def aliasSummary(alias: String, nodeIds: Set[String]): Option[String] =
    if nodeIds.isEmpty then Some("unknown")
    else
      val failing = nodeIds.count(n => failures.containsKey((n, alias)))
      val ok      = nodeIds.count(n => attached.contains((n, alias)))
      if failing > 0 then Some(s"failed on $failing of ${nodeIds.size} nodes")
      else if ok == nodeIds.size then Some("attached")
      else Some("unknown")
