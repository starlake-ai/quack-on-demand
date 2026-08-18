package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.Names
import com.typesafe.scalalogging.LazyLogging
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

import java.security.{MessageDigest, SecureRandom}
import java.sql.{Connection, ResultSet, Timestamp}
import java.time.Instant
import java.util.Base64

/** A row in `qodstate_pat` (Liquibase `0032`). `id` is a surrogate `pat-<32 hex chars>`; the raw
  * bearer token is never persisted, only its SHA-256 hash (see [[PatStore.sha256Hex]]). `revokedAt`
  * and an expired `expiresAt` both make the token permanently unusable via [[PatStore.verify]].
  */
final case class PatRecord(
    id: String,
    userId: String,
    name: String,
    createdAt: Instant,
    expiresAt: Option[Instant],
    lastUsedAt: Option[Instant],
    revokedAt: Option[Instant]
)

/** Manages the `qodstate_pat` table -- personal access tokens, long-lived bearer credentials for
  * agents (MCP) and scripts that authenticate as a `qodstate_user` row without a password.
  *
  * Mint returns the raw token exactly once; only its SHA-256 hash is stored (`token_hash`, unique).
  * `verify` looks the hash up, rejects a revoked or expired row, and stamps `last_used_at` on a
  * live hit so operators can see which tokens are actually in use. `revoke` is scoped to the owning
  * user: a caller can never revoke another user's token, mirroring `UserStore`'s per-row semantics.
  */
final class PatStore(
    jdbcUrl: String,
    dbUser: String,
    dbPassword: String,
    poolSize: Int = 5,
    clock: () => Instant = () => Instant.now()
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
    hc.setPoolName("qod-pat-store")
    new HikariDataSource(hc)

  private def withConn[A](f: Connection => A): A =
    val c = dataSource.getConnection
    try f(c)
    finally c.close()

  /** Release the pool's idle connections. Idempotent. Called from Main's shutdown hook. */
  def close(): Unit = if !dataSource.isClosed then dataSource.close()

  private val rnd = new SecureRandom()

  /** Mint a fresh token for `userId`. Returns the stored record and the raw token; the raw value is
    * shown to the caller exactly once here and is never persisted or retrievable again.
    */
  def mint(userId: String, name: String, expiresAt: Option[Instant]): (PatRecord, String) =
    val raw =
      val bytes = new Array[Byte](32)
      rnd.nextBytes(bytes)
      PatStore.TokenPrefix + Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
    val now    = clock()
    val record = PatRecord(Names.newSurrogateId("pat"), userId, name, now, expiresAt, None, None)
    withConn { c =>
      val ps = c.prepareStatement(
        "INSERT INTO qodstate_pat (id, user_id, name, token_hash, created_at, expires_at) " +
          "VALUES (?, ?, ?, ?, ?, ?)"
      )
      try
        ps.setString(1, record.id)
        ps.setString(2, userId)
        ps.setString(3, name)
        ps.setString(4, PatStore.sha256Hex(raw))
        ps.setTimestamp(5, Timestamp.from(now))
        expiresAt match
          case Some(e) => ps.setTimestamp(6, Timestamp.from(e))
          case None    => ps.setNull(6, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
        ps.executeUpdate()
        ()
      finally ps.close()
    }
    (record, raw)

  /** Live-token lookup: `None` on an unrecognized hash, a revoked row, an expired row, or a value
    * that doesn't even carry [[PatStore.TokenPrefix]]. On a live hit, stamps `last_used_at` in the
    * same statement.
    */
  def verify(token: String): Option[PatRecord] =
    if !token.startsWith(PatStore.TokenPrefix) then None
    else
      withConn { c =>
        val ps = c.prepareStatement(
          "UPDATE qodstate_pat SET last_used_at = NOW() WHERE token_hash = ? " +
            "AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > NOW()) " +
            "RETURNING id, user_id, name, created_at, expires_at, last_used_at, revoked_at"
        )
        try
          ps.setString(1, PatStore.sha256Hex(token))
          val rs = ps.executeQuery()
          try if rs.next() then Some(rowOf(rs)) else None
          finally rs.close()
        finally ps.close()
      }

  /** All PATs (live and revoked/expired) owned by `userId`, newest first. */
  def list(userId: String): List[PatRecord] =
    withConn { c =>
      val ps = c.prepareStatement(
        "SELECT id, user_id, name, created_at, expires_at, last_used_at, revoked_at " +
          "FROM qodstate_pat WHERE user_id = ? ORDER BY created_at DESC"
      )
      try
        ps.setString(1, userId)
        val rs  = ps.executeQuery()
        val buf = scala.collection.mutable.ListBuffer.empty[PatRecord]
        try
          while rs.next() do buf += rowOf(rs)
        finally rs.close()
        buf.toList
      finally ps.close()
    }

  /** Revoke `patId`, scoped to `userId`: returns `false` (no-op) for a token owned by a different
    * user, an already-revoked token, or an unknown id, so a caller can never revoke someone else's
    * token nor learn whether an id exists under another account.
    */
  def revoke(userId: String, patId: String): Boolean =
    withConn { c =>
      val ps = c.prepareStatement(
        "UPDATE qodstate_pat SET revoked_at = NOW() WHERE id = ? AND user_id = ? AND revoked_at IS NULL"
      )
      try
        ps.setString(1, patId)
        ps.setString(2, userId)
        ps.executeUpdate() == 1
      finally ps.close()
    }

  private def rowOf(rs: ResultSet): PatRecord =
    PatRecord(
      id = rs.getString("id"),
      userId = rs.getString("user_id"),
      name = rs.getString("name"),
      createdAt = rs.getTimestamp("created_at").toInstant,
      expiresAt = Option(rs.getTimestamp("expires_at")).map(_.toInstant),
      lastUsedAt = Option(rs.getTimestamp("last_used_at")).map(_.toInstant),
      revokedAt = Option(rs.getTimestamp("revoked_at")).map(_.toInstant)
    )

object PatStore:
  val TokenPrefix = "qod_pat_"

  def sha256Hex(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8")).map(b => f"$b%02x").mkString

  /** Build a store from the global `defaultMetastore` map. Same shape as
    * `UserStore.fromDefaultMetastore` so the PAT table lives next to the user table by default.
    */
  def fromDefaultMetastore(meta: Map[String, String]): PatStore =
    def required(k: String) =
      meta
        .get(k)
        .filter(_.nonEmpty)
        .getOrElse(
          sys.error(s"defaultMetastore.$k must be set for PatStore")
        )
    val host = required("pgHost")
    val port = required("pgPort")
    val user = required("pgUser")
    val pass = required("pgPassword")
    val db   = required("dbName")
    val url  = s"jdbc:postgresql://$host:$port/$db"
    new PatStore(url, user, pass)
