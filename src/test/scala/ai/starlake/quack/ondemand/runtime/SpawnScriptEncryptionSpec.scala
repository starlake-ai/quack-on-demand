package ai.starlake.quack.ondemand.runtime

import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.compiletime.uninitialized
import scala.jdk.CollectionConverters.*
import scala.sys.process.*

// Shared per-test temp directory fixture for both suites below: a real, writable directory so
// each script's (non-dry-run-gated) mkdir-equivalent against dataPath exercises the same
// filesystem preparation a production spawn does, rather than a fake path like /var/lake or
// C:\lake that the test runner has no access to - and, on the Windows twin, so New-Item never
// touches (and potentially fails to create, aborting the whole script under
// $ErrorActionPreference = 'Stop') a real drive root.
private trait SpawnScriptTempDir extends BeforeAndAfterEach:
  this: AnyFlatSpec =>

  protected var tempDir: Path = uninitialized

  override def beforeEach(): Unit =
    tempDir = Files.createTempDirectory("spawn-script-encryption-spec")

  override def afterEach(): Unit =
    val all = Files.walk(tempDir).iterator().asScala.toSeq
    // A SIGKILLed node leaves its read-only (0500) secret directory around a marker file.
    all.filter(Files.isDirectory(_)).foreach(_.toFile.setWritable(true, true))
    all.sortBy(_.toString.length).reverse.foreach(Files.deleteIfExists)

  /** Run a spawn script the way the launchers do: every [[NodeSecretFiles.Keys]] entry of `env`
    * (plus a node token) goes to an ephemeral secrets directory, the rest to the environment.
    */
  protected def runSpawn(cmd: Seq[String], env: Seq[(String, String)]): String =
    val (secret, plain) = env.partition { case (k, _) => NodeSecretFiles.isKey(k) }
    val dir             = NodeSecretFiles.writePrivateDir(
      (Seq(NodeSecretFiles.TokenKey -> "t") ++ secret).toMap,
      Some(tempDir)
    )
    Process(
      cmd,
      None,
      (plain ++ Seq(
        NodeSecretFiles.DirEnv       -> dir.toString,
        NodeSecretFiles.EphemeralEnv -> "1"
      ))*
    ).!!

/** The DuckLake ATTACH option naming the catalog password's temporary secret. */
private val CatalogSecretParam = "METADATA_PARAMETERS MAP {'SECRET': 'qod_catalog_pg'}"

class SpawnScriptEncryptionSpec extends AnyFlatSpec with Matchers with SpawnScriptTempDir:

  private val script = "scripts/spawn-quack-node.sh"

  // PORT is passed positionally, exactly as LocalQuackBackend.defaultCommand invokes the script
  // in production; the token and the other secrets come from the secrets directory.
  private def initSql(env: (String, String)*): String =
    val base = Seq(
      "QOD_SPAWN_DRY_RUN" -> "1",
      "schemaName"        -> "main",
      "catalogAlias"      -> "acme_lake",
      "dbName"            -> "acme_lake"
    )
    runSpawn(Seq("bash", script, "21999"), base ++ env)

  "the ducklake arm" should "omit ENCRYPTED when the flag is absent" in {
    val dataPath = tempDir.resolve("lake").toString
    val sql      = initSql(
      "kind"       -> "ducklake",
      "dataPath"   -> dataPath,
      "pgHost"     -> "h",
      "pgPort"     -> "5432",
      "pgUser"     -> "u",
      "pgPassword" -> "p"
    )
    sql should include(s"(DATA_PATH '$dataPath', $CatalogSecretParam);")
    sql should not include "ENCRYPTED"
  }

  it should "carry ENCRYPTED and load httpfs when the flag is set" in {
    val dataPath = tempDir.resolve("lake").toString
    val sql      = initSql(
      "kind"       -> "ducklake",
      "encrypted"  -> "true",
      "dataPath"   -> dataPath,
      "pgHost"     -> "h",
      "pgPort"     -> "5432",
      "pgUser"     -> "u",
      "pgPassword" -> "p"
    )
    sql should include(s"(DATA_PATH '$dataPath', ENCRYPTED, $CatalogSecretParam);")
    sql should include("INSTALL httpfs;")
    sql should include("LOAD httpfs;")
    sql.indexOf("INSTALL httpfs") should be < sql.indexOf("ENCRYPTED")
  }

  "the duckdb-file arm" should "attach plainly when the flag is absent" in {
    val dataPath = tempDir.resolve("sales.duckdb").toString
    val sql      = initSql("kind" -> "duckdb-file", "dataPath" -> dataPath)
    sql should include(s"ATTACH '$dataPath' AS \"acme_lake\";")
    sql should not include "ENCRYPTION_KEY"
  }

  it should "carry ENCRYPTION_KEY and load httpfs when the flag is set" in {
    val dataPath = tempDir.resolve("sales.duckdb").toString
    val sql      = initSql(
      "kind"          -> "duckdb-file",
      "encrypted"     -> "true",
      "encryptionKey" -> "c2VjcmV0",
      "dataPath"      -> dataPath
    )
    sql should include(s"ATTACH '$dataPath' AS \"acme_lake\" (ENCRYPTION_KEY 'c2VjcmV0');")
    sql should include("INSTALL httpfs;")
    sql should include("LOAD httpfs;")
    sql.indexOf("INSTALL httpfs") should be < sql.indexOf("ENCRYPTION_KEY")
  }

