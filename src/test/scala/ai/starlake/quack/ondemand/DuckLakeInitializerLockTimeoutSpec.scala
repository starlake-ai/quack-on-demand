package ai.starlake.quack.ondemand

import ai.starlake.quack.EmbeddedPostgresConfig
import ai.starlake.quack.boot.EmbeddedControlPlane
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files
import java.sql.{Connection, DriverManager}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/** The F11 regression end to end, over a real Postgres catalog: a throwaway embedded Postgres on a
  * free port (never the developer's local server), the real [[DuckLakeInitializer.initBlocking]].
  *
  *   - A boot over a current catalog runs no DDL, so an open read of the catalog's tables (a node
  *     mid-query, an orphaned transaction) cannot stall it.
  *   - A boot that does have to migrate waits at most [[DuckLakeInitializer.CatalogLockTimeoutMs]]
  *     behind such a read and fails with the retry-later
  *     [[DuckLakeInitializer.CatalogLockTimeoutException]], which proves the `lock_timeout` on the
  *     initializer's connstr reaches every metadata connection DuckLake opens.
  *   - A 1.0 catalog (a 1.5.x install) is migrated once and stamped.
  */
class DuckLakeInitializerLockTimeoutSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  Class.forName("org.postgresql.Driver")
  Class.forName("org.duckdb.DuckDBDriver")

  private def freePort(): Int =
    val s = new java.net.ServerSocket(0)
    try s.getLocalPort
    finally s.close()

  private var server: Option[EmbeddedControlPlane] = None

  /** Started on first use, so a run that filters every test out never boots Postgres. */
  private def pg: EmbeddedControlPlane =
    server.getOrElse {
      val dir = Files.createTempDirectory("qod-ducklake-locktimeout")
      val p   = EmbeddedControlPlane.start(
        EmbeddedPostgresConfig(enabled = true, port = freePort(), dataDir = dir.toString)
      )
      server = Some(p)
      p
    }

  override def afterAll(): Unit = server.foreach(_.stop())

  private def connect(db: String): Connection =
    DriverManager.getConnection(
      s"jdbc:postgresql://${pg.host}:${pg.port}/$db",
      pg.user,
      pg.password
    )

  private def exec(c: Connection, sql: String): Unit =
    val st = c.createStatement()
    try st.execute(sql)
    finally st.close()

  private def count(c: Connection, sql: String): Int =
    val st = c.createStatement()
    try
      val rs = st.executeQuery(sql)
      try
        rs.next()
        rs.getInt(1)
      finally rs.close()
    finally st.close()

  private def metastoreFor(db: String): Map[String, String] =
    Map(
      "pgHost"     -> pg.host,
      "pgPort"     -> pg.port.toString,
      "pgUser"     -> pg.user,
      "pgPassword" -> pg.password,
      "dbName"     -> db,
      "schemaName" -> "main",
      "dataPath"   -> s"${Files.createTempDirectory("qod-ducklake-data")}/"
    )

  /** Same rule as `DuckLakeInitializerRaceSpec`. */
  private def extensionUnavailable(t: Throwable): Boolean =
    val msg = Option(t.getMessage).getOrElse("").toLowerCase
    msg.contains("failed to download extension") ||
    msg.contains("unable to connect to extension repository") ||
    (msg.contains("extension") && msg.contains("not found"))

  private def init(meta: Map[String, String]): Unit =
    Try(DuckLakeInitializer.initBlocking(meta, encrypted = false, createMissingDatabase = true)) match
      case Success(_)                            => ()
      case Failure(t) if extensionUnavailable(t) =>
        cancel(s"DuckDB extensions unavailable (${t.getMessage}); skipping")
      case Failure(t) => throw t

  /** A fresh catalog database with a DDL log: an event trigger records the tag of every DDL
    * statement that reaches Postgres, so "no migration DDL" is observed, not inferred.
    */
  private def freshDb(): String =
    val db = s"qodlock_test_${System.nanoTime()}"
    pg.ensureDatabase(db)
    val c = connect(db)
    try
      exec(c, "CREATE SCHEMA qod_test")
      exec(c, "CREATE TABLE qod_test.ddl_log (tag text NOT NULL)")
      exec(
        c,
        "CREATE FUNCTION qod_test.log_ddl() RETURNS event_trigger LANGUAGE plpgsql AS " +
          "$$ BEGIN INSERT INTO qod_test.ddl_log (tag) VALUES (tg_tag); END $$"
      )
      exec(
        c,
        "CREATE EVENT TRIGGER qod_test_ddl ON ddl_command_start EXECUTE FUNCTION qod_test.log_ddl()"
      )
    finally c.close()
    db

  private def alterCount(db: String): Int =
    val c = connect(db)
    try count(c, "SELECT count(*) FROM qod_test.ddl_log WHERE tag = 'ALTER TABLE'")
    finally c.close()

  private def resetDdlLog(db: String): Unit =
    val c = connect(db)
    try exec(c, "TRUNCATE qod_test.ddl_log")
    finally c.close()

  private def stamp(db: String): Option[String] =
    val c = connect(db)
    try DuckLakeInitializer.readMigrationStamp(c, "public")
    finally c.close()

  /** A node mid-query: an open transaction that has read `ducklake_data_file`, so it holds ACCESS
    * SHARE on it until `release`.
    */
  private def openReader(db: String): Connection =
    val c = connect(db)
    c.setAutoCommit(false)
    count(c, "SELECT count(*) FROM ducklake_data_file")
    c

  /** A catalog as a DuckDB 1.5.x install left it: DuckLake 1.0, one table, no migration stamp. */
  private def createV10Catalog(db: String, meta: Map[String, String]): Unit =
    val duck = DriverManager.getConnection(
      "jdbc:duckdb:",
      DuckDbExtensionSource.fromEnv.connectionProperties
    )
    try
      val ext = DuckDbExtensionSource.fromEnv
      Try {
        exec(duck, ext.loadSql("ducklake"))
        exec(duck, ext.loadSql("postgres_scanner"))
      } match
        case Failure(t) if extensionUnavailable(t) =>
          cancel(s"DuckDB extensions unavailable (${t.getMessage}); skipping")
        case Failure(t) => throw t
        case Success(_) => ()
      exec(duck, "SET ducklake_default_version = '1.0'")
      exec(duck, CatalogPgSecret.createSql(CatalogPgSecret.Name, pg.password))
      exec(
        duck,
        DuckLakeInitializer.attachSql(
          CatalogPgSecret.connstr(pg.host, pg.port.toString, db, pg.user),
          "old",
          meta("dataPath"),
          encrypted = false,
          migrate = false
        )
      )
      exec(duck, "CREATE TABLE old.t AS SELECT range AS i FROM range(5)")
      exec(duck, "DETACH old")
    finally duck.close()
    val c = connect(db)
    try
      DuckLakeInitializer.readCatalogState(c).version shouldBe
        DuckLakeInitializer.MetadataRead.Value("1.0")
    finally c.close()

  "initBlocking" should "create a catalog and stamp the engine build that created it" in {
    val db   = freshDb()
    val meta = metastoreFor(db)
    init(meta)
    stamp(db).getOrElse(fail("no stamp recorded")) should startWith("duckdb=")
    val c = connect(db)
    try
      val state = DuckLakeInitializer.readCatalogState(c)
      state.version shouldBe
        DuckLakeInitializer.MetadataRead.Value(DuckLakeInitializer.LatestDuckLakeVersion)
      state.established shouldBe true
      state.schema shouldBe Some("public")
    finally c.close()
  }

  it should "run no DDL on a second boot of a current catalog, even behind an open read" in {
    val db   = freshDb()
    val meta = metastoreFor(db)
    init(meta)
    resetDdlLog(db)
    val reader = openReader(db)
    try
      // Before the fix this flagged attach queued its ALTER TABLE behind the reader forever.
      init(meta)
      alterCount(db) shouldBe 0
    finally
      reader.rollback()
      reader.close()
  }

  it should "give up on a needed migration behind an open read with a retry-later error" in {
    val db   = freshDb()
    val meta = metastoreFor(db)
    // A catalog older than the pinned DuckLake: the next boot must migrate.
    createV10Catalog(db, meta)

    val reader             = openReader(db)
    given ExecutionContext = ExecutionContext.global
    val started            = System.nanoTime()
    val attempt            = Future(
      Try(DuckLakeInitializer.initBlocking(meta, encrypted = false, createMissingDatabase = true))
    )
    val outcome            =
      try Await.result(attempt, 90.seconds)
      finally
        // Release before asserting, so a regression that hangs still lets the init finish.
        reader.rollback()
        reader.close()
    val elapsedMs = (System.nanoTime() - started) / 1000000
    outcome match
      case Failure(e: DuckLakeInitializer.CatalogLockTimeoutException) =>
        e.getMessage should include(db)
        e.getMessage should include("retried")
      case other => fail(s"expected CatalogLockTimeoutException, got $other")
    elapsedMs should be < 60000L
    stamp(db) shouldBe None

    // The read ended: the retry migrates and stamps the current build.
    resetDdlLog(db)
    init(meta)
    alterCount(db) should be > 0
    stamp(db) shouldBe defined
    val after = connect(db)
    try
      DuckLakeInitializer.readCatalogState(after).version shouldBe
        DuckLakeInitializer.MetadataRead.Value(DuckLakeInitializer.LatestDuckLakeVersion)
    finally after.close()
  }

  it should "migrate a 1.0 catalog once, then attach it as-is" in {
    val db   = freshDb()
    val meta = metastoreFor(db)
    createV10Catalog(db, meta)

    resetDdlLog(db)
    init(meta)
    alterCount(db) should be > 0
    val after = connect(db)
    try
      DuckLakeInitializer.readCatalogState(after).version shouldBe
        DuckLakeInitializer.MetadataRead.Value(DuckLakeInitializer.LatestDuckLakeVersion)
    finally after.close()
    stamp(db) shouldBe defined

    resetDdlLog(db)
    init(meta)
    alterCount(db) shouldBe 0
  }
