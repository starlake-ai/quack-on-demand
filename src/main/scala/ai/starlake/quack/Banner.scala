package ai.starlake.quack

import java.sql.DriverManager

/** Operator-facing boot output. Everything here prints to stdout unconditionally: the default log
  * level is ERROR (quiet boot), but the operator must always see which Postgres the manager is
  * about to use and where clients connect. Keep these the only println call sites in the manager.
  */
object Banner:

  private val Line = "=" * 78

  /** Where client connection strings (DuckDB, JDBC, ADBC, ODBC) are documented. */
  val ClientsDocUrl = "https://docs.starlake.ai/qod/connecting/clients"

  def jdbcControlPlaneUrl(meta: Map[String, String]): String =
    s"jdbc:postgresql://${meta.getOrElse("pgHost", "localhost")}:${meta
        .getOrElse("pgPort", "5432")}/${meta.getOrElse("dbName", "qod")}"

  /** Probe the control-plane Postgres BEFORE anything else touches it, creating the control-plane
    * database when the server is up but the database is missing (SQLState 3D000), so a fresh
    * install needs no `psql` in the launcher. Right(()) when the database is reachable (or was just
    * created) within `timeoutSec`; Left(operator message) on any other failure. Pure of exit
    * decisions so it is unit-testable; Main prints the message and exits on Left.
    */
  def postgresPreflight(meta: Map[String, String], timeoutSec: Int = 5): Either[String, Unit] =
    val url  = jdbcControlPlaneUrl(meta)
    val user = meta.getOrElse("pgUser", "postgres")
    println(s"control-plane Postgres: $url (user $user)")
    ai.starlake.quack.ondemand.state.ControlPlaneBootstrap
      .ensureDatabase(meta, timeoutSec = timeoutSec) match
      case Right(created) =>
        if created then
          println(
            s"control-plane database '${meta.getOrElse("dbName", "qod")}' did not exist; created it"
          )
        Right(())
      case Left(reason) =>
        Left(
          s"""$Line
             | Postgres is NOT reachable; refusing to start.
             |   url    : $url
             |   user   : $user
             |   error  : $reason
             | Check that Postgres is running and that the QOD_* metastore overrides
             | (quack-on-demand.defaultMetastore: pgHost/pgPort/pgUser/pgPassword/dbName)
             | point at it.
             |$Line""".stripMargin
        )

  /** The post-startup banner: printed once REST and FlightSQL are both listening. `restHost` /
    * `flightHost` of 0.0.0.0 render as localhost so the endpoints are copy-pasteable. Client
    * connection strings are not printed; the banner links to their documentation instead.
    */
  def startup(
      meta: Map[String, String],
      restHost: String,
      restPort: Int,
      flightHost: String,
      flightPort: Int,
      tlsEnabled: Boolean,
      /** The native Quack front door `(host, port, tls)` when it is enabled. */
      quack: Option[(String, Int, Boolean)] = None,
      /** Whether the SQL ACL (`quack-flightsql.acl.enabled`, env `QOD_ACL_ENABLED`) is enforced.
        * Required, not defaulted: the logger's ACL line sits below the default ERROR level, so this
        * banner is the one place an operator reliably sees whether grants are enforced.
        */
      aclEnabled: Boolean,
      /** The global node-lockdown default (`quack-flightsql.nodeLockdown.enabled`, env
        * `QOD_NODE_LOCKDOWN`). Required for the same reason as `aclEnabled`: without lockdown a
        * tenant can ATTACH, INSTALL, read local files and the node environment, which holds the
        * catalog password, object-store keys and federation secrets.
        */
      nodeLockdown: Boolean,
      /** Pools whose effective lockdown is off (per-pool override, else the global default). */
      unlockedPools: Int = 0,
      /** Authorization engine of a tenant that sets none (`quack-flightsql.opa.defaultMode`, env
        * `QOD_ACL_MODE`): `qod` or `opa`.
        */
      aclMode: String = "qod",
      /** Tenants currently in `opa` mode. Their statements are enforced by their OPA even with the
        * SQL ACL disabled, so the banner must never imply "nothing enforced" while one exists.
        */
      opaTenants: Int = 0,
      /** The `qod` CLI config file (`QOD_CONFIG_FILE`, set by `qod start`) the manager was launched
        * with; None when launched outside the CLI.
        */
      cliConfigFile: Option[String] = None
  ): String =
    def display(h: String) = if h == "0.0.0.0" || h == "::" then "localhost" else h
    val opaInPlay          = aclMode != "qod" || opaTenants > 0
    val aclLine            =
      if aclEnabled then "   SQL ACL       : ENABLED (grants, column and row policies enforced)"
      else if opaInPlay then
        "   SQL ACL       : DISABLED for qod tenants (every statement admitted; set QOD_ACL_ENABLED=true to enforce); opa tenants enforced by OPA"
      else
        "   SQL ACL       : DISABLED (every statement admitted; set QOD_ACL_ENABLED=true to enforce)"
    val exposure     = "can ATTACH/INSTALL/LOAD, read local files and node credentials"
    val lockdownLine =
      if !nodeLockdown then
        s"   NODE LOCKDOWN : DISABLED ($unlockedPools pool(s) unlocked): their tenants $exposure; set QOD_NODE_LOCKDOWN=true to enforce"
      else if unlockedPools > 0 then
        s"   NODE LOCKDOWN : ENABLED, but $unlockedPools pool(s) opted out: their tenants $exposure"
      else
        "   NODE LOCKDOWN : ENABLED (tenants cannot ATTACH/INSTALL/LOAD, read local files or node credentials)"
    val modeLine =
      s"   ACL MODE      : $aclMode (default for tenants that set none; $opaTenants tenant(s) in opa mode)"
    val rh        = display(restHost)
    val fh        = display(flightHost)
    val quackLine = quack.fold("") { case (h, p, tls) =>
      s"\n   Quack (DuckDB): quack:${display(h)}:$p  (${if tls then "TLS" else "plain HTTP"})"
    }
    val scheme     = if tlsEnabled then "grpc+tls" else "grpc"
    val configLine = cliConfigFile.fold("")(f => s"\n   qod config    : $f")
    val version    =
      Option(getClass.getPackage.getImplementationVersion).getOrElse("dev")
    s"""$Line
       | Quack on Demand $version is up$configLine
       |   control plane : ${jdbcControlPlaneUrl(meta)}
       |   REST API + UI : http://$rh:$restPort  (UI: http://$rh:$restPort/ui)
       |   FlightSQL     : $scheme://$fh:$flightPort$quackLine
       |$aclLine
       |$modeLine
       |$lockdownLine
       |
       | Client connection strings: $ClientsDocUrl
       |$Line""".stripMargin
