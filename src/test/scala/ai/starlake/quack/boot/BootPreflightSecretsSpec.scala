package ai.starlake.quack.boot

import ai.starlake.quack.Main
import ai.starlake.quack.ManagerConfig
import ai.starlake.quack.edge.auth.AuthenticatedProfile
import ai.starlake.quack.ondemand.api.{ResetTokenStore, SessionTokenStore}
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.state.LiquibaseRunner
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.postgresql.ds.PGSimpleDataSource
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import javax.sql.DataSource
import scala.util.Try

/** Pins the boot's session-secret contract (`BootPreflight.withSessionSecret`, the seam
  * `Main.bootManager` calls right after the Liquibase apply): a configured QOD_SESSION_JWT_SECRET
  * wins and the control plane is not touched; an unset one is the control plane's, created by the
  * first boot and read by every later boot, HA or not, so a session or a reset link minted before a
  * restart still verifies after it. Boot prints one line naming the source, never the value, and no
  * banner.
  *
  * Assertions compare as booleans: a failure must not print a secret or a config dump. The Postgres
  * cases cancel without the test Postgres (SL_TEST_PG_*).
  */
class BootPreflightSecretsSpec extends AnyFlatSpec with Matchers:
  import Main.given

  // Reaps any `qodbs_test_%` database an interrupted run left behind.
  TestPostgres.dropStrayTestDatabases("qodbs")

  // Overlays pin the secret regardless of QOD_* env vars in the invoking shell.
  private def load(overlay: String): ManagerConfig =
    ConfigSource
      .string(overlay)
      .withFallback(ConfigSource.default)
      .at("quack-on-demand")
      .loadOrThrow[ManagerConfig]

  /** `withSessionSecret` with stdout captured: (result, what it printed). */
  private def resolve(cfg: ManagerConfig, ds: => DataSource): (ManagerConfig, String) =
    val out    = new ByteArrayOutputStream()
    val result = Console.withOut(new PrintStream(out, true, StandardCharsets.UTF_8)) {
      BootPreflight.withSessionSecret(cfg, ds)
    }
    (result, out.toString(StandardCharsets.UTF_8))

  private def secretOf(cfg: ManagerConfig): String = cfg.auth.management.sessionJwtSecret

  private val unset  = """quack-on-demand { auth.management.sessionJwtSecret = "" }"""
  private val Pinned = "pinned-secret-0123456789abcdef0123456789"

  private val profile = AuthenticatedProfile(
    username = "admin",
    role = "admin",
    groups = Set.empty,
    claims = Map.empty,
    authMethod = "db",
    tenant = None
  )

  /** A fresh control-plane database with the changelog applied, as a plain DataSource. */
  private def withControlPlane(test: DataSource => Unit): Unit =
    TestPostgres.ensureReachable()
    val db = s"qodbs_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$db"""")
    try
      new LiquibaseRunner(TestPostgres.dbUrl(db), TestPostgres.pgUser, TestPostgres.pgPass).run()
      val ds = new PGSimpleDataSource()
      ds.setURL(TestPostgres.dbUrl(db))
      ds.setUser(TestPostgres.pgUser)
      ds.setPassword(TestPostgres.pgPass)
      test(ds)
    finally Try(TestPostgres.dropDatabase(db))

  "withSessionSecret" should "keep a configured secret without touching the control plane" in {
    val cfg      = load(s"""quack-on-demand { auth.management.sessionJwtSecret = "$Pinned" }""")
    val (out, p) = resolve(cfg, throw new AssertionError("control plane read"))
    (out == cfg) shouldBe true
    // Both places a value can come from: the env and the config key.
    p.contains(
      "session secret: from QOD_SESSION_JWT_SECRET (quack-on-demand.auth.management.sessionJwtSecret)"
    ) shouldBe true
    p.contains(Pinned) shouldBe false
  }

  it should "store one in the control plane when unset, and read it on the next boot" in
    withControlPlane { ds =>
      val (first, p1)  = resolve(load(unset), ds)
      val (second, p2) = resolve(load(unset), ds)
      (secretOf(first).length >= 64) shouldBe true
      (secretOf(second) == secretOf(first)) shouldBe true
      p1.contains("session secret: generated and stored in the control plane") shouldBe true
      p2.contains("session secret: stored in the control plane") shouldBe true
      // Neither boot prints the value, nor the banner that used to carry it.
      p1.contains(secretOf(first)) shouldBe false
      p2.contains(secretOf(first)) shouldBe false
      (p1 + p2).contains("GENERATED BOOT SECRET") shouldBe false
      (p1 + p2).contains("QOD_SESSION_JWT_SECRET=") shouldBe false
    }

  it should "keep a session and a reset link minted before a restart valid after it" in
    withControlPlane { ds =>
      val (before, _) = resolve(load(unset), ds)
      val session     = new SessionTokenStore(secretOf(before)).mintWithScope(
        profile,
        SessionScope(superuser = true, manageableTenants = Set.empty)
      )
      val resetLink  = new ResetTokenStore(secretOf(before)).mint("u-1", "hash-1")
      val (after, _) = resolve(load(unset), ds)
      new SessionTokenStore(secretOf(after)).get(session).isDefined shouldBe true
      new ResetTokenStore(secretOf(after)).verify(resetLink, "hash-1").isRight shouldBe true
    }

  it should "change nothing else in the configuration" in withControlPlane { ds =>
    val cfg      = load(unset)
    val (out, _) = resolve(cfg, ds)
    (out.copy(auth = cfg.auth) == cfg) shouldBe true
    (out.auth.copy(management = cfg.auth.management) == cfg.auth) shouldBe true
  }
