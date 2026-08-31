package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.Names
import ai.starlake.quack.ondemand.auth.TokenRestriction
import com.typesafe.scalalogging.LazyLogging
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

import java.security.{MessageDigest, SecureRandom}
import java.sql.{Connection, ResultSet, Timestamp}
import java.time.Instant
import java.util.Base64

/** A row in `qodstate_pat` (Liquibase `0032`, scope columns added in `0033`). `id` is a surrogate
  * `pat-<32 hex chars>`; the raw bearer token is never persisted, only its SHA-256 hash (see
  * [[PatStore.sha256Hex]]). `revokedAt` and an expired `expiresAt` both make the token permanently
  * unusable via [[PatStore.verify]]. `parentId` / `depth` place this token in the mint chain rooted
  * at a full-strength credential; `restriction` is the scope narrowed at mint time relative to the
  * parent (see `TokenRestriction.narrow`). `restriction.expiresAt` is not a second expiry: it is
  * always read back from the same `expires_at` column as the top-level `expiresAt` field (see
  * [[PatStore.rowOf]]), so the two can never drift apart.
  */
final case class PatRecord(
    id: String,
    userId: String,
    name: String,
    createdAt: Instant,
    expiresAt: Option[Instant],
    lastUsedAt: Option[Instant],
    revokedAt: Option[Instant],
    parentId: Option[String] = None,
    depth: Int = 0,
    restriction: TokenRestriction = TokenRestriction.Unrestricted
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

  /** `NULL` reads back as `None` (unrestricted); an empty array reads back as `Some(Set.empty)`
    * (nothing allowed). Collapsing the two would silently widen a token that was minted to allow
    * nothing.
    */
  private def readSet(rs: ResultSet, col: String): Option[Set[String]] =
    Option(rs.getArray(col)).map { a =>
      a.getArray.asInstanceOf[Array[AnyRef]].map(_.asInstanceOf[String]).toSet
    }

  private def writeSet(c: Connection, v: Option[Set[String]]): java.sql.Array =
    v.map(s => c.createArrayOf("text", s.toArray[AnyRef])).orNull

  /** Mint a fresh token for `userId`, scoped by `restriction` and placed at `depth` under
    * `parentId` (both `None`/`0` for a root token minted directly by a human session). Returns the
    * stored record and the raw token; the raw value is shown to the caller exactly once here and is
    * never persisted or retrievable again.
    *
    * `restriction.expiresAt` is the only source written to `expires_at`; there is no separate
    * top-level expiry input, so the clamped value produced by `TokenRestriction.narrow` is exactly
    * what gets persisted -- EXCEPT for precision: `expires_at` is `TIMESTAMPTZ` (microsecond
    * precision) and pgjdbc ROUNDS a finer-grained `Instant` on write rather than truncating it, so
    * an `Instant` carrying nanoseconds would silently become a DIFFERENT instant once read back
    * from the row. This method truncates `restriction.expiresAt` to microseconds before it is used
    * anywhere below -- including the returned `PatRecord` -- so the in-process response and every
    * later read of the same row are bit-identical for every caller, not only ones that happen to
    * pre-truncate their own input.
    *
    * When `parentId` is `Some`, the insert is a single conditional statement (`INSERT ... SELECT
    * ... WHERE EXISTS (...)`) that re-checks, in the same statement as the write, that the parent
    * is owned by `userId` and still live (neither revoked nor expired). Zero rows inserted means
    * that check failed, and this throws [[PatStore.ParentNotLiveException]]. This is a race
    * backstop, not the primary error path: a caller should pre-check with [[PatStore.findById]] and
    * answer a friendly error before ever calling `mint`, but a concurrent revoke or expiry between
    * that pre-check and this call could otherwise still let a child be minted under a parent that
    * is no longer live, and this closes that window atomically.
    */
  def mint(
      userId: String,
      name: String,
      restriction: TokenRestriction,
      parentId: Option[String],
      depth: Int
  ): (PatRecord, String) =
    // See the scaladoc above: truncate BEFORE this value is used anywhere else, so the
    // returned record, the row inserted, and a later `verify`/`findById` read all agree.
    val effective = restriction.copy(
      expiresAt = restriction.expiresAt.map(_.truncatedTo(java.time.temporal.ChronoUnit.MICROS))
    )
    val raw =
      val bytes = new Array[Byte](32)
      rnd.nextBytes(bytes)
      PatStore.TokenPrefix + Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
    val now    = clock()
    val record = PatRecord(
      id = Names.newSurrogateId("pat"),
      userId = userId,
      name = name,
      createdAt = now,
      expiresAt = effective.expiresAt,
      lastUsedAt = None,
      revokedAt = None,
      parentId = parentId,
      depth = depth,
      restriction = effective
    )
    val inserted = withConn { c =>
      val ps = c.prepareStatement(
        "INSERT INTO qodstate_pat (id, user_id, name, token_hash, created_at, expires_at, " +
          "parent_id, depth, roles, databases, pools, tools, verb_ceiling, drop_admin, " +
          "stmt_timeout_ms, max_rows) " +
          "SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ? " +
          "WHERE ? IS NULL OR EXISTS (SELECT 1 FROM qodstate_pat WHERE id = ? AND user_id = ? " +
          "AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > NOW()))"
      )
      try
        ps.setString(1, record.id)
        ps.setString(2, userId)
        ps.setString(3, name)
        ps.setString(4, PatStore.sha256Hex(raw))
        ps.setTimestamp(5, Timestamp.from(now))
        effective.expiresAt match
          case Some(e) => ps.setTimestamp(6, Timestamp.from(e))
          case None    => ps.setNull(6, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
        parentId match
          case Some(p) => ps.setString(7, p)
          case None    => ps.setNull(7, java.sql.Types.VARCHAR)
        ps.setInt(8, depth)
        ps.setArray(9, writeSet(c, effective.roles))
        ps.setArray(10, writeSet(c, effective.databases))
        ps.setArray(11, writeSet(c, effective.pools))
        ps.setArray(12, writeSet(c, effective.tools))
        effective.verbCeiling match
          case Some(v) => ps.setString(13, v)
          case None    => ps.setNull(13, java.sql.Types.VARCHAR)
        ps.setBoolean(14, effective.dropAdmin)
        effective.stmtTimeoutMs match
          case Some(t) => ps.setInt(15, t)
          case None    => ps.setNull(15, java.sql.Types.INTEGER)
        effective.maxRows match
          case Some(m) => ps.setInt(16, m)
          case None    => ps.setNull(16, java.sql.Types.INTEGER)
        // The WHERE-clause guard: `? IS NULL` (17) short-circuits the check for a root mint,
        // and the EXISTS probe (18, 19) re-reads liveness of the claimed parent under this user.
        ps.setString(17, parentId.orNull)
        ps.setString(18, parentId.orNull)
        ps.setString(19, userId)
        ps.executeUpdate()
      finally ps.close()
    }
    if inserted == 0 then throw PatStore.ParentNotLiveException(parentId.getOrElse(""))
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
            s"RETURNING ${PatStore.SelectCols}"
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
        s"SELECT ${PatStore.SelectCols} FROM qodstate_pat WHERE user_id = ? ORDER BY created_at DESC"
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

  /** Owner-scoped single-row lookup by id, live or dead. Used at mint time so a chained mint reads
    * the parent's `depth` and `restriction` fresh from the table rather than trusting a principal
    * resolved earlier in the request. `None` for another user's token and for an unknown id alike,
    * so existence under another account never leaks.
    */
  def findById(userId: String, patId: String): Option[PatRecord] =
    withConn { c =>
      val ps = c.prepareStatement(
        s"SELECT ${PatStore.SelectCols} FROM qodstate_pat WHERE id = ? AND user_id = ?"
      )
      try
        ps.setString(1, patId)
        ps.setString(2, userId)
        val rs = ps.executeQuery()
        try if rs.next() then Some(rowOf(rs)) else None
        finally rs.close()
      finally ps.close()
    }

  /** Every descendant of `rootPatId` (not including it), owner-scoped, newest first. Backs the
    * PAT-scoped `list` surfaced to a restricted caller in Task 7: it may see the tokens it minted,
    * never its own row nor a sibling subtree.
    */
  def listSubtree(userId: String, rootPatId: String): List[PatRecord] =
    withConn { c =>
      val ps = c.prepareStatement(
        s"""WITH RECURSIVE subtree AS (
           |  SELECT id FROM qodstate_pat WHERE parent_id = ? AND user_id = ?
           |  UNION ALL
           |  SELECT p.id FROM qodstate_pat p JOIN subtree s ON p.parent_id = s.id
           |    AND p.user_id = ?
           |)
           |SELECT ${PatStore.SelectCols} FROM qodstate_pat
           |WHERE id IN (SELECT id FROM subtree) ORDER BY created_at DESC""".stripMargin
      )
      try
        ps.setString(1, rootPatId)
        ps.setString(2, userId)
        ps.setString(3, userId)
        val rs  = ps.executeQuery()
        val buf = scala.collection.mutable.ListBuffer.empty[PatRecord]
        try
          while rs.next() do buf += rowOf(rs)
        finally rs.close()
        buf.toList
      finally ps.close()
    }

  /** Revoke `patId` and every descendant, in one statement. The cascade is what makes PAT-minted
    * children safe: a thief who mints a successor from a stolen token loses it the moment the
    * stolen token is revoked, so rolling forward does not work. Owner-scoped throughout: both the
    * anchor and the recursive step require `user_id = userId`, so a bad `parent_id` can never walk
    * the subtree into another user's rows even by accident.
    *
    * Returns `false` when there is nothing left to flip: the id is unknown, owned by a different
    * user, or the token and its entire subtree are already revoked. It is `true` whenever at least
    * one row in the subtree had `revoked_at IS NULL` and got revoked by this call -- note that the
    * anchor match (`id = ? AND user_id = ?`) does not itself require the anchor to be live, so if
    * `patId` were already revoked while a descendant of it was somehow still live, this call would
    * revoke that descendant and return `true` rather than `false`. That state is unreachable
    * through this store's own API: [[PatStore.mint]] refuses to place a child under a parent that
    * is not live, and a revoke always cascades to every descendant in the same statement, so an
    * already-revoked token can never have a live child to begin with.
    */
  def revoke(userId: String, patId: String): Boolean =
    withConn { c =>
      val ps = c.prepareStatement(
        """WITH RECURSIVE subtree AS (
          |  SELECT id FROM qodstate_pat WHERE id = ? AND user_id = ?
          |  UNION ALL
          |  SELECT p.id FROM qodstate_pat p JOIN subtree s ON p.parent_id = s.id
          |    AND p.user_id = ?
          |)
          |UPDATE qodstate_pat SET revoked_at = NOW()
          |WHERE id IN (SELECT id FROM subtree) AND revoked_at IS NULL""".stripMargin
      )
      try
        ps.setString(1, patId)
        ps.setString(2, userId)
        ps.setString(3, userId)
        ps.executeUpdate() >= 1
      finally ps.close()
    }

  /** Whether `candidateId` is a strict descendant of `rootPatId`, both owned by `userId`. Never
    * true for `rootPatId` itself: a token may retire what it created, never itself. Owner-scoped at
    * both the anchor and the recursive step, so a bad `parent_id` can never walk the subtree across
    * users.
    */
  def isInSubtree(userId: String, rootPatId: String, candidateId: String): Boolean =
    withConn { c =>
      val ps = c.prepareStatement(
        """WITH RECURSIVE subtree AS (
          |  SELECT id FROM qodstate_pat WHERE parent_id = ? AND user_id = ?
          |  UNION ALL
          |  SELECT p.id FROM qodstate_pat p JOIN subtree s ON p.parent_id = s.id
          |    AND p.user_id = ?
          |)
          |SELECT 1 FROM subtree WHERE id = ?""".stripMargin
      )
      try
        ps.setString(1, rootPatId)
        ps.setString(2, userId)
        ps.setString(3, userId)
        ps.setString(4, candidateId)
        val rs = ps.executeQuery()
        try rs.next()
        finally rs.close()
      finally ps.close()
    }

  /** Delete `patId`, scoped to `userId` like [[revoke]], and further restricted to rows that are
    * already unusable (revoked, or past their expiry): revoke stays the only way to kill a live
    * token, delete is pure listing cleanup and can never resurrect or retire anything. `Live`
    * reports a row the caller owns that must be revoked first; a dead row owned by someone else and
    * an unknown id are both `NotFound`, so existence under another account never leaks.
    */
  def delete(userId: String, patId: String): PatStore.DeleteOutcome =
    withConn { c =>
      val del = c.prepareStatement(
        "DELETE FROM qodstate_pat WHERE id = ? AND user_id = ? " +
          "AND (revoked_at IS NOT NULL OR (expires_at IS NOT NULL AND expires_at <= NOW()))"
      )
      val deleted =
        try
          del.setString(1, patId)
          del.setString(2, userId)
          del.executeUpdate() == 1
        finally del.close()
      if deleted then PatStore.DeleteOutcome.Deleted
      else
        // Nothing matched: either the caller owns a still-live row, or the id is
        // unknown / someone else's (indistinguishable by design).
        val probe = c.prepareStatement(
          "SELECT 1 FROM qodstate_pat WHERE id = ? AND user_id = ?"
        )
        try
          probe.setString(1, patId)
          probe.setString(2, userId)
          val rs = probe.executeQuery()
          try
            if rs.next() then PatStore.DeleteOutcome.Live else PatStore.DeleteOutcome.NotFound
          finally rs.close()
        finally probe.close()
    }

  private def rowOf(rs: ResultSet): PatRecord =
    // `expires_at` is read once here and fills both PatRecord.expiresAt (the field the existing
    // verify/list/delete logic checks) and restriction.expiresAt below: one column, two views,
    // never two sources, so a child token can never read back as outliving its parent.
    val expiresAt = Option(rs.getTimestamp("expires_at")).map(_.toInstant)
    PatRecord(
      id = rs.getString("id"),
      userId = rs.getString("user_id"),
      name = rs.getString("name"),
      createdAt = rs.getTimestamp("created_at").toInstant,
      expiresAt = expiresAt,
      lastUsedAt = Option(rs.getTimestamp("last_used_at")).map(_.toInstant),
      revokedAt = Option(rs.getTimestamp("revoked_at")).map(_.toInstant),
      parentId = Option(rs.getString("parent_id")),
      depth = rs.getInt("depth"),
      restriction = TokenRestriction(
        roles = readSet(rs, "roles"),
        databases = readSet(rs, "databases"),
        pools = readSet(rs, "pools"),
        tools = readSet(rs, "tools"),
        verbCeiling = Option(rs.getString("verb_ceiling")),
        dropAdmin = rs.getBoolean("drop_admin"),
        stmtTimeoutMs =
          Option(rs.getObject("stmt_timeout_ms")).map(_.asInstanceOf[Number].intValue),
        maxRows = Option(rs.getObject("max_rows")).map(_.asInstanceOf[Number].intValue),
        expiresAt = expiresAt
      )
    )

object PatStore:
  val TokenPrefix = "qod_pat_"

  /** Column list shared by every read path (`verify`'s `RETURNING`, `list`, `findById`,
    * `listSubtree`) so a new scope column added later only needs updating in one place.
    */
  private val SelectCols =
    "id, user_id, name, created_at, expires_at, last_used_at, revoked_at, parent_id, depth, " +
      "roles, databases, pools, tools, verb_ceiling, drop_admin, stmt_timeout_ms, max_rows"

  /** Result of [[PatStore.delete]]: only an already-dead row (revoked or expired) is deletable. */
  enum DeleteOutcome:
    case Deleted, Live, NotFound

  /** Thrown by [[PatStore.mint]] when a `parentId` was given but, at the instant of the atomic
    * insert, no row with that id was owned by the caller and live (neither revoked nor expired).
    * This is the race backstop, not the primary error path: a caller should pre-check with
    * [[PatStore.findById]] and answer a friendly 4xx before ever calling `mint` (Task 7's REST
    * handler does this). This exception exists only to close the window between that pre-check and
    * the insert, where a concurrent revoke or expiry could otherwise still let a child be minted
    * under a parent that is no longer live.
    */
  final class ParentNotLiveException(val parentId: String)
      extends RuntimeException(s"parent PAT $parentId is not live")

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
