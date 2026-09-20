package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.{FederatedSecret, FederatedSource}

import java.sql.{Connection, DriverManager, ResultSet}
import scala.collection.mutable.ListBuffer

/** Minimal interface consumed by [[ai.starlake.quack.ondemand.api.FederatedSourceHandlers]]. The
  * concrete Postgres-backed store and any test stubs both implement this trait.
  */
trait FederatedSourceOps:
  def upsertSource(s: FederatedSource): Unit
  def deleteSource(id: String): Unit
  def getSource(tenantDbId: String, alias: String): Option[FederatedSource]
  def listSources(tenantDbId: String): List[FederatedSource]
  def upsertSecret(s: FederatedSecret): Unit
  def deleteSecret(sourceId: String, name: String): Unit
  def getSecret(sourceId: String, name: String): Option[FederatedSecret]
  def listSecrets(sourceId: String): List[FederatedSecret]

  /** Tenant-db ids that have at least one ENABLED federated source. Backs
    * [[ai.starlake.quack.ondemand.PoolSupervisor.restore]]'s skip-resolution guard: a tenant-db
    * absent from this set has nothing to resolve, so restore() never opens a connection for it.
    */
  def tenantDbIdsWithSources(): Set[String]

object FederatedSourceStore:

  /** Appends `connectTimeout` and `socketTimeout` (pgjdbc units: SECONDS) to a JDBC URL so a
    * fresh-per-call connection (see `withConn` below) can never hang a caller indefinitely on a
    * half-dead Postgres. Handles both a bare URL and one that already carries a query string, and
    * never overrides a timeout the caller's URL already sets.
    */
  def withTimeouts(jdbcUrl: String, connectSec: Int = 10, socketSec: Int = 30): String =
    val hasQuery   = jdbcUrl.contains("?")
    val hasConnect = jdbcUrl.contains("connectTimeout=")
    val hasSocket  = jdbcUrl.contains("socketTimeout=")
    val extras     = List(
      Option.when(!hasConnect)(s"connectTimeout=$connectSec"),
      Option.when(!hasSocket)(s"socketTimeout=$socketSec")
    ).flatten
    if extras.isEmpty then jdbcUrl
    else jdbcUrl + (if hasQuery then "&" else "?") + extras.mkString("&")

/** Postgres-backed CRUD against `qodstate_federated_source` and `qodstate_federated_secret`.
  * Cascade-delete on source -> secret is enforced by the FK constraint, so deleting a source
  * automatically wipes its secrets.
  */
class FederatedSourceStore(
    jdbcUrl: String,
    user: String,
    password: String
) extends FederatedSourceOps:

  Class.forName("org.postgresql.Driver")

  // Bounded once at construction: every caller of withConn (handlers, blob builder loads via
  // listEnabledSources/listSecrets, tenantDbIdsWithSources) benefits without a per-call cost.
  private val boundedUrl = FederatedSourceStore.withTimeouts(jdbcUrl)

  private def withConn[A](f: Connection => A): A =
    val c = DriverManager.getConnection(boundedUrl, user, password)
    try f(c)
    finally c.close()

  // ---------------- FederatedSource ----------------

  def upsertSource(s: FederatedSource): Unit = withConn { c =>
    val ps = c.prepareStatement(
      """INSERT INTO qodstate_federated_source
        |  (id, tenant_db_id, alias, setup_sql, description, disabled,
        |   source_type, config, read_only)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        |ON CONFLICT (id) DO UPDATE SET
        |  tenant_db_id = EXCLUDED.tenant_db_id,
        |  alias        = EXCLUDED.alias,
        |  setup_sql    = EXCLUDED.setup_sql,
        |  description  = EXCLUDED.description,
        |  disabled     = EXCLUDED.disabled,
        |  source_type  = EXCLUDED.source_type,
        |  config       = EXCLUDED.config,
        |  read_only    = EXCLUDED.read_only""".stripMargin
    )
    try
      ps.setString(1, s.id)
      ps.setString(2, s.tenantDbId)
      ps.setString(3, s.alias)
      ps.setString(4, s.setupSql)
      ps.setString(5, s.description.orNull)
      ps.setBoolean(6, s.disabled)
      ps.setString(7, s.sourceType.wire)
      ps.setString(8, s.config.orNull)
      ps.setBoolean(9, s.readOnly)
      ps.executeUpdate()
    finally ps.close()
  }

  def deleteSource(id: String): Unit = withConn { c =>
    val ps = c.prepareStatement("DELETE FROM qodstate_federated_source WHERE id = ?")
    try
      ps.setString(1, id)
      ps.executeUpdate()
    finally ps.close()
  }

  def getSource(tenantDbId: String, alias: String): Option[FederatedSource] = withConn { c =>
    val ps = c.prepareStatement(
      """SELECT id, tenant_db_id, alias, setup_sql, description, disabled, created_at,
        |       source_type, config, read_only
        |FROM qodstate_federated_source WHERE tenant_db_id = ? AND alias = ?""".stripMargin
    )
    try
      ps.setString(1, tenantDbId)
      ps.setString(2, alias)
      val rs = ps.executeQuery()
      try if rs.next() then Some(readSource(rs)) else None
      finally rs.close()
    finally ps.close()
  }

  def listSources(tenantDbId: String): List[FederatedSource] = withConn { c =>
    queryWithTd(
      c,
      tenantDbId,
      """SELECT id, tenant_db_id, alias, setup_sql, description, disabled, created_at,
        |       source_type, config, read_only
        |FROM qodstate_federated_source WHERE tenant_db_id = ? ORDER BY alias""".stripMargin
    )
  }

  def listEnabledSources(tenantDbId: String): List[FederatedSource] = withConn { c =>
    queryWithTd(
      c,
      tenantDbId,
      """SELECT id, tenant_db_id, alias, setup_sql, description, disabled, created_at,
        |       source_type, config, read_only
        |FROM qodstate_federated_source
        |WHERE tenant_db_id = ? AND disabled = false ORDER BY alias""".stripMargin
    )
  }

  def tenantDbIdsWithSources(): Set[String] = withConn { c =>
    val ps = c.prepareStatement(
      "SELECT DISTINCT tenant_db_id FROM qodstate_federated_source WHERE disabled = false"
    )
    try
      val rs = ps.executeQuery()
      try
        val buf = scala.collection.mutable.Set.empty[String]
        while rs.next() do buf += rs.getString("tenant_db_id")
        buf.toSet
      finally rs.close()
    finally ps.close()
  }

  private def queryWithTd(c: Connection, tenantDbId: String, sql: String): List[FederatedSource] =
    val ps = c.prepareStatement(sql)
    try
      ps.setString(1, tenantDbId)
      val rs = ps.executeQuery()
      try drain(rs)(readSource)
      finally rs.close()
    finally ps.close()

  // ---------------- FederatedSecret ----------------

  def upsertSecret(s: FederatedSecret): Unit = withConn { c =>
    val ps = c.prepareStatement(
      """INSERT INTO qodstate_federated_secret
        |  (id, federated_source_id, name, value, external_ref)
        |VALUES (?, ?, ?, ?, ?)
        |ON CONFLICT (id) DO UPDATE SET
        |  federated_source_id = EXCLUDED.federated_source_id,
        |  name                = EXCLUDED.name,
        |  value               = EXCLUDED.value,
        |  external_ref        = EXCLUDED.external_ref""".stripMargin
    )
    try
      ps.setString(1, s.id)
      ps.setString(2, s.federatedSourceId)
      ps.setString(3, s.name)
      ps.setString(4, s.value.orNull)
      ps.setString(5, s.externalRef.orNull)
      ps.executeUpdate()
    finally ps.close()
  }

  def deleteSecret(sourceId: String, name: String): Unit = withConn { c =>
    val ps = c.prepareStatement(
      "DELETE FROM qodstate_federated_secret WHERE federated_source_id = ? AND name = ?"
    )
    try
      ps.setString(1, sourceId)
      ps.setString(2, name)
      ps.executeUpdate()
    finally ps.close()
  }

  def getSecret(sourceId: String, name: String): Option[FederatedSecret] = withConn { c =>
    val ps = c.prepareStatement(
      """SELECT id, federated_source_id, name, value, external_ref, created_at
        |FROM qodstate_federated_secret
        |WHERE federated_source_id = ? AND name = ?""".stripMargin
    )
    try
      ps.setString(1, sourceId)
      ps.setString(2, name)
      val rs = ps.executeQuery()
      try if rs.next() then Some(readSecret(rs)) else None
      finally rs.close()
    finally ps.close()
  }

  def listSecrets(sourceId: String): List[FederatedSecret] = withConn { c =>
    val ps = c.prepareStatement(
      """SELECT id, federated_source_id, name, value, external_ref, created_at
        |FROM qodstate_federated_secret
        |WHERE federated_source_id = ? ORDER BY name""".stripMargin
    )
    try
      ps.setString(1, sourceId)
      val rs = ps.executeQuery()
      try drain(rs)(readSecret)
      finally rs.close()
    finally ps.close()
  }

  // ---------------- helpers ----------------

  private def readSource(rs: ResultSet): FederatedSource =
    FederatedSource(
      id = rs.getString("id"),
      tenantDbId = rs.getString("tenant_db_id"),
      alias = rs.getString("alias"),
      // Nullable since 0038: a typed source carries no operator SQL.
      setupSql = Option(rs.getString("setup_sql")).getOrElse(""),
      description = Option(rs.getString("description")),
      disabled = rs.getBoolean("disabled"),
      createdAt = Option(rs.getTimestamp("created_at")).map(_.toInstant),
      sourceType = ai.starlake.quack.model.FederatedSourceType
        .fromWireOrSql(rs.getString("source_type")),
      config = Option(rs.getString("config")),
      readOnly = rs.getBoolean("read_only")
    )

  private def readSecret(rs: ResultSet): FederatedSecret =
    FederatedSecret(
      id = rs.getString("id"),
      federatedSourceId = rs.getString("federated_source_id"),
      name = rs.getString("name"),
      value = Option(rs.getString("value")),
      externalRef = Option(rs.getString("external_ref")),
      createdAt = Option(rs.getTimestamp("created_at")).map(_.toInstant)
    )

  private def drain[A](rs: ResultSet)(read: ResultSet => A): List[A] =
    val buf = ListBuffer.empty[A]
    while rs.next() do buf += read(rs)
    buf.toList
