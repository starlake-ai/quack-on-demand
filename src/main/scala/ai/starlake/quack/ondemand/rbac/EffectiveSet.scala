package ai.starlake.quack.ondemand.rbac

import ai.starlake.quack.ondemand.state._

/** Closure computed for one user at handshake time. Combines the user's direct edges (fetched from
  * Postgres) with the schema-bounded slice cached in [[RbacResolver]]:
  *
  * {{{
  *   effective_roles(U)  = direct_roles(U) ∪ ⋃ roles(g)   for g ∈ groups(U)
  *   effective_pools(U)  = direct_pools(U) ∪ ⋃ pools(g)   for g ∈ groups(U)
  *   effective_perms(U)  = ⋃ permissions(r)               for r ∈ effective_roles(U)
  * }}}
  *
  * One of these is pinned onto each FlightSQL [[ai.starlake.quack.edge.ConnectionContext]] keyed by
  * peerId so the per-statement ACL gate reads it without any further joins. The REST surface also
  * exposes it through `/user/{id}/effective` and reuses it inside the role/group/pool columns
  * served by `/user/list`.
  */
final case class EffectiveSet(
    user: RbacUser,
    roles: List[RbacRole],
    groups: List[RbacGroup],
    permissions: List[RolePermission],
    poolPerms: List[PoolPermission]
)
