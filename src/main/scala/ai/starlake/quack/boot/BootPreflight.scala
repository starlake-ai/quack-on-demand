package ai.starlake.quack.boot

import ai.starlake.quack.AdminConfig
import ai.starlake.quack.ManagerConfig
import ai.starlake.quack.edge.auth.AuthQueryPreconditions
import ai.starlake.quack.edge.config.DatabaseAuthConfig
import ai.starlake.quack.ondemand.api.SessionTokenStore
import ai.starlake.quack.ondemand.state.EmailFormat
import ai.starlake.quack.ondemand.state.UserStore
import com.typesafe.scalalogging.LazyLogging

/** Boot-time gates and seeding that must run before the manager wires its components. Extracted
  * from Main.bootManager; every check keeps its original fail-fast behavior (sys.error aborts the
  * boot with a clean config-error framing instead of a raw stack trace).
  */
object BootPreflight extends LazyLogging:

  /** Resolve the two boot secrets an operator may leave unset: the session JWT signing secret and
    * the static admin API key. Outside HA, an unset (or empty) value is replaced with a fresh
    * random one for this boot, and the generated values are printed to stdout -- deliberately NOT
    * through the logger, whose default ERROR root level would swallow an info line -- so a
    * first-run operator cannot miss them. Under HA the config is returned unchanged: a per-replica
    * random secret cannot verify sessions minted by other replicas (HaPreconditions refuses the
    * empty secret), and a per-replica API key behind one load balancer would authenticate on only
    * the replica that minted it.
    */
  def withGeneratedBootSecrets(cfg: ManagerConfig): ManagerConfig =
    if cfg.ha.enabled then cfg
    else
      val genSecret = Option.when(cfg.auth.management.sessionJwtSecret.trim.isEmpty)(
        SessionTokenStore.randomSecret()
      )
      val genKey = Option.when(cfg.apiKey.forall(_.trim.isEmpty))(randomApiKey())
      if genSecret.isEmpty && genKey.isEmpty then cfg
      else
        val lines =
          genSecret.map(s => s"  QOD_SESSION_JWT_SECRET=$s").toList ++
            genKey.map(k => s"  QOD_API_KEY=$k").toList
        println(
          s"""
             |================================================================================
             |  GENERATED BOOT SECRETS
             |
             |  The following were not configured, so fresh random values were minted for
             |  this boot only:
             |
             |${lines.mkString("\n")}
             |
             |  They change on every restart: UI/CLI sessions and API-key callers die with
             |  the process. Pin both env vars to stable values for production.
             |================================================================================
             |""".stripMargin
        )
        cfg.copy(
          apiKey = genKey.orElse(cfg.apiKey),
          auth = cfg.auth.copy(
            management = cfg.auth.management.copy(
              sessionJwtSecret = genSecret.getOrElse(cfg.auth.management.sessionJwtSecret)
            )
          )
        )

  /** 32 random bytes, url-safe base64 without padding, `qod_`-prefixed so a leaked value is
    * recognizable in config dumps the way `qod_pat_` tokens are.
    */
  private def randomApiKey(): String =
    val bytes = new Array[Byte](32)
    new java.security.SecureRandom().nextBytes(bytes)
    "qod_" + java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  /** Cheap startup gate: when database auth is enabled, systemQuery/tenantQuery must each project
    * (password_hash, role, enabled, must_change_password) -- exactly the shape
    * DatabaseAuthenticator requires at runtime now that the tolerant short-projection branch is
    * gone. Caught here instead of at first login. Runs AFTER the Liquibase apply: the default
    * queries target qodstate_user in the control-plane database, which does not exist yet on a
    * fresh (or nuked) metastore until the changelog has been applied. The probe result is computed
    * first and sys.error'd after, so an unreachable auth database surfaces as the same clean
    * config-error framing as the sibling boot gates, not a raw JDBC exception.
    */
  def probeAuthDatabase(dbCfg: DatabaseAuthConfig): Unit =
    val probeResult: Either[String, Unit] =
      try
        Class.forName("org.postgresql.Driver")
        val probeConn = java.sql.DriverManager.getConnection(
          dbCfg.jdbcUrl,
          dbCfg.username,
          dbCfg.password
        )
        try AuthQueryPreconditions.validate(probeConn, dbCfg)
        finally probeConn.close()
      catch
        case e: Exception =>
          Left(
            "auth.database startup validation failed: could not probe " +
              s"systemQuery/tenantQuery against '${dbCfg.jdbcUrl}' " +
              s"(${e.getMessage}). Check QOD_AUTH_DB_JDBC_URL / QOD_AUTH_DB_USER / " +
              "QOD_AUTH_DB_PASSWORD and that the auth database is reachable."
          )
    probeResult.left.foreach(msg => sys.error(msg))

  /** Probe the CONTROL-PLANE database (the `defaultMetastore` URL that `UserStore` enforces
    * against) for the lockout columns. Lockout state lives on `qodstate_user` in the control-plane
    * db, NOT the auth db, so this must probe the same URL UserStore uses -- probing the auth db
    * would be untruthful when they diverge (see `checkLockoutDbCoherence`). A control-plane db that
    * never ran changelog 0029/0030 would otherwise fail at first login instead of at boot.
    */
  def probeLockoutColumns(controlPlaneUrl: String, user: String, password: String): Unit =
    val probeResult: Either[String, Unit] =
      try
        Class.forName("org.postgresql.Driver")
        val probeConn = java.sql.DriverManager.getConnection(controlPlaneUrl, user, password)
        try checkLockoutColumns(probeConn)
        finally probeConn.close()
      catch
        case e: Exception =>
          Left(
            "auth.lockout.enabled is true but the control-plane database could not be probed for " +
              s"lockout columns at '$controlPlaneUrl' (${e.getMessage}). Check the " +
              "quack-on-demand.defaultMetastore settings and that the database is reachable."
          )
    probeResult.left.foreach(msg => sys.error(msg))

  /** Pure gate: lockout state (`failed_attempts` / `locked_at`) is read and written by `UserStore`
    * against `qodstate_user` in the CONTROL-PLANE database (the `defaultMetastore`), but auth
    * queries run against the auth database (`QOD_AUTH_DB_JDBC_URL`). In the default deployment the
    * two URLs coincide (the auth URL is derived from the defaultMetastore). If an operator points
    * the auth db at a DIFFERENT database, lockout enforcement becomes inert -- writes hit 0 rows on
    * the auth side and `isLocked` is always false, i.e. the control fails OPEN while boot claims it
    * is enabled. Rather than fail open on a security control, refuse to start. URLs are compared
    * trim-normalized; the two default URLs are built by identical string interpolation, so exact
    * equality holds for the coincident case. Side-effect-free so it is unit-testable; the call site
    * sys.errors on Left, mirroring `checkLockoutSmtp`.
    */
  def checkLockoutDbCoherence(
      lockoutEnabled: Boolean,
      controlPlaneUrl: String,
      authUrl: String
  ): Either[String, Unit] =
    if lockoutEnabled && controlPlaneUrl.trim != authUrl.trim then
      Left(
        "auth.lockout.enabled is true but the auth database URL differs from the control-plane " +
          "database. Account lockout enforces against qodstate_user in the control-plane database " +
          s"('$controlPlaneUrl'), while auth queries run against '$authUrl' -- with divergent URLs " +
          "lockout is inert (writes hit 0 rows, isLocked is always false, so lockout fails open). " +
          "Point QOD_AUTH_DB_JDBC_URL at the control-plane database, or set " +
          "QOD_AUTH_LOCKOUT_ENABLED=false."
      )
    else Right(())

  /** When lockout is enabled, `qodstate_user` must carry `failed_attempts`, `locked_at`, and
    * `email` -- the three columns Task 9's enforcement reads and writes. A control-plane database
    * that never ran the quack-on-demand Liquibase changelog (or an operator-managed lookalike
    * table) would otherwise fail at first login instead of at boot.
    */
  private val LockoutRequiredColumns = Set("failed_attempts", "locked_at", "email")

  private def checkLockoutColumns(conn: java.sql.Connection): Either[String, Unit] =
    val rs      = conn.getMetaData.getColumns(null, null, "qodstate_user", null)
    val present = scala.collection.mutable.Set.empty[String]
    try while rs.next() do present += rs.getString("COLUMN_NAME").toLowerCase
    finally rs.close()
    val missing = LockoutRequiredColumns.diff(present.toSet)
    if missing.nonEmpty then
      Left(
        "auth.lockout.enabled is true but qodstate_user is missing column(s): " +
          s"${missing.toList.sorted.mkString(", ")} -- run the quack-on-demand Liquibase " +
          "changelog (0029-user-email / 0030-user-lockout) against the control-plane database, " +
          "or set QOD_AUTH_LOCKOUT_ENABLED=false."
      )
    else Right(())

  /** Pure gate: lockout requires a reachable SMTP relay, otherwise a locked-out user has no
    * self-service way back in (the whole reason Phase 1 built the forgot/reset-password flow). Kept
    * side-effect-free so it is unit-testable without a live Postgres or SMTP relay; the call site
    * sys.errors on Left, mirroring `probeAuthDatabase`.
    */
  def checkLockoutSmtp(lockoutEnabled: Boolean, smtpHost: Option[String]): Either[String, Unit] =
    if lockoutEnabled && smtpHost.forall(_.trim.isEmpty) then
      Left(
        "auth.lockout.enabled is true but no SMTP relay is configured -- a locked-out user would " +
          "have no way back in. Set QOD_SMTP_HOST (and QOD_SMTP_PORT / QOD_SMTP_USER / " +
          "QOD_SMTP_PASSWORD as needed) or set QOD_AUTH_LOCKOUT_ENABLED=false."
      )
    else Right(())

  /** Bootstrap admin users at startup so the DB auth backend has at least one credential. Re-hashed
    * on every boot: changing QOD_ADMIN_PASSWORD + restart rotates. All names in QOD_ADMIN_USERNAME
    * (comma-separated) get the same password + role. Superuser scope: tenant=NULL (the
    * qodstate_user_scope_consistency CHECK only forbids empty-string tenants).
    */
  def seedAdminUsers(userStore: UserStore, admin: AdminConfig): Unit =
    val admins = admin.usernameList
    if admins.isEmpty then
      logger.warn("quack-on-demand.admin.username is empty - no admin user seeded.")
    else
      admins.foreach { name =>
        // Same rule as EmailPolicy / the 0031 backfill: an email-format admin username
        // IS its own email, so the seeded admin is reachable for reset and (when lockout
        // is enabled) lockable, consistently on fresh and upgraded installs.
        val seedEmail = if EmailFormat.matches(name) then Some(name) else None
        val out       = userStore.upsertUser(
          tenant = None,
          username = name,
          plaintext = admin.password,
          role = admin.role,
          email = Some(seedEmail)
        )
        val verb = if out.inserted then "created" else "updated"
        logger.info(
          s"admin user $verb: $name (id=${out.id}, role=${admin.role}) in qodstate_user"
        )
      }
