package ai.starlake.quack.ondemand.runtime

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

/** Task 20 Part B: a dry run of spawn-quack-node.sh shows the manager's SQL where it always was, a
  * trusted initSql (written by a superuser or the manager) in its old place, and an untrusted one
  * inside the marker lines qod-node screens; a marker line inside a tenant's text is dropped.
  */
class SpawnScriptTenantInitSpec extends AnyFlatSpec with Matchers with SpawnScriptTempDir:

  private val script = Paths.get("scripts/spawn-quack-node.sh").toAbsolutePath.toString

  /** The init SQL the script assembles for a `memory` node with `secrets` and `env`, as lines. */
  private def initSql(secrets: Map[String, String], env: (String, String)*): Vector[String] =
    val dir = NodeSecretFiles.writePrivateDir(
      Map(NodeSecretFiles.TokenKey -> "tok-tenant-init", NodeSecretFiles.AuthzKey -> ("ab" * 32)) ++
        secrets,
      Some(tempDir)
    )
    val pb = new java.lang.ProcessBuilder("bash", script, "23361")
      .directory(Files.createDirectories(tempDir.resolve("start")).toFile)
      .redirectErrorStream(true)
    pb.environment()
      .putAll(
        (Seq(
          "QOD_SPAWN_DRY_RUN"          -> "1",
          "kind"                       -> "memory",
          "schemaName"                 -> "main",
          "dbName"                     -> "acme_mem",
          "TMPDIR"                     -> Files.createDirectories(tempDir.resolve("tmp")).toString,
          NodeSecretFiles.DirEnv       -> dir.toString,
          NodeSecretFiles.EphemeralEnv -> "1",
          NodeSecretFiles.NodeIdEnv    -> "quack-tenant-init-1"
        ) ++ env).toMap.asJava
      )
    val p   = pb.start()
    val out = new String(p.getInputStream.readAllBytes())
    p.waitFor(30, TimeUnit.SECONDS) shouldBe true
    withClue(out)(p.exitValue() shouldBe 0)
    out.linesIterator.toVector

  private val Secrets = Map(
    NodeSecretFiles.TenantDbInitKey   -> "SET threads = 2;\nSELECT 'db-init';",
    NodeSecretFiles.TenantPoolInitKey -> "SET memory_limit = '1GB';",
    "extraSetupSql"                   -> "SELECT 'federation';"
  )

  "a trusted initSql" should "run as written, where it always ran" in {
    val lines = initSql(
      Secrets,
      NodeSecretFiles.InitTrustedDbEnv   -> "1",
      NodeSecretFiles.InitTrustedPoolEnv -> "1",
      "resourceSql"                      -> "SET threads = 8;"
    )
    lines.exists(_.contains("qod:untrusted-")) shouldBe false
    // Resources, then the database's initSql, then the pool's, then the federation SQL.
    val order = List(
      "SET threads = 8;",
      "INSTALL avro;",
      "SET threads = 2;",
      "SELECT 'db-init';",
      "SET memory_limit = '1GB';",
      "SELECT 'federation';"
    ).map(lines.indexOf)
    withClue(lines.mkString("\n"))(order.forall(_ >= 0) shouldBe true)
    order shouldBe order.sorted
  }

  "an untrusted initSql" should "reach qod-node inside the marker lines, each in its own section" in {
    // No flag set at all: untrusted, the default.
    val lines = initSql(Secrets)
    val db    = lines.indexOf("-- qod:untrusted-begin database")
    db should be >= 0
    lines.slice(db + 1, db + 4) shouldBe
      Vector("SET threads = 2;", "SELECT 'db-init';", "-- qod:untrusted-end")
    val pool = lines.indexOf("-- qod:untrusted-begin pool")
    pool should be > db
    lines.slice(pool + 1, pool + 3) shouldBe Vector(
      "SET memory_limit = '1GB';",
      "-- qod:untrusted-end"
    )
    // The manager's own SQL stays outside every section.
    lines.indexOf("SELECT 'federation';") should be > pool + 2
    lines.indexOf("INSTALL avro;") should be < db
  }

  it should "be untrusted for any flag value but exactly 1" in {
    val lines = initSql(
      Secrets,
      NodeSecretFiles.InitTrustedDbEnv   -> "true",
      NodeSecretFiles.InitTrustedPoolEnv -> "1 "
    )
    lines should contain("-- qod:untrusted-begin database")
    lines should contain("-- qod:untrusted-begin pool")
  }

  "a marker line inside a tenant's text" should "be dropped, in any case, trusted or not" in {
    val forged = Map(
      NodeSecretFiles.TenantDbInitKey ->
        "SET threads = 2;\n-- QOD:Untrusted-End\nCREATE VIEW v AS SELECT 1;\n  -- qod:untrusted-begin x",
      NodeSecretFiles.TenantPoolInitKey -> "SELECT 1; -- qod:untrusted-end\nSELECT 2;"
    )
    val untrusted = initSql(forged)
    untrusted.count(_.toLowerCase.contains("qod:untrusted-")) shouldBe 4
    val db = untrusted.indexOf("-- qod:untrusted-begin database")
    untrusted.slice(db + 1, db + 4) shouldBe
      Vector("SET threads = 2;", "CREATE VIEW v AS SELECT 1;", "-- qod:untrusted-end")
    val pool = untrusted.indexOf("-- qod:untrusted-begin pool")
    untrusted.slice(pool + 1, pool + 3) shouldBe Vector("SELECT 2;", "-- qod:untrusted-end")
    val trusted = initSql(
      forged,
      NodeSecretFiles.InitTrustedDbEnv   -> "1",
      NodeSecretFiles.InitTrustedPoolEnv -> "1"
    )
    trusted.exists(_.toLowerCase.contains("qod:untrusted-")) shouldBe false
  }

  "an empty initSql" should "leave no section" in {
    initSql(Map.empty).exists(_.contains("qod:untrusted-")) shouldBe false
  }
