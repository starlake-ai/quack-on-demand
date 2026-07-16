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

  /** The telemetry tenant-scoping rule, shared by the audit / history / usage / active-statement
    * handlers (`None` result = no tenant restriction):
    *   - superuser or unresolvable session (static key / open mode): the requested tenant filter
    *     passes through unchanged;
    *   - tenant admin: pinned to `manageableTenants`; a requested tenant narrows WITHIN them, and a
    *     non-manageable request falls back to the full manageable set (no error, so a foreign
    *     tenant's existence never leaks through a differential response).
    */
  def tenantsFilter(
      scoped: Option[SessionScope],
      requested: Option[String]
  ): Option[Set[String]] =
    scoped match
      case Some(s) if !s.superuser =>
        Some(
          requested.filter(s.manageableTenants.contains).map(Set(_)).getOrElse(s.manageableTenants)
        )
      case _ =>
        requested.map(Set(_))
