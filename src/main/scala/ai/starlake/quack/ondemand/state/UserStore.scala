package ai.starlake.quack.ondemand.state

import at.favre.lib.crypto.bcrypt.BCrypt
import com.typesafe.scalalogging.LazyLogging
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

import java.sql.Connection

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
  *
  * DESIGN DECISION -- password operations are per-`(tenant, username)` row, never fan out.
  * `changePassword`, `upsertUser` (admin reset), and user creation each mutate exactly ONE row,
  * keyed by that row's id. The same username enrolled in several tenants (e.g. `alice@acme` and
  * `alice@globex`) is intentionally a set of INDEPENDENT database credentials with separate hashes;
  * rotating one leaves the others untouched. There is deliberately NO bulk "reset this username in
  * every tenant" helper -- do not add one. This applies ONLY to database-backed principals stored
  * here (the [[ai.starlake.quack.edge.auth.DatabaseAuthenticator]] path). It does not concern IdP /
  * OIDC identities (no password is stored here; the credential lives at the provider) or ROPC
  * logins (delegated to the IdP), where "change it once" is inherent to the external identity.
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
      role: String,
      mustChangePassword: Option[Boolean] = None,
      email: Option[Option[String]] = None
  ): UserStore.Upsert =
    require(
      tenant.forall(_.nonEmpty),
      "tenant must be either None (superuser) or a non-empty string"
    )
    withConn { c =>
      val hash = BCrypt.withDefaults().hashToString(12, plaintext.toCharArray)
      // Delegate to the shared upsert. enabled = None: a credential/role
      // rotation through this path must never re-enable a disabled user, so
      // the enabled column is left untouched on update (DB default on insert).
      // email: outer None leaves the stored value untouched (same rule).
      val r = UserUpsert(
        c,
        tenant,
        username,
        hash,
        role,
        enabled = None,
        mustChangePassword = mustChangePassword,
        email = email
      )
      UserStore.Upsert(id = r.id, inserted = r.inserted)
    }

  /** Self-service credential rotation: verify `currentPassword` against the stored bcrypt hash,
    * then swap in `newPassword` and clear `must_change_password` in the same statement. Unknown
    * user, wrong password, and disabled account all return the same
    * [[UserStore.ChangePasswordError.InvalidCredentials]] so the public change-password endpoint
    * cannot be used to probe which accounts exist. Password-policy checks (non-empty, differs from
    * current) live in the REST handler, not here.
    *
    * Every call pays exactly one bcrypt verify, whether or not the row exists: a missing row (or a
    * tenant-scope miss) verifies against [[UserStore.DummyHash]] instead of skipping the verify,
    * and a found row always verifies before its `enabled` flag is checked (mirroring
    * [[ai.starlake.quack.edge.auth.DatabaseAuthenticator]]'s ordering) so response latency cannot
    * be used to distinguish an unknown user from a disabled one from a live wrong-password attempt.
    */
  def changePassword(
      tenant: Option[String],
      username: String,
      currentPassword: String,
      newPassword: String
  ): Either[UserStore.ChangePasswordError, Unit] =
    withConn { c =>
      val selectSql = tenant match
        case Some(_) =>
          "SELECT id, password_hash, enabled FROM qodstate_user WHERE tenant = ? AND username = ?"
        case None =>
          "SELECT id, password_hash, enabled FROM qodstate_user WHERE tenant IS NULL AND username = ?"
      val ps  = c.prepareStatement(selectSql)
      val row =
        try
          tenant match
            case Some(t) =>
              ps.setString(1, t)
              ps.setString(2, username)
            case None =>
              ps.setString(1, username)
          val rs = ps.executeQuery()
          try
            if rs.next() then Some((rs.getString(1), rs.getString(2), rs.getBoolean(3)))
            else None
          finally rs.close()
        finally ps.close()
      row match
        case Some((id, storedHash, enabled)) =>
          val verified = BCrypt.verifyer().verify(currentPassword.toCharArray, storedHash).verified
          if verified && enabled then
            val newHash = BCrypt.withDefaults().hashToString(12, newPassword.toCharArray)
            val upd     = c.prepareStatement(
              """UPDATE qodstate_user
                |SET password_hash = ?, must_change_password = false, updated_at = NOW()
                |WHERE id = ?""".stripMargin
            )
            try
              upd.setString(1, newHash)
              upd.setString(2, id)
              upd.executeUpdate()
              Right(())
            finally upd.close()
          else Left(UserStore.ChangePasswordError.InvalidCredentials)
        case None =>
          // Row missing (or tenant-scope miss): burn the same bcrypt verify against a fixed dummy
          // hash so this path costs the same as a wrong password on a live row -- see
          // UserStore.DummyHash.
          BCrypt.verifyer().verify(currentPassword.toCharArray, UserStore.DummyHash)
          Left(UserStore.ChangePasswordError.InvalidCredentials)
    }

  /** All management-plane grants for an OIDC-verified identity. Matches `username = identity`
    * first; if that yields nothing AND `email` is given, retries with `username = email` so
    * operators can provision either form. tenant=NULL rows are superuser grants.
    *
    * The disabled check is per-row, not per-username: the same username may exist once per tenant
    * plus once as global superuser, and a multi-tenant admin must keep every enabled row's grant
    * even when another tenant's row for the same username is disabled. Three cases per lookup:
    *
    *   - rows exist, at least one enabled: return ONLY the enabled rows' grants (disabled rows
    *     dropped). The email fallback does NOT run: rows existed under this key.
    *   - rows exist, ALL disabled: deny outright (no grants) WITHOUT falling through to the email
    *     lookup. Otherwise an all-disabled username would look identical to a genuinely-missing one
    *     and an enabled email-keyed row for the same person would silently restore access.
    *   - no rows: fall back to the email lookup (same per-row logic there).
    *
    * Returns one `UserGrant` per enabled row. Order is unspecified.
    */
  def grantsForIdentity(identity: String, email: Option[String]): List[UserGrant] =
    withConn { c =>
      lookupByUsername(c, identity) match
        case Lookup.AllDisabled => Nil
        case Lookup.Found(gs)   => gs
        case Lookup.Missing     =>
          email
            .filter(e => e.nonEmpty && e != identity)
            .map(lookupByUsername(c, _))
            .map {
              case Lookup.AllDisabled => Nil
              case Lookup.Found(gs)   => gs
              case Lookup.Missing     => Nil
            }
            .getOrElse(Nil)
    }

  /** Outcome of a single username lookup, distinguishing "no rows at all" from "rows exist but
    * every one is disabled" so callers can short-circuit the all-disabled case instead of treating
    * it the same as absent. `Found` carries only the enabled rows' grants.
    */
  private enum Lookup:
    case Missing
    case AllDisabled
    case Found(grants: List[UserGrant])

  private def lookupByUsername(c: Connection, username: String): Lookup =
    // No `AND enabled` filter here: rows must be fetched regardless of
    // their enabled state so an all-disabled username can be distinguished
    // from a genuinely-missing one (see grantsForIdentity's doc).
    val ps = c.prepareStatement(
      "SELECT tenant, role, enabled FROM qodstate_user WHERE username = ?"
    )
    try
      ps.setString(1, username)
      val rs        = ps.executeQuery()
      val buf       = scala.collection.mutable.ListBuffer.empty[UserGrant]
      var rowsFound = false
      try
        while rs.next() do
          rowsFound = true
          val t       = Option(rs.getString(1)).filter(_.nonEmpty)
          val r       = Option(rs.getString(2)).getOrElse("user")
          val enabled = rs.getBoolean(3)
          if enabled then buf += UserGrant(t, r)
      finally rs.close()
      if !rowsFound then Lookup.Missing
      else if buf.isEmpty then Lookup.AllDisabled
      else Lookup.Found(buf.toList)
    finally ps.close()

object UserStore:

  /** Outcome of an upsert: the persisted id and whether the row was freshly inserted (`true`) or an
    * existing row got its password + role refreshed (`false`).
    */
  final case class Upsert(id: String, inserted: Boolean)

  /** Failure of [[UserStore.changePassword]]. Deliberately a single case: unknown user, wrong
    * current password, and disabled account are indistinguishable to the caller.
    */
  enum ChangePasswordError:
    case InvalidCredentials

  // Burned on the row-miss path so a missing/disabled account costs the same bcrypt verify
  // as a wrong password on a live one -- response latency must not become an existence oracle.
  private val DummyHash = BCrypt.withDefaults().hashToString(12, "qod-dummy".toCharArray)

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
