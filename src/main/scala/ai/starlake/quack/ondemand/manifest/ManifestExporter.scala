package ai.starlake.quack.ondemand.manifest

import ai.starlake.quack.model.{FederatedSecret, LockdownTriState, TenantDb}
import ai.starlake.quack.ondemand.state.{
  BuiltinRbac,
  ControlPlaneSnapshot,
  ControlPlaneStore,
  FederatedSourceStore
}
import com.typesafe.scalalogging.LazyLogging

import java.time.Instant

/** Read-only snapshot of the qodstate_* control plane in YAML-manifest shape. The output never
  * carries a plaintext user password (`ManifestUser.password` stays `None` -- plaintext is never
  * available once a user is created, only the bcrypt hash is stored) but DOES carry the real bcrypt
  * hash in `ManifestUser.passwordHash` so a round-trip preserves the SAME credential. A tenant's
  * `authConfig` is carried verbatim, its `clientSecretRef` included (the name of a secret, not its
  * value); a tenant's stored client secret is exported as `FederatedSecret.RedactedMarker`, like
  * `opaToken`, never in the clear.
  */
object ManifestExporter extends LazyLogging:

  private val Redacted = FederatedSecret.RedactedMarker

  /** Export refused: user-made roles/groups carry the reserved `qod_` prefix (case-insensitive),
    * e.g. a backfill-renamed `qod_all_tables_renamed` or a legacy `qod_readers`. The importer
    * refuses such names, so emitting them would produce a manifest that can never be replayed.
    * `rows` are `<tenant>/<role|group>/<name>`, sorted. Built-in rows are not offenders: they are
    * silently omitted from every export.
    */
  final case class ReservedNameRows(rows: List[String]):
    def message: String =
      s"export refused: ${rows.size} role(s)/group(s) use the reserved qod_ prefix, which a " +
        s"manifest import refuses: ${rows.mkString(", ")}. Rename them first, then export again."

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
  ): Either[ReservedNameRows, ConfigManifest] =

    // Pull the whole graph in one round-trip; the per-tenant loop below
    // walks the in-memory index maps instead of going back to the store.
    val snap = store.snapshot()

    // Refuse the whole export (no partial manifest) when a non-built-in row holds a reserved name.
    val offenders =
      (snap.roles.collect {
        case r if !r.builtin && BuiltinRbac.isReserved(r.name) => s"${r.tenantId}/role/${r.name}"
      } ++ snap.groups.collect {
        case g if !g.builtin && BuiltinRbac.isReserved(g.name) => s"${g.tenantId}/group/${g.name}"
      }).sorted
    if offenders.nonEmpty then Left(ReservedNameRows(offenders))
    else Right(buildManifest(store, snap, exportedAt, managerVersion, hostname, federatedStore))

  private def buildManifest(
      store: ControlPlaneStore,
      snap: ControlPlaneSnapshot,
      exportedAt: Instant,
      managerVersion: String,
      hostname: String,
      federatedStore: Option[FederatedSourceStore]
  ): ConfigManifest =
    val dbsByTenant = snap.tenantDbs.groupBy(_.tenantId)
    // Authorized locations are not part of the snapshot.
    val locationsByTenant = store.listAllTenantLocations().groupBy(_.tenantId)
    val poolsByDb         = snap.pools.groupBy(_.tenantDbId)
    // Global pool -> (owning tenant addressing-name, pool name) index, used to
    // resolve pool grants regardless of which tenant loop iteration is
    // running. Pool carries its own tenantId directly (denormalized from its
    // tenant-db), so this does not need to go through tenantDbId. The tenant
    // id IS the manifest addressing name (see the comment at `name = t.id`
    // below), so `p.tenantId` needs no further lookup.
    val poolTenantAndNameByPoolId = snap.pools.map(p => p.id -> (p.tenantId, p.name)).toMap
    val rolesByT                  = snap.roles.groupBy(_.tenantId)
    val groupsByT                 = snap.groups.groupBy(_.tenantId)
    val rolePermsByRole           = snap.rolePermissions.groupBy(_.roleId)
    val colPoliciesByRole         = snap.columnPolicies.groupBy(_.roleId)
    val rowPoliciesByRole         = snap.rowPolicies.groupBy(_.roleId)
    val rolesByGroup    = snap.groupRoles.groupBy(_.groupId).view.mapValues(_.map(_.roleId)).toMap
    val rolesByUser     = snap.userRoles.groupBy(_.userId).view.mapValues(_.map(_.roleId)).toMap
    val groupsByUser    = snap.userGroups.groupBy(_.userId).view.mapValues(_.map(_.groupId)).toMap
    val poolPermsByUser =
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
              // The typed triple. Dropping any of it turned an exported iceberg_rest source back
              // into a `sql` source with an empty setupSql on re-import (the catalog silently
              // vanished at node spawn); dropping `readOnly` additionally re-attached the catalog
              // WRITABLE, since that flag is what puts READ_ONLY on the rendered ATTACH.
              sourceType = src.sourceType.wire,
              config = src.config,
              readOnly = src.readOnly,
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
          // `encryptionKey` exports redacted like every other SecretKeys member, which means a
          // redacted manifest can never recreate an encrypted duckdb-file database: without the
          // original key, the imported row cannot be made to open the same encrypted file on
          // disk. That is deliberate, not a gap to close -- the key must not leave the control
          // plane in a backup/replay artifact. (README.md may document this for operators; this
          // comment does not depend on that.) Unlike `pgPassword` (also a SecretKeys member but
          // carried verbatim here so a manifest replay can still connect), this key is minted or
          // caller-supplied per database and has no legitimate reason to travel with a backup.
          // Removed in every spelling, silently: a row stored before the API compared names
          // exactly may hold `EncryptionKey`, which its nodes use as the key
          // (`NodeMetastore.secrets`), and an export never carries an encryption key, whatever
          // its spelling. No line is logged for one: the API offers no way to change an
          // encryption key, so the line would have no remedy.
          metastore = knownMetastore(
            t.id,
            d.name,
            d.metastore.filterNot { case (k, _) => k.equalsIgnoreCase(TenantDb.EncryptionKeyName) }
          ),
          dataPath = d.dataPath,
          objectStore = d.objectStore,
          defaultDatabase = d.defaultDatabase,
          defaultSchema = d.defaultSchema,
          initSql = d.initSql,
          federatedSources = fedSources,
          encrypted = d.encrypted
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
          suspended = p.suspended,
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
          initSql = p.initSql,
          cpu = p.cpu,
          memory = p.memory,
          podTemplateYaml = p.podTemplateYaml,
          lockdown = LockdownTriState.render(p.lockdown),
          minNodes = p.minNodes,
          maxNodes = p.maxNodes
        )
      }

      // Built-ins are omitted: every tenant gets them from the importer, and the id -> name maps
      // above span ALL rows so user and group references to them still export by name.
      val manifestRoles = roleRows.filterNot(_.builtin).map { r =>
        ManifestRole(
          tenant = t.id,
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

      val manifestGroups = groupRows.filterNot(_.builtin).map { g =>
        ManifestGroup(
          tenant = t.id,
          name = g.name,
          description = g.description.filter(_.nonEmpty),
          roles = rolesByGroup.getOrElse(g.id, Nil).flatMap(roleIdToName.get).sorted
        )
      }

      // qodstate_user.tenant carries the tenant id (the slug IS the id since
      // the slug-id refactor), so a single id lookup covers every user.
      val tenantUsers = usersByTenant
        .getOrElse(Some(t.id), Nil)
        .sortBy(_.username)
        .map { u =>
          val userRoleNames =
            rolesByUser.getOrElse(u.id, Nil).flatMap(roleIdToName.get).sorted
          val userGroupNames =
            groupsByUser.getOrElse(u.id, Nil).flatMap(groupIdToName.get).sorted
          val userPoolGrants = poolPermsByUser
            .getOrElse(u.id, Nil)
            .map(pp => poolGrantFor(pp, ownerTenantAddr = Some(t.id), poolTenantAndNameByPoolId))
          ManifestUser(
            // Emit the tenant DISPLAY NAME -- manifests are human-facing and
            // keyed by name (matching role/group emission above), not the
            // stored surrogate id. Superusers (tenant = None) stay None.
            tenant = u.tenant.map(_ => t.id),
            username = u.username,
            password = None,
            // Real bcrypt hash: plaintext is never available post-creation (see
            // ManifestUser.passwordHash doc comment), so this is the only way a
            // round-trip can carry the SAME credential forward.
            passwordHash = store.getPasswordHash(u.tenant, u.username),
            kind = u.kind,
            enabled = u.enabled,
            mustChangePassword = u.mustChangePassword,
            email = u.email,
            roles = userRoleNames,
            groups = userGroupNames,
            poolGrants = userPoolGrants
          )
        }

      (
        ManifestTenant(
          name = t.id,
          displayName = t.displayName,
          disabled = t.disabled,
          authProvider = t.authProvider,
          authConfig = t.authConfig,
          // Never the value: the marker only says a secret is stored (see ManifestTenant).
          clientSecret = t.authClientSecret.map(_ => Redacted),
          tenantDbs = manifestDbs,
          pools = manifestPools,
          acl = Option.when(t.acl != ai.starlake.quack.model.TenantAcl())(
            ManifestTenantAcl(
              mode = t.acl.mode,
              opaUrl = t.acl.opaUrl,
              opaPolicyPath = t.acl.opaPolicyPath,
              opaToken = t.acl.opaToken.map(_ => Redacted),
              sendStatementText = t.acl.sendStatementText
            )
          ),
          // Never a secret value: the marker only says one is stored (see ManifestTenantLocation).
          locations = locationsByTenant
            .get(t.id)
            .map(_.sortBy(_.location).map { l =>
              ManifestTenantLocation(
                l.location,
                ai.starlake.quack.model.TenantLocation.redacted(l.objectStore)
              )
            })
        ),
        manifestRoles,
        manifestGroups,
        tenantUsers
      )
    }

    // Superusers are listed with tenant=None; collect them separately. A
    // superuser has no home tenant, so every pool grant is potentially
    // cross-tenant -- poolGrantFor always emits the `tenant` qualifier here
    // (ownerTenantAddr = None) so the grant resolves back to the exact same
    // (tenant, pool) pair on import instead of collapsing to a useless
    // tenant-less `pool = None`.
    val superusers = snap.users.filter(_.tenant.isEmpty).sortBy(_.username).map { u =>
      val userPoolGrants = poolPermsByUser
        .getOrElse(u.id, Nil)
        .map(pp => poolGrantFor(pp, ownerTenantAddr = None, poolTenantAndNameByPoolId))
      ManifestUser(
        tenant = None,
        username = u.username,
        password = None,
        passwordHash = store.getPasswordHash(None, u.username),
        kind = u.kind,
        enabled = u.enabled,
        mustChangePassword = u.mustChangePassword,
        email = u.email,
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

  /** `metastore` (of `tenant`'s `database`) without its keys outside
    * [[TenantDb.KnownMetastoreKeys]], which a manifest import refuses as `database/create` does:
    * one of them would make the whole manifest unimportable. A row holds one when it was stored
    * before the API refused them (the admin UI once suggested `applicationName`) or compared their
    * names exactly (`PgPassword`); the row itself keeps it. Each key left out is logged by name,
    * never by value, at ERROR (the default root level, logback.xml, drops a WARN), with how to fix
    * the row: every export repeats the line until then.
    */
  private def knownMetastore(
      tenant: String,
      database: String,
      metastore: Map[String, String]
  ): Map[String, String] =
    val (known, other) =
      metastore.partition { case (k, _) => TenantDb.KnownMetastoreKeys.contains(k) }
    other.keys.toList.sorted.foreach { k =>
      logger.error(
        s"manifest export: tenant '$tenant' database '$database': left out metastore key " +
          s"'${TenantDb.printableKey(k)}', which a manifest import refuses " +
          s"(${TenantDb.MetastoreHoldsOnly}). ${leftOutAdvice(k)}"
      )
    }
    known

  /** What the line for a left-out `key` advises. A password stored under another spelling
    * (`PgPassword`) is no leftover: the database's nodes use it as the catalog password
    * (`NodeMetastore.secrets` matches any spelling), so removing it would cut them off, and storing
    * it under its own name replaces every spelling (`PoolSupervisor.mergeSecretKeys`). Any other
    * key is removed ([[TenantDb.metastoreKeyRemoval]], the advice a node's dropped-key line gives).
    */
  private def leftOutAdvice(key: String): String =
    if key.equalsIgnoreCase("pgPassword") then
      "The database's nodes use it as the catalog password, so a replay of this export runs " +
        "without it. Fix the row: send the database's full metastore through database/update " +
        "with 'pgPassword' set to the password (that replaces every spelling), then export again."
    else TenantDb.metastoreKeyRemoval(key)

  /** Resolve a single [[ai.starlake.quack.ondemand.state.PoolPermission]] into a
    * [[ManifestPoolGrant]]. `pool = None` when the permission has no pool id (a tenant-wide
    * wildcard grant) or the pool id no longer resolves (dangling FK, should not happen). The
    * `tenant` qualifier is only emitted when the pool's owning tenant differs from
    * `ownerTenantAddr` -- the common in-tenant case stays qualifier-free so existing manifests
    * round-trip byte-for-byte; a cross-tenant grant (only possible for a superuser, whose
    * `ownerTenantAddr` is None) always gets the qualifier so import can resolve back to the exact
    * same (tenant, pool) pair instead of guessing.
    */
  private def poolGrantFor(
      pp: ai.starlake.quack.ondemand.state.PoolPermission,
      ownerTenantAddr: Option[String],
      poolTenantAndNameByPoolId: Map[String, (String, String)]
  ): ManifestPoolGrant =
    pp.poolId.flatMap(poolTenantAndNameByPoolId.get) match
      case None => ManifestPoolGrant(pool = None)
      case Some((poolTenantAddr, poolName)) if ownerTenantAddr.contains(poolTenantAddr) =>
        ManifestPoolGrant(pool = Some(poolName))
      case Some((poolTenantAddr, poolName)) =>
        ManifestPoolGrant(pool = Some(poolName), tenant = Some(poolTenantAddr))
