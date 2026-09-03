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

  /** `reason` is "rest" (explicit API call), "query" (edge wake path re-suspend guard), "module" (a
    * ManagerModule asked), or "idle" (the core hibernation sweep).
    */
  final case class PoolSuspended(tenant: String, tenantDb: String, pool: String, reason: String)
      extends ManagerEvent
  final case class PoolResumed(tenant: String, tenantDb: String, pool: String, reason: String)
      extends ManagerEvent

  /** A pool's node count changed through scale (manual REST call or the autoscale sweep). Emitted
    * after the mutation commits. `reason` is "manual" or "autoscale".
    */
  final case class PoolScaled(
      tenant: String,
      tenantDb: String,
      pool: String,
      fromSize: Int,
      toSize: Int,
      reason: String
  ) extends ManagerEvent

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

  /** Delivers each event to every sink in order. Sinks must not throw (the routing hot path calls
    * emit inline); no error isolation is added here.
    */
  def fanout(sinks: ManagerEventSink*): ManagerEventSink =
    (e: ManagerEvent) => sinks.foreach(_.emit(e))
