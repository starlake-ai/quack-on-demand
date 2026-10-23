package ai.starlake.quack.ondemand.runtime

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The catalog Postgres password never rides an ATTACH string: DuckDB prints the attach path
  * verbatim in `duckdb_databases().path` (and in connection errors), which any SQL user of the node
  * can read. The node bootstrap carries it in a temporary DuckDB secret instead, created just
  * before the two catalog ATTACHes, named by them (`SECRET` / DuckLake `METADATA_PARAMETERS`), and
  * dropped right after, before any federation SQL or the quack server, so no session can reuse it.
  */
private trait SpawnScriptCatalogSecretCases extends Matchers with SpawnScriptTempDir:
  this: AnyFlatSpec =>

  protected def command: Seq[String]
  protected def assumeHost(): Unit

  protected val password = "Catalog-Pw-7731"

  protected def ducklake(extra: (String, String)*): Vector[String] =
    assumeHost()
    val base = Seq(
      "QOD_SPAWN_DRY_RUN" -> "1",
      "schemaName"        -> "main",
      "catalogAlias"      -> "acme_lake",
      "dbName"            -> "acme_lake",
      "kind"              -> "ducklake",
      "dataPath"          -> tempDir.resolve("lake").toString,
      "pgHost"            -> "h",
      "pgPort"            -> "5432",
      "pgUser"            -> "u",
      "pgPassword"        -> password,
      "extraSetupSql"     -> "SELECT 'federation';"
    )
    runSpawn(command, base ++ extra).linesIterator.toVector

  protected def cases(): Unit =
    it should "carry the password only in the temporary secret" in {
      ducklake().filter(_.contains(password)) shouldBe Vector(
        s"CREATE OR REPLACE TEMPORARY SECRET qod_catalog_pg (TYPE postgres, PASSWORD '$password');"
      )
    }

    it should "keep every ATTACH string free of a password" in {
      val attaches = ducklake().filter(_.startsWith("ATTACH"))
      attaches should have size 2
      attaches.foreach(a => withClue(a)(a.toLowerCase should not include "password"))
    }

    it should "name the secret on both catalog ATTACHes" in {
      val lines = ducklake()
      lines.find(_.contains("AS qod_init_pg")).get should include(
        "(TYPE postgres, SECRET qod_catalog_pg);"
      )
      val lake = lines.indexWhere(_.startsWith("ATTACH 'ducklake:postgres:"))
      lines(lake + 1) should include("METADATA_PARAMETERS MAP {'SECRET': 'qod_catalog_pg'}")
    }

    it should "drop the secret after the catalog ATTACH and before federation SQL" in {
      val lines  = ducklake()
      val create =
        lines.indexWhere(_.startsWith("CREATE OR REPLACE TEMPORARY SECRET qod_catalog_pg"))
      val first = lines.indexWhere(_.startsWith("ATTACH"))
      val lake  = lines.indexWhere(_.startsWith("ATTACH 'ducklake:postgres:"))
      val drop  = lines.indexOf("DROP SECRET IF EXISTS qod_catalog_pg;")
      create should be < first
      lake should be < drop
      drop should be < lines.indexOf("SELECT 'federation';")
      // qod-node serves only after the whole init SQL has run, so the secret is gone by then.
      lines.exists(_.contains("quack_serve(")) shouldBe false
    }

    it should "double an apostrophe in the password" in {
      ducklake("pgPassword" -> "a'b").filter(_.contains("SECRET qod_catalog_pg (")) shouldBe
        Vector(
          "CREATE OR REPLACE TEMPORARY SECRET qod_catalog_pg (TYPE postgres, PASSWORD 'a''b');"
        )
    }

class SpawnScriptCatalogSecretSpec extends AnyFlatSpec with SpawnScriptCatalogSecretCases:
  protected val command            = Seq("bash", "scripts/spawn-quack-node.sh", "21999")
  protected def assumeHost(): Unit = ()
  behavior of "the ducklake arm (bash)"
  cases()

// PowerShell twin: cancelled off Windows, like SpawnScriptEncryptionWindowsSpec.
class SpawnScriptCatalogSecretWindowsSpec extends AnyFlatSpec with SpawnScriptCatalogSecretCases:
  protected val command =
    Seq("powershell.exe", "-File", "scripts/spawn-quack-node.ps1", "21999")
  protected def assumeHost(): Unit =
    assume(
      System.getProperty("os.name").toLowerCase.contains("win"),
      "spawn-quack-node.ps1 runs on Windows only"
    )
  behavior of "the ducklake arm (PowerShell)"
  cases()
