package ai.starlake.quack.ondemand.telemetry

import cats.effect.{Fiber, IO}
import com.typesafe.scalalogging.LazyLogging

import java.util.concurrent.ArrayBlockingQueue
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Bounded async writer for data-plane telemetry events (audit-log spec section 3). `offer` is a
  * plain non-blocking side effect so the FlightSQL statement hot path never waits on Postgres; a
  * drain fiber batches queued events into the store. Overflow and failed appends drop events and
  * report the count through `onDrop` (wired to the qod_journal_dropped_total counter). With a
  * disabled store (telemetry.store = none) offers are silent no-ops and nothing counts as dropped.
  */
final class EventJournal(
    store: TelemetryStore,
    capacity: Int = 8192,
    batchMax: Int = 500,
    flushInterval: FiniteDuration = 500.millis,
    onDrop: Int => Unit = _ => ()
) extends LazyLogging:

  private val queue = new ArrayBlockingQueue[AuditEvent](capacity)

  def offer(e: AuditEvent): Unit =
    if store.enabled then if !queue.offer(e) then onDrop(1)

  /** Drain everything currently queued, in batches of at most batchMax. */
  def drainNow(): Unit =
    val buf = new java.util.ArrayList[AuditEvent](batchMax)
    while queue.drainTo(buf, batchMax) > 0 do
      val batch = buf.asScala.toList
      try store.appendAudit(batch)
      catch
        case NonFatal(e) =>
          onDrop(batch.size)
          logger.error(
            s"telemetry journal append failed, ${batch.size} events dropped: ${e.getMessage}"
          )
      buf.clear()

  def start: IO[Fiber[IO, Throwable, Unit]] =
    (IO.blocking(drainNow()) *> IO.sleep(flushInterval)).foreverM.void.start

object EventJournal:
  /** For constructor defaults in tests and the telemetry.store=none wiring. */
  val noop: EventJournal = new EventJournal(NoopTelemetryStore)
