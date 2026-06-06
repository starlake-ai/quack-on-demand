// src/main/scala/ai/starlake/quack/ondemand/manifest/ManifestImporter.scala
package ai.starlake.quack.ondemand.manifest

import ai.starlake.quack.model.{Pool, RoleDistribution, Tenant, TenantDb}
import ai.starlake.quack.ondemand.state.{
  ControlPlaneStore,
  PoolPermission,
  RbacGroup,
  RbacRole,
  RolePermission
}

import java.util.UUID

object ManifestImporter:

  type ValidationResult = Either[List[String], Unit]

  // ------------------------------------------------------------------
  // validate
  // ------------------------------------------------------------------

  def validate(m: ConfigManifest, store: ControlPlaneStore): ValidationResult =
    val errs = scala.collection.mutable.ListBuffer.empty[String]

    if m.apiVersion != ConfigManifest.ApiVersion then
      errs += s"apiVersion '${m.apiVersion}' is not supported (expected '${ConfigManifest.ApiVersion}')"

    // Tenants in YAML + tenants already in the DB form the set of valid tenants
    // a user/role/group may reference.
    val tenantsInYaml = m.tenants.map(_.name).toSet
    val tenantsInDb   = store.listTenants().map(_.displayName).toSet
    val knownTenants  = tenantsInYaml ++ tenantsInDb

    // Dup detection
    def dup[A, K](xs: List[A], key: A => K, label: String): Unit =
      xs.groupBy(key).collect { case (k, vs) if vs.size > 1 =>
        errs += s"duplicate $label: $k"
      }
    dup(m.tenants,        _.name,                       "tenant name")
    dup(m.roles,          r => (r.tenant, r.name),      "role (tenant, name)")
    dup(m.groups,         g => (g.tenant, g.name),      "group (tenant, name)")
    dup(m.users,          u => (u.tenant, u.username),  "user (tenant, username)")

    // Per-tenant nested duplicates
    m.tenants.foreach { t =>
      dup(t.tenantDbs, _.name, s"tenant-db within ${t.name}")
      dup(t.pools,     _.name, s"pool within ${t.name}")
    }

    // Cross-references
    m.users.foreach { u =>
      u.tenant.foreach { tn =>
        if !knownTenants.contains(tn) then
          errs += s"user '${u.username}': tenant '$tn' not in YAML or DB"
      }
      val rolesInTenant = m.roles.filter(_.tenant == u.tenant.getOrElse("")).map(_.name).toSet
      val groupsInTenant = m.groups.filter(_.tenant == u.tenant.getOrElse("")).map(_.name).toSet
      u.roles.foreach { r =>
        if !rolesInTenant.contains(r) then
          errs += s"user '${u.username}' references role '$r' not defined in tenant '${u.tenant.getOrElse("(superuser)")}'"
      }
      u.groups.foreach { g =>
        if !groupsInTenant.contains(g) then
          errs += s"user '${u.username}' references group '$g' not defined in tenant '${u.tenant.getOrElse("(superuser)")}'"
      }
    }

    m.roles.foreach { r =>
      if !knownTenants.contains(r.tenant) then
        errs += s"role '${r.name}': tenant '${r.tenant}' not in YAML or DB"
    }
    m.groups.foreach { g =>
      if !knownTenants.contains(g.tenant) then
        errs += s"group '${g.name}': tenant '${g.tenant}' not in YAML or DB"
      val rolesInTenant = m.roles.filter(_.tenant == g.tenant).map(_.name).toSet
      g.roles.foreach { rn =>
        if !rolesInTenant.contains(rn) then
          errs += s"group '${g.name}' references role '$rn' not defined in tenant '${g.tenant}'"
      }
    }

    // Pools reference their tenant-db by name (sibling check, no DB lookup needed)
    m.tenants.foreach { t =>
      val dbNames = t.tenantDbs.map(_.name).toSet
      t.pools.foreach { p =>
        if !dbNames.contains(p.tenantDb) then
          errs += s"tenant '${t.name}' pool '${p.name}': tenantDb '${p.tenantDb}' not declared under tenant"
      }
    }

    if errs.isEmpty then Right(()) else Left(errs.toList)

  // ------------------------------------------------------------------
  // apply
  // ------------------------------------------------------------------

  /** Apply a manifest against `store`. Validation runs first; on success
    * the per-resource replace pipeline executes. Returns `Right(())` on
    * success; `Left(errors)` on a validation or apply failure.
    *
    * Per-resource semantics: resources that appear in the YAML get
    * upserted; sibling rows that no longer appear under a parent that IS
    * in the YAML get deleted (delete-then-upsert). The importer never
    * drops a Postgres database -- only the `qodstate_*` registry rows
    * are removed.
    *
    * Passwords: a user with `password` set has it bcrypt-ed (or kept
    * verbatim if it already looks like a hash); a user with no
    * `password` field reuses the existing hash captured at the start
    * (snapshot); a brand-new user without a password is an error. */
  def apply(m: ConfigManifest, store: ControlPlaneStore): ValidationResult =
    validate(m, store).flatMap { _ =>
      val errs = scala.collection.mutable.ListBuffer.empty[String]

      // 1. Snapshot existing password hashes for every user mentioned in
      //    the manifest so the "no password field" path can carry them
      //    forward.
      val snapshot: Map[(Option[String], String), String] =
        m.users.flatMap { u =>
          store.getPasswordHash(u.tenant, u.username).map(h => (u.tenant, u.username) -> h)
        }.toMap

      // 2. Tenants + their nested collections (tenant-dbs, pools).
      m.tenants.foreach { mt =>
        val tenantId = tenantIdFor(store, mt.name).getOrElse {
          val newId = s"t-${UUID.randomUUID().toString.take(8)}"
          store.upsertTenant(Tenant(
            id           = newId,
            name         = mt.name,
            displayName  = mt.name,
            disabled     = mt.disabled,
            authProvider = mt.authProvider,
            authConfig   = mt.authConfig
          ))
          newId
        }

        // Existing tenant: refresh top-level fields.
        if tenantIdFor(store, mt.name).contains(tenantId) then
          store.upsertTenant(Tenant(
            id           = tenantId,
            name         = mt.name,
            displayName  = mt.name,
            disabled     = mt.disabled,
            authProvider = mt.authProvider,
            authConfig   = mt.authConfig
          ))

        // ---- TenantDbs: delete-then-upsert.
        val keepDbNames = mt.tenantDbs.map(_.name).toSet
        store.listTenantDbs(tenantId).foreach { d =>
          if !keepDbNames.contains(d.name) then store.deleteTenantDb(d.id)
        }
        mt.tenantDbs.foreach { mtd =>
          val existing = store.listTenantDbs(tenantId).find(_.name == mtd.name)
          val tdId     = existing.map(_.id).getOrElse(s"td-${UUID.randomUUID().toString.take(8)}")
          store.upsertTenantDb(TenantDb(
            id          = tdId,
            tenantId    = tenantId,
            name        = mtd.name,
            metastore   = mtd.metastore,
            dataPath    = mtd.metastore.getOrElse("dataPath", ""),
            objectStore = mtd.objectStore
          ))
        }

        // ---- Pools: delete-then-upsert, keyed by (tenant, pool name).
        val keepPoolNames = mt.pools.map(_.name).toSet
        val currentDbs    = store.listTenantDbs(tenantId)
        currentDbs.foreach { d =>
          store.listPools(d.id).foreach { p =>
            if !keepPoolNames.contains(p.name) then store.deletePool(p.id)
          }
        }
        mt.pools.foreach { mp =>
          val dbId = currentDbs.find(_.name == mp.tenantDb).map(_.id).getOrElse {
            // Validation already caught dangling tenantDb references, so
            // this branch is unreachable -- but bail loudly if it ever
            // fires rather than corrupting the row.
            errs += s"tenant '${mt.name}' pool '${mp.name}': tenant-db '${mp.tenantDb}' not found after upsert"
            ""
          }
          if dbId.nonEmpty then
            val existing = store.listPools(dbId).find(_.name == mp.name)
            val poolId   = existing.map(_.id).getOrElse(s"p-${UUID.randomUUID().toString.take(8)}")
            val dist     = RoleDistribution(
              writeonly = mp.roleDistribution.writeonly,
              readonly  = mp.roleDistribution.readonly,
              dual      = mp.roleDistribution.dual
            )
            store.upsertPool(Pool(
              id                   = poolId,
              tenantId             = tenantId,
              tenantDbId           = dbId,
              name                 = mp.name,
              size                 = dist.total,
              distribution         = dist,
              maxConcurrentPerNode = mp.maxConcurrentPerNode,
              disabled             = mp.disabled
            ))
        }
      }

      // 3. Roles + permissions (per-tenant). Roles whose tenant is in the
      //    YAML or pre-existing in the DB are valid; validation already
      //    rejected the rest.
      m.roles.foreach { mr =>
        tenantIdFor(store, mr.tenant) match
          case None =>
            errs += s"role '${mr.name}': tenant '${mr.tenant}' not found after tenant pass"
          case Some(tenantId) =>
            val existing = store.listRoles(tenantId).find(_.name == mr.name)
            val roleId   = existing.map(_.id).getOrElse(s"r-${UUID.randomUUID().toString.take(8)}")
            store.upsertRole(RbacRole(
              id          = roleId,
              tenantId    = tenantId,
              name        = mr.name,
              description = mr.description.filter(_.nonEmpty)
            ))
            // Replace permissions: delete every existing then re-insert.
            store.listRolePermissions(roleId).foreach(p => store.deleteRolePermission(p.id))
            mr.permissions.foreach { perm =>
              store.insertRolePermission(RolePermission(
                id          = s"rp-${UUID.randomUUID().toString.take(8)}",
                roleId      = roleId,
                catalogName = perm.catalog,
                schemaName  = perm.schema,
                tableName   = perm.table,
                verb        = perm.verb
              ))
            }
      }

      // 4. Groups + group-role memberships (per-tenant).
      m.groups.foreach { mg =>
        tenantIdFor(store, mg.tenant) match
          case None =>
            errs += s"group '${mg.name}': tenant '${mg.tenant}' not found after tenant pass"
          case Some(tenantId) =>
            val existing = store.listGroups(tenantId).find(_.name == mg.name)
            val groupId  = existing.map(_.id).getOrElse(s"g-${UUID.randomUUID().toString.take(8)}")
            store.upsertGroup(RbacGroup(
              id          = groupId,
              tenantId    = tenantId,
              name        = mg.name,
              description = mg.description.filter(_.nonEmpty)
            ))
            val tenantRoles = store.listRoles(tenantId)
            val keepRoleIds = mg.roles.flatMap(rn => tenantRoles.find(_.name == rn).map(_.id)).toSet
            store.listRolesForGroup(groupId).foreach { rid =>
              if !keepRoleIds.contains(rid) then store.removeGroupRole(groupId, rid)
            }
            keepRoleIds.foreach(rid => store.addGroupRole(groupId, rid))
      }

      // 5. Users (with password snapshot fallback).
      m.users.foreach { mu =>
        val hashOpt: Option[String] = mu.password.map(BcryptUtils.toHash).orElse {
          snapshot.get((mu.tenant, mu.username))
        }
        hashOpt match
          case None =>
            errs += s"users[]: '${mu.username}' has no password and no prior credential in the manager"
          case Some(hash) =>
            val userId = store.upsertUserWithHash(mu.tenant, mu.username, hash, mu.role)

            // Tenant-scoped lookups for the user's role / group / pool
            // grants. Superusers (tenant = None) skip the role/group
            // replace below because the manifest cannot reference any --
            // validation already enforced that.
            val tenantId: Option[String] = mu.tenant.flatMap(t => tenantIdFor(store, t))

            // --- User roles
            val tenantRoles = tenantId.map(store.listRoles).getOrElse(Nil)
            val keepUserRoles = mu.roles.flatMap { rn =>
              tenantRoles.find(_.name == rn).map(_.id)
            }.toSet
            store.listDirectRolesForUser(userId).foreach { rid =>
              if !keepUserRoles.contains(rid) then store.removeUserRole(userId, rid)
            }
            keepUserRoles.foreach(rid => store.addUserRole(userId, rid))

            // --- User groups
            val tenantGroups = tenantId.map(store.listGroups).getOrElse(Nil)
            val keepUserGroups = mu.groups.flatMap { gn =>
              tenantGroups.find(_.name == gn).map(_.id)
            }.toSet
            store.listGroupsForUser(userId).foreach { gid =>
              if !keepUserGroups.contains(gid) then store.removeUserGroup(userId, gid)
            }
            keepUserGroups.foreach(gid => store.addUserGroup(userId, gid))

            // --- Pool permissions: full replace.
            store.listPoolPermissionsForUser(userId).foreach { p =>
              store.deletePoolPermission(p.id)
            }
            mu.poolGrants.foreach { mpg =>
              val poolId: Option[String] = mpg.pool.flatMap { pn =>
                tenantId.flatMap { tid =>
                  store.listTenantDbs(tid).flatMap(d => store.listPools(d.id)).find(_.name == pn).map(_.id)
                }
              }
              store.insertPoolPermission(PoolPermission(
                id       = s"pp-${UUID.randomUUID().toString.take(8)}",
                tenantId = tenantId.getOrElse(""),
                poolId   = poolId,
                userId   = Some(userId),
                groupId  = None
              ))
            }
      }

      if errs.isEmpty then Right(()) else Left(errs.toList)
    }

  /** Resolve a tenant identifier from its YAML-facing name. Matches on
    * `displayName` first (the user-facing label persisted on
    * `qodstate_tenant.display_name`), then falls back to `Tenant.name`
    * for fixtures that only populate one of the two. */
  private def tenantIdFor(store: ControlPlaneStore, name: String): Option[String] =
    store.listTenants().find(t => t.displayName == name || t.name == name).map(_.id)