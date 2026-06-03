package ai.starlake.quack.ondemand.state

import at.favre.lib.crypto.bcrypt.BCrypt
import com.typesafe.scalalogging.LazyLogging

import java.sql.{Connection, DriverManager, Types}

/** Manages the `qodstate_user` table used by `DatabaseAuthenticator`.
  *
  * Schema (owned by Liquibase changelog `0003-user-table.yaml`):
  * {{{
  *   CREATE TABLE qodstate_user (
  *     tenant        TEXT NULL,
  *     pool          TEXT NULL,
  *     username      TEXT NOT NULL,
  *     password_hash TEXT NOT NULL,
  *     role          TEXT NOT NULL DEFAULT 'user',
  *     created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  *     updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
  *   );
  *   -- partial unique indexes:
  *   --   admin   (tenant IS NULL AND pool IS NULL): UNIQUE (username)
  *   --   scoped  (tenant IS NOT NULL AND pool IS NOT NULL): UNIQUE (tenant, pool, username)
  *   -- CHECK ((tenant IS NULL) = (pool IS NULL))
  * }}}
  *
  * A user is identified by `(tenant, pool, username)`. `(NULL, NULL, name)`
  * is the system admin (manager UI / REST). Non-NULL rows are tenant-scoped
  * principals that the FlightSQL edge looks up using the `tenant` and
  * `pool` headers (or URL params) attached to the connection. */
final class UserStore(jdbcUrl: String, dbUser: String, dbPassword: String) extends LazyLogging:

  // Force driver registration for the same reason as PostgresStateStore.
  Class.forName("org.postgresql.Driver")

  private def withConn[A](f: Connection => A): A =
    val c = DriverManager.getConnection(jdbcUrl, dbUser, dbPassword)
    try f(c) finally c.close()

  /** Upsert a user. Both `tenant` and `pool` must be either both `Some`
    * (tenant-scoped principal) or both `None` (system admin) -- the
    * `qodstate_user_scope_consistency` CHECK constraint enforces this.
    *
    * Returns `true` if a new row was inserted, `false` if an existing row
    * was updated. */
  def upsertUser(
      tenant:    Option[String],
      pool:      Option[String],
      username:  String,
      plaintext: String,
      role:      String
  ): Boolean =
    require((tenant.isEmpty) == (pool.isEmpty),
            "tenant and pool must be both set or both empty")
    withConn { c =>
      val hash = BCrypt.withDefaults().hashToString(12, plaintext.toCharArray)
      // ON CONFLICT can only target one unique index at a time, and our
      // unique indexes are partial. Pick the right one based on whether
      // the row is system or scoped.
      val sql =
        if tenant.isEmpty then
          """INSERT INTO qodstate_user (tenant, pool, username, password_hash, role, updated_at)
            |VALUES (NULL, NULL, ?, ?, ?, NOW())
            |ON CONFLICT (username) WHERE tenant IS NULL AND pool IS NULL DO UPDATE SET
            |  password_hash = EXCLUDED.password_hash,
            |  role          = EXCLUDED.role,
            |  updated_at    = NOW()
            |RETURNING (xmax = 0) AS inserted""".stripMargin
        else
          """INSERT INTO qodstate_user (tenant, pool, username, password_hash, role, updated_at)
            |VALUES (?, ?, ?, ?, ?, NOW())
            |ON CONFLICT (tenant, pool, username) WHERE tenant IS NOT NULL AND pool IS NOT NULL DO UPDATE SET
            |  password_hash = EXCLUDED.password_hash,
            |  role          = EXCLUDED.role,
            |  updated_at    = NOW()
            |RETURNING (xmax = 0) AS inserted""".stripMargin
      val ps = c.prepareStatement(sql)
      try
        if tenant.isEmpty then
          ps.setString(1, username)
          ps.setString(2, hash)
          ps.setString(3, role)
        else
          ps.setString(1, tenant.get)
          ps.setString(2, pool.get)
          ps.setString(3, username)
          ps.setString(4, hash)
          ps.setString(5, role)
        val rs = ps.executeQuery()
        try
          rs.next()
          rs.getBoolean("inserted")
        finally rs.close()
      finally ps.close()
    }

object UserStore:

  /** Build a store from the global `defaultMetastore` map. Same shape as
    * `PostgresStateStore.fromDefaultMetastore` so the user table lives next
    * to the state table by default. */
  def fromDefaultMetastore(meta: Map[String, String]): UserStore =
    def required(k: String) =
      meta.get(k).filter(_.nonEmpty).getOrElse(
        sys.error(s"defaultMetastore.$k must be set for UserStore")
      )
    val host = required("pgHost")
    val port = required("pgPort")
    val user = required("pgUser")
    val pass = required("pgPassword")
    val db   = required("dbName")
    val url  = s"jdbc:postgresql://$host:$port/$db"
    new UserStore(url, user, pass)