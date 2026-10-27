package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.model.TenantLocation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path, Paths}
import scala.sys.process.*

/** A database whose data path lies in an object store keeps signing with its own credentials once
  * the tenant authorizes the bucket it lives in. DuckDB picks the s3 secret with the longest
  * matching scope (a tie going to the name first in alphabetical order), so the unscoped `quack_s3`
  * would lose every path of that bucket to the tenant's `qod_tl_<id8>` scoped to `s3://<bucket>/`.
  * The spawn scripts therefore create the manager's `QOD_S3_*` keys a second time as `qod_data_s3`,
  * scoped to the data path: a branch to its parent's prefix without the separator
  * (BranchNames.secretScope), any other path (a managed prefix included) to itself with a trailing
  * `/`. A database with its own objectStore secret (`qod_db_store`, already scoped to its data
  * path) gets none.
  */
private trait SpawnScriptDataPathSecretCases extends Matchers with SpawnScriptTempDir:
  this: AnyFlatSpec =>

  protected def command: Seq[String]
  protected def assumeHost(): Unit

  protected def initSql(dataPath: String, extra: (String, String)*): String =
    assumeHost()
    val base = Seq(
      "QOD_SPAWN_DRY_RUN"        -> "1",
      "schemaName"               -> "main",
      "catalogAlias"             -> "acme_lake",
      "dbName"                   -> "acme_lake",
      "kind"                     -> "ducklake",
      "dataPath"                 -> dataPath,
      "pgHost"                   -> "h",
      "pgPort"                   -> "5432",
      "pgUser"                   -> "u",
      "pgPassword"               -> "p",
      "QOD_S3_ACCESS_KEY_ID"     -> "lake-key-id",
      "QOD_S3_SECRET_ACCESS_KEY" -> "lake-secret",
      "QOD_S3_ENDPOINT"          -> "http://minio:9000",
      "QOD_S3_USE_SSL"           -> "false"
    )
    runSpawn(command, base ++ extra)

  /** The `CREATE OR REPLACE SECRET <name> (...)` statement of `sql`, its lines joined by `\n`. */
  protected def secret(sql: String, name: String): Option[String] =
    val lines = sql.linesIterator.toVector
    val start = lines.indexWhere(_.startsWith(s"CREATE OR REPLACE SECRET $name ("))
    if start < 0 then None
    else
      val end = lines.indexWhere(_.trim == ");", start)
      Some(lines.slice(start, end + 1).mkString("\n"))

  private def scopeOf(statement: String): Option[String] =
    "SCOPE '([^']*)'".r.findFirstMatchIn(statement).map(_.group(1))

  protected def cases(): Unit =
    it should "scope the manager's keys to the data path, with a trailing slash" in {
      val sql  = initSql("s3://lake/acme_lake")
      val data = secret(sql, "qod_data_s3").getOrElse(fail(s"no qod_data_s3 in:\n$sql"))
      scopeOf(data) shouldBe Some("s3://lake/acme_lake/")
      // The same credentials as the unscoped quack_s3, and that one is still there.
      val global = secret(sql, "quack_s3").getOrElse(fail("no quack_s3"))
      scopeOf(global) shouldBe None
      List("KEY_ID 'lake-key-id'", "SECRET 'lake-secret'", "ENDPOINT 'minio:9000'", "USE_SSL false")
        .foreach { opt =>
          global should include(opt)
          data should include(opt)
        }
    }

    it should "keep one trailing slash on a data path that has one" in {
      scopeOf(secret(initSql("s3://lake/acme_lake/"), "qod_data_s3").get) shouldBe
        Some("s3://lake/acme_lake/")
    }

    it should "scope a managed prefix to itself" in {
      scopeOf(secret(initSql("s3://qod-managed/acme_lake-0123abcd/"), "qod_data_s3").get) shouldBe
        Some("s3://qod-managed/acme_lake-0123abcd/")
    }

    it should "scope a branch to its parent's prefix, which also covers the parent's files" in {
      val branchPath = "s3://lake/acme_lake__br_0123abcd/"
      scopeOf(secret(initSql(branchPath), "qod_data_s3").get) shouldBe
        Some(ai.starlake.quack.ondemand.branch.BranchNames.secretScope(branchPath))
      scopeOf(secret(initSql(branchPath), "qod_data_s3").get) shouldBe Some("s3://lake/acme_lake")
    }

    it should "create none for a database with its own object-store secret" in {
      val own = ObjectStoreSecret.sql(
        Map("s3_access_key_id" -> "own-id", "s3_secret_access_key" -> "own-secret"),
        "s3://lake/acme_lake"
      )
      val sql = initSql("s3://lake/acme_lake", "objectStoreSql" -> own)
      secret(sql, "qod_data_s3") shouldBe None
      secret(sql, "qod_db_store").flatMap(scopeOf) shouldBe Some("s3://lake/acme_lake")
    }

    it should "create none without the manager's keys" in {
      val sql = initSql(
        "s3://lake/acme_lake",
        "QOD_S3_ACCESS_KEY_ID"     -> "",
        "QOD_S3_SECRET_ACCESS_KEY" -> ""
      )
      secret(sql, "quack_s3") shouldBe None
      secret(sql, "qod_data_s3") shouldBe None
    }

