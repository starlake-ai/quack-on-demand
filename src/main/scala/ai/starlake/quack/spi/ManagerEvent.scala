package ai.starlake.quack.spi

/** Events the manager core emits to loaded modules.
  *
  * Delivery contract (see ModuleEventBus): asynchronous, at-most-once, per-replica, lost on crash,
  * dropped under backpressure. Events are freshness signals, not ledgers; anything billing-grade
  * must be derived from durable state and only refreshed by these.
  *
  * Fields are Strings/primitives on purpose: the SPI stays decoupled from core model types, whose
  * surface is exempt from the SPI stability promise.
  */
sealed trait ManagerEvent

object ManagerEvent:
  final case class StatementExecuted(
      tenant: String,
      tenantDb: String,
      pool: String,
      kind: String,
      user: String,
      durationMs: Long,
      ok: Boolean
  ) extends ManagerEvent
  final case class NodeStarted(tenant: String, tenantDb: String, pool: String, nodeId: String)
      extends ManagerEvent
  final case class NodeStopped(
      tenant: String,
      tenantDb: String,
      pool: String,
      nodeId: String,
      reason: String
  ) extends ManagerEvent
  final case class TenantCreated(tenant: String)                               extends ManagerEvent
  final case class TenantDeleted(tenant: String)                               extends ManagerEvent
  final case class TenantDbCreated(tenant: String, tenantDb: String)           extends ManagerEvent
  final case class TenantDbDeleted(tenant: String, tenantDb: String)           extends ManagerEvent
  final case class PoolCreated(tenant: String, tenantDb: String, pool: String) extends ManagerEvent
  final case class PoolDeleted(tenant: String, tenantDb: String, pool: String) extends ManagerEvent

  /** A new session was established. `via` identifies the entry point:
    *   - `"flightsql"`: an Arrow FlightSQL handshake.
    *   - `"rest"`: a REST password login (`AuthHandlers.login`).
    *   - `"oidc"`: a REST OIDC callback login (`AuthHandlers.oidcCallback`).
    */
  final case class SessionOpened(tenant: String, user: String, via: String) extends ManagerEvent

/** Core-side emission interface. Implementations must never block: the routing hot path calls
  * `emit` inline.
  */
trait ManagerEventSink:
  def emit(event: ManagerEvent): Unit

object ManagerEventSink:
  val noop: ManagerEventSink = (_: ManagerEvent) => ()
