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
    singleton: SingletonTasks,
    /** Resolves the caller's credential (the X-API-Key header or `qod_session` cookie value, None
      * when the request carried neither) to its scope, PAT-aware and total: no credential, or one
      * that does not resolve, is `SessionScope.NoAccess`. Same seam core handlers use for
      * TenantScopeCheck / SuperuserCheck. Exempt from the SPI stability promise, like supervisor
      * and users.
      */
    scopeOf: ai.starlake.quack.ondemand.auth.SessionScope.Resolver,
    /** Resolves a session JWT to the full session (identity + scope), None for anything else (a
      * personal access token included). scopeOf is the scope-only projection; portal-style modules
      * need the caller's username/tenant too. Exempt from the SPI stability promise.
      */
    sessionOf: String => Option[ai.starlake.quack.ondemand.api.SessionTokenStore.Session],
    /** Runs one statement on a pool as the manager's system caller, bound to this module's name
      * (labelled and audited with it). Superuser-level: for the module's own provisioning
      * statements, never for SQL a user supplied. See [[SystemSql]] for the trust model. Required:
      * every context is built with a module's own instance (Main builds one context per module).
      */
    systemSql: SystemSql
)
