package ai.starlake.quack.ondemand.module

import ai.starlake.quack.spi.{ManagerEvent, ManagerEventSink, ManagerModule}
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{ArrayBlockingQueue, ConcurrentHashMap, TimeUnit}

/** Bounded per-module event queues with one dispatcher loop each.
  *
  * The one rule: the routing hot path never blocks on a module. `sink.emit` is a non-blocking
  * `offer`; a full queue drops the event and bumps a per-module counter (logged every 1000 drops).
  * Dispatch is at-most-once, per-replica, lost on crash; this is intentional and permanent (see the
  * SPI design spec).
  */
final class ModuleEventBus(
    modules: List[ManagerModule],
    capacity: Int = ModuleEventBus.DefaultCapacity
) extends LazyLogging:

  private val queues: List[(ManagerModule, ArrayBlockingQueue[ManagerEvent])] =
    modules.map(m => m -> new ArrayBlockingQueue[ManagerEvent](capacity))

  private val drops              = new ConcurrentHashMap[String, AtomicLong]()
  @volatile private var stopping = false

  val sink: ManagerEventSink =
    if queues.isEmpty then ManagerEventSink.noop
    else
      (event: ManagerEvent) =>
        queues.foreach { case (m, q) =>
          if !q.offer(event) then
            val n = drops.computeIfAbsent(m.name, _ => new AtomicLong()).incrementAndGet()
            if n % 1000L == 1L then
              logger.warn(s"module ${m.name}: event queue full, $n events dropped so far")
        }

  def droppedCount(moduleName: String): Long =
    Option(drops.get(moduleName)).map(_.get()).getOrElse(0L)

  /** One infinite dispatcher per module. Short poll timeout so `shutdown()` is observed within
    * ~200ms; `onEvent` failures are logged and never propagate.
    */
  def dispatchers: List[IO[Unit]] =
    queues.map { case (m, q) =>
      def loop: IO[Unit] =
        IO.blocking(Option(q.poll(200, TimeUnit.MILLISECONDS)))
          .flatMap {
            case Some(e) =>
              m.onEvent(e)
                .handleErrorWith(t =>
                  IO(logger.warn(s"module ${m.name}: onEvent failed: ${t.getMessage}"))
                )
            case None => IO.unit
          }
          .flatMap(_ => if stopping then IO.unit else loop)
      loop
    }

  def shutdown(): Unit = stopping = true

object ModuleEventBus:
  val DefaultCapacity = 4096
