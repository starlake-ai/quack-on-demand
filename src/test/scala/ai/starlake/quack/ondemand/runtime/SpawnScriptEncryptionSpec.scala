package ai.starlake.quack.ondemand.runtime

import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.compiletime.uninitialized
import scala.jdk.CollectionConverters.*
import scala.sys.process.*

class SpawnScriptEncryptionSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach:

  private val script = "scripts/spawn-quack-node.sh"

  // A real, writable temp directory per test so the script's (non-dry-run-gated) mkdir -p
  // against dataPath exercises the same filesystem preparation a production spawn does,
  // rather than a fake path like /var/lake that the test runner has no access to.
  private var tempDir: Path = uninitialized

  override def beforeEach(): Unit =
    tempDir = Files.createTempDirectory("spawn-script-encryption-spec")

  override def afterEach(): Unit =
    Files
      .walk(tempDir)
      .iterator()
      .asScala
      .toSeq
      .sortBy(_.toString.length)
      .reverse
      .foreach(Files.deleteIfExists)

  // PORT/TOKEN are passed positionally, exactly as LocalQuackBackend.defaultCommand invokes
  // the script in production - the script's contract requires them as $1/$2, not env vars.
  private def initSql(env: (String, String)*): String =
    val base = Seq(
      "QOD_SPAWN_DRY_RUN" -> "1",
      "schemaName"        -> "main",
      "catalogAlias"      -> "acme_lake",
      "dbName"            -> "acme_lake"
    )
    Process(Seq("bash", script, "21999", "t"), None, (base ++ env)*).!!

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
    sql should include(s"(DATA_PATH '$dataPath');")
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
    sql should include(s"(DATA_PATH '$dataPath', ENCRYPTED);")
    sql should include("INSTALL httpfs; LOAD httpfs;")
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
    sql should include("INSTALL httpfs; LOAD httpfs;")
    sql.indexOf("INSTALL httpfs") should be < sql.indexOf("ENCRYPTION_KEY")
  }

// PowerShell twin of the suite above. scripts/spawn-quack-node.ps1 runs on Windows only
// (LocalQuackBackend.defaultCommand picks it via `powershell.exe -File` there); on any other
// host these tests report CANCELED via `assume`, which is the correct, honest outcome - not a
// pass to celebrate.
class SpawnScriptEncryptionWindowsSpec extends AnyFlatSpec with Matchers:

  private val isWindows = System.getProperty("os.name").toLowerCase.contains("win")

  // PORT/TOKEN are passed positionally, exactly as LocalQuackBackend.defaultCommand invokes the
  // script in production - the script's contract requires them as -Port/-Token positional
  // parameters, not env vars (no env fallback: an inherited PORT silently binding the wrong port
  // is a worse failure mode than requiring the caller to pass it explicitly).
  private def initSql(env: (String, String)*): String =
    val base = Seq(
      "QOD_SPAWN_DRY_RUN" -> "1",
      "schemaName"        -> "main",
      "catalogAlias"      -> "acme_lake",
      "dbName"            -> "acme_lake"
    )
    Process(
      Seq("powershell.exe", "-File", "scripts/spawn-quack-node.ps1", "21999", "t"),
      None,
      (base ++ env)*
    ).!!

  "the PowerShell ducklake arm" should "carry ENCRYPTED when the flag is set" in {
    assume(isWindows, "spawn-quack-node.ps1 runs on Windows only")
    val sql = initSql(
      "kind"       -> "ducklake",
      "encrypted"  -> "true",
      "dataPath"   -> "C:/lake",
      "pgHost"     -> "h",
      "pgPort"     -> "5432",
      "pgUser"     -> "u",
      "pgPassword" -> "p"
    )
    sql should include("ENCRYPTED")
    sql should include("INSTALL httpfs; LOAD httpfs;")
  }

  "the PowerShell duckdb-file arm" should "carry ENCRYPTION_KEY when the flag is set" in {
    assume(isWindows, "spawn-quack-node.ps1 runs on Windows only")
    val sql = initSql(
      "kind"          -> "duckdb-file",
      "encrypted"     -> "true",
      "encryptionKey" -> "c2VjcmV0",
      "dataPath"      -> "C:/sales.duckdb"
    )
    sql should include("ENCRYPTION_KEY 'c2VjcmV0'")
    sql should include("INSTALL httpfs; LOAD httpfs;")
  }
