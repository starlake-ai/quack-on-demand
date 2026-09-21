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
