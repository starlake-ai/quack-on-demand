package ai.starlake.quack.mcp

import ai.starlake.quack.ondemand.auth.{PatPrincipal, SessionScope, TokenRestriction}
import ai.starlake.quack.ondemand.state.RbacUser

/** The superuser credential the MCP tool specs act with: a personal access token owned by a
  * superuser row (`tenant = None`, kind `admin`), unrestricted. [[principal]] is what
  * `McpRoutes.authenticate` builds for such a bearer, so a tool sees what it sees in production: an
  * admin bound to no tenant (an explicit `tenant` argument is required, as for every superuser
  * credential), whose raw token is curried into the REST handlers.
  *
  * The specs resolve that token with [[scopeOf]] (to `SessionScope.Superuser`) and, for the
  * handlers that build an executor caller through `RestCaller`, with [[patOf]].
  */
object McpSuperuserPat:

  /** The raw bearer, PAT-shaped like a real one. */
  val Token: String = "qod_pat_superuser_fixture"

  val PatId: String = "pat-superuser"

  val Owner: RbacUser = RbacUser(id = "u-root", tenant = None, username = "root", kind = "admin")

  val Principal: PatPrincipal = PatPrincipal(
    user = Owner,
    patId = PatId,
    scope = SessionScope.Superuser,
    isAdmin = true,
    restriction = TokenRestriction.Unrestricted
  )

  /** The MCP principal for [[Token]]. */
  val principal: McpPrincipal = new McpPrincipal.Pat(Principal, Token)

  /** [[Token]] to `SessionScope.Superuser`; anything else, no credential included, to
    * `SessionScope.NoAccess`: total, like the resolver Main wires.
    */
  val scopeOf: SessionScope.Resolver =
    t => if t.contains(Token) then SessionScope.Superuser else SessionScope.NoAccess

  /** [[Token]] to [[Principal]], for a `RestCaller(patOf = ...)`. */
  val patOf: String => Option[PatPrincipal] = t => Option.when(t == Token)(Principal)
