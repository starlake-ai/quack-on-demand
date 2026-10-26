package ai.starlake.quack.ondemand.state

import java.util.Locale
import ai.starlake.quack.model.{
  Branch,
  BranchMerge,
  BranchMergeStatus,
  BranchStatus,
  MaintenancePolicy,
  MaintenanceRun,
  NodePlacement,
  NodeToleration,
  Pool,
  PoolCohort,
  PoolKey,
  Role,
  RoleDistribution,
  RunCounters,
  RunningNode,
  SnapshotTag,
  Tenant,
  TenantAcl,
  TenantDb,
  TenantDbKind
}
import com.typesafe.scalalogging.LazyLogging
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import io.circe.parser.parse
import io.circe.syntax._
import io.circe.Json

import java.sql.{Connection, PreparedStatement, ResultSet, Timestamp, Types}
import java.time.Instant
import scala.collection.mutable.ListBuffer

/** Postgres-backed [[ControlPlaneStore]] for the normalized schema produced by changelog
  * `0001-normalized-schema.yaml`. Assumes the schema is already in place -- the caller (typically
  * `Main`) runs [[LiquibaseRunner]] first.
  *
  * Connections come from a HikariCP pool (size [[poolSize]], default 10). Before this lived behind
  * a per-call `DriverManager.getConnection` which paid a fresh TCP + TLS + auth handshake on every
  * read/write -- 5 connections per FlightSQL handshake, 4 per `/user/list`. The pool drops that to
  * borrow-from-idle. Call [[close]] on shutdown to release the pool's idle connections.
  */
