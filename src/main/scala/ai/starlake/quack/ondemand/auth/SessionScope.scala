package ai.starlake.quack.ondemand.auth

/** Authorization envelope attached to a UI session.
  *
  * `superuser = true` => cross-tenant; `manageableTenants` is ignored. Otherwise the session is
  * confined to the listed tenants by the per-request `tenant_forbidden` guard.
  *
  * `superuser = false && manageableTenants.isEmpty` is intentionally rejected at login
  * (`admin_required`); the type allows it so tests can construct an "empty" scope.
  */
final case class SessionScope(superuser: Boolean, manageableTenants: Set[String])

object SessionScope:
  /** Convenience: a superuser scope (no tenant restriction). */
  val Superuser: SessionScope = SessionScope(superuser = true, manageableTenants = Set.empty)
