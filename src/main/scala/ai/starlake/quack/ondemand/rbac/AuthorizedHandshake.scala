package ai.starlake.quack.ondemand.rbac

import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.ondemand.state.RbacUser

/** Result of [[ai.starlake.quack.ondemand.PoolSupervisor.authorizeHandshake]]. Carries everything
  * FlightEdgeServer needs to bind a session: the resolved pool key + ids, the user row that was
  * matched, and the effective set (empty for superusers -- the validator bypasses them).
  *
  * Used by the FlightSQL handshake path; pinned onto [[ai.starlake.quack.edge.ConnectionContext]]
  * keyed by peerId so the per-statement ACL gate reads the cached snapshot.
  */
final case class AuthorizedHandshake(
    poolKey: PoolKey,
    tenantId: String,
    poolId: String,
    user: RbacUser,
    effectiveSet: EffectiveSet
):
  /** Convenience: a superuser bypasses pool-access AND per-statement ACL. */
  def isSuperuser: Boolean = user.tenant.isEmpty
