package ai.starlake.quack.boot

import java.util.Locale
import ai.starlake.quack.{EmbeddedPostgresConfig, ManagerConfig}
import ai.starlake.quack.edge.config.AuthenticationConfig
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres

import java.nio.file.{Files, Path, Paths}
import java.sql.DriverManager

/** A persistent embedded Postgres holding the control plane: the zero-prerequisite single-node mode
  * behind `qod serve`.
  *
  * Sibling of [[ai.starlake.quack.ondemand.demo.DemoPostgres]] in shape, deliberately NOT in
  * behaviour. Three divergences are the whole point of this class existing separately, and none of
  * them may be "unified" into a shared base later:
  *
  *   1. The data directory is never wiped before start. `DemoHome` wipes because a demo must always
  *      initdb fresh; here the directory IS the control plane.
  *   2. The data directory is never deleted on stop. Persistence is the feature.
  *   3. A stale `postmaster.pid` naming a DEAD process falls through to ordinary Postgres crash
  *      recovery (`setCleanDataDirectory(false)` keeps the WAL). The demo treats that same state as
  *      "wipe and start over", which here would silently destroy every tenant.
  *
  * The live-pid refusal IS kept: a `postmaster.pid` naming a process that is still alive means
  * another manager owns this directory, and booting a second server on it would corrupt it.
  *
  * The port is fixed rather than OS-assigned (zonky's default) so coordinates stay stable across
  * restarts and `psql -p <port>` works for support.
  */
