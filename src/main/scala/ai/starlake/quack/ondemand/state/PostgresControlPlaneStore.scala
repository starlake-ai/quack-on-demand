package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.{
  NodePlacement, NodeToleration, Pool, PoolCohort, PoolKey, Role, RoleDistribution,
  RunningNode, Tenant, TenantDb, TenantDbKind
}
import io.circe.parser.parse
import io.circe.syntax._
import io.circe.Json

import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet, Timestamp, Types}
import java.time.Instant
import scala.collection.mutable.ListBuffer

/** Postgres-backed [[ControlPlaneStore]] for the normalized schema
  * produced by changelog `0001-normalized-schema.yaml`. Assumes the
  * schema is already in place -- the caller (typically `Main`) runs
  * [[LiquibaseRunner]] first. */
final class PostgresControlPlaneStore(
    jdbcUrl:  String,
    user:     String,
    password: String
) extends ControlPlaneStore:

  Class.forName("org.postgresql.Driver")

  private def withConn[A](f: Connection => A): A =
    val c = DriverManager.getConnection(jdbcUrl, user, password)
    try f(c) finally c.close()

  // ---------------- Tenant ----------------

  def upsertTenant(t: Tenant): Unit = withConn { c =>
    val ps = c.prepareStatement(
      """INSERT INTO qodstate_tenant
        |  (id, display_name, disabled, auth_provider, auth_config)
        |VALUES (?, ?, ?, ?, ?::jsonb)
        |ON CONFLICT (id) DO UPDATE SET
        |  display_name  = EXCLUDED.display_name,
        |  disabled      = EXCLUDED.disabled,
        |  auth_provider = EXCLUDED.auth_provider,
        |  auth_config   = EXCLUDED.auth_config""".stripMargin
    )
    try
      ps.setString(1, t.id)
      ps.setString(2, if t.displayName.nonEmpty then t.displayName else t.name)
      ps.setBoolean(3, t.disabled)
      ps.setString(4, t.authProvider)
      ps.setString(5, mapToJson(t.authConfig))
      ps.executeUpdate()
    finally ps.close()
  }

  def listTenants(): List[Tenant] = withConn { c =>
    val rs = c.createStatement().executeQuery(
      "SELECT id, display_name, disabled, auth_provider, auth_config FROM qodstate_tenant ORDER BY display_name"
    )
    try drain(rs)(readTenant)
    finally rs.close()
  }

  def deleteTenant(id: String): Unit = withConn(c => deleteById(c, "qodstate_tenant", "id", id))

  def createTenantWithAdminRole(
      tenant:          Tenant,
      adminRole:       RbacRole,
      adminPermission: RolePermission
  ): Unit = withConn { c =>
    // Single connection + manual commit so the three inserts either all
    // land or roll back together. INSERT (not upsert) because this is a
    // bootstrap path -- a duplicate id is a programmer error.
    c.setAutoCommit(false)
    try
      val tps = c.prepareStatement(
        """INSERT INTO qodstate_tenant
          |  (id, display_name, disabled, auth_provider, auth_config)
          |VALUES (?, ?, ?, ?, ?::jsonb)""".stripMargin
      )
      try
        tps.setString(1, tenant.id)
        tps.setString(2, if tenant.displayName.nonEmpty then tenant.displayName else tenant.name)
        tps.setBoolean(3, tenant.disabled)
        tps.setString(4, tenant.authProvider)
        tps.setString(5, mapToJson(tenant.authConfig))
        tps.executeUpdate()
      finally tps.close()

      val rps = c.prepareStatement(
        """INSERT INTO qodstate_role (id, tenant_id, name, description)
          |VALUES (?, ?, ?, ?)""".stripMargin
      )
      try
        rps.setString(1, adminRole.id)
        rps.setString(2, adminRole.tenantId)
        rps.setString(3, adminRole.name)
        setNullable(rps, 4, adminRole.description)
        rps.executeUpdate()
      finally rps.close()

      val pps = c.prepareStatement(
        """INSERT INTO qodstate_role_permission
          |  (id, role_id, catalog_name, schema_name, table_name, verb)
          |VALUES (?, ?, ?, ?, ?, ?)""".stripMargin
      )
      try
        pps.setString(1, adminPermission.id)
        pps.setString(2, adminPermission.roleId)
        pps.setString(3, adminPermission.catalogName)
        pps.setString(4, adminPermission.schemaName)
        pps.setString(5, adminPermission.tableName)
        pps.setString(6, adminPermission.verb.toUpperCase)
        pps.executeUpdate()
      finally pps.close()

      c.commit()
    catch case t: Throwable =>
      try c.rollback() catch case _: Throwable => ()
      throw t
    finally c.setAutoCommit(true)
  }

  private def readTenant(rs: ResultSet): Tenant =
    val dn = rs.getString("display_name")
    Tenant(
      id           = rs.getString("id"),
      name         = dn,
      displayName  = dn,
      disabled     = rs.getBoolean("disabled"),
      authProvider = rs.getString("auth_provider"),
      authConfig   = jsonToMap(rs.getString("auth_config"))
    )

  // ---------------- TenantDb ----------------

  def upsertTenantDb(t: TenantDb): Unit = withConn { c =>
    val ps = c.prepareStatement(
      """INSERT INTO qodstate_tenant_db
        |  (id, tenant_id, name, metastore_params, data_path, object_store_params, disabled,
        |   kind, default_database, default_schema)
        |VALUES (?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?)
        |ON CONFLICT (id) DO UPDATE SET
        |  tenant_id            = EXCLUDED.tenant_id,
        |  name                 = EXCLUDED.name,
        |  metastore_params     = EXCLUDED.metastore_params,
        |  data_path            = EXCLUDED.data_path,
        |  object_store_params  = EXCLUDED.object_store_params,
        |  disabled             = EXCLUDED.disabled,
        |  kind                 = EXCLUDED.kind,
        |  default_database     = EXCLUDED.default_database,
        |  default_schema       = EXCLUDED.default_schema""".stripMargin
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
      ps.executeUpdate()
    finally ps.close()
  }

  def listTenantDbs(tenantId: String): List[TenantDb] = withConn { c =>
    val ps = c.prepareStatement(
      """SELECT id, tenant_id, name, metastore_params, data_path, object_store_params, disabled,
        |       kind, default_database, default_schema
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
      id              = rs.getString("id"),
      tenantId        = rs.getString("tenant_id"),
      name            = rs.getString("name"),
      kind            = TenantDbKind.fromWire(rs.getString("kind"))
                          .fold(err => sys.error(s"qodstate_tenant_db.kind invalid: '${rs.getString("kind")}' ($err)"), identity),
      metastore       = jsonToMap(rs.getString("metastore_params")),
      dataPath        = rs.getString("data_path"),
      objectStore     = jsonToMap(rs.getString("object_store_params")),
      defaultDatabase = Option(rs.getString("default_database")),
      defaultSchema   = Option(rs.getString("default_schema")),
      disabled        = rs.getBoolean("disabled")
    )

  // ---------------- Pool ----------------

  def upsertPool(p: Pool): Unit = withConn { c =>
    val ps = c.prepareStatement(
      """INSERT INTO qodstate_pool
        |  (id, tenant_id, tenant_db_id, name, size,
        |   dist_writeonly, dist_readonly, dist_dual,
        |   max_concurrent_per_node, idle_timeout_sec, disabled, cohorts)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
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
        |  cohorts                 = EXCLUDED.cohorts""".stripMargin
    )
    try
      ps.setString(1,  p.id)
      ps.setString(2,  p.tenantId)
      ps.setString(3,  p.tenantDbId)
      ps.setString(4,  p.name)
      ps.setInt   (5,  p.size)
      ps.setInt   (6,  p.distribution.writeonly)
      ps.setInt   (7,  p.distribution.readonly)
      ps.setInt   (8,  p.distribution.dual)
      ps.setInt   (9,  p.maxConcurrentPerNode)
      p.idleTimeoutSec match
        case Some(v) => ps.setInt(10, v)
        case None    => ps.setNull(10, Types.INTEGER)
      ps.setBoolean(11, p.disabled)
      ps.setString(12, PostgresControlPlaneStore.cohortsToJson(p.cohorts))
      ps.executeUpdate()
    finally ps.close()
  }

  def listPools(tenantDbId: String): List[Pool] = withConn { c =>
    val ps = c.prepareStatement(
      """SELECT id, tenant_id, tenant_db_id, name, size,
        |       dist_writeonly, dist_readonly, dist_dual,
        |       max_concurrent_per_node, idle_timeout_sec, disabled, cohorts
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
      id           = rs.getString("id"),
      tenantId     = rs.getString("tenant_id"),
      tenantDbId   = rs.getString("tenant_db_id"),
      name         = rs.getString("name"),
      size         = rs.getInt("size"),
      distribution = RoleDistribution(
        writeonly = rs.getInt("dist_writeonly"),
        readonly  = rs.getInt("dist_readonly"),
        dual      = rs.getInt("dist_dual")
      ),
      maxConcurrentPerNode = rs.getInt("max_concurrent_per_node"),
      idleTimeoutSec       = Option(idle).map(_.asInstanceOf[Number].intValue),
      disabled             = rs.getBoolean("disabled"),
      cohorts              = PostgresControlPlaneStore.cohortsFromJson(rs.getString("cohorts"))
    )

  // ---------------- Node ----------------

  def upsertNode(n: RunningNode, poolId: String): Unit = withConn { c =>
    val ps = c.prepareStatement(
      """INSERT INTO qodstate_node
        |  (node_id, pool_id, host, port, token, role,
        |   pid, pod_name, started_at, last_seen, max_concurrent)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
        |  max_concurrent = EXCLUDED.max_concurrent""".stripMargin
    )
    try
      ps.setString(1, n.nodeId)
      ps.setString(2, poolId)
      ps.setString(3, n.host)
      ps.setInt   (4, n.port)
      ps.setString(5, n.token)
      ps.setString(6, n.role.toString)
      n.pid     match { case Some(v) => ps.setLong(7, v) ; case None => ps.setNull(7, Types.BIGINT) }
      n.podName match { case Some(v) => ps.setString(8, v); case None => ps.setNull(8, Types.VARCHAR) }
      ps.setTimestamp(9, Timestamp.from(n.startedAt))
      n.lastSeen match { case Some(v) => ps.setTimestamp(10, Timestamp.from(v)); case None => ps.setNull(10, Types.TIMESTAMP) }
      ps.setInt(11, n.maxConcurrent)
      ps.executeUpdate()
    finally ps.close()
  }

  def listNodes(poolId: String): List[RunningNode] = withConn { c =>
    val ps = c.prepareStatement(
      """SELECT n.node_id, n.host, n.port, n.token, n.role,
        |       n.pid, n.pod_name, n.started_at, n.last_seen, n.max_concurrent,
        |       t.display_name AS tenant_name, td.name AS tenant_db_name, p.name AS pool_name
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

  private def readNode(rs: ResultSet): RunningNode =
    RunningNode(
      nodeId    = rs.getString("node_id"),
      poolKey   = PoolKey(
        rs.getString("tenant_name"),
        rs.getString("tenant_db_name"),
        rs.getString("pool_name")
      ),
      role      = Role.valueOf(rs.getString("role")),
      host      = rs.getString("host"),
      port      = rs.getInt("port"),
      token     = rs.getString("token"),
      pid       = Option(rs.getObject("pid")).map(_.asInstanceOf[Number].longValue),
      podName   = Option(rs.getString("pod_name")),
      startedAt = rs.getTimestamp("started_at").toInstant,
      maxConcurrent = rs.getInt("max_concurrent"),
      lastSeen      = Option(rs.getTimestamp("last_seen")).map(_.toInstant)
    )

  // ---------------- RBAC: users ----------------

  def upsertUserIdentity(u: RbacUser): Unit = withConn { c =>
    val ps = c.prepareStatement(
      """INSERT INTO qodstate_user (id, tenant, username, role, password_hash, created_at, updated_at)
        |VALUES (?, ?, ?, ?, '', COALESCE(?, NOW()), NOW())
        |ON CONFLICT (id) DO UPDATE SET
        |  tenant     = EXCLUDED.tenant,
        |  username   = EXCLUDED.username,
        |  role       = EXCLUDED.role,
        |  updated_at = NOW()""".stripMargin
    )
    try
      ps.setString(1, u.id)
      u.tenant match
        case Some(t) => ps.setString(2, t)
        case None    => ps.setNull(2, Types.VARCHAR)
      ps.setString(3, u.username)
      ps.setString(4, u.role)
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
      tenant:       Option[String],
      username:     String,
      passwordHash: String,
      role:         String
  ): String = withConn { c =>
    // Look up the existing id first so the upsert can preserve it
    // (mirrors [[UserStore.upsertUser]]). The partial unique indexes
    // (admin vs scoped) mean ON CONFLICT cannot target a single index
    // without knowing the tenant kind.
    val lookup = tenant match
      case Some(t) =>
        val p = c.prepareStatement(
          "SELECT id FROM qodstate_user WHERE tenant = ? AND username = ?"
        )
        p.setString(1, t)
        p.setString(2, username)
        p
      case None =>
        val p = c.prepareStatement(
          "SELECT id FROM qodstate_user WHERE tenant IS NULL AND username = ?"
        )
        p.setString(1, username)
        p
    val existingId =
      try
        val rs = lookup.executeQuery()
        try if rs.next() then Some(rs.getString(1)) else None
        finally rs.close()
      finally lookup.close()

    val id = existingId.getOrElse(s"u-${java.util.UUID.randomUUID().toString.take(8)}")

    val ps = c.prepareStatement(
      """INSERT INTO qodstate_user (id, tenant, username, password_hash, role, updated_at)
        |VALUES (?, ?, ?, ?, ?, NOW())
        |ON CONFLICT (id) DO UPDATE SET
        |  password_hash = EXCLUDED.password_hash,
        |  role          = EXCLUDED.role,
        |  updated_at    = NOW()""".stripMargin
    )
    try
      ps.setString(1, id)
      tenant match
        case Some(t) => ps.setString(2, t)
        case None    => ps.setNull(2, Types.VARCHAR)
      ps.setString(3, username)
      ps.setString(4, passwordHash)
      ps.setString(5, role)
      ps.executeUpdate()
      id
    finally ps.close()
  }

  def getUserById(id: String): Option[RbacUser] = withConn { c =>
    val ps = c.prepareStatement(
      "SELECT id, tenant, username, role, created_at, updated_at FROM qodstate_user WHERE id = ?"
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
          """SELECT id, tenant, username, role, created_at, updated_at
            |FROM qodstate_user WHERE tenant = ? AND username = ?""".stripMargin
        )
        p.setString(1, t)
        p.setString(2, username)
        p
      case None =>
        val p = c.prepareStatement(
          """SELECT id, tenant, username, role, created_at, updated_at
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
          """SELECT id, tenant, username, role, created_at, updated_at
            |FROM qodstate_user WHERE tenant = ? ORDER BY username""".stripMargin
        )
        p.setString(1, t)
        p
      case None =>
        c.prepareStatement(
          """SELECT id, tenant, username, role, created_at, updated_at
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
      """SELECT id, tenant, username, role, created_at, updated_at
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
      """SELECT id, tenant, username, role, created_at, updated_at
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
      id        = rs.getString("id"),
      tenant    = Option(rs.getString("tenant")),
      username  = rs.getString("username"),
      role      = rs.getString("role"),
      createdAt = Option(rs.getTimestamp("created_at")).map(_.toInstant),
      updatedAt = Option(rs.getTimestamp("updated_at")).map(_.toInstant)
    )

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
      """SELECT id, tenant_id, name, description, created_at
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
      "SELECT id, tenant_id, name, description, created_at FROM qodstate_role WHERE id = ?"
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
      """SELECT id, tenant_id, name, description, created_at
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
      id          = rs.getString("id"),
      tenantId    = rs.getString("tenant_id"),
      name        = rs.getString("name"),
      description = Option(rs.getString("description")),
      createdAt   = Option(rs.getTimestamp("created_at")).map(_.toInstant)
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
      ps.setString(6, p.verb.toUpperCase)
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
    else withConn { c =>
      val placeholders = roleIds.toList.map(_ => "?").mkString(",")
      val ps = c.prepareStatement(
        s"""SELECT id, role_id, catalog_name, schema_name, table_name, verb, granted_at
           |FROM qodstate_role_permission WHERE role_id IN ($placeholders)""".stripMargin
      )
      try
        roleIds.zipWithIndex.foreach { (id, i) => ps.setString(i + 1, id) }
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

  private def readRolePermission(rs: ResultSet): RolePermission =
    RolePermission(
      id          = rs.getString("id"),
      roleId      = rs.getString("role_id"),
      catalogName = rs.getString("catalog_name"),
      schemaName  = rs.getString("schema_name"),
      tableName   = rs.getString("table_name"),
      verb        = rs.getString("verb"),
      grantedAt   = Option(rs.getTimestamp("granted_at")).map(_.toInstant)
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
      """SELECT id, tenant_id, name, description FROM qodstate_group
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
      "SELECT id, tenant_id, name, description FROM qodstate_group WHERE id = ?"
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
      """SELECT id, tenant_id, name, description FROM qodstate_group
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
      id          = rs.getString("id"),
      tenantId    = rs.getString("tenant_id"),
      name        = rs.getString("name"),
      description = Option(rs.getString("description"))
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
      c: Connection, table: String, leftCol: String, rightCol: String,
      left: String, right: String
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
      c: Connection, table: String, leftCol: String, rightCol: String,
      left: String, right: String
  ): Boolean =
    val ps = c.prepareStatement(s"DELETE FROM $table WHERE $leftCol = ? AND $rightCol = ?")
    try
      ps.setString(1, left)
      ps.setString(2, right)
      ps.executeUpdate() > 0
    finally ps.close()

  private def listEdgeRight(
      c: Connection, table: String, filterCol: String, returnCol: String, filter: String
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

  def listPoolPermissions(
      tenantId: Option[String] = None,
      userId:   Option[String] = None,
      groupId:  Option[String] = None
  ): List[PoolPermission] = withConn { c =>
    // Build the WHERE dynamically so callers can mix filters; an unset
    // option contributes no predicate. Always ORDER BY id for stable
    // iteration in tests.
    val clauses = scala.collection.mutable.ListBuffer.empty[String]
    val binds   = scala.collection.mutable.ListBuffer.empty[String]
    tenantId.foreach { t => clauses += "tenant_id = ?"; binds += t }
    userId  .foreach { u => clauses += "user_id   = ?"; binds += u }
    groupId .foreach { g => clauses += "group_id  = ?"; binds += g }
    val where = if clauses.isEmpty then "" else clauses.mkString(" WHERE ", " AND ", "")
    val ps = c.prepareStatement(
      s"""SELECT id, tenant_id, pool_id, user_id, group_id, granted_at
         |FROM qodstate_pool_permission$where ORDER BY id""".stripMargin
    )
    try
      binds.zipWithIndex.foreach { (v, i) => ps.setString(i + 1, v) }
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
    else withConn { c =>
      val placeholders = userIds.map(_ => "?").mkString(",")
      val ps = c.prepareStatement(
        s"""SELECT id, tenant_id, pool_id, user_id, group_id, granted_at
           |FROM qodstate_pool_permission WHERE user_id IN ($placeholders)""".stripMargin
      )
      try
        userIds.zipWithIndex.foreach { (id, i) => ps.setString(i + 1, id) }
        val rs = ps.executeQuery()
        try
          val buf = scala.collection.mutable.Map.empty[String, scala.collection.mutable.ListBuffer[PoolPermission]]
          while rs.next() do
            val pp = readPoolPermission(rs)
            pp.userId.foreach { u =>
              buf.getOrElseUpdate(u, scala.collection.mutable.ListBuffer.empty) += pp
            }
          buf.view.mapValues(_.toList).toMap
        finally rs.close()
      finally ps.close()
    }

  /** Group-by-left helper: bulk-fetch the rightCol values for a list
    * of leftCol keys in one round-trip. Used by the user-list path to
    * avoid the N+1 read of user-role + user-group edges. */
  private def bulkEdgeByLeft(
      ids:      List[String],
      table:    String,
      leftCol:  String,
      rightCol: String
  ): Map[String, Set[String]] =
    if ids.isEmpty then Map.empty
    else withConn { c =>
      val placeholders = ids.map(_ => "?").mkString(",")
      val ps = c.prepareStatement(
        s"SELECT $leftCol, $rightCol FROM $table WHERE $leftCol IN ($placeholders)"
      )
      try
        ids.zipWithIndex.foreach { (id, i) => ps.setString(i + 1, id) }
        val rs = ps.executeQuery()
        try
          val buf = scala.collection.mutable.Map.empty[String, scala.collection.mutable.Set[String]]
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
      id        = rs.getString("id"),
      tenantId  = rs.getString("tenant_id"),
      poolId    = Option(rs.getString("pool_id")),
      userId    = Option(rs.getString("user_id")),
      groupId   = Option(rs.getString("group_id")),
      grantedAt = Option(rs.getTimestamp("granted_at")).map(_.toInstant)
    )

  // ---------------- Snapshot ----------------

  def snapshot(): ControlPlaneSnapshot = withConn { c =>
    ControlPlaneSnapshot(
      tenants   = selectAll(c, "SELECT id, display_name, disabled, auth_provider, auth_config FROM qodstate_tenant ORDER BY display_name", readTenant),
      tenantDbs = selectAll(c, "SELECT id, tenant_id, name, metastore_params, data_path, object_store_params, disabled, kind, default_database, default_schema FROM qodstate_tenant_db ORDER BY name", readTenantDb),
      pools     = selectAll(c, "SELECT id, tenant_id, tenant_db_id, name, size, dist_writeonly, dist_readonly, dist_dual, max_concurrent_per_node, idle_timeout_sec, disabled, cohorts FROM qodstate_pool ORDER BY name", readPool),
      nodes     = selectAll(c,
        """SELECT n.node_id, n.host, n.port, n.token, n.role,
          |       n.pid, n.pod_name, n.started_at, n.last_seen, n.max_concurrent,
          |       t.display_name AS tenant_name,
          |       td.name        AS tenant_db_name,
          |       p.name         AS pool_name
          |FROM qodstate_node n
          |JOIN qodstate_pool p       ON p.id  = n.pool_id
          |JOIN qodstate_tenant_db td ON td.id = p.tenant_db_id
          |JOIN qodstate_tenant t     ON t.id  = td.tenant_id
          |ORDER BY n.node_id""".stripMargin,
        readNode
      ),
      users           = selectAll(c, "SELECT id, tenant, username, role, created_at, updated_at FROM qodstate_user ORDER BY COALESCE(tenant, ''), username", readRbacUser),
      roles           = selectAll(c, "SELECT id, tenant_id, name, description, created_at FROM qodstate_role ORDER BY tenant_id, name", readRole),
      rolePermissions = selectAll(c, "SELECT id, role_id, catalog_name, schema_name, table_name, verb, granted_at FROM qodstate_role_permission ORDER BY role_id, catalog_name, schema_name, table_name, verb", readRolePermission),
      groups          = selectAll(c, "SELECT id, tenant_id, name, description FROM qodstate_group ORDER BY tenant_id, name", readGroup),
      userGroups      = selectAll(c, "SELECT user_id, group_id FROM qodstate_user_group  ORDER BY user_id, group_id", rs => UserGroupEdge(rs.getString(1), rs.getString(2))),
      userRoles       = selectAll(c, "SELECT user_id, role_id  FROM qodstate_user_role   ORDER BY user_id, role_id",  rs => UserRoleEdge (rs.getString(1), rs.getString(2))),
      groupRoles      = selectAll(c, "SELECT group_id, role_id FROM qodstate_group_role  ORDER BY group_id, role_id", rs => GroupRoleEdge(rs.getString(1), rs.getString(2))),
      poolPermissions = selectAll(c, "SELECT id, tenant_id, pool_id, user_id, group_id, granted_at FROM qodstate_pool_permission ORDER BY id", readPoolPermission)
    )
  }

  // ---------------- helpers ----------------

  private def selectAll[A](c: Connection, sql: String, read: ResultSet => A): List[A] =
    val ps = c.prepareStatement(sql)
    try
      val rs = ps.executeQuery()
      try drain(rs)(read) finally rs.close()
    finally ps.close()

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
    else
      parse(raw).flatMap(_.as[Map[String, String]]).getOrElse(Map.empty)

  private def setNullable(ps: PreparedStatement, idx: Int, v: Option[String]): Unit =
    v match
      case Some(s) => ps.setString(idx, s)
      case None    => ps.setNull(idx, Types.VARCHAR)

object PostgresControlPlaneStore:

  def fromDefaultMetastore(meta: Map[String, String]): PostgresControlPlaneStore =
    def required(k: String): String =
      meta.get(k).filter(_.nonEmpty).getOrElse(
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
          "nodeSelector" -> Json.fromFields(c.placement.nodeSelector.map((k, v) => k -> Json.fromString(v))),
          "tolerations"  -> Json.fromValues(c.placement.tolerations.map { t =>
            Json.fromFields(List(
              Some("key"      -> Json.fromString(t.key)),
              Some("operator" -> Json.fromString(t.operator)),
              t.value.map(v => "value" -> Json.fromString(v)),
              t.effect.map(e => "effect" -> Json.fromString(e))
            ).flatten)
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
    else parse(raw).toOption.flatMap(_.asArray).getOrElse(Vector.empty).toList.flatMap { j =>
      val cur = j.hcursor
      val placement = cur.downField("placement")
      val ns = placement.downField("nodeSelector").as[Map[String, String]].getOrElse(Map.empty)
      val tols = placement.downField("tolerations").focus
        .flatMap(_.asArray).getOrElse(Vector.empty).toList.flatMap { tj =>
          val tc = tj.hcursor
          tc.downField("key").as[String].toOption.map { key =>
            NodeToleration(
              key      = key,
              operator = tc.downField("operator").as[String].getOrElse("Equal"),
              value    = tc.downField("value").as[String].toOption,
              effect   = tc.downField("effect").as[String].toOption
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
