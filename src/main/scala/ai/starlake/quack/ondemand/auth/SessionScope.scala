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

  /** No privilege at all: not a superuser, no manageable tenant. What a [[Resolver]] answers for an
    * absent credential or one that does not resolve, so every scope gate refuses it and every
    * tenant filter is empty.
    */
  val NoAccess: SessionScope = SessionScope(superuser = false, manageableTenants = Set.empty)

  /** What every scope gate reads: the caller's credential (the `X-API-Key` header or `qod_session`
    * cookie value, `None` when the request carried neither) to its scope. Total: there is no
    * trusted answer for an absent credential.
    */
  type Resolver = Option[String] => SessionScope

  /** The [[Resolver]] over a token lookup (a session JWT, then a PAT). No credential, or one the
    * lookup cannot resolve, is [[NoAccess]].
    *
    * Load-bearing because the guard and the handler resolve the token at different instants: a
    * session that expired (or was revoked) after the guard admitted it reaches the handler with no
    * privilege, never with a superuser's (manifest export, every tenant in the listings, the RBAC
    * tenant-scope gates).
    */
  def resolver(lookup: String => Option[SessionScope]): Resolver =
    credential => credential.flatMap(lookup).getOrElse(NoAccess)

  /** The telemetry tenant-scoping rule, shared by the audit / history / usage / active-statement
    * handlers (`None` result = no tenant restriction):
    *   - superuser: the requested tenant filter passes through unchanged;
    *   - anyone else: pinned to `manageableTenants`; a requested tenant narrows WITHIN them, and a
    *     non-manageable request falls back to the full manageable set (no error, so a foreign
    *     tenant's existence never leaks through a differential response). [[NoAccess]] sees
    *     nothing.
    */
  def tenantsFilter(scope: SessionScope, requested: Option[String]): Option[Set[String]] =
    if scope.superuser then requested.map(Set(_))
    else
      Some(
        requested
          .filter(scope.manageableTenants.contains)
          .map(Set(_))
          .getOrElse(scope.manageableTenants)
      )
