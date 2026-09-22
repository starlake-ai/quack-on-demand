package ai.starlake.quack.ondemand

import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import scala.sys.process._
import scala.util.{Failure, Success, Try}

/** Regression for issue #3 (Node-init race on `CREATE TABLE __ducklake_metadata`).
  *
  * On a fresh tenant-db Postgres, DuckLake's first ATTACH runs `CREATE TABLE __ducklake_metadata`
  * (plus sibling tables). Concurrent initializers (multiple managers, or a manager racing with
  * parallel `spawn-quack-node.sh` invocations on K8s) used to race on Postgres's
  * `pg_type_typname_nsp_index` uniqueness:
  *
  * {{{
  * ERROR: duplicate key value violates unique constraint
  *        "pg_type_typname_nsp_index"
  * DETAIL: Key (typname, typnamespace)=(ducklake_metadata, 2200) already exists.
  * }}}
  *
  * Fix: `DuckLakeInitializer.runInit` now wraps the ATTACH in a per- dbname `pg_advisory_lock`
  * taken on a side-channel Postgres connection. This test fans out N parallel `initBlocking` calls
  * against the same fresh Postgres database and asserts every one succeeds.
  *
  * Skipped when no local Postgres is reachable (mirrors the other `*PostgresSpec` integration tests
  * under `state/testkit`).
  */
class DuckLakeInitializerRaceSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qoddl")

  private val pgHost = sys.env.getOrElse("SL_TEST_PG_HOST", "localhost")
  private val pgPort = sys.env.getOrElse("SL_TEST_PG_PORT", "5432").toInt
  private val pgUser = sys.env.getOrElse("SL_TEST_PG_USER", "postgres")
  private val pgPass = sys.env.getOrElse("SL_TEST_PG_PASSWORD", "azizam")

  Class.forName("org.postgresql.Driver")

  private def adminUrl: String = s"jdbc:postgresql://$pgHost:$pgPort/postgres"

  private def pgReachable: Boolean =
    Try {
      val c = DriverManager.getConnection(adminUrl, pgUser, pgPass)
      try c.isValid(2)
      finally c.close()
    }.getOrElse(false)

  private def psql(targetDb: String, sql: String): Unit =
    val rc = Process(
      Seq("psql", "-h", pgHost, "-p", pgPort.toString, "-U", pgUser, "-d", targetDb, "-tAc", sql),
      None,
      "PGPASSWORD" -> pgPass
    ).!
    assert(rc == 0, s"psql ($sql) exit=$rc")

  /** The metastore map `initBlocking` reads, pointed at a fresh tenant-db and a fresh temp
    * dataPath.
    */
  private def metastoreFor(db: String, dataPath: String): Map[String, String] =
    Map(
      "pgHost"     -> pgHost,
      "pgPort"     -> pgPort.toString,
      "pgUser"     -> pgUser,
      "pgPassword" -> pgPass,
      "dbName"     -> db,
      "schemaName" -> "main",
      "dataPath"   -> dataPath
    )

  /** Reads a `ducklake_metadata` key back off the tenant-db Postgres, or `None` when the catalog
    * recorded no such key. Deliberately a raw JDBC read rather than
    * `DuckLakeInitializer.readMetadata`, so the assertion is about what DuckLake actually wrote and
    * not about the helper under test agreeing with itself.
    */
  private def metadataValue(db: String, key: String): Option[String] =
    val conn = DriverManager.getConnection(
      s"jdbc:postgresql://$pgHost:$pgPort/$db",
      pgUser,
      pgPass
    )
    try
      val st = conn.prepareStatement("SELECT value FROM ducklake_metadata WHERE key = ? LIMIT 1")
      try
        st.setString(1, key)
        val rs = st.executeQuery()
        try if rs.next() then Option(rs.getString(1)) else None
        finally rs.close()
      finally st.close()
    finally conn.close()

  /** The DuckDB engine ships inside the JDBC driver, but INSTALL ducklake / INSTALL postgres still
    * fetch from the extension repository on a machine that has never cached them. Treat that as
    * "DuckDB unavailable" and skip. Anything else, notably DuckLake rejecting the `ENCRYPTED`
    * option, is a real failure and must surface.
    */
  private def extensionUnavailable(t: Throwable): Boolean =
    val msg = Option(t.getMessage).getOrElse("").toLowerCase
    msg.contains("failed to download extension") ||
    msg.contains("unable to connect to extension repository") ||
    (msg.contains("extension") && msg.contains("not found"))

  private def initOrSkip(metastore: Map[String, String], encrypted: Boolean): Unit =
    Try(DuckLakeInitializer.initBlocking(metastore, encrypted)) match
      case Success(_)                            => ()
      case Failure(t) if extensionUnavailable(t) =>
        cancel(s"DuckDB extensions unavailable (${t.getMessage}); skipping")
      case Failure(t) => throw t

  private def withFreshDb(test: String => Unit): Unit =
    if !pgReachable then
      cancel(
        s"local Postgres not reachable at $pgHost:$pgPort (SL_TEST_PG_* envs); skipping"
      )
    val dbName = s"qoddl_test_${System.nanoTime()}"
    psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try test(dbName)
    finally Try(psql("postgres", s"""DROP DATABASE IF EXISTS "$dbName" WITH (FORCE)"""))

  "DuckLakeInitializer.initBlocking" should "serialize concurrent ATTACHes on the same tenant-db" in
    withFreshDb { db =>
      val dataPath  = java.nio.file.Files.createTempDirectory("qoddl-race-").toString
      val metastore = Map(
        "pgHost"     -> pgHost,
        "pgPort"     -> pgPort.toString,
        "pgUser"     -> pgUser,
        "pgPassword" -> pgPass,
        "dbName"     -> db,
        "schemaName" -> "main",
        "dataPath"   -> dataPath
      )

      // Fan out N parallel initBlocking calls. Without the advisory
      // lock, at least one of these would crash with the pg_type race.
      val n       = 4
      val barrier = new java.util.concurrent.CyclicBarrier(n)
      val results = new java.util.concurrent.ConcurrentLinkedQueue[Either[Throwable, Unit]]()
      val threads: List[Thread] = (1 to n).toList.map { i =>
        val runnable: Runnable = () =>
          barrier.await()
          val r = Try(DuckLakeInitializer.initBlocking(metastore, encrypted = false)).toEither
          results.add(r.map(_ => ()))
          ()
        val t = new Thread(runnable, s"ducklake-init-$i")
        t.start()
        t
      }
      threads.foreach(_.join(60_000L))
      results.size shouldBe n
      val errors = (1 to n).flatMap(_ => Option(results.poll())).collect { case Left(e) => e }
      errors shouldBe empty
    }

  /** The pure `attachSql` tests can only assert the string's shape. This one settles what the
    * string actually does: whether DuckLake accepts a bare `ENCRYPTED` on the creating ATTACH, and
    * what it then records. If it did not, every encrypted catalog would fail at creation while the
    * pure tests stayed green.
    */
  it should "create an encrypted catalog and record it in ducklake_metadata" in
    withFreshDb { db =>
      val dataPath = java.nio.file.Files.createTempDirectory("qoddl-enc-").toString
      initOrSkip(metastoreFor(db, dataPath), encrypted = true)
      metadataValue(db, "encrypted") shouldBe Some("true")
      metadataValue(db, "data_path") should not be empty
    }

  /** The companion observation, and the one that decides whether `recordedEncryption`'s
    * "established catalog with no `encrypted` row" arm can ever fire against a real catalog: does
    * DuckLake write `encrypted='false'` for an unencrypted catalog, or omit the key? Asserted as
    * "not true" so the test holds under either answer; the value observed is reported by `info`.
    */
  it should "not record an unencrypted catalog as encrypted" in
    withFreshDb { db =>
      val dataPath = java.nio.file.Files.createTempDirectory("qoddl-plain-").toString
      initOrSkip(metastoreFor(db, dataPath), encrypted = false)
      val recorded = metadataValue(db, "encrypted")
      info(s"unencrypted catalog recorded ducklake_metadata.encrypted = $recorded")
      recorded should not be Some("true")
      metadataValue(db, "data_path") should not be empty
    }

  /** Re-attaching an already-created catalog while still passing `ENCRYPTED` is what every node
    * spawn does after the creating ATTACH, so it has to be legal. `initBlocking` is idempotent, so
    * running it a second time is exactly that second attach.
    */
  it should "tolerate a second ENCRYPTED attach on an already-created catalog" in
    withFreshDb { db =>
      val dataPath  = java.nio.file.Files.createTempDirectory("qoddl-enc2-").toString
      val metastore = metastoreFor(db, dataPath)
      initOrSkip(metastore, encrypted = true)
      noException should be thrownBy DuckLakeInitializer.initBlocking(metastore, encrypted = true)
      metadataValue(db, "encrypted") shouldBe Some("true")
    }

  /** End to end for the guard: a catalog really created unencrypted, then a tenant-db row that asks
    * for encryption. Without the guard this is DuckDB's raw "Failed to set encryption" error once
    * per node spawn, forever.
    */
  it should "refuse to attach an existing unencrypted catalog as encrypted" in
    withFreshDb { db =>
      val dataPath  = java.nio.file.Files.createTempDirectory("qoddl-flip-").toString
      val metastore = metastoreFor(db, dataPath)
      initOrSkip(metastore, encrypted = false)
      val ex = intercept[DuckLakeInitializer.EncryptionMismatchException] {
        DuckLakeInitializer.initBlocking(metastore, encrypted = true)
      }
      ex.getMessage should include("was created unencrypted")
      ex.getMessage should include(db)
    }
