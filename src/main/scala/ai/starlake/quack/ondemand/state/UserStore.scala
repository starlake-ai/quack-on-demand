package ai.starlake.quack.ondemand.state

import at.favre.lib.crypto.bcrypt.BCrypt
import com.typesafe.scalalogging.LazyLogging

import java.sql.{Connection, DriverManager}

/** Manages the `slkstate_user` table used by `DatabaseAuthenticator`.
  *
  * Schema:
  * {{{
  *   CREATE TABLE slkstate_user (
  *     username      TEXT PRIMARY KEY,
  *     password_hash TEXT NOT NULL,
  *     role          TEXT NOT NULL DEFAULT 'user',
  *     created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  *     updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
  *   )
  * }}}
  *
  * Column shape matches the default `auth.database.query`:
  * `SELECT password_hash, role FROM slkstate_user WHERE username = ?`
  *
  * On manager startup we upsert the configured admin user so the DB auth
  * backend has at least one credential, and so rotating the admin password
  * is just an env-var + restart. Re-hashing on every boot is fine — bcrypt
  * is intentionally slow but a one-shot at startup is unnoticeable. */
final class UserStore(jdbcUrl: String, user: String, password: String) extends LazyLogging:

  // Force driver registration for the same reason as PostgresStateStore.
  Class.forName("org.postgresql.Driver")

  private def withConn[A](f: Connection => A): A =
    val c = DriverManager.getConnection(jdbcUrl, user, password)
    try f(c) finally c.close()

  /** Ensure the `slkstate_user` table exists and the named user is present
    * with the given password (bcrypt-hashed) and role. Returns `true` if a
    * row was inserted, `false` if an existing row was updated. */
  def upsertUser(username: String, plaintext: String, role: String): Boolean = withConn { c =>
    ensureTable(c)
    val hash = BCrypt.withDefaults().hashToString(12, plaintext.toCharArray)
    val ps = c.prepareStatement(
      """INSERT INTO slkstate_user (username, password_hash, role, updated_at)
        |VALUES (?, ?, ?, NOW())
        |ON CONFLICT (username) DO UPDATE SET
        |  password_hash = EXCLUDED.password_hash,
        |  role          = EXCLUDED.role,
        |  updated_at    = NOW()
        |RETURNING (xmax = 0) AS inserted""".stripMargin
    )
    try
      ps.setString(1, username)
      ps.setString(2, hash)
      ps.setString(3, role)
      val rs = ps.executeQuery()
      try
        rs.next()
        rs.getBoolean("inserted")
      finally rs.close()
    finally ps.close()
  }

  private def ensureTable(c: Connection): Unit =
    val st = c.createStatement()
    try
      st.execute(
        """CREATE TABLE IF NOT EXISTS slkstate_user (
          |  username      TEXT PRIMARY KEY,
          |  password_hash TEXT NOT NULL,
          |  role          TEXT NOT NULL DEFAULT 'user',
          |  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
          |  updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
          |)""".stripMargin
      )
    finally st.close()

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