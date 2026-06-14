// src/main/scala/ai/starlake/quack/ondemand/manifest/ManifestImporter.scala
package ai.starlake.quack.ondemand.manifest

import ai.starlake.quack.model.{
  FederatedSecret,
  FederatedSource,
  Names,
  NodePlacement,
  NodeToleration,
  Pool,
  PoolCohort,
  RoleDistribution,
  Tenant,
  TenantDb,
  TenantDbKind
}
import ai.starlake.quack.ondemand.state.{
  ControlPlaneStore,
  FederatedSourceStore,
  PoolPermission,
  RbacGroup,
  RbacRole,
  RoleColumnPolicy,
  RolePermission
}

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
      xs.groupBy(key).collect {
        case (k, vs) if vs.size > 1 =>
          errs += s"duplicate $label: $k"
      }
    dup(m.tenants, _.name, "tenant name")
    dup(m.roles, r => (r.tenant, r.name), "role (tenant, name)")
    dup(m.groups, g => (g.tenant, g.name), "group (tenant, name)")
    dup(m.users, u => (u.tenant, u.username), "user (tenant, username)")

    // Per-tenant nested duplicates
    m.tenants.foreach { t =>
      dup(t.tenantDbs, _.name, s"tenant-db within ${t.name}")
      dup(t.pools, _.name, s"pool within ${t.name}")
    }

    // Cross-references
    m.users.foreach { u =>
      u.tenant.foreach { tn =>
        if !knownTenants.contains(tn) then
          errs += s"user '${u.username}': tenant '$tn' not in YAML or DB"
      }
      val rolesInTenant  = m.roles.filter(_.tenant == u.tenant.getOrElse("")).map(_.name).toSet
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
        // Per-cohort sums must match the pool's roleDistribution and total.
        if p.cohorts.nonEmpty then
          val sumW = p.cohorts.map(_.distribution.writeonly).sum
          val sumR = p.cohorts.map(_.distribution.readonly).sum
          val sumD = p.cohorts.map(_.distribution.dual).sum
          val rd   = p.roleDistribution
          if sumW != rd.writeonly || sumR != rd.readonly || sumD != rd.dual then
            errs += s"tenant '${t.name}' pool '${p.name}': cohort distributions " +
              s"sum to (wo=$sumW, ro=$sumR, dual=$sumD) but pool roleDistribution is " +
              s"(wo=${rd.writeonly}, ro=${rd.readonly}, dual=${rd.dual})"
      }
    }

    if errs.isEmpty then Right(()) else Left(errs.toList)

  // ------------------------------------------------------------------
  // apply
  // ------------------------------------------------------------------

  /** Apply a manifest against `store`. Validation runs first; on success the per-resource replace
    * pipeline executes. Returns `Right(())` on success; `Left(errors)` on a validation or apply
    * failure.
    *
    * Per-resource semantics: resources that appear in the YAML get upserted; sibling rows that no
    * longer appear under a parent that IS in the YAML get deleted (delete-then-upsert). The
    * importer never drops a Postgres database -- only the `qodstate_*` registry rows are removed.
    *
    * Passwords: a user with `password` set has it bcrypt-ed (or kept verbatim if it already looks
    * like a hash); a user with no `password` field reuses the existing hash captured at the start
    * (snapshot); a brand-new user without a password is an error.
    *
    * @param federatedStore
    *   when present, federated sources and secrets nested inside each tenant-db are upserted using
    *   the same replace-by-alias + reuse-on-redacted semantics as the dedicated federation YAML
    *   endpoint. Pass None in file-mode or tests that do not exercise federation.
    */
  def apply(
      m: ConfigManifest,
      store: ControlPlaneStore,
      federatedStore: Option[FederatedSourceStore] = None
  ): ValidationResult =
    validate(m, store).flatMap { _ =>
      val errs = scala.collection.mutable.ListBuffer.empty[String]

      // 0. Snapshot the existing graph ONCE so the per-tenant loops below
      //    don't re-query the store on every iteration. The maps are
      //    mutated in lock-step with every upsert so the rest of apply()
      //    sees the live state.
      val snap              = store.snapshot()
      val tenantDbsByTenant =
        snap.tenantDbs
          .groupBy(_.tenantId)
          .map { case (k, xs) =>
            k -> scala.collection.mutable.Map.from(xs.map(td => td.name -> td))
          }
          .to(scala.collection.mutable.Map)
      val poolsByDb =
        snap.pools
          .groupBy(_.tenantDbId)
          .map { case (k, xs) =>
            k -> scala.collection.mutable.Map.from(xs.map(p => p.name -> p))
          }
          .to(scala.collection.mutable.Map)
      val rolesByTenant =
        snap.roles
          .groupBy(_.tenantId)
          .map { case (k, xs) =>
            k -> scala.collection.mutable.Map.from(xs.map(r => r.name -> r))
          }
          .to(scala.collection.mutable.Map)
      val groupsByTenant =
        snap.groups
          .groupBy(_.tenantId)
          .map { case (k, xs) =>
            k -> scala.collection.mutable.Map.from(xs.map(g => g.name -> g))
          }
          .to(scala.collection.mutable.Map)

      def dbsOf(tid: String): collection.Map[String, TenantDb] =
        tenantDbsByTenant.getOrElse(tid, scala.collection.mutable.Map.empty)
      def poolsOf(dbId: String): collection.Map[String, Pool] =
        poolsByDb.getOrElse(dbId, scala.collection.mutable.Map.empty)
      def rolesOf(tid: String): collection.Map[String, RbacRole] =
        rolesByTenant.getOrElse(tid, scala.collection.mutable.Map.empty)
      def groupsOf(tid: String): collection.Map[String, RbacGroup] =
        groupsByTenant.getOrElse(tid, scala.collection.mutable.Map.empty)

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
          val newId = Names.newSurrogateId("t")
          store.upsertTenant(
            Tenant(
              id = newId,
              name = mt.name,
              displayName = mt.name,
              disabled = mt.disabled,
              authProvider = mt.authProvider,
              authConfig = mt.authConfig
            )
          )
          newId
        }

        // Existing tenant: refresh top-level fields.
        if tenantIdFor(store, mt.name).contains(tenantId) then
          store.upsertTenant(
            Tenant(
              id = tenantId,
              name = mt.name,
              displayName = mt.name,
              disabled = mt.disabled,
              authProvider = mt.authProvider,
              authConfig = mt.authConfig
            )
          )

        // ---- TenantDbs: delete-then-upsert.
        val keepDbNames = mt.tenantDbs.map(_.name).toSet
        val localDbs    = tenantDbsByTenant.getOrElseUpdate(
          tenantId,
          scala.collection.mutable.Map.empty
        )
        localDbs.values.toList.foreach { d =>
          if !keepDbNames.contains(d.name) then
            store.deleteTenantDb(d.id)
            localDbs.remove(d.name)
            poolsByDb.remove(d.id) // cascades on the DB side
        }
        mt.tenantDbs.foreach { mtd =>
          TenantDbKind.fromWire(mtd.kind) match
            case Left(err) =>
              errs += s"tenant '${mt.name}' tenant-db '${mtd.name}': $err"
            case Right(dbKind) =>
              val existing = localDbs.get(mtd.name)
              val tdId     = existing.map(_.id).getOrElse(Names.newSurrogateId("td"))
              val upserted = TenantDb(
                id = tdId,
                tenantId = tenantId,
                name = mtd.name,
                kind = dbKind,
                metastore = mtd.metastore,
                dataPath = mtd.dataPath,
                objectStore = mtd.objectStore,
                defaultDatabase = mtd.defaultDatabase,
                defaultSchema = mtd.defaultSchema
              )
              store.upsertTenantDb(upserted)
              localDbs.put(mtd.name, upserted)
              applyFederatedSources(federatedStore, mtd, tdId, errs)
        }

        // ---- Pools: delete-then-upsert, keyed by (tenant, pool name).
        val keepPoolNames = mt.pools.map(_.name).toSet
        localDbs.values.foreach { d =>
          val localPools = poolsByDb.getOrElseUpdate(d.id, scala.collection.mutable.Map.empty)
          localPools.values.toList.foreach { p =>
            if !keepPoolNames.contains(p.name) then
              store.deletePool(p.id)
              localPools.remove(p.name)
          }
        }
        mt.pools.foreach { mp =>
          val dbId = localDbs.get(mp.tenantDb).map(_.id).getOrElse {
            // Validation already caught dangling tenantDb references, so
            // this branch is unreachable -- but bail loudly if it ever
            // fires rather than corrupting the row.
            errs += s"tenant '${mt.name}' pool '${mp.name}': tenant-db '${mp.tenantDb}' not found after upsert"
            ""
          }
          if dbId.nonEmpty then
            val localPools = poolsByDb.getOrElseUpdate(dbId, scala.collection.mutable.Map.empty)
            val existing   = localPools.get(mp.name)
            val poolId     = existing.map(_.id).getOrElse(Names.newSurrogateId("p"))
            val dist       = RoleDistribution(
              writeonly = mp.roleDistribution.writeonly,
              readonly = mp.roleDistribution.readonly,
              dual = mp.roleDistribution.dual
            )
            val cohorts = mp.cohorts.map { mc =>
              PoolCohort(
                placement = NodePlacement(
                  nodeSelector = mc.placement.nodeSelector,
                  tolerations = mc.placement.tolerations.map(t =>
                    NodeToleration(t.key, t.operator, t.value, t.effect)
                  )
                ),
                distribution = RoleDistribution(
                  writeonly = mc.distribution.writeonly,
                  readonly = mc.distribution.readonly,
                  dual = mc.distribution.dual
                )
              )
            }
            val upserted = Pool(
              id = poolId,
              tenantId = tenantId,
              tenantDbId = dbId,
              name = mp.name,
              size = dist.total,
              distribution = dist,
              maxConcurrentPerNode = mp.maxConcurrentPerNode,
              disabled = mp.disabled,
              cohorts = cohorts,
              initSql = mp.initSql
            )
            store.upsertPool(upserted)
            localPools.put(mp.name, upserted)
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
            val localRoles = rolesByTenant.getOrElseUpdate(
              tenantId,
              scala.collection.mutable.Map.empty
            )
            val existing = localRoles.get(mr.name)
            val roleId   = existing.map(_.id).getOrElse(Names.newSurrogateId("r"))
            val upserted = RbacRole(
              id = roleId,
              tenantId = tenantId,
              name = mr.name,
              description = mr.description.filter(_.nonEmpty)
            )
            store.upsertRole(upserted)
            localRoles.put(mr.name, upserted)
            // Replace permissions: delete every existing then re-insert.
            store.listRolePermissions(roleId).foreach(p => store.deleteRolePermission(p.id))
            mr.permissions.foreach { perm =>
              store.insertRolePermission(
                RolePermission(
                  id = Names.newSurrogateId("rp"),
                  roleId = roleId,
                  catalogName = perm.catalog,
                  schemaName = perm.schema,
                  tableName = perm.table,
                  verb = perm.verb
                )
              )
            }
            // Replace column policies: delete every existing then re-insert.
            store.listColumnPolicies(roleId).foreach(p => store.deleteColumnPolicy(p.id))
            mr.columnPolicies.foreach { mcp =>
              store.insertColumnPolicy(
                RoleColumnPolicy(
                  id = Names.newSurrogateId("cp"),
                  roleId = roleId,
                  catalogName = mcp.catalog,
                  schemaName = mcp.schema,
                  tableName = mcp.table,
                  columnName = mcp.column,
                  action = mcp.action,
                  transformSql = mcp.transformSql
                )
              )
            }
      }

      // 4. Groups + group-role memberships (per-tenant).
      m.groups.foreach { mg =>
        tenantIdFor(store, mg.tenant) match
          case None =>
            errs += s"group '${mg.name}': tenant '${mg.tenant}' not found after tenant pass"
          case Some(tenantId) =>
            val localGroups = groupsByTenant.getOrElseUpdate(
              tenantId,
              scala.collection.mutable.Map.empty
            )
            val existing = localGroups.get(mg.name)
            val groupId  = existing.map(_.id).getOrElse(Names.newSurrogateId("g"))
            val upserted = RbacGroup(
              id = groupId,
              tenantId = tenantId,
              name = mg.name,
              description = mg.description.filter(_.nonEmpty)
            )
            store.upsertGroup(upserted)
            localGroups.put(mg.name, upserted)
            val tenantRoles = rolesOf(tenantId)
            val keepRoleIds =
              mg.roles.flatMap(rn => tenantRoles.get(rn).map(_.id)).toSet
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
            val tenantRoles   = tenantId.map(rolesOf).getOrElse(collection.Map.empty)
            val keepUserRoles = mu.roles.flatMap { rn =>
              tenantRoles.get(rn).map(_.id)
            }.toSet
            store.listDirectRolesForUser(userId).foreach { rid =>
              if !keepUserRoles.contains(rid) then store.removeUserRole(userId, rid)
            }
            keepUserRoles.foreach(rid => store.addUserRole(userId, rid))

            // --- User groups
            val tenantGroups   = tenantId.map(groupsOf).getOrElse(collection.Map.empty)
            val keepUserGroups = mu.groups.flatMap { gn =>
              tenantGroups.get(gn).map(_.id)
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
                  // Walk this tenant's tenant-dbs from the local map and
                  // search each db's pool map by name -- no store call.
                  dbsOf(tid).values.iterator
                    .flatMap(d => poolsOf(d.id).get(pn))
                    .nextOption()
                    .map(_.id)
                }
              }
              store.insertPoolPermission(
                PoolPermission(
                  id = Names.newSurrogateId("pp"),
                  tenantId = tenantId.getOrElse(""),
                  poolId = poolId,
                  userId = Some(userId),
                  groupId = None
                )
              )
            }
      }

      if errs.isEmpty then Right(()) else Left(errs.toList)
    }

  /** Resolve a tenant identifier from its YAML-facing name. Matches on `displayName` first (the
    * user-facing label persisted on `qodstate_tenant.display_name`), then falls back to
    * `Tenant.name` for fixtures that only populate one of the two.
    */
  private def tenantIdFor(store: ControlPlaneStore, name: String): Option[String] =
    store.listTenants().find(t => t.displayName == name || t.name == name).map(_.id)

  /** Apply federated sources from a manifest tenant-db into the federated source store.
    * Replace-by-alias semantics: sources not present in the manifest are deleted; each present
    * source is upserted. Secrets follow the same delete-then-upsert pattern with reuse of existing
    * values when the manifest carries "***REDACTED***".
    */
  private def applyFederatedSources(
      federatedStore: Option[FederatedSourceStore],
      mtd: ManifestTenantDb,
      tdId: String,
      errs: scala.collection.mutable.ListBuffer[String]
  ): Unit =
    if federatedStore.isDefined && mtd.federatedSources.nonEmpty then
      val fs = federatedStore.get

      // Reject duplicate aliases in payload.
      mtd.federatedSources.groupBy(_.alias).foreach { case (a, vs) =>
        if vs.size > 1 then errs += s"tenant-db '${mtd.name}': duplicate alias '$a' in payload"
      }

      // Load existing for value-reuse keyed by (alias, secretName).
      val existing: Map[(String, String), FederatedSecret] =
        fs.listSources(tdId)
          .flatMap { src =>
            fs.listSecrets(src.id).map(sec => (src.alias, sec.name) -> sec)
          }
          .toMap

      // Delete sources not in the incoming payload.
      val incomingAliases = mtd.federatedSources.map(_.alias).toSet
      fs.listSources(tdId)
        .filterNot(s => incomingAliases.contains(s.alias))
        .foreach(s => fs.deleteSource(s.id))

      // Upsert each source and its secrets.
      mtd.federatedSources.foreach { msrc =>
        val srcId = existing
          .collectFirst {
            case ((alias, _), sec) if alias == msrc.alias => sec.federatedSourceId
          }
          .getOrElse(Names.newSurrogateId("fs"))

        fs.upsertSource(
          FederatedSource(
            id = srcId,
            tenantDbId = tdId,
            alias = msrc.alias,
            setupSql = msrc.setupSql,
            description = msrc.description,
            disabled = msrc.disabled
          )
        )

        val incomingSecretNames = msrc.secrets.map(_.name).toSet
        fs.listSecrets(srcId)
          .filterNot(s => incomingSecretNames.contains(s.name))
          .foreach(s => fs.deleteSecret(srcId, s.name))

        msrc.secrets.foreach { msec =>
          val resolved: (Option[String], Option[String]) =
            (msec.value, msec.externalRef) match
              case (Some("***REDACTED***"), None) | (None, None) =>
                existing.get((msrc.alias, msec.name)) match
                  case Some(old) => (old.value, old.externalRef)
                  case None      =>
                    errs += s"tenant-db '${mtd.name}' source '${msrc.alias}' secret '${msec.name}': " +
                      "no existing value to reuse; provide value or externalRef"
                    (None, None)
              case (v, ref) => (v, ref)

          if resolved._1.isDefined || resolved._2.isDefined then
            val secId = existing
              .get((msrc.alias, msec.name))
              .map(_.id)
              .getOrElse(Names.newSurrogateId("fsec"))
            fs.upsertSecret(
              FederatedSecret(
                id = secId,
                federatedSourceId = srcId,
                name = msec.name,
                value = resolved._1,
                externalRef = resolved._2
              )
            )
        }
      }