final class EmbeddedControlPlane private (
    instance: EmbeddedPostgres,
    val host: String,
    val port: Int,
    val user: String,
    val password: String
):

  /** Idempotent `CREATE DATABASE`. Postgres has no `IF NOT EXISTS` for databases, so probe
    * `pg_database` first and skip when present.
    */
  def ensureDatabase(name: String): Unit =
    val admin =
      DriverManager.getConnection(s"jdbc:postgresql://$host:$port/postgres", user, password)
    try
      val exists =
        val ps = admin.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")
        ps.setString(1, name)
        try ps.executeQuery().next()
        finally ps.close()
      if !exists then
        val st = admin.createStatement()
        try st.executeUpdate(s"""CREATE DATABASE "$name"""")
        finally st.close()
    finally admin.close()

  /** Stop the server. Never deletes the data directory. */
  def stop(): Unit = instance.close()

object EmbeddedControlPlane:

  Class.forName("org.postgresql.Driver")

  /** zonky's superuser authenticates via `trust` (the password is not verified), but
    * `LiquibaseRunner`, `PostgresControlPlaneStore` and spawned Quack nodes all gate on a non-empty
    * `pgPassword`, so we report a placeholder. The value is irrelevant to the server.
    */
  private val User     = "postgres"
  private val Password = "postgres"

  /** `dataDir` when non-empty, else `<platform user-data dir>/pg`. The default must match what
    * `qod serve` reports in its banner: `platformdirs.user_data_dir("qod")` on the Python side.
    */
  def resolveDataDir(configured: String): Path =
    val trimmed = configured.trim
    if trimmed.nonEmpty then Paths.get(trimmed)
    else
      val home = System.getProperty("user.home")
      val base = System.getProperty("os.name").toLowerCase(Locale.ROOT) match
        case os if os.contains("mac") => Paths.get(home, "Library", "Application Support", "qod")
        case os if os.contains("win") =>
          // platformdirs on Windows appends appauthor/appname, and appauthor defaults to the
          // appname, so user_data_dir("qod") resolves to LOCALAPPDATA\qod\qod. The CLI's
          // default_data_dir() already anchors `qod start` state there, so this must agree.
          Option(System.getenv("LOCALAPPDATA"))
            .filter(_.nonEmpty)
            .map(Paths.get(_, "qod", "qod"))
            .getOrElse(Paths.get(home, "AppData", "Local", "qod", "qod"))
        case _ =>
          Option(System.getenv("XDG_DATA_HOME"))
            .filter(_.nonEmpty)
            .map(Paths.get(_, "qod"))
            .getOrElse(Paths.get(home, ".local", "share", "qod"))
      base.resolve("pg")

  /** Start (or restart onto an existing data directory) the embedded server. */
  def start(cfg: EmbeddedPostgresConfig): EmbeddedControlPlane =
    val root   = resolveDataDir(cfg.dataDir)
    val pgData = root.resolve("pgdata")
    Files.createDirectories(root)
    livePostmaster(pgData).foreach(pid =>
      sys.error(
        s"another manager already owns the embedded control plane at $pgData (postgres pid " +
          s"$pid) - stop it first, or point QOD_PG_EMBEDDED_DATA_DIR elsewhere"
      )
    )
    val instance =
      try
        EmbeddedPostgres
          .builder()
          .setOverrideWorkingDirectory(root.toFile)
          .setDataDirectory(pgData.toFile)
          .setCleanDataDirectory(false)
          .setPort(cfg.port)
          .start()
      catch case scala.util.control.NonFatal(t) => throw startFailure(t, pgData)
    new EmbeddedControlPlane(instance, "localhost", instance.getPort, User, Password)

  /** Copy the live coordinates onto the manager config. Touches ONLY the Postgres coordinate fields
    * of `defaultMetastore` (pgHost, pgPort, pgUser, pgPassword); `dbName` deliberately stays as
    * configured, and nothing else on the config moves. This is what keeps the persistent embedded
    * mode outside the demo posture.
    */
  def applyCoordinates(mgrCfg: ManagerConfig, cp: EmbeddedControlPlane): ManagerConfig =
    mgrCfg.copy(defaultMetastore =
      mgrCfg.defaultMetastore.copy(
        pgHost = cp.host,
        pgPort = cp.port.toString,
        pgUser = cp.user,
        pgPassword = cp.password
      )
    )

  /** The `auth.database` block's `jdbcUrl`/`username`/`password` are HOCON-substituted from
    * `defaultMetastore` at CONFIG-LOAD time, before this embedded server exists, so they still
    * point at the config-file coordinates rather than the live embedded server. Re-anchor them to
    * the live server, per key, unless the operator explicitly overrode that key via its own env var
    * (`QOD_AUTH_DB_JDBC_URL` / `_USER` / `_PASSWORD`) -- an explicit override is a deliberate
    * decision to authenticate against a different database and must keep winning. `dbName` is
    * passed in rather than read off `cp`/`authCfg` because it is the caller's already-resolved
    * control-plane database name (the same one `ensureDatabase` was called with), not a field this
    * class or the auth config carries. Only the `database` sub-block moves; every other auth field
    * (oidc, jwt, queries, the `enabled` flag) is untouched.
    */
  def applyAuthCoordinates(
      authCfg: AuthenticationConfig,
      cp: EmbeddedControlPlane,
      dbName: String,
      env: String => Option[String]
  ): AuthenticationConfig =
    val db = authCfg.database
    authCfg.copy(database =
      db.copy(
        jdbcUrl = env("QOD_AUTH_DB_JDBC_URL")
          .getOrElse(s"jdbc:postgresql://${cp.host}:${cp.port}/$dbName"),
        username = env("QOD_AUTH_DB_USER").getOrElse(cp.user),
        password = env("QOD_AUTH_DB_PASSWORD").getOrElse(cp.password)
      )
    )

  /** zonky reports a failed child as a bare `IllegalStateException: Process [...initdb...] failed`
    * and routes that child's stdout/stderr - the line that actually says what went wrong - to an
    * INFO logger, which the default `QOD_LOG_LEVEL=ERROR` swallows. Re-throw pointing at the knob
    * that makes the real message visible, and at the port, which is the other common cause.
    */
  private[boot] def startFailure(cause: Throwable, dataDir: Path): Throwable =
    new IllegalStateException(
      s"embedded Postgres failed to start on $dataDir - if the port is already in use, set " +
        "QOD_PG_EMBEDDED_PORT; otherwise re-run with QOD_LOG_LEVEL=INFO to see the " +
        s"initdb/postgres output (${cause.getMessage})",
      cause
    )

  /** The pid from `pgdata/postmaster.pid` when that process is still alive, else `None` (no file,
    * unreadable, unparseable, or a pid that has since died). A dead pid deliberately returns None:
    * that is the crashed-run case, and Postgres recovers it from the WAL on the next start.
    */
  private def livePostmaster(pgData: Path): Option[Long] =
    val pidFile = pgData.resolve("postmaster.pid")
    if !Files.exists(pidFile) then None
    else
      scala.util
        .Try(Files.readString(pidFile).linesIterator.next().trim.toLong)
        .toOption
        .filter(pid => ProcessHandle.of(pid).filter(_.isAlive).isPresent)
