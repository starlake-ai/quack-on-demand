package ai.starlake.quack.ondemand.demo

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres

import java.nio.file.Path
import java.sql.DriverManager

/** Coordinates for reaching the embedded demo Postgres. */
final case class PgCoords(host: String, port: Int, user: String, password: String):
  def jdbcUrl(db: String): String = s"jdbc:postgresql://$host:$port/$db"

/** Ephemeral embedded Postgres for `qod demo`. Wraps a zonky [[EmbeddedPostgres]] whose data
  * directory lives under the demo home, so teardown removes it with the rest of the demo state.
  */
final class DemoPostgres private (instance: EmbeddedPostgres, val coords: PgCoords):

  /** Idempotent `CREATE DATABASE`. Postgres has no `IF NOT EXISTS` for databases, so we probe
    * `pg_database` first and skip when present.
    */
  def createDatabase(name: String): Unit =
    val admin =
      DriverManager.getConnection(coords.jdbcUrl("postgres"), coords.user, coords.password)
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

  def stop(): Unit = instance.close()

object DemoPostgres:

  Class.forName("org.postgresql.Driver")

  /** Start an embedded Postgres rooted at `dataDir` on an OS-assigned free port. zonky's default
    * superuser is `postgres`; it authenticates via `trust` (the password is not verified), so we
    * report a non-empty placeholder password. Downstream consumers (`LiquibaseRunner`,
    * `PostgresControlPlaneStore`, spawned Quack nodes) require a non-empty `pgPassword`
    * (`required(_).filter(_.nonEmpty)`); an empty string fails their config gate even though trust
    * auth would accept it. The value itself is irrelevant to the embedded server.
    */
  def start(dataDir: Path): DemoPostgres =
    val instance = EmbeddedPostgres
      .builder()
      .setOverrideWorkingDirectory(dataDir.toFile)
      .setDataDirectory(dataDir.resolve("pgdata").toFile)
      .start()
    val coords = PgCoords("localhost", instance.getPort, "postgres", "postgres")
    new DemoPostgres(instance, coords)
