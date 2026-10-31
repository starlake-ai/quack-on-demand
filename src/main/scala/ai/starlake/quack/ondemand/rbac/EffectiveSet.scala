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
  * peerId, and each statement's session handle carries it (the node decider judges with it)
  * without any further joins. The REST surface also
  * exposes it through `/user/{id}/effective` and reuses it inside the role/group/pool columns
  * served by `/user/list`.
  */
final case class EffectiveSet(
    user: RbacUser,
    roles: List[RbacRole],
    groups: List[RbacGroup],
    permissions: List[RolePermission],
    poolPerms: List[PoolPermission],
    columnPolicies: List[RoleColumnPolicy] = Nil,
    rowPolicies: List[RoleRowPolicy] = Nil,
    // Verified token claims of the session that pinned this set; never part of the
    // effective-set cache key (copied in after the cache lookup).
    claims: Map[String, String] = Map.empty,
    // The collapsed verbs a personal access token admits (set by Attenuation from its
    // verbCeiling; None = no ceiling). The QoD arm needs no such field, since attenuation
    // already clipped `permissions`; the OPA arm never reads permissions, so it refuses any
    // access outside this set before asking OPA.
    verbCeiling: Option[Set[ai.starlake.acl.parser.Verb]] = None,
    // RoutedExecutor's system caller (ExecCaller.system): the manager acting for itself, never a
    // user. Set only there; nodes run its statements as NodePrincipal.System.
    system: Boolean = false,
    // The personal access token restriction this set was narrowed by (set by Attenuation, the one
    // place a token narrows a set); None when no token did. The node decision pipeline reads it
    // (branchOnly, and the session context the handle digests). Like `claims`, never part of the
    // effective-set cache key.
    restriction: Option[ai.starlake.quack.ondemand.auth.TokenRestriction] = None
)
