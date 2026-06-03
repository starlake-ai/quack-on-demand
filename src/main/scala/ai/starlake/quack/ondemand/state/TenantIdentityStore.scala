package ai.starlake.quack.ondemand.state

import java.sql.{Connection, DriverManager}
import java.time.Instant
import java.util.UUID

/** A row in `qodstate_tenant_identity`. Maps a verified
  * `(issuer, externalId)` pair to a tenant id; the FlightSQL connect
  * path resolves the authenticated profile to a tenant via this table.
  *
  * Issuer conventions:
  *   - `"db"`               + `<username>`     : local DB user.
  *   - Keycloak realm URL   + `<realm>`        : OIDC.
  *   - `accounts.google.com`+ `<hd domain>`    : Google Workspace.
  *   - Azure AD issuer URL  + `<tenant-id>`    : Azure AD.
  *   - Cognito issuer URL   + `<userpool-id>`  : AWS Cognito. */
final case class TenantIdentity(
    id:         String,
    tenantId:   String,
    issuer:     String,
    externalId: String,
    createdAt:  Instant
)

final class TenantIdentityStore(
    jdbcUrl:  String,
    user:     String,
    password: String
):

  Class.forName("org.postgresql.Driver")

  private def withConn[A](f: Connection => A): A =
    val c = DriverManager.getConnection(jdbcUrl, user, password)
    try f(c) finally c.close()

  /** Insert a new identity row. Throws on UNIQUE violation
    * (`issuer + external_id` is globally unique). */
  def create(tenantId: String, issuer: String, externalId: String): TenantIdentity =
    val id = "ti-" + UUID.randomUUID().toString.take(8)
    withConn { c =>
      val ps = c.prepareStatement(
        """INSERT INTO qodstate_tenant_identity (id, tenant_id, issuer, external_id)
          |VALUES (?, ?, ?, ?)
          |RETURNING created_at""".stripMargin
      )
      try
        ps.setString(1, id)
        ps.setString(2, tenantId)
        ps.setString(3, issuer)
        ps.setString(4, externalId)
        val rs = ps.executeQuery()
        try
          rs.next()
          TenantIdentity(id, tenantId, issuer, externalId, rs.getTimestamp(1).toInstant)
        finally rs.close()
      finally ps.close()
    }

  /** Returns identities filtered by tenant when `tenantId` is given,
    * else all rows. Newest-first. */
  def list(tenantId: Option[String] = None): List[TenantIdentity] = withConn { c =>
    val (sql, bind) = tenantId match
      case Some(t) =>
        ("""SELECT id, tenant_id, issuer, external_id, created_at
           |FROM qodstate_tenant_identity WHERE tenant_id = ?
           |ORDER BY created_at DESC""".stripMargin, Some(t))
      case None =>
        ("""SELECT id, tenant_id, issuer, external_id, created_at
           |FROM qodstate_tenant_identity
           |ORDER BY created_at DESC""".stripMargin, None)
    val ps = c.prepareStatement(sql)
    try
      bind.foreach(ps.setString(1, _))
      val rs = ps.executeQuery()
      try
        val buf = scala.collection.mutable.ListBuffer.empty[TenantIdentity]
        while rs.next() do
          buf += TenantIdentity(
            id         = rs.getString("id"),
            tenantId   = rs.getString("tenant_id"),
            issuer     = rs.getString("issuer"),
            externalId = rs.getString("external_id"),
            createdAt  = rs.getTimestamp("created_at").toInstant
          )
        buf.toList
      finally rs.close()
    finally ps.close()
  }

  /** Resolve a verified `(issuer, externalId)` to its tenant id.
    * Hot path -- called on every authenticated connect. */
  def lookup(issuer: String, externalId: String): Option[String] = withConn { c =>
    val ps = c.prepareStatement(
      "SELECT tenant_id FROM qodstate_tenant_identity WHERE issuer = ? AND external_id = ?"
    )
    try
      ps.setString(1, issuer)
      ps.setString(2, externalId)
      val rs = ps.executeQuery()
      try if rs.next() then Some(rs.getString(1)) else None
      finally rs.close()
    finally ps.close()
  }

  def delete(id: String): Boolean = withConn { c =>
    val ps = c.prepareStatement("DELETE FROM qodstate_tenant_identity WHERE id = ?")
    try
      ps.setString(1, id)
      ps.executeUpdate() > 0
    finally ps.close()
  }

object TenantIdentityStore:
  def fromDefaultMetastore(meta: Map[String, String]): TenantIdentityStore =
    def required(k: String): String =
      meta.get(k).filter(_.nonEmpty).getOrElse(
        sys.error(s"defaultMetastore.$k must be set for TenantIdentityStore")
      )
    val host = required("pgHost")
    val port = required("pgPort")
    val user = required("pgUser")
    val pass = required("pgPassword")
    val db   = required("dbName")
    new TenantIdentityStore(s"jdbc:postgresql://$host:$port/$db", user, pass)
