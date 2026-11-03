package ai.starlake.quack.ondemand.runtime

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

/** Task 20 Part B: a dry run of spawn-quack-node.sh shows the manager's SQL where it always was, a
  * trusted initSql (a `...Trusted` file: written by a superuser or the manager) in its old place,
  * and an untrusted one inside the marker lines qod-node screens, each carrying this spawn's nonce;
  * a marker line inside a tenant's text is dropped.
  */
class SpawnScriptTenantInitSpec extends AnyFlatSpec with Matchers with SpawnScriptTempDir:

  private val script = Paths.get("scripts/spawn-quack-node.sh").toAbsolutePath.toString

  private val Nonce = "00112233445566778899aabbccddeeff"
  private val Begin = s"-- qod:untrusted-begin $Nonce"
  private val End   = s"-- qod:untrusted-end $Nonce"

  /** (exit code, output lines) of a dry run for a `memory` node with `secrets` and `env`. */
  private def run(secrets: Map[String, String], env: (String, String)*): (Int, Vector[String]) =
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
    (p.exitValue(), out.linesIterator.toVector)

  /** The init SQL the script assembles, as lines; the dry run must succeed. */
  private def initSql(secrets: Map[String, String], env: (String, String)*): Vector[String] =
    val (rc, lines) = run(secrets + (NodeSecretFiles.InitNonceKey -> Nonce), env*)
    withClue(lines.mkString("\n"))(rc shouldBe 0)
    lines

  private val Untrusted = Map(
    NodeSecretFiles.TenantDbInitKey   -> "SET threads = 2;\nSELECT 'db-init';",
    NodeSecretFiles.TenantPoolInitKey -> "SET memory_limit = '1GB';",
    "extraSetupSql"                   -> "SELECT 'federation';"
  )
  private val Trusted = Map(
    NodeSecretFiles.TenantDbInitTrustedKey   -> "SET threads = 2;\nSELECT 'db-init';",
    NodeSecretFiles.TenantPoolInitTrustedKey -> "SET memory_limit = '1GB';",
    "extraSetupSql"                          -> "SELECT 'federation';"
  )

  "a trusted initSql" should "run as written, where it always ran" in {
    val lines = initSql(Trusted, "resourceSql" -> "SET threads = 8;")
    lines.exists(_.contains("qod:untrusted-")) shouldBe false
    // Resources, then the database's initSql, then the pool's, then the federation SQL.
    val order = List(
      "SET threads = 8;",
      "INSTALL avro;",
      "SET threads = 2;",
      "SELECT 'db-init';",
      "SET memory_limit = '1GB';",
      "SELECT 'federation';"
    ).map(l => lines.indexOf(l))
    withClue(lines.mkString("\n"))(order.forall(_ >= 0) shouldBe true)
    order shouldBe order.sorted
  }

  "an untrusted initSql" should "reach qod-node inside nonce-marked lines, each in its own section" in {
    val lines = initSql(Untrusted)
    val db    = lines.indexOf(s"$Begin database")
    db should be >= 0
    lines.slice(db + 1, db + 4) shouldBe Vector("SET threads = 2;", "SELECT 'db-init';", End)
    val pool = lines.indexOf(s"$Begin pool")
    pool should be > db
    lines.slice(pool + 1, pool + 3) shouldBe Vector("SET memory_limit = '1GB';", End)
    // The manager's own SQL stays outside every section.
    lines.indexOf("SELECT 'federation';") should be > pool + 2
    lines.indexOf("INSTALL avro;") should be < db
  }

  it should "never reach qod-node unmarked: no nonce, or a malformed one, stops the spawn" in {
    run(Untrusted)._1 should not be 0
    run(Untrusted + (NodeSecretFiles.InitNonceKey -> "not-hex"))._1 should not be 0
    // A trusted text needs no nonce.
    run(Trusted)._1 shouldBe 0
  }

  it should "not be trusted by an environment variable of the old contract" in {
    val lines = initSql(Untrusted, "QOD_INIT_TRUSTED_DB" -> "1", "QOD_INIT_TRUSTED_POOL" -> "1")
    lines should contain(s"$Begin database")
    lines should contain(s"$Begin pool")
  }

  "a marker line inside a tenant's text" should "be dropped, in any case, trusted or not" in {
    val text =
      "SET threads = 2;\n-- QOD:Untrusted-End\nCREATE VIEW v AS SELECT 1;\n  -- qod:untrusted-begin x"
    val untrusted = initSql(
      Map(
        NodeSecretFiles.TenantDbInitKey   -> text,
        NodeSecretFiles.TenantPoolInitKey -> "SELECT 1; -- qod:untrusted-end\nSELECT 2;"
      )
    )
    untrusted.count(_.toLowerCase.contains("qod:untrusted-")) shouldBe 4
    val db = untrusted.indexOf(s"$Begin database")
    untrusted.slice(db + 1, db + 4) shouldBe Vector(
      "SET threads = 2;",
      "CREATE VIEW v AS SELECT 1;",
      End
    )
    val pool = untrusted.indexOf(s"$Begin pool")
    untrusted.slice(pool + 1, pool + 3) shouldBe Vector("SELECT 2;", End)
    val trusted = initSql(Map(NodeSecretFiles.TenantDbInitTrustedKey -> text))
    trusted.exists(_.toLowerCase.contains("qod:untrusted-")) shouldBe false
  }

  // Review N1: the stream opens with the nonce the script read, which qod-node compares with its
  // own and refuses the whole stream on a mismatch.
  "the init SQL" should "open with this spawn's nonce line" in {
    initSql(Untrusted).head shouldBe s"-- qod:init-nonce $Nonce"
    initSql(Map.empty).head shouldBe s"-- qod:init-nonce $Nonce"
  }

  "an empty initSql" should "leave no section" in {
    initSql(Map.empty).exists(_.contains("qod:untrusted-")) shouldBe false
  }

  // Task 18: qod-node splits the whole stream on DuckDB's own tokenizer, so a text whose last
  // statement has no `;` ran into the statement after it (`SET threads = 2` then `INSTALL
  // ducklake;` read as one statement that does not parse) and both were lost. The script closes
  // every text it splices in with a `;` on a line of its own, whatever the text ends in, and never
  // edits the text itself.
  "a text with no final semicolon" should "be closed by a semicolon line of its own, as stored" in {
    // Each ends differently: bare, and in a comment with no newline after it.
    val secrets = Map(
      NodeSecretFiles.TenantDbInitTrustedKey   -> "SET threads = 2",
      "objectStoreSql"                         -> "SELECT 'store'",
      NodeSecretFiles.TenantPoolInitTrustedKey -> "SET memory_limit = '1GB' -- one gb",
      "extraSetupSql"                          -> "SELECT 'federation'"
    )
    val resource = "resourceSql" -> "SET threads = 8"
    val lines    = initSql(secrets, resource)
    (secrets.toList :+ resource).foreach { case (key, text) =>
      val at = lines.indexOf(text)
      withClue(s"$key: the text whole on a line of its own, in ${lines.mkString("\n")}")(
        at should be >= 0
      )
      withClue(s"$key: the line after the text")(lines.lift(at + 1) shouldBe Some(";"))
    }
  }

  "a tenant admin's text with no final semicolon" should
    "need no terminator, its end marker closing it" in {
      val lines = initSql(
        Map(
          NodeSecretFiles.TenantDbInitKey   -> "SET threads = 2",
          NodeSecretFiles.TenantPoolInitKey -> "SET memory_limit = '1GB' -- one gb"
        )
      )
      val db = lines.indexOf(s"$Begin database")
      lines.slice(db + 1, db + 3) shouldBe Vector("SET threads = 2", End)
      val pool = lines.indexOf(s"$Begin pool")
      lines.slice(pool + 1, pool + 3) shouldBe Vector("SET memory_limit = '1GB' -- one gb", End)
      lines.contains(";") shouldBe false
    }

  "an empty text" should "add no terminator" in {
    initSql(Map.empty).contains(";") shouldBe false
    // One terminator per text there is, nothing for the texts there are not.
    initSql(
      Map(NodeSecretFiles.TenantDbInitTrustedKey -> "SET threads = 2")
    ).count(_ == ";") shouldBe 1
  }
