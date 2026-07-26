package ai.starlake.quack.ondemand

import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.{Connection, DriverManager}

/** TDD coverage for [[DuckLakeInitializer.guardDataPath]], the check that refuses to ATTACH a
  * tenant-db's DuckLake catalog when this manager's effective dataPath disagrees with the dataPath
  * already recorded in that catalog's own `ducklake_metadata` (e.g. a dev checkout and the `qod`
  * launcher sharing one control-plane Postgres, each pointing the same tenant-db at a different
  * storage root).
  *
  * This spec never runs an actual DuckLake ATTACH -- it hand-seeds a `ducklake_metadata` table with
  * `psql`/JDBC and calls the guard directly against a real Postgres connection, so it exercises the
  * exact query the guard runs without paying for the DuckDB side of
  * [[DuckLakeInitializerRaceSpec]].
  *
  * Skipped when no local Postgres is reachable (mirrors the other `*PostgresSpec` integration tests
  * under `state/testkit`).
  */
class DuckLakeDataPathGuardSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qoddlguard")

  Class.forName("org.postgresql.Driver")

  private def withFreshDb(test: Connection => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qoddlguard_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    val conn =
      DriverManager.getConnection(
        TestPostgres.dbUrl(dbName),
        TestPostgres.pgUser,
        TestPostgres.pgPass
      )
    try test(conn)
    finally
      conn.close()
      TestPostgres.dropDatabase(dbName)

  /** Hand-seed the `data_path` row the way a real DuckLake ATTACH would leave it, without paying
    * for an actual DuckDB engine in this spec.
    */
  private def seedDataPath(conn: Connection, value: String): Unit =
    val ddl = conn.createStatement()
    try
      ddl.execute(
        "CREATE TABLE ducklake_metadata (key varchar, value varchar, scope varchar, scope_id bigint)"
      )
    finally ddl.close()
    val ins =
      conn.prepareStatement("INSERT INTO ducklake_metadata (key, value) VALUES ('data_path', ?)")
    try
      ins.setString(1, value)
      ins.executeUpdate()
    finally ins.close()

  "guardDataPath" should "pass silently on a fresh catalog with no ducklake_metadata table" in
    withFreshDb { conn =>
      noException should be thrownBy
        DuckLakeInitializer.guardDataPath(conn, "some_db", "/data/some_db")
    }

  it should "pass when the recorded and effective absolute paths match exactly" in
    withFreshDb { conn =>
      seedDataPath(conn, "/data/some_db")
      noException should be thrownBy
        DuckLakeInitializer.guardDataPath(conn, "some_db", "/data/some_db")
    }

  it should "pass when the two absolute paths differ only by a trailing slash" in
    withFreshDb { conn =>
      seedDataPath(conn, "/data/some_db/")
      noException should be thrownBy
        DuckLakeInitializer.guardDataPath(conn, "some_db", "/data/some_db")
    }

  it should "fail with an actionable message when the absolute paths differ" in
    withFreshDb { conn =>
      seedDataPath(conn, "/data/other_install/some_db")
      val ex = intercept[DuckLakeInitializer.DataPathMismatchException] {
        DuckLakeInitializer.guardDataPath(conn, "some_db", "/data/some_db")
      }
      ex.getMessage should include("does not match the DuckLake catalog's recorded data_path")
      ex.getMessage should include("/data/some_db")
      ex.getMessage should include("/data/other_install/some_db")
      ex.getMessage should include("some_db")
    }

  it should "warn and pass when the recorded value is a relative path" in
    withFreshDb { conn =>
      seedDataPath(conn, "./ducklake/some_db")
      noException should be thrownBy
        DuckLakeInitializer.guardDataPath(conn, "some_db", "/data/some_db")
    }

  it should "warn and pass when the effective path is relative" in
    withFreshDb { conn =>
      seedDataPath(conn, "/data/some_db")
      noException should be thrownBy
        DuckLakeInitializer.guardDataPath(conn, "some_db", "ducklake/some_db")
    }

  it should "pass when scheme URLs match modulo a trailing slash" in
    withFreshDb { conn =>
      seedDataPath(conn, "s3://bucket/x/")
      noException should be thrownBy
        DuckLakeInitializer.guardDataPath(conn, "some_db", "s3://bucket/x")
    }

  it should "fail when scheme URLs point at different buckets or prefixes" in
    withFreshDb { conn =>
      seedDataPath(conn, "s3://bucket/y")
      val ex = intercept[DuckLakeInitializer.DataPathMismatchException] {
        DuckLakeInitializer.guardDataPath(conn, "some_db", "s3://bucket/x")
      }
      ex.getMessage should include("s3://bucket/x")
      ex.getMessage should include("s3://bucket/y")
    }
