package ai.starlake.quack.ondemand.state

import ai.starlake.quack.ondemand.manifest.ManifestExporter
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import io.circe.syntax.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

/** The session JWT secret: QOD_SESSION_JWT_SECRET when set, else one value created once in the
  * control plane (`qodstate_boot_secret`, row `session_jwt`) and read back by every boot and every
  * replica. Assertions compare as booleans or counts: a failure must never print a secret. The
  * Postgres cases cancel without the test Postgres (SL_TEST_PG_*).
  */
class SessionSecretStoreSpec extends AnyFlatSpec with Matchers:

  // Reaps any `qodss_test_%` database an interrupted run left behind.
  TestPostgres.dropStrayTestDatabases("qodss")

  private val Explicit = "explicit-session-secret-0123456789abcdef0123456789"

  /** A fresh control-plane database with the changelog applied, and a pool of `poolSize`. */
  private def withControlPlane(poolSize: Int = 2)(test: (String, HikariDataSource) => Unit): Unit =
    TestPostgres.ensureReachable()
    val db = s"qodss_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$db"""")
    try
      new LiquibaseRunner(TestPostgres.dbUrl(db), TestPostgres.pgUser, TestPostgres.pgPass).run()
      val hc = new HikariConfig()
      hc.setJdbcUrl(TestPostgres.dbUrl(db))
      hc.setUsername(TestPostgres.pgUser)
      hc.setPassword(TestPostgres.pgPass)
      hc.setMaximumPoolSize(poolSize)
      val ds = new HikariDataSource(hc)
      try test(db, ds)
      finally ds.close()
    finally Try(TestPostgres.dropDatabase(db))

  /** The stored rows, read over their own connection: (row count, the `session_jwt` value). */
  private def stored(db: String): (Int, Option[String]) =
    val c =
      DriverManager.getConnection(TestPostgres.dbUrl(db), TestPostgres.pgUser, TestPostgres.pgPass)
    try
      val rs = c.createStatement().executeQuery("SELECT name, value FROM qodstate_boot_secret")
      var n  = 0
      var value: Option[String] = None
      while rs.next() do
        n += 1
        if rs.getString(1) == SessionSecretStore.RowName then value = Some(rs.getString(2))
      (n, value)
    finally c.close()

  "a configured secret" should "win without touching the control plane" in {
    val r = SessionSecretStore.resolve(Explicit, throw new AssertionError("control plane read"))
    (r.secret == Explicit) shouldBe true
    r.source shouldBe SessionSecretStore.Source.Configured
  }

  it should "be used as configured, surrounding whitespace included" in {
    // Starflow verifies the manager's JWTs with the same configured string: trimming it here
    // would break a pairing that works today.
    val padded = s" $Explicit "
    val r      = SessionSecretStore.resolve(padded, throw new AssertionError("control plane read"))
    (r.secret == padded) shouldBe true
  }

  it should "write nothing to an empty control plane" in withControlPlane() { (db, ds) =>
    SessionSecretStore.resolve(Explicit, ds).source shouldBe SessionSecretStore.Source.Configured
    stored(db)._1 shouldBe 0
  }

  it should "leave a stored row untouched, so unsetting it falls back to that row" in
    withControlPlane() { (db, ds) =>
      val first = SessionSecretStore.resolve("", ds)
      SessionSecretStore.resolve(Explicit, ds)
      val (rows, value) = stored(db)
      rows shouldBe 1
      value.contains(first.secret) shouldBe true
      value.contains(Explicit) shouldBe false
      (SessionSecretStore.resolve("", ds).secret == first.secret) shouldBe true
    }

  "an unset secret" should "be created on the first call and read back by the next" in
    withControlPlane() { (db, ds) =>
      val first  = SessionSecretStore.resolve("", ds)
      val second = SessionSecretStore.resolve("   ", ds)
      first.source shouldBe SessionSecretStore.Source.Created
      second.source shouldBe SessionSecretStore.Source.Stored
      // At least as strong as SessionTokenStore.randomSecret (32 random bytes).
      (first.secret.length >= 64) shouldBe true
      (second.secret == first.secret) shouldBe true
      val (rows, value) = stored(db)
      rows shouldBe 1
      value.contains(first.secret) shouldBe true
    }

  it should "converge on one value when two connections create it at once" in
    withControlPlane(poolSize = 4) { (db, ds) =>
      val pool               = Executors.newFixedThreadPool(2)
      given ExecutionContext = ExecutionContext.fromExecutor(pool)
      try
        val go      = new CountDownLatch(1)
        val results = (1 to 2).map { _ =>
          Future {
            go.await(10, TimeUnit.SECONDS)
            SessionSecretStore.loadOrCreate(ds)
          }
        }
        go.countDown()
        val got = Await.result(Future.sequence(results), 30.seconds)
        got.map(_.secret).distinct.size shouldBe 1
        got.count(_.source == SessionSecretStore.Source.Created) shouldBe 1
        stored(db)._1 shouldBe 1
      finally pool.shutdownNow()
    }

  "a stored secret shorter than 32 characters" should "refuse, naming the fix but not the value" in
    withControlPlane() { (db, ds) =>
      SessionSecretStore.loadOrCreate(ds)
      val short = "short-hand-edited-value"
      TestPostgres.psql(db, s"UPDATE qodstate_boot_secret SET value = '$short'")
      val err = intercept[IllegalStateException](SessionSecretStore.resolve("", ds))
      err.getMessage.contains(
        "DELETE FROM qodstate_boot_secret WHERE name = 'session_jwt'"
      ) shouldBe
        true
      err.getMessage.contains(short) shouldBe false
    }

  "a resolved secret" should "never render its value" in withControlPlane() { (_, ds) =>
    val r = SessionSecretStore.resolve("", ds)
    r.toString.contains(r.secret) shouldBe false
    SessionSecretStore.resolve(Explicit, ds).toString.contains(Explicit) shouldBe false
  }

  "the manifest export" should "not carry the stored secret" in withControlPlane() { (db, ds) =>
    val secret = SessionSecretStore.resolve("", ds).secret
    val store  = new PostgresControlPlaneStore(
      TestPostgres.dbUrl(db),
      TestPostgres.pgUser,
      TestPostgres.pgPass
    )
    try
      val manifest = ManifestExporter
        .build(store, Instant.EPOCH, "0.0.0", "test")
        .fold(e => fail(e.message), identity)
      manifest.asJson.noSpaces.contains(secret) shouldBe false
    finally store.close()
  }
