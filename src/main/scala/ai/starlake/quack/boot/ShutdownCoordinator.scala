package ai.starlake.quack.boot

import ai.starlake.quack.edge.FlightEdgeServer
import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.ondemand.ha.HaCoordinator
import ai.starlake.quack.ondemand.module.ModuleEventBus
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.{PostgresControlPlaneStore, UserStore}
import ai.starlake.quack.ondemand.telemetry.{EventJournal, TelemetryStore}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.foldable.*
import com.typesafe.scalalogging.LazyLogging

/** Manager teardown, extracted from Main.bootManager: the graceful cats-effect finalizer chain plus
  * the belt-and-braces JVM shutdown hook.
  *
  * The JVM hook is a FALLBACK, not a parallel teardown: when the JVM is told to exit (SIGTERM in
  * containers; user Ctrl-C at the terminal) we want every spawned child Quack node killed before we
  * let the process die, even if cats-effect's own cancellation finalizer never runs. The hook first
  * waits for `gracefulShutdown` (the finalizer chain) to terminate. Running both concurrently
  * killed nodes and closed the control-plane pools mid-drain, and let the not-yet-cancelled
  * reconcile fiber respawn "dead" nodes after the hook's cleanup - orphaning them past JVM exit.
  * The latch fires on any termination of the graceful chain (success, error, or cancellation); the
  * bounded wait keeps the hook a real backstop when the runtime is wedged. The hook's own cleanup
  * pass is idempotent so running it after a completed graceful chain stays safe.
  */
final class ShutdownCoordinator(
    edge: FlightEdgeServer,
    backend: QuackBackend,
    coordinator: Option[HaCoordinator],
    eventJournal: EventJournal,
    telemetryStore: TelemetryStore,
    store: PostgresControlPlaneStore,
    userStore: UserStore,
    catalogReaders: CatalogReaders,
    tracker: NodeLoadTracker,
    modules: List[ai.starlake.quack.spi.ManagerModule],
    moduleEventBus: ModuleEventBus,
    drainTimeoutSec: Int
) extends LazyLogging:

  private val gracefulDone = new java.util.concurrent.CountDownLatch(1)

  def installJvmHook(): Unit =
    val shutdownHook = new Thread(
      { () =>
        val finished =
          try
            gracefulDone.await(
              drainTimeoutSec.toLong + 15L,
              java.util.concurrent.TimeUnit.SECONDS
            )
          catch case _: InterruptedException => false
        if !finished then
          logger.warn(
            "shutdown hook: graceful shutdown did not finish in time; forcing teardown"
          )
        try edge.stop()
        catch case _: Throwable => ()
        try backend.cleanup().unsafeRunSync()
        catch case _: Throwable => ()
          // Release the leader lock + LISTEN connection. Terminal +
          // idempotent; safe to run before the pools are drained.
        try coordinator.foreach(_.close())
        catch case _: Throwable => ()
          // Drain the audit event journal and close the telemetry store
          // before the control-plane pool shuts down.
        try eventJournal.drainNow()
        catch case _: Throwable => ()
        try telemetryStore.close()
        catch case _: Throwable => ()
          // Drain the JDBC connection pools. Both close()s are
          // idempotent + no-op if already closed.
        try store.close()
        catch case _: Throwable => ()
        try userStore.close()
        catch case _: Throwable => ()
          // Stop the idle-eviction sweeper, then close every cached
          // catalog reader's Hikari pool.
        try catalogReaders.closeAllAndShutdown()
        catch case _: Throwable => ()
      },
      "qod-shutdown-hook"
    )
    Runtime.getRuntime.addShutdownHook(shutdownHook)

  /** Poll the load tracker until no node reports in-flight work, or `drainTimeoutSec` elapses. */
  private def waitForDrain: IO[Unit] =
    val deadlineNs =
      System.nanoTime() +
        scala.concurrent.duration.SECONDS.toNanos(drainTimeoutSec.toLong)
    def tick: IO[Unit] = IO
      .delay {
        tracker.snapshotAll.values.map(_.inFlight).sum
      }
      .flatMap { inflight =>
        if inflight <= 0 then IO.unit
        else if System.nanoTime() >= deadlineNs then
          IO.delay(
            logger.warn(
              s"graceful shutdown: $inflight statement(s) still in-flight " +
                s"after ${drainTimeoutSec}s; proceeding"
            )
          )
        else IO.sleep(scala.concurrent.duration.DurationInt(200).millis) *> tick
      }
    tick

  /** Graceful drain on cancellation: stop accepting new FlightSQL sessions, await in-flight work,
    * stop SPI modules (after the edge has drained but before the control-plane pools close, so a
    * module's stop can still reach its qodhosted_* tables; a stop failure is logged, not fatal),
    * then SIGTERM child Quack nodes via `backend.cleanup()`.
    */
  def gracefulShutdown: IO[Unit] =
    (IO.delay(logger.info("graceful shutdown: stopping FlightSQL edge")) *>
      IO.delay(edge.stop()) *>
      IO.delay(
        logger.info(
          s"graceful shutdown: awaiting in-flight statements (up to ${drainTimeoutSec}s)"
        )
      ) *>
      waitForDrain *>
      IO.delay(logger.info("graceful shutdown: stopping modules")) *>
      modules.traverse_(m =>
        m.stop.handleErrorWith(t =>
          IO(logger.warn(s"module ${m.name}: stop failed: ${t.getMessage}"))
        )
      ) *>
      IO.delay(moduleEventBus.shutdown()) *>
      IO.delay(logger.info("graceful shutdown: stopping child Quack nodes")) *>
      backend.cleanup() *>
      IO.delay(logger.info("graceful shutdown: complete")))
      // Release the JVM shutdown hook whichever way the WHOLE chain
      // ends (success, error, cancellation); otherwise the hook's
      // fallback wait runs its full timeout before forcing teardown.
      .guaranteeCase(_ => IO.delay(gracefulDone.countDown()))
