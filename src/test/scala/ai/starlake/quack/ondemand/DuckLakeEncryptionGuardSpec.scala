package ai.starlake.quack.ondemand

import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.{Connection, DriverManager}

/** TDD coverage for [[DuckLakeInitializer.guardEncryption]] and the metadata read it stands on, the
  * check that refuses to ATTACH a tenant-db's DuckLake catalog when the control-plane row's
  * `encrypted` flag disagrees with what the catalog itself recorded. A catalog's encryption is
  * stamped once, at creation, and can never be changed, so this guard turns a per-spawn DuckDB
  * error that repeats forever into one message naming the cause.
  *
  * Same shape as [[DuckLakeDataPathGuardSpec]]: no actual DuckLake ATTACH here, just a hand-seeded
  * `ducklake_metadata` over a real Postgres connection, so the exact queries the guard runs are
  * exercised without paying for the DuckDB side. [[DuckLakeInitializerRaceSpec]] covers the live
  * ATTACH.
  *
  * Skipped when no local Postgres is reachable.
  */
class DuckLakeEncryptionGuardSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodencguard")

  Class.forName("org.postgresql.Driver")

  private def withFreshDb(test: Connection => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodencguard_test_${System.nanoTime()}"
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

  /** Create the metadata table the way a real DuckLake ATTACH leaves it, optionally in a schema
    * other than `public` (the manager cannot assume `public`, which is why the read resolves the
    * schema rather than hardcoding it).
    */
  private def createMetadata(conn: Connection, schema: String = "public"): Unit =
    val ddl = conn.createStatement()
    try
      if schema != "public" then ddl.execute(s"""CREATE SCHEMA "$schema"""")
      ddl.execute(
        s"""CREATE TABLE "$schema".ducklake_metadata """ +
          "(key varchar, value varchar, scope varchar, scope_id bigint)"
      )
    finally ddl.close()

  private def seed(conn: Connection, key: String, value: String, schema: String = "public"): Unit =
    val ins = conn.prepareStatement(
      s"""INSERT INTO "$schema".ducklake_metadata (key, value) VALUES (?, ?)"""
    )
    try
      ins.setString(1, key)
      ins.setString(2, value)
      ins.executeUpdate()
    finally ins.close()

  /** An established catalog: one whose creating ATTACH has committed, so it has recorded its own
    * `data_path`. The encryption guard only reads an absent `encrypted` row as "unencrypted" on
    * such a catalog.
    */
  private def established(conn: Connection, schema: String = "public"): Unit =
    seed(conn, "data_path", "/data/some_db", schema)

  // ---------- readMetadata: three answers, not two ----------

  "readMetadata" should "report NoTable on a catalog with no ducklake_metadata at all" in
    withFreshDb { conn =>
      DuckLakeInitializer.readMetadata(conn, "encrypted") shouldBe
        DuckLakeInitializer.MetadataRead.NoTable
    }

  it should "report NoRow when the table exists but carries no row for the key" in
    withFreshDb { conn =>
      createMetadata(conn)
      established(conn)
      DuckLakeInitializer.readMetadata(conn, "encrypted") shouldBe
        DuckLakeInitializer.MetadataRead.NoRow
    }

  it should "report the recorded value when the row exists" in
    withFreshDb { conn =>
      createMetadata(conn)
      seed(conn, "encrypted", "true")
      DuckLakeInitializer.readMetadata(conn, "encrypted") shouldBe
        DuckLakeInitializer.MetadataRead.Value("true")
    }

  it should "resolve the table when it lives outside the public schema" in
    withFreshDb { conn =>
      createMetadata(conn, schema = "ducklake_meta")
      seed(conn, "encrypted", "true", schema = "ducklake_meta")
      DuckLakeInitializer.readMetadata(conn, "encrypted") shouldBe
        DuckLakeInitializer.MetadataRead.Value("true")
    }

  "catalogEstablished" should "be false before the creating ATTACH recorded data_path" in
    withFreshDb { conn =>
      DuckLakeInitializer.catalogEstablished(conn) shouldBe false
      createMetadata(conn)
      DuckLakeInitializer.catalogEstablished(conn) shouldBe false
    }

  it should "be true once data_path is recorded" in
    withFreshDb { conn =>
      createMetadata(conn)
      established(conn)
      DuckLakeInitializer.catalogEstablished(conn) shouldBe true
    }

  // ---------- guardEncryption ----------

  "guardEncryption" should "pass on a fresh catalog with no ducklake_metadata table" in
    withFreshDb { conn =>
      noException should be thrownBy
        DuckLakeInitializer.guardEncryption(conn, "some_db", encrypted = true)
      noException should be thrownBy
        DuckLakeInitializer.guardEncryption(conn, "some_db", encrypted = false)
    }

  it should "pass when the recorded value agrees with the tenant-db row" in
    withFreshDb { conn =>
      createMetadata(conn)
      established(conn)
      seed(conn, "encrypted", "true")
      noException should be thrownBy
        DuckLakeInitializer.guardEncryption(conn, "some_db", encrypted = true)
    }

  it should "refuse an unencrypted row pointed at a catalog created encrypted" in
    withFreshDb { conn =>
      createMetadata(conn)
      established(conn)
      seed(conn, "encrypted", "true")
      val ex = intercept[DuckLakeInitializer.EncryptionMismatchException] {
        DuckLakeInitializer.guardEncryption(conn, "some_db", encrypted = false)
      }
      ex.getMessage should include("some_db")
      ex.getMessage should include("was created encrypted")
    }

  it should "refuse an encrypted row pointed at a catalog created unencrypted" in
    withFreshDb { conn =>
      createMetadata(conn)
      established(conn)
      seed(conn, "encrypted", "false")
      val ex = intercept[DuckLakeInitializer.EncryptionMismatchException] {
        DuckLakeInitializer.guardEncryption(conn, "some_db", encrypted = true)
      }
      ex.getMessage should include("some_db")
      ex.getMessage should include("was created unencrypted")
    }

  /** The case the guard exists for, in the shape that used to slip past it: an established catalog
    * that never recorded `encrypted` at all. Reading that as "fresh, nothing to compare" would let
    * an encrypted tenant-db row attach an unencrypted catalog and fail per node spawn instead.
    */
  it should "refuse an encrypted row when an established catalog recorded no encrypted key" in
    withFreshDb { conn =>
      createMetadata(conn)
      established(conn)
      val ex = intercept[DuckLakeInitializer.EncryptionMismatchException] {
        DuckLakeInitializer.guardEncryption(conn, "some_db", encrypted = true)
      }
      ex.getMessage should include("was created unencrypted")
    }

  it should "still pass an unencrypted row when an established catalog recorded no encrypted key" in
    withFreshDb { conn =>
      createMetadata(conn)
      established(conn)
      noException should be thrownBy
        DuckLakeInitializer.guardEncryption(conn, "some_db", encrypted = false)
    }

  /** The guard runs BEFORE the advisory lock, so it can see a concurrent initializer's metadata
    * table between its CREATE TABLE and its INSERTs. A table with no `data_path` yet is exactly
    * that window, and must not read as an unencrypted catalog.
    */
  it should "pass when the metadata table exists but the catalog is not established yet" in
    withFreshDb { conn =>
      createMetadata(conn)
      noException should be thrownBy
        DuckLakeInitializer.guardEncryption(conn, "some_db", encrypted = true)
    }
