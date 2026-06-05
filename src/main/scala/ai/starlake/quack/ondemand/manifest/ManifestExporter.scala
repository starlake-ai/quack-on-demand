package ai.starlake.quack.ondemand.manifest

import ai.starlake.quack.ondemand.state.ControlPlaneStore

import java.time.Instant

/** Read-only snapshot of the qodstate_* control plane in YAML-manifest
  * shape. The output never carries user passwords (hashes stay in
  * Postgres) but does carry tenant authProvider clientSecrets verbatim
  * since those are operator-supplied and cannot be recovered if lost. */
object ManifestExporter:

  /** Build a [[ConfigManifest]] from the live control-plane store.
    * Named `build` rather than `export` because `export` is a reserved
    * keyword in Scala 3. */
  def build(
      store:          ControlPlaneStore,
      exportedAt:     Instant,
      managerVersion: String,
      hostname:       String
  ): ConfigManifest =

    val tenants = store.listTenants().sortBy(_.displayName).map { t =>
      // Build id -> name maps for dbs, pools, roles, groups in this tenant.
      val dbRows   = store.listTenantDbs(t.id).sortBy(_.name)
      val dbIdToName: Map[String, String] = dbRows.map(d => d.id -> d.name).toMap

      val poolRows = dbRows.flatMap(d => store.listPools(d.id)).sortBy(_.name)
      val poolIdToName: Map[String, String] = poolRows.map(p => p.id -> p.name).toMap

      val roleRows = store.listRoles(t.id).sortBy(_.name)
      val roleIdToName: Map[String, String] = roleRows.map(r => r.id -> r.name).toMap

      val groupRows = store.listGroups(t.id).sortBy(_.name)
      val groupIdToName: Map[String, String] = groupRows.map(g => g.id -> g.name).toMap

      val manifestDbs = dbRows.map { d =>
        ManifestTenantDb(name = d.name, metastore = d.metastore, objectStore = d.objectStore)
      }

      val manifestPools = poolRows.map { p =>
        val dbName = dbIdToName.getOrElse(p.tenantDbId, "")
        ManifestPool(
          name                 = p.name,
          tenantDb             = dbName,
          roleDistribution     = ManifestRoleDistribution(
            writeonly = p.distribution.writeonly,
            readonly  = p.distribution.readonly,
            dual      = p.distribution.dual
          ),
          maxConcurrentPerNode = p.maxConcurrentPerNode,
          disabled             = p.disabled
        )
      }

      val manifestRoles = roleRows.map { r =>
        ManifestRole(
          tenant      = t.displayName,
          name        = r.name,
          description = r.description.filter(_.nonEmpty),
          permissions = store.listRolePermissions(r.id).sortBy(p =>
            (p.catalogName, p.schemaName, p.tableName, p.verb)
          ).map { p =>
            ManifestTablePermission(
              catalog = p.catalogName,
              schema  = p.schemaName,
              table   = p.tableName,
              verb    = p.verb
            )
          }
        )
      }

      val manifestGroups = groupRows.map { g =>
        ManifestGroup(
          tenant      = t.displayName,
          name        = g.name,
          description = g.description.filter(_.nonEmpty),
          roles       = store.listRolesForGroup(g.id).flatMap(roleIdToName.get).sorted
        )
      }

      val tenantUsers = store.listUsers(Some(t.name)).sortBy(_.username).map { u =>
        val userRoleNames  = store.listDirectRolesForUser(u.id).flatMap(roleIdToName.get).sorted
        val userGroupNames = store.listGroupsForUser(u.id).flatMap(groupIdToName.get).sorted
        val userPoolGrants = store.listPoolPermissionsForUser(u.id).map { pp =>
          ManifestPoolGrant(pool = pp.poolId.flatMap(poolIdToName.get))
        }
        ManifestUser(
          tenant     = u.tenant,
          username   = u.username,
          password   = None,
          role       = u.role,
          enabled    = true,
          roles      = userRoleNames,
          groups     = userGroupNames,
          poolGrants = userPoolGrants
        )
      }

      (
        ManifestTenant(
          name         = t.displayName,
          disabled     = t.disabled,
          authProvider = t.authProvider,
          authConfig   = t.authConfig,
          tenantDbs    = manifestDbs,
          pools        = manifestPools,
          identities   = Nil
        ),
        manifestRoles,
        manifestGroups,
        tenantUsers
      )
    }

    // Superusers are listed with tenant=None; collect them separately.
    val superusers = store.listSuperusers().sortBy(_.username).map { u =>
      val userPoolGrants = store.listPoolPermissionsForUser(u.id).map { pp =>
        ManifestPoolGrant(pool = None) // superusers: pool id resolution requires tenant context
      }
      ManifestUser(
        tenant     = None,
        username   = u.username,
        password   = None,
        role       = u.role,
        enabled    = true,
        roles      = Nil,
        groups     = Nil,
        poolGrants = userPoolGrants
      )
    }

    ConfigManifest(
      apiVersion   = ConfigManifest.ApiVersion,
      kind         = ConfigManifest.Kind,
      exportedAt   = exportedAt,
      exportedFrom = ExportedFrom(managerVersion = managerVersion, hostname = hostname),
      tenants      = tenants.map(_._1),
      roles        = tenants.flatMap(_._2),
      groups       = tenants.flatMap(_._3),
      users        = superusers ++ tenants.flatMap(_._4)
    )