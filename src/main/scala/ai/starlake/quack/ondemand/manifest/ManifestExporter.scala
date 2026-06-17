package ai.starlake.quack.ondemand.manifest

import ai.starlake.quack.ondemand.state.{ControlPlaneStore, FederatedSourceStore}

import java.time.Instant

/** Read-only snapshot of the qodstate_* control plane in YAML-manifest shape. The output never
  * carries user passwords (hashes stay in Postgres) but does carry tenant authProvider
  * clientSecrets verbatim since those are operator-supplied and cannot be recovered if lost.
  */
object ManifestExporter:

  private val Redacted = "***REDACTED***"

  /** Build a [[ConfigManifest]] from the live control-plane store. Named `build` rather than
    * `export` because `export` is a reserved keyword in Scala 3.
    *
    * @param federatedStore
    *   when present, each tenant-db's federated sources and their secrets are included in the
    *   manifest. Secrets with an inline value are exported as "***REDACTED***"; external-ref
    *   secrets carry their ref verbatim. Pass None in file-mode or in tests that do not exercise
    *   federation.
    */
  def build(
      store: ControlPlaneStore,
      exportedAt: Instant,
      managerVersion: String,
      hostname: String,
      federatedStore: Option[FederatedSourceStore] = None
  ): ConfigManifest =

    // Pull the whole graph in one round-trip; the per-tenant loop below
    // walks the in-memory index maps instead of going back to the store.
    val snap              = store.snapshot()
    val dbsByTenant       = snap.tenantDbs.groupBy(_.tenantId)
    val poolsByDb         = snap.pools.groupBy(_.tenantDbId)
    val rolesByT          = snap.roles.groupBy(_.tenantId)
    val groupsByT         = snap.groups.groupBy(_.tenantId)
    val rolePermsByRole   = snap.rolePermissions.groupBy(_.roleId)
    val colPoliciesByRole = snap.columnPolicies.groupBy(_.roleId)
    val rowPoliciesByRole = snap.rowPolicies.groupBy(_.roleId)
    val rolesByGroup      = snap.groupRoles.groupBy(_.groupId).view.mapValues(_.map(_.roleId)).toMap
    val rolesByUser       = snap.userRoles.groupBy(_.userId).view.mapValues(_.map(_.roleId)).toMap
    val groupsByUser      = snap.userGroups.groupBy(_.userId).view.mapValues(_.map(_.groupId)).toMap
    val poolPermsByUser   =
      snap.poolPermissions
        .collect { case p if p.userId.isDefined => p.userId.get -> p }
        .groupBy(_._1)
        .view
        .mapValues(_.map(_._2))
        .toMap
    val usersByTenant = snap.users.groupBy(_.tenant)

    val tenants = snap.tenants.sortBy(_.displayName).map { t =>
      // Build id -> name maps for dbs, pools, roles, groups in this tenant.
      val dbRows                          = dbsByTenant.getOrElse(t.id, Nil).sortBy(_.name)
      val dbIdToName: Map[String, String] = dbRows.map(d => d.id -> d.name).toMap

      val poolRows =
        dbRows.flatMap(d => poolsByDb.getOrElse(d.id, Nil)).sortBy(_.name)
      val poolIdToName: Map[String, String] = poolRows.map(p => p.id -> p.name).toMap

      val roleRows                          = rolesByT.getOrElse(t.id, Nil).sortBy(_.name)
      val roleIdToName: Map[String, String] = roleRows.map(r => r.id -> r.name).toMap

      val groupRows                          = groupsByT.getOrElse(t.id, Nil).sortBy(_.name)
      val groupIdToName: Map[String, String] = groupRows.map(g => g.id -> g.name).toMap

      val manifestDbs = dbRows.map { d =>
        val fedSources: List[ManifestFederatedSource] = federatedStore.toList.flatMap { fs =>
          fs.listSources(d.id).map { src =>
            ManifestFederatedSource(
              alias = src.alias,
              setupSql = src.setupSql,
              description = src.description,
              disabled = src.disabled,
              secrets = fs.listSecrets(src.id).map { sec =>
                ManifestFederatedSecret(
                  name = sec.name,
                  value = sec.value.map(_ => Redacted),
                  externalRef = sec.externalRef
                )
              }
            )
          }
        }
        ManifestTenantDb(
          name = d.name,
          kind = d.kind.wireValue,
          metastore = d.metastore,
          dataPath = d.dataPath,
          objectStore = d.objectStore,
          defaultDatabase = d.defaultDatabase,
          defaultSchema = d.defaultSchema,
          federatedSources = fedSources
        )
      }

      val manifestPools = poolRows.map { p =>
        val dbName = dbIdToName.getOrElse(p.tenantDbId, "")
        ManifestPool(
          name = p.name,
          tenantDb = dbName,
          roleDistribution = ManifestRoleDistribution(
            writeonly = p.distribution.writeonly,
            readonly = p.distribution.readonly,
            dual = p.distribution.dual
          ),
          maxConcurrentPerNode = p.maxConcurrentPerNode,
          disabled = p.disabled,
          cohorts = p.cohorts.map { c =>
            ManifestPoolCohort(
              placement = ManifestNodePlacement(
                nodeSelector = c.placement.nodeSelector,
                tolerations = c.placement.tolerations.map { t =>
                  ManifestNodeToleration(t.key, t.operator, t.value, t.effect)
                }
              ),
              distribution = ManifestRoleDistribution(
                writeonly = c.distribution.writeonly,
                readonly = c.distribution.readonly,
                dual = c.distribution.dual
              )
            )
          },
          initSql = p.initSql
        )
      }

      val manifestRoles = roleRows.map { r =>
        ManifestRole(
          tenant = t.displayName,
          name = r.name,
          description = r.description.filter(_.nonEmpty),
          permissions = rolePermsByRole
            .getOrElse(r.id, Nil)
            .sortBy(p => (p.catalogName, p.schemaName, p.tableName, p.verb))
            .map { p =>
              ManifestTablePermission(
                catalog = p.catalogName,
                schema = p.schemaName,
                table = p.tableName,
                verb = p.verb
              )
            },
          columnPolicies = colPoliciesByRole
            .getOrElse(r.id, Nil)
            .sortBy(p => (p.catalogName, p.schemaName, p.tableName, p.columnName))
            .map { p =>
              ManifestRoleColumnPolicy(
                catalog = p.catalogName,
                schema = p.schemaName,
                table = p.tableName,
                column = p.columnName,
                action = p.action,
                transformSql = p.transformSql
              )
            },
          rowPolicies = rowPoliciesByRole
            .getOrElse(r.id, Nil)
            .sortBy(p => (p.catalogName, p.schemaName, p.tableName, p.predicateSql))
            .map { p =>
              ManifestRoleRowPolicy(
                catalog = p.catalogName,
                schema = p.schemaName,
                table = p.tableName,
                predicateSql = p.predicateSql
              )
            }
        )
      }

      val manifestGroups = groupRows.map { g =>
        ManifestGroup(
          tenant = t.displayName,
          name = g.name,
          description = g.description.filter(_.nonEmpty),
          roles = rolesByGroup.getOrElse(g.id, Nil).flatMap(roleIdToName.get).sorted
        )
      }

      // Users may be keyed by tenant id or tenant displayName depending on
      // how they were seeded; union both keys and dedup by user id.
      val tenantUsers = (
        usersByTenant.getOrElse(Some(t.id), Nil) ++
          usersByTenant.getOrElse(Some(t.name), Nil)
      ).distinctBy(_.id)
        .sortBy(_.username)
        .map { u =>
          val userRoleNames =
            rolesByUser.getOrElse(u.id, Nil).flatMap(roleIdToName.get).sorted
          val userGroupNames =
            groupsByUser.getOrElse(u.id, Nil).flatMap(groupIdToName.get).sorted
          val userPoolGrants = poolPermsByUser.getOrElse(u.id, Nil).map { pp =>
            ManifestPoolGrant(pool = pp.poolId.flatMap(poolIdToName.get))
          }
          ManifestUser(
            // Emit the tenant DISPLAY NAME -- manifests are human-facing and
            // keyed by name (matching role/group emission above), not the
            // stored surrogate id. Superusers (tenant = None) stay None.
            tenant = u.tenant.map(_ => t.displayName),
            username = u.username,
            password = None,
            role = u.role,
            enabled = true,
            roles = userRoleNames,
            groups = userGroupNames,
            poolGrants = userPoolGrants
          )
        }

      (
        ManifestTenant(
          name = t.displayName,
          disabled = t.disabled,
          authProvider = t.authProvider,
          authConfig = t.authConfig,
          tenantDbs = manifestDbs,
          pools = manifestPools
        ),
        manifestRoles,
        manifestGroups,
        tenantUsers
      )
    }

    // Superusers are listed with tenant=None; collect them separately.
    val superusers = snap.users.filter(_.tenant.isEmpty).sortBy(_.username).map { u =>
      val userPoolGrants = poolPermsByUser.getOrElse(u.id, Nil).map { _ =>
        ManifestPoolGrant(pool = None) // superusers: pool id resolution requires tenant context
      }
      ManifestUser(
        tenant = None,
        username = u.username,
        password = None,
        role = u.role,
        enabled = true,
        roles = Nil,
        groups = Nil,
        poolGrants = userPoolGrants
      )
    }

    ConfigManifest(
      apiVersion = ConfigManifest.ApiVersion,
      kind = ConfigManifest.Kind,
      exportedAt = exportedAt,
      exportedFrom = ExportedFrom(managerVersion = managerVersion, hostname = hostname),
      tenants = tenants.map(_._1),
      roles = tenants.flatMap(_._2),
      groups = tenants.flatMap(_._3),
      users = superusers ++ tenants.flatMap(_._4)
    )
