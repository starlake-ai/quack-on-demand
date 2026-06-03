package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.{Pool, PoolKey, Role, RoleDistribution, RunningNode, Tenant, TenantDb}
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
      """INSERT INTO qodstate_tenant (id, display_name, disabled)
        |VALUES (?, ?, ?)
        |ON CONFLICT (id) DO UPDATE SET
        |  display_name = EXCLUDED.display_name,
        |  disabled     = EXCLUDED.disabled""".stripMargin
    )
    try
      ps.setString(1, t.id)
      ps.setString(2, if t.displayName.nonEmpty then t.displayName else t.name)
      ps.setBoolean(3, t.disabled)
      ps.executeUpdate()
    finally ps.close()
  }

  def listTenants(): List[Tenant] = withConn { c =>
    val rs = c.createStatement().executeQuery(
      "SELECT id, display_name, disabled FROM qodstate_tenant ORDER BY display_name"
    )
    try drain(rs)(readTenant)
    finally rs.close()
  }

  def deleteTenant(id: String): Unit = withConn(c => deleteById(c, "qodstate_tenant", "id", id))

  private def readTenant(rs: ResultSet): Tenant =
    val dn = rs.getString("display_name")
    Tenant(
      id          = rs.getString("id"),
      name        = dn,
      displayName = dn,
      disabled    = rs.getBoolean("disabled")
    )

  // ---------------- TenantDb ----------------

  def upsertTenantDb(t: TenantDb): Unit = withConn { c =>
    val ps = c.prepareStatement(
      """INSERT INTO qodstate_tenant_db
        |  (id, tenant_id, name, metastore_params, data_path, object_store_params, disabled)
        |VALUES (?, ?, ?, ?::jsonb, ?, ?::jsonb, ?)
        |ON CONFLICT (id) DO UPDATE SET
        |  tenant_id            = EXCLUDED.tenant_id,
        |  name                 = EXCLUDED.name,
        |  metastore_params     = EXCLUDED.metastore_params,
        |  data_path            = EXCLUDED.data_path,
        |  object_store_params  = EXCLUDED.object_store_params,
        |  disabled             = EXCLUDED.disabled""".stripMargin
    )
    try
      ps.setString(1, t.id)
      ps.setString(2, t.tenantId)
      ps.setString(3, t.name)
      ps.setString(4, mapToJson(t.metastore))
      ps.setString(5, t.dataPath)
      ps.setString(6, mapToJson(t.objectStore))
      ps.setBoolean(7, t.disabled)
      ps.executeUpdate()
    finally ps.close()
  }

  def listTenantDbs(tenantId: String): List[TenantDb] = withConn { c =>
    val ps = c.prepareStatement(
      """SELECT id, tenant_id, name, metastore_params, data_path, object_store_params, disabled
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
      id          = rs.getString("id"),
      tenantId    = rs.getString("tenant_id"),
      name        = rs.getString("name"),
      metastore   = jsonToMap(rs.getString("metastore_params")),
      dataPath    = rs.getString("data_path"),
      objectStore = jsonToMap(rs.getString("object_store_params")),
      disabled    = rs.getBoolean("disabled")
    )

  // ---------------- Pool ----------------

  def upsertPool(p: Pool): Unit = withConn { c =>
    val ps = c.prepareStatement(
      """INSERT INTO qodstate_pool
        |  (id, tenant_id, tenant_db_id, name, size,
        |   dist_writeonly, dist_readonly, dist_dual,
        |   max_concurrent_per_node, idle_timeout_sec, disabled)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
        |  disabled                = EXCLUDED.disabled""".stripMargin
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
      ps.executeUpdate()
    finally ps.close()
  }

  def listPools(tenantDbId: String): List[Pool] = withConn { c =>
    val ps = c.prepareStatement(
      """SELECT id, tenant_id, tenant_db_id, name, size,
        |       dist_writeonly, dist_readonly, dist_dual,
        |       max_concurrent_per_node, idle_timeout_sec, disabled
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
      disabled             = rs.getBoolean("disabled")
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

  // ---------------- Snapshot ----------------

  def snapshot(): ControlPlaneSnapshot = withConn { c =>
    ControlPlaneSnapshot(
      tenants   = selectAll(c, "SELECT id, display_name, disabled FROM qodstate_tenant ORDER BY display_name", readTenant),
      tenantDbs = selectAll(c, "SELECT id, tenant_id, name, metastore_params, data_path, object_store_params, disabled FROM qodstate_tenant_db ORDER BY name", readTenantDb),
      pools     = selectAll(c, "SELECT id, tenant_id, tenant_db_id, name, size, dist_writeonly, dist_readonly, dist_dual, max_concurrent_per_node, idle_timeout_sec, disabled FROM qodstate_pool ORDER BY name", readPool),
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
      )
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
