package ai.starlake.quack.boot

import java.util.Locale
import ai.starlake.quack.AdminConfig
import ai.starlake.quack.{ManagementAuthConfig, ManagerConfig}
import ai.starlake.quack.edge.auth.{AuthQueryPreconditions, AuthenticationService}
import ai.starlake.quack.edge.config.{AuthenticationConfig, DatabaseAuthConfig}
import ai.starlake.quack.model.{FederatedAlias, Names}
import ai.starlake.quack.ondemand.api.SessionTokenStore
import ai.starlake.quack.ondemand.state.{EmailFormat, FederatedSourceOps, UserStore}
import com.typesafe.scalalogging.LazyLogging

/** Boot-time gates and seeding that must run before the manager wires its components. Extracted
  * from Main.bootManager; every check keeps its original fail-fast behavior (sys.error aborts the
  * boot with a clean config-error framing instead of a raw stack trace).
  */
object BootPreflight extends LazyLogging:

  /** The refusal for a removed static API key, verbatim in the boot error. */
  val RemovedApiKeyMessage: String =
    "QOD_API_KEY (quack-on-demand.apiKey) was removed: the manager no longer accepts a static " +
      "API key. Use a personal access token (qod auth pat create) or a qod login session, and " +
      "unset QOD_API_KEY."

  /** Pure gate: the static API key was removed. Refuses when the environment holds a non-empty
    * (after trimming) `QOD_API_KEY`, or when the loaded configuration holds a non-empty
    * `quack-on-demand.apiKey` (an operator's own config file, or a `-D` system property). An empty
    * value passes: older Compose files render `API_KEY=` as one. Both are read directly because,
    * with the field gone, pureconfig would ignore the key silently and the manager would boot
    * without the credential its operator relies on. `Main.bootManager` runs it first, before the
    * session secret is generated and before HA validation.
    */
  def checkRemovedApiKey(
      env: String => Option[String],
      raw: com.typesafe.config.Config
  ): Either[String, Unit] =
    val path     = "quack-on-demand.apiKey"
    val inEnv    = env("QOD_API_KEY").exists(_.trim.nonEmpty)
    val inConfig = raw.hasPath(path) && raw.getValue(path).unwrapped().toString.trim.nonEmpty
    if inEnv || inConfig then Left(RemovedApiKeyMessage) else Right(())

  /** The refusal for a FlightSQL edge with no authenticator, verbatim in the boot error. */
  val NoEdgeAuthMessage: String =
    "the FlightSQL edge and the Quack front door have no authenticator: QOD_AUTH_DB_ENABLED=false " +
      "and no other quack-flightsql.auth provider is configured, so no FlightSQL or Quack client " +
      "could ever connect. " +
      "System-scope OIDC (QOD_MGMT_OIDC_*) signs in to the admin UI and REST only, never to " +
      "FlightSQL. Set QOD_AUTH_DB_ENABLED=true, or a Keycloak or Azure password backend " +
      "(QOD_AUTH_KEYCLOAK_ENABLED=true or QOD_AUTH_AZURE_ENABLED=true, with their settings), or " +
      "a bearer provider: an external JWT issuer (JWT_SECRET_KEY or JWT_PUBLIC_KEY_PATH), " +
      "QOD_AUTH_GOOGLE_ENABLED=true or QOD_AUTH_AWS_ENABLED=true."

  /** Pure gate: the FlightSQL edge needs an authenticator. With an empty `quack-flightsql.auth`
    * chain (no basic and no bearer provider) the edge handshake refuses every client, so no
    * FlightSQL or Quack client could connect; system-scope OIDC adds no edge provider, so it does
    * not count. The FlightSQL edge has no enable switch and always starts, so the check holds
    * whether or not the Quack front door (`QOD_QUACK_ENABLED`) runs too. It reads the
    * configuration, not a built [[AuthenticationService]], so `Main.bootManager` runs it right
    * after the removed-key refusal, at the top of `bootManager`, before it opens any socket and
    * before HA validation.
    */
  def checkEdgeAuthAvailable(auth: AuthenticationConfig): Either[String, Unit] =
    if AuthenticationService.configuresProvider(auth) then Right(()) else Left(NoEdgeAuthMessage)

  /** Pure gate: something must be able to create a session. Refuses when the authentication service
    * has no password provider (its basic chain is empty, as with `QOD_AUTH_DB_ENABLED=false` and no
    * Keycloak or Azure password backend) AND system-scope OIDC sign-in is not served: nothing could
    * create a session, a personal access token is minted with one, and `/api` would be unreachable.
    * `Main.bootManager` runs it right after the edge check, from the configuration alone
    * (`AuthenticationService.configuresPasswordProvider`). System-scope OIDC counts exactly as
    * `ManagementAuthWiring` serves it: `identitySource=oidc` with the issuer URL and the client id
    * set (under `db` the system scope gets no OIDC client, whatever `QOD_MGMT_OIDC_*` hold). Tenant
    * OIDC does not count: it is configured through `/api` and never yields a superuser.
    */
  def checkLoginAvailable(
      hasPasswordProvider: Boolean,
      mgmt: ManagementAuthConfig
  ): Either[String, Unit] =
    val systemOidc =
      mgmt.identitySource.trim.toLowerCase(Locale.ROOT) == "oidc" &&
        mgmt.oidc.issuerUrl.trim.nonEmpty && mgmt.oidc.clientId.trim.nonEmpty
    if !hasPasswordProvider && !systemOidc then
      Left(
        "nothing can sign in to this manager: the authentication service has no password " +
          "provider (QOD_AUTH_DB_ENABLED=false and no Keycloak or Azure password backend) and " +
          "system-scope OIDC sign-in is not configured (it needs QOD_MGMT_IDENTITY_SOURCE=oidc " +
          "with QOD_MGMT_OIDC_ISSUER_URL and QOD_MGMT_OIDC_CLIENT_ID set). Without a session no " +
          "personal access token can be minted, so /api would be unreachable. Set " +
          "QOD_AUTH_DB_ENABLED=true, or set QOD_MGMT_IDENTITY_SOURCE=oidc with " +
          "QOD_MGMT_OIDC_ISSUER_URL, QOD_MGMT_OIDC_CLIENT_ID and QOD_MGMT_OIDC_CLIENT_SECRET."
      )
    else Right(())

  /** Resolve the boot secret an operator may leave unset: the session JWT signing secret. Outside
    * HA, an unset (or empty) value is replaced with a fresh random one for this boot and printed to
    * stdout, deliberately NOT through the logger, whose default ERROR root level would swallow an
    * info line, so a first-run operator cannot miss it. Under HA the config is returned unchanged:
    * a per-replica random secret cannot verify sessions minted by other replicas (HaPreconditions
    * refuses the empty secret).
    */
  def withGeneratedBootSecrets(cfg: ManagerConfig): ManagerConfig =
    if cfg.ha.enabled || cfg.auth.management.sessionJwtSecret.trim.nonEmpty then cfg
    else
      val secret = SessionTokenStore.randomSecret()
      println(
        s"""
           |================================================================================
           |  GENERATED BOOT SECRET
           |
           |  QOD_SESSION_JWT_SECRET was not configured, so a fresh random value was minted
           |  for this boot only:
           |
           |  QOD_SESSION_JWT_SECRET=$secret
           |
           |  It changes on every restart: UI and CLI sessions die with the process. Pin it
           |  to a stable value for production.
           |================================================================================
           |""".stripMargin
      )
      cfg.copy(
        auth = cfg.auth.copy(
          management = cfg.auth.management.copy(sessionJwtSecret = secret)
        )
      )

  /** Cheap startup gate: when database auth is enabled, systemQuery/tenantQuery must each project
    * (password_hash, kind, enabled, must_change_password) -- exactly the shape
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
    try while rs.next() do present += rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT)
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

  /** Bootstrap admin users at startup so the DB auth backend has at least one credential. Seeds
    * each name in QOD_ADMIN_USERNAME (comma-separated) ONLY when its superuser row is missing: an
    * existing row is never touched (password, kind, email, lockout), so `qod auth change-password`
    * survives restarts. Recovery of a lost password is `qod admin reset-password`, not a restart.
    * Superuser scope: tenant=NULL. Returns the warnings it logged (one per existing row when
    * QOD_ADMIN_PASSWORD is explicitly set, since that value is then ignored). An empty
    * `admin.password` never seeds a missing row; it logs an ERROR instead.
    */
  def seedAdminUsers(
      userStore: UserStore,
      admin: AdminConfig,
      env: String => Option[String] = sys.env.get
  ): List[String] =
    val admins = admin.usernameList
    if admins.isEmpty then
      logger.warn("quack-on-demand.admin.username is empty - no admin user seeded.")
      Nil
    else
      val passwordFromEnv = env("QOD_ADMIN_PASSWORD").exists(_.nonEmpty)
      admins.flatMap { name =>
        if admin.password.isEmpty then
          if userStore.userIdOf(None, name).isDefined then None
          else
            val msg =
              s"admin '$name' not seeded: QOD_ADMIN_PASSWORD is not set; export it and restart " +
                "once to create the admin"
            logger.error(msg)
            Some(msg)
        else
          // Same rule as EmailPolicy / the 0031 backfill: an email-format admin username
          // IS its own email, so the seeded admin is reachable for reset and (when lockout
          // is enabled) lockable, consistently on fresh and upgraded installs.
          val seedEmail = if EmailFormat.matches(name) then Some(name) else None
          val out       = userStore.upsertUser(
            tenant = None,
            username = name,
            plaintext = admin.password,
            kind = admin.kind,
            email = Some(seedEmail),
            insertOnly = true
          )
          if out.inserted then
            logger.info(s"admin user created: $name (id=${out.id}, kind=${admin.kind})")
            None
          else if passwordFromEnv then
            val msg =
              s"admin '$name' already exists; QOD_ADMIN_PASSWORD is ignored (use qod auth " +
                "change-password or qod admin reset-password)"
            logger.warn(msg)
            Some(msg)
          else None
      }

  /** Federated sources whose alias [[Names]]'s identifier rule would REWRITE (not merely reject) --
    * every federated alias, `sql` as well as `iceberg_rest`, has been normalized through this rule
    * since the typed iceberg_rest source type landed. This is broader than "invalid": an alias that
    * is a valid identifier but not already lowercase (e.g. `extS3`) is silently rewritten to
    * `exts3` on its next upsert rather than rejected, which is exactly the class `Names.isValid`
    * alone is blind to (its pattern accepts uppercase). Returned as (tenantDbId, alias) pairs.
    *
    * Pure and total: a store failure yields `Nil` rather than throwing, because a legacy alias must
    * never prevent boot. Kept separate from [[checkFederatedAliases]] so the decision (what counts
    * as invalid) is testable directly, without a logging harness.
    *
    * The lowercase test folds through [[FederatedAlias]] rather than `toLowerCase` because it is
    * predicting what `Names.normalizeOrError` (which folds with `Locale.ROOT`) will store on the
    * next upsert. A default-locale fold here would report a `tr`/`az` JVM's single-`I` alias as
    * already-normalized when it is not, and the message below would name a rewrite target the write
    * path never produces.
    */
  private[boot] def invalidFederatedAliases(fedStore: FederatedSourceOps): List[(String, String)] =
    try
      for
        tenantDbId <- fedStore.tenantDbIdsWithSources().toList
        source     <- fedStore.listSources(tenantDbId)
        if !Names.isValid(source.alias) || source.alias != FederatedAlias.fold(source.alias)
      yield (tenantDbId, source.alias)
    catch
      case e: Exception =>
        logger.error(s"checkFederatedAliases: scan failed, skipping: ${e.getMessage}")
        Nil

  /** Boot-time check reporting [[invalidFederatedAliases]]: an alias created before every federated
    * alias was normalized falls into one of two cases.
    *
    *   - **Invalid** (e.g. containing a hyphen or a dot, or over-length): cannot be re-upserted
    *     through REST, CLI, MCP or manifest import until it is recreated under a valid alias, and
    *     if its setup SQL used the bare `{{alias}}` substitution form (rather than a hand-quoted
    *     `AS "{{alias}}"`), its `ATTACH ... AS <alias>` has likely been failing silently at every
    *     node spawn already: DuckDB's parser rejects the unquoted identifier, and the piped CLI
    *     does not bail on that error.
    *   - **Valid but not lowercase** (e.g. `extS3`): will be rewritten in place to its lowercase
    *     form on its next upsert (safe, now that the lookup falls back to a case-insensitive scan
    *     for exactly this case) -- but until then its `ATTACH` may already be colliding with a
    *     sibling alias differing only by case, since DuckDB treats catalog names
    *     case-insensitively.
    *
    * MUST NOT fail boot and MUST NOT throw: an existing install carrying a legacy alias has to keep
    * starting -- `invalidFederatedAliases` already degrades a store failure to `Nil`. Reports
    * nothing when every alias is already normalized, so the overwhelmingly common install stays
    * silent.
    */
  def checkFederatedAliases(fedStore: FederatedSourceOps): Unit =
    invalidFederatedAliases(fedStore).foreach { case (tenantDbId, alias) =>
      // ERROR, not WARN: the default logback root level is ERROR (see logback.xml), so a WARN
      // here would be silently swallowed on every install that hasn't raised the level -- and an
      // operator restarting into this state needs to see it, not lose it.
      val detail =
        if !Names.isValid(alias) then
          "is no longer a valid identifier (lowercase letters, digits and underscore only, not " +
            s"starting with a digit, 1..${Names.MaxLength} chars). It cannot be updated through " +
            "REST, CLI, MCP or manifest import until it is recreated under a valid alias; if its " +
            "setup SQL uses the bare {{alias}} substitution form, its ATTACH has likely been " +
            "failing silently at every node spawn already."
        else
          s"is not lowercase (stored as '$alias'). It will be rewritten to " +
            s"'${FederatedAlias.fold(alias)}' in place the next time it is updated; until then " +
            "its ATTACH may already be colliding with a sibling alias differing only by case, " +
            "since DuckDB treats catalog names case-insensitively."
      logger.error(s"tenant-db '$tenantDbId': federated source alias '$alias' $detail")
    }
