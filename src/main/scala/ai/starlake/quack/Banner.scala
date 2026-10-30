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
      /** Authorization engine of a tenant that sets none (`quack-flightsql.opa.defaultMode`, env
        * `QOD_ACL_MODE`): `qod` or `opa`.
        */
      aclMode: String = "qod",
      /** Tenants currently in `opa` mode. Named on the ACL MODE line. */
      opaTenants: Int = 0,
      /** The `qod` CLI config file (`QOD_CONFIG_FILE`, set by `qod start`) the manager was launched
        * with; None when launched outside the CLI.
        */
      cliConfigFile: Option[String] = None
  ): String =
    def display(h: String) = if h == "0.0.0.0" || h == "::" then "localhost" else h
    // Constant since Phase D: every node checks every statement, and nothing switches it off.
    val aclLine =
      "   SQL ACL       : ENFORCED on every node (QoD grants, or the tenant's OPA in opa mode)"
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
       |
       | Client connection strings: $ClientsDocUrl
       |$Line""".stripMargin
