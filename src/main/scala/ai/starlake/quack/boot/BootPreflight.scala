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
    * (password_hash, role, enabled) -- exactly the shape DatabaseAuthenticator requires at runtime
    * now that the tolerant two-column branch is gone. Caught here instead of at first login. Runs
    * AFTER the Liquibase apply: the default queries target qodstate_user in the control-plane
    * database, which does not exist yet on a fresh (or nuked) metastore until the changelog has
    * been applied. The probe result is computed first and sys.error'd after, so an unreachable auth
    * database surfaces as the same clean config-error framing as the sibling boot gates, not a raw
    * JDBC exception.
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
