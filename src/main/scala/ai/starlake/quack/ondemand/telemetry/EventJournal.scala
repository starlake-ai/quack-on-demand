package ai.starlake.quack.ondemand.telemetry

import cats.effect.{Fiber, IO}
import com.typesafe.scalalogging.LazyLogging

import java.util.concurrent.ArrayBlockingQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Bounded async writer for data-plane telemetry events (audit-log spec section 3). `offer` and
  * `offerStatement` are plain non-blocking side effects so the FlightSQL statement hot path never
  * waits on Postgres; a drain fiber batches queued events into the store. Two separate bounded
  * queues are maintained: one for [[AuditEvent]] rows and one for [[StatementEvent]] rows. Overflow
  * and failed appends drop events and report the count through `onDrop` (audit queue) or
  * `onStatementDrop` (statement queue), each wired to a labeled Prometheus counter. With a disabled
  * store (telemetry.store = none) all offers are silent no-ops and nothing counts as dropped.
  */
final class EventJournal(
    store: TelemetryStore,
    capacity: Int = 8192,
    batchMax: Int = 500,
    flushInterval: FiniteDuration = 500.millis,
    onDrop: Int => Unit = _ => (),
    onStatementDrop: Int => Unit = _ => ()
) extends LazyLogging:

  private val queue          = new ArrayBlockingQueue[AuditEvent](capacity)
  private val statementQueue = new ArrayBlockingQueue[StatementEvent](capacity)

  def offer(e: AuditEvent): Unit =
    if store.enabled then if !queue.offer(e) then onDrop(1)

  def offerStatement(e: StatementEvent): Unit =
    if store.enabled then if !statementQueue.offer(e) then onStatementDrop(1)

  /** Drain everything currently queued, in batches of at most batchMax. Audit events are flushed
    * first, then statement events. A failed batch append counts the dropped rows through the
    * respective drop callback and the drain continues.
    */
  def drainNow(): Unit =
    val auditBuf = new java.util.ArrayList[AuditEvent](batchMax)
    while queue.drainTo(auditBuf, batchMax) > 0 do
      val batch = auditBuf.asScala.toList
      try store.appendAudit(batch)
      catch
        case NonFatal(e) =>
          onDrop(batch.size)
          logger.error(
            s"telemetry journal audit append failed, ${batch.size} events dropped: ${e.getMessage}"
          )
      auditBuf.clear()

    val stmtBuf = new java.util.ArrayList[StatementEvent](batchMax)
    while statementQueue.drainTo(stmtBuf, batchMax) > 0 do
      val batch = stmtBuf.asScala.toList
      try store.appendStatements(batch)
      catch
        case NonFatal(e) =>
          onStatementDrop(batch.size)
          logger.error(
            s"telemetry journal statement append failed, ${batch.size} events dropped: ${e.getMessage}"
          )
      stmtBuf.clear()

  def start: IO[Fiber[IO, Throwable, Unit]] =
    (IO.blocking(drainNow()) *> IO.sleep(flushInterval)).foreverM.void.start

object EventJournal:
  /** For constructor defaults in tests and the telemetry.store=none wiring. */
  val noop: EventJournal = new EventJournal(NoopTelemetryStore)
