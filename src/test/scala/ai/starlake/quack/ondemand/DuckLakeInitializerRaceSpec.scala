package ai.starlake.quack.ondemand

import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import scala.sys.process._
import scala.util.Try

/** Regression for issue #3 (Node-init race on `CREATE TABLE
  * __ducklake_metadata`).
  *
  * On a fresh tenant-db Postgres, DuckLake's first ATTACH runs
  * `CREATE TABLE __ducklake_metadata` (plus sibling tables). Concurrent
  * initializers (multiple managers, or a manager racing with parallel
  * `spawn-quack-node.sh` invocations on K8s) used to race on Postgres's
  * `pg_type_typname_nsp_index` uniqueness:
  *
  * {{{
  * ERROR: duplicate key value violates unique constraint
  *        "pg_type_typname_nsp_index"
  * DETAIL: Key (typname, typnamespace)=(ducklake_metadata, 2200) already exists.
  * }}}
  *
  * Fix: `DuckLakeInitializer.runInit` now wraps the ATTACH in a per-
  * dbname `pg_advisory_lock` taken on a side-channel Postgres
  * connection. This test fans out N parallel `initBlocking` calls
  * against the same fresh Postgres database and asserts every one
  * succeeds.
  *
  * Skipped when no local Postgres is reachable (mirrors the other
  * `*PostgresSpec` integration tests under `state/testkit`). */
class DuckLakeInitializerRaceSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qoddl")

  private val pgHost = sys.env.getOrElse("SL_TEST_PG_HOST",     "localhost")
  private val pgPort = sys.env.getOrElse("SL_TEST_PG_PORT",     "5432").toInt
  private val pgUser = sys.env.getOrElse("SL_TEST_PG_USER",     "postgres")
  private val pgPass = sys.env.getOrElse("SL_TEST_PG_PASSWORD", "azizam")

  Class.forName("org.postgresql.Driver")

  private def adminUrl: String = s"jdbc:postgresql://$pgHost:$pgPort/postgres"

  private def pgReachable: Boolean =
    Try {
      val c = DriverManager.getConnection(adminUrl, pgUser, pgPass)
      try c.isValid(2) finally c.close()
    }.getOrElse(false)

  private def psql(targetDb: String, sql: String): Unit =
    val rc = Process(
      Seq("psql", "-h", pgHost, "-p", pgPort.toString, "-U", pgUser, "-d", targetDb, "-tAc", sql),
      None,
      "PGPASSWORD" -> pgPass
    ).!
    assert(rc == 0, s"psql ($sql) exit=$rc")

  private def withFreshDb(test: String => Unit): Unit =
    if !pgReachable then cancel(
      s"local Postgres not reachable at $pgHost:$pgPort (SL_TEST_PG_* envs); skipping"
    )
    val dbName = s"qoddl_test_${System.nanoTime()}"
    psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try test(dbName)
    finally Try(psql("postgres", s"""DROP DATABASE IF EXISTS "$dbName" WITH (FORCE)"""))

  "DuckLakeInitializer.initBlocking" should "serialize concurrent ATTACHes on the same tenant-db" in
    withFreshDb { db =>
      val dataPath = java.nio.file.Files.createTempDirectory("qoddl-race-").toString
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
      val n        = 4
      val barrier  = new java.util.concurrent.CyclicBarrier(n)
      val results  = new java.util.concurrent.ConcurrentLinkedQueue[Either[Throwable, Unit]]()
      val threads: List[Thread] = (1 to n).toList.map { i =>
        val runnable: Runnable = () =>
          barrier.await()
          val r = Try(DuckLakeInitializer.initBlocking(metastore)).toEither
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