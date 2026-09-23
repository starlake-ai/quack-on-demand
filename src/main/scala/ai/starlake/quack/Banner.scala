package ai.starlake.quack

import java.sql.DriverManager

/** Operator-facing boot output. Everything here prints to stdout unconditionally: the default log
  * level is ERROR (quiet boot), but the operator must always see which Postgres the manager is
  * about to use and how clients connect. Keep these the only println call sites in the manager.
  */
object Banner:

  private val Line = "=" * 78

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
    * `flightHost` of 0.0.0.0 render as localhost so the strings are copy-pasteable.
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
      aclEnabled: Boolean
  ): String =
    def display(h: String) = if h == "0.0.0.0" || h == "::" then "localhost" else h
    val aclLine            =
      if aclEnabled then "   SQL ACL       : ENABLED (grants, column and row policies enforced)"
      else
        "   SQL ACL       : DISABLED (every statement admitted; set QOD_ACL_ENABLED=true to enforce)"
    val rh        = display(restHost)
    val fh        = display(flightHost)
    val quackLine = quack.fold("") { case (h, p, tls) =>
      s"\n   Quack (DuckDB): quack:${display(h)}:$p  (${if tls then "TLS" else "plain HTTP"})"
    }
    val quackStrings = quack.fold("") { case (h, p, tls) =>
      // The DuckDB client speaks plain HTTP to loopback hosts and TLS to any other host; the
      // hint tells a remote client how to reach a plain-HTTP listener.
      val ssl = if tls || display(h) == "localhost" then "" else ", DISABLE_SSL true"
      s"\n   DuckDB: ATTACH 'quack:${display(h)}:$p' AS qod (TYPE quack, TOKEN 'tenant=<tenant>&pool=<pool>&user=<user>&password=<password>'$ssl);" +
        s"\n           SELECT * FROM quack_query('quack:${display(h)}:$p', 'SELECT 1', token := 'tenant=<tenant>&pool=<pool>&user=<user>&password=<password>'${
            if ssl.isEmpty then "" else ", disable_ssl := true"
          });"
    }
    val scheme  = if tlsEnabled then "grpc+tls" else "grpc"
    val jdbcTls =
      if tlsEnabled then "&useEncryption=true&disableCertificateVerification=true"
      else "&useEncryption=false"
    val version =
      Option(getClass.getPackage.getImplementationVersion).getOrElse("dev")
    s"""$Line
       | Quack on Demand $version is up
       |   control plane : ${jdbcControlPlaneUrl(meta)}
       |   REST API + UI : http://$rh:$restPort  (UI: http://$rh:$restPort/ui)
       |   FlightSQL     : $scheme://$fh:$flightPort$quackLine
       |$aclLine
       |
       | Client connection strings (replace <tenant>, <pool>, <user>):$quackStrings
       |   JDBC : jdbc:arrow-flight-sql://$fh:$flightPort/?tenant=<tenant>&pool=<pool>&user=<user>$jdbcTls
       |   ADBC : uri=$scheme://$fh:$flightPort  (adbc_driver_flightsql; db_kwargs: username, password, plus grpc headers tenant=<tenant>, pool=<pool>)
       |   ODBC : Driver={Arrow Flight SQL ODBC Driver};Host=$fh;Port=$flightPort;UseEncryption=${
        if tlsEnabled then "true" else "false"
      }${
        if tlsEnabled then ";DisableCertificateVerification=true" else ""
      };UID=<user>;PWD=<password>;TENANT=<tenant>;POOL=<pool>
       |$Line""".stripMargin