final class PostgresControlPlaneStore(
    jdbcUrl: String,
    user: String,
    password: String,
    poolSize: Int = 20
) extends ControlPlaneStore
    with FleetServerStore
    with LazyLogging:

  Class.forName("org.postgresql.Driver")

  private val dataSource: HikariDataSource =
    val hc = new HikariConfig()
    hc.setJdbcUrl(jdbcUrl)
    hc.setUsername(user)
    hc.setPassword(password)
    hc.setMaximumPoolSize(poolSize)
    hc.setMinimumIdle(math.min(2, poolSize))
    hc.setConnectionTimeout(5000)
    hc.setPoolName("qod-control-plane")
    new HikariDataSource(hc)

  /** SPI access for module-owned qodhosted_* tables. Modules share the control-plane pool rather
    * than opening their own.
    */
  def jdbcDataSource: javax.sql.DataSource = dataSource

  private def withConn[A](f: Connection => A): A =
    val c = dataSource.getConnection
    try f(c)
    finally c.close()

  /** Release the pool's idle connections. Idempotent. Should be called from the manager shutdown
    * hook before JVM exit; outstanding `withConn` callers fail with the standard HikariCP
    * `pool has been shut down` error.
    */
  override def close(): Unit = if !dataSource.isClosed then dataSource.close()

  // ---------------- Tenant ----------------

  /** Every tenant read selects these columns (the ones [[readTenant]] maps), so a new tenant column
    * is added in one place.
    */
  private val SelectTenants: String =
    "SELECT id, display_name, disabled, auth_provider, auth_config, " +
      "acl_mode, opa_url, opa_policy_path, opa_token, opa_send_statement_text, " +
      "auth_client_secret FROM qodstate_tenant ORDER BY display_name"

  def upsertTenant(t: Tenant): Unit = withConn { c =>
    val ps = c.prepareStatement(
      """INSERT INTO qodstate_tenant
        |  (id, display_name, disabled, auth_provider, auth_config,
        |   acl_mode, opa_url, opa_policy_path, opa_token, opa_send_statement_text,
        |   auth_client_secret)
        |VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
        |ON CONFLICT (id) DO UPDATE SET
        |  display_name            = EXCLUDED.display_name,
        |  disabled                = EXCLUDED.disabled,
        |  auth_provider           = EXCLUDED.auth_provider,
        |  auth_config             = EXCLUDED.auth_config,
        |  auth_client_secret      = EXCLUDED.auth_client_secret""".stripMargin
    )
    // The acl columns are bound for the INSERT arm only: the conflict arm deliberately leaves
    // them alone (see ControlPlaneStore.upsertTenant), so a stale full row cannot revert them.
    // auth_client_secret is the opposite: written in both arms, with auth_config, because the
    // secret is bound to the issuer it was saved with and never changes on its own.
    try
      ps.setString(1, t.id)
      ps.setString(2, if t.displayName.nonEmpty then t.displayName else t.id)
      ps.setBoolean(3, t.disabled)
      ps.setString(4, t.authProvider)
      ps.setString(5, mapToJson(t.authConfig))
      bindAcl(ps, 6, t.acl)
      ps.setString(11, t.authClientSecret.orNull)
      ps.executeUpdate()
    finally ps.close()
  }

  def updateTenantAcl(tenantId: String, acl: TenantAcl): Unit = withConn { c =>
    val ps = c.prepareStatement(
      """UPDATE qodstate_tenant SET
        |  acl_mode = ?, opa_url = ?, opa_policy_path = ?, opa_token = ?,
        |  opa_send_statement_text = ?
        |WHERE id = ?""".stripMargin
    )
    try
      bindAcl(ps, 1, acl)
      ps.setString(6, tenantId)
      ps.executeUpdate()
    finally ps.close()
  }

  private def bindAcl(ps: PreparedStatement, from: Int, acl: TenantAcl): Unit =
    ps.setString(from, acl.mode.orNull)
    ps.setString(from + 1, acl.opaUrl.orNull)
    ps.setString(from + 2, acl.opaPolicyPath.orNull)
    ps.setString(from + 3, acl.opaToken.orNull)
    ps.setBoolean(from + 4, acl.sendStatementText)

  def listTenants(): List[Tenant] = withConn { c =>
    val rs = c.createStatement().executeQuery(SelectTenants)
    try drain(rs)(readTenant)
    finally rs.close()
  }

  def policyVersions(): Map[String, Long] = withConn { c =>
    val rs = c.createStatement().executeQuery("SELECT id, policy_version FROM qodstate_tenant")
    try drain(rs)(r => r.getString(1) -> r.getLong(2)).toMap
    finally rs.close()
  }

  def deleteTenant(id: String): Unit = withConn(c => deleteById(c, "qodstate_tenant", "id", id))

  def createTenantWithBuiltins(tenant: Tenant, builtins: BuiltinRbac.Rows): Unit = withConn { c =>
    // Single connection + manual commit so the tenant row and every built-in row either all land
    // or roll back together. INSERT (not upsert) because this is a bootstrap path -- a duplicate
    // id is a programmer error.
    c.setAutoCommit(false)
    try
      val tps = c.prepareStatement(
        """INSERT INTO qodstate_tenant
          |  (id, display_name, disabled, auth_provider, auth_config,
          |   acl_mode, opa_url, opa_policy_path, opa_token, opa_send_statement_text,
          |   auth_client_secret)
          |VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)""".stripMargin
      )
      try
        tps.setString(1, tenant.id)
        tps.setString(2, if tenant.displayName.nonEmpty then tenant.displayName else tenant.id)
        tps.setBoolean(3, tenant.disabled)
        tps.setString(4, tenant.authProvider)
        tps.setString(5, mapToJson(tenant.authConfig))
        bindAcl(tps, 6, tenant.acl)
        tps.setString(11, tenant.authClientSecret.orNull)
        tps.executeUpdate()
      finally tps.close()

      builtins.roles.foreach(r => insertRoleRow(c, r))
      builtins.groups.foreach(g => insertGroupRow(c, g))
      builtins.permissions.foreach(p => insertRolePermissionRow(c, p))
      builtins.poolGrants.foreach(p => insertPoolPermissionRow(c, p))
      c.commit()
    catch
      case t: Throwable =>
        try c.rollback()
        catch case _: Throwable => ()
        throw t
    finally c.setAutoCommit(true)
  }

  def ensureBuiltins(tenantId: String): BuiltinRbac.Rows =
    var changed = false
    withConn { c =>
      // Every write is either guarded by `NOT builtin` or ON CONFLICT DO NOTHING against a unique
      // constraint, so two replicas running this at once serialize on the row/index locks and the
      // loser's writes become no-ops instead of failing.
      c.setAutoCommit(false)
      try
        def exec(sql: String)(bind: PreparedStatement => Unit): Unit =
          val ps = c.prepareStatement(sql)
          try
            bind(ps)
            if ps.executeUpdate() > 0 then changed = true
          finally ps.close()

        // Case-insensitive: the qod_ prefix is reserved regardless of case. FOR UPDATE makes a
        // concurrent replica block here, then re-check lower(name) on the renamed row and skip it.
        def renameCollisions(table: String, names: Set[String]): Unit =
          names.toList.sorted.foreach { n =>
            val ps = c.prepareStatement(
              s"SELECT id, name FROM $table WHERE tenant_id = ? AND lower(name) = ? " +
                "AND NOT builtin ORDER BY id FOR UPDATE"
            )
            val colliding =
              try
                ps.setString(1, tenantId)
                ps.setString(2, n.toLowerCase(Locale.ROOT))
                val rs = ps.executeQuery()
                try drain(rs)(r => (r.getString(1), r.getString(2)))
                finally rs.close()
              finally ps.close()
            colliding.foreach { case (id, name) =>
              val target = BuiltinRbac.renamedName(name, candidate => nameTaken(table, candidate))
              exec(s"UPDATE $table SET name = ? WHERE id = ?") { ps =>
                ps.setString(1, target)
                ps.setString(2, id)
              }
            }
          }
        def nameTaken(table: String, name: String): Boolean =
          val ps = c.prepareStatement(s"SELECT 1 FROM $table WHERE tenant_id = ? AND name = ?")
          try
            ps.setString(1, tenantId)
            ps.setString(2, name)
            val rs = ps.executeQuery()
            try rs.next()
            finally rs.close()
          finally ps.close()
        renameCollisions("qodstate_role", BuiltinRbac.RoleNames)
        renameCollisions("qodstate_group", BuiltinRbac.GroupNames)

        val fresh = BuiltinRbac.rowsFor(tenantId)
        def insertBuiltin(table: String, id: String, name: String, desc: Option[String]): Unit =
          exec(
            s"""INSERT INTO $table (id, tenant_id, name, description, builtin)
               |VALUES (?, ?, ?, ?, TRUE)
               |ON CONFLICT (tenant_id, name) DO NOTHING""".stripMargin
          ) { ps =>
            ps.setString(1, id)
            ps.setString(2, tenantId)
            ps.setString(3, name)
            setNullable(ps, 4, desc)
          }
        fresh.roles.foreach(r => insertBuiltin("qodstate_role", r.id, r.name, r.description))
        fresh.groups.foreach(g => insertBuiltin("qodstate_group", g.id, g.name, g.description))

        def builtinId(table: String, name: String): String =
          val ps =
            c.prepareStatement(
              s"SELECT id FROM $table WHERE tenant_id = ? AND name = ? AND builtin"
            )
          try
            ps.setString(1, tenantId)
            ps.setString(2, name)
            val rs = ps.executeQuery()
            try
              if rs.next() then rs.getString(1)
              else throw new IllegalStateException(s"built-in $name missing in tenant $tenantId")
            finally rs.close()
          finally ps.close()
        val allTablesId = builtinId("qodstate_role", BuiltinRbac.AllTables)
        val allPoolsId  = builtinId("qodstate_group", BuiltinRbac.AllPools)

        // qodstate_role_permission_unique covers (role_id, catalog, schema, table, verb).
        val perm = fresh.permissions.head
        exec(
          """INSERT INTO qodstate_role_permission
            |  (id, role_id, catalog_name, schema_name, table_name, verb)
            |VALUES (?, ?, ?, ?, ?, ?)
            |ON CONFLICT DO NOTHING""".stripMargin
        ) { ps =>
          ps.setString(1, perm.id)
          ps.setString(2, allTablesId)
          ps.setString(3, perm.catalogName)
          ps.setString(4, perm.schemaName)
          ps.setString(5, perm.tableName)
          ps.setString(6, perm.verb.toUpperCase(Locale.ROOT))
        }
        // qodstate_pool_permission_group_unique covers (tenant_id, COALESCE(pool_id, ''),
        // group_id), so a second tenant-wide grant for the group is a conflict.
        val grant = fresh.poolGrants.head
        exec(
          """INSERT INTO qodstate_pool_permission (id, tenant_id, pool_id, user_id, group_id)
            |VALUES (?, ?, NULL, NULL, ?)
            |ON CONFLICT DO NOTHING""".stripMargin
        ) { ps =>
          ps.setString(1, grant.id)
          ps.setString(2, tenantId)
          ps.setString(3, allPoolsId)
        }
        c.commit()
      catch
        case t: Throwable =>
          try c.rollback()
          catch case _: Throwable => ()
          throw t
      finally c.setAutoCommit(true)
    }
    builtinRowsOf(tenantId).copy(changed = changed)

  private def builtinRowsOf(tenantId: String): BuiltinRbac.Rows =
    val roles  = listRoles(tenantId).filter(_.builtin)
    val groups = listGroups(tenantId).filter(_.builtin)
    BuiltinRbac.Rows(
      roles = roles,
      groups = groups,
      permissions = roles.flatMap(r => listRolePermissions(r.id)),
      poolGrants = groups.flatMap(g => listPoolPermissionsForGroup(g.id))
    )

  def foldLegacyAdminRole(tenantId: String): Boolean = withConn { c =>
    c.setAutoCommit(false)
    try
      def single(sql: String)(bind: PreparedStatement => Unit): Option[String] =
        val ps = c.prepareStatement(sql)
        try
          bind(ps)
          val rs = ps.executeQuery()
          try if rs.next() then Some(rs.getString(1)) else None
          finally rs.close()
        finally ps.close()

      // FOR UPDATE: a concurrent replica blocks here, then re-checks and finds the row gone.
      val adminId = single(
        """SELECT r.id FROM qodstate_role r
          |WHERE r.tenant_id = ? AND r.name = 'admin' AND NOT r.builtin
          |  AND (SELECT count(*) FROM qodstate_role_permission p WHERE p.role_id = r.id) = 1
          |  AND EXISTS (SELECT 1 FROM qodstate_role_permission p WHERE p.role_id = r.id
          |      AND p.catalog_name = '*' AND p.schema_name = '*' AND p.table_name = '*'
          |      AND p.verb = 'ALL')
          |  AND NOT EXISTS (SELECT 1 FROM qodstate_role_column_policy cp WHERE cp.role_id = r.id)
          |  AND NOT EXISTS (SELECT 1 FROM qodstate_role_row_policy rp WHERE rp.role_id = r.id)
          |FOR UPDATE""".stripMargin
      )(_.setString(1, tenantId))
      val target = adminId.flatMap { _ =>
        single("SELECT id FROM qodstate_role WHERE tenant_id = ? AND name = ? AND builtin") { ps =>
          ps.setString(1, tenantId)
          ps.setString(2, BuiltinRbac.AllTables)
        }
      }
      val folded = (adminId, target) match
        case (Some(aid), Some(all)) =>
          List(
            "INSERT INTO qodstate_user_role (user_id, role_id) " +
              "SELECT user_id, ? FROM qodstate_user_role WHERE role_id = ? ON CONFLICT DO NOTHING",
            "INSERT INTO qodstate_group_role (group_id, role_id) " +
              "SELECT group_id, ? FROM qodstate_group_role WHERE role_id = ? ON CONFLICT DO NOTHING"
          ).foreach { sql =>
            val ps = c.prepareStatement(sql)
            try
              ps.setString(1, all)
              ps.setString(2, aid)
              ps.executeUpdate()
            finally ps.close()
          }
          // The admin role's own edges and its permission go with it (ON DELETE CASCADE).
          deleteById(c, "qodstate_role", "id", aid)
          true
        case _ => false
      c.commit()
      folded
    catch
      case t: Throwable =>
        try c.rollback()
        catch case _: Throwable => ()
        throw t
    finally c.setAutoCommit(true)
  }

  private def insertRoleRow(c: Connection, r: RbacRole): Unit =
    val ps = c.prepareStatement(
      """INSERT INTO qodstate_role (id, tenant_id, name, description, builtin)
        |VALUES (?, ?, ?, ?, ?)""".stripMargin
    )
    try
      ps.setString(1, r.id)
      ps.setString(2, r.tenantId)
      ps.setString(3, r.name)
      setNullable(ps, 4, r.description)
      ps.setBoolean(5, r.builtin)
      ps.executeUpdate()
    finally ps.close()

  private def insertGroupRow(c: Connection, g: RbacGroup): Unit =
    val ps = c.prepareStatement(
      """INSERT INTO qodstate_group (id, tenant_id, name, description, builtin)
        |VALUES (?, ?, ?, ?, ?)""".stripMargin
    )
    try
      ps.setString(1, g.id)
      ps.setString(2, g.tenantId)
      ps.setString(3, g.name)
      setNullable(ps, 4, g.description)
      ps.setBoolean(5, g.builtin)
      ps.executeUpdate()
    finally ps.close()

  private def insertRolePermissionRow(c: Connection, p: RolePermission): Unit =
    val ps = c.prepareStatement(
      """INSERT INTO qodstate_role_permission
        |  (id, role_id, catalog_name, schema_name, table_name, verb)
        |VALUES (?, ?, ?, ?, ?, ?)""".stripMargin
    )
    try
      ps.setString(1, p.id)
      ps.setString(2, p.roleId)
      ps.setString(3, p.catalogName)
      ps.setString(4, p.schemaName)
      ps.setString(5, p.tableName)
      ps.setString(6, p.verb.toUpperCase(Locale.ROOT))
      ps.executeUpdate()
    finally ps.close()

  private def insertPoolPermissionRow(c: Connection, p: PoolPermission): Unit =
    val ps = c.prepareStatement(
      """INSERT INTO qodstate_pool_permission (id, tenant_id, pool_id, user_id, group_id)
        |VALUES (?, ?, ?, ?, ?)""".stripMargin
    )
    try
      ps.setString(1, p.id)
      ps.setString(2, p.tenantId)
      setNullable(ps, 3, p.poolId)
      setNullable(ps, 4, p.userId)
      setNullable(ps, 5, p.groupId)
      ps.executeUpdate()
    finally ps.close()

  private def readTenant(rs: ResultSet): Tenant =
    Tenant(
      id = rs.getString("id"),
      displayName = rs.getString("display_name"),
      disabled = rs.getBoolean("disabled"),
      authProvider = rs.getString("auth_provider"),
      authConfig = jsonToMap(rs.getString("auth_config")),
      acl = TenantAcl(
        mode = Option(rs.getString("acl_mode")),
        opaUrl = Option(rs.getString("opa_url")),
        opaPolicyPath = Option(rs.getString("opa_policy_path")),
        opaToken = Option(rs.getString("opa_token")),
        sendStatementText = rs.getBoolean("opa_send_statement_text")
      ),
      authClientSecret = Option(rs.getString("auth_client_secret"))
    )

  // ---------------- TenantDb ----------------

  def upsertTenantDb(t: TenantDb): Unit = withConn { c =>
    val ps = c.prepareStatement(
      """INSERT INTO qodstate_tenant_db
        |  (id, tenant_id, name, metastore_params, data_path, object_store_params, disabled,
        |   kind, default_database, default_schema, init_sql, branch_of, encrypted)
        |VALUES (?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
        |ON CONFLICT (id) DO UPDATE SET
        |  tenant_id            = EXCLUDED.tenant_id,
        |  name                 = EXCLUDED.name,
        |  metastore_params     = EXCLUDED.metastore_params,
        |  data_path            = EXCLUDED.data_path,
        |  object_store_params  = EXCLUDED.object_store_params,
        |  disabled             = EXCLUDED.disabled,
        |  kind                 = EXCLUDED.kind,
        |  default_database     = EXCLUDED.default_database,
        |  default_schema       = EXCLUDED.default_schema,
        |  init_sql             = EXCLUDED.init_sql,
        |  branch_of            = EXCLUDED.branch_of,
        |  encrypted            = EXCLUDED.encrypted""".stripMargin
    )
    try
      ps.setString(1, t.id)
      ps.setString(2, t.tenantId)
      ps.setString(3, t.name)
      ps.setString(4, mapToJson(t.metastore))
      ps.setString(5, t.dataPath)
      ps.setString(6, mapToJson(t.objectStore))
      ps.setBoolean(7, t.disabled)
      ps.setString(8, t.kind.wireValue)
      ps.setString(9, t.defaultDatabase.orNull)
      ps.setString(10, t.defaultSchema.orNull)
      ps.setString(11, t.initSql)
      setNullable(ps, 12, t.branchOf)
      ps.setBoolean(13, t.encrypted)
      ps.executeUpdate()
    finally ps.close()
  }

  def listTenantDbs(tenantId: String): List[TenantDb] = withConn { c =>
    val ps = c.prepareStatement(
      """SELECT id, tenant_id, name, metastore_params, data_path, object_store_params, disabled,
        |       kind, default_database, default_schema, init_sql, branch_of, encrypted
        |FROM qodstate_tenant_db WHERE tenant_id = ? ORDER BY name""".stripMargin
    )
    try
      ps.setString(1, tenantId)
      val rs = ps.executeQuery()
      try drain(rs)(readTenantDb)
      finally rs.close()
    finally ps.close()
  }

  def deleteTenantDb(id: String): Unit =
    withConn(c => deleteById(c, "qodstate_tenant_db", "id", id))

  private def readTenantDb(rs: ResultSet): TenantDb =
    TenantDb(
      id = rs.getString("id"),
      tenantId = rs.getString("tenant_id"),
      name = rs.getString("name"),
      kind = TenantDbKind
        .fromWire(rs.getString("kind"))
        .fold(
          err => sys.error(s"qodstate_tenant_db.kind invalid: '${rs.getString("kind")}' ($err)"),
          identity
        ),
      metastore = jsonToMap(rs.getString("metastore_params")),
      dataPath = rs.getString("data_path"),
      objectStore = jsonToMap(rs.getString("object_store_params")),
      defaultDatabase = Option(rs.getString("default_database")),
      defaultSchema = Option(rs.getString("default_schema")),
      disabled = rs.getBoolean("disabled"),
      initSql = rs.getString("init_sql"),
      branchOf = Option(rs.getString("branch_of")),
      encrypted = rs.getBoolean("encrypted")
    )

  // ---------------- Pool ----------------

  def upsertPool(p: Pool): Unit = withConn { c =>
    val ps = c.prepareStatement(
      """INSERT INTO qodstate_pool
        |  (id, tenant_id, tenant_db_id, name, size,
        |   dist_writeonly, dist_readonly, dist_dual,
        |   max_concurrent_per_node, idle_timeout_sec, disabled, cohorts, init_sql,
        |   cpu, memory, pod_template_yaml, suspended, lockdown, min_nodes, max_nodes)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)
        |ON CONFLICT (id) DO UPDATE SET
        |  tenant_id               = EXCLUDED.tenant_id,
        |  tenant_db_id            = EXCLUDED.tenant_db_id,
        |  name                    = EXCLUDED.name,
        |  size                    = EXCLUDED.size,
        |  dist_writeonly          = EXCLUDED.dist_writeonly,
        |  dist_readonly           = EXCLUDED.dist_readonly,
        |  dist_dual               = EXCLUDED.dist_dual,
        |  max_concurrent_per_node = EXCLUDED.max_concurrent_per_node,
        |  idle_timeout_sec        = EXCLUDED.idle_timeout_sec,
        |  disabled                = EXCLUDED.disabled,
        |  cohorts                 = EXCLUDED.cohorts,
        |  init_sql                = EXCLUDED.init_sql,
        |  cpu                     = EXCLUDED.cpu,
        |  memory                  = EXCLUDED.memory,
        |  pod_template_yaml       = EXCLUDED.pod_template_yaml,
        |  suspended               = EXCLUDED.suspended,
        |  lockdown                = EXCLUDED.lockdown,
        |  min_nodes               = EXCLUDED.min_nodes,
        |  max_nodes               = EXCLUDED.max_nodes""".stripMargin
    )
    try
      ps.setString(1, p.id)
      ps.setString(2, p.tenantId)
      ps.setString(3, p.tenantDbId)
      ps.setString(4, p.name)
      ps.setInt(5, p.size)
      ps.setInt(6, p.distribution.writeonly)
      ps.setInt(7, p.distribution.readonly)
      ps.setInt(8, p.distribution.dual)
      ps.setInt(9, p.maxConcurrentPerNode)
      p.idleTimeoutSec match
        case Some(v) => ps.setInt(10, v)
        case None    => ps.setNull(10, Types.INTEGER)
      ps.setBoolean(11, p.disabled)
      ps.setString(12, PostgresControlPlaneStore.cohortsToJson(p.cohorts))
      ps.setString(13, p.initSql)
      ps.setString(14, p.cpu)
      ps.setString(15, p.memory)
      ps.setString(16, p.podTemplateYaml)
      ps.setBoolean(17, p.suspended)
      p.lockdown match
        case Some(v) => ps.setBoolean(18, v)
        case None    => ps.setNull(18, Types.BOOLEAN)
      p.minNodes match
        case Some(v) => ps.setInt(19, v)
        case None    => ps.setNull(19, Types.INTEGER)
      p.maxNodes match
        case Some(v) => ps.setInt(20, v)
        case None    => ps.setNull(20, Types.INTEGER)
      ps.executeUpdate()
    finally ps.close()
  }

  def listPools(tenantDbId: String): List[Pool] = withConn { c =>
    val ps = c.prepareStatement(
      """SELECT id, tenant_id, tenant_db_id, name, size,
        |       dist_writeonly, dist_readonly, dist_dual,
        |       max_concurrent_per_node, idle_timeout_sec, disabled, cohorts, init_sql,
        |       cpu, memory, pod_template_yaml, suspended, lockdown, min_nodes, max_nodes
        |FROM qodstate_pool WHERE tenant_db_id = ? ORDER BY name""".stripMargin
    )
    try
      ps.setString(1, tenantDbId)
      val rs = ps.executeQuery()
      try drain(rs)(readPool)
      finally rs.close()
    finally ps.close()
  }

  def deletePool(id: String): Unit = withConn(c => deleteById(c, "qodstate_pool", "id", id))

  private def readPool(rs: ResultSet): Pool =
    val idle = rs.getObject("idle_timeout_sec")
    Pool(
      id = rs.getString("id"),
      tenantId = rs.getString("tenant_id"),
      tenantDbId = rs.getString("tenant_db_id"),
      name = rs.getString("name"),
      size = rs.getInt("size"),
      distribution = RoleDistribution(
        writeonly = rs.getInt("dist_writeonly"),
        readonly = rs.getInt("dist_readonly"),
        dual = rs.getInt("dist_dual")
      ),
      maxConcurrentPerNode = rs.getInt("max_concurrent_per_node"),
      idleTimeoutSec = Option(idle).map(_.asInstanceOf[Number].intValue),
      disabled = rs.getBoolean("disabled"),
      suspended = rs.getBoolean("suspended"),
      cohorts = PostgresControlPlaneStore.cohortsFromJson(rs.getString("cohorts")),
      // Column added in changeset 27; NULL on older rows -> default to "".
      initSql = Option(rs.getString("init_sql")).getOrElse(""),
      cpu = Option(rs.getString("cpu")).getOrElse(""),
      memory = Option(rs.getString("memory")).getOrElse(""),
      podTemplateYaml = Option(rs.getString("pod_template_yaml")).getOrElse(""),
      // Column added in changeset 0024; NULL = inherit the global flag.
      lockdown =
        Option(rs.getObject("lockdown")).map(_.asInstanceOf[java.lang.Boolean].booleanValue),
      // Columns added in changeset 0025; NULL = fixed-size pool (no band).
      minNodes = Option(rs.getObject("min_nodes")).map(_.asInstanceOf[Number].intValue),
      maxNodes = Option(rs.getObject("max_nodes")).map(_.asInstanceOf[Number].intValue)
    )

  // ---------------- Pool load (autoscale demand buckets) ----------------

  def addPoolLoad(
      poolId: String,
      bucketStart: java.time.Instant,
      statements: Long,
      totalDurationMs: Long
  ): Unit = withConn { c =>
    val ps = c.prepareStatement(
      """INSERT INTO qodstate_pool_load (pool_id, bucket_start, statements, total_duration_ms)
        |VALUES (?, ?, ?, ?)
        |ON CONFLICT (pool_id, bucket_start) DO UPDATE SET
        |  statements        = qodstate_pool_load.statements + EXCLUDED.statements,
        |  total_duration_ms = qodstate_pool_load.total_duration_ms + EXCLUDED.total_duration_ms""".stripMargin
    )
    try
      ps.setString(1, poolId)
      ps.setTimestamp(2, Timestamp.from(bucketStart))
      ps.setLong(3, statements)
      ps.setLong(4, totalDurationMs)
      ps.executeUpdate()
      ()
    finally ps.close()
  }

  def poolLoadWindow(from: java.time.Instant): Map[String, (Long, Long)] = withConn { c =>
    val ps = c.prepareStatement(
      """SELECT pool_id, SUM(statements) AS s, SUM(total_duration_ms) AS d
        |FROM qodstate_pool_load WHERE bucket_start >= ? GROUP BY pool_id""".stripMargin
    )
    try
      ps.setTimestamp(1, Timestamp.from(from))
      val rs = ps.executeQuery()
      try
        val out = Map.newBuilder[String, (Long, Long)]
        while rs.next() do out += rs.getString("pool_id") -> (rs.getLong("s"), rs.getLong("d"))
        out.result()
      finally rs.close()
    finally ps.close()
  }

  def purgePoolLoad(olderThan: java.time.Instant): Int = withConn { c =>
    val ps = c.prepareStatement("DELETE FROM qodstate_pool_load WHERE bucket_start < ?")
    try
      ps.setTimestamp(1, Timestamp.from(olderThan))
      ps.executeUpdate()
    finally ps.close()
  }

  def upsertPoolActivity(poolId: String, lastStatementAt: java.time.Instant): Unit = withConn { c =>
    val ps = c.prepareStatement(
      """INSERT INTO qodstate_pool_activity (pool_id, last_statement_at)
          |VALUES (?, ?)
          |ON CONFLICT (pool_id) DO UPDATE SET
          |  last_statement_at =
          |    GREATEST(qodstate_pool_activity.last_statement_at, EXCLUDED.last_statement_at)""".stripMargin
    )
    try
      ps.setString(1, poolId)
      ps.setTimestamp(2, Timestamp.from(lastStatementAt))
      ps.executeUpdate()
      ()
    finally ps.close()
  }

  def poolActivity(): Map[String, java.time.Instant] = withConn { c =>
    val ps = c.prepareStatement("SELECT pool_id, last_statement_at FROM qodstate_pool_activity")
    try
      val rs = ps.executeQuery()
      try
        val out = Map.newBuilder[String, java.time.Instant]
        while rs.next() do
          out += rs.getString("pool_id") -> rs.getTimestamp("last_statement_at").toInstant
        out.result()
      finally rs.close()
    finally ps.close()
  }

  def purgePoolActivity(olderThan: java.time.Instant): Int = withConn { c =>
    val ps = c.prepareStatement("DELETE FROM qodstate_pool_activity WHERE last_statement_at < ?")
    try
      ps.setTimestamp(1, Timestamp.from(olderThan))
      ps.executeUpdate()
    finally ps.close()
  }

  // ---------------- Node ----------------

  def upsertNode(n: RunningNode, poolId: String): Unit = withConn { c =>
    val ps = c.prepareStatement(
      """INSERT INTO qodstate_node
        |  (node_id, pool_id, host, port, token, role,
        |   pid, pod_name, started_at, last_seen, max_concurrent, server_name)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        |ON CONFLICT (node_id) DO UPDATE SET
        |  pool_id        = EXCLUDED.pool_id,
        |  host           = EXCLUDED.host,
        |  port           = EXCLUDED.port,
        |  token          = EXCLUDED.token,
        |  role           = EXCLUDED.role,
        |  pid            = EXCLUDED.pid,
        |  pod_name       = EXCLUDED.pod_name,
        |  started_at     = EXCLUDED.started_at,
        |  last_seen      = EXCLUDED.last_seen,
        |  max_concurrent = EXCLUDED.max_concurrent,
        |  server_name    = EXCLUDED.server_name""".stripMargin
    )
    try
      ps.setString(1, n.nodeId)
      ps.setString(2, poolId)
      ps.setString(3, n.host)
      ps.setInt(4, n.port)
      ps.setString(5, n.token)
      ps.setString(6, n.role.toString)
      n.pid match { case Some(v) => ps.setLong(7, v); case None => ps.setNull(7, Types.BIGINT) }
      n.podName match {
        case Some(v) => ps.setString(8, v); case None => ps.setNull(8, Types.VARCHAR)
      }
      ps.setTimestamp(9, Timestamp.from(n.startedAt))
      n.lastSeen match {
        case Some(v) => ps.setTimestamp(10, Timestamp.from(v));
        case None    => ps.setNull(10, Types.TIMESTAMP)
      }
      ps.setInt(11, n.maxConcurrent)
      n.serverName match
        case Some(v) => ps.setString(12, v)
        case None    => ps.setNull(12, Types.VARCHAR)
      ps.executeUpdate()
    finally ps.close()
  }

  def listNodes(poolId: String): List[RunningNode] = withConn { c =>
    val ps = c.prepareStatement(
      """SELECT n.node_id, n.host, n.port, n.token, n.role,
        |       n.pid, n.pod_name, n.started_at, n.last_seen, n.max_concurrent, n.server_name,
        |       t.id AS tenant_id, td.name AS tenant_db_name, p.name AS pool_name
        |FROM qodstate_node n
        |JOIN qodstate_pool p       ON p.id  = n.pool_id
        |JOIN qodstate_tenant_db td ON td.id = p.tenant_db_id
        |JOIN qodstate_tenant t     ON t.id  = td.tenant_id
        |WHERE n.pool_id = ?
        |ORDER BY n.node_id""".stripMargin
    )
    try
      ps.setString(1, poolId)
      val rs = ps.executeQuery()
      try drain(rs)(readNode)
      finally rs.close()
    finally ps.close()
  }

  def deleteNode(nodeId: String): Unit =
    withConn(c => deleteById(c, "qodstate_node", "node_id", nodeId))

  def nodeExists(nodeId: String): Boolean = withConn { c =>
    val st = c.prepareStatement("SELECT 1 FROM qodstate_node WHERE node_id = ?")
    try
      st.setString(1, nodeId)
      val rs = st.executeQuery()
      try rs.next()
      finally rs.close()
    finally st.close()
  }

  def deleteNodesForPool(poolId: String): Unit = withConn { c =>
    val st = c.prepareStatement("DELETE FROM qodstate_node WHERE pool_id = ?")
    try
      st.setString(1, poolId)
      st.executeUpdate()
      ()
    finally st.close()
  }

  override def setNodeQuarantined(nodeId: String, quarantined: Boolean): Unit =
    withConn { c =>
      val st = c.prepareStatement("UPDATE qodstate_node SET quarantined = ? WHERE node_id = ?")
      try
        st.setBoolean(1, quarantined)
        st.setString(2, nodeId)
        st.executeUpdate()
        ()
      finally st.close()
    }

  override def listQuarantinedNodeIds(): Set[String] =
    withConn { c =>
      val st = c.prepareStatement("SELECT node_id FROM qodstate_node WHERE quarantined")
      try
        val rs = st.executeQuery()
        val b  = Set.newBuilder[String]
        while rs.next() do b += rs.getString(1)
        b.result()
      finally st.close()
    }

  private def readNode(rs: ResultSet): RunningNode =
    RunningNode(
      nodeId = rs.getString("node_id"),
      poolKey = PoolKey(
        rs.getString("tenant_id"),
        rs.getString("tenant_db_name"),
        rs.getString("pool_name")
      ),
      role = Role.valueOf(rs.getString("role")),
      host = rs.getString("host"),
      port = rs.getInt("port"),
      token = rs.getString("token"),
      pid = Option(rs.getObject("pid")).map(_.asInstanceOf[Number].longValue),
      podName = Option(rs.getString("pod_name")),
      startedAt = rs.getTimestamp("started_at").toInstant,
      maxConcurrent = rs.getInt("max_concurrent"),
      lastSeen = Option(rs.getTimestamp("last_seen")).map(_.toInstant),
      serverName = Option(rs.getString("server_name"))
    )

  // ---------------- RBAC: users ----------------

  def upsertUserIdentity(u: RbacUser): Unit = withConn { c =>
    val ps = c.prepareStatement(
      """INSERT INTO qodstate_user (id, tenant, username, kind, password_hash, created_at, updated_at)
        |VALUES (?, ?, ?, ?, '', COALESCE(?, NOW()), NOW())
        |ON CONFLICT (id) DO UPDATE SET
        |  tenant     = EXCLUDED.tenant,
        |  username   = EXCLUDED.username,
        |  kind       = EXCLUDED.kind,
        |  updated_at = NOW()""".stripMargin
    )
    try
      ps.setString(1, u.id)
      u.tenant match
        case Some(t) => ps.setString(2, t)
        case None    => ps.setNull(2, Types.VARCHAR)
      ps.setString(3, u.username)
      ps.setString(4, u.kind)
      u.createdAt match
        case Some(t) => ps.setTimestamp(5, Timestamp.from(t))
        case None    => ps.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE)
      ps.executeUpdate()
    finally ps.close()
  }

  def getPasswordHash(tenant: Option[String], username: String): Option[String] = withConn { c =>
    val ps = tenant match
      case Some(t) =>
        val p = c.prepareStatement(
          "SELECT password_hash FROM qodstate_user WHERE tenant = ? AND username = ?"
        )
        p.setString(1, t)
        p.setString(2, username)
        p
      case None =>
        val p = c.prepareStatement(
          "SELECT password_hash FROM qodstate_user WHERE tenant IS NULL AND username = ?"
        )
        p.setString(1, username)
        p
    try
      val rs = ps.executeQuery()
      try if rs.next() then Some(rs.getString(1)) else None
      finally rs.close()
    finally ps.close()
  }

  def upsertUserWithHash(
      tenant: Option[String],
      username: String,
      passwordHash: String,
      kind: String,
      enabled: Boolean = true,
      mustChangePassword: Boolean = false,
      email: Option[String] = None
  ): String = withConn { c =>
    // Delegate to the shared upsert. enabled = Some(enabled) and
    // mustChangePassword = Some(mustChangePassword): this path owns both flags
    // (manifest import, admin provisioning) and writes them on both insert and
    // update. email = Some(email): the manifest import always sets an explicit
    // value or null, so this path owns the column too.
    UserUpsert(
      c,
      tenant,
      username,
      passwordHash,
      kind,
      enabled = Some(enabled),
      mustChangePassword = Some(mustChangePassword),
      email = Some(email)
    ).id
  }

  def getUserById(id: String): Option[RbacUser] = withConn { c =>
    val ps = c.prepareStatement(
      "SELECT id, tenant, username, kind, enabled, must_change_password, email, created_at, updated_at, external_id FROM qodstate_user WHERE id = ?"
    )
    try
      ps.setString(1, id)
      val rs = ps.executeQuery()
      try if rs.next() then Some(readRbacUser(rs)) else None
      finally rs.close()
    finally ps.close()
  }

  def findUser(tenant: Option[String], username: String): Option[RbacUser] = withConn { c =>
    val ps = tenant match
      case Some(t) =>
        val p = c.prepareStatement(
          """SELECT id, tenant, username, kind, enabled, must_change_password, email, created_at, updated_at, external_id
            |FROM qodstate_user WHERE tenant = ? AND username = ?""".stripMargin
        )
        p.setString(1, t)
        p.setString(2, username)
        p
      case None =>
        val p = c.prepareStatement(
          """SELECT id, tenant, username, kind, enabled, must_change_password, email, created_at, updated_at, external_id
            |FROM qodstate_user WHERE tenant IS NULL AND username = ?""".stripMargin
        )
        p.setString(1, username)
        p
    try
      val rs = ps.executeQuery()
      try if rs.next() then Some(readRbacUser(rs)) else None
      finally rs.close()
    finally ps.close()
  }

  def listUsers(tenant: Option[String]): List[RbacUser] = withConn { c =>
    val ps = tenant match
      case Some(t) =>
        val p = c.prepareStatement(
          """SELECT id, tenant, username, kind, enabled, must_change_password, email, created_at, updated_at, external_id
            |FROM qodstate_user WHERE tenant = ? ORDER BY username""".stripMargin
        )
        p.setString(1, t)
        p
      case None =>
        c.prepareStatement(
          """SELECT id, tenant, username, kind, enabled, must_change_password, email, created_at, updated_at, external_id
            |FROM qodstate_user ORDER BY COALESCE(tenant, ''), username""".stripMargin
        )
    try
      val rs = ps.executeQuery()
      try drain(rs)(readRbacUser)
      finally rs.close()
    finally ps.close()
  }

  def listSuperusers(): List[RbacUser] = withConn { c =>
    val ps = c.prepareStatement(
      """SELECT id, tenant, username, kind, enabled, must_change_password, email, created_at, updated_at, external_id
        |FROM qodstate_user WHERE tenant IS NULL ORDER BY username""".stripMargin
    )
    try
      val rs = ps.executeQuery()
      try drain(rs)(readRbacUser)
      finally rs.close()
    finally ps.close()
  }

  def findUserForLogin(tenantId: String, username: String): Option[RbacUser] = withConn { c =>
    // ORDER BY (tenant IS NOT NULL) DESC so a tenant-scoped row wins over
    // the wildcard NULL superuser row when both exist with the same
    // username. Mirrors application.conf's auth.database.query.
    val ps = c.prepareStatement(
      """SELECT id, tenant, username, kind, enabled, must_change_password, email, created_at, updated_at, external_id
        |FROM qodstate_user
        |WHERE (tenant IS NULL OR tenant = ?) AND username = ?
        |ORDER BY (tenant IS NOT NULL) DESC
        |LIMIT 1""".stripMargin
    )
    try
      ps.setString(1, tenantId)
      ps.setString(2, username)
      val rs = ps.executeQuery()
      try if rs.next() then Some(readRbacUser(rs)) else None
      finally rs.close()
    finally ps.close()
  }

  def deleteUser(id: String): Unit =
    withConn(c => deleteById(c, "qodstate_user", "id", id))

  private def readRbacUser(rs: ResultSet): RbacUser =
    RbacUser(
      id = rs.getString("id"),
      tenant = Option(rs.getString("tenant")),
      username = rs.getString("username"),
      kind = rs.getString("kind"),
      enabled = rs.getBoolean("enabled"),
      mustChangePassword = rs.getBoolean("must_change_password"),
      email = Option(rs.getString("email")),
      createdAt = Option(rs.getTimestamp("created_at")).map(_.toInstant),
      updatedAt = Option(rs.getTimestamp("updated_at")).map(_.toInstant),
      externalId = Option(rs.getString("external_id"))
    )

  def setUserExternalId(id: String, externalId: Option[String]): Unit = withConn { c =>
    val ps = c.prepareStatement("UPDATE qodstate_user SET external_id = ? WHERE id = ?")
    try
      ps.setString(1, externalId.orNull)
      ps.setString(2, id)
      ps.executeUpdate()
      ()
    finally ps.close()
  }

  def setGroupExternalId(id: String, externalId: Option[String]): Unit = withConn { c =>
    val ps = c.prepareStatement("UPDATE qodstate_group SET external_id = ? WHERE id = ?")
    try
      ps.setString(1, externalId.orNull)
      ps.setString(2, id)
      ps.executeUpdate()
      ()
    finally ps.close()
  }

  // ---------------- RBAC: roles ----------------

  def upsertRole(r: RbacRole): Unit = withConn { c =>
    val ps = c.prepareStatement(
      """INSERT INTO qodstate_role (id, tenant_id, name, description, created_at)
        |VALUES (?, ?, ?, ?, COALESCE(?, NOW()))
        |ON CONFLICT (id) DO UPDATE SET
        |  tenant_id   = EXCLUDED.tenant_id,
        |  name        = EXCLUDED.name,
        |  description = EXCLUDED.description""".stripMargin
    )
    try
      ps.setString(1, r.id)
      ps.setString(2, r.tenantId)
      ps.setString(3, r.name)
      setNullable(ps, 4, r.description)
      r.createdAt match
        case Some(t) => ps.setTimestamp(5, Timestamp.from(t))
        case None    => ps.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE)
      ps.executeUpdate()
    finally ps.close()
  }

  def listRoles(tenantId: String): List[RbacRole] = withConn { c =>
    val ps = c.prepareStatement(
      """SELECT id, tenant_id, name, description, created_at, builtin
        |FROM qodstate_role WHERE tenant_id = ? ORDER BY name""".stripMargin
    )
    try
      ps.setString(1, tenantId)
      val rs = ps.executeQuery()
      try drain(rs)(readRole)
      finally rs.close()
    finally ps.close()
  }

  def getRole(id: String): Option[RbacRole] = withConn { c =>
    val ps = c.prepareStatement(
      "SELECT id, tenant_id, name, description, created_at, builtin FROM qodstate_role WHERE id = ?"
    )
    try
      ps.setString(1, id)
      val rs = ps.executeQuery()
      try if rs.next() then Some(readRole(rs)) else None
      finally rs.close()
    finally ps.close()
  }

  def findRole(tenantId: String, name: String): Option[RbacRole] = withConn { c =>
    val ps = c.prepareStatement(
      """SELECT id, tenant_id, name, description, created_at, builtin
        |FROM qodstate_role WHERE tenant_id = ? AND name = ?""".stripMargin
    )
    try
      ps.setString(1, tenantId)
      ps.setString(2, name)
      val rs = ps.executeQuery()
      try if rs.next() then Some(readRole(rs)) else None
      finally rs.close()
    finally ps.close()
  }

  def deleteRole(id: String): Unit =
    withConn(c => deleteById(c, "qodstate_role", "id", id))

  private def readRole(rs: ResultSet): RbacRole =
    RbacRole(
      id = rs.getString("id"),
      tenantId = rs.getString("tenant_id"),
      name = rs.getString("name"),
      description = Option(rs.getString("description")),
      createdAt = Option(rs.getTimestamp("created_at")).map(_.toInstant),
      builtin = rs.getBoolean("builtin")
    )

  // ---------------- RBAC: role permissions ----------------

  def insertRolePermission(p: RolePermission): RolePermission = withConn { c =>
    val ps = c.prepareStatement(
      """INSERT INTO qodstate_role_permission
        |  (id, role_id, catalog_name, schema_name, table_name, verb)
        |VALUES (?, ?, ?, ?, ?, ?)
        |RETURNING granted_at""".stripMargin
    )
    try
      ps.setString(1, p.id)
      ps.setString(2, p.roleId)
      ps.setString(3, p.catalogName)
      ps.setString(4, p.schemaName)
      ps.setString(5, p.tableName)
      ps.setString(6, p.verb.toUpperCase(Locale.ROOT))
      val rs = ps.executeQuery()
      try
        rs.next()
        p.copy(grantedAt = Some(rs.getTimestamp(1).toInstant))
      finally rs.close()
    finally ps.close()
  }

  def listRolePermissions(roleId: String): List[RolePermission] = withConn { c =>
    val ps = c.prepareStatement(
      """SELECT id, role_id, catalog_name, schema_name, table_name, verb, granted_at
        |FROM qodstate_role_permission WHERE role_id = ?
        |ORDER BY catalog_name, schema_name, table_name, verb""".stripMargin
    )
    try
      ps.setString(1, roleId)
      val rs = ps.executeQuery()
      try drain(rs)(readRolePermission)
      finally rs.close()
    finally ps.close()
  }

  def listRolePermissionsForRoles(roleIds: Set[String]): List[RolePermission] =
    if roleIds.isEmpty then Nil
    else
      withConn { c =>
        val placeholders = roleIds.toList.map(_ => "?").mkString(",")
        val ps           = c.prepareStatement(
          s"""SELECT id, role_id, catalog_name, schema_name, table_name, verb, granted_at
           |FROM qodstate_role_permission WHERE role_id IN ($placeholders)""".stripMargin
        )
        try
          roleIds.zipWithIndex.foreach((id, i) => ps.setString(i + 1, id))
          val rs = ps.executeQuery()
          try drain(rs)(readRolePermission)
          finally rs.close()
        finally ps.close()
      }

  def deleteRolePermission(id: String): Boolean = withConn { c =>
    val ps = c.prepareStatement("DELETE FROM qodstate_role_permission WHERE id = ?")
    try
      ps.setString(1, id)
      ps.executeUpdate() > 0
    finally ps.close()
  }

  def getRolePermission(id: String): Option[RolePermission] = withConn { c =>
    val ps = c.prepareStatement(
      """SELECT id, role_id, catalog_name, schema_name, table_name, verb, granted_at
        |FROM qodstate_role_permission WHERE id = ?""".stripMargin
    )
    try
      ps.setString(1, id)
      val rs = ps.executeQuery()
      try if rs.next() then Some(readRolePermission(rs)) else None
      finally rs.close()
    finally ps.close()
  }

  private def readRolePermission(rs: ResultSet): RolePermission =
    RolePermission(
      id = rs.getString("id"),
      roleId = rs.getString("role_id"),
      catalogName = rs.getString("catalog_name"),
      schemaName = rs.getString("schema_name"),
      tableName = rs.getString("table_name"),
      verb = rs.getString("verb"),
      grantedAt = Option(rs.getTimestamp("granted_at")).map(_.toInstant)
    )

  // ---------------- RBAC: groups ----------------

  def upsertGroup(g: RbacGroup): Unit = withConn { c =>
    val ps = c.prepareStatement(
      """INSERT INTO qodstate_group (id, tenant_id, name, description)
        |VALUES (?, ?, ?, ?)
        |ON CONFLICT (id) DO UPDATE SET
        |  tenant_id   = EXCLUDED.tenant_id,
        |  name        = EXCLUDED.name,
        |  description = EXCLUDED.description""".stripMargin
    )
    try
      ps.setString(1, g.id)
      ps.setString(2, g.tenantId)
      ps.setString(3, g.name)
      setNullable(ps, 4, g.description)
      ps.executeUpdate()
    finally ps.close()
  }

  def listGroups(tenantId: String): List[RbacGroup] = withConn { c =>
    val ps = c.prepareStatement(
      """SELECT id, tenant_id, name, description, external_id, builtin FROM qodstate_group
        |WHERE tenant_id = ? ORDER BY name""".stripMargin
    )
    try
      ps.setString(1, tenantId)
      val rs = ps.executeQuery()
      try drain(rs)(readGroup)
      finally rs.close()
    finally ps.close()
  }

  def getGroup(id: String): Option[RbacGroup] = withConn { c =>
    val ps = c.prepareStatement(
      "SELECT id, tenant_id, name, description, external_id, builtin FROM qodstate_group WHERE id = ?"
    )
    try
      ps.setString(1, id)
      val rs = ps.executeQuery()
      try if rs.next() then Some(readGroup(rs)) else None
      finally rs.close()
    finally ps.close()
  }

  def findGroup(tenantId: String, name: String): Option[RbacGroup] = withConn { c =>
    val ps = c.prepareStatement(
      """SELECT id, tenant_id, name, description, external_id, builtin FROM qodstate_group
        |WHERE tenant_id = ? AND name = ?""".stripMargin
    )
    try
      ps.setString(1, tenantId)
      ps.setString(2, name)
      val rs = ps.executeQuery()
      try if rs.next() then Some(readGroup(rs)) else None
      finally rs.close()
    finally ps.close()
  }

  def deleteGroup(id: String): Unit =
    withConn(c => deleteById(c, "qodstate_group", "id", id))

  private def readGroup(rs: ResultSet): RbacGroup =
    RbacGroup(
      id = rs.getString("id"),
      tenantId = rs.getString("tenant_id"),
      name = rs.getString("name"),
      description = Option(rs.getString("description")),
      externalId = Option(rs.getString("external_id")),
      builtin = rs.getBoolean("builtin")
    )

  // ---------------- RBAC: memberships ----------------

  def addUserGroup(userId: String, groupId: String): Unit = withConn { c =>
    insertEdge(c, "qodstate_user_group", "user_id", "group_id", userId, groupId)
  }

  def removeUserGroup(userId: String, groupId: String): Boolean = withConn { c =>
    deleteEdge(c, "qodstate_user_group", "user_id", "group_id", userId, groupId)
  }

  def listGroupsForUser(userId: String): List[String] = withConn { c =>
    listEdgeRight(c, "qodstate_user_group", "user_id", "group_id", userId)
  }

  def listUsersInGroup(groupId: String): List[String] = withConn { c =>
    listEdgeRight(c, "qodstate_user_group", "group_id", "user_id", groupId)
  }

  def addUserRole(userId: String, roleId: String): Unit = withConn { c =>
    insertEdge(c, "qodstate_user_role", "user_id", "role_id", userId, roleId)
  }

  def addUserMemberships(userId: String, roleIds: List[String], groupIds: List[String]): Unit =
    withConn { c =>
      // Single connection + manual commit: a fresh user ends with all of its edges or none.
      c.setAutoCommit(false)
      try
        roleIds.foreach(r => insertEdge(c, "qodstate_user_role", "user_id", "role_id", userId, r))
        groupIds.foreach(g =>
          insertEdge(c, "qodstate_user_group", "user_id", "group_id", userId, g)
        )
        c.commit()
      catch
        case t: Throwable =>
          try c.rollback()
          catch case _: Throwable => ()
          throw t
      finally c.setAutoCommit(true)
    }

  def removeUserRole(userId: String, roleId: String): Boolean = withConn { c =>
    deleteEdge(c, "qodstate_user_role", "user_id", "role_id", userId, roleId)
  }

  def listDirectRolesForUser(userId: String): List[String] = withConn { c =>
    listEdgeRight(c, "qodstate_user_role", "user_id", "role_id", userId)
  }

  def listDirectRolesByUsers(userIds: List[String]): Map[String, Set[String]] =
    bulkEdgeByLeft(userIds, "qodstate_user_role", "user_id", "role_id")

  def listGroupsByUsers(userIds: List[String]): Map[String, Set[String]] =
    bulkEdgeByLeft(userIds, "qodstate_user_group", "user_id", "group_id")

  def addGroupRole(groupId: String, roleId: String): Unit = withConn { c =>
    insertEdge(c, "qodstate_group_role", "group_id", "role_id", groupId, roleId)
  }

  def removeGroupRole(groupId: String, roleId: String): Boolean = withConn { c =>
    deleteEdge(c, "qodstate_group_role", "group_id", "role_id", groupId, roleId)
  }

  def listRolesForGroup(groupId: String): List[String] = withConn { c =>
    listEdgeRight(c, "qodstate_group_role", "group_id", "role_id", groupId)
  }

  private def insertEdge(
      c: Connection,
      table: String,
      leftCol: String,
      rightCol: String,
      left: String,
      right: String
  ): Unit =
    // ON CONFLICT DO NOTHING so add-member is idempotent (matches the
    // REST surface's expectation of 204 even on a duplicate add).
    val ps = c.prepareStatement(
      s"INSERT INTO $table ($leftCol, $rightCol) VALUES (?, ?) ON CONFLICT DO NOTHING"
    )
    try
      ps.setString(1, left)
      ps.setString(2, right)
      ps.executeUpdate()
    finally ps.close()

  private def deleteEdge(
      c: Connection,
      table: String,
      leftCol: String,
      rightCol: String,
      left: String,
      right: String
  ): Boolean =
    val ps = c.prepareStatement(s"DELETE FROM $table WHERE $leftCol = ? AND $rightCol = ?")
    try
      ps.setString(1, left)
      ps.setString(2, right)
      ps.executeUpdate() > 0
    finally ps.close()

  private def listEdgeRight(
      c: Connection,
      table: String,
      filterCol: String,
      returnCol: String,
      filter: String
  ): List[String] =
    val ps = c.prepareStatement(s"SELECT $returnCol FROM $table WHERE $filterCol = ?")
    try
      ps.setString(1, filter)
      val rs = ps.executeQuery()
      try drain(rs)(_.getString(1))
      finally rs.close()
    finally ps.close()

  // ---------------- RBAC: pool permissions ----------------

  def insertPoolPermission(p: PoolPermission): PoolPermission = withConn { c =>
    val ps = c.prepareStatement(
      """INSERT INTO qodstate_pool_permission
        |  (id, tenant_id, pool_id, user_id, group_id)
        |VALUES (?, ?, ?, ?, ?)
        |RETURNING granted_at""".stripMargin
    )
    try
      ps.setString(1, p.id)
      ps.setString(2, p.tenantId)
      setNullable(ps, 3, p.poolId)
      setNullable(ps, 4, p.userId)
      setNullable(ps, 5, p.groupId)
      val rs = ps.executeQuery()
      try
        rs.next()
        p.copy(grantedAt = Some(rs.getTimestamp(1).toInstant))
      finally rs.close()
    finally ps.close()
  }

  def deletePoolPermission(id: String): Boolean = withConn { c =>
    val ps = c.prepareStatement("DELETE FROM qodstate_pool_permission WHERE id = ?")
    try
      ps.setString(1, id)
      ps.executeUpdate() > 0
    finally ps.close()
  }

  def getPoolPermission(id: String): Option[PoolPermission] = withConn { c =>
    val ps = c.prepareStatement(
      """SELECT id, tenant_id, pool_id, user_id, group_id, granted_at
        |FROM qodstate_pool_permission WHERE id = ?""".stripMargin
    )
    try
      ps.setString(1, id)
      val rs = ps.executeQuery()
      try if rs.next() then Some(readPoolPermission(rs)) else None
      finally rs.close()
    finally ps.close()
  }

  def listPoolPermissions(
      tenantId: Option[String] = None,
      userId: Option[String] = None,
      groupId: Option[String] = None
  ): List[PoolPermission] = withConn { c =>
    // Build the WHERE dynamically so callers can mix filters; an unset
    // option contributes no predicate. Always ORDER BY id for stable
    // iteration in tests.
    val clauses = scala.collection.mutable.ListBuffer.empty[String]
    val binds   = scala.collection.mutable.ListBuffer.empty[String]
    tenantId.foreach { t => clauses += "tenant_id = ?"; binds += t }
    userId.foreach { u => clauses += "user_id   = ?"; binds += u }
    groupId.foreach { g => clauses += "group_id  = ?"; binds += g }
    val where = if clauses.isEmpty then "" else clauses.mkString(" WHERE ", " AND ", "")
    val ps    = c.prepareStatement(
      s"""SELECT id, tenant_id, pool_id, user_id, group_id, granted_at
         |FROM qodstate_pool_permission$where ORDER BY id""".stripMargin
    )
    try
      binds.zipWithIndex.foreach((v, i) => ps.setString(i + 1, v))
      val rs = ps.executeQuery()
      try drain(rs)(readPoolPermission)
      finally rs.close()
    finally ps.close()
  }

  def listPoolPermissionsForUser(userId: String): List[PoolPermission] =
    listPoolPermissions(userId = Some(userId))

  def listPoolPermissionsForGroup(groupId: String): List[PoolPermission] =
    listPoolPermissions(groupId = Some(groupId))

  def listPoolPermissionsByUsers(userIds: List[String]): Map[String, List[PoolPermission]] =
    if userIds.isEmpty then Map.empty
    else
      withConn { c =>
        val placeholders = userIds.map(_ => "?").mkString(",")
        val ps           = c.prepareStatement(
          s"""SELECT id, tenant_id, pool_id, user_id, group_id, granted_at
           |FROM qodstate_pool_permission WHERE user_id IN ($placeholders)""".stripMargin
        )
        try
          userIds.zipWithIndex.foreach((id, i) => ps.setString(i + 1, id))
          val rs = ps.executeQuery()
          try
            val buf = scala.collection.mutable.Map
              .empty[String, scala.collection.mutable.ListBuffer[PoolPermission]]
            while rs.next() do
              val pp = readPoolPermission(rs)
              pp.userId.foreach { u =>
                buf.getOrElseUpdate(u, scala.collection.mutable.ListBuffer.empty) += pp
              }
            buf.view.mapValues(_.toList).toMap
          finally rs.close()
        finally ps.close()
      }

  /** Group-by-left helper: bulk-fetch the rightCol values for a list of leftCol keys in one
    * round-trip. Used by the user-list path to avoid the N+1 read of user-role + user-group edges.
    */
  private def bulkEdgeByLeft(
      ids: List[String],
      table: String,
      leftCol: String,
      rightCol: String
  ): Map[String, Set[String]] =
    if ids.isEmpty then Map.empty
    else
      withConn { c =>
        val placeholders = ids.map(_ => "?").mkString(",")
        val ps           = c.prepareStatement(
          s"SELECT $leftCol, $rightCol FROM $table WHERE $leftCol IN ($placeholders)"
        )
        try
          ids.zipWithIndex.foreach((id, i) => ps.setString(i + 1, id))
          val rs = ps.executeQuery()
          try
            val buf =
              scala.collection.mutable.Map.empty[String, scala.collection.mutable.Set[String]]
            while rs.next() do
              val k = rs.getString(1)
              val v = rs.getString(2)
              buf.getOrElseUpdate(k, scala.collection.mutable.Set.empty) += v
            buf.view.mapValues(_.toSet).toMap
          finally rs.close()
        finally ps.close()
      }

  private def readPoolPermission(rs: ResultSet): PoolPermission =
    PoolPermission(
      id = rs.getString("id"),
      tenantId = rs.getString("tenant_id"),
      poolId = Option(rs.getString("pool_id")),
      userId = Option(rs.getString("user_id")),
      groupId = Option(rs.getString("group_id")),
      grantedAt = Option(rs.getTimestamp("granted_at")).map(_.toInstant)
    )

  // ---------------- Column policies (Liquibase 0012) ----------------

  def insertColumnPolicy(p: RoleColumnPolicy): RoleColumnPolicy = withConn { c =>
    val ps = c.prepareStatement(
      """INSERT INTO qodstate_role_column_policy
        |  (id, role_id, catalog_name, schema_name, table_name, column_name, action, transform_sql)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin
    )
    try
      ps.setString(1, p.id)
      ps.setString(2, p.roleId)
      ps.setString(3, p.catalogName)
      ps.setString(4, p.schemaName)
      ps.setString(5, p.tableName)
      ps.setString(6, p.columnName)
      ps.setString(7, p.action)
      p.transformSql match
        case Some(s) => ps.setString(8, s)
        case None    => ps.setNull(8, Types.VARCHAR)
      ps.executeUpdate()
      p
    finally ps.close()
  }

  def updateColumnPolicy(id: String, action: String, transformSql: Option[String]): Boolean =
    withConn { c =>
      val ps = c.prepareStatement(
        "UPDATE qodstate_role_column_policy SET action = ?, transform_sql = ? WHERE id = ?"
      )
      try
        ps.setString(1, action)
        transformSql match
          case Some(s) => ps.setString(2, s)
          case None    => ps.setNull(2, Types.VARCHAR)
        ps.setString(3, id)
        ps.executeUpdate() > 0
      finally ps.close()
    }

  def deleteColumnPolicy(id: String): Boolean = withConn { c =>
    val ps = c.prepareStatement("DELETE FROM qodstate_role_column_policy WHERE id = ?")
    try
      ps.setString(1, id)
      ps.executeUpdate() > 0
    finally ps.close()
  }

  def getColumnPolicy(id: String): Option[RoleColumnPolicy] = withConn { c =>
    val ps = c.prepareStatement(
      """SELECT id, role_id, catalog_name, schema_name, table_name, column_name, action, transform_sql
        |FROM qodstate_role_column_policy WHERE id = ?""".stripMargin
    )
    try
      ps.setString(1, id)
      val rs = ps.executeQuery()
      try if rs.next() then Some(readColumnPolicy(rs)) else None
      finally rs.close()
    finally ps.close()
  }

  def listColumnPolicies(roleId: String): List[RoleColumnPolicy] = withConn { c =>
    val ps = c.prepareStatement(
      """SELECT id, role_id, catalog_name, schema_name, table_name, column_name, action, transform_sql
        |FROM qodstate_role_column_policy WHERE role_id = ? ORDER BY id""".stripMargin
    )
    try
      ps.setString(1, roleId)
      val rs = ps.executeQuery()
      try drain(rs)(readColumnPolicy)
      finally rs.close()
    finally ps.close()
  }

  def listAllColumnPolicies(): List[RoleColumnPolicy] = withConn { c =>
    val ps = c.prepareStatement(
      """SELECT id, role_id, catalog_name, schema_name, table_name, column_name, action, transform_sql
        |FROM qodstate_role_column_policy ORDER BY id""".stripMargin
    )
    try
      val rs = ps.executeQuery()
      try drain(rs)(readColumnPolicy)
      finally rs.close()
    finally ps.close()
  }

  private def readColumnPolicy(rs: ResultSet): RoleColumnPolicy =
    RoleColumnPolicy(
      id = rs.getString("id"),
      roleId = rs.getString("role_id"),
      catalogName = rs.getString("catalog_name"),
      schemaName = rs.getString("schema_name"),
      tableName = rs.getString("table_name"),
      columnName = rs.getString("column_name"),
      action = rs.getString("action"),
      transformSql = Option(rs.getString("transform_sql"))
    )

  // ---------------- Row policies (Liquibase 0013) ----------------

  def insertRowPolicy(p: RoleRowPolicy): RoleRowPolicy = withConn { c =>
    val ps = c.prepareStatement(
      """INSERT INTO qodstate_role_row_policy
        |  (id, role_id, catalog_name, schema_name, table_name, predicate_sql)
        |VALUES (?, ?, ?, ?, ?, ?)""".stripMargin
    )
    try
      ps.setString(1, p.id)
      ps.setString(2, p.roleId)
      ps.setString(3, p.catalogName)
      ps.setString(4, p.schemaName)
      ps.setString(5, p.tableName)
      ps.setString(6, p.predicateSql)
      ps.executeUpdate()
      p
    finally ps.close()
  }

  def updateRowPolicy(id: String, predicateSql: String): Boolean = withConn { c =>
    val ps = c.prepareStatement(
      "UPDATE qodstate_role_row_policy SET predicate_sql = ? WHERE id = ?"
    )
    try
      ps.setString(1, predicateSql)
      ps.setString(2, id)
      ps.executeUpdate() > 0
    finally ps.close()
  }

  def deleteRowPolicy(id: String): Boolean = withConn { c =>
    val ps = c.prepareStatement("DELETE FROM qodstate_role_row_policy WHERE id = ?")
    try
      ps.setString(1, id)
      ps.executeUpdate() > 0
    finally ps.close()
  }

  def getRowPolicy(id: String): Option[RoleRowPolicy] = withConn { c =>
    val ps = c.prepareStatement(
      """SELECT id, role_id, catalog_name, schema_name, table_name, predicate_sql
        |FROM qodstate_role_row_policy WHERE id = ?""".stripMargin
    )
    try
      ps.setString(1, id)
      val rs = ps.executeQuery()
      try if rs.next() then Some(readRowPolicy(rs)) else None
      finally rs.close()
    finally ps.close()
  }

  def listRowPolicies(roleId: String): List[RoleRowPolicy] = withConn { c =>
    val ps = c.prepareStatement(
      """SELECT id, role_id, catalog_name, schema_name, table_name, predicate_sql
        |FROM qodstate_role_row_policy WHERE role_id = ? ORDER BY id""".stripMargin
    )
    try
      ps.setString(1, roleId)
      val rs = ps.executeQuery()
      try drain(rs)(readRowPolicy)
      finally rs.close()
    finally ps.close()
  }

  private def readRowPolicy(rs: ResultSet): RoleRowPolicy =
    RoleRowPolicy(
      id = rs.getString("id"),
      roleId = rs.getString("role_id"),
      catalogName = rs.getString("catalog_name"),
      schemaName = rs.getString("schema_name"),
      tableName = rs.getString("table_name"),
      predicateSql = rs.getString("predicate_sql")
    )

  // ---------------- HA: revocation + liveness ----------------

  override def insertRevokedJti(jti: String, expiresAt: Instant): Unit = withConn { c =>
    val st = c.prepareStatement(
      "INSERT INTO qodstate_revoked_jti (jti, expires_at) VALUES (?, ?) ON CONFLICT (jti) DO NOTHING"
    )
    try
      st.setString(1, jti)
      st.setTimestamp(2, java.sql.Timestamp.from(expiresAt))
      st.executeUpdate()
    finally st.close()
  }

  override def listRevokedJti(): List[(String, Instant)] = withConn { c =>
    val st = c.prepareStatement("SELECT jti, expires_at FROM qodstate_revoked_jti")
    try
      val rs  = st.executeQuery()
      val buf = scala.collection.mutable.ListBuffer.empty[(String, Instant)]
      while rs.next() do buf += ((rs.getString(1), rs.getTimestamp(2).toInstant))
      buf.toList
    finally st.close()
  }

  override def purgeExpiredRevokedJti(now: Instant): Unit = withConn { c =>
    val st = c.prepareStatement("DELETE FROM qodstate_revoked_jti WHERE expires_at < ?")
    try
      st.setTimestamp(1, java.sql.Timestamp.from(now))
      st.executeUpdate()
    finally st.close()
  }

  override def notifyListeners(channel: String, payload: String): Unit =
    try
      withConn { c =>
        val st = c.prepareStatement("SELECT pg_notify(?, ?)")
        try
          st.setString(1, channel)
          st.setString(2, payload)
          st.executeQuery()
        finally st.close()
      }
    catch
      case t: Throwable =>
        // Best effort: peers heal via the periodic refresh fallback.
        logger.warn(s"pg_notify($channel) failed: ${t.getMessage}")

  override def ping(): Boolean =
    try
      withConn { c =>
        val st = c.prepareStatement("SELECT 1")
        try st.executeQuery().next()
        finally st.close()
      }
    catch case _: Throwable => false

  // ---------------- Snapshot ----------------

  def snapshot(): ControlPlaneSnapshot = withConn { c =>
    ControlPlaneSnapshot(
      tenants = selectAll(c, SelectTenants, readTenant),
      tenantDbs = selectAll(
        c,
        "SELECT id, tenant_id, name, metastore_params, data_path, object_store_params, disabled, kind, default_database, default_schema, init_sql, branch_of, encrypted FROM qodstate_tenant_db ORDER BY name",
        readTenantDb
      ),
      pools = selectAll(
        c,
        "SELECT id, tenant_id, tenant_db_id, name, size, dist_writeonly, dist_readonly, dist_dual, max_concurrent_per_node, idle_timeout_sec, disabled, cohorts, init_sql, cpu, memory, pod_template_yaml, suspended, lockdown, min_nodes, max_nodes FROM qodstate_pool ORDER BY name",
        readPool
      ),
      nodes = selectAll(
        c,
        """SELECT n.node_id, n.host, n.port, n.token, n.role,
          |       n.pid, n.pod_name, n.started_at, n.last_seen, n.max_concurrent, n.server_name,
          |       t.id           AS tenant_id,
          |       td.name        AS tenant_db_name,
          |       p.name         AS pool_name
          |FROM qodstate_node n
          |JOIN qodstate_pool p       ON p.id  = n.pool_id
          |JOIN qodstate_tenant_db td ON td.id = p.tenant_db_id
          |JOIN qodstate_tenant t     ON t.id  = td.tenant_id
          |ORDER BY n.node_id""".stripMargin,
        readNode
      ),
      users = selectAll(
        c,
        "SELECT id, tenant, username, kind, enabled, must_change_password, email, created_at, updated_at, external_id FROM qodstate_user ORDER BY COALESCE(tenant, ''), username",
        readRbacUser
      ),
      roles = selectAll(
        c,
        "SELECT id, tenant_id, name, description, created_at, builtin FROM qodstate_role ORDER BY tenant_id, name",
        readRole
      ),
      rolePermissions = selectAll(
        c,
        "SELECT id, role_id, catalog_name, schema_name, table_name, verb, granted_at FROM qodstate_role_permission ORDER BY role_id, catalog_name, schema_name, table_name, verb",
        readRolePermission
      ),
      groups = selectAll(
        c,
        "SELECT id, tenant_id, name, description, external_id, builtin FROM qodstate_group ORDER BY tenant_id, name",
        readGroup
      ),
      userGroups = selectAll(
        c,
        "SELECT user_id, group_id FROM qodstate_user_group  ORDER BY user_id, group_id",
        rs => UserGroupEdge(rs.getString(1), rs.getString(2))
      ),
      userRoles = selectAll(
        c,
        "SELECT user_id, role_id  FROM qodstate_user_role   ORDER BY user_id, role_id",
        rs => UserRoleEdge(rs.getString(1), rs.getString(2))
      ),
      groupRoles = selectAll(
        c,
        "SELECT group_id, role_id FROM qodstate_group_role  ORDER BY group_id, role_id",
        rs => GroupRoleEdge(rs.getString(1), rs.getString(2))
      ),
      poolPermissions = selectAll(
        c,
        "SELECT id, tenant_id, pool_id, user_id, group_id, granted_at FROM qodstate_pool_permission ORDER BY id",
        readPoolPermission
      ),
      columnPolicies = selectAll(
        c,
        "SELECT id, role_id, catalog_name, schema_name, table_name, column_name, action, transform_sql FROM qodstate_role_column_policy ORDER BY id",
        readColumnPolicy
      ),
      rowPolicies = selectAll(
        c,
        "SELECT id, role_id, catalog_name, schema_name, table_name, predicate_sql FROM qodstate_role_row_policy ORDER BY id",
        readRowPolicy
      )
    )
  }

  // ---------------- Fleet ----------------

  override def recordHeartbeat(hb: Heartbeat): HeartbeatOutcome = withConn { c =>
    // One transaction, so a concurrent `delete(name)` cannot interleave between the statements
    // below and leave a heartbeat pointing at a vanished server. INSERT ... ON CONFLICT DO
    // NOTHING and a plain SELECT take no lock on an existing server row; step 2b's approval
    // UPDATE does lock the row, but only while it is still pending (AND NOT approved), and a
    // concurrent claim's FOR UPDATE OF s SKIP LOCKED already ignores pending rows, so it never
    // skips over one for that reason. A row deleted and committed between our statements (READ
    // COMMITTED: each statement takes a fresh snapshot) shows up as an empty SELECT or an FK
    // violation on the heartbeat insert; either rolls back and retries once, and the retry's
    // INSERT re-creates the row (a re-join).
    c.setAutoCommit(false)
    try
      def attempt(retriesLeft: Int): HeartbeatOutcome =
        val outcome =
          try heartbeatOnce(c, hb)
          catch
            case e: java.sql.SQLException if e.getSQLState == "23503" && retriesLeft > 0 =>
              None // FK violation: the server row vanished under us
        outcome match
          case Some(o) =>
            c.commit()
            o
          case None if retriesLeft > 0 =>
            c.rollback()
            attempt(retriesLeft - 1)
          case None =>
            throw new IllegalStateException(
              s"fleet server '${hb.name}' vanished twice while recording its heartbeat"
            )
      attempt(1)
    catch
      case t: Throwable =>
        // A failing rollback (connection already broken) must not hide the original error.
        scala.util.Try(c.rollback())
        throw t
    finally c.setAutoCommit(true)
  }

  /** One heartbeat pass inside the caller's transaction. `None` when the server row vanished
    * between the INSERT-or-nothing and the SELECT (a concurrent delete committed in between).
    */
  private def heartbeatOnce(c: Connection, hb: Heartbeat): Option[HeartbeatOutcome] =
    // 1. First contact: insert the server row; a no-op for a known name.
    val ins = c.prepareStatement(
      """INSERT INTO qodstate_fleet_server (name, advertise_host, node_port, approved)
        |VALUES (?, ?, ?, false) ON CONFLICT (name) DO NOTHING""".stripMargin
    )
    val joined =
      try
        ins.setString(1, hb.name)
        ins.setString(2, hb.advertiseHost)
        ins.setInt(3, hb.nodePort)
        ins.executeUpdate() == 1
      finally ins.close()
    // 2. Address guard and stale-rule inputs, read from the server row (plain read, no lock).
    val sel = c.prepareStatement(
      """SELECT advertise_host, node_port, unschedulable, assignment_epoch, assigned_node_id,
        |  approved, approved_source
        |FROM qodstate_fleet_server WHERE name = ?""".stripMargin
    )
    val current =
      try
        sel.setString(1, hb.name)
        val rs = sel.executeQuery()
        try
          if rs.next() then
            Some(
              (
                rs.getString(1),
                rs.getInt(2),
                rs.getBoolean(3),
                rs.getLong(4),
                Option(rs.getString(5)),
                rs.getBoolean(6),
                Option(rs.getString(7))
              )
            )
          else None
        finally rs.close()
      finally sel.close()
    current.map { case (host, port, drained, rowEpoch, assignedNodeId, approved, approvedSource) =>
      val readdress = !joined && (host != hb.advertiseHost || port != hb.nodePort)
      // Source binding (Liquibase 0042): an approved row bound to another source than this
      // heartbeat's (an unknown one included). Accepted only when the new source is itself in
      // QOD_FLEET_AUTO_APPROVE (rebind) or the server is drained and unassigned (approval reset,
      // judged again); refused otherwise, with nothing written.
      val sourceMoved = approved && approvedSource.isDefined && approvedSource != hb.sourceAddr
      val idleDrained = drained && assignedNodeId.isEmpty
      // An approved row bound to no source (the 0042 upgrade backfill) binds only to a known
      // source inside QOD_FLEET_AUTO_APPROVE: whoever heartbeats first with the name must not
      // inherit the approval. From anywhere else it is refused, unless drained and unassigned
      // (approval reset, judged again).
      val unbound = approved && approvedSource.isEmpty
      // The isDefined half is defense in depth; Heartbeat already refuses autoApprove without a
      // source.
      val trustedBind = hb.sourceAddr.isDefined && hb.autoApprove
      // A re-address needs a drained server that holds no assignment. Drain flips unschedulable
      // THEN releases; a heartbeat landing in between must not move (and un-approve) a server
      // that still holds a node. The legitimate re-home retries on the next heartbeat.
      if readdress && !idleDrained then HeartbeatOutcome.AddressChangeRefused
      else if sourceMoved && !hb.autoApprove && !idleDrained then
        HeartbeatOutcome.SourceChangeRefused
      else if !readdress && unbound && !trustedBind && !idleDrained then
        HeartbeatOutcome.ApprovalUnbound
      // 2a. Accepted re-address (drained, unassigned server, new host or port): the approval was
      // granted to the old address, so it is reset here and the new address is judged by step 2b
      // on this same heartbeat. Otherwise a token holder outside QOD_FLEET_AUTO_APPROVE could
      // take over a drained name and inherit its approval. The WHERE re-checks the guard: the
      // SELECT above took no lock, so an undrain landing in between is refused, not overwritten.
      else if readdress && !readdressDrained(c, hb) then HeartbeatOutcome.AddressChangeRefused
      // 2c. Source binding of an approved row that keeps its address (see sourceMoved above).
      // Each UPDATE re-checks what the unlocked SELECT saw; a row that changed in between is
      // refused (the server retries on its next heartbeat) rather than bound on a stale read.
      else if !readdress && approved && !bindSource(c, hb, approvedSource, sourceMoved) then
        if unbound then HeartbeatOutcome.ApprovalUnbound else HeartbeatOutcome.SourceChangeRefused
      else
        // 2b. Approval: a server not approved yet is approved when the caller judged this
        // heartbeat's source to be in QOD_FLEET_AUTO_APPROVE; an approved one is never re-judged
        // (a re-address reset by step 2a counts as not approved yet).
        if hb.autoApprove then
          val ap = c.prepareStatement(
            """UPDATE qodstate_fleet_server SET approved = true, approved_at = now(),
              |  approved_by = 'auto', approved_source = ? WHERE name = ? AND NOT approved""".stripMargin
          )
          try
            setNullable(ap, 1, hb.sourceAddr)
            ap.setString(2, hb.name)
            ap.executeUpdate()
            ()
          finally ap.close()
        // 3. Heartbeat row: the only row this handler rewrites every interval.
        val up = c.prepareStatement(
          """INSERT INTO qodstate_fleet_heartbeat
            |  (name, last_heartbeat_at, qod_version, os, duckdb_version, cpus, memory_bytes,
            |   node_state, node_error, node_pid, node_started_at, source_addr, node_action)
            |VALUES (?, now(), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            |ON CONFLICT (name) DO UPDATE SET
            |  last_heartbeat_at = now(), qod_version = EXCLUDED.qod_version, os = EXCLUDED.os,
            |  duckdb_version = EXCLUDED.duckdb_version, cpus = EXCLUDED.cpus, memory_bytes = EXCLUDED.memory_bytes,
            |  node_state = EXCLUDED.node_state, node_error = EXCLUDED.node_error, node_pid = EXCLUDED.node_pid,
            |  node_started_at = EXCLUDED.node_started_at, source_addr = EXCLUDED.source_addr,
            |  node_action = EXCLUDED.node_action""".stripMargin
        )
        try
          up.setString(1, hb.name)
          setNullable(up, 2, hb.qodVersion); setNullable(up, 3, hb.os);
          setNullable(up, 4, hb.duckdbVersion)
          setNullableInt(up, 5, hb.cpus)
          setNullableLong(up, 6, hb.memoryBytes)
          up.setString(7, FleetServerStore.effectiveState(hb.node, rowEpoch, assignedNodeId))
          setNullable(up, 8, hb.node.error); setNullableLong(up, 9, hb.node.pid);
          setNullableInstant(up, 10, hb.node.startedAt)
          setNullable(up, 11, hb.sourceAddr)
          setNullable(up, 12, hb.node.action)
          up.executeUpdate()
        finally up.close()
        if joined then HeartbeatOutcome.Joined else HeartbeatOutcome.Updated
    }

  /** Step 2c of [[heartbeatOnce]], for an approved row that keeps its address. `bound` and `moved`
    * are what the unlocked SELECT saw. A source move inside QOD_FLEET_AUTO_APPROVE rebinds; any
    * other move (the caller only lets a drained, unassigned row through) resets approval, so step
    * 2b judges the new source. An unbound row binds to a known source inside the list; from
    * anywhere else (the caller only lets a drained, unassigned row through) its approval is reset
    * the same way. No UPDATE runs (and no row lock is taken) on a plain heartbeat from the bound
    * source. False when the row no longer matches the read (e.g. a concurrent bind won).
    */
  private def bindSource(
      c: Connection,
      hb: Heartbeat,
      bound: Option[String],
      moved: Boolean
  ): Boolean =
    def run(sql: String)(bind: PreparedStatement => Unit): Boolean =
      val ps = c.prepareStatement(sql)
      try { bind(ps); ps.executeUpdate() == 1 }
      finally ps.close()
    if moved && hb.autoApprove then
      run(
        """UPDATE qodstate_fleet_server SET approved_source = ?
          |WHERE name = ? AND approved AND approved_source = ?""".stripMargin
      ) { ps =>
        setNullable(ps, 1, hb.sourceAddr); ps.setString(2, hb.name); ps.setString(3, bound.get)
      }
    else if moved then
      run(
        """UPDATE qodstate_fleet_server SET
          |  approved = false, approved_at = NULL, approved_by = NULL, approved_source = NULL
          |WHERE name = ? AND approved AND approved_source = ? AND unschedulable
          |  AND assigned_node_id IS NULL""".stripMargin
      ) { ps => ps.setString(1, hb.name); ps.setString(2, bound.get) }
    else if bound.isEmpty && hb.sourceAddr.isDefined && hb.autoApprove then
      run(
        """UPDATE qodstate_fleet_server SET approved_source = ?
          |WHERE name = ? AND approved AND approved_source IS NULL""".stripMargin
      ) { ps => ps.setString(1, hb.sourceAddr.get); ps.setString(2, hb.name) }
    else if bound.isEmpty then
      run(
        """UPDATE qodstate_fleet_server SET
          |  approved = false, approved_at = NULL, approved_by = NULL, approved_source = NULL
          |WHERE name = ? AND approved AND approved_source IS NULL AND unschedulable
          |  AND assigned_node_id IS NULL""".stripMargin
      )(ps => ps.setString(1, hb.name))
    else true

  /** Step 2a of [[heartbeatOnce]]: move a drained, unassigned server to the heartbeat's address and
    * reset its approval. False when the row no longer qualifies.
    */
  private def readdressDrained(c: Connection, hb: Heartbeat): Boolean =
    val addr = c.prepareStatement(
      """UPDATE qodstate_fleet_server SET advertise_host = ?, node_port = ?,
        |  approved = false, approved_at = NULL, approved_by = NULL, approved_source = NULL
        |WHERE name = ? AND unschedulable AND assigned_node_id IS NULL""".stripMargin
    )
    try
      addr.setString(1, hb.advertiseHost)
      addr.setInt(2, hb.nodePort)
      addr.setString(3, hb.name)
      addr.executeUpdate() == 1
    finally addr.close()

  override def claim(
      a: FleetAssignment,
      reachableWithinSec: Int,
      requiredMemoryBytes: Option[Long]
  ): Either[ClaimMiss, FleetServerRow] =
    inFleetTx { c =>
      val result = claimIn(c, a, reachableWithinSec, requiredMemoryBytes)
      c.commit()
      result
    }

  override def claimReplacing(
      a: FleetAssignment,
      reachableWithinSec: Int,
      requiredMemoryBytes: Option[Long]
  ): Either[ClaimMiss, FleetServerRow] =
    inFleetTx { c =>
      // Release first, inside the transaction: the released holder's row is locked by this
      // transaction (SKIP LOCKED never skips our own locks) and reads as unassigned, so a
      // reachable, schedulable holder is re-claimed in place under a new epoch.
      releaseIn(c, a.nodeId)
      val result = claimIn(c, a, reachableWithinSec, requiredMemoryBytes)
      // No replacement: roll back so the old holder keeps its assignment, epoch and claim.
      if result.isLeft then c.rollback() else c.commit()
      result
    }

  /** Runs `f` on one connection with autocommit off; `f` commits or rolls back itself. Any throw
    * rolls back.
    */
  private def inFleetTx[A](f: Connection => A): A =
    withConn { c =>
      c.setAutoCommit(false)
      try f(c)
      catch
        case t: Throwable =>
          // A failing rollback (connection already broken) must not hide the original error.
          scala.util.Try(c.rollback())
          throw t
      finally c.setAutoCommit(true)
    }

  /** The claim itself, on the caller's connection and transaction. */
  private def claimIn(
      c: Connection,
      a: FleetAssignment,
      reachableWithinSec: Int,
      requiredMemoryBytes: Option[Long]
  ): Either[ClaimMiss, FleetServerRow] =
    val pick = c.prepareStatement(
      """SELECT s.name, s.assignment_epoch, s.node_port FROM qodstate_fleet_server s
        |JOIN qodstate_fleet_heartbeat h USING (name)
        |WHERE s.approved AND s.assigned_node_id IS NULL AND NOT s.unschedulable
        |  AND h.last_heartbeat_at > now() - make_interval(secs => ?)
        |  AND (? IS NULL OR h.memory_bytes IS NULL OR h.memory_bytes >= ?)
        |ORDER BY s.joined_at LIMIT 1 FOR UPDATE OF s SKIP LOCKED""".stripMargin
    )
    pick.setInt(1, reachableWithinSec)
    requiredMemoryBytes match
      case Some(b) => pick.setLong(2, b); pick.setLong(3, b)
      case None    => pick.setNull(2, Types.BIGINT); pick.setNull(3, Types.BIGINT)
    val rs     = pick.executeQuery()
    val chosen =
      if rs.next() then Some((rs.getString(1), rs.getLong(2), rs.getInt(3))) else None
    rs.close(); pick.close()
    chosen match
      case Some((name, epoch0, nodePort)) =>
        val epoch = epoch0 + 1
        val upd   = c.prepareStatement(
          """UPDATE qodstate_fleet_server SET assigned_node_id = ?, assignment = ?::jsonb,
            |  assignment_epoch = ?, claimed_at = now() WHERE name = ?""".stripMargin
        )
        upd.setString(1, a.nodeId)
        // Stamp the server's own node_port so the join process never has to remember it.
        upd.setString(2, a.copy(epoch = epoch, port = nodePort).asJson.noSpaces)
        upd.setLong(3, epoch); upd.setString(4, name)
        upd.executeUpdate(); upd.close()
        Right(readFleetRow(c, name).get)
      case None =>
        // Distinguish "nothing free" from "nothing fits". Plain read, no SKIP LOCKED, so rows
        // a concurrent claim holds locked are counted: if one of them fits it was merely
        // taken, and the honest answer is NoneFree. NoneFits only when free servers exist and
        // none of them, locked or not, has the memory.
        val any = c.prepareStatement(
          """SELECT count(*) AS free,
            |  count(*) FILTER (WHERE ? IS NULL OR h.memory_bytes IS NULL OR h.memory_bytes >= ?)
            |    AS fitting
            |FROM qodstate_fleet_server s JOIN qodstate_fleet_heartbeat h USING (name)
            |WHERE s.approved AND s.assigned_node_id IS NULL AND NOT s.unschedulable
            |  AND h.last_heartbeat_at > now() - make_interval(secs => ?)""".stripMargin
        )
        requiredMemoryBytes match
          case Some(b) => any.setLong(1, b); any.setLong(2, b)
          case None    => any.setNull(1, Types.BIGINT); any.setNull(2, Types.BIGINT)
        any.setInt(3, reachableWithinSec)
        val r2 = any.executeQuery()
        r2.next()
        val (free, fitting) = (r2.getLong("free"), r2.getLong("fitting"))
        r2.close(); any.close()
        Left(if free > 0 && fitting == 0 then ClaimMiss.NoneFits else ClaimMiss.NoneFree)

  override def setAssignment(name: String, a: FleetAssignment): Unit = withConn { c =>
    val ps =
      c.prepareStatement("UPDATE qodstate_fleet_server SET assignment = ?::jsonb WHERE name = ?")
    try { ps.setString(1, a.asJson.noSpaces); ps.setString(2, name); ps.executeUpdate(); () }
    finally ps.close()
  }

  override def release(nodeId: String): Option[String] = withConn(c => releaseIn(c, nodeId))

  private def releaseIn(c: Connection, nodeId: String): Option[String] =
    val ps = c.prepareStatement(
      """UPDATE qodstate_fleet_server SET assigned_node_id = NULL, assignment = NULL, claimed_at = NULL,
        |  assignment_epoch = assignment_epoch + 1 WHERE assigned_node_id = ? RETURNING name""".stripMargin
    )
    try
      ps.setString(1, nodeId)
      val rs = ps.executeQuery()
      try if rs.next() then Some(rs.getString(1)) else None
      finally rs.close()
    finally ps.close()

  override def get(name: String): Option[FleetServerRow] = withConn(c => readFleetRow(c, name))

  override def list(): List[FleetServerRow] = withConn { c =>
    val ps = c.prepareStatement(FleetSelect + " ORDER BY s.joined_at, s.name")
    try
      val rs = ps.executeQuery()
      try drain(rs)(fleetRowOf)
      finally rs.close()
    finally ps.close()
  }

  override def byNodeId(nodeId: String): Option[FleetServerRow] = withConn { c =>
    val ps = c.prepareStatement(FleetSelect + " WHERE s.assigned_node_id = ?")
    try
      ps.setString(1, nodeId)
      val rs = ps.executeQuery()
      try if rs.next() then Some(fleetRowOf(rs)) else None
      finally rs.close()
    finally ps.close()
  }

  override def setUnschedulable(name: String, value: Boolean): Boolean = withConn { c =>
    val ps = c.prepareStatement("UPDATE qodstate_fleet_server SET unschedulable = ? WHERE name = ?")
    try { ps.setBoolean(1, value); ps.setString(2, name); ps.executeUpdate() == 1 }
    finally ps.close()
  }

  override def approve(name: String, by: String): ApproveResult = inFleetTx { c =>
    // The server row is locked for the whole decision, so `approved` cannot change under it.
    val sel = c.prepareStatement(
      "SELECT approved FROM qodstate_fleet_server WHERE name = ? FOR UPDATE"
    )
    val approved =
      try
        sel.setString(1, name)
        val rs = sel.executeQuery()
        try Option.when(rs.next())(rs.getBoolean(1))
        finally rs.close()
      finally sel.close()
    val result = approved match
      case None => ApproveResult.NotFound
      // An approved server keeps its first approver, time and source binding.
      case Some(true)  => ApproveResult.AlreadyApproved
      case Some(false) =>
        // Binds the latest heartbeat's source, read by this same statement (no gap between the
        // read and the write). An unknown source is refused: an unbound approval would bind to
        // whoever heartbeats first.
        val up = c.prepareStatement(
          """UPDATE qodstate_fleet_server s SET approved = true, approved_at = now(),
            |  approved_by = ?, approved_source = h.source_addr
            |FROM qodstate_fleet_heartbeat h
            |WHERE s.name = ? AND h.name = s.name AND NOT s.approved
            |  AND h.source_addr IS NOT NULL""".stripMargin
        )
        try
          up.setString(1, by); up.setString(2, name)
          if up.executeUpdate() == 1 then ApproveResult.Approved else ApproveResult.SourceUnknown
        finally up.close()
    c.commit()
    result
  }

  override def delete(name: String): Boolean = withConn { c =>
    val ps = c.prepareStatement("DELETE FROM qodstate_fleet_server WHERE name = ?")
    try { ps.setString(1, name); ps.executeUpdate() == 1 }
    finally ps.close()
  }

  private val FleetSelect =
    """SELECT s.name, s.advertise_host, s.node_port, s.joined_at, s.unschedulable, s.assigned_node_id,
      |  s.assignment::text AS assignment, s.assignment_epoch, s.claimed_at,
      |  s.approved, s.approved_at, s.approved_by, s.approved_source, h.source_addr,
      |  EXTRACT(EPOCH FROM now() - s.claimed_at)::bigint AS claim_age_seconds,
      |  h.last_heartbeat_at, EXTRACT(EPOCH FROM now() - h.last_heartbeat_at)::bigint AS silent_seconds,
      |  h.qod_version, h.os, h.duckdb_version, h.cpus, h.memory_bytes,
      |  h.node_state, h.node_error, h.node_pid, h.node_started_at, h.node_action
      |FROM qodstate_fleet_server s JOIN qodstate_fleet_heartbeat h USING (name)""".stripMargin

  private def readFleetRow(c: Connection, name: String): Option[FleetServerRow] =
    val ps = c.prepareStatement(FleetSelect + " WHERE s.name = ?")
    try
      ps.setString(1, name)
      val rs = ps.executeQuery()
      try if rs.next() then Some(fleetRowOf(rs)) else None
      finally rs.close()
    finally ps.close()

  private def fleetRowOf(rs: ResultSet): FleetServerRow =
    FleetServerRow(
      name = rs.getString("name"),
      advertiseHost = rs.getString("advertise_host"),
      nodePort = rs.getInt("node_port"),
      joinedAt = rs.getTimestamp("joined_at").toInstant,
      unschedulable = rs.getBoolean("unschedulable"),
      assignedNodeId = Option(rs.getString("assigned_node_id")),
      assignment = Option(rs.getString("assignment")).map(str =>
        io.circe.parser
          .decode[FleetAssignment](str)
          .fold(e => throw new IllegalStateException(s"bad assignment json: $e"), identity)
      ),
      assignmentEpoch = rs.getLong("assignment_epoch"),
      claimedAt = Option(rs.getTimestamp("claimed_at")).map(_.toInstant),
      lastHeartbeatAt = rs.getTimestamp("last_heartbeat_at").toInstant,
      silentSeconds = rs.getLong("silent_seconds"),
      claimAgeSeconds =
        Option(rs.getObject("claim_age_seconds")).map(_.asInstanceOf[Number].longValue),
      qodVersion = Option(rs.getString("qod_version")),
      os = Option(rs.getString("os")),
      duckdbVersion = Option(rs.getString("duckdb_version")),
      cpus = Option(rs.getObject("cpus")).map(_.asInstanceOf[Number].intValue),
      memoryBytes = Option(rs.getObject("memory_bytes")).map(_.asInstanceOf[Number].longValue),
      nodeState = rs.getString("node_state"),
      nodeError = Option(rs.getString("node_error")),
      nodePid = Option(rs.getObject("node_pid")).map(_.asInstanceOf[Number].longValue),
      nodeStartedAt = Option(rs.getTimestamp("node_started_at")).map(_.toInstant),
      approved = rs.getBoolean("approved"),
      approvedAt = Option(rs.getTimestamp("approved_at")).map(_.toInstant),
      approvedBy = Option(rs.getString("approved_by")),
      sourceAddr = Option(rs.getString("source_addr")),
      approvedSource = Option(rs.getString("approved_source")),
      nodeAction = Option(rs.getString("node_action"))
    )

  // ---------------- helpers ----------------

  private def selectAll[A](c: Connection, sql: String, read: ResultSet => A): List[A] =
    val ps = c.prepareStatement(sql)
    try
      val rs = ps.executeQuery()
      try drain(rs)(read)
      finally rs.close()
    finally ps.close()

  // ---- snapshot tags ----

  private def readSnapshotTag(rs: java.sql.ResultSet): SnapshotTag =
    SnapshotTag(
      id = rs.getString("id"),
      tenant = rs.getString("tenant"),
      tenantDb = rs.getString("tenant_db"),
      name = rs.getString("name"),
      snapshotId = rs.getLong("snapshot_id"),
      isProtected = rs.getBoolean("protected"),
      createdBy = Option(rs.getString("created_by")),
      createdAt = Option(rs.getTimestamp("created_at")).map(_.toInstant)
    )

  def createSnapshotTag(t: SnapshotTag): Either[String, SnapshotTag] = withConn { c =>
    // ON CONFLICT DO NOTHING + RETURNING: zero rows back means the unique
    // (tenant, tenant_db, name) constraint fired -- race-free duplicate detection.
    val ps = c.prepareStatement(
      """INSERT INTO qodstate_snapshot_tag
        |  (id, tenant, tenant_db, name, snapshot_id, protected, created_by)
        |VALUES (?, ?, ?, ?, ?, ?, ?)
        |ON CONFLICT (tenant, tenant_db, name) DO NOTHING
        |RETURNING created_at""".stripMargin
    )
    try
      ps.setString(1, t.id)
      ps.setString(2, t.tenant)
      ps.setString(3, t.tenantDb)
      ps.setString(4, t.name)
      ps.setLong(5, t.snapshotId)
      ps.setBoolean(6, t.isProtected)
      setNullable(ps, 7, t.createdBy)
      val rs = ps.executeQuery()
      try
        if rs.next() then Right(t.copy(createdAt = Some(rs.getTimestamp(1).toInstant)))
        else Left("duplicate")
      finally rs.close()
    finally ps.close()
  }

  def deleteSnapshotTag(tenant: String, tenantDb: String, name: String): Option[SnapshotTag] =
    withConn { c =>
      val ps = c.prepareStatement(
        """DELETE FROM qodstate_snapshot_tag
          |WHERE tenant = ? AND tenant_db = ? AND name = ?
          |RETURNING id, tenant, tenant_db, name, snapshot_id, protected, created_by, created_at""".stripMargin
      )
      try
        ps.setString(1, tenant); ps.setString(2, tenantDb); ps.setString(3, name)
        val rs = ps.executeQuery()
        try if rs.next() then Some(readSnapshotTag(rs)) else None
        finally rs.close()
      finally ps.close()
    }

  def setSnapshotTagProtected(
      tenant: String,
      tenantDb: String,
      name: String,
      isProtected: Boolean
  ): Option[SnapshotTag] = withConn { c =>
    val ps = c.prepareStatement(
      """UPDATE qodstate_snapshot_tag SET protected = ?
        |WHERE tenant = ? AND tenant_db = ? AND name = ?
        |RETURNING id, tenant, tenant_db, name, snapshot_id, protected, created_by, created_at""".stripMargin
    )
    try
      ps.setBoolean(1, isProtected)
      ps.setString(2, tenant); ps.setString(3, tenantDb); ps.setString(4, name)
      val rs = ps.executeQuery()
      try if rs.next() then Some(readSnapshotTag(rs)) else None
      finally rs.close()
    finally ps.close()
  }

  def listSnapshotTags(tenant: String, tenantDb: String): List[SnapshotTag] = withConn { c =>
    val ps = c.prepareStatement(
      """SELECT id, tenant, tenant_db, name, snapshot_id, protected, created_by, created_at
        |FROM qodstate_snapshot_tag WHERE tenant = ? AND tenant_db = ? ORDER BY name""".stripMargin
    )
    try
      ps.setString(1, tenant); ps.setString(2, tenantDb)
      val rs = ps.executeQuery()
      try drain(rs)(readSnapshotTag)
      finally rs.close()
    finally ps.close()
  }

  def findSnapshotTag(tenant: String, tenantDb: String, name: String): Option[SnapshotTag] =
    withConn { c =>
      val ps = c.prepareStatement(
        """SELECT id, tenant, tenant_db, name, snapshot_id, protected, created_by, created_at
          |FROM qodstate_snapshot_tag WHERE tenant = ? AND tenant_db = ? AND name = ?""".stripMargin
      )
      try
        ps.setString(1, tenant); ps.setString(2, tenantDb); ps.setString(3, name)
        val rs = ps.executeQuery()
        try if rs.next() then Some(readSnapshotTag(rs)) else None
        finally rs.close()
      finally ps.close()
    }

  // ---- branches (Epic 1) ----

  private val BranchCols =
    "id, tenant, parent_db_id, parent_db_name, name, tenant_db_id, tenant_db_name, pool_name, " +
      "data_path, fork_snapshot, owner_user, status, expires_at, created_at, updated_at, purged_at"

  private def readBranch(rs: ResultSet): Branch =
    Branch(
      id = rs.getString("id"),
      tenant = rs.getString("tenant"),
      parentDbId = rs.getString("parent_db_id"),
      parentDbName = rs.getString("parent_db_name"),
      name = rs.getString("name"),
      tenantDbId = rs.getString("tenant_db_id"),
      tenantDbName = rs.getString("tenant_db_name"),
      poolName = rs.getString("pool_name"),
      dataPath = rs.getString("data_path"),
      forkSnapshot = rs.getLong("fork_snapshot"),
      ownerUser = rs.getString("owner_user"),
      status = BranchStatus
        .fromWire(rs.getString("status"))
        .fold(err => sys.error(s"qodstate_branch.status invalid ($err)"), identity),
      expiresAt = Option(rs.getTimestamp("expires_at")).map(_.toInstant),
      createdAt = Option(rs.getTimestamp("created_at")).map(_.toInstant),
      updatedAt = Option(rs.getTimestamp("updated_at")).map(_.toInstant),
      purgedAt = Option(rs.getTimestamp("purged_at")).map(_.toInstant)
    )

  private def setNullableInstant(ps: PreparedStatement, idx: Int, v: Option[Instant]): Unit =
    v match
      case Some(i) => ps.setTimestamp(idx, Timestamp.from(i))
      case None    => ps.setNull(idx, Types.TIMESTAMP_WITH_TIMEZONE)

  private def setNullableLong(ps: PreparedStatement, idx: Int, v: Option[Long]): Unit =
    v match
      case Some(l) => ps.setLong(idx, l)
      case None    => ps.setNull(idx, Types.BIGINT)

  def createBranch(b: Branch): Either[String, Branch] = withConn { c =>
    // The partial unique index (parent_db_id, name) WHERE status IN (live) fires as a plain
    // unique violation; ON CONFLICT cannot name a partial index without its predicate, so
    // detect the duplicate by catching the violation (SQLSTATE 23505).
    val ps = c.prepareStatement(
      s"""INSERT INTO qodstate_branch
         |  (id, tenant, parent_db_id, parent_db_name, name, tenant_db_id, tenant_db_name,
         |   pool_name, data_path, fork_snapshot, owner_user, status, expires_at)
         |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         |RETURNING $BranchCols""".stripMargin
    )
    try
      ps.setString(1, b.id)
      ps.setString(2, b.tenant)
      ps.setString(3, b.parentDbId)
      ps.setString(4, b.parentDbName)
      ps.setString(5, b.name)
      ps.setString(6, b.tenantDbId)
      ps.setString(7, b.tenantDbName)
      ps.setString(8, b.poolName)
      ps.setString(9, b.dataPath)
      ps.setLong(10, b.forkSnapshot)
      ps.setString(11, b.ownerUser)
      ps.setString(12, b.status.wire)
      setNullableInstant(ps, 13, b.expiresAt)
      val rs = ps.executeQuery()
      try
        if rs.next() then Right(readBranch(rs))
        else Left("duplicate")
      finally rs.close()
    catch case e: java.sql.SQLException if e.getSQLState == "23505" => Left("duplicate")
    finally ps.close()
  }

  def getBranch(id: String): Option[Branch] = withConn { c =>
    val ps = c.prepareStatement(s"SELECT $BranchCols FROM qodstate_branch WHERE id = ?")
    try
      ps.setString(1, id)
      val rs = ps.executeQuery()
      try if rs.next() then Some(readBranch(rs)) else None
      finally rs.close()
    finally ps.close()
  }

  def findBranch(parentDbId: String, name: String, liveOnly: Boolean): Option[Branch] =
    withConn { c =>
      val statusPred = if liveOnly then " AND status IN ('open', 'proposed')" else ""
      val ps         = c.prepareStatement(
        s"SELECT $BranchCols FROM qodstate_branch WHERE parent_db_id = ? AND name = ?$statusPred " +
          "ORDER BY created_at DESC LIMIT 1"
      )
      try
        ps.setString(1, parentDbId); ps.setString(2, name)
        val rs = ps.executeQuery()
        try if rs.next() then Some(readBranch(rs)) else None
        finally rs.close()
      finally ps.close()
    }

  private def statusPredicate(statuses: Set[BranchStatus]): String =
    if statuses.isEmpty then ""
    else statuses.toList.map(s => s"'${s.wire}'").sorted.mkString(" AND status IN (", ", ", ")")

  def listBranches(parentDbId: String, statuses: Set[BranchStatus]): List[Branch] = withConn { c =>
    val ps = c.prepareStatement(
      s"SELECT $BranchCols FROM qodstate_branch WHERE parent_db_id = ?${statusPredicate(statuses)} " +
        "ORDER BY created_at DESC, id DESC"
    )
    try
      ps.setString(1, parentDbId)
      val rs = ps.executeQuery()
      try drain(rs)(readBranch)
      finally rs.close()
    finally ps.close()
  }

  def listTenantBranches(tenant: String, statuses: Set[BranchStatus]): List[Branch] = withConn {
    c =>
      val ps = c.prepareStatement(
        s"SELECT $BranchCols FROM qodstate_branch WHERE tenant = ?${statusPredicate(statuses)} " +
          "ORDER BY created_at DESC, id DESC"
      )
      try
        ps.setString(1, tenant)
        val rs = ps.executeQuery()
        try drain(rs)(readBranch)
        finally rs.close()
      finally ps.close()
  }

  def updateBranch(b: Branch): Option[Branch] = withConn { c =>
    val ps = c.prepareStatement(
      s"""UPDATE qodstate_branch
         |SET status = ?, expires_at = ?, purged_at = ?, updated_at = NOW()
         |WHERE id = ?
         |RETURNING $BranchCols""".stripMargin
    )
    try
      ps.setString(1, b.status.wire)
      setNullableInstant(ps, 2, b.expiresAt)
      setNullableInstant(ps, 3, b.purgedAt)
      ps.setString(4, b.id)
      val rs = ps.executeQuery()
      try if rs.next() then Some(readBranch(rs)) else None
      finally rs.close()
    finally ps.close()
  }

  private val BranchMergeCols =
    "id, branch_id, proposer, approver, status, summary_json, conflicts_json, " +
      "main_snapshot_at_propose, main_snapshot_after, tag_name, error, created_at, decided_at"

  private def readBranchMerge(rs: ResultSet): BranchMerge =
    BranchMerge(
      id = rs.getString("id"),
      branchId = rs.getString("branch_id"),
      proposer = rs.getString("proposer"),
      approver = Option(rs.getString("approver")),
      status = BranchMergeStatus
        .fromWire(rs.getString("status"))
        .fold(err => sys.error(s"qodstate_branch_merge.status invalid ($err)"), identity),
      summaryJson = rs.getString("summary_json"),
      conflictsJson = rs.getString("conflicts_json"),
      mainSnapshotAtPropose = rs.getLong("main_snapshot_at_propose"),
      mainSnapshotAfter = {
        val v = rs.getLong("main_snapshot_after"); if rs.wasNull() then None else Some(v)
      },
      tagName = Option(rs.getString("tag_name")),
      error = Option(rs.getString("error")),
      createdAt = Option(rs.getTimestamp("created_at")).map(_.toInstant),
      decidedAt = Option(rs.getTimestamp("decided_at")).map(_.toInstant)
    )

  def createBranchMerge(m: BranchMerge): BranchMerge = withConn { c =>
    val ps = c.prepareStatement(
      s"""INSERT INTO qodstate_branch_merge
         |  (id, branch_id, proposer, approver, status, summary_json, conflicts_json,
         |   main_snapshot_at_propose, main_snapshot_after, tag_name, error, decided_at)
         |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         |RETURNING $BranchMergeCols""".stripMargin
    )
    try
      ps.setString(1, m.id)
      ps.setString(2, m.branchId)
      ps.setString(3, m.proposer)
      setNullable(ps, 4, m.approver)
      ps.setString(5, m.status.wire)
      ps.setString(6, m.summaryJson)
      ps.setString(7, m.conflictsJson)
      ps.setLong(8, m.mainSnapshotAtPropose)
      setNullableLong(ps, 9, m.mainSnapshotAfter)
      setNullable(ps, 10, m.tagName)
      setNullable(ps, 11, m.error)
      setNullableInstant(ps, 12, m.decidedAt)
      val rs = ps.executeQuery()
      try
        rs.next(); readBranchMerge(rs)
      finally rs.close()
    finally ps.close()
  }

  def getBranchMerge(id: String): Option[BranchMerge] = withConn { c =>
    val ps = c.prepareStatement(s"SELECT $BranchMergeCols FROM qodstate_branch_merge WHERE id = ?")
    try
      ps.setString(1, id)
      val rs = ps.executeQuery()
      try if rs.next() then Some(readBranchMerge(rs)) else None
      finally rs.close()
    finally ps.close()
  }

  def listBranchMerges(branchId: String): List[BranchMerge] = withConn { c =>
    val ps = c.prepareStatement(
      s"SELECT $BranchMergeCols FROM qodstate_branch_merge WHERE branch_id = ? " +
        "ORDER BY created_at DESC, id DESC"
    )
    try
      ps.setString(1, branchId)
      val rs = ps.executeQuery()
      try drain(rs)(readBranchMerge)
      finally rs.close()
    finally ps.close()
  }

  def updateBranchMerge(m: BranchMerge): Option[BranchMerge] = withConn { c =>
    val ps = c.prepareStatement(
      s"""UPDATE qodstate_branch_merge
         |SET status = ?, approver = ?, main_snapshot_after = ?, tag_name = ?, error = ?,
         |    decided_at = ?
         |WHERE id = ?
         |RETURNING $BranchMergeCols""".stripMargin
    )
    try
      ps.setString(1, m.status.wire)
      setNullable(ps, 2, m.approver)
      setNullableLong(ps, 3, m.mainSnapshotAfter)
      setNullable(ps, 4, m.tagName)
      setNullable(ps, 5, m.error)
      setNullableInstant(ps, 6, m.decidedAt)
      ps.setString(7, m.id)
      val rs = ps.executeQuery()
      try if rs.next() then Some(readBranchMerge(rs)) else None
      finally rs.close()
    finally ps.close()
  }

  // ---- maintenance (EPIC Spec 09) ----

  private def readMaintenancePolicy(rs: ResultSet): MaintenancePolicy =
    def optInt(col: String): Option[Int] =
      Option(rs.getObject(col)).map(_.asInstanceOf[Number].intValue)
    def optDouble(col: String): Option[Double] =
      Option(rs.getObject(col)).map(_.asInstanceOf[Number].doubleValue)
    MaintenancePolicy(
      id = rs.getString("id"),
      tenant = rs.getString("tenant"),
      tenantDb = rs.getString("tenant_db"),
      scopeKind = rs.getString("scope_kind"),
      scopeSchema = Option(rs.getString("scope_schema")),
      scopeTable = Option(rs.getString("scope_table")),
      enabled = Option(rs.getObject("enabled")).map(_.asInstanceOf[Boolean]),
      retentionDays = optInt("retention_days"),
      compactionEnabled = Option(rs.getObject("compaction_enabled")).map(_.asInstanceOf[Boolean]),
      targetFileSize = Option(rs.getString("target_file_size")),
      smallFileMinCount = optInt("small_file_min_count"),
      rewriteDeleteThreshold = optDouble("rewrite_delete_threshold"),
      cleanupGraceDays = optInt("cleanup_grace_days"),
      orphanMinAgeDays = optInt("orphan_min_age_days"),
      cron = Option(rs.getString("cron")),
      updatedAt = Option(rs.getTimestamp("updated_at")).map(_.toInstant)
    )

  private def setNullableInt(ps: PreparedStatement, idx: Int, v: Option[Int]): Unit =
    v match
      case Some(i) => ps.setInt(idx, i)
      case None    => ps.setNull(idx, Types.INTEGER)

  private def setNullableBoolean(ps: PreparedStatement, idx: Int, v: Option[Boolean]): Unit =
    v match
      case Some(b) => ps.setBoolean(idx, b)
      case None    => ps.setNull(idx, Types.BOOLEAN)

  private def setNullableDouble(ps: PreparedStatement, idx: Int, v: Option[Double]): Unit =
    v match
      case Some(d) => ps.setDouble(idx, d)
      case None    => ps.setNull(idx, Types.DOUBLE)

  /** Upsert on the (tenant, tenantDb, scopeKind, scopeSchema, scopeTable) scope tuple. The
    * expression index (`COALESCE(scope_schema, '')`) isn't usable in `ON CONFLICT`, so this is a
    * manual delete-then-insert in one transaction rather than a native upsert.
    */
  def upsertMaintenancePolicy(p: MaintenancePolicy): MaintenancePolicy = withConn { c =>
    c.setAutoCommit(false)
    try
      val del = c.prepareStatement(
        """DELETE FROM qodstate_maintenance_policy
          |WHERE tenant = ? AND tenant_db = ? AND scope_kind = ?
          |  AND COALESCE(scope_schema, '') = ? AND COALESCE(scope_table, '') = ?""".stripMargin
      )
      try
        del.setString(1, p.tenant)
        del.setString(2, p.tenantDb)
        del.setString(3, p.scopeKind)
        del.setString(4, p.scopeSchema.getOrElse(""))
        del.setString(5, p.scopeTable.getOrElse(""))
        del.executeUpdate()
      finally del.close()

      val ins = c.prepareStatement(
        """INSERT INTO qodstate_maintenance_policy
          |  (id, tenant, tenant_db, scope_kind, scope_schema, scope_table, enabled,
          |   retention_days, compaction_enabled, target_file_size, small_file_min_count,
          |   rewrite_delete_threshold, cleanup_grace_days, orphan_min_age_days, cron)
          |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          |RETURNING updated_at""".stripMargin
      )
      try
        ins.setString(1, p.id)
        ins.setString(2, p.tenant)
        ins.setString(3, p.tenantDb)
        ins.setString(4, p.scopeKind)
        setNullable(ins, 5, p.scopeSchema)
        setNullable(ins, 6, p.scopeTable)
        setNullableBoolean(ins, 7, p.enabled)
        setNullableInt(ins, 8, p.retentionDays)
        setNullableBoolean(ins, 9, p.compactionEnabled)
        setNullable(ins, 10, p.targetFileSize)
        setNullableInt(ins, 11, p.smallFileMinCount)
        setNullableDouble(ins, 12, p.rewriteDeleteThreshold)
        setNullableInt(ins, 13, p.cleanupGraceDays)
        setNullableInt(ins, 14, p.orphanMinAgeDays)
        setNullable(ins, 15, p.cron)
        val rs = ins.executeQuery()
        try
          rs.next()
          val updated = p.copy(updatedAt = Some(rs.getTimestamp(1).toInstant))
          c.commit()
          updated
        finally rs.close()
      finally ins.close()
    catch
      case e: Throwable =>
        c.rollback()
        throw e
    finally c.setAutoCommit(true)
  }

  def deleteMaintenancePolicy(id: String): Boolean = withConn { c =>
    val ps = c.prepareStatement("DELETE FROM qodstate_maintenance_policy WHERE id = ?")
    try
      ps.setString(1, id)
      ps.executeUpdate() > 0
    finally ps.close()
  }

  def findMaintenancePolicy(id: String): Option[MaintenancePolicy] = withConn { c =>
    val ps = c.prepareStatement(
      """SELECT id, tenant, tenant_db, scope_kind, scope_schema, scope_table, enabled,
        |       retention_days, compaction_enabled, target_file_size, small_file_min_count,
        |       rewrite_delete_threshold, cleanup_grace_days, orphan_min_age_days, cron, updated_at
        |FROM qodstate_maintenance_policy WHERE id = ?""".stripMargin
    )
    try
      ps.setString(1, id)
      val rs = ps.executeQuery()
      try if rs.next() then Some(readMaintenancePolicy(rs)) else None
      finally rs.close()
    finally ps.close()
  }

  def listMaintenancePolicies(tenant: String, tenantDb: String): List[MaintenancePolicy] =
    withConn { c =>
      val ps = c.prepareStatement(
        """SELECT id, tenant, tenant_db, scope_kind, scope_schema, scope_table, enabled,
          |       retention_days, compaction_enabled, target_file_size, small_file_min_count,
          |       rewrite_delete_threshold, cleanup_grace_days, orphan_min_age_days, cron, updated_at
          |FROM qodstate_maintenance_policy
          |WHERE tenant = ? AND tenant_db = ?
          |ORDER BY scope_kind, scope_schema, scope_table""".stripMargin
      )
      try
        ps.setString(1, tenant)
        ps.setString(2, tenantDb)
        val rs = ps.executeQuery()
        try drain(rs)(readMaintenancePolicy)
        finally rs.close()
      finally ps.close()
    }

  private val maintenanceRunColumns =
    """id, tenant, tenant_db, scope, trigger, operations, status, queued_at, started_at,
      |finished_at, heartbeat_at, node_id, snapshots_expired, snapshots_skipped_pinned,
      |files_merged, files_rewritten, files_cleaned, orphans_deleted, bytes_reclaimed,
      |error""".stripMargin

  private def readMaintenanceRun(rs: ResultSet): MaintenanceRun =
    MaintenanceRun(
      id = rs.getLong("id"),
      tenant = rs.getString("tenant"),
      tenantDb = rs.getString("tenant_db"),
      scope = rs.getString("scope"),
      trigger = rs.getString("trigger"),
      operations = Option(rs.getString("operations")),
      status = rs.getString("status"),
      queuedAt = rs.getTimestamp("queued_at").toInstant,
      startedAt = Option(rs.getTimestamp("started_at")).map(_.toInstant),
      finishedAt = Option(rs.getTimestamp("finished_at")).map(_.toInstant),
      heartbeatAt = Option(rs.getTimestamp("heartbeat_at")).map(_.toInstant),
      nodeId = Option(rs.getString("node_id")),
      counters = RunCounters(
        snapshotsExpired = rs.getInt("snapshots_expired"),
        snapshotsSkippedPinned = rs.getInt("snapshots_skipped_pinned"),
        filesMerged = rs.getInt("files_merged"),
        filesRewritten = rs.getInt("files_rewritten"),
        filesCleaned = rs.getInt("files_cleaned"),
        orphansDeleted = rs.getInt("orphans_deleted"),
        bytesReclaimed = rs.getLong("bytes_reclaimed")
      ),
      error = Option(rs.getString("error"))
    )

  def enqueueMaintenanceRun(
      tenant: String,
      tenantDb: String,
      scope: String,
      trigger: String,
      operations: Option[String]
  ): MaintenanceRun = withConn { c =>
    val ps = c.prepareStatement(
      s"""INSERT INTO qodstate_maintenance_run (tenant, tenant_db, scope, trigger, operations, status)
        |VALUES (?, ?, ?, ?, ?, 'queued')
        |RETURNING $maintenanceRunColumns""".stripMargin
    )
    try
      ps.setString(1, tenant)
      ps.setString(2, tenantDb)
      ps.setString(3, scope)
      ps.setString(4, trigger)
      setNullable(ps, 5, operations)
      val rs = ps.executeQuery()
      try
        rs.next()
        readMaintenanceRun(rs)
      finally rs.close()
    finally ps.close()
  }

  def claimQueuedMaintenanceRun(): Option[MaintenanceRun] = withConn { c =>
    val ps = c.prepareStatement(
      s"""UPDATE qodstate_maintenance_run
        |SET status = 'running', started_at = NOW(), heartbeat_at = NOW()
        |WHERE id = (
        |  SELECT id FROM qodstate_maintenance_run
        |  WHERE status = 'queued'
        |  ORDER BY id
        |  FOR UPDATE SKIP LOCKED
        |  LIMIT 1
        |)
        |RETURNING $maintenanceRunColumns""".stripMargin
    )
    try
      val rs = ps.executeQuery()
      try if rs.next() then Some(readMaintenanceRun(rs)) else None
      finally rs.close()
    finally ps.close()
  }

  def heartbeatMaintenanceRun(id: Long, counters: RunCounters): Boolean = withConn { c =>
    val ps = c.prepareStatement(
      """UPDATE qodstate_maintenance_run
        |SET heartbeat_at = NOW(), snapshots_expired = ?, snapshots_skipped_pinned = ?,
        |    files_merged = ?, files_rewritten = ?, files_cleaned = ?, orphans_deleted = ?,
        |    bytes_reclaimed = ?
        |WHERE id = ? AND status = 'running'""".stripMargin
    )
    try
      ps.setInt(1, counters.snapshotsExpired)
      ps.setInt(2, counters.snapshotsSkippedPinned)
      ps.setInt(3, counters.filesMerged)
      ps.setInt(4, counters.filesRewritten)
      ps.setInt(5, counters.filesCleaned)
      ps.setInt(6, counters.orphansDeleted)
      ps.setLong(7, counters.bytesReclaimed)
      ps.setLong(8, id)
      ps.executeUpdate() > 0
    finally ps.close()
  }

  /** Returns `false` (without writing) when the row was no longer `"running"` -- a concurrent sweep
    * already failed it out from under this caller; see the trait doc.
    */
  def finishMaintenanceRun(
      id: Long,
      status: String,
      counters: RunCounters,
      error: Option[String]
  ): Boolean = withConn { c =>
    val ps = c.prepareStatement(
      """UPDATE qodstate_maintenance_run
        |SET status = ?, finished_at = NOW(), snapshots_expired = ?, snapshots_skipped_pinned = ?,
        |    files_merged = ?, files_rewritten = ?, files_cleaned = ?, orphans_deleted = ?,
        |    bytes_reclaimed = ?, error = ?
        |WHERE id = ? AND status = 'running'""".stripMargin
    )
    try
      ps.setString(1, status)
      ps.setInt(2, counters.snapshotsExpired)
      ps.setInt(3, counters.snapshotsSkippedPinned)
      ps.setInt(4, counters.filesMerged)
      ps.setInt(5, counters.filesRewritten)
      ps.setInt(6, counters.filesCleaned)
      ps.setInt(7, counters.orphansDeleted)
      ps.setLong(8, counters.bytesReclaimed)
      setNullable(ps, 9, error)
      ps.setLong(10, id)
      ps.executeUpdate() > 0
    finally ps.close()
  }

  def listMaintenanceRuns(
      tenant: String,
      tenantDb: String,
      limit: Int,
      before: Option[Long]
  ): List[MaintenanceRun] = withConn { c =>
    val sql = before match
      case Some(_) =>
        s"""SELECT $maintenanceRunColumns FROM qodstate_maintenance_run
          |WHERE tenant = ? AND tenant_db = ? AND id < ?
          |ORDER BY id DESC LIMIT ?""".stripMargin
      case None =>
        s"""SELECT $maintenanceRunColumns FROM qodstate_maintenance_run
          |WHERE tenant = ? AND tenant_db = ?
          |ORDER BY id DESC LIMIT ?""".stripMargin
    val ps = c.prepareStatement(sql)
    try
      ps.setString(1, tenant)
      ps.setString(2, tenantDb)
      before match
        case Some(cursor) =>
          ps.setLong(3, cursor)
          ps.setInt(4, limit)
        case None =>
          ps.setInt(3, limit)
      val rs = ps.executeQuery()
      try drain(rs)(readMaintenanceRun)
      finally rs.close()
    finally ps.close()
  }

  def hasActiveMaintenanceRun(tenant: String, tenantDb: String): Boolean = withConn { c =>
    val ps = c.prepareStatement(
      """SELECT 1 FROM qodstate_maintenance_run
        |WHERE tenant = ? AND tenant_db = ? AND status IN ('queued', 'running') LIMIT 1""".stripMargin
    )
    try
      ps.setString(1, tenant)
      ps.setString(2, tenantDb)
      val rs = ps.executeQuery()
      try rs.next()
      finally rs.close()
    finally ps.close()
  }

  def lastNonManualMaintenanceRunAt(tenant: String, tenantDb: String): Option[java.time.Instant] =
    withConn { c =>
      val ps = c.prepareStatement(
        """SELECT max(queued_at) FROM qodstate_maintenance_run
          |WHERE tenant = ? AND tenant_db = ? AND trigger <> 'manual'""".stripMargin
      )
      try
        ps.setString(1, tenant)
        ps.setString(2, tenantDb)
        val rs = ps.executeQuery()
        try
          rs.next()
          Option(rs.getTimestamp(1)).map(_.toInstant)
        finally rs.close()
      finally ps.close()
    }

  def sweepStaleMaintenanceRuns(heartbeatOlderThan: java.time.Instant): Int = withConn { c =>
    val ps = c.prepareStatement(
      """UPDATE qodstate_maintenance_run
        |SET status = 'failed', finished_at = NOW(), error = 'stale: heartbeat timeout'
        |WHERE status = 'running' AND heartbeat_at < ?""".stripMargin
    )
    try
      ps.setTimestamp(1, Timestamp.from(heartbeatOlderThan))
      ps.executeUpdate()
    finally ps.close()
  }

  // ---------------- Managed prefix (tombstone registry) ------------------

  def insertManagedPrefix(
      id: String,
      tenant: String,
      tenantDbName: String,
      prefix: String,
      createdAt: Instant
  ): Unit = withConn { c =>
    val ps = c.prepareStatement(
      """INSERT INTO qodstate_managed_prefix (id, tenant, tenant_db_name, prefix, created_at)
        |VALUES (?, ?, ?, ?, ?)
        |ON CONFLICT (id) DO NOTHING""".stripMargin
    )
    try
      ps.setString(1, id)
      ps.setString(2, tenant)
      ps.setString(3, tenantDbName)
      ps.setString(4, prefix)
      ps.setTimestamp(5, Timestamp.from(createdAt))
      ps.executeUpdate()
      ()
    finally ps.close()
  }

  def markManagedPrefixDeleted(
      id: String,
      deletedAt: Instant,
      purgeEligibleAt: Instant
  ): Unit = withConn { c =>
    val ps = c.prepareStatement(
      """UPDATE qodstate_managed_prefix
        |SET deleted_at = ?, purge_eligible_at = ?
        |WHERE id = ?""".stripMargin
    )
    try
      ps.setTimestamp(1, Timestamp.from(deletedAt))
      ps.setTimestamp(2, Timestamp.from(purgeEligibleAt))
      ps.setString(3, id)
      ps.executeUpdate()
      ()
    finally ps.close()
  }

  def dueManagedPrefixes(now: Instant): List[ManagedPrefixRow] = withConn { c =>
    val ps = c.prepareStatement(
      """SELECT id, tenant, tenant_db_name, prefix, created_at, deleted_at, purge_eligible_at, purged_at
        |FROM qodstate_managed_prefix
        |WHERE purge_eligible_at <= ? AND purged_at IS NULL
        |ORDER BY purge_eligible_at ASC""".stripMargin
    )
    try
      ps.setTimestamp(1, Timestamp.from(now))
      val rs = ps.executeQuery()
      try drain(rs)(readManagedPrefixRow)
      finally rs.close()
    finally ps.close()
  }

  def markManagedPrefixPurged(id: String, purgedAt: Instant): Unit = withConn { c =>
    val ps = c.prepareStatement(
      "UPDATE qodstate_managed_prefix SET purged_at = ? WHERE id = ?"
    )
    try
      ps.setTimestamp(1, Timestamp.from(purgedAt))
      ps.setString(2, id)
      ps.executeUpdate()
      ()
    finally ps.close()
  }

  def managedPrefix(id: String): Option[ManagedPrefixRow] = withConn { c =>
    val ps = c.prepareStatement(
      """SELECT id, tenant, tenant_db_name, prefix, created_at, deleted_at, purge_eligible_at, purged_at
        |FROM qodstate_managed_prefix WHERE id = ?""".stripMargin
    )
    try
      ps.setString(1, id)
      val rs = ps.executeQuery()
      try if rs.next() then Some(readManagedPrefixRow(rs)) else None
      finally rs.close()
    finally ps.close()
  }

  private def readManagedPrefixRow(rs: ResultSet): ManagedPrefixRow =
    ManagedPrefixRow(
      id = rs.getString("id"),
      tenant = rs.getString("tenant"),
      tenantDbName = rs.getString("tenant_db_name"),
      prefix = rs.getString("prefix"),
      createdAt = rs.getTimestamp("created_at").toInstant,
      deletedAt = Option(rs.getTimestamp("deleted_at")).map(_.toInstant),
      purgeEligibleAt = Option(rs.getTimestamp("purge_eligible_at")).map(_.toInstant),
      purgedAt = Option(rs.getTimestamp("purged_at")).map(_.toInstant)
    )

  private def drain[A](rs: ResultSet)(read: ResultSet => A): List[A] =
    val buf = ListBuffer.empty[A]
    while rs.next() do buf += read(rs)
    buf.toList

  private def deleteById(c: Connection, table: String, column: String, id: String): Unit =
    val ps = c.prepareStatement(s"DELETE FROM $table WHERE $column = ?")
    try
      ps.setString(1, id)
      ps.executeUpdate()
    finally ps.close()

  private def mapToJson(m: Map[String, String]): String =
    if m.isEmpty then "{}"
    else m.map { case (k, v) => k -> Json.fromString(v) }.asJson.noSpaces

  private def jsonToMap(raw: String): Map[String, String] =
    if raw == null || raw.isEmpty then Map.empty
    else parse(raw).flatMap(_.as[Map[String, String]]).getOrElse(Map.empty)

  private def setNullable(ps: PreparedStatement, idx: Int, v: Option[String]): Unit =
    v match
      case Some(s) => ps.setString(idx, s)
      case None    => ps.setNull(idx, Types.VARCHAR)

object PostgresControlPlaneStore:

  def fromDefaultMetastore(meta: Map[String, String]): PostgresControlPlaneStore =
    def required(k: String): String =
      meta
        .get(k)
        .filter(_.nonEmpty)
        .getOrElse(
          sys.error(s"defaultMetastore.$k must be set for PostgresControlPlaneStore")
        )
    val host = required("pgHost")
    val port = required("pgPort")
    val user = required("pgUser")
    val pass = required("pgPassword")
    val db   = required("dbName")
    new PostgresControlPlaneStore(s"jdbc:postgresql://$host:$port/$db", user, pass)

  // ---------------- PoolCohort JSON ----------------
  // Stored as a JSON array in `qodstate_pool.cohorts`. We keep the on-disk
  // format hand-rolled rather than depending on a generic circe codec so
  // that adding new optional placement keys (e.g. affinity rules) later
  // can stay backward-compatible without a Liquibase migration.

  def cohortsToJson(cohorts: List[PoolCohort]): String =
    val arr = Json.fromValues(cohorts.map { c =>
      Json.obj(
        "placement" -> Json.obj(
          "nodeSelector" -> Json.fromFields(
            c.placement.nodeSelector.map((k, v) => k -> Json.fromString(v))
          ),
          "tolerations" -> Json.fromValues(c.placement.tolerations.map { t =>
            Json.fromFields(
              List(
                Some("key"      -> Json.fromString(t.key)),
                Some("operator" -> Json.fromString(t.operator)),
                t.value.map(v => "value" -> Json.fromString(v)),
                t.effect.map(e => "effect" -> Json.fromString(e))
              ).flatten
            )
          })
        ),
        "distribution" -> Json.obj(
          "writeonly" -> Json.fromInt(c.distribution.writeonly),
          "readonly"  -> Json.fromInt(c.distribution.readonly),
          "dual"      -> Json.fromInt(c.distribution.dual)
        )
      )
    })
    arr.noSpaces

  def cohortsFromJson(raw: String): List[PoolCohort] =
    if raw == null || raw.isEmpty then Nil
    else
      parse(raw).toOption.flatMap(_.asArray).getOrElse(Vector.empty).toList.flatMap { j =>
        val cur       = j.hcursor
        val placement = cur.downField("placement")
        val ns   = placement.downField("nodeSelector").as[Map[String, String]].getOrElse(Map.empty)
        val tols = placement
          .downField("tolerations")
          .focus
          .flatMap(_.asArray)
          .getOrElse(Vector.empty)
          .toList
          .flatMap { tj =>
            val tc = tj.hcursor
            tc.downField("key").as[String].toOption.map { key =>
              NodeToleration(
                key = key,
                operator = tc.downField("operator").as[String].getOrElse("Equal"),
                value = tc.downField("value").as[String].toOption,
                effect = tc.downField("effect").as[String].toOption
              )
            }
          }
        val dist = cur.downField("distribution")
        for
          w <- dist.downField("writeonly").as[Int].toOption
          r <- dist.downField("readonly").as[Int].toOption
          d <- dist.downField("dual").as[Int].toOption
        yield PoolCohort(NodePlacement(ns, tols), RoleDistribution(w, r, d))
      }
