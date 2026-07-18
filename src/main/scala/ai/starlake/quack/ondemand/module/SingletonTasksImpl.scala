package ai.starlake.quack.ondemand.module

import ai.starlake.quack.spi.SingletonTasks
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.FiniteDuration

/** Collects registrations during `ManagerModule.start`; `Main` turns them into one fiber per task,
  * gated per tick on the HA leader flag - the same shape as the telemetry purge / rollup fibers. In
  * non-HA mode the gate is constantly true.
  */
final class SingletonTasksImpl extends SingletonTasks with LazyLogging:

  private val registrations = ListBuffer.empty[(String, FiniteDuration, IO[Unit])]

  def register(name: String, interval: FiniteDuration)(task: IO[Unit]): Unit =
    synchronized(registrations += ((name, interval, task)))

  def loops(isLeader: () => Boolean): List[IO[Unit]] =
    synchronized(registrations.toList).map { case (name, interval, task) =>
      (IO.defer(
        if isLeader() then
          task.handleErrorWith(t =>
            IO(
              logger.warn(
                s"module singleton task '$name' failed, retrying next tick: ${t.getMessage}"
              )
            )
          )
        else IO.unit
      ) *> IO.sleep(interval)).foreverM.void
    }
