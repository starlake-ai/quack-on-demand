package ai.starlake.quack.ondemand.state

import at.favre.lib.crypto.bcrypt.BCrypt
import com.typesafe.scalalogging.LazyLogging
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

import java.sql.{Connection, Types}

/** Manages the `qodstate_user` table -- the principal directory used by
  * [[ai.starlake.quack.edge.auth.DatabaseAuthenticator]] and as the FK target for the RBAC
  * user-group / user-role / pool-permission edges.
  *
  * Schema (owned by Liquibase changelogs `0003-user-table.yaml` + `0006-rbac.yaml`):
  * {{{
  *   CREATE TABLE qodstate_user (
  *     id            TEXT PRIMARY KEY,                -- u-<8 hex>
  *     tenant        TEXT NULL,                       -- NULL = superuser
  *     username      TEXT NOT NULL,
  *     password_hash TEXT NOT NULL,
  *     role          TEXT NOT NULL DEFAULT 'user',    -- free-text auth label
  *     created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  *     updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
  *   );
  *   -- partial unique indexes:
  *   --   admin   (tenant IS NULL):     UNIQUE (username)
  *   --   scoped  (tenant IS NOT NULL): UNIQUE (tenant, username)
  *   -- CHECK (tenant IS NULL OR length(tenant) > 0)
  * }}}
  *
  * A user is identified by `(tenant, username)`. `(NULL, name)` is a superuser; tenant-scoped
  * principals carry a non-empty tenant. Pool access is granted through [[PoolPermission]] rows, not
  * on the user row itself.
  */
final class UserStore(
    jdbcUrl: String,
    dbUser: String,
    dbPassword: String,
    poolSize: Int = 10
) extends LazyLogging:

  // Force driver registration so the JDBC URL resolves before HikariCP
  // probes the connection.
  Class.forName("org.postgresql.Driver")

  private val dataSource: HikariDataSource =
    val hc = new HikariConfig()
    hc.setJdbcUrl(jdbcUrl)
    hc.setUsername(dbUser)
    hc.setPassword(dbPassword)
    hc.setMaximumPoolSize(poolSize)
    hc.setMinimumIdle(math.min(2, poolSize))
    hc.setConnectionTimeout(5000)
    hc.setPoolName("qod-user-store")
    new HikariDataSource(hc)

  private def withConn[A](f: Connection => A): A =
    val c = dataSource.getConnection
    try f(c)
    finally c.close()

  /** Release the pool's idle connections. Idempotent. Called from Main's shutdown hook. */
  def close(): Unit = if !dataSource.isClosed then dataSource.close()

  /** Upsert a user identified by `(tenant, username)`. `tenant = None` is a superuser. Returns the
    * persisted row (with id assigned if the row is new, or reused if it already existed).
    *
    * On a new row the id is freshly generated (`u-<8 hex>`); on an update the existing id is
    * preserved -- callers (RBAC membership edges, pool-permission grants) keep their FK targets
    * across password rotations.
    */
  def upsertUser(
      tenant: Option[String],
      username: String,
      plaintext: String,
      role: String
  ): UserStore.Upsert =
    require(
      tenant.forall(_.nonEmpty),
      "tenant must be either None (superuser) or a non-empty string"
    )
    withConn { c =>
      val hash = BCrypt.withDefaults().hashToString(12, plaintext.toCharArray)
      // Two queries: look up the existing id first so the upsert can
      // preserve it. The partial unique indexes (admin vs scoped) mean
      // ON CONFLICT cannot target a single index without knowing the
      // tenant kind, so the lookup is the cleanest path.
      val existing = lookupId(c, tenant, username)
      val id       = existing.getOrElse(UserStore.newId())
      val sql      =
        """INSERT INTO qodstate_user (id, tenant, username, password_hash, role, updated_at)
          |VALUES (?, ?, ?, ?, ?, NOW())
          |ON CONFLICT (id) DO UPDATE SET
          |  password_hash = EXCLUDED.password_hash,
          |  role          = EXCLUDED.role,
          |  updated_at    = NOW()""".stripMargin
      val ps = c.prepareStatement(sql)
      try
        ps.setString(1, id)
        tenant match
          case Some(t) => ps.setString(2, t)
          case None    => ps.setNull(2, Types.VARCHAR)
        ps.setString(3, username)
        ps.setString(4, hash)
        ps.setString(5, role)
        ps.executeUpdate()
        UserStore.Upsert(id = id, inserted = existing.isEmpty)
      finally ps.close()
    }

  /** All management-plane grants for an OIDC-verified identity. Matches `username = identity`
    * first; if that yields nothing AND `email` is given, retries with `username = email` so
    * operators can provision either form. tenant=NULL rows are superuser grants.
    *
    * Returns one `UserGrant` per row. Order is unspecified.
    */
  def grantsForIdentity(identity: String, email: Option[String]): List[UserGrant] =
    withConn { c =>
      val first = grantsByUsername(c, identity)
      if first.nonEmpty then first
      else
        email
          .filter(e => e.nonEmpty && e != identity)
          .map(grantsByUsername(c, _))
          .getOrElse(Nil)
    }

  private def grantsByUsername(c: Connection, username: String): List[UserGrant] =
    // A disabled user contributes no grants: the OIDC callback then hits the
    // not_provisioned gate instead of minting a session, mirroring the
    // password-login and FlightSQL-handshake enforcement of the same flag.
    val ps = c.prepareStatement(
      "SELECT tenant, role FROM qodstate_user WHERE username = ? AND enabled"
    )
    try
      ps.setString(1, username)
      val rs  = ps.executeQuery()
      val buf = scala.collection.mutable.ListBuffer.empty[UserGrant]
      try
        while rs.next() do
          val t = Option(rs.getString(1)).filter(_.nonEmpty)
          val r = Option(rs.getString(2)).getOrElse("user")
          buf += UserGrant(t, r)
      finally rs.close()
      buf.toList
    finally ps.close()

  private def lookupId(c: Connection, tenant: Option[String], username: String): Option[String] =
    val ps = tenant match
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
    try
      val rs = ps.executeQuery()
      try if rs.next() then Some(rs.getString(1)) else None
      finally rs.close()
    finally ps.close()

object UserStore:

  /** Outcome of an upsert: the persisted id and whether the row was freshly inserted (`true`) or an
    * existing row got its password + role refreshed (`false`).
    */
  final case class Upsert(id: String, inserted: Boolean)

  private def newId(): String = ai.starlake.quack.model.Names.newSurrogateId("u")

  /** Build a store from the global `defaultMetastore` map. Same shape as
    * `PostgresStateStore.fromDefaultMetastore` so the user table lives next to the state table by
    * default.
    */
  def fromDefaultMetastore(meta: Map[String, String]): UserStore =
    def required(k: String) =
      meta
        .get(k)
        .filter(_.nonEmpty)
        .getOrElse(
          sys.error(s"defaultMetastore.$k must be set for UserStore")
        )
    val host = required("pgHost")
    val port = required("pgPort")
    val user = required("pgUser")
    val pass = required("pgPassword")
    val db   = required("dbName")
    val url  = s"jdbc:postgresql://$host:$port/$db"
    new UserStore(url, user, pass)
