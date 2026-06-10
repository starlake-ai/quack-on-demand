package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.{FederatedSecret, FederatedSource}

import java.sql.{Connection, DriverManager, ResultSet}
import scala.collection.mutable.ListBuffer

/** Postgres-backed CRUD against `qodstate_federated_source` and `qodstate_federated_secret`.
  * Cascade-delete on source -> secret is enforced by the FK constraint, so deleting a source
  * automatically wipes its secrets.
  */
class FederatedSourceStore(
    jdbcUrl: String,
    user: String,
    password: String
):

  Class.forName("org.postgresql.Driver")

  private def withConn[A](f: Connection => A): A =
    val c = DriverManager.getConnection(jdbcUrl, user, password)
    try f(c)
    finally c.close()

  // ---------------- FederatedSource ----------------

  def upsertSource(s: FederatedSource): Unit = withConn { c =>
    val ps = c.prepareStatement(
      """INSERT INTO qodstate_federated_source
        |  (id, tenant_db_id, alias, setup_sql, description, disabled)
        |VALUES (?, ?, ?, ?, ?, ?)
        |ON CONFLICT (id) DO UPDATE SET
        |  tenant_db_id = EXCLUDED.tenant_db_id,
        |  alias        = EXCLUDED.alias,
        |  setup_sql    = EXCLUDED.setup_sql,
        |  description  = EXCLUDED.description,
        |  disabled     = EXCLUDED.disabled""".stripMargin
    )
    try
      ps.setString(1, s.id)
      ps.setString(2, s.tenantDbId)
      ps.setString(3, s.alias)
      ps.setString(4, s.setupSql)
      ps.setString(5, s.description.orNull)
      ps.setBoolean(6, s.disabled)
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
      """SELECT id, tenant_db_id, alias, setup_sql, description, disabled, created_at
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
      """SELECT id, tenant_db_id, alias, setup_sql, description, disabled, created_at
        |FROM qodstate_federated_source WHERE tenant_db_id = ? ORDER BY alias""".stripMargin
    )
  }

  def listEnabledSources(tenantDbId: String): List[FederatedSource] = withConn { c =>
    queryWithTd(
      c,
      tenantDbId,
      """SELECT id, tenant_db_id, alias, setup_sql, description, disabled, created_at
        |FROM qodstate_federated_source
        |WHERE tenant_db_id = ? AND disabled = false ORDER BY alias""".stripMargin
    )
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
      setupSql = rs.getString("setup_sql"),
      description = Option(rs.getString("description")),
      disabled = rs.getBoolean("disabled"),
      createdAt = Option(rs.getTimestamp("created_at")).map(_.toInstant)
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
