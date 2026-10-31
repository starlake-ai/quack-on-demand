package ai.starlake.quack.boot

import ai.starlake.quack.{EmbeddedPostgresConfig, ManagerConfig}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import java.nio.file.Files
import java.sql.DriverManager

class EmbeddedControlPlaneSpec extends AnyFlatSpec with Matchers:
  import ai.starlake.quack.Main.given

  /** A free port picked once per test, so two specs in the same fork cannot collide on the
    * production default of 25432.
    */
  private def freePort(): Int =
    val s = new java.net.ServerSocket(0)
    try s.getLocalPort
    finally s.close()

  "EmbeddedControlPlane" should "keep its data across a stop and restart" in:
    val dir  = Files.createTempDirectory("qod-embedded-spec")
    val port = freePort()
    val cfg  = EmbeddedPostgresConfig(enabled = true, port = port, dataDir = dir.toString)

    val first = EmbeddedControlPlane.start(cfg)
    try
      first.ensureDatabase("qod")
      val conn = DriverManager.getConnection(
        s"jdbc:postgresql://${first.host}:${first.port}/qod",
        first.user,
        first.password
      )
      try
        val st = conn.createStatement()
        try
          st.executeUpdate("CREATE TABLE persisted (id int)")
          st.executeUpdate("INSERT INTO persisted VALUES (42)")
        finally st.close()
      finally conn.close()
    finally first.stop()

    val second = EmbeddedControlPlane.start(cfg)
    try
      val conn = DriverManager.getConnection(
        s"jdbc:postgresql://${second.host}:${second.port}/qod",
        second.user,
        second.password
      )
      try
        val rs = conn.createStatement().executeQuery("SELECT id FROM persisted")
        rs.next() shouldBe true
        rs.getInt("id") shouldBe 42
      finally conn.close()
    finally second.stop()

  it should "be idempotent in ensureDatabase" in:
    val dir = Files.createTempDirectory("qod-embedded-idem")
    val cp  = EmbeddedControlPlane.start(
      EmbeddedPostgresConfig(enabled = true, port = freePort(), dataDir = dir.toString)
    )
    try
      cp.ensureDatabase("qod")
      noException should be thrownBy cp.ensureDatabase("qod")
    finally cp.stop()

  it should "project only the Postgres coordinate fields onto ManagerConfig" in:
    val dir = Files.createTempDirectory("qod-embedded-coords")
    val cp  = EmbeddedControlPlane.start(
      EmbeddedPostgresConfig(enabled = true, port = freePort(), dataDir = dir.toString)
    )
    try
      val base    = ConfigSource.default.at("quack-on-demand").loadOrThrow[ManagerConfig]
      val applied = EmbeddedControlPlane.applyCoordinates(base, cp)
      applied.defaultMetastore.pgHost shouldBe cp.host
      applied.defaultMetastore.pgPort shouldBe cp.port.toString
      applied.defaultMetastore.pgUser shouldBe cp.user
      applied.defaultMetastore.pgPassword shouldBe cp.password
      applied.defaultMetastore.dbName shouldBe base.defaultMetastore.dbName
      // Everything else is untouched: this is the demo-posture guardrail in assertion form.
      applied.defaultMetastore.dataPath shouldBe base.defaultMetastore.dataPath
      applied.defaultMetastore.schemaName shouldBe base.defaultMetastore.schemaName
      applied.runtimeType shouldBe base.runtimeType
      applied.admin shouldBe base.admin
      applied.auth shouldBe base.auth
    finally cp.stop()

  it should "default the data dir to the platform user-data dir when unconfigured" in:
    val resolved = EmbeddedControlPlane.resolveDataDir("")
    resolved.isAbsolute shouldBe true
    resolved.getFileName.toString shouldBe "pg"
    resolved.getParent.getFileName.toString shouldBe "qod"

  it should "honor an explicit data dir" in:
    EmbeddedControlPlane.resolveDataDir("/tmp/custom-qod").toString shouldBe "/tmp/custom-qod"

  it should "recover a data directory left behind with a stale postmaster.pid" in:
    val dir  = Files.createTempDirectory("qod-embedded-stale-pid")
    val port = freePort()
    val cfg  = EmbeddedPostgresConfig(enabled = true, port = port, dataDir = dir.toString)

    val first = EmbeddedControlPlane.start(cfg)
    try
      first.ensureDatabase("qod")
      val conn = DriverManager.getConnection(
        s"jdbc:postgresql://${first.host}:${first.port}/qod",
        first.user,
        first.password
      )
      try
        val st = conn.createStatement()
        try
          st.executeUpdate("CREATE TABLE persisted2 (id int)")
          st.executeUpdate("INSERT INTO persisted2 VALUES (43)")
        finally st.close()
      finally conn.close()
    finally first.stop()

    val p = new ProcessBuilder(java.util.List.of("true")).start()
    p.waitFor()
    val deadPid = p.pid()
    Files.writeString(dir.resolve("pgdata").resolve("postmaster.pid"), s"$deadPid\n")

    val second = EmbeddedControlPlane.start(cfg)
    try
      val conn = DriverManager.getConnection(
        s"jdbc:postgresql://${second.host}:${second.port}/qod",
        second.user,
        second.password
      )
      try
        val rs = conn.createStatement().executeQuery("SELECT id FROM persisted2")
        rs.next() shouldBe true
        rs.getInt("id") shouldBe 43
      finally conn.close()
    finally second.stop()

  it should "refuse to start while another instance owns the data directory" in:
    val dir  = Files.createTempDirectory("qod-embedded-live-pid")
    val cfg1 = EmbeddedPostgresConfig(enabled = true, port = freePort(), dataDir = dir.toString)

    val first = EmbeddedControlPlane.start(cfg1)
    try
      val cfg2 = EmbeddedPostgresConfig(enabled = true, port = freePort(), dataDir = dir.toString)
      val ex   = the[RuntimeException] thrownBy EmbeddedControlPlane.start(cfg2)
      ex.getMessage should include("already owns")
      ex.getMessage should include(dir.resolve("pgdata").toString)
    finally first.stop()
