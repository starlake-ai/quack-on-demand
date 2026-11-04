package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.NodeFilesConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}
import scala.sys.process.*

/** What both spawn scripts add to a node's init SQL for `QOD_NODE_ALLOW_LOCAL_FILES`, read from the
  * scripts' own dry runs: with exactly `false`, [[NodeFilesConfig.InitStatements]] as the last
  * statements, after every ATTACH and the federation SQL; with anything else, nothing. The wheel's
  * copies are byte-equal to these (cli/tests/test_demo.py). The PowerShell cases are cancelled
  * without PowerShell.
  */
class NodeFilesScriptsSpec extends AnyFlatSpec with Matchers with SpawnScriptTempDir:

  private val Nonce = "ffeeddccbbaa99887766554433221100"

  private val sh  = Paths.get("scripts/spawn-quack-node.sh").toAbsolutePath.toString
  private val ps1 = Paths.get("scripts/spawn-quack-node.ps1").toAbsolutePath.toString

  private val ducklake = Seq(
    "kind"       -> "ducklake",
    "schemaName" -> "main",
    "dbName"     -> "acme_lake",
    "dataPath"   -> "s3://bucket/lake/",
    "pgHost"     -> "h",
    "pgPort"     -> "5432",
    "pgUser"     -> "u"
  )
  private val memory = Seq("kind" -> "memory", "schemaName" -> "main", "dbName" -> "m")

  private val federation  = "ATTACH 'x' AS federated;"
  private val poolInit    = "SET threads = 3;"
  private val tenantInit  = "SET threads = 4;"
  private val databaseSql = "SELECT 'database-init';"

  private val secrets = Map(
    "extraSetupSql"              -> federation,
    "tenantPoolInitSqlTrusted"   -> poolInit,
    "tenantPoolInitSql"          -> tenantInit,
    "tenantDbInitSqlTrusted"     -> databaseSql,
    NodeSecretFiles.TokenKey     -> "tok-local-files",
    NodeSecretFiles.InitNonceKey -> Nonce,
    NodeSecretFiles.AuthzKey     -> ("ab" * 32)
  )

  private var counter = 0

  /** One dry run of `command`, in directories of its own: the script's init SQL, line by line. */
  private def dryRun(command: Seq[String], env: Seq[(String, String)]): Vector[String] =
    counter += 1
    val root  = Files.createDirectories(tempDir.resolve(s"run-$counter"))
    val tmp   = Files.createDirectories(root.resolve("tmp"))
    val start = Files.createDirectories(root.resolve("start"))
    val dir   = NodeSecretFiles.writePrivateDir(secrets, Some(root))
    val out   = new StringBuilder
    val err   = new StringBuilder
    val full  = Seq(
      "TMPDIR"                     -> tmp.toString,
      "TMP"                        -> tmp.toString,
      "TEMP"                       -> tmp.toString,
      "QOD_SPAWN_DRY_RUN"          -> "1",
      NodeSecretFiles.DirEnv       -> dir.toString,
      NodeSecretFiles.EphemeralEnv -> "1",
      NodeSecretFiles.NodeIdEnv    -> "quack-local-files-1"
    ) ++ env
    val code = Process(command, start.toFile, full*)
      .!(ProcessLogger(l => out.append(l).append('\n'), l => err.append(l).append('\n')))
    withClue(err.toString)(code shouldBe 0)
    out.toString.linesIterator.map(_.stripSuffix("\r")).filter(_.nonEmpty).toVector

  private def bash(env: (String, String)*): Vector[String] =
    dryRun(Seq("bash", sh, "23950"), env)

  private def powershell(env: (String, String)*): Vector[String] =
    assume(PowerShell.command.nonEmpty, "no PowerShell on this host")
    dryRun(PowerShell.command.get ++ Seq("-File", ps1, "23951"), env)

  /** The init SQL with this run's own directory left out. */
  private def stable(lines: Vector[String]): Vector[String] =
    lines.filterNot(_.startsWith("SET temp_directory"))

  private def checkOff(run: Seq[(String, String)] => Vector[String]) =
    val lines        = run(ducklake :+ ("QOD_NODE_ALLOW_LOCAL_FILES" -> "false"))
    val off          = NodeFilesConfig.InitStatements
    val (head, last) = lines.splitAt(lines.size - off.size)
    last shouldBe off.toVector
    // Fenced from what comes before: a text above that never closes a quote stops at these lines.
    head.takeRight(2) shouldBe Vector(
      s"-- qod:untrusted-begin $Nonce",
      s"-- qod:untrusted-end $Nonce"
    )
    // Before the first of them: every catalog, every initSql, the federation SQL.
    for earlier <- List(
        "LOAD ducklake;",
        "LOAD postgres;",
        "DETACH qod_init_pg;",
        "CREATE SCHEMA IF NOT EXISTS \"main\";",
        databaseSql,
        poolInit,
        tenantInit,
        federation
      )
    do withClue(earlier)(head should contain(earlier))
    head.exists(_.contains("ATTACH 'ducklake:postgres:")) shouldBe true
    head.exists(_.contains("enable_external_access")) shouldBe false
    head.exists(_.contains("allowed_directories")) shouldBe false

  private def checkOthers(run: Seq[(String, String)] => Vector[String]) =
    val unset = stable(run(ducklake))
    for value <- List("true", "FALSE", "False", " false", "false ", "0", "no", "off", "") do
      withClue(s"QOD_NODE_ALLOW_LOCAL_FILES='$value': ") {
        stable(run(ducklake :+ ("QOD_NODE_ALLOW_LOCAL_FILES" -> value))) shouldBe unset
      }
    unset.exists(_.contains("enable_external_access")) shouldBe false
    unset.exists(_.contains("allowed_paths")) shouldBe false

  private def checkKinds(run: Seq[(String, String)] => Vector[String]) =
    for env <- List(
        memory,
        ducklake,
        ducklake.map {
          case ("dataPath", _) => "dataPath" -> "az://container/lake/"
          case kv              => kv
        }
      )
    do
      val lines = run(env :+ ("QOD_NODE_ALLOW_LOCAL_FILES" -> "false"))
      lines.takeRight(NodeFilesConfig.InitStatements.size) shouldBe
        NodeFilesConfig.InitStatements.toVector
    // Azure's arm loads azure ahead of them: its schemes are allowed only where it is loaded.
    val onAzure = run(
      ducklake.map {
        case ("dataPath", _) => "dataPath" -> "az://container/lake/"
        case kv              => kv
      } :+ ("QOD_NODE_ALLOW_LOCAL_FILES" -> "false")
    )
    onAzure.dropRight(NodeFilesConfig.InitStatements.size) should contain("LOAD azure;")

  "the bash script" should "end the init SQL with the statements, after every ATTACH and the federation" in
    checkOff(env => bash(env*))

  it should "add nothing on any other value" in
    checkOthers(env => bash(env*))

  it should "add them on every kind" in
    checkKinds(env => bash(env*))

  "the PowerShell script" should "end the init SQL with the statements, after every ATTACH and the federation" in
    checkOff(env => powershell(env*))

  it should "add nothing on any other value" in
    checkOthers(env => powershell(env*))

  it should "add them on every kind" in
    checkKinds(env => powershell(env*))