class SpawnScriptDataPathSecretSpec extends AnyFlatSpec with SpawnScriptDataPathSecretCases:
  protected val command            = Seq("bash", "scripts/spawn-quack-node.sh", "21999")
  protected def assumeHost(): Unit = ()
  behavior of "the data-path secret (bash)"
  cases()

  /** The release DuckDB CLI: DUCKDB_CLI, else the pinned version in DUCKDB_CACHE_DIR (build.sbt
    * points forked tests at the main checkout's .duckdb cache).
    */
  private def duckdbCli: Option[Path] =
    val pin = """val duckdb\s*=\s*"(\d+\.\d+\.\d+)\.\d+"""".r
      .findFirstMatchIn(Files.readString(Paths.get("project/Versions.scala")))
      .map(_.group(1))
    sys.env
      .get("DUCKDB_CLI")
      .filter(_.trim.nonEmpty)
      .map(Paths.get(_))
      .orElse(
        for
          root <- sys.env.get("DUCKDB_CACHE_DIR").filter(_.trim.nonEmpty)
          v    <- pin
        yield Paths.get(root, v, "bin", "duckdb")
      )
      .filter(Files.isExecutable(_))

  /** Runs `sql` in a fresh in-memory DuckDB, returning (exit code, stdout, stderr). */
  private def runCli(cli: Path, sql: String): (Int, String, String) =
    val in = Files.createTempFile(tempDir, "secrets", ".sql")
    Files.writeString(in, sql)
    val out = new StringBuilder
    val err = new StringBuilder
    // Without AI_AGENT: the CLI switches to its agent output mode when it is set.
    val rc = (Process(Seq("env", "-u", "AI_AGENT", cli.toString, "-csv", "-noheader")) #< in.toFile)
      .!(ProcessLogger(l => out.append(l).append('\n'), l => err.append(l).append('\n')))
    (rc, out.toString, err.toString)

  behavior of "the data-path secret on the DuckDB release (bash)"

  it should "outrank a tenant's bucket secret for the data path, on a lockdown node too" in {
    val cli          = duckdbCli.getOrElse(cancel("no DuckDB CLI (DUCKDB_CLI or DUCKDB_CACHE_DIR)"))
    val (okRc, _, _) = runCli(cli, "INSTALL httpfs;\nLOAD httpfs;\nSELECT 1;\n")
    assume(okRc == 0, "httpfs cannot be installed on this host (offline, no cache)")
    val bucket = TenantLocation(
      "0123abcd" + "0" * 24,
      "acme",
      TenantLocation.S3,
      "s3://lake/",
      Map("s3_access_key_id" -> "tenant-key-id", "s3_secret_access_key" -> "tenant-secret"),
      1L,
      None
    )
    List(
      ("s3://lake/acme_lake", "s3://lake/acme_lake/main/t/part-0.parquet"),
      ("s3://lake/acme_lake__br_0123abcd/", "s3://lake/acme_lake/main/t/part-0.parquet"),
      ("s3://lake/acme_lake__br_0123abcd/", "s3://lake/acme_lake__br_0123abcd/main/t/x.parquet")
    ).foreach { (dataPath, file) =>
      withClue(s"dataPath $dataPath, file $file: ") {
        val init = initSql(dataPath)
        // The spawn order: the storage secrets, the ATTACH (its secret lookup, here a which_secret,
        // loads DuckDB's persistent secret storage before lockdown: a first lookup after
        // `disabled_filesystems = 'LocalFileSystem'` would read the local file system), lockdown,
        // then, on the running node, the push.
        val sql = (List(
          "INSTALL httpfs;",
          "LOAD httpfs;",
          secret(init, "quack_s3").get,
          secret(init, "qod_data_s3").get,
          s"SELECT name FROM which_secret('$file', 's3');",
          NodeLockdown.sql(dataPath, enabled = true)
        ) ++ TenantLocationSecrets.pushAdd(bucket) ++ List(
          s"SELECT name FROM which_secret('$file', 's3');",
          "SELECT name FROM which_secret('s3://lake/raw/events.parquet', 's3');"
        )).mkString("\n")
        val (rc, out, err) = runCli(cli, sql + "\n")
        withClue(s"stderr: $err ") {
          rc shouldBe 0
          err.trim shouldBe ""
          out.linesIterator.map(_.trim).filter(_.nonEmpty).toList.takeRight(2) shouldBe
            List("qod_data_s3", TenantLocationSecrets.name(bucket))
        }
      }
    }
  }

// PowerShell twin: pwsh off Windows, cancelled without PowerShell, like
// SpawnScriptCatalogSecretWindowsSpec.
class SpawnScriptDataPathSecretWindowsSpec extends AnyFlatSpec with SpawnScriptDataPathSecretCases:
  protected def command =
    PowerShell.command.getOrElse(Nil) ++ Seq("-File", "scripts/spawn-quack-node.ps1", "21999")
  protected def assumeHost(): Unit =
    assume(PowerShell.command.nonEmpty, "no PowerShell on this host")
  behavior of "the data-path secret (PowerShell)"
  cases()
