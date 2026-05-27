package ai.starlake.quack.ondemand.state

import java.sql.{Connection, DriverManager, Timestamp}
import java.time.Instant

/** Relational storage for ACL grants in the same Postgres database as the
  * rest of the slkstate_* control-plane tables.
  *
  * Schema:
  * {{{
  *   CREATE TABLE slkstate_acl_grant (
  *     id            BIGSERIAL PRIMARY KEY,
  *     tenant_id     TEXT NOT NULL,
  *     principal     TEXT NOT NULL,                  -- e.g. "user:alice", "group:eng"
  *     catalog_name  TEXT,                            -- NULL = any
  *     schema_name   TEXT,                            -- NULL = any
  *     table_name    TEXT,                            -- NULL = any
  *     permission    TEXT NOT NULL,                   -- SELECT|INSERT|UPDATE|DELETE|ALL
  *     granted_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  *     UNIQUE (tenant_id, principal, catalog_name, schema_name, table_name, permission)
  *   )
  * }}}
  *
  * Phase 1: this store persists grants. The SQL validator does not yet
  * consult it (still using the file-based AclStore). Phase 2 will plug
  * it in. */
final class AclGrantStore(jdbcUrl: String, user: String, password: String):

  Class.forName("org.postgresql.Driver")

  private def withConn[A](f: Connection => A): A =
    val c = DriverManager.getConnection(jdbcUrl, user, password)
    try f(c) finally c.close()

  def ensureTable(): Unit = withConn { c =>
    val st = c.createStatement()
    try
      st.execute(
        """CREATE TABLE IF NOT EXISTS slkstate_acl_grant (
          |  id            BIGSERIAL PRIMARY KEY,
          |  tenant_id     TEXT NOT NULL,
          |  principal     TEXT NOT NULL,
          |  catalog_name  TEXT,
          |  schema_name   TEXT,
          |  table_name    TEXT,
          |  permission    TEXT NOT NULL,
          |  granted_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
          |)""".stripMargin
      )
      // NULLs compare as distinct in UNIQUE constraints under default
      // semantics, which we want here (so a row with catalog=NULL is
      // distinct from catalog='tpch'). Postgres 15+ supports NULLS NOT
      // DISTINCT but we keep the default for backwards compat.
      st.execute(
        """CREATE UNIQUE INDEX IF NOT EXISTS slkstate_acl_grant_uniq
          |  ON slkstate_acl_grant
          |  (tenant_id, principal, COALESCE(catalog_name, ''),
          |   COALESCE(schema_name, ''), COALESCE(table_name, ''), permission)""".stripMargin
      )
    finally st.close()
  }

  /** Insert a new grant. Throws on duplicate (caller should 409). Returns
    * the assigned id and granted_at timestamp. */
  def insert(g: AclGrant): AclGrant = withConn { c =>
    ensureTable()
    val ps = c.prepareStatement(
      """INSERT INTO slkstate_acl_grant
        |  (tenant_id, principal, catalog_name, schema_name, table_name, permission)
        |VALUES (?, ?, ?, ?, ?, ?)
        |RETURNING id, granted_at""".stripMargin
    )
    try
      ps.setString(1, g.tenantId)
      ps.setString(2, g.principal)
      setNullable(ps, 3, g.catalogName)
      setNullable(ps, 4, g.schemaName)
      setNullable(ps, 5, g.tableName)
      ps.setString(6, g.permission.toUpperCase)
      val rs = ps.executeQuery()
      try
        rs.next()
        g.copy(
          id        = Some(rs.getLong("id")),
          grantedAt = Some(rs.getTimestamp("granted_at").toInstant)
        )
      finally rs.close()
    finally ps.close()
  }

  /** List grants, optionally filtered by tenant. Ordered newest first. */
  def list(tenantFilter: Option[String]): List[AclGrant] = withConn { c =>
    ensureTable()
    val sql = tenantFilter match
      case Some(_) => "SELECT * FROM slkstate_acl_grant WHERE tenant_id = ? ORDER BY granted_at DESC"
      case None    => "SELECT * FROM slkstate_acl_grant ORDER BY granted_at DESC"
    val ps = c.prepareStatement(sql)
    try
      tenantFilter.foreach(ps.setString(1, _))
      val rs = ps.executeQuery()
      try
        val buf = scala.collection.mutable.ListBuffer.empty[AclGrant]
        while rs.next() do
          buf += AclGrant(
            id          = Some(rs.getLong("id")),
            tenantId    = rs.getString("tenant_id"),
            principal   = rs.getString("principal"),
            catalogName = Option(rs.getString("catalog_name")),
            schemaName  = Option(rs.getString("schema_name")),
            tableName   = Option(rs.getString("table_name")),
            permission  = rs.getString("permission"),
            grantedAt   = Option(rs.getTimestamp("granted_at")).map(_.toInstant)
          )
        buf.toList
      finally rs.close()
    finally ps.close()
  }

  /** Returns true if any of the given principals has a grant covering the
    * named table with one of the required permissions. Wildcards apply:
    * a row with `catalog_name IS NULL` matches any catalog (same for
    * schema/table). `ALL` is always an acceptable match.
    *
    * Empty `principals` returns false (no one has access). Empty
    * `requiredPerms` is treated as "any permission". */
  def hasAccess(
      tenantId: String,
      principals: Set[String],
      catalog: String,
      schema: String,
      table: String,
      requiredPerms: Set[String]
  ): Boolean =
    if principals.isEmpty then false
    else withConn { c =>
      ensureTable()
      val principalPlaceholders = principals.toList.map(_ => "?").mkString(",")
      // Always treat ALL as accepted; otherwise restrict to required perms.
      val permClause = requiredPerms match
        case s if s.isEmpty => ""
        case s              =>
          " AND permission IN ('ALL'," + s.map(_ => "?").mkString(",") + ")"
      val sql =
        s"""SELECT 1 FROM slkstate_acl_grant
           |WHERE tenant_id = ?
           |  AND principal IN ($principalPlaceholders)
           |  AND (catalog_name IS NULL OR catalog_name = ?)
           |  AND (schema_name  IS NULL OR schema_name  = ?)
           |  AND (table_name   IS NULL OR table_name   = ?)
           |  $permClause
           |LIMIT 1""".stripMargin
      val ps = c.prepareStatement(sql)
      try
        var i = 1
        ps.setString(i, tenantId); i += 1
        principals.foreach { p => ps.setString(i, p); i += 1 }
        ps.setString(i, catalog); i += 1
        ps.setString(i, schema);  i += 1
        ps.setString(i, table);   i += 1
        requiredPerms.foreach { p => ps.setString(i, p.toUpperCase); i += 1 }
        val rs = ps.executeQuery()
        try rs.next() finally rs.close()
      finally ps.close()
    }

  /** Delete a grant by id. Returns true if a row was deleted. */
  def delete(id: Long): Boolean = withConn { c =>
    ensureTable()
    val ps = c.prepareStatement("DELETE FROM slkstate_acl_grant WHERE id = ?")
    try
      ps.setLong(1, id)
      ps.executeUpdate() > 0
    finally ps.close()
  }

  private def setNullable(
      ps: java.sql.PreparedStatement, idx: Int, v: Option[String]
  ): Unit = v match
    case Some(s) => ps.setString(idx, s)
    case None    => ps.setNull(idx, java.sql.Types.VARCHAR)

object AclGrantStore:
  def fromDefaultMetastore(meta: Map[String, String]): AclGrantStore =
    def required(k: String) =
      meta.get(k).filter(_.nonEmpty).getOrElse(
        sys.error(s"defaultMetastore.$k must be set for AclGrantStore")
      )
    val host = required("pgHost")
    val port = required("pgPort")
    val user = required("pgUser")
    val pass = required("pgPassword")
    val db   = required("dbName")
    val url  = s"jdbc:postgresql://$host:$port/$db"
    new AclGrantStore(url, user, pass)