package ai.starlake.quack.it.authz

import ai.starlake.quack.ManagedObjectStoreConfig
import ai.starlake.quack.ondemand.federation.IcebergFixture
import ai.starlake.quack.ondemand.runtime.QodNodeTestBinary
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.storage.S3ManagedStoreClient
import io.circe.Json
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path}
import java.sql.{Connection, DriverManager}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import scala.concurrent.duration.*
import scala.sys.process.{Process, ProcessLogger}
import scala.util.{Try, Using}

/** `QOD_NODE_ALLOW_LOCAL_FILES=false` on a real manager, real qod-node processes and a real object
  * store (the Iceberg fixture's rustfs and REST catalog, under a prefix of this run's own): what a
  * node serves with DuckDB's external access off, what it refuses, and what that costs. Every node
  * starts through a wrapper of the spawn script that puts sentinels in its environment, read back
  * only as booleans. Manager on 21100 (REST, 21101 verdicts) / 21102 (FlightSQL) / 21103 (front
  * door), relay 21104, nodes 23400-23419. Cancelled without the test Postgres, a qod-node build or
  * the fixture.
  */
class LocalFilesOffE2ESpec
    extends AnyFlatSpec
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach:

  private val run    = java.lang.Long.toHexString(System.nanoTime() & 0xffffffL)
  private val Acme   = s"localfilesx$run"
  private val Pat    = "Pat-Pw-7731"
  private val Prefix = s"localfiles-e2e-$run"
  private val Bucket = "warehouse"
  private val Rustfs = "http://localhost:9000"
  private val iceNs  = s"qod_lf_$run"

  /** The value of every environment variable httpfs copies into a setting. */
  private val Sentinel = s"qod-sentinel-$run"

  private lazy val missing: Option[String] =
    if scala.util.Properties.isWin then Some("the bash spawn script")
    else if !TestPostgres.reachable then Some("no test Postgres")
    else if QodNodeTestBinary.path.isEmpty then Some("no qod-node build (QOD_NODE_BIN)")
    else if !IcebergFixture.reachable then Some(IcebergFixture.missingFixture)
    else None

  private lazy val work: Path    = Files.createTempDirectory("qod-lf-work").toRealPath()
  private lazy val outside: Path = Files.createDirectories(work.resolve("outside"))

  /** One line per node the spawn wrapper started, its id. */
  private lazy val spawnLog: Path = work.resolve("spawns.log")

  /** Until this exists, a node whose id holds `noswitch` starts as if its spawn script did not know
    * the setting.
    */
  private lazy val noswitchFixed: Path = work.resolve("noswitch-fixed")

  private var stack: AuthzStack = null

  /** The current case's pools, deleted after it: the stack has twenty node ports. */
  private val opened     = scala.collection.mutable.ListBuffer.empty[(String, String)]
  private var patSession = ""

  private def note(line: String): Unit             = println(s"[lf] $line")
  private def obj(fields: (String, Json)*): String = Json.obj(fields*).noSpaces
  private def s(v: String): Json                   = Json.fromString(v)

  private val storeJson = Json.obj(
    "s3_endpoint"          -> s(Rustfs),
    "s3_access_key_id"     -> s("admin"),
    "s3_secret_access_key" -> s("password"),
    "s3_region"            -> s("us-east-1"),
    "s3_url_style"         -> s("path")
  )

  /** The real spawn script with this spec's environment: sentinels for what httpfs would copy into
    * its s3_* settings, except on the one database that signs with the instance's credential chain.
    */
  private def spawnWrapper(): Path =
    val real    = Path.of("scripts/spawn-quack-node.sh").toAbsolutePath
    val wrapper = work.resolve("spawn-wrapper.sh")
    Files.writeString(
      wrapper,
      s"""#!/usr/bin/env bash
         |echo "$${QOD_NODE_ID:-}" >> "$spawnLog"
         |if [[ "$${QOD_NODE_ID:-}" == *noswitch* && ! -e "$noswitchFixed" ]]; then
         |  export QOD_NODE_ALLOW_LOCAL_FILES=true
         |fi
         |if [[ "$${dbName:-}" == *chain* ]]; then
         |  export AWS_ACCESS_KEY_ID=admin AWS_SECRET_ACCESS_KEY=password AWS_REGION=us-east-1
         |  export QOD_S3_ENDPOINT=localhost:9000 QOD_S3_USE_SSL=false QOD_S3_URL_STYLE=path
         |else
         |  export AWS_ACCESS_KEY_ID=$Sentinel AWS_SECRET_ACCESS_KEY=$Sentinel
         |  export AWS_SESSION_TOKEN=$Sentinel AWS_REGION=$Sentinel AWS_DEFAULT_REGION=$Sentinel
         |  export AWS_KMS_KEY_ID=$Sentinel DUCKDB_S3_ENDPOINT=$Sentinel
         |fi
         |exec bash "$real" "$$@"
         |""".stripMargin
    )
    Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwx------"))
    wrapper

  override def beforeAll(): Unit =
    if missing.isEmpty then
      stack = new AuthzStack(
        "localfiles",
        21100,
        21102,
        21103,
        21104,
        23400,
        23419,
        filesRoot = work,
        maintenanceTickSec = Some(2),
        userS3Endpoints = Rustfs,
        stackPrefixes = List("localfilesx"),
        allowLocalFiles = false,
        spawnScript = Some(spawnWrapper())
      )
      stack.boot()
      stack.post(
        "/api/tenant/create",
        obj("id" -> s(Acme), "displayName" -> s(Acme), "metastore" -> Json.obj())
      )
      stack.post(
        "/api/user/create",
        obj(
          "tenant"   -> s(Acme),
          "username" -> s("pat"),
          "password" -> s(Pat),
          "kind"     -> s("user"),
          "roles"    -> Json.arr(s("qod_all_tables")),
          "groups"   -> Json.arr(s("qod_all_pools"))
        )
      )
      patSession = stack.login(Some(Acme), "pat", Pat)

  override def afterAll(): Unit =
    try if stack != null then stack.stop()
    finally
      Try(purgePrefixes())
      Try {
        val tree = Files.walk(work)
        try tree.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
        finally tree.close()
      }

  private lazy val fixtureStore = new S3ManagedStoreClient(
    ManagedObjectStoreConfig(
      enabled = true,
      endpoint = Rustfs,
      bucket = Bucket,
      accessKeyId = "admin",
      secretAccessKey = "password"
    )
  )

  override def afterEach(): Unit =
    try
      opened.foreach { (td, pool) =>
        Try(
          stack.postStatus(
            "/api/pool/delete",
            obj("tenant" -> s(Acme), "tenantDb" -> s(td), "pool" -> s(pool), "force" -> Json.True)
          )
        )
      }
      opened.clear()
    finally super.afterEach()

  /** Removes what this run wrote under its prefixes of the shared bucket, nothing else. */
  private def purgePrefixes(): Unit =
    List(Prefix, iceNs).foreach { p =>
      var more = true
      while more do
        val keys = fixtureStore.listPrefix(p + "/", 1000).getOrElse(Nil)
        if keys.isEmpty then more = false
        else fixtureStore.deleteBatch(keys).left.foreach(_ => more = false)
    }

  private def ready(): Unit = missing.foreach(reason => cancel(s"skipped: $reason"))

  private def su(pool: String, sql: String): Either[String, List[String]] =
    stack.flight(sql, "admin", "admin", Acme, pool, superuser = true)
  private def pat(pool: String, sql: String): Either[String, List[String]] =
    stack.flight(sql, "pat", Pat, Acme, pool)

  private def rows(r: Either[String, List[String]]): List[String] =
    r.fold(err => fail(s"expected rows, got: $err"), identity)

  private def refusal(r: Either[String, List[String]]): String =
    r.fold(identity, v => fail(s"expected a refusal, got rows $v"))

  private def show(label: String, r: Either[String, List[String]]): Either[String, List[String]] =
    note(s"$label -> ${r.fold(e => "ERR " + e.take(600), v => "OK " + v.take(40).mkString(" | "))}")
    r

  /** A DuckLake database with its data under this run's prefix, signed with its own keys. */
  private def s3Database(name: String, metastore: Json = Json.obj()): String =
    val td = stack
      .post(
        "/api/database/create",
        obj(
          "tenant"      -> s(Acme),
          "name"        -> s(name),
          "kind"        -> s("ducklake"),
          "dataPath"    -> s(s"s3://$Bucket/$Prefix/$name/"),
          "objectStore" -> storeJson,
          "metastore"   -> metastore
        )
      )
      .hcursor
      .get[String]("name")
      .fold(e => throw e, identity)
    stack.catalogDatabases += td
    td

  private def memoryDatabase(name: String): String =
    stack
      .post(
        "/api/database/create",
        obj("tenant" -> s(Acme), "name" -> s(name), "kind" -> s("memory"))
      )
      .hcursor
      .get[String]("name")
      .fold(e => throw e, identity)

  private def pool(td: String, name: String, initSql: String = ""): Unit =
    openPool(td, name, initSql)
    stack.await(s"pool $name serving", 120.seconds)(su(name, "SELECT 1").toOption)

  private def openPool(td: String, name: String, initSql: String = ""): Unit =
    opened += ((td, name))
    stack.post(
      "/api/pool/create",
      obj(
        "tenant"           -> s(Acme),
        "tenantDb"         -> s(td),
        "pool"             -> s(name),
        "size"             -> Json.fromInt(1),
        "roleDistribution" -> Json.obj(
          "writeonly" -> Json.fromInt(0),
          "readonly"  -> Json.fromInt(0),
          "dual"      -> Json.fromInt(1)
        ),
        "initSql" -> s(initSql)
      )
    )
    ()

  /** Restarts the pool's one node through REST, and waits for it to serve again. */
  private def restart(td: String, name: String): Unit =
    val nodeId = stack
      .get("/api/pool/list")
      .hcursor
      .downField("pools")
      .values
      .toList
      .flatten
      .find(_.hcursor.get[String]("pool").contains(name))
      .flatMap(_.hcursor.downField("nodes").downArray.get[String]("nodeId").toOption)
      .getOrElse(fail(s"no node of $name listed"))
    val (code, body) = stack.postStatus(
      "/api/node/restart",
      obj("tenant" -> s(Acme), "tenantDb" -> s(td), "pool" -> s(name), "nodeId" -> s(nodeId)),
      timeoutSec = 120
    )
    withClue(body.take(300))(code shouldBe 200)
    stack.await(s"the restarted node of $name serving", 120.seconds)(su(name, "SELECT 1").toOption)

  private def withCatalog[A](db: String)(f: Connection => A): A =
    Using.resource(
      DriverManager.getConnection(TestPostgres.dbUrl(db), TestPostgres.pgUser, TestPostgres.pgPass)
    )(f)

  /** Every entry of the stored `allowed_directories` but DuckDB's own temp directory. */
  private def remoteEntries(pool: String): List[String] =
    val List(line) = rows(
      su(
        pool,
        "SELECT replace(replace(replace(current_setting('allowed_directories')::VARCHAR, '[', ''), " +
          "']', ''), ' ', '') || '|' || current_setting('temp_directory')"
      )
    ): @unchecked
    val Array(entries, temp) = line.split('|'): @unchecked
    entries.split(',').toList.map(_.stripPrefix("'").stripSuffix("'")).filterNot(_.startsWith(temp))

  /** A catalog database on the test Postgres under another host name: a server the row names. */
  private def ownServerDatabase(name: String): String =
    val full = s"${Acme}_$name"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$full"""")
    s3Database(
      name,
      Json.obj(
        "pgHost"     -> s("127.0.0.1"),
        "pgPort"     -> s(TestPostgres.pgPort.toString),
        "pgUser"     -> s(TestPostgres.pgUser),
        "pgPassword" -> s(TestPostgres.pgPass)
      )
    )

  /** A Parquet file written by the DuckDB CLI, nowhere near any data path. */
  private def localParquet(path: Path, note: String): Unit =
    val script =
      s"COPY (SELECT i AS id, '$note' AS note, 7 AS qty FROM range(2048) r(i)) " +
        s"TO '$path' (FORMAT parquet);"
    val code = (Process(Seq("duckdb", "-no-agent", "-bail")) #<
      new java.io.ByteArrayInputStream(script.getBytes(StandardCharsets.UTF_8)))
      .!(ProcessLogger(_ => (), _ => ()))
    code shouldBe 0

  private def maintenanceRun(td: String, operations: String): Json =
    val runId = stack
      .post(
        "/api/maintenance/run",
        obj("tenant" -> s(Acme), "tenantDb" -> s(td), "operations" -> s(operations))
      )
      .hcursor
      .get[Long]("id")
      .fold(e => throw e, identity)
    stack.await(s"maintenance run $runId to end", 180.seconds) {
      stack
        .get(s"/api/maintenance/runs?tenant=$Acme&tenantDb=$td")
        .asArray
        .toList
        .flatten
        .find(_.hcursor.get[Long]("id").contains(runId))
        .filter(_.hcursor.get[String]("finishedAt").isRight)
    }

  // ---------- what the node serves ----------

  "a node with local files off" should "start, go healthy and serve a plain user end to end" in {
    ready()
    val td = s3Database("lake")
    pool(td, "lake")
    rows(pat("lake", s"CREATE TABLE $td.main.t (id BIGINT, v VARCHAR)"))
    rows(pat("lake", s"INSERT INTO $td.main.t SELECT i, 'row' FROM range(5000) r(i)"))
    rows(pat("lake", s"SELECT count(*), max(v) FROM $td.main.t")) shouldBe List("5000,row")
    // An update and a delete rewrite files and write delete files on the store.
    rows(pat("lake", s"UPDATE $td.main.t SET v = 'new' WHERE id < 10"))
    rows(pat("lake", s"DELETE FROM $td.main.t WHERE id >= 4990"))
    rows(pat("lake", s"SELECT count(*), max(v) FROM $td.main.t")) shouldBe List("4990,row")
    rows(su("lake", "SELECT current_setting('enable_external_access')")) shouldBe List("false")
  }

  it should "hold no local path in allowed_directories, and azure's schemes only where azure loaded" in {
    ready()
    val lake = s3Database("dirs")
    pool(lake, "dirs")
    val httpfsOnly = remoteEntries("dirs")
    note(s"allowed_directories, httpfs only: $httpfsOnly")
    httpfsOnly should contain allOf ("s3://", "https://", "http://", "gs://", "r2://")
    httpfsOnly.filter(_.contains(":///")) shouldBe Nil
    httpfsOnly.filter(e => !e.contains("://")) shouldBe Nil
    httpfsOnly.filter(_.contains("az")) shouldBe Nil

    val azure = memoryDatabase("azuremem")
    pool(azure, "azuremem", initSql = "INSTALL azure;\nLOAD azure;\n")
    val withAzure = remoteEntries("azuremem")
    note(s"allowed_directories, azure loaded: $withAzure")
    withAzure should contain allOf ("az://", "azure://", "abfs://", "abfss://", "s3://")
    withAzure.filter(_.contains(":///")) shouldBe Nil
  }

  it should "refuse a superuser's local read and write, and every way back on" in {
    ready()
    val td = s3Database("refuse")
    pool(td, "refuse")
    val denied = "file system operations are disabled by configuration"
    val probe  = outside.resolve("copied.csv")
    Files.writeString(outside.resolve("readable.txt"), "local")
    List(
      "SELECT length(content) FROM read_text('/etc/passwd')",
      "SELECT length(content) FROM read_text('file:///etc/passwd')",
      s"SELECT length(content) FROM read_text('${outside.resolve("readable.txt")}')",
      "SELECT count(*) FROM glob('/etc/*')",
      s"COPY (SELECT 1 AS a) TO '$probe'"
    ).foreach { sql =>
      val err = refusal(show(sql, su("refuse", sql)))
      withClue(sql)(err should include(denied))
    }
    Files.exists(probe) shouldBe false
    List(
      "SET enable_external_access = true"   -> "Cannot enable external access",
      "RESET enable_external_access"        -> "Cannot enable external access",
      "SET allowed_directories = ['/etc/']" -> "Cannot change allowed_directories",
      "RESET allowed_directories"           -> "Cannot change allowed_directories",
      "SET allowed_paths = ['/etc/passwd']" -> "Cannot change allowed_paths",
      "RESET allowed_paths"                 -> "Cannot change allowed_paths",
      "SET temp_directory = '/etc'"         -> "Modifying the temp_directory",
      "RESET temp_directory"                -> "Modifying the temp_directory"
    ).foreach { (sql, expected) =>
      val err = refusal(show(sql, su("refuse", sql)))
      withClue(sql)(err should include(expected))
    }
    rows(su("refuse", "SELECT current_setting('enable_external_access')")) shouldBe List("false")
    refusal(su("refuse", "SELECT length(content) FROM read_text('/etc/passwd')")) should
      include(denied)
  }

  it should "stay off when a superuser's init text never closes a quote" in {
    ready()
    val mem = memoryDatabase("swallow")
    pool(mem, "swallow", initSql = "SELECT 'a quote that never closes;\n")
    rows(su("swallow", "SELECT current_setting('enable_external_access')")) shouldBe List("false")
    refusal(su("swallow", "SELECT length(content) FROM read_text('/etc/passwd')")) should
      include("file system operations are disabled by configuration")
  }

  it should "get past the CA bundle on an HTTPS request, on a port nothing answers" in {
    ready()
    val td = s3Database("tls")
    pool(td, "tls")
    val err =
      refusal(
        show(
          "https",
          su("tls", "SELECT count(*) FROM read_parquet('https://127.0.0.1:9/x.parquet')")
        )
      )
    err should not include "CA certificate bundle"
    // One allowed bundle file is all a TLS client needs, and nothing else under /etc opens up.
    refusal(su("tls", "SELECT length(content) FROM read_text('/etc/hosts')")) should
      include("file system operations are disabled by configuration")
  }

  it should "reset what httpfs copied from the environment, on a memory node too" in {
    ready()
    val mem = memoryDatabase("envmem")
    pool(mem, "envmem")
    val lake = s3Database("envlake")
    pool(lake, "envlake")
    // httpfs does copy the environment: on the CLI, with no qod-node to reset it, a value comes back.
    val cli = (Process(
      Seq("duckdb", "-no-agent", "-noheader", "-list"),
      None,
      "AWS_ACCESS_KEY_ID" -> Sentinel
    ) #< new java.io.ByteArrayInputStream(
      s"LOAD httpfs;\nSELECT current_setting('s3_access_key_id') = '$Sentinel';\n".getBytes(
        StandardCharsets.UTF_8
      )
    )).!!.trim
    cli shouldBe "true"
    val settings = List(
      "s3_access_key_id",
      "s3_secret_access_key",
      "s3_session_token",
      "s3_region",
      "s3_endpoint",
      "s3_kms_key_id"
    )
    List("envmem", "envlake").foreach { p =>
      settings.foreach { name =>
        // A boolean, never the value; a setting reset to its default reads NULL.
        val same = rows(su(p, s"SELECT current_setting('$name') IS NOT DISTINCT FROM '$Sentinel'"))
        note(s"$p $name holds the environment's value: $same")
        same shouldBe List("false")
      }
    }
  }

  it should "sign the data path with the credential chain, aws_default_credentials off" in {
    ready()
    val td = stack
      .post(
        "/api/database/create",
        obj(
          "tenant"   -> s(Acme),
          "name"     -> s("chain"),
          "kind"     -> s("ducklake"),
          "dataPath" -> s(s"s3://$Bucket/$Prefix/chain/")
        )
      )
      .hcursor
      .get[String]("name")
      .fold(e => throw e, identity)
    stack.catalogDatabases += td
    pool(td, "chain")
    rows(su("chain", "SELECT current_setting('aws_default_credentials')")) shouldBe List("false")
    rows(su("chain", s"CREATE TABLE $td.main.t AS SELECT 1 AS id"))
    rows(su("chain", s"SELECT count(*) FROM $td.main.t")) shouldBe List("1")
    rows(
      su("chain", "SELECT current_setting('s3_access_key_id') IS NOT DISTINCT FROM 'admin'")
    ) shouldBe List("false")
  }

  it should "fetch a user's S3 credential and read and write with it" in {
    ready()
    val mem = memoryDatabase("creds")
    pool(mem, "creds")
    val scope = s"s3://$Bucket/$Prefix/mine/"
    stack.post(
      "/api/profile/s3/add",
      obj(
        "scope"           -> s(scope),
        "accessKeyId"     -> s("admin"),
        "secretAccessKey" -> s("password"),
        "region"          -> s("us-east-1"),
        "endpoint"        -> s(Rustfs),
        "urlStyle"        -> s("path")
      ),
      patSession
    )
    rows(pat("creds", s"COPY (SELECT 42 AS answer) TO '${scope}x.parquet' (FORMAT parquet)"))
    rows(pat("creds", s"SELECT answer FROM read_parquet('${scope}x.parquet')")) shouldBe List("42")
    // The location rules still judge a plain user: nothing outside their own scope.
    refusal(
      pat("creds", s"SELECT count(*) FROM read_parquet('s3://$Bucket/$Prefix/elsewhere/x.parquet')")
    ) should
      include("QoD: ")
    refusal(pat("creds", "SELECT length(content) FROM read_text('/etc/passwd')")) should
      include("QoD: ")
  }

  // ---------- what a catalog's file list can no longer reach ----------

  "a catalog listing local files on an own server" should "reach no local file, and the sweep still works on the store" in {
    ready()
    val td = ownServerDatabase("own")
    pool(td, "own")
    rows(pat("own", s"CREATE TABLE $td.main.fact (id BIGINT, note VARCHAR, qty INTEGER)"))
    rows(pat("own", s"INSERT INTO $td.main.fact SELECT i, 'OWN', 1 FROM range(2048) r(i)"))

    // The row's file list names a real Parquet outside every data path.
    val outsideFile = outside.resolve("outside.parquet")
    localParquet(outsideFile, "OUTSIDE")
    val (count, size, footer) = (2048L, Files.size(outsideFile), 0L)
    withCatalog(td) { c =>
      val ps = c.prepareStatement(
        "UPDATE ducklake_data_file SET path = ?, path_is_relative = false, record_count = ?, " +
          "file_size_bytes = ?, footer_size = ?"
      )
      ps.setString(1, outsideFile.toString)
      ps.setLong(2, count)
      ps.setLong(3, size)
      ps.setLong(4, footer)
      ps.executeUpdate() should be > 0
      ps.close()
    }
    val read =
      show("list naming a local file", pat("own", s"SELECT DISTINCT note FROM $td.main.fact"))
    refusal(read) should include("file system operations are disabled by configuration")

    // A schedule naming two local files, backdated, and a tenant's cleanup run.
    val scheduledParquet = outside.resolve("scheduled.parquet")
    val scheduledText    = outside.resolve("scheduled.txt")
    Files.writeString(scheduledParquet, "not really parquet")
    Files.writeString(scheduledText, "plain")
    def schedule(c: Connection, path: String): Unit =
      val ps = c.prepareStatement(
        "INSERT INTO ducklake_files_scheduled_for_deletion " +
          "(data_file_id, path, path_is_relative, schedule_start) " +
          "VALUES (?, ?, false, TIMESTAMP '2000-01-01 00:00:00')"
      )
      ps.setLong(1, Math.abs(scala.util.Random.nextInt().toLong) + 900000000L)
      ps.setString(2, path)
      ps.executeUpdate()
      ps.close()
    withCatalog(td) { c =>
      schedule(c, scheduledParquet.toString)
      schedule(c, scheduledText.toString)
    }
    val localSchedule = maintenanceRun(td, "cleanup")
    note(s"schedule naming local files: ${localSchedule.noSpaces.take(600)}")
    Files.exists(scheduledParquet) shouldBe true
    Files.exists(scheduledText) shouldBe true
    Files.exists(outsideFile) shouldBe true

    // The schedule cleared, a real one on the object store goes, and so does an orphan.
    withCatalog(td)(c =>
      Using.resource(c.createStatement())(
        _.executeUpdate("DELETE FROM ducklake_files_scheduled_for_deletion")
      )
    )
    val dataPrefix = s"s3://$Bucket/$Prefix/own/"
    rows(su("own", s"COPY (SELECT 1 AS a) TO '${dataPrefix}scheduled.parquet' (FORMAT parquet)"))
    rows(su("own", s"COPY (SELECT 1 AS a) TO '${dataPrefix}orphan.parquet' (FORMAT parquet)"))
    withCatalog(td)(schedule(_, s"${dataPrefix}scheduled.parquet"))
    def listed(name: String): Boolean =
      fixtureStore.listPrefix(s"$Prefix/own/$name", 10).getOrElse(Nil).nonEmpty
    listed("scheduled.parquet") shouldBe true
    val cleaned = maintenanceRun(td, "cleanup")
    note(s"cleanup on the object store: ${cleaned.noSpaces.drop(300).take(500)}")
    cleaned.hcursor.get[String]("status") shouldBe Right("succeeded")
    listed("scheduled.parquet") shouldBe false
    // A maintenance run keeps an orphan younger than its minimum age (a day at least): the same call
    // with no age limit is the one that deletes.
    val swept = maintenanceRun(td, "orphans")
    note(s"orphan sweep on the object store: ${swept.noSpaces.drop(300).take(400)}")
    swept.hcursor.get[String]("status") shouldBe Right("succeeded")
    listed("orphan.parquet") shouldBe true
    show(
      "the sweep with no age limit",
      su(
        "own",
        s"SELECT count(*) FROM ducklake_delete_orphaned_files('$td', cleanup_all => true)"
      )
    )
    listed("orphan.parquet") shouldBe false
  }

  "an Iceberg catalog" should "read a table on the store and refuse a manifest listing a local file" in {
    ready()
    val localFile = outside.resolve("iceberg-local.parquet")
    val script    = work.resolve("iceberg_tables.py")
    Files.writeString(
      script,
      s"""import pyarrow as pa, pyarrow.parquet as pq
         |from pyiceberg.catalog import load_catalog
         |cat = load_catalog("rest", **{"type": "rest", "uri": "${IcebergFixture.endpoint}",
         |  "s3.endpoint": "$Rustfs", "s3.access-key-id": "admin", "s3.secret-access-key": "password",
         |  "s3.region": "us-east-1", "s3.path-style-access": "true"})
         |cat.create_namespace("$iceNs")
         |schema = pa.schema([("id", pa.int32()), ("note", pa.string())])
         |rows = {"id": [1, 2, 3], "note": ["LOCAL", "LOCAL", "LOCAL"]}
         |pq.write_table(pa.table(rows, schema=schema), "$localFile")
         |local = cat.create_table("$iceNs.localfile", schema=schema)
         |local.add_files(["$localFile"])
         |ctrl = cat.create_table("$iceNs.ctrl", schema=schema)
         |ctrl.append(pa.table({"id": [1, 2, 3], "note": ["STORE", "STORE", "STORE"]}, schema=schema))
         |""".stripMargin
    )
    val out = new StringBuilder
    val rc  = Try(
      Process(
        Seq(
          "uv",
          "run",
          "--no-project",
          "--with",
          "pyiceberg[pyarrow,s3fs]",
          "python",
          script.toString
        )
      ).!(ProcessLogger(l => out.append(l).append('\n'), l => out.append(l).append('\n')))
    ).getOrElse(127)
    if rc != 0 then
      cancel(s"pyiceberg could not write the manifest (exit $rc): ${out.toString.takeRight(400)}")

    val fed = memoryDatabase("fed")
    stack.post(
      s"/api/tenants/$Acme/tenant-dbs/$fed/federated-sources",
      obj(
        "alias"      -> s("ice"),
        "disabled"   -> Json.False,
        "sourceType" -> s("iceberg_rest"),
        "readOnly"   -> Json.False,
        "config"     -> Json.obj(
          "uri"       -> s(IcebergFixture.endpoint),
          "warehouse" -> s(IcebergFixture.warehouse),
          "authType"  -> s("none")
        )
      )
    )
    // The fixture vends no credentials: the reader signs with a key of their own.
    stack.post(
      "/api/profile/s3/add",
      obj(
        "scope"           -> s(s"s3://$Bucket/$iceNs/"),
        "accessKeyId"     -> s("admin"),
        "secretAccessKey" -> s("password"),
        "region"          -> s("us-east-1"),
        "endpoint"        -> s(Rustfs),
        "urlStyle"        -> s("path")
      ),
      stack.login(None, "admin", "admin")
    )
    pool(fed, "fed")
    rows(
      show("iceberg on the store", su("fed", s"SELECT DISTINCT note FROM ice.$iceNs.ctrl"))
    ) shouldBe
      List("STORE")
    refusal(
      show(
        "manifest naming a local file",
        su("fed", s"SELECT DISTINCT note FROM ice.$iceNs.localfile")
      )
    ) should
      include("file system operations are disabled by configuration")
    Files.exists(localFile) shouldBe true
  }

  // ---------- what it costs ----------

  "a statement past memory_limit" should "spill and return, and the temp directory stays allowed" in {
    ready()
    val td = ownServerDatabase("spill")
    pool(td, "spill", initSql = "SET memory_limit = '120MB';\nSET threads = 2;\n")
    rows(su("spill", "SELECT current_setting('memory_limit')")).head should startWith("114")
    val List(temp) = rows(su("spill", "SELECT current_setting('temp_directory')")): @unchecked

    // The window sort the lead ran on the CLI, watched from another session.
    val peak    = new AtomicLong(0)
    val done    = new AtomicBoolean(false)
    val watcher = new Thread(() =>
      while !done.get() do
        su("spill", "SELECT coalesce(sum(size), 0) FROM duckdb_temporary_files()")
          .foreach(_.headOption.foreach(bytes => peak.accumulateAndGet(bytes.toLong, Math.max)))
        Thread.sleep(150)
    )
    watcher.setDaemon(true)
    watcher.start()
    val sort =
      "SELECT max(rn) AS spilled_ok FROM (SELECT i, row_number() OVER (ORDER BY hash(i)) rn " +
        "FROM range(30000000) t(i))"
    val sorted =
      try rows(show("window sort past memory_limit", su("spill", sort)))
      finally
        done.set(true)
        watcher.join(5000)
    sorted shouldBe List("30000000")
    note(s"peak of duckdb_temporary_files while sorting: ${peak.get()} bytes")
    peak.get() should be > 100000000L
    val join =
      "SELECT count(*) FROM (SELECT hash(i) h FROM range(12000000) t(i)) a " +
        "JOIN (SELECT hash(i) h FROM range(12000000) t(i)) b USING (h)"
    rows(show("hash join past memory_limit", su("spill", join))) shouldBe List("12000000")
    // A plain user's statement spills the same way: no location of theirs is involved.
    rows(show("plain user's sort", pat("spill", sort))) shouldBe List("30000000")

    // What a statement can do there. A superuser reads and writes it.
    val inTemp = s"$temp/lf.parquet"
    rows(
      su(
        "spill",
        s"COPY (SELECT i AS id, 'TEMP' AS note, 7 AS qty FROM range(2048) r(i)) TO '$inTemp' " +
          "(FORMAT parquet, FIELD_IDS {id: 1, note: 2, qty: 3})"
      )
    )
    rows(su("spill", s"SELECT count(*) FROM read_parquet('$inTemp')")) shouldBe List("2048")
    rows(su("spill", s"SELECT count(*) > 0 FROM glob('$temp/*')")) shouldBe List("true")
    // A plain user does neither: the location rules judge the path as they do any other.
    val userRead = refusal(
      show(
        "plain user reads the temp directory",
        pat("spill", s"SELECT count(*) FROM read_parquet('$inTemp')")
      )
    )
    userRead should include("QoD: ")
    val userWrite = refusal(
      show(
        "plain user writes the temp directory",
        pat("spill", s"COPY (SELECT 1 AS a) TO '$temp/mine.csv'")
      )
    )
    userWrite should include("QoD: ")
    Files.exists(Path.of(s"$temp/mine.csv")) shouldBe false

    // A file list naming a file there reads it: DuckDB allows the directory, for anyone.
    rows(pat("spill", s"CREATE TABLE $td.main.fact (id BIGINT, note VARCHAR, qty INTEGER)"))
    rows(pat("spill", s"INSERT INTO $td.main.fact SELECT i, 'OWN', 1 FROM range(2048) r(i)"))
    val List(footer) =
      rows(su("spill", s"SELECT footer_size FROM parquet_file_metadata('$inTemp')")): @unchecked
    withCatalog(td) { c =>
      val ps = c.prepareStatement(
        "UPDATE ducklake_data_file SET path = ?, path_is_relative = false, record_count = 2048, " +
          "file_size_bytes = ?, footer_size = ?"
      )
      ps.setString(1, inTemp)
      ps.setLong(2, Files.size(Path.of(inTemp)))
      ps.setLong(3, footer.toLong)
      ps.executeUpdate() should be > 0
      ps.close()
    }
    val inTempRead = show(
      "list naming a superuser's Parquet in the temp directory",
      pat("spill", s"SELECT DISTINCT note FROM $td.main.fact")
    )
    // What DuckDB leaves allowed, for anyone: only what a superuser put there, as below.
    inTempRead shouldBe Right(List("TEMP"))
    // A spill file is no Parquet: a list naming a live one reads nothing of it.
    val busy = new Thread(() =>
      su("spill", sort)
      ()
    )
    busy.setDaemon(true)
    busy.start()
    val live = stack.await("a live spill file", 60.seconds) {
      su("spill", "SELECT path FROM duckdb_temporary_files() LIMIT 1").toOption.flatMap(
        _.headOption
      )
    }
    withCatalog(td) { c =>
      val ps =
        c.prepareStatement("UPDATE ducklake_data_file SET path = ?, path_is_relative = false")
      ps.setString(1, live)
      ps.executeUpdate()
      ps.close()
    }
    refusal(
      show(
        "list naming a live spill file",
        pat("spill", s"SELECT DISTINCT note FROM $td.main.fact")
      )
    )
    busy.join(120000)
    // A schedule naming this node's temp directory does not reach it: maintenance runs on a node of its
    // own, with a temp directory of its own.
    withCatalog(td)(c =>
      Using.resource(
        c.prepareStatement(
          "INSERT INTO ducklake_files_scheduled_for_deletion (data_file_id, path, path_is_relative, schedule_start) " +
            "VALUES (987654321, ?, false, TIMESTAMP '2000-01-01 00:00:00')"
        )
      ) { ps =>
        ps.setString(1, inTemp)
        ps.executeUpdate()
      }
    )
    val swept = maintenanceRun(td, "cleanup")
    note(s"cleanup naming another node's temp directory: ${swept.noSpaces.take(500)}")
    Files.exists(Path.of(inTemp)) shouldBe true
  }

  "an extension a node did not load at start" should "stay out, and the static ones keep working" in {
    ready()
    val mem = memoryDatabase("extmem")
    pool(mem, "extmem")
    val lake = s3Database("extlake")
    pool(lake, "extlake")
    val checks = Map(
      "icu" -> "SELECT strftime(TIMESTAMPTZ '2026-01-01 00:00:00+00' AT TIME ZONE 'Asia/Tokyo', '%H')",
      "json"     -> """SELECT json_extract('{"a":1}', '$.a')""",
      "parquet"  -> "SELECT count(*) FROM parquet_schema('http://127.0.0.1:9/x.parquet')",
      "httpfs"   -> "SELECT count(*) FROM read_parquet('http://127.0.0.1:9/x.parquet')",
      "avro"     -> "SELECT * FROM read_avro('http://127.0.0.1:9/x.avro')",
      "ducklake" -> "SELECT * FROM ducklake_snapshots('nope')",
      "iceberg"  -> "SELECT * FROM iceberg_scan('http://127.0.0.1:9/x')",
      "postgres" -> "SELECT * FROM postgres_query('nope', 'SELECT 1')",
      "aws"      -> "CALL load_aws_credentials()",
      "sqlite"   -> "SELECT * FROM sqlite_scan('x.db', 't')",
      "tpch"     -> "SELECT count(*) FROM tpch_queries()",
      "fts"      -> "SELECT stem('running', 'english')",
      "azure"    -> "SELECT count(*) FROM read_parquet('az://nocontainer/x.parquet')",
      "spatial"  -> "SELECT ST_Point(1, 2)"
    )
    def outcome(p: String, name: String) = show(s"$p extension $name", su(p, checks(name)))
    val autoload = "Loading external extensions is disabled through configuration"

    // What ships inside libduckdb keeps working on every node, and httpfs is always there.
    rows(outcome("extmem", "icu")) shouldBe List("09")
    rows(outcome("extmem", "json")) shouldBe List("1")
    refusal(outcome("extmem", "parquet")) should include("Could not connect")
    refusal(outcome("extmem", "httpfs")) should include("Could not connect")
    // What has to load from disk does not, past the switch.
    for name <- List("avro", "ducklake", "iceberg", "postgres", "aws", "sqlite", "tpch", "fts") do
      withClue(name)(refusal(outcome("extmem", name)) should include(autoload))
    refusal(outcome("extmem", "azure")) should include("file system operations are disabled")
    refusal(outcome("extmem", "spatial")) should include("it exists in the spatial extension")
    // Named by a superuser, for the same reason.
    refusal(show("LOAD avro", su("extmem", "LOAD avro"))) should
      include("Loading external extensions is disabled")
    refusal(show("INSTALL avro", su("extmem", "INSTALL avro"))) should
      include("file system operations are disabled")
    refusal(
      show("duckdb_extensions()", su("extmem", "SELECT count(*) FROM duckdb_extensions()"))
    ) should
      include("file system operations are disabled")
    // A DuckLake node holds its own two: the name is what fails, nothing loads.
    refusal(outcome("extlake", "ducklake")) should include("Failed to find attached database")
    refusal(outcome("extlake", "postgres")) should include("Failed to find attached database")
    refusal(outcome("extlake", "avro")) should include(autoload)
    // What a node's own init SQL loaded stays loaded.
    val spatial = memoryDatabase("extinit")
    pool(spatial, "extinit", initSql = "INSTALL tpch;\nLOAD tpch;\n")
    rows(su("extinit", checks("tpch"))).head.toLong should be > 0L
  }

  private def icebergSource(td: String, alias: String, uri: String): Unit =
    stack.post(
      s"/api/tenants/$Acme/tenant-dbs/$td/federated-sources",
      obj(
        "alias"      -> s(alias),
        "disabled"   -> Json.False,
        "sourceType" -> s("iceberg_rest"),
        "readOnly"   -> Json.False,
        "config"     -> Json.obj(
          "uri"       -> s(uri),
          "warehouse" -> s(IcebergFixture.warehouse),
          "authType"  -> s("none")
        )
      )
    )

  "a late ATTACH" should "reach no local file, and a source down at the start waits for a restart" in {
    ready()
    val mem = memoryDatabase("late")
    pool(mem, "late")
    show("ATTACH :memory:", su("late", "ATTACH ':memory:' AS late_mem")).isRight shouldBe true
    refusal(
      show("ATTACH local", su("late", s"ATTACH '${outside.resolve("late.duckdb")}' AS late_file"))
    ) should
      include("file system operations are disabled")
    refusal(
      show(
        "ATTACH postgres, not loaded",
        su(
          "late",
          s"ATTACH 'host=${TestPostgres.pgHost} port=${TestPostgres.pgPort} dbname=postgres user=${TestPostgres.pgUser}' AS late_pg (TYPE postgres)"
        )
      )
    ) should include("Loading external extensions is disabled")

    // Down when the node starts: the re-attach cannot get past its INSTALL, and says so.
    val down = memoryDatabase("downice")
    icebergSource(down, "downice", "http://127.0.0.1:21111")
    pool(down, "downice")
    val failure = stack.await("the attach failure", 60.seconds) {
      stack
        .get("/api/pool/list")
        .hcursor
        .downField("pools")
        .values
        .toList
        .flatten
        .find(_.hcursor.get[String]("pool").contains("downice"))
        .flatMap(
          _.hcursor
            .downField("nodes")
            .downArray
            .downField("catalogAttachFailures")
            .downArray
            .get[String]("error")
            .toOption
        )
        .filter(_.contains("Permission Error"))
    }
    note(s"a source down at the node's start: $failure")
    failure should include("file system operations are disabled")
    // The catalog back, the node restarted: it attaches.
    val relay = new TcpRelay(21111, 8181)
    relay.start()
    try
      restart(down, "downice")
      rows(
        su("downice", "SELECT count(*) FROM duckdb_databases() WHERE database_name = 'downice'")
      ) shouldBe List("1")
    finally relay.stop()
  }

  "what a superuser's init SQL did before the switch" should "stay as it was, or read nothing" in {
    ready()
    // A local file attached at init stays reachable: DuckDB allows what is attached.
    val fedFile = work.resolve("fed.duckdb")
    val fedDb   = memoryDatabase("fedfile")
    stack.post(
      s"/api/tenants/$Acme/tenant-dbs/$fedDb/federated-sources",
      obj(
        "alias"      -> s("localfed"),
        "disabled"   -> Json.False,
        "sourceType" -> s("sql"),
        "readOnly"   -> Json.False,
        "setupSql"   -> s(
          s"ATTACH '$fedFile' AS {{alias}};\nCREATE TABLE IF NOT EXISTS {{alias}}.main.t AS SELECT 1 AS v;"
        )
      )
    )
    pool(fedDb, "fedfile")
    rows(su("fedfile", "SELECT * FROM localfed.main.t")) shouldBe List("1")
    rows(su("fedfile", "INSERT INTO localfed.main.t VALUES (2)"))
    // A view over a local file is made at init, and reads nothing: `qod serve <local file>`'s shape.
    val parquet = outside.resolve("served.parquet")
    localParquet(parquet, "SERVED")
    val served = memoryDatabase("served")
    pool(served, "served", initSql = s"CREATE VIEW v AS SELECT * FROM read_parquet('$parquet');\n")
    refusal(show("a view over a local file", su("served", "SELECT * FROM memory.main.v"))) should
      include("file system operations are disabled")
    // A private CA at a path of its own is refused at the first HTTPS request.
    val privateCa = outside.resolve("private-ca.pem")
    Files.writeString(privateCa, "not a certificate")
    val ca = memoryDatabase("privateca")
    pool(ca, "privateca", initSql = s"SET GLOBAL ca_cert_file = '$privateCa';\n")
    refusal(
      show(
        "ca_cert_file at a path of its own",
        su("privateca", "SELECT count(*) FROM read_parquet('https://127.0.0.1:9/x.parquet')")
      )
    ) should include(s"Cannot access file \"$privateCa\"")
  }

  "a node's life" should "cover a restart, the health probe and qod_authz_stats" in {
    ready()
    val td = s3Database("life")
    pool(td, "life")
    rows(pat("life", s"CREATE TABLE $td.main.t AS SELECT 1 AS id"))
    restart(td, "life")
    rows(pat("life", s"SELECT count(*) FROM $td.main.t")) shouldBe List("1")
    rows(su("life", "SELECT current_setting('enable_external_access')")) shouldBe List("false")
    // The leader's scrape exports the node's counters only after a healthy probe that read them.
    val exported = stack.await("qod_authz_stats in the metrics", 60.seconds) {
      val (metricsCode, metrics) = stack.getStatus("/metrics", "none")
      Option.when(metricsCode == 200 && metrics.contains("qod_node_authz_screen_calls_total"))(
        metrics
      )
    }
    exported.linesIterator.count(_.startsWith("qod_node_authz_screen_calls_total")) should be > 0
  }

  // ---------- a node that does not show it ----------

  private def poolInfo(name: String): Json =
    stack
      .get("/api/pool/list")
      .hcursor
      .downField("pools")
      .values
      .toList
      .flatten
      .find(_.hcursor.get[String]("pool").contains(name))
      .getOrElse(fail(s"pool $name is not listed"))

  private def spawnsOf(name: String): Int =
    if Files.exists(spawnLog) then Files.readString(spawnLog).linesIterator.count(_.contains(name))
    else 0

  "a node that does not apply the setting" should "not be routed to, say why, and stay as it is" in {
    ready()
    // A node the same manager started with the switch applied: the case can fail.
    val switched = memoryDatabase("switched")
    pool(switched, "switched")

    val held = memoryDatabase("noswitch")
    openPool(held, "noswitch")
    val detail = stack.await("the probe to hold the node out", 60.seconds) {
      poolInfo("noswitch").hcursor.get[String]("statusDetail").toOption
    }
    note(s"statusDetail: $detail")
    detail should include("QOD_NODE_ALLOW_LOCAL_FILES=false")
    detail should include("its enable_external_access reads true")
    detail should include("restart the node")
    detail should include("upgrade its image or fleet server")
    // Node health is not what `status` says: a blocked pool is one whose nodes never start.
    poolInfo("noswitch").hcursor.get[String]("status") shouldBe Right("ready")
    val nodeOf = poolInfo("noswitch").hcursor.downField("nodes").downArray
    nodeOf.get[Boolean]("healthy") shouldBe Right(false)
    val nodeId = nodeOf.get[String]("nodeId").getOrElse(fail("no node listed"))

    // As for no routable node, for anyone.
    refusal(show("a superuser's statement", su("noswitch", "SELECT 1"))) should
      include("no node with role")
    refusal(show("a plain user's statement", pat("noswitch", "SELECT 1"))) should
      include("no node with role")

    // In the same run, on the same manager, the node with the switch applied passes.
    rows(su("switched", "SELECT current_setting('enable_external_access')")) shouldBe List("false")
    stack.await("the switched node healthy", 30.seconds) {
      Option.when(
        poolInfo("switched").hcursor
          .downField("nodes")
          .downArray
          .get[Boolean]("healthy")
          .contains(true)
      )(())
    }
    poolInfo("switched").hcursor.get[String]("statusDetail").isLeft shouldBe true

    // It stays as it is: three reconcile passes start nothing, since the process lives. On a server
    // whose release never applies the setting, a restart here would never end.
    spawnsOf("noswitch") shouldBe 1
    Thread.sleep(7000)
    spawnsOf("noswitch") shouldBe 1
    poolInfo("noswitch").hcursor.downField("nodes").downArray.get[String]("nodeId") shouldBe
      Right(nodeId)
    poolInfo("noswitch").hcursor.get[String]("statusDetail") shouldBe Right(detail)

    // Restarted on a script that applies it, the node serves and the listing says nothing more.
    Files.writeString(noswitchFixed, "")
    val (code, body) = stack.postStatus(
      "/api/node/restart",
      obj(
        "tenant"   -> s(Acme),
        "tenantDb" -> s(held),
        "pool"     -> s("noswitch"),
        "nodeId"   -> s(nodeId)
      ),
      timeoutSec = 120
    )
    withClue(body.take(300))(code shouldBe 200)
    stack.await("the restarted node serving", 120.seconds) {
      su("noswitch", "SELECT current_setting('enable_external_access')").toOption
    } shouldBe List("false")
    stack.await("the listing to say nothing more", 30.seconds) {
      Option.when(poolInfo("noswitch").hcursor.get[String]("statusDetail").isLeft)(())
    }
    spawnsOf("noswitch") shouldBe 2
  }

  // ---------- branches and maintenance ----------

  "a branch of a database on the store" should "create, propose and merge, then maintenance runs" in {
    ready()
    val td = s3Database("br")
    pool(td, "br")
    val token = {
      stack.post(
        "/api/user/create",
        obj(
          "tenant"   -> s(Acme),
          "username" -> s("tess"),
          "password" -> s("Tess-Pw-3391"),
          "kind"     -> s("admin")
        )
      )
      stack.login(Some(Acme), "tess", "Tess-Pw-3391")
    }
    rows(su("br", s"CREATE TABLE $td.main.fact (id BIGINT, note VARCHAR, qty INTEGER)"))
    rows(su("br", s"INSERT INTO $td.main.fact SELECT i, 'n', 0 FROM range(10000) r(i)"))
    stack.post(
      "/api/branch/create",
      obj("tenant" -> s(Acme), "tenantDb" -> s(td), "name" -> s("feature"))
    )
    stack.await("the branch serving", 120.seconds) {
      stack
        .flight(
          "SELECT 1",
          "admin",
          "admin",
          Acme,
          "br",
          superuser = true,
          branch = Some("feature")
        )
        .toOption
    }
    def onBranch(sql: String) =
      rows(
        stack.flight(sql, "admin", "admin", Acme, "br", superuser = true, branch = Some("feature"))
      )
    onBranch(s"UPDATE $td.main.fact SET note = 'b' WHERE id < 100")
    onBranch(s"DELETE FROM $td.main.fact WHERE id BETWEEN 1000 AND 1009")
    rows(su("br", s"UPDATE $td.main.fact SET qty = 9 WHERE id BETWEEN 50 AND 149"))
    val body     = obj("tenant" -> s(Acme), "tenantDb" -> s(td), "branch" -> s("feature"))
    val proposed = stack.post("/api/branch/propose", body, token)
    val id = proposed.hcursor.downField("merge").get[String]("id").getOrElse(fail("no proposal id"))
    val merged = stack.post(
      "/api/branch/merge",
      obj(
        "tenant"     -> s(Acme),
        "tenantDb"   -> s(td),
        "branch"     -> s("feature"),
        "proposalId" -> s(id)
      ),
      timeoutSec = 180
    )
    merged.hcursor.downField("merge").get[String]("status") shouldBe Right("merged")
    rows(su("br", s"SELECT note, qty FROM $td.main.fact WHERE id = 60")) shouldBe List("b,9")
    rows(
      su("br", s"SELECT count(*) FROM $td.main.fact WHERE id BETWEEN 1000 AND 1009")
    ) shouldBe List("0")
    val run = maintenanceRun(td, "flush,expire,merge,cleanup,orphans")
    note(s"a whole maintenance run: ${run.noSpaces.take(600)}")
    run.hcursor.get[String]("status") shouldBe Right("succeeded")
  }

  // ---------- rows that need local files ----------

  "a database on a local path" should "be refused at create for a superuser, with a code of its own" in {
    ready()
    val (code, body) = stack.postStatus(
      "/api/database/create",
      obj(
        "tenant"   -> s(Acme),
        "name"     -> s("onlocal"),
        "kind"     -> s("ducklake"),
        "dataPath" -> s(s"$work/onlocal/")
      )
    )
    note(s"create on a local path: $code ${body.take(300)}")
    code shouldBe 400
    body should include("local_files_off")
    body should include("QOD_NODE_ALLOW_LOCAL_FILES")
    val (fileCode, fileBody) = stack.postStatus(
      "/api/database/create",
      obj(
        "tenant"    -> s(Acme),
        "name"      -> s("onfile"),
        "kind"      -> s("duckdb-file"),
        "dataPath"  -> s(s"$work/onfile.duckdb"),
        "metastore" -> Json.obj("dbName" -> s("onfile"), "schemaName" -> s("main"))
      )
    )
    fileCode shouldBe 400
    fileBody should include("local_files_off")
  }

  it should "be blocked when a manifest brings it: it imports, reports why, and no node starts" in {
    ready()
    val tenant = s"lfimport$run"
    val td     = s"${tenant}_lake"
    stack.catalogDatabases += td
    val manifest =
      s"""apiVersion: ${ai.starlake.quack.ondemand.manifest.ConfigManifest.ApiVersion}
         |kind: ${ai.starlake.quack.ondemand.manifest.ConfigManifest.Kind}
         |exportedAt: 2026-11-04T00:00:00Z
         |exportedFrom:
         |  managerVersion: test
         |  hostname: test
         |tenants:
         |  - name: $tenant
         |    tenantDbs:
         |      - name: $td
         |        kind: ducklake
         |        dataPath: $work/imported/
         |    pools:
         |      - name: imp
         |        tenantDb: $td
         |        roleDistribution:
         |          writeonly: 0
         |          readonly: 0
         |          dual: 1
         |""".stripMargin
    val (code, body) = stack.postStatus("/api/manifest/import", manifest)
    note(s"manifest import: $code ${body.take(300)}")
    code shouldBe 200
    def imported =
      stack
        .get("/api/pool/list")
        .hcursor
        .downField("pools")
        .values
        .toList
        .flatten
        .filter(_.hcursor.get[String]("tenant").contains(tenant))
    val List(p) = imported: @unchecked
    p.hcursor.get[String]("status") shouldBe Right("blocked")
    val detail = p.hcursor.get[String]("statusDetail").getOrElse(fail("no statusDetail"))
    detail should include("QOD_NODE_ALLOW_LOCAL_FILES")
    detail should include("object storage")
    // Three reconcile passes later, no node was started.
    Thread.sleep(6000)
    imported.flatMap(_.hcursor.downField("nodes").values.toList.flatten) shouldBe Nil
  }

  it should "answer a statement that would wake its suspended pool at once, naming the setting" in {
    ready()
    val tenant   = s"lfwake$run"
    val td       = s"${tenant}_lake"
    val path     = s"$work/imported-suspended/"
    val manifest =
      s"""apiVersion: ${ai.starlake.quack.ondemand.manifest.ConfigManifest.ApiVersion}
         |kind: ${ai.starlake.quack.ondemand.manifest.ConfigManifest.Kind}
         |exportedAt: 2026-11-04T00:00:00Z
         |exportedFrom:
         |  managerVersion: test
         |  hostname: test
         |tenants:
         |  - name: $tenant
         |    tenantDbs:
         |      - name: $td
         |        kind: ducklake
         |        dataPath: $path
         |    pools:
         |      - name: sleeping
         |        tenantDb: $td
         |        suspended: true
         |        roleDistribution:
         |          writeonly: 0
         |          readonly: 0
         |          dual: 1
         |""".stripMargin
    val (code, body) = stack.postStatus("/api/manifest/import", manifest)
    withClue(body.take(300))(code shouldBe 200)
    val t0     = System.nanoTime()
    val answer =
      stack.flightCall("SELECT 1", "admin", "admin", tenant, "sleeping", superuser = true)
    val tookMs = (System.nanoTime() - t0) / 1000000
    val failed = answer.fold(identity, v => fail(s"expected a refusal, got rows $v"))
    note(s"a statement on a suspended pool of a blocked row, $tookMs ms: ${failed.getMessage}")
    failed.status().code() shouldBe org.apache.arrow.flight.FlightStatusCode.INVALID_ARGUMENT
    failed.getMessage should include("QOD_NODE_ALLOW_LOCAL_FILES=false")
    failed.getMessage should include("cannot wake")
    failed.getMessage should not include path
    // Far from the 60 s the hold would wait out.
    tookMs should be < 20000L
    val listed = poolInfo("sleeping").hcursor
    listed.get[Boolean]("suspended") shouldBe Right(true)
    listed.get[String]("status") shouldBe Right("blocked")
    listed.downField("nodes").values.toList.flatten shouldBe Nil
  }
