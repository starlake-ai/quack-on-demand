package ai.starlake.quack.ondemand.runtime

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SpawnScriptDuckDb20Spec extends AnyFlatSpec with Matchers with SpawnScriptTempDir:

  private def initSql(env: (String, String)*): String =
    val base = Seq(
      "QOD_SPAWN_DRY_RUN" -> "1",
      "schemaName"        -> "main",
      "catalogAlias"      -> "acme_lake",
      "dbName"            -> "acme_lake"
    )
    runSpawn(Seq("bash", "scripts/spawn-quack-node.sh", "21999"), base ++ env)

  private def ducklake(extra: (String, String)*): String =
    initSql(
      (Seq(
        "kind"       -> "ducklake",
        "dataPath"   -> tempDir.resolve("lake").toString,
        "pgHost"     -> "h",
        "pgPort"     -> "5432",
        "pgUser"     -> "u",
        "pgPassword" -> "p"
      ) ++ extra)*
    )

  // Plain HTTP (disable_ssl) is now qod-node's: its ServeStatement, pinned by sql_script_test.
  "the node init SQL" should "leave loading quack and serving to qod-node" in {
    val sql = ducklake()
    sql should not include "quack_serve"
    sql should not include "LOAD quack"
  }

  it should "hold one statement per line (a failing INSTALL must not skip a LOAD)" in
    ducklake("encrypted" -> "true").linesIterator.foreach { line =>
      withClue(line)((line.contains("INSTALL ") && line.contains("LOAD ")) shouldBe false)
    }

  it should "NOT carry AUTOMATIC_MIGRATION on the node ATTACH" in {
    ducklake() should not include "AUTOMATIC_MIGRATION"
  }
