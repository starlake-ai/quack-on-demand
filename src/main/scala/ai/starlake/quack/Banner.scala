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

  /** Probe the control-plane Postgres BEFORE anything else touches it. Right(()) when a connection
    * opens within `timeoutSec`; Left(operator message) otherwise. Pure of exit decisions so it is
    * unit-testable; Main prints the message and exits on Left.
    */
  def postgresPreflight(meta: Map[String, String], timeoutSec: Int = 5): Either[String, Unit] =
    val url  = jdbcControlPlaneUrl(meta)
    val user = meta.getOrElse("pgUser", "postgres")
    println(s"control-plane Postgres: $url (user $user)")
    try
      DriverManager.setLoginTimeout(timeoutSec)
      val c = DriverManager.getConnection(url, user, meta.getOrElse("pgPassword", ""))
      c.close()
      Right(())
    catch
      case t: Throwable =>
        Left(
          s"""$Line
             | Postgres is NOT reachable; refusing to start.
             |   url    : $url
             |   user   : $user
             |   error  : ${Option(t.getMessage)
              .getOrElse(t.getClass.getSimpleName)
              .linesIterator
              .next()}
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
      tlsEnabled: Boolean
  ): String =
    def display(h: String) = if h == "0.0.0.0" || h == "::" then "localhost" else h
    val rh                 = display(restHost)
    val fh                 = display(flightHost)
    val scheme             = if tlsEnabled then "grpc+tls" else "grpc"
    val jdbcTls            =
      if tlsEnabled then "&useEncryption=true&disableCertificateVerification=true"
      else "&useEncryption=false"
    val version =
      Option(getClass.getPackage.getImplementationVersion).getOrElse("dev")
    s"""$Line
       | Quack on Demand $version is up
       |   control plane : ${jdbcControlPlaneUrl(meta)}
       |   REST API + UI : http://$rh:$restPort  (UI: http://$rh:$restPort/ui)
       |   FlightSQL     : $scheme://$fh:$flightPort
       |
       | Client connection strings (replace <tenant>, <pool>, <user>):
       |   JDBC : jdbc:arrow-flight-sql://$fh:$flightPort/?tenant=<tenant>&pool=<pool>&user=<user>$jdbcTls
       |   ADBC : uri=$scheme://$fh:$flightPort  (adbc_driver_flightsql; db_kwargs: username, password, plus grpc headers tenant=<tenant>, pool=<pool>)
       |   ODBC : Driver={Arrow Flight SQL ODBC Driver};Host=$fh;Port=$flightPort;UseEncryption=${
        if tlsEnabled then "true" else "false"
      }${
        if tlsEnabled then ";DisableCertificateVerification=true" else ""
      };UID=<user>;PWD=<password>;TENANT=<tenant>;POOL=<pool>
       |$Line""".stripMargin
