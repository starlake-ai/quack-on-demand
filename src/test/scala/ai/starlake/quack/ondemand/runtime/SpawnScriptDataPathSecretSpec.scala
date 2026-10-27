package ai.starlake.quack.ondemand.runtime

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path, Paths}
import scala.sys.process.*

/** A database's data path signs with credentials scoped to it alone (spec decision 23): its own
  * objectStore (`qod_db_store`, ObjectStoreSecret) or else `qod_data_s3`, which the spawn scripts
  * build from the manager's `QOD_S3_*` keys or, with neither, from the instance's AWS credential
  * chain (the aws extension). No unscoped s3 secret is created on a node, so a user's `s3://` URL
  * signs with that user's own connection secret `qod_uc_<id8>` or with nothing, and every key is
  * named, so DuckDB fills none from a session's settings. The scopes follow
  * ObjectStoreSecret.dataScopes: a duckdb-file path itself, a branch its parent's prefix and its
  * own, any other path itself plus one `/` and its branches' prefix.
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

  private val NoManagerKeys =
    Seq("QOD_S3_ACCESS_KEY_ID" -> "", "QOD_S3_SECRET_ACCESS_KEY" -> "")

  /** The `CREATE OR REPLACE SECRET <name> (...)` statement of `sql`, its lines joined by `\n`. */
  protected def secret(sql: String, name: String): Option[String] =
    val lines = sql.linesIterator.toVector
    val start = lines.indexWhere(_.startsWith(s"CREATE OR REPLACE SECRET $name ("))
    if start < 0 then None
    else
      val end = lines.indexWhere(_.trim == ");", start)
      Some(lines.slice(start, end + 1).mkString("\n"))

  protected def scopesOf(statement: String): Option[List[String]] =
    "SCOPE \\[([^\\]]*)\\]".r
      .findFirstMatchIn(statement)
      .map(_.group(1).split(",").toList.map(_.trim.stripPrefix("'").stripSuffix("'")))

  private val ManagerKeys = List(
    "TYPE s3",
    "KEY_ID 'lake-key-id'",
    "SECRET 'lake-secret'",
    "SESSION_TOKEN ''",
    "REGION 'us-east-1'",
    "ENDPOINT 'minio:9000'",
    "URL_STYLE 'path'",
    "USE_SSL false",
    "KMS_KEY_ID ''",
    "URL_COMPATIBILITY_MODE false",
    "REQUESTER_PAYS false"
  )

  protected def cases(): Unit =
    it should "scope the manager's keys to the data path and its branches, naming every key" in {
      val sql  = initSql("s3://lake/acme_lake")
      val data = secret(sql, "qod_data_s3").getOrElse(fail(s"no qod_data_s3 in:\n$sql"))
      scopesOf(data) shouldBe Some(List("s3://lake/acme_lake/", "s3://lake/acme_lake__br_"))
      ManagerKeys.foreach(opt => withClue(opt)(data should include(opt)))
    }

    it should "create no unscoped s3 secret, whatever signs the data path" in
      List(
        initSql("s3://lake/acme_lake"),
        initSql("s3://lake/acme_lake", NoManagerKeys*),
        initSql(
          "s3://lake/acme_lake",
          "objectStoreSql" -> ObjectStoreSecret.sql(OwnKeys, "s3://lake/acme_lake", "ducklake")
        ),
        initSql("s3://lake/acme.duckdb", "kind" -> "duckdb-file")
      ).foreach { sql =>
        sql should not include "quack_s3"
        List("qod_data_s3", "qod_db_store")
          .flatMap(secret(sql, _))
          .foreach(s => withClue(s)(scopesOf(s) shouldBe defined))
      }

    it should "keep one trailing slash on a data path that has one" in {
      scopesOf(secret(initSql("s3://lake/acme_lake/"), "qod_data_s3").get) shouldBe
        Some(List("s3://lake/acme_lake/", "s3://lake/acme_lake__br_"))
    }

    it should "scope a managed prefix to itself and its branches" in {
      scopesOf(secret(initSql("s3://qod-managed/acme_lake-0123abcd/"), "qod_data_s3").get) shouldBe
        Some(
          List("s3://qod-managed/acme_lake-0123abcd/", "s3://qod-managed/acme_lake-0123abcd__br_")
        )
    }

    it should "scope a branch to its parent's prefix and its own" in {
      scopesOf(secret(initSql("s3://lake/acme_lake__br_0123abcd/"), "qod_data_s3").get) shouldBe
        Some(List("s3://lake/acme_lake/", "s3://lake/acme_lake__br_0123abcd/"))
    }

    it should "create none for a database with its own object-store secret" in {
      val own = ObjectStoreSecret.sql(OwnKeys, "s3://lake/acme_lake", "ducklake")
      val sql = initSql("s3://lake/acme_lake", "objectStoreSql" -> own)
      secret(sql, "qod_data_s3") shouldBe None
      secret(sql, "qod_db_store").flatMap(scopesOf) shouldBe
        Some(List("s3://lake/acme_lake/", "s3://lake/acme_lake__br_"))
      sql should not include "LOAD aws;"
    }

    it should "scope a bucket root, written with or without its slash, to s3://<bucket>/" in
      List("s3://lake", "s3://lake/").foreach { root =>
        withClue(s"$root: ") {
          scopesOf(secret(initSql(root), "qod_data_s3").get) shouldBe
            Some(List("s3://lake/", "s3://lake__br_"))
        }
      }

    it should "give a duckdb-file database a secret scoped to its file, on either credential" in {
      val file = "s3://lake/acme.duckdb"
      scopesOf(secret(initSql(file, "kind" -> "duckdb-file"), "qod_data_s3").get) shouldBe
        Some(List(file))
      val own = ObjectStoreSecret.sql(OwnKeys, file, "duckdb-file")
      secret(initSql(file, "kind" -> "duckdb-file", "objectStoreSql" -> own), "qod_db_store")
        .flatMap(scopesOf) shouldBe Some(List(file))
    }

    it should "fall back to the instance's credential chain without the manager's keys" in {
      val sql  = initSql("s3://lake/acme_lake", NoManagerKeys*)
      val data = secret(sql, "qod_data_s3").getOrElse(fail(s"no qod_data_s3 in:\n$sql"))
      List(
        "TYPE s3",
        "PROVIDER credential_chain",
        "VALIDATION 'none'",
        "REFRESH 'auto'",
        "ENDPOINT 'minio:9000'",
        "USE_SSL false",
        "URL_COMPATIBILITY_MODE false"
      ).foreach(opt => withClue(opt)(data should include(opt)))
      // The chain supplies the identity; an explicit empty session token would break it.
      List("KEY_ID", "SECRET '", "SESSION_TOKEN", "REGION").foreach(k =>
        withClue(k)(data should not include k)
      )
      scopesOf(data) shouldBe Some(List("s3://lake/acme_lake/", "s3://lake/acme_lake__br_"))
      sql should include("INSTALL aws;")
      sql.indexOf("LOAD aws;") should (be >= 0 and be < sql.indexOf(
        "CREATE OR REPLACE SECRET qod_data_s3"
      ))
      secret(
        initSql("s3://lake/acme_lake", (NoManagerKeys :+ ("QOD_S3_REGION" -> "eu-west-3"))*),
        "qod_data_s3"
      ).get should include("REGION 'eu-west-3'")
    }

    it should "load no aws extension when the manager's keys sign the data path" in {
      initSql("s3://lake/acme_lake") should not include "LOAD aws;"
    }

    it should "create the data-path secret before any ATTACH, for every kind" in {
      val lake = initSql("s3://lake/acme_lake")
      lake.indexOf("CREATE OR REPLACE SECRET qod_data_s3") should be < lake.indexOf(
        "ATTACH 'ducklake:"
      )
      val file = initSql("s3://lake/acme.duckdb", "kind" -> "duckdb-file")
      file.indexOf("CREATE OR REPLACE SECRET qod_data_s3") should be < file.indexOf("ATTACH 's3://")
    }

    it should "double a quote in the manager's keys" in {
      secret(
        initSql("s3://lake/acme_lake", "QOD_S3_SECRET_ACCESS_KEY" -> "it's"),
        "qod_data_s3"
      ).get should
        include("SECRET 'it''s'")
    }

    it should "refuse a QOD_S3_USE_SSL other than true or false, spliced unquoted into SQL" in {
      a[RuntimeException] should be thrownBy
        initSql("s3://lake/acme_lake", "QOD_S3_USE_SSL" -> "false, SCOPE 's3://'")
      secret(initSql("s3://lake/acme_lake", "QOD_S3_USE_SSL" -> "TRUE"), "qod_data_s3").get should
        include("USE_SSL true")
      secret(
        initSql("s3://lake/acme_lake", (NoManagerKeys :+ ("QOD_S3_USE_SSL" -> "False"))*),
        "qod_data_s3"
      ).get should include("USE_SSL false")
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

  /** A user's connection secret, as qod-node installs one (Task 7), here through SQL. */
  private val UserSecret =
    "CREATE SECRET qod_uc_0123abcd IN connection (TYPE s3, KEY_ID 'user-key-id', " +
      "SECRET 'user-secret', SCOPE 's3://lake/');"
  private val Raw = "s3://lake/raw/events.parquet"

  /** The secret each of `files` signs with on a node of a database at `dataPath` whose connection
    * holds a user's credential for `s3://lake/` (when `user`), `none` for no secret. The node's
    * order: the storage secrets the spawn rendered, the ATTACH (its secret lookup, here a
    * which_secret, runs before lockdown, as on a node: the first lookup reads the persistent secret
    * storage, which lockdown's disabled LocalFileSystem would refuse), lockdown, then a statement's
    * lookups.
    */
  private def winners(
      cli: Path,
      dataPath: String,
      files: List[String],
      kind: String = "ducklake",
      own: Boolean = false,
      user: Boolean = true
  ): List[String] =
    val extra =
      List("kind" -> kind) ++
        Option.when(own)("objectStoreSql" -> ObjectStoreSecret.sql(OwnKeys, dataPath, kind))
    val init = initSql(dataPath, extra*)
    val sql  = (List("INSTALL httpfs;", "LOAD httpfs;") ++
      List("qod_data_s3", "qod_db_store").flatMap(secret(init, _)) ++
      List(
        s"SELECT coalesce((SELECT name FROM which_secret('${files.head}', 's3')), 'none');",
        NodeLockdown.sql(dataPath, enabled = true)
      ) ++
      Option.when(user)(UserSecret).toList ++
      files.map(f => s"SELECT coalesce((SELECT name FROM which_secret('$f', 's3')), 'none');"))
      .mkString("\n")
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

  it should "sign the data path with its own secret and a user's URL with the user's" in {
    val cli = cliOrCancel()
    winners(
      cli,
      "s3://lake/acme_lake",
      List("s3://lake/acme_lake/main/t/part-0.parquet", Raw)
    ) shouldBe List("qod_data_s3", "qod_uc_0123abcd")
    // A branch: the parent's files it reads in place, and its own.
    winners(
      cli,
      "s3://lake/acme_lake__br_0123abcd/",
      List(
        "s3://lake/acme_lake/main/t/part-0.parquet",
        "s3://lake/acme_lake__br_0123abcd/main/t/x.parquet",
        Raw
      )
    ) shouldBe List("qod_data_s3", "qod_data_s3", "qod_uc_0123abcd")
    // The parent's node (and its merge node) reaches its branches' files too.
    winners(
      cli,
      "s3://lake/acme_lake",
      List("s3://lake/acme_lake__br_0123abcd/main/t/x.parquet")
    ) shouldBe List("qod_data_s3")
  }

  it should "sign nothing outside the data path when no user credential covers it" in {
    val cli = cliOrCancel()
    winners(cli, "s3://lake/acme_lake", List(Raw, "s3://other/x.parquet"), user = false) shouldBe
      List("none", "none")
  }

  it should "sign a bucket-root data path with its own secret, on either credential" in {
    val cli  = cliOrCancel()
    val file = "s3://lake/main/t/part-0.parquet"
    List("s3://lake", "s3://lake/").foreach { root =>
      withClue(s"$root: ") {
        winners(cli, root, List(file), user = false) shouldBe List("qod_data_s3")
        winners(cli, root, List(file), own = true, user = false) shouldBe List("qod_db_store")
      }
    }
  }

  it should "scope a duckdb-file database's own secret to its file" in {
    val cli  = cliOrCancel()
    val file = "s3://lake/acme.duckdb"
    winners(cli, file, List(file, s"$file.wal", Raw), kind = "duckdb-file", own = true) shouldBe
      List("qod_db_store", "qod_db_store", "qod_uc_0123abcd")
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
