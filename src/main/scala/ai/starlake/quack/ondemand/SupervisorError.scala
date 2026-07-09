package ai.starlake.quack.ondemand

/** Typed error for the [[PoolSupervisor]] mutators.
  *
  * Replaces the old `Either[String, X]` contract where REST handlers classified failures by
  * sniffing the message text (`startsWith("tenant not found")`, `contains("active pool")`,
  * `endsWith("not found")`, ...). Handlers now pattern-match on the case; `message` carries the
  * exact human-readable text the string contract used to emit, so REST response bodies are
  * unchanged.
  *
  * The case set is derived from the error categories the supervisor actually produces:
  *   - [[NotFound]]: a referenced entity (tenant, tenant-db, pool, node, user, role, group,
  *     permission, policy) does not exist.
  *   - [[AlreadyExists]]: unique-name collision on create (tenant, tenant-db, role, group).
  *   - [[Conflict]]: live dependents block the mutation (tenant / tenant-db still has active
  *     pools).
  *   - [[InvalidArgument]]: request payload failed validation (bad verb, bad authProvider, bad kind
  *     contract, cross-tenant membership edge, invalid transform / predicate SQL, ...).
  *   - [[InvalidName]]: slug normalization failure from [[ai.starlake.quack.model.Names]]. Kept
  *     distinct from [[InvalidArgument]] because the tenant / tenant-db create endpoints
  *     historically map these to `409 exists` (the catch-all), not `400`.
  *   - [[Internal]]: infrastructure or control-plane-state failure (Postgres provisioning failed,
  *     in-memory pool cache out of sync with the store). Only ever consumed by catch-all arms.
  */
enum SupervisorError(val message: String):
  case NotFound(msg: String)        extends SupervisorError(msg)
  case AlreadyExists(msg: String)   extends SupervisorError(msg)
  case Conflict(msg: String)        extends SupervisorError(msg)
  case InvalidArgument(msg: String) extends SupervisorError(msg)
  case InvalidName(msg: String)     extends SupervisorError(msg)
  case Internal(msg: String)        extends SupervisorError(msg)
