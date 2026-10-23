package ai.starlake.quack.ondemand.runtime

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.sys.process.*

class NodeSecretFilesSpec extends AnyFlatSpec with Matchers with SpawnScriptTempDir:

  private val isWindows = System.getProperty("os.name").toLowerCase.contains("win")

  private def keyList(file: String, start: String, end: String): Seq[String] =
    val body = Files.readString(Paths.get(file))
    val from = body.indexOf(start)
    from should be >= 0
    val slice = body.substring(from + start.length, body.indexOf(end, from + start.length))
    "[A-Za-z_][A-Za-z0-9_]*".r.findAllIn(slice.replace("'", " ").replace("\"", " ")).toSeq

  "the secret key list" should "be the same in the Scala launcher, both spawn scripts and fleet join" in {
    keyList("scripts/spawn-quack-node.sh", "SECRET_KEYS=(", ")") shouldBe NodeSecretFiles.Keys
    keyList("scripts/spawn-quack-node.ps1", "$SecretKeys = @(", ")") shouldBe NodeSecretFiles.Keys
    keyList("cli/src/qod_cli/fleet_join.py", "_SECRET_KEYS = (", ")") shouldBe NodeSecretFiles.Keys
  }

  "collect" should "gather every sensitive value under its canonical name" in {
    val values = NodeSecretFiles.collect(
      metastore = Map(
        "pgHost"                   -> "h",
        "PgPassword"               -> "odd",
        "pgPassword"               -> "exact",
        "encryptionKey"            -> "k",
        "QOD_S3_SECRET_ACCESS_KEY" -> "from-metastore"
      ),
      extraSetupSql = "fed",
      objectStoreSql = "",
      token = "tok",
      inheritedEnv = Map(
        "QOD_S3_SECRET_ACCESS_KEY" -> "from-manager",
        "QOD_S3_ACCESS_KEY_ID"     -> "id",
        "QOD_API_KEY"              -> "never"
      )
    )
    values shouldBe Map(
      "pgPassword"               -> "exact",
      "encryptionKey"            -> "k",
      "extraSetupSql"            -> "fed",
      "QOD_NODE_TOKEN"           -> "tok",
      "QOD_S3_SECRET_ACCESS_KEY" -> "from-metastore",
      "QOD_S3_ACCESS_KEY_ID"     -> "id"
    )
  }

  "isInheritedSecret" should "drop the manager's own credentials but keep cloud SDK and plain settings" in {
    Seq(
      "QOD_API_KEY",
      "QOD_SESSION_JWT_SECRET",
      "QOD_ADMIN_PASSWORD",
      "QOD_SMTP_PASSWORD",
      "QOD_FLEET_JOIN_TOKEN",
      "QOD_MANAGED_STORE_SECRET_ACCESS_KEY",
      "PGPASSWORD",
      "STRIPE_SECRET_KEY",
      "GITHUB_TOKEN"
    ).foreach(n => withClue(n)(NodeSecretFiles.isInheritedSecret(n) shouldBe true))
    Seq("PATH", "HOME", "QOD_S3_ENDPOINT", "AWS_SECRET_ACCESS_KEY", "QOD_NODE_BIND", "kind")
      .foreach(n => withClue(n)(NodeSecretFiles.isInheritedSecret(n) shouldBe false))
  }

  "writePrivateDir" should "create an owner-only directory of owner-only files and refuse a foreign key" in {
    val dir = NodeSecretFiles.writePrivateDir(Map("pgPassword" -> "p\nwith newline"), Some(tempDir))
    Files.readString(dir.resolve("pgPassword")) shouldBe "p\nwith newline"
    if !isWindows then
      PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)) shouldBe "rwx------"
      PosixFilePermissions.toString(
        Files.getPosixFilePermissions(dir.resolve("pgPassword"))
      ) shouldBe
        "rw-------"
    an[IllegalArgumentException] should be thrownBy
      NodeSecretFiles.writePrivateDir(Map("PATH" -> "/x"), Some(tempDir))
    // The failed attempt left nothing behind.
    Files.list(tempDir).count() shouldBe 1
  }

  "sweepStale" should "remove an old secrets directory and keep a fresh one" in {
    val old   = NodeSecretFiles.writePrivateDir(Map("pgPassword" -> "p"), Some(tempDir))
    val fresh = NodeSecretFiles.writePrivateDir(Map("pgPassword" -> "p"), Some(tempDir))
    Files.setLastModifiedTime(
      old,
      java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(3600))
    )
    NodeSecretFiles.sweepStale(tempDir, olderThanSec = 600)
    Files.exists(old) shouldBe false
    Files.exists(fresh) shouldBe true
  }

  /** Runs the real bash script up to the node launch, with a stand-in qod-node that dumps its own
    * environment and arguments: the values must reach the init SQL path but never the node's
    * environment, and only qod-node's own key (the token) is left for it to read.
    */
  "spawn-quack-node.sh" should "read the secrets, leave qod-node only its own key, export none" in {
    assume(!isWindows, "bash script")
    val envOut  = tempDir.resolve("node.env")
    val argsOut = tempDir.resolve("node.args")
    val dirOut  = tempDir.resolve("node.secrets")
    val fake    = tempDir.resolve("fake-qod-node")
    // Records its environment, its arguments and what its secrets directory holds, then exits.
    Files.writeString(
      fake,
      s"""#!/usr/bin/env bash
         |env > '$envOut'
         |printf '%s\\n' "$$@" > '$argsOut'
         |while [ $$# -gt 0 ]; do
         |  if [ "$$1" = --secrets-dir ]; then ls -A "$$2" > '$dirOut'; fi
         |  shift
         |done
         |cat > /dev/null
         |sleep 0.5
         |""".stripMargin
    )
    fake.toFile.setExecutable(true)
    val secrets = Map(
      "pgPassword"               -> "hunter2-file",
      "encryptionKey"            -> "key-file",
      "extraSetupSql"            -> "SELECT 'fed-file';",
      "objectStoreSql"           -> "SELECT 'store-file';",
      "QOD_NODE_TOKEN"           -> "token-file",
      "QOD_S3_SECRET_ACCESS_KEY" -> "s3-file"
    )
    val dir    = NodeSecretFiles.writePrivateDir(secrets, Some(tempDir))
    val stderr = new StringBuilder
    val exit   = Process(
      Seq("bash", "scripts/spawn-quack-node.sh", "21999"),
      None,
      "PATH"                       -> sys.env.getOrElse("PATH", "/usr/bin:/bin"),
      "kind"                       -> "memory",
      "dbName"                     -> "lake",
      "schemaName"                 -> "main",
      "QOD_NODE_BIN"               -> fake.toString,
      NodeSecretFiles.DirEnv       -> dir.toString,
      NodeSecretFiles.EphemeralEnv -> "1",
      // A secret still handed over the environment is ignored, not exported.
      "pgPassword" -> "hunter2-env"
    ).!(ProcessLogger(_ => (), l => stderr.append(l).append('\n')))
    // The fake node exits on its own while the wrapper is alive, which the wrapper reports.
    withClue(stderr.toString)(exit shouldBe 1)
    stderr.toString should include("while its wrapper was still running")
    // The script deleted every file it read, leaving qod-node only the token; once the node is gone
    // the script removes the directory too.
    Files.readAllLines(dirOut).asScala.toList shouldBe List(NodeSecretFiles.TokenKey)
    Files.exists(dir) shouldBe false
    val args = Files.readAllLines(argsOut).asScala.toList
    args.sliding(2).toList should contain(List("--secrets-dir", dir.toString))
    args should contain("--secrets-ephemeral")
    // These checks never print the environment, which holds whatever this test JVM inherited.
    val nodeEnv = Files.readString(envOut)
    val names   = nodeEnv.linesIterator.map(_.takeWhile(_ != '=')).toSet
    (secrets.values.toSeq :+ "hunter2-env").foreach { v =>
      withClue(s"$v in the node's environment")(nodeEnv.contains(v) shouldBe false)
      withClue(s"$v in the node's arguments")(args.exists(_.contains(v)) shouldBe false)
    }
    withClue("QOD_SECRETS_DIR in the node's environment")(
      names.contains(NodeSecretFiles.DirEnv) shouldBe false
    )
    stderr.toString should include("ignoring pgPassword from the environment")
  }

  it should "refuse to start without a token" in {
    assume(!isWindows, "bash script")
    val dir  = NodeSecretFiles.writePrivateDir(Map("pgPassword" -> "p"), Some(tempDir))
    val exit = Process(
      Seq("bash", "scripts/spawn-quack-node.sh", "21999"),
      None,
      "QOD_SPAWN_DRY_RUN"          -> "1",
      "kind"                       -> "memory",
      NodeSecretFiles.DirEnv       -> dir.toString,
      NodeSecretFiles.EphemeralEnv -> "1"
    ).!(ProcessLogger(_ => (), _ => ()))
    exit should not be 0
    Files.exists(dir) shouldBe false
  }