// PowerShell twin of the suite above. scripts/spawn-quack-node.ps1 runs on Windows
// (LocalQuackBackend.defaultCommand picks it via `powershell.exe -File` there); these dry runs run it
// through pwsh elsewhere, and report CANCELED on a host with no PowerShell.
class SpawnScriptEncryptionWindowsSpec extends AnyFlatSpec with Matchers with SpawnScriptTempDir:

  // PORT is passed positionally, exactly as LocalQuackBackend.defaultCommand invokes the script in
  // production (no env fallback: an inherited PORT silently binding the wrong port is a worse
  // failure mode than requiring the caller to pass it explicitly); the token and the other
  // secrets come from the secrets directory.
  private def initSql(env: (String, String)*): String =
    val base = Seq(
      "QOD_SPAWN_DRY_RUN" -> "1",
      "schemaName"        -> "main",
      "catalogAlias"      -> "acme_lake",
      "dbName"            -> "acme_lake"
    )
    runSpawn(
      PowerShell.command.get ++ Seq("-File", "scripts/spawn-quack-node.ps1", "21999"),
      base ++ env
    )

  "the PowerShell ducklake arm" should "omit ENCRYPTED when the flag is absent" in {
    assume(PowerShell.command.nonEmpty, "no PowerShell on this host")
    val dataPath = tempDir.resolve("lake").toString
    val sql      = initSql(
      "kind"       -> "ducklake",
      "dataPath"   -> dataPath,
      "pgHost"     -> "h",
      "pgPort"     -> "5432",
      "pgUser"     -> "u",
      "pgPassword" -> "p"
    )
    sql should include(s"(DATA_PATH '$dataPath', $CatalogSecretParam);")
    sql should not include "ENCRYPTED"
  }

  it should "carry ENCRYPTED and load httpfs when the flag is set" in {
    assume(PowerShell.command.nonEmpty, "no PowerShell on this host")
    val dataPath = tempDir.resolve("lake").toString
    val sql      = initSql(
      "kind"       -> "ducklake",
      "encrypted"  -> "true",
      "dataPath"   -> dataPath,
      "pgHost"     -> "h",
      "pgPort"     -> "5432",
      "pgUser"     -> "u",
      "pgPassword" -> "p"
    )
    sql should include(s"(DATA_PATH '$dataPath', ENCRYPTED, $CatalogSecretParam);")
    sql should include("INSTALL httpfs;")
    sql should include("LOAD httpfs;")
    sql.indexOf("INSTALL httpfs") should be < sql.indexOf("ENCRYPTED")
  }

  "the PowerShell duckdb-file arm" should "attach plainly when the flag is absent" in {
    assume(PowerShell.command.nonEmpty, "no PowerShell on this host")
    val dataPath = tempDir.resolve("sales.duckdb").toString
    val sql      = initSql("kind" -> "duckdb-file", "dataPath" -> dataPath)
    sql should include(s"ATTACH '$dataPath' AS \"acme_lake\";")
    sql should not include "ENCRYPTION_KEY"
  }

  it should "carry ENCRYPTION_KEY and load httpfs when the flag is set" in {
    assume(PowerShell.command.nonEmpty, "no PowerShell on this host")
    val dataPath = tempDir.resolve("sales.duckdb").toString
    val sql      = initSql(
      "kind"          -> "duckdb-file",
      "encrypted"     -> "true",
      "encryptionKey" -> "c2VjcmV0",
      "dataPath"      -> dataPath
    )
    sql should include(s"ATTACH '$dataPath' AS \"acme_lake\" (ENCRYPTION_KEY 'c2VjcmV0');")
    sql should include("INSTALL httpfs;")
    sql should include("LOAD httpfs;")
    sql.indexOf("INSTALL httpfs") should be < sql.indexOf("ENCRYPTION_KEY")
  }
