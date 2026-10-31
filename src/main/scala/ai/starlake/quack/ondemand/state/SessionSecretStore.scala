package ai.starlake.quack.ondemand.state

import javax.sql.DataSource

/** Where the session JWT signing secret comes from: `QOD_SESSION_JWT_SECRET` when set, else the
  * control plane's, created by the first manager that boots (table `qodstate_boot_secret`, row
  * `session_jwt`). Either way every restart and every HA replica signs with the same value, so a
  * session or a password-reset link minted by one manager verifies on the next. A configured value
  * is never written to the table, and the stored row is left as it is while one is configured, so
  * unsetting it falls back to that row. Rotating the stored secret means deleting the row and
  * restarting every replica.
  */
object SessionSecretStore:

  /** The row of `qodstate_boot_secret` holding the session secret. */
  val RowName: String = "session_jwt"

  enum Source:
    /** `QOD_SESSION_JWT_SECRET` (or `auth.management.sessionJwtSecret`). */
    case Configured

    /** The control plane's, created by an earlier boot or a concurrent replica. */
    case Stored

    /** The control plane's, created by this call. */
    case Created

  /** The secret and where it came from. Its rendering never carries the value. */
  final case class Resolved(secret: String, source: Source):
    override def toString: String = s"Resolved(<redacted>, $source)"

  /** The secret: `configured` as given when non-empty after trimming (the value Starflow verifies
    * with too, so it is not altered), else [[loadOrCreate]]. `controlPlane` is by name: a
    * configured secret never touches it.
    */
  def resolve(configured: String, controlPlane: => DataSource): Resolved =
    if configured.trim.nonEmpty then Resolved(configured, Source.Configured)
    else loadOrCreate(controlPlane)

  /** The control plane's secret, inserted first when absent. `ON CONFLICT DO NOTHING` then a read:
    * replicas booting at once all read the one row that won. Postgres generates the value (96 hex
    * digits from three `gen_random_uuid()`, core since PostgreSQL 13; at least as strong as
    * `SessionTokenStore.randomSecret()`), so it never travels as a bind parameter, which statement
    * logging or pgaudit would record.
    */
  def loadOrCreate(ds: DataSource): Resolved =
    val c = ds.getConnection
    try
      val insert  = c.createStatement()
      val created =
        try
          insert.executeUpdate(
            s"INSERT INTO qodstate_boot_secret (name, value) VALUES ('$RowName', " +
              "replace(gen_random_uuid()::text || gen_random_uuid()::text || " +
              "gen_random_uuid()::text, '-', '')) ON CONFLICT (name) DO NOTHING"
          ) == 1
        finally insert.close()
      val st = c.createStatement()
      try
        val rs =
          st.executeQuery(s"SELECT value FROM qodstate_boot_secret WHERE name = '$RowName'")
        if !rs.next() then
          throw new IllegalStateException(s"qodstate_boot_secret holds no $RowName row")
        val value = rs.getString(1)
        // HS256 needs a key of at least 32 bytes; a hand-edited row would otherwise fail every
        // session mint with an opaque signer error.
        if value.trim.length < 32 then
          throw new IllegalStateException(
            s"the stored session secret (qodstate_boot_secret row $RowName) is shorter than 32 " +
              s"characters: delete the row (DELETE FROM qodstate_boot_secret WHERE name = " +
              s"'$RowName') and restart, or set QOD_SESSION_JWT_SECRET"
          )
        Resolved(value, if created then Source.Created else Source.Stored)
      finally st.close()
    finally c.close()
