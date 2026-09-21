package ai.starlake.quack.ondemand.runtime

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.sys.process.*

class SpawnScriptEncryptionSpec extends AnyFlatSpec with Matchers:

  private val script = "scripts/spawn-quack-node.sh"

  private def initSql(env: (String, String)*): String =
    val base = Seq(
      "QOD_SPAWN_DRY_RUN" -> "1",
      "PORT"              -> "21999",
      "TOKEN"             -> "t",
      "schemaName"        -> "main",
      "catalogAlias"      -> "acme_lake",
      "dbName"            -> "acme_lake"
    )
    Process(Seq("bash", script), None, (base ++ env)*).!!

  "the ducklake arm" should "omit ENCRYPTED when the flag is absent" in {
    val sql = initSql(
      "kind"       -> "ducklake",
      "dataPath"   -> "/var/lake",
      "pgHost"     -> "h",
      "pgPort"     -> "5432",
      "pgUser"     -> "u",
      "pgPassword" -> "p"
    )
    sql should include("DATA_PATH '/var/lake'")
    sql should not include "ENCRYPTED"
  }

  it should "carry ENCRYPTED and load httpfs when the flag is set" in {
    val sql = initSql(
      "kind"       -> "ducklake",
      "encrypted"  -> "true",
      "dataPath"   -> "/var/lake",
      "pgHost"     -> "h",
      "pgPort"     -> "5432",
      "pgUser"     -> "u",
      "pgPassword" -> "p"
    )
    sql should include("ENCRYPTED")
    sql should include("INSTALL httpfs; LOAD httpfs;")
    sql.indexOf("INSTALL httpfs") should be < sql.indexOf("ENCRYPTED")
  }

  "the duckdb-file arm" should "attach plainly when the flag is absent" in {
    val sql = initSql("kind" -> "duckdb-file", "dataPath" -> "/var/sales.duckdb")
    sql should include("ATTACH '/var/sales.duckdb'")
    sql should not include "ENCRYPTION_KEY"
  }

  it should "carry ENCRYPTION_KEY and load httpfs when the flag is set" in {
    val sql = initSql(
      "kind"          -> "duckdb-file",
      "encrypted"     -> "true",
      "encryptionKey" -> "c2VjcmV0",
      "dataPath"      -> "/var/sales.duckdb"
    )
    sql should include("ENCRYPTION_KEY 'c2VjcmV0'")
    sql should include("INSTALL httpfs; LOAD httpfs;")
    sql.indexOf("INSTALL httpfs") should be < sql.indexOf("ENCRYPTION_KEY")
  }
