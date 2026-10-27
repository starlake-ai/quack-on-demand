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
  * scoped to the data path by ObjectStoreSecret.dataScope's rule: a branch to its parent's prefix
  * without the separator, any other directory (a managed prefix and a bucket root included) to
  * itself with one trailing `/`. A database with its own objectStore secret (`qod_db_store`, the
  * same rule, a duckdb-file path scoped to the file itself) gets none. At a bucket root both tie
  * the tenant's `s3://<bucket>/` and win it by name.
  */
private trait SpawnScriptDataPathSecretCases extends Matchers with SpawnScriptTempDir:
  this: AnyFlatSpec =>

  protected def command: Seq[String]
  protected def assumeHost(): Unit

  protected val OwnKeys: Map[String, String] =
    Map("s3_access_key_id" -> "own-id", "s3_secret_access_key" -> "own-secret")

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
      val own = ObjectStoreSecret.sql(OwnKeys, "s3://lake/acme_lake", "ducklake")
      val sql = initSql("s3://lake/acme_lake", "objectStoreSql" -> own)
      secret(sql, "qod_data_s3") shouldBe None
      secret(sql, "qod_db_store").flatMap(scopeOf) shouldBe Some("s3://lake/acme_lake/")
    }

    it should "scope a bucket root, written with or without its slash, to s3://<bucket>/" in
      List("s3://lake", "s3://lake/").foreach { root =>
        withClue(s"$root: ") {
          scopeOf(secret(initSql(root), "qod_data_s3").get) shouldBe Some("s3://lake/")
        }
      }

    it should "create neither storage secret for a duckdb-file database (ducklake only)" in {
      // STORAGE_SQL (quack_s3, qod_data_s3) rides the ducklake arm only: a duckdb-file or memory
      // database on the manager's keys has no secret scoped to its data path (the known limitation
      // noted beside qod_data_s3 in spawn-quack-node.sh). Its own objectStore secret is scoped to
      // the file itself.
      val file = "s3://lake/acme.duckdb"
      secret(initSql(file, "kind" -> "duckdb-file"), "quack_s3") shouldBe None
      secret(initSql(file, "kind" -> "duckdb-file"), "qod_data_s3") shouldBe None
      val own = ObjectStoreSecret.sql(OwnKeys, file, "duckdb-file")
      secret(initSql(file, "kind" -> "duckdb-file", "objectStoreSql" -> own), "qod_db_store")
        .flatMap(scopeOf) shouldBe Some(file)
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

  private val Bucket = TenantLocation(
    "0123abcd" + "0" * 24,
    "acme",
    TenantLocation.S3,
    "s3://lake/",
    Map("s3_access_key_id" -> "tenant-key-id", "s3_secret_access_key" -> "tenant-secret"),
    1L,
    None
  )
  private val Tenant = TenantLocationSecrets.name(Bucket)
  private val Raw    = "s3://lake/raw/events.parquet"

  /** The secret each of `files` signs with on a node of a database at `dataPath`, after the tenant
    * authorized `s3://lake/` through the live push. The node's order: the storage secrets the spawn
    * rendered, the ATTACH (its secret lookup, here a which_secret, runs before lockdown, as on a
    * node), lockdown, then the push.
    */
  private def winners(
      cli: Path,
      dataPath: String,
      files: List[String],
      kind: String = "ducklake",
      own: Boolean = false
  ): List[String] =
    val extra =
      List("kind" -> kind) ++
        Option.when(own)("objectStoreSql" -> ObjectStoreSecret.sql(OwnKeys, dataPath, kind))
    val init = initSql(dataPath, extra*)
    val sql  = (List("INSTALL httpfs;", "LOAD httpfs;") ++
      List("quack_s3", "qod_data_s3", "qod_db_store").flatMap(secret(init, _)) ++
      List(
        s"SELECT name FROM which_secret('${files.head}', 's3');",
        NodeLockdown.sql(dataPath, enabled = true)
      ) ++ TenantLocationSecrets.pushAdd(Bucket) ++
      files.map(f => s"SELECT name FROM which_secret('$f', 's3');")).mkString("\n")
    val (rc, out, err) = runCli(cli, sql + "\n")
    withClue(s"stderr: $err ") {
      rc shouldBe 0
      err.trim shouldBe ""
    }
    out.linesIterator.map(_.trim).filter(_.nonEmpty).toList.takeRight(files.size)

  private def cliOrCancel(): Path =
    val cli          = duckdbCli.getOrElse(cancel("no DuckDB CLI (DUCKDB_CLI or DUCKDB_CACHE_DIR)"))
    val (okRc, _, _) = runCli(cli, "INSTALL httpfs;\nLOAD httpfs;\nSELECT 1;\n")
    assume(okRc == 0, "httpfs cannot be installed on this host (offline, no cache)")
    cli

  it should "outrank a tenant's bucket secret for the data path, on a lockdown node too" in {
    val cli = cliOrCancel()
    winners(
      cli,
      "s3://lake/acme_lake",
      List("s3://lake/acme_lake/main/t/part-0.parquet", Raw)
    ) shouldBe
      List("qod_data_s3", Tenant)
    // A branch: the parent's files it reads in place and its own.
    winners(
      cli,
      "s3://lake/acme_lake__br_0123abcd/",
      List(
        "s3://lake/acme_lake/main/t/part-0.parquet",
        "s3://lake/acme_lake__br_0123abcd/main/t/x.parquet",
        Raw
      )
    ) shouldBe List("qod_data_s3", "qod_data_s3", Tenant)
  }

  it should "win the tie at a bucket root, on the manager's keys and on the database's own" in {
    val cli  = cliOrCancel()
    val file = "s3://lake/main/t/part-0.parquet"
    List("s3://lake", "s3://lake/").foreach { root =>
      withClue(s"$root: ") {
        winners(cli, root, List(file, Raw)) shouldBe List("qod_data_s3", "qod_data_s3")
        winners(cli, root, List(file, Raw), own = true) shouldBe
          List("qod_db_store", "qod_db_store")
      }
    }
  }

  it should "scope a duckdb-file database's own secret to its file" in {
    val cli  = cliOrCancel()
    val file = "s3://lake/acme.duckdb"
    winners(cli, file, List(file, s"$file.wal", Raw), kind = "duckdb-file", own = true) shouldBe
      List("qod_db_store", "qod_db_store", Tenant)
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
