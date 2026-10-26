package ai.starlake.quack.cli

import ai.starlake.quack.{EmbeddedPostgresConfig, ManagerConfig}
import ai.starlake.quack.Main.given
import ai.starlake.quack.boot.EmbeddedControlPlane
import ai.starlake.quack.ondemand.state.UserStore
import pureconfig.ConfigSource

import java.io.{BufferedReader, InputStream, InputStreamReader, PrintStream}
import java.nio.charset.StandardCharsets
import scala.util.control.NonFatal

/** `java -jar qod.jar admin reset-password <username>... [--must-change]`, password on the first
  * stdin line. Break-glass recovery of superusers straight in the control-plane database (a new
  * password, the lockout cleared, the account re-enabled), so it works with every login path
  * blocked. It never starts Postgres: an external control plane is reached as configured (the
  * manager may be down), the embedded one only while a running `qod serve` owns it
  * (`EmbeddedControlPlane.attachRunning`). Every named superuser that exists is reset to the same
  * password in one store session (the default admin names are seeded as one credential). Authority =
  * reaching that database with the configured credentials. Exit 0 at least one updated, 1 none of
  * the names is a superuser / usage / control plane not initialized, 2 control plane unreachable.
  */
object AdminResetCli:

  private val Usage = "usage: admin reset-password <username>... [--must-change]"

  def run(args: List[String], in: InputStream, out: PrintStream, err: PrintStream): Int =
    runWith(args, in, out, err, loadConfig, EmbeddedControlPlane.attachRunning)

  private def loadConfig(): ManagerConfig =
    ConfigSource.default.at("quack-on-demand").loadOrThrow[ManagerConfig]

  private[cli] def runWith(
      args: List[String],
      in: InputStream,
      out: PrintStream,
      err: PrintStream,
      loadCfg: () => ManagerConfig,
      attach: EmbeddedPostgresConfig => Either[String, EmbeddedControlPlane.Handle]
  ): Int =
    val mustChange = args.contains("--must-change")
    args.filterNot(_ == "--must-change") match
      case usernames @ (_ :: _) =>
        val reader   = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
        val password = Option(reader.readLine()).getOrElse("")
        if password.isEmpty then
          err.println("error: empty password on stdin")
          1
        else resetConfigured(usernames, password, mustChange, out, err, loadCfg, attach)
      case _ =>
        err.println(Usage)
        1

  /** The update itself, on an open store. Exit code per the object doc. */
  def reset(store: UserStore, username: String, password: String, mustChange: Boolean): Int =
    store.resetSuperuserPassword(username, password, mustChange) match
      case Some(_) => 0
      case None    => 1

  private def sqlCause(t: Throwable): Option[java.sql.SQLException] =
    Iterator
      .iterate(t)(_.getCause)
      .takeWhile(_ != null)
      .collectFirst { case e: java.sql.SQLException => e }

  private val NoSuperuserHint =
    "if the first boot never created it, export QOD_ADMIN_PASSWORD and restart the manager once"

  private def resetConfigured(
      usernames: List[String],
      password: String,
      mustChange: Boolean,
      out: PrintStream,
      err: PrintStream,
      loadCfg: () => ManagerConfig,
      attach: EmbeddedPostgresConfig => Either[String, EmbeddedControlPlane.Handle]
  ): Int =
    def unreachable(e: Throwable): Int =
      err.println(s"error: control plane unreachable: ${e.getMessage}")
      2
    try
      val mgrCfg                                      = loadCfg()
      val coords: Either[String, Map[String, String]] =
        if !mgrCfg.embeddedPostgres.enabled then Right(mgrCfg.defaultMetastore.asMap)
        else
          attach(mgrCfg.embeddedPostgres).map { h =>
            mgrCfg.defaultMetastore.asMap ++ Map(
              "pgHost"     -> h.host,
              "pgPort"     -> h.port.toString,
              "pgUser"     -> h.user,
              "pgPassword" -> h.password
            )
          }
      coords match
        case Left(msg) =>
          err.println(s"error: $msg")
          1
        case Right(meta) =>
          try
            val store = UserStore.fromDefaultMetastore(meta, mgrCfg.auth.lockout)
            try
              val updated =
                usernames.distinct.filter(u => reset(store, u, password, mustChange) == 0)
              updated.foreach { u =>
                out.println(
                  s"password reset for superuser '$u'. Existing sessions are not revoked."
                )
              }
              if updated.nonEmpty then 0
              else
                val names = usernames.distinct.map(u => s"'$u'").mkString(", ")
                err.println(s"error: no superuser named $names; $NoSuperuserHint")
                1
            finally store.close()
          catch
            case NonFatal(e)
                if sqlCause(e).exists(x => x.getSQLState == "42P01" || x.getSQLState == "3D000") =>
              err.println("error: control plane not initialized; start the manager once first")
              1
            case NonFatal(e) => unreachable(e)
    catch case NonFatal(e) => unreachable(e)
