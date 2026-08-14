package ai.starlake.quack.boot

import ai.starlake.quack.AdminConfig
import ai.starlake.quack.edge.auth.AuthQueryPreconditions
import ai.starlake.quack.edge.config.DatabaseAuthConfig
import ai.starlake.quack.ondemand.state.UserStore
import com.typesafe.scalalogging.LazyLogging

/** Boot-time gates and seeding that must run before the manager wires its components. Extracted
  * from Main.bootManager; every check keeps its original fail-fast behavior (sys.error aborts the
  * boot with a clean config-error framing instead of a raw stack trace).
  */
object BootPreflight extends LazyLogging:

  /** Cheap startup gate: when database auth is enabled, systemQuery/tenantQuery must each project
    * (password_hash, role, enabled, must_change_password) -- exactly the shape
    * DatabaseAuthenticator requires at runtime now that the tolerant short-projection branch is
    * gone. Caught here instead of at first login. Runs AFTER the Liquibase apply: the default
    * queries target qodstate_user in the control-plane database, which does not exist yet on a
    * fresh (or nuked) metastore until the changelog has been applied. The probe result is computed
    * first and sys.error'd after, so an unreachable auth database surfaces as the same clean
    * config-error framing as the sibling boot gates, not a raw JDBC exception.
    */
  def probeAuthDatabase(dbCfg: DatabaseAuthConfig, lockoutEnabled: Boolean = false): Unit =
    val probeResult: Either[String, Unit] =
      try
        Class.forName("org.postgresql.Driver")
        val probeConn = java.sql.DriverManager.getConnection(
          dbCfg.jdbcUrl,
          dbCfg.username,
          dbCfg.password
        )
        try
          AuthQueryPreconditions
            .validate(probeConn, dbCfg)
            .flatMap(_ => if lockoutEnabled then checkLockoutColumns(probeConn) else Right(()))
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

  /** When lockout is enabled, `qodstate_user` must carry `failed_attempts`, `locked_at`, and
    * `email` -- the three columns Task 9's enforcement reads and writes. A custom
    * QOD_AUTH_DB_JDBC_URL pointed at a database that never ran the quack-on-demand Liquibase
    * changelog (or an operator-managed lookalike table) would otherwise fail at first login instead
    * of at boot.
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
          "changelog (0029-user-email / 0030-user-lockout) against the auth database, or " +
          "point QOD_AUTH_DB_JDBC_URL at one that has."
      )
    else Right(())

  /** Pure gate: lockout requires a reachable SMTP relay, otherwise a locked-out user has no
    * self-service way back in (the whole reason Phase 1 built the forgot/reset-password flow). Kept
    * side-effect-free so it is unit-testable without a live Postgres or SMTP relay; the call site
    * sys.errors on Left, mirroring `probeAuthDatabase`.
    */
  def checkLockoutSmtp(lockoutEnabled: Boolean, smtpHost: Option[String]): Either[String, Unit] =
    if lockoutEnabled && smtpHost.forall(_.isEmpty) then
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
        val out = userStore.upsertUser(
          tenant = None,
          username = name,
          plaintext = admin.password,
          role = admin.role
        )
        val verb = if out.inserted then "created" else "updated"
        logger.info(
          s"admin user $verb: $name (id=${out.id}, role=${admin.role}) in qodstate_user"
        )
      }
