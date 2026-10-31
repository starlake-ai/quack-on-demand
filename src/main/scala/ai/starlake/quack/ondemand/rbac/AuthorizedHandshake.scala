package ai.starlake.quack.ondemand.rbac

import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.ondemand.state.RbacUser

/** Result of [[ai.starlake.quack.ondemand.PoolSupervisor.authorizeHandshake]]. Carries everything
  * FlightEdgeServer needs to bind a session: the resolved pool key + ids, the user row that was
  * matched, and the effective set (empty for superusers -- the node allows them without a
  * decision).
  *
  * Used by the FlightSQL handshake path; pinned onto [[ai.starlake.quack.edge.ConnectionContext]]
  * keyed by peerId, so each statement's session handle carries the cached snapshot until the
  * tenant's policy version moves.
  */
final case class AuthorizedHandshake(
    poolKey: PoolKey,
    tenantId: String,
    poolId: String,
    user: RbacUser,
    effectiveSet: EffectiveSet
):
  /** Convenience: a superuser bypasses pool access AND the node's per-statement decision. */
  def isSuperuser: Boolean = user.tenant.isEmpty
