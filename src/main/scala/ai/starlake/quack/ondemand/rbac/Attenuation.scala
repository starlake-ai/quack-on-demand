package ai.starlake.quack.ondemand.rbac

import ai.starlake.quack.ondemand.auth.TokenRestriction

/** Narrows an [[EffectiveSet]] to what one token may use.
  *
  * Permissions and pool grants shrink. Column and row policies are passed through UNTOUCHED, and
  * that is a correctness condition, not an optimization: policies are attached to roles but
  * restrict tables that any role may grant. Row policies combine with `OR` and a table with no
  * matching policy is passthrough (`RowPolicyRewriter`), so dropping a policy-carrying role while
  * keeping a grant-carrying one would let the child see MORE rows than its parent. Anyone tempted
  * to filter policies here should read `AttenuationSpec`'s regression test first.
  */
object Attenuation:

  def attenuatedBy(eff: EffectiveSet, r: TokenRestriction): EffectiveSet =
    if r == TokenRestriction.Unrestricted then eff
    else
      val keptRoles = r.roles match
        case None        => eff.roles
        case Some(names) => eff.roles.filter(role => names.contains(role.name))
      val keptRoleIds = keptRoles.map(_.id).toSet

      // Groups pass through unchanged, exactly like the policies: an EffectiveSet
      // has already resolved group membership into `permissions` and `poolPerms`,
      // and RbacGroup carries no role reference to filter by, so a group list is
      // informational here and narrowing it would change no authorization decision.
      val keptGroups = eff.groups

      val clipped = eff.permissions.filter(p => keptRoleIds.contains(p.roleId)).flatMap { p =>
        r.verbCeiling match
          case None          => Some(p)
          case Some(ceiling) =>
            val residue =
              TokenRestriction.covers(p.verb).intersect(TokenRestriction.covers(ceiling))
            TokenRestriction.verbOf(residue).map(v => p.copy(verb = v))
      }

      // `poolPerms` is passed through UNCHANGED, deliberately, regardless of `r.pools`.
      // `poolPerms` carries `poolId` (from the RBAC grant table), not a pool name, and
      // `TokenRestriction.pools` is a set of names -- this function has no store to resolve one
      // into the other, and it must stay pure. More importantly, `poolPerms` is not where pool
      // scoping is enforced downstream: `authorizeHandshake` already used the OWNER's
      // (unattenuated) `poolPerms` to gate the handshake before this function ever runs, and
      // nothing after attenuation reads `poolPerms` again for a per-statement decision. The
      // `pools` axis is enforced separately, on the RESOLVED `PoolKey`, in
      // `Main.routedExecutor` (`caller.restriction.allowsPool(poolKey.pool)`) -- one choke
      // point every current and future caller of the executor routes through, rather than a
      // filter here that a store-less function could not make correct or meaningful anyway.
      eff.copy(
        roles = keptRoles,
        groups = keptGroups,
        permissions = clipped,
        poolPerms = eff.poolPerms,
        columnPolicies = eff.columnPolicies,
        rowPolicies = eff.rowPolicies
      )
