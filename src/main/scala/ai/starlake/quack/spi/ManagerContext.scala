package ai.starlake.quack.spi

import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.state.UserStore
import ai.starlake.quack.ondemand.telemetry.AuditRecorder
import cats.effect.IO

import scala.concurrent.duration.FiniteDuration

/** Recurring module duties (hibernation sweep, metering reconciliation). Under HA only the
  * advisory-lock leader runs each tick; in local / single-replica mode ticks always run. `register`
  * may only be called from inside `ManagerModule.start`.
  */
trait SingletonTasks:
  def register(name: String, interval: FiniteDuration)(task: IO[Unit]): Unit

/** Everything a module gets from the manager. The `supervisor` and `users` surfaces are exempt from
  * the SPI stability promise (the hosted repo pins an exact core version); the stable part of the
  * SPI is the trait set in this package plus the event model.
  */
final case class ManagerContext(
    supervisor: PoolSupervisor,
    users: UserStore,
    controlPlaneDs: javax.sql.DataSource,
    rawConfig: com.typesafe.config.Config,
    audit: AuditRecorder,
    singleton: SingletonTasks
)
