package ai.starlake.quack.cli

import ai.starlake.quack.ManagerConfig
import ai.starlake.quack.Main.given
import ai.starlake.quack.boot.EmbeddedControlPlane
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, UserStore}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import at.favre.lib.crypto.bcrypt.BCrypt
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, PrintStream}
import java.nio.file.Files
import java.sql.DriverManager
import scala.util.Try

class AdminResetCliSpec extends AnyFlatSpec with Matchers:

  private def withFreshDb(test: String => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodreset_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      test(url)
    finally Try(TestPostgres.dropDatabase(dbName))

  private def column(url: String, sql: String): String =
    val c = DriverManager.getConnection(url, TestPostgres.pgUser, TestPostgres.pgPass)
    try
      val rs = c.createStatement().executeQuery(sql)
      rs.next()
      rs.getString(1)
    finally c.close()

  private def quiet: PrintStream = new PrintStream(new ByteArrayOutputStream())

  private val ReEnabled = "was disabled and is now enabled; its personal access tokens work again"
  private val LeftDisabled = "is disabled and was left disabled"

  private def disable(url: String, username: String): Unit =
    val c = DriverManager.getConnection(url, TestPostgres.pgUser, TestPostgres.pgPass)
    try
      c.createStatement()
        .executeUpdate(
          s"UPDATE qodstate_user SET enabled = FALSE WHERE tenant IS NULL AND username = '$username'"
        )
      ()
    finally c.close()

  "reset" should "rehash, clear lockout and honor --must-change" in withFreshDb { url =>
    val store = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
    try
      store.upsertUser(None, "root", "old-pw", "admin")
      val c = DriverManager.getConnection(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try
        c.createStatement()
          .executeUpdate(
            "UPDATE qodstate_user SET failed_attempts = 7, locked_at = NOW() WHERE username = 'root'"
          )
      finally c.close()
      AdminResetCli.reset(
        store,
        "root",
        "new-pw",
        mustChange = true,
        reenable = false,
        quiet
      ) shouldBe 0
      val hash = column(url, "SELECT password_hash FROM qodstate_user WHERE username = 'root'")
      BCrypt.verifyer().verify("new-pw".toCharArray, hash).verified shouldBe true
      column(url, "SELECT failed_attempts FROM qodstate_user WHERE username = 'root'") shouldBe "0"
      column(
        url,
        "SELECT locked_at IS NULL FROM qodstate_user WHERE username = 'root'"
      ) shouldBe "t"
      column(
        url,
        "SELECT must_change_password FROM qodstate_user WHERE username = 'root'"
      ) shouldBe "t"
    finally store.close()
  }

  it should "refuse an unknown name and a tenant user with exit 1" in withFreshDb { url =>
    val store = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
    try
      store.upsertUser(Some("acme"), "alice", "pw", "user")
      AdminResetCli.reset(
        store,
        "nobody",
        "x",
        mustChange = false,
        reenable = true,
        quiet
      ) shouldBe 1
      AdminResetCli.reset(
        store,
        "alice",
        "x",
        mustChange = false,
        reenable = true,
        quiet
      ) shouldBe 1
    finally store.close()
  }

  it should "re-enable a disabled superuser named explicitly, and say so" in withFreshDb { url =>
    val store = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
    try
      store.upsertUser(None, "root", "old-pw", "admin")
      disable(url, "root")
      val out = new ByteArrayOutputStream()
      AdminResetCli.reset(
        store,
        "root",
        "new-pw",
        mustChange = false,
        reenable = true,
        new PrintStream(out)
      ) shouldBe 0
      column(
        url,
        "SELECT enabled FROM qodstate_user WHERE tenant IS NULL AND username = 'root'"
      ) shouldBe "t"
      out.toString should include(s"superuser 'root' $ReEnabled")
    finally store.close()
  }

  it should "reset a disabled superuser's password but leave it disabled without reenable" in
    withFreshDb { url =>
      val store = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try
        store.upsertUser(None, "root", "old-pw", "admin")
        disable(url, "root")
        val out = new ByteArrayOutputStream()
        AdminResetCli.reset(
          store,
          "root",
          "new-pw",
          mustChange = false,
          reenable = false,
          new PrintStream(out)
        ) shouldBe 0
        column(
          url,
          "SELECT enabled FROM qodstate_user WHERE tenant IS NULL AND username = 'root'"
        ) shouldBe "f"
        out.toString should include(
          s"superuser 'root' $LeftDisabled; rerun qod admin reset-password --username root " +
            "to re-enable it"
        )
      finally store.close()
    }

  it should "leave a disabled tenant user of the same name disabled and untouched" in withFreshDb {
    url =>
      val store = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try
        store.upsertUser(None, "root", "old-pw", "admin")
        store.upsertUser(Some("acme"), "root", "tenant-pw", "user")
        val c = DriverManager.getConnection(url, TestPostgres.pgUser, TestPostgres.pgPass)
        try
          c.createStatement()
            .executeUpdate("UPDATE qodstate_user SET enabled = FALSE WHERE username = 'root'")
          ()
        finally c.close()
        AdminResetCli.reset(
          store,
          "root",
          "new-pw",
          mustChange = false,
          reenable = true,
          quiet
        ) shouldBe 0
        column(
          url,
          "SELECT enabled FROM qodstate_user WHERE tenant IS NULL AND username = 'root'"
        ) shouldBe "t"
        column(
          url,
          "SELECT enabled FROM qodstate_user WHERE tenant IS NOT NULL AND username = 'root'"
        ) shouldBe "f"
        val tenantHash = column(
          url,
          "SELECT password_hash FROM qodstate_user WHERE tenant IS NOT NULL AND username = 'root'"
        )
        BCrypt.verifyer().verify("tenant-pw".toCharArray, tenantHash).verified shouldBe true
      finally store.close()
  }

  "run" should "refuse a missing username and an empty stdin password" in {
    val err                                    = new ByteArrayOutputStream()
    def run(args: List[String], stdin: String) =
      AdminResetCli.run(
        args,
        new ByteArrayInputStream(stdin.getBytes("UTF-8")),
        new PrintStream(new ByteArrayOutputStream()),
        new PrintStream(err)
      )
    run(Nil, "pw\n") shouldBe 1
    err.toString should include(
      "usage: admin reset-password <username>... [--must-change] [--reenable]"
    )
    run(List("root"), "\n") shouldBe 1
    err.toString should include("empty password")
  }

  "postmasterPort" should "read the port from line 4 of postmaster.pid" in {
    val dir = Files.createTempDirectory("pgdata")
    EmbeddedControlPlane.postmasterPort(dir) shouldBe None
    Files.writeString(dir.resolve("postmaster.pid"), "123\n/data\n1700000000\n25432\n/tmp\n")
    EmbeddedControlPlane.postmasterPort(dir) shouldBe Some(25432)
  }

  "attachRunning" should "refuse a data dir with no pgdata" in {
    val root = Files.createTempDirectory("qodpg")
    val cfg  = ai.starlake.quack.EmbeddedPostgresConfig(enabled = true, dataDir = root.toString)
    EmbeddedControlPlane.attachRunning(cfg).left.toOption.get should include(
      "nothing to reset; run qod serve first"
    )
  }

  it should "refuse, never start, a pgdata whose postmaster is not running" in {
    val root   = Files.createTempDirectory("qodpg")
    val pgData = Files.createDirectories(root.resolve("pgdata"))
    val cfg    = ai.starlake.quack.EmbeddedPostgresConfig(enabled = true, dataDir = root.toString)
    // No postmaster.pid at all, then a stale one naming a pid that is not alive.
    for pidFile <- Seq(None, Some("2147483646\n/data\n1700000000\n25432\n/tmp\n")) do
      pidFile.foreach(Files.writeString(pgData.resolve("postmaster.pid"), _))
      val msg = EmbeddedControlPlane.attachRunning(cfg).left.toOption.get
      msg should include(s"the embedded control plane at $pgData is not running")
      msg should include("qod admin never starts Postgres")
    Files.exists(pgData.resolve("PG_VERSION")) shouldBe false // nothing was initialized
  }

  it should "attach to a live postmaster on the port its pid file names" in {
    val root   = Files.createTempDirectory("qodpg")
    val pgData = Files.createDirectories(root.resolve("pgdata"))
    val cfg    = ai.starlake.quack.EmbeddedPostgresConfig(enabled = true, dataDir = root.toString)
    // This JVM's own pid stands in for a live postmaster.
    Files.writeString(
      pgData.resolve("postmaster.pid"),
      s"${ProcessHandle.current().pid()}\n/data\n1700000000\n25499\n/tmp\n"
    )
    val h = EmbeddedControlPlane.attachRunning(cfg).toOption.get
    (h.host, h.port) shouldBe ("localhost", 25499)
  }

  "runWith" should "exit 2 when config loading or the embedded attach throws" in {
    val err = new ByteArrayOutputStream()
    def go(
        load: () => ManagerConfig,
        attach: ai.starlake.quack.EmbeddedPostgresConfig => Either[
          String,
          EmbeddedControlPlane.Handle
        ]
    ) =
      AdminResetCli.runWith(
        List("root"),
        new ByteArrayInputStream("pw\n".getBytes("UTF-8")),
        new PrintStream(new ByteArrayOutputStream()),
        new PrintStream(err),
        load,
        attach
      )
    go(() => throw new IllegalStateException("bad conf"), _ => Left("unused")) shouldBe 2
    err.toString should include("error: control plane unreachable: bad conf")
    val cfg = pureconfig.ConfigSource.default
      .at("quack-on-demand")
      .loadOrThrow[ManagerConfig]
    val embedded = cfg.copy(embeddedPostgres = cfg.embeddedPostgres.copy(enabled = true))
    go(() => embedded, _ => throw new IllegalStateException("attach boom")) shouldBe 2
    err.toString should include("error: control plane unreachable: attach boom")
    go(() => embedded, _ => Left("nothing to reset; run qod serve first")) shouldBe 1
  }

  /** runWith against a fresh control-plane DB through the embedded seam: the handle points at the
    * test Postgres.
    */
  private def runOnDb(url: String, names: List[String]): (Int, String, String) =
    val dbName = url.substring(url.lastIndexOf('/') + 1)
    val base   = pureconfig.ConfigSource.default
      .at("quack-on-demand")
      .loadOrThrow[ManagerConfig]
    val cfg = base.copy(
      embeddedPostgres = base.embeddedPostgres.copy(enabled = true),
      defaultMetastore = base.defaultMetastore.copy(dbName = dbName)
    )
    val handle = EmbeddedControlPlane.Handle(
      TestPostgres.pgHost,
      TestPostgres.pgPort,
      TestPostgres.pgUser,
      TestPostgres.pgPass
    )
    val out  = new ByteArrayOutputStream()
    val err  = new ByteArrayOutputStream()
    val code = AdminResetCli.runWith(
      names,
      new ByteArrayInputStream("new-pw\n".getBytes("UTF-8")),
      new PrintStream(out),
      new PrintStream(err),
      () => cfg,
      _ => Right(handle)
    )
    (code, out.toString, err.toString)

  private def verifies(url: String, username: String, pw: String): Boolean =
    val hash = column(
      url,
      s"SELECT password_hash FROM qodstate_user WHERE tenant IS NULL AND username = '$username'"
    )
    BCrypt.verifyer().verify(pw.toCharArray, hash).verified

  it should "reset every named superuser that exists and exit 0" in withFreshDb { url =>
    val store = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
    try
      store.upsertUser(None, "admin@localhost.local", "old-pw", "admin")
      store.upsertUser(None, "admin", "old-pw", "admin")
    finally store.close()
    val (code, out, _) = runOnDb(url, List("admin@localhost.local", "admin"))
    code shouldBe 0
    out should include("password reset for superuser 'admin@localhost.local'")
    out should include("password reset for superuser 'admin'")
    verifies(url, "admin@localhost.local", "new-pw") shouldBe true
    verifies(url, "admin", "new-pw") shouldBe true
  }

  it should "exit 0 when one of two names is missing, updating the existing one" in withFreshDb {
    url =>
      val store = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try store.upsertUser(None, "root", "old-pw", "admin")
      finally store.close()
      val (code, out, _) = runOnDb(url, List("root", "ghost"))
      code shouldBe 0
      out should include("password reset for superuser 'root'")
      out should not include "ghost"
      verifies(url, "root", "new-pw") shouldBe true
  }

  it should "exit 1 with the first-boot hint when no name is a superuser" in withFreshDb { url =>
    val (code, _, err) = runOnDb(url, List("ghost", "phantom"))
    code shouldBe 1
    err should include("no superuser named 'ghost', 'phantom'")
    err should include(
      "if the first boot never created it, export QOD_ADMIN_PASSWORD and restart the manager once"
    )
  }

  it should "re-enable a disabled superuser with --reenable, as qod admin reset-password " +
    "--username runs it" in withFreshDb { url =>
      val store = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try store.upsertUser(None, "root", "old-pw", "admin")
      finally store.close()
      disable(url, "root")
      val (code, out, _) = runOnDb(url, List("root", "--reenable"))
      code shouldBe 0
      out should include("password reset for superuser 'root'")
      out should include(s"superuser 'root' $ReEnabled")
      column(
        url,
        "SELECT enabled FROM qodstate_user WHERE tenant IS NULL AND username = 'root'"
      ) shouldBe "t"
      verifies(url, "root", "new-pw") shouldBe true
    }

  it should "leave a disabled configured admin disabled without --reenable, still resetting " +
    "its password" in withFreshDb { url =>
      val store = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try
        store.upsertUser(None, "admin@localhost.local", "old-pw", "admin")
        store.upsertUser(None, "admin", "old-pw", "admin")
      finally store.close()
      disable(url, "admin")
      val (code, out, _) = runOnDb(url, List("admin@localhost.local", "admin"))
      code shouldBe 0
      out should include("password reset for superuser 'admin@localhost.local'")
      out should include("password reset for superuser 'admin'")
      out should include(
        s"superuser 'admin' $LeftDisabled; rerun qod admin reset-password --username admin " +
          "to re-enable it"
      )
      // An account that was already enabled gets only the password line.
      out should not include "superuser 'admin@localhost.local' is disabled"
      out should not include ReEnabled
      column(
        url,
        "SELECT enabled FROM qodstate_user WHERE tenant IS NULL AND username = 'admin'"
      ) shouldBe "f"
      verifies(url, "admin", "new-pw") shouldBe true
      verifies(url, "admin@localhost.local", "new-pw") shouldBe true
    }
