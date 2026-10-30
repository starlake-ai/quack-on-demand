package ai.starlake.quack.it.authz

import ai.starlake.quack.ManagedObjectStoreConfig
import ai.starlake.quack.ondemand.federation.IcebergFixture
import ai.starlake.quack.ondemand.runtime.QodNodeTestBinary
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.storage.S3ManagedStoreClient
import io.circe.Json
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path, Paths}
import scala.concurrent.duration.*
import scala.util.Try

/** The in-engine authorization cases of spec Section 10 and decisions 15-23, on a real manager and
  * real qod-node, through both front doors (FlightSQL; the native front door when a DuckDB 2.0 CLI
  * is on PATH). The node is the only layer judging grants, views, files, settings and catalogs, so
  * every refusal carries the node's `QoD: ` prefix.
  *
  * World (`run` is random per spec, so two stacks or two runs never share a name):
  *   - tenant acme_<run>: DuckLake `lake` (data under `<root>/acme_<run>/shared/lake/`, pool
  *     `lake`), duckdb-file `files` (outside the root, pool `files`), memory `fed` (Postgres and,
  *     when the Iceberg fixture is up, Iceberg federated sources, pool `fed`), memory `rofed`
  *     (`sql` sources `rosql`, read-only, and `rwsql`, writable, each a local DuckDB file attached
  *     READ_WRITE, and `pgro`, a read-only typed Postgres source; pool `rofed`), duckdb-file
  *     `aliased` (catalog alias `e2e_alias_<run>`, pool `aliased`) and, with rustfs up, DuckLake
  *     `s3lake` (data in the spec's bucket, pool `s3lake`); authorized folder
  *     `<root>/acme_<run>/shared/`;
  *   - tenant globex_<run>: two DuckLake database rows, no pool, whose data folders lie inside
  *     acme's authorized folder and inside Alice's S3 credential scope;
  *   - alice (acme): role `e2e_reader` (RO lake orders, RW lake events, RO the plain view
  *     `v_secret`, RO the secure views, RW the federated tables (the read-only `rosql.main.t`
  *     included), RO the aliased and s3lake tables), group `qod_all_pools`;
  *   - bob, carol, dave (acme): the defaults, `qod_all_tables` (`*.*.* ALL`) and `qod_all_pools`;
  *   - S3 credentials of their own (decision 23): Alice and the admin for `s3://qod-authz-<run>/`
  *     and `s3://warehouse/` on rustfs (when it is up); Alice, Bob and Carol for the [[FakeS3]]
  *     bucket `fake-shared`, which refuses Carol's key; Dave none.
  *
  * Cancelled without the test Postgres or a qod-node build; the S3, Iceberg and native front door
  * cases cancel on their own prerequisite.
  */
abstract class AuthzEndToEndCases(
    stackName: String,
    restPort: Int,
    flightPort: Int,
    quackPort: Int,
    relayPort: Int,
    minNodePort: Int,
    maxNodePort: Int
) extends AnyFlatSpec
    with Matchers
    with BeforeAndAfterAll:

  private val run    = java.lang.Long.toHexString(System.nanoTime() & 0xffffffL)
  private val Acme   = s"acme_${stackName}_$run"
  private val Globex = s"globex_${stackName}_$run"
  private val Bucket = s"qod-authz-$run"
  private val Alias  = s"e2e_alias_$run"

  private val AlicePw   = "alice-e2e-pw-1"
  private val BobPw     = "bob-e2e-pw-1"
  private val CarolPw   = "carol-e2e-pw-1"
  private val DavePw    = "dave-e2e-pw-1"
  private val Passwords =
    Map("alice" -> AlicePw, "bob" -> BobPw, "carol" -> CarolPw, "dave" -> DavePw)

  private val Rustfs = "http://localhost:9000"

  // The spec's own S3 endpoint (decision 23): its bucket admits Alice's and Bob's keys, refuses
  // Carol's, and logs whose key signed every request.
  private val FakeBucket = "fake-shared"
  private val FakeUrl    = s"s3://$FakeBucket/x.csv"
  private val fake       = new FakeS3(
    Map(FakeBucket            -> Set("AKIAALICEE2E", "AKIABOBE2E", "AKIADAVELATE")),
    Map((FakeBucket, "x.csv") -> "id\n1\n2\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))
  )

  private lazy val missing: Option[String] =
    if scala.util.Properties.isWin then Some("the bash spawn script and kill-quack-nodes.sh")
    else if !TestPostgres.reachable then
      Some(s"test Postgres not reachable at ${TestPostgres.pgHost}:${TestPostgres.pgPort}")
    else if QodNodeTestBinary.path.isEmpty then Some("no qod-node build (QOD_NODE_BIN)")
    else None

  // The real paths: the node resolves symlinks before judging (macOS /var is /private/var).
  private lazy val root: Path = Files.createTempDirectory("qod-authz-files").toRealPath()
  private lazy val work: Path = Files.createTempDirectory("qod-authz-work").toRealPath()
  // acme's authorized folder, strictly inside <root>/<tenant>/, holding a plain input and output
  // folder and two DuckLake data folders (acme's own, and another tenant's).
  private def shared: Path     = root.resolve(Acme).resolve("shared")
  private def inDir: Path      = shared.resolve("in")
  private def outDir: Path     = shared.resolve("out")
  private def lakeData: Path   = shared.resolve("lake")
  private def globexLake: Path = shared.resolve("globex_lake")

  private var stack: AuthzStack     = null
  private var lake: String          = "" // acme's DuckLake tenant-db, also its catalog alias
  private var files: String         = "" // acme's duckdb-file tenant-db
  private var fed: String           = "" // acme's memory tenant-db with the federated sources
  private var rofed: String         = "" // acme's memory tenant-db with the read-only sources
  private var aliased: String       = "" // acme's duckdb-file tenant-db attached as Alias
  private var s3lake: String        = "" // acme's DuckLake tenant-db in the spec's bucket
  private var ordersGrant: String   = "" // alice's RO grant on lake orders, revoked and restored
  private var roleId: String        = ""
  private var s3Ready: Boolean      = false
  private var icebergReady: Boolean = false
  private val pgDb                  = s"qod_pg_${stackName}_$run"
  private val iceNs                 = s"qod_authz_$run"

  private val s3Store = Map(
    "s3_endpoint"          -> "http://localhost:9000",
    "s3_access_key_id"     -> "admin",
    "s3_secret_access_key" -> "password",
    "s3_region"            -> "us-east-1",
    "s3_url_style"         -> "path"
  )
  private def storeJson: Json = Json.obj(s3Store.map((k, v) => k -> Json.fromString(v)).toSeq*)

  // ---------- setup ----------

  override def beforeAll(): Unit =
    if missing.isEmpty then
      fake.start()
      stack = new AuthzStack(
        stackName,
        restPort,
        flightPort,
        quackPort,
        relayPort,
        minNodePort,
        maxNodePort,
        filesRoot = root,
        userS3Endpoints = s"$Rustfs,${fake.endpoint}",
        // Tenant catalogs and the Postgres source database of an earlier run of this stack.
        stackPrefixes =
          List(s"acme_${stackName}_", s"globex_${stackName}_", s"qod_pg_${stackName}_")
      )
      stack.boot()
      provisionFiles()
      s3Ready = bucket(Bucket).isRight
      icebergReady = s3Ready && IcebergFixture.reachable
      provisionTenants()
      provisionCredentials()
      provisionObjects()

  override def afterAll(): Unit =
    try if stack != null then stack.stop()
    finally
      Try(fake.stop())
      Try(TestPostgres.dropDatabase(pgDb))
      List(root, work).foreach { d =>
        Try {
          val tree = Files.walk(d)
          try tree.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
          finally tree.close()
        }
      }

  /** Creates `name` on rustfs, private (no policy set), with the fixture's keys. */
  private def bucket(name: String): Either[String, Unit] =
    new S3ManagedStoreClient(
      ManagedObjectStoreConfig(
        enabled = true,
        endpoint = Rustfs,
        bucket = name,
        accessKeyId = "admin",
        secretAccessKey = "password"
      )
    ).ensureBucket()

  private def provisionFiles(): Unit =
    Files.createDirectories(inDir)
    Files.createDirectories(outDir)
    Files.createDirectories(lakeData)
    Files.createDirectories(globexLake)
    Files.writeString(inDir.resolve("x.csv"), "id,v\n1,a\n2,b\n")
    Files.writeString(inDir.resolve("events.csv"), "id,v\n7,f\n")
    // A link inside the authorized folder that leads out of it.
    Files.createSymbolicLink(inDir.resolve("escape"), Paths.get("/etc"))

  private def obj(fields: (String, Json)*): String = Json.obj(fields*).noSpaces
  private def s(v: String): Json                   = Json.fromString(v)

  private def createDb(tenant: String, name: String, kind: String, extra: (String, Json)*): String =
    val created = stack.post(
      "/api/database/create",
      obj((Seq("tenant" -> s(tenant), "name" -> s(name), "kind" -> s(kind)) ++ extra)*)
    )
    val td = created.hcursor.get[String]("name").fold(e => throw e, identity)
    if kind == "ducklake" then stack.catalogDatabases += td
    td

  private def createPool(tenant: String, tenantDb: String, pool: String): Unit =
    stack.post(
      "/api/pool/create",
      s"""{"tenant":"$tenant","tenantDb":"$tenantDb","pool":"$pool","size":1,
         |"roleDistribution":{"writeonly":0,"readonly":0,"dual":1}}""".stripMargin
    )

  private def grant(
      catalog: String,
      schema: String,
      table: String,
      verb: String,
      role: => String = roleId
  ): String =
    stack
      .post(
        "/api/role/permission/grant",
        obj(
          "roleId"  -> s(role),
          "catalog" -> s(catalog),
          "schema"  -> s(schema),
          "table"   -> s(table),
          "verb"    -> s(verb)
        )
      )
      .hcursor
      .get[String]("id")
      .fold(e => throw e, identity)

  private def provisionTenants(): Unit =
    List(Acme, Globex).foreach { t =>
      stack.post(
        "/api/tenant/create",
        obj("id" -> s(t), "displayName" -> s(t), "metastore" -> Json.obj())
      )
    }
    lake = createDb(Acme, "lake", "ducklake", "dataPath" -> s(s"$lakeData/"))
    // A duckdb-file database names its catalog: the tenant-db name, as the other kinds get.
    files = createDb(
      Acme,
      "files",
      "duckdb-file",
      "dataPath"  -> s(s"$work/acme_files.duckdb"),
      "metastore" -> Json.obj("dbName" -> s(s"${Acme}_files"), "schemaName" -> s("main"))
    )
    require(files == s"${Acme}_files", s"tenant-db $files is not named as its catalog")
    fed = createDb(Acme, "fed", "memory")
    rofed = createDb(Acme, "rofed", "memory")
    aliased = createDb(
      Acme,
      "aliased",
      "duckdb-file",
      "dataPath"  -> s(s"$work/acme_aliased.duckdb"),
      "metastore" -> Json.obj(
        "dbName"       -> s(s"${Acme}_aliased"),
        "schemaName"   -> s("main"),
        "catalogAlias" -> s(Alias)
      )
    )
    if s3Ready then
      s3lake = createDb(
        Acme,
        "s3lake",
        "ducklake",
        "dataPath"    -> s(s"s3://$Bucket/acme_lake/"),
        "objectStore" -> storeJson
      )
    // Another tenant's DuckLake data, inside acme's authorized folder and Alice's S3 scope: rows only.
    createDb(Globex, "glake", "ducklake", "dataPath" -> s(s"$globexLake/"))
    if s3Ready then
      createDb(
        Globex,
        "gs3",
        "ducklake",
        "dataPath"    -> s(s"s3://$Bucket/globex_lake/"),
        "objectStore" -> storeJson
      )
    // The tenant's folder before the pools (a local folder needs no node secret).
    stack.post("/api/tenant/location/add", obj("tenant" -> s(Acme), "location" -> s(s"$shared/")))
    provisionFederation()
    provisionReadOnlyFederation()
    createPool(Acme, lake, "lake")
    createPool(Acme, files, "files")
    createPool(Acme, fed, "fed")
    createPool(Acme, rofed, "rofed")
    createPool(Acme, aliased, "aliased")
    if s3Ready then createPool(Acme, s3lake, "s3lake")
    roleId = createRole("e2e_reader")
    // Owner of the Postgres schema e2e (CREATE INDEX there): `*.*.*` covers only the tenant's own
    // database catalogs, never a federated one.
    val pgOwner = createRole("e2e_pg_owner")
    grant("pgfed", "e2e", "*", "ALL", pgOwner)
    ordersGrant = grant(lake, "main", "orders", "RO")
    grant(lake, "main", "events", "RW")
    grant(lake, "main", "v_secret", "RO")
    List("sv_hidden", "sv_var", "sv_setting").foreach(v => grant(files, "main", v, "RO"))
    grant("pgfed", "e2e", "orders", "RW")
    grant(Alias, "main", "aliased_t", "RO")
    if s3Ready then grant(s3lake, "main", "t", "RO")
    if icebergReady then grant("ice", iceNs, "t", "RW")
    // A grant opens no read-only catalog (decision 24): alice holds RW on both sql tables.
    grant("rosql", "main", "t", "RW")
    grant("rwsql", "main", "t", "RW")
    stack.post(
      "/api/user/create",
      obj(
        "tenant"   -> s(Acme),
        "username" -> s("alice"),
        "password" -> s(AlicePw),
        "kind"     -> s("user"),
        "roles"    -> Json.arr(s("e2e_reader")),
        "groups"   -> Json.arr(s("qod_all_pools"))
      )
    )
    List("bob" -> BobPw, "dave" -> DavePw).foreach { (user, pw) =>
      stack.post(
        "/api/user/create",
        obj("tenant" -> s(Acme), "username" -> s(user), "password" -> s(pw), "kind" -> s("user"))
      )
    }
    stack.post(
      "/api/user/create",
      obj(
        "tenant"   -> s(Acme),
        "username" -> s("carol"),
        "password" -> s(CarolPw),
        "kind"     -> s("user"),
        "roles"    -> Json.arr(s("qod_all_tables"), s("e2e_pg_owner"))
      )
    )

  private def createRole(name: String): String =
    stack
      .post("/api/role/create", obj("tenant" -> s(Acme), "name" -> s(name)))
      .hcursor
      .get[String]("id")
      .fold(e => throw e, identity)

  /** A session of `user` (acme), for the calls a user makes from their own profile. */
  private def session(user: String): String = stack.login(Some(Acme), user, Passwords(user))

  private def credentialJson(
      scope: String,
      keyId: String,
      secret: String,
      endpoint: String
  ): String =
    obj(
      "scope"           -> s(scope),
      "accessKeyId"     -> s(keyId),
      "secretAccessKey" -> s(secret),
      "region"          -> s("us-east-1"),
      "endpoint"        -> s(endpoint),
      "urlStyle"        -> s("path")
    )

  /** A credential of the caller of `token`'s own (decision 23); its id. */
  private def addCredential(
      token: String,
      scope: String,
      keyId: String,
      secret: String,
      endpoint: String
  ): String =
    stack
      .post("/api/profile/s3/add", credentialJson(scope, keyId, secret, endpoint), token)
      .hcursor
      .get[String]("id")
      .fold(e => throw e, identity)

  /** Each user's own S3 credentials, from their own sessions (the admin's from a system-realm
    * session). Before the objects: the admin writes into the spec's bucket and creates the Iceberg
    * tables with its own keys.
    */
  private def provisionCredentials(): Unit =
    val alice = session("alice")
    val admin = stack.login(None, "admin", "admin")
    addCredential(alice, s"s3://$FakeBucket/", "AKIAALICEE2E", "alice-fake-secret", fake.endpoint)
    addCredential(
      session("bob"),
      s"s3://$FakeBucket/",
      "AKIABOBE2E",
      "bob-fake-secret",
      fake.endpoint
    )
    addCredential(
      session("carol"),
      s"s3://$FakeBucket/",
      "AKIACAROLE2E",
      "carol-fake-secret",
      fake.endpoint
    )
    if s3Ready then
      List(alice, admin).foreach(t =>
        addCredential(t, s"s3://$Bucket/", "admin", "password", Rustfs)
      )
    if icebergReady then
      List(alice, admin).foreach(t =>
        addCredential(t, "s3://warehouse/", "admin", "password", Rustfs)
      )

  /** The Postgres federated source (a database of the test Postgres, its password a federated
    * secret) and, when the fixture is up, the Iceberg REST catalog, both read-write. Created by the
    * seeded superuser.
    */
  private def provisionFederation(): Unit =
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$pgDb"""")
    TestPostgres.psql(
      pgDb,
      "CREATE SCHEMA e2e; CREATE TABLE e2e.orders (id int, v text); " +
        "INSERT INTO e2e.orders VALUES (1, 'a'), (2, 'b'); CREATE TABLE e2e.other (x int)"
    )
    val base = s"/api/tenants/$Acme/tenant-dbs/$fed/federated-sources"
    stack.post(
      base,
      obj(
        "alias"      -> s("pgfed"),
        "disabled"   -> Json.False,
        "sourceType" -> s("postgres"),
        "readOnly"   -> Json.False,
        "connection" -> Json.obj(
          "host"     -> s(TestPostgres.pgHost),
          "port"     -> Json.fromInt(TestPostgres.pgPort),
          "database" -> s(pgDb),
          "user"     -> s(TestPostgres.pgUser),
          "password" -> s("{{secret.PG_PWD}}"),
          "sslMode"  -> s("disable")
        )
      )
    )
    stack.put(s"$base/pgfed/secrets", obj("name" -> s("PG_PWD"), "value" -> s(TestPostgres.pgPass)))
    if icebergReady then
      stack.post(
        base,
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

  /** On `rofed`: `rosql`, a `sql` source attaching a local DuckDB file READ_WRITE but flagged
    * read-only in QoD, so only QoD's flag protects it (decision 24); `rwsql`, its writable twin;
    * and `pgro`, a read-only typed Postgres source on the spec's Postgres database. Each sql source
    * creates its table `t` (one row, 1) on the node's first start. Created by the seeded superuser.
    */
  private def provisionReadOnlyFederation(): Unit =
    val base = s"/api/tenants/$Acme/tenant-dbs/$rofed/federated-sources"
    List("rosql" -> true, "rwsql" -> false).foreach { (alias, readOnly) =>
      stack.post(
        base,
        obj(
          "alias"      -> s(alias),
          "disabled"   -> Json.False,
          "sourceType" -> s("sql"),
          "readOnly"   -> Json.fromBoolean(readOnly),
          "setupSql"   -> s(
            s"ATTACH '${work.resolve(s"$alias.duckdb")}' AS {{alias}};\n" +
              "CREATE TABLE IF NOT EXISTS {{alias}}.main.t AS SELECT 1 AS v;"
          )
        )
      )
    }
    stack.post(
      base,
      obj(
        "alias"      -> s("pgro"),
        "disabled"   -> Json.False,
        "sourceType" -> s("postgres"),
        "readOnly"   -> Json.True,
        "connection" -> Json.obj(
          "host"     -> s(TestPostgres.pgHost),
          "port"     -> Json.fromInt(TestPostgres.pgPort),
          "database" -> s(pgDb),
          "user"     -> s(TestPostgres.pgUser),
          "password" -> s("{{secret.PG_PWD}}"),
          "sslMode"  -> s("disable")
        )
      )
    )
    stack.put(s"$base/pgro/secrets", obj("name" -> s("PG_PWD"), "value" -> s(TestPostgres.pgPass)))

  /** `sql` as the system admin, through the superuser header; throws with the error. */
  private def admin(pool: String, sql: String): List[String] =
    stack
      .flight(sql, "admin", "admin", Acme, pool, superuser = true)
      .fold(err => throw new IllegalStateException(s"admin on $pool: $sql -> $err"), identity)

  /** Until the pool's node serves (its first node is still starting right after the create). */
  private def awaitPool(pool: String): Unit =
    stack.await(s"pool $pool serving", 120.seconds) {
      stack.flight("SELECT 1", "admin", "admin", Acme, pool, superuser = true).toOption
    }

  private def provisionObjects(): Unit =
    val pools = List("lake", "files", "fed", "aliased", "rofed") ++ Option.when(s3Ready)("s3lake")
    pools.foreach(awaitPool)
    admin(
      "lake",
      s"CREATE TABLE $lake.main.orders AS SELECT range AS id, 'o' || range AS v FROM range(10)"
    )
    admin("lake", s"CREATE TABLE $lake.main.events (id INTEGER, v VARCHAR)")
    admin("lake", s"CREATE TABLE $lake.main.secret AS SELECT 42 AS x")
    admin("lake", s"CREATE VIEW $lake.main.v_secret AS SELECT x FROM $lake.main.secret")
    // Files inside both data folders, so a refusal cannot be "no such file".
    admin("lake", s"COPY (SELECT 1 AS id) TO '$lakeData/probe.parquet' (FORMAT parquet)")
    admin("lake", s"COPY (SELECT 1 AS id) TO '$globexLake/x.parquet' (FORMAT parquet)")
    if s3Ready then
      admin(
        "lake",
        s"COPY (SELECT 1 AS id) TO 's3://$Bucket/globex_lake/x.parquet' (FORMAT parquet)"
      )
      admin("s3lake", s"CREATE TABLE $s3lake.main.t AS SELECT 3 AS v")
    admin("files", s"CREATE TABLE $files.main.hidden AS SELECT 7 AS h")
    admin(
      "files",
      s"CREATE SECURE VIEW $files.main.sv_hidden AS SELECT h FROM $files.main.hidden"
    )
    admin(
      "files",
      s"CREATE SECURE VIEW $files.main.sv_var AS " +
        s"SELECT h FROM $files.main.hidden WHERE h = getvariable('h')"
    )
    admin(
      "files",
      s"CREATE SECURE VIEW $files.main.sv_setting AS SELECT current_setting('TimeZone') AS tz"
    )
    admin("aliased", s"CREATE TABLE $Alias.main.aliased_t AS SELECT 5 AS v")
    admin("aliased", s"CREATE TABLE $Alias.main.aliased_u AS SELECT 6 AS v")
    if icebergReady then
      admin("fed", s"CREATE SCHEMA ice.$iceNs")
      admin("fed", s"CREATE TABLE ice.$iceNs.t (id INTEGER)")
      admin("fed", s"CREATE TABLE ice.$iceNs.u (id INTEGER)")
      // One row, written by the fixture's CLI with its own secret: no user credential can commit
      // an Iceberg write (see the Iceberg write case).
      val seeded = IcebergFixture.duckdb(
        IcebergFixture.script(
          IcebergFixture.storagePrelude,
          "LOAD iceberg;",
          s"ATTACH '${IcebergFixture.warehouse}' AS ice (TYPE iceberg, " +
            s"ENDPOINT '${IcebergFixture.endpoint}', AUTHORIZATION_TYPE 'none');",
          s"INSERT INTO ice.$iceNs.t VALUES (1);"
        )
      )
      require(seeded.code == 0, "the Iceberg seed row could not be written")

  // ---------- running a case ----------

  private enum Door:
    case Flight, Native

  private def doors: List[Door] =
    if AuthzStack.cliPresent then List(Door.Flight, Door.Native) else List(Door.Flight)

  private def ready(): Unit =
    missing.foreach(reason => cancel(s"authorization end to end skipped: $reason"))

  private def as(
      door: Door,
      user: String,
      pool: String,
      sql: String
  ): Either[String, List[String]] =
    val pw = Passwords(user)
    door match
      case Door.Flight => stack.flight(sql, user, pw, Acme, pool)
      case Door.Native => stack.door(sql, user, pw, Acme, pool)

  /** `sql` as `user` on `pool` succeeds through every door, with `rows` when given. */
  private def allowed(user: String, pool: String, sql: String, rows: Option[List[String]] = None) =
    doors.foreach { d =>
      withClue(s"[$d] $user: $sql -> ") {
        val out = as(d, user, pool, sql)
        out shouldBe a[Right[?, ?]]
        rows.foreach(r => out shouldBe Right(r))
      }
    }

  /** `sql` as `user` on `pool` is refused through every door, by the node: the error carries the
    * node's `QoD: ` prefix and `node`. `anyRefusal`: only the refusal is asserted, for a statement
    * DuckDB itself may refuse while binding, before any verdict (DDL in the system catalog).
    */
  private def refused(
      user: String,
      pool: String,
      sql: String,
      node: String = "QoD: ",
      anyRefusal: Boolean = false
  ) =
    doors.foreach { d =>
      withClue(s"[$d] $user: $sql -> ") {
        as(d, user, pool, sql) match
          case Left(err) =>
            if !anyRefusal then
              err should include("QoD: ")
              err should include(node)
          case Right(r) => fail(s"expected a refusal, got rows $r")
      }
    }

  private def requireS3(): Unit = assume(s3Ready, IcebergFixture.missingFixture)

  // ---------- files: local folders (decision 15) ----------

  "a file reader" should "read an authorized local folder" in {
    ready()
    allowed("alice", "lake", s"SELECT count(*) FROM read_csv('$inDir/x.csv')", Some(List("2")))
  }

  "COPY" should "write to and read from an authorized local folder" in {
    ready()
    doors.foreach { d =>
      val out = s"$outDir/copy-$d.csv"
      withClue(s"[$d] ") {
        as(d, "alice", "lake", s"COPY (SELECT 1 AS id) TO '$out' (HEADER)") shouldBe a[Right[?, ?]]
        as(d, "alice", "lake", s"SELECT count(*) FROM read_csv('$out')") shouldBe Right(List("1"))
      }
    }
    allowed("alice", "lake", s"COPY $lake.main.events FROM '$inDir/events.csv' (HEADER)")
  }

  it should "judge an Iceberg COPY by its location (decision 19)" in {
    ready()
    // duckdb-iceberg's COPY ... (FORMAT iceberg) is an extension operator that names itself neither
    // by name nor by extension: the describer reads its target from the statement.
    doors.foreach { d =>
      withClue(s"[$d] ") {
        as(
          d,
          "alice",
          "lake",
          s"COPY (SELECT 1 AS id) TO '$outDir/ice-$d' (FORMAT iceberg)"
        ) shouldBe
          a[Right[?, ?]]
      }
    }
    Files.isDirectory(outDir.resolve(s"ice-${Door.Flight}").resolve("metadata")) shouldBe true
    refused(
      "alice",
      "lake",
      s"COPY (SELECT 1 AS id) TO '/tmp/qod-authz-ice-$run' (FORMAT iceberg)",
      node = "outside the tenant's authorized locations"
    )
    refused(
      "alice",
      "lake",
      s"COPY (SELECT 1 AS id) TO '$lakeData/ice' (FORMAT iceberg)",
      node = "is a database data folder"
    )
    Files.exists(Paths.get(s"/tmp/qod-authz-ice-$run")) shouldBe false
  }

  "a database data folder" should "never be readable by path, its own tenant's or another's" in {
    ready()
    refused(
      "alice",
      "lake",
      s"SELECT * FROM read_parquet('$lakeData/probe.parquet')",
      node = "is a database data folder"
    )
    refused(
      "alice",
      "lake",
      s"SELECT * FROM read_parquet('$globexLake/x.parquet')",
      node = "is a database data folder"
    )
    refused(
      "bob",
      "lake",
      s"SELECT * FROM read_parquet('$lakeData/probe.parquet')",
      node = "is a database data folder"
    )
  }

  "a local path outside the authorized folders" should "be refused, however it is spelled" in {
    ready()
    refused(
      "alice",
      "lake",
      "SELECT count(*) FROM read_text('/etc/hosts')",
      node = "outside the tenant's authorized locations"
    )
    refused("alice", "lake", "SELECT count(*) FROM read_csv('x.csv')")
    refused("alice", "lake", s"SELECT count(*) FROM read_csv('$inDir/../in/x.csv')")
    refused("alice", "lake", s"SELECT count(*) FROM read_csv('$inDir//x.csv')")
    refused("alice", "lake", "COPY (SELECT 1) TO '/tmp/qod-authz-escape.csv'")
    Files.exists(Paths.get("/tmp/qod-authz-escape.csv")) shouldBe false
  }

  "a symlink the path names inside an authorized folder" should "not lead out of it" in {
    ready()
    // The node resolves the link before judging. Only a
    // link the path names: one below a glob segment or a COPY PARTITION_BY / OVERWRITE target is not
    // judged (CONTRACT item 33), and keeping tenant folders free of links is the operator's.
    refused(
      "alice",
      "lake",
      s"SELECT count(*) FROM read_text('$inDir/escape/hosts')",
      node = "outside the tenant's authorized locations"
    )
  }

  "a file in a table position" should "be a missing table, whatever its spelling" in {
    ready()
    List(
      s"SELECT count(*) FROM '$inDir/x.csv'",
      s"SELECT count(*) FROM \"$inDir/x.csv\"",
      "SELECT count(*) FROM \"x.csv\""
    ).foreach { sql =>
      doors.foreach { d =>
        withClue(s"[$d] $sql -> ") {
          as(d, "alice", "lake", sql) match
            case Left(err) => err should (include("does not exist") or include("QoD: "))
            case Right(r)  => fail(s"expected a refusal, got rows $r")
        }
      }
    }
  }

  "query()" should "have its literal screened like the statement" in {
    ready()
    refused(
      "alice",
      "lake",
      "SELECT * FROM query('SELECT count(*) FROM read_csv(''/etc/hosts'')')",
      node = "outside the tenant's authorized locations"
    )
    // A computed query() argument: the node cannot read its text, so it refuses it.
    refused(
      "alice",
      "lake",
      "SELECT * FROM query('SELECT * FROM ' || 'orders')"
    )
  }

  "a superuser" should "keep full access to the node's files" in {
    ready()
    stack.flight(
      "SELECT count(*) > 0 FROM read_text('/etc/hosts')",
      "admin",
      "admin",
      Acme,
      "lake",
      superuser = true
    ) shouldBe Right(List("true"))
  }

  // ---------- files: object store, each user's own S3 credentials (decisions 18, 23) ----------

  "a user's own S3 credential" should "let them write and read what it covers, outside every data folder" in {
    ready()
    requireS3()
    doors.foreach { d =>
      withClue(s"[$d] ") {
        val etl = s"s3://$Bucket/etl/x-$d.parquet"
        as(d, "alice", "lake", s"COPY (SELECT 1 AS id) TO '$etl' (FORMAT parquet)") shouldBe
          a[Right[?, ?]]
        as(d, "alice", "lake", s"SELECT count(*) FROM read_parquet('$etl')") shouldBe
          Right(List("1"))
        // `globex_lake2` shares a prefix with the data folder `globex_lake`, not a segment.
        val near = s"s3://$Bucket/globex_lake2/y-$d.parquet"
        as(d, "alice", "lake", s"COPY (SELECT 2 AS id) TO '$near' (FORMAT parquet)") shouldBe
          a[Right[?, ?]]
        as(d, "alice", "lake", s"SELECT id FROM read_parquet('$near')") shouldBe Right(List("2"))
      }
    }
  }

  "a DuckLake data folder under a user's credential scope" should "be refused" in {
    ready()
    requireS3()
    refused(
      "alice",
      "lake",
      s"SELECT * FROM read_parquet('s3://$Bucket/globex_lake/x.parquet')",
      node = "is a database data folder"
    )
    refused(
      "alice",
      "lake",
      s"SELECT * FROM read_parquet('s3://$Bucket/globex_lake/*.parquet')",
      node = "is a database data folder"
    )
    refused("alice", "lake", s"SELECT * FROM read_parquet('s3://$Bucket/globex%5Flake/x.parquet')")
    refused(
      "alice",
      "lake",
      s"SELECT * FROM read_parquet('s3://$Bucket/etl/../globex_lake/x.parquet')"
    )
    refused(
      "alice",
      "lake",
      s"COPY (SELECT 1) TO 's3://$Bucket/globex_lake/evil.parquet'",
      node = "is a database data folder"
    )
    // acme's own S3 DuckLake data folder, which a node of its pool signs with the database's keys.
    refused(
      "alice",
      "lake",
      s"SELECT * FROM read_parquet('s3://$Bucket/acme_lake/**/*.parquet')",
      node = "is a database data folder"
    )
  }

  "a bucket no credential of the user covers, or another scheme" should "be refused" in {
    ready()
    refused(
      "alice",
      "lake",
      s"SELECT * FROM read_parquet('s3://qod-authz-other-$run/x.parquet')",
      node = "no S3 credential of yours covers it"
    )
    // Bob's credential covers the fake's bucket, not Alice's.
    refused(
      "bob",
      "lake",
      s"SELECT * FROM read_parquet('s3://$Bucket/etl/x.parquet')",
      node = "no S3 credential of yours covers it"
    )
    refused("alice", "lake", s"SELECT * FROM read_csv('http://127.0.0.1:$restPort/health')")
    refused("alice", "lake", s"SELECT * FROM read_csv('gs://$Bucket/etl/x.csv')")
  }

  "two users with keys of their own for one bucket" should "each be signed with their own keys" in {
    ready()
    doors.foreach { d =>
      List("alice" -> "AKIAALICEE2E", "bob" -> "AKIABOBE2E").foreach { (user, keyId) =>
        withClue(s"[$d] $user: ") {
          val mark = fake.requests.size
          as(d, user, "lake", s"SELECT count(*) FROM read_csv('$FakeUrl')") shouldBe Right(
            List("2")
          )
          val seen = fake.requests.drop(mark).map(_.keyId)
          seen should not be empty
          seen.distinct shouldBe List(keyId)
        }
      }
    }
  }

  "a user without a credential" should "be refused before any request reaches S3" in {
    ready()
    val mark = fake.requests.size
    refused(
      "dave",
      "lake",
      s"SELECT count(*) FROM read_csv('$FakeUrl')",
      node = "no S3 credential of yours covers it"
    )
    fake.requests.drop(mark) shouldBe empty
  }

  "the node's file caches" should "never hand one user what another read" in {
    ready()
    doors.foreach { d =>
      withClue(s"[$d] ") {
        as(d, "alice", "lake", s"SELECT count(*) FROM read_csv('$FakeUrl')") shouldBe Right(
          List("2")
        )
        // Carol's credential covers the bucket, but the bucket refuses her key: with a cache shared
        // across connections she would be served Alice's bytes.
        val mark = fake.requests.size
        as(d, "carol", "lake", s"SELECT count(*) FROM read_csv('$FakeUrl')") match
          case Left(err) => err should include("403")
          case Right(r)  => fail(s"carol read the file with keys the bucket refuses: $r")
        fake.requests.drop(mark).map(_.keyId) should contain("AKIACAROLE2E")
      }
    }
  }

  "a user's credential on an object-store pool" should "sign their own reads beside the pool's data secret" in {
    ready()
    requireS3()
    // The s3lake pool's data lives in the spec's bucket, under the node's data-path secret; Alice's
    // own connection secret signs her read of another bucket.
    doors.foreach { d =>
      withClue(s"[$d] ") {
        val mark = fake.requests.size
        as(d, "alice", "s3lake", s"SELECT count(*) FROM read_csv('$FakeUrl')") shouldBe Right(
          List("2")
        )
        fake.requests.drop(mark).map(_.keyId).distinct shouldBe List("AKIAALICEE2E")
      }
    }
    allowed("alice", "s3lake", s"SELECT v FROM $s3lake.main.t", Some(List("3")))
    // The pool's own DuckLake data prefix would be signed with the node's data-path secret, never
    // with her credential: this node refuses the read on that ground (the lake pool's node refuses
    // the same prefix as a database data folder, above).
    refused(
      "alice",
      "s3lake",
      s"SELECT * FROM read_parquet('s3://$Bucket/acme_lake/**/*.parquet')",
      node = "would be signed with this node's own S3 keys"
    )
  }

  "secrets, the S3 settings and an s3:// ATTACH" should "be the superusers'" in {
    ready()
    refused(
      "alice",
      "lake",
      s"CREATE SECRET mine IN connection (TYPE s3, KEY_ID 'k', SECRET 's', SCOPE 's3://$Bucket/')",
      node = "secrets are reserved to superusers"
    )
    refused(
      "alice",
      "lake",
      "DROP SECRET IF EXISTS mine",
      node = "secrets are reserved to superusers"
    )
    // s3_* settings and the external file cache are reserved to superusers on the node (Task 8).
    refused("alice", "lake", "SET s3_endpoint = 'evil.example.com'")
    refused("alice", "lake", "SET enable_external_file_cache = true")
    refused(
      "alice",
      "lake",
      s"ATTACH 's3://$Bucket/x.duckdb' AS x (READ_ONLY)"
    )
  }

  "a superuser" should "reach S3 with a credential of their own" in {
    ready()
    requireS3()
    val path = s"s3://$Bucket/su/x.parquet"
    stack.flight(
      s"COPY (SELECT 9 AS id) TO '$path' (FORMAT parquet)",
      "admin",
      "admin",
      Acme,
      "lake",
      superuser = true
    ) shouldBe a[Right[?, ?]]
    stack.flight(
      s"SELECT id FROM read_parquet('$path')",
      "admin",
      "admin",
      Acme,
      "lake",
      superuser = true
    ) shouldBe Right(List("9"))
  }

  // ---------- settings (decisions 11, 17) ----------

  "a session setting" should "be allowed" in {
    ready()
    allowed("alice", "lake", "SET TimeZone = 'UTC'")
  }

  "the extension settings" should "be refused to users and false on every node" in {
    ready()
    List(
      "SET allow_community_extensions = true",
      "SET allow_unsigned_extensions = true",
      "SET autoinstall_known_extensions = true"
    ).foreach(sql => refused("alice", "lake", sql))
    stack.flight(
      "SELECT current_setting('allow_community_extensions'), " +
        "current_setting('allow_unsigned_extensions'), " +
        "current_setting('autoinstall_known_extensions')",
      "admin",
      "admin",
      Acme,
      "lake",
      superuser = true
    ) shouldBe Right(List("false,false,false"))
  }

  "an optimizer-disabling or global setting" should "be refused" in {
    ready()
    refused("alice", "lake", "SET disabled_optimizers = 'filter_pushdown'")
    refused("alice", "lake", "SET GLOBAL TimeZone = 'UTC'")
  }

  // ---------- grants, views (decisions 3-4) ----------

  "a granted table" should "be readable and writable as granted" in {
    ready()
    allowed("alice", "lake", s"SELECT count(*) FROM $lake.main.orders", Some(List("10")))
    allowed("alice", "lake", s"INSERT INTO $lake.main.events VALUES (1, 'a')")
    refused("alice", "lake", s"INSERT INTO $lake.main.orders VALUES (99, 'x')")
    refused("alice", "lake", s"DROP TABLE $lake.main.events")
    refused("alice", "lake", s"SELECT * FROM $lake.main.secret")
  }

  "SHOW TABLES" should "list every table, unchanged, for a user granted every table" in {
    ready()
    // T19 N5: the gateway rewrites SHOW TABLES only where the metadata filter narrows a listing.
    doors.foreach { d =>
      withClue(s"[$d] ") {
        val out = as(d, "bob", "lake", "SHOW TABLES")
        out shouldBe a[Right[?, ?]]
        out.toOption.get.map(_.split(",").head) should contain allOf (
          "events",
          "orders",
          "secret",
          "v_secret"
        )
      }
    }
  }

  "a plain view" should "need grants on its base tables" in {
    ready()
    // alice holds RO on the view only: the node reads the expanded plan and refuses the base table.
    refused("alice", "lake", s"SELECT * FROM $lake.main.v_secret")
  }

  "a secure view" should "be covered by its own read grant" in {
    ready()
    allowed("alice", "files", s"SELECT h FROM $files.main.sv_hidden", Some(List("7")))
    refused("alice", "files", s"SELECT h FROM $files.main.hidden")
  }

  it should "be readable through an ATTACHed front door: the client sets no session option" in {
    ready()
    assume(AuthzStack.cliPresent, "no DuckDB 2.0 CLI on PATH")
    // A secure view boundary is refused while the connection holds any session setting other than
    // schema and search_path: the quack client's attach-time statements and the node's own code
    // must set none on a client connection (Task 9 fix round 1).
    stack.attached("SELECT h FROM q.main.sv_hidden;", "alice", AlicePw, Acme, "files") shouldBe
      Right(List("7"))
  }

  it should "refuse current_setting() inside its boundary, and a session variable steering it" in {
    ready()
    // Local refusals of the describer (Task 9).
    refused(
      "alice",
      "files",
      s"SELECT * FROM $files.main.sv_setting",
      node = "current_setting() inside a secure view"
    )
    // getvariable() folds to a constant at bind, so the describer refuses any boundary while the
    // session holds a user variable; without one the body's getvariable('h') is NULL.
    refused(
      "alice",
      "files",
      s"SET VARIABLE h = 7; SELECT * FROM $files.main.sv_var"
    )
    allowed("alice", "files", s"SELECT count(*) FROM $files.main.sv_var", Some(List("0")))
  }

  "temp tables" should "work for every principal" in {
    ready()
    val sql = "CREATE TEMP TABLE tt AS SELECT 1 AS a; SELECT a FROM tt"
    allowed("bob", "lake", sql, Some(List("1")))
    allowed("alice", "lake", sql, Some(List("1")))
  }

  "DDL in the system catalog" should "be refused under *.*.* ALL" in {
    ready()
    refused("bob", "lake", "CREATE TABLE system.main.qod_e2e (a INTEGER)", anyRefusal = true)
    refused(
      "bob",
      "lake",
      "CREATE VIEW system.main.qod_e2e_v AS SELECT 1 AS a",
      anyRefusal = true
    )
  }

  "an unclassified function" should "be refused" in {
    ready()
    val loaded =
      stack.flight("INSTALL fts", "admin", "admin", Acme, "lake", superuser = true).isRight &&
        stack.flight("LOAD fts", "admin", "admin", Acme, "lake", superuser = true).isRight
    assume(loaded, "the node could not install the fts extension (no network access?)")
    refused(
      "alice",
      "lake",
      "SELECT stem('running', 'porter')",
      node = "function stem is not classified"
    )
  }

  "the calls of a statement" should "reach the node's description on a DuckLake-on-Postgres pool" in {
    ready()
    // Addendum 4 item 32 A: with remote_pushdown disabled, DuckDB no longer folds a constant call
    // before the binder, so two statements that differ only by the function they call are two plans
    // the node decides, not one cached constant projection.
    // A description carries no constant: each door calls functions of its own, so neither reuses the
    // other's verdict.
    val calls =
      Map(Door.Flight -> ("ltrim(' a')", "rtrim('a ')"), Door.Native -> ("ascii('a')", "chr(65)"))
    doors.foreach { d =>
      withClue(s"[$d] ") {
        val (one, two) = calls(d)
        val before     = stack.relay.decideCalls
        as(d, "alice", "lake", s"SELECT $one") shouldBe a[Right[?, ?]]
        val first = stack.relay.decideCalls
        as(d, "alice", "lake", s"SELECT $two") shouldBe a[Right[?, ?]]
        withClue(s"decision calls for $one then $two: ") {
          first should be > before
          stack.relay.decideCalls should be > first
        }
      }
    }
  }

  "a catalog macro shadowing a built-in" should "make the built-in's name unclassified for users" in {
    ready()
    // Addendum 4 item 32 B: a macro in the pool's DuckLake catalog (on Postgres) bearing a classified
    // name is what DuckDB binds an unqualified call to, in the statement and in a view's body read
    // later, so the node judges the entry the name resolves to, on the client's own context. Planted by
    // the superuser, removed after.
    admin("lake", s"CREATE VIEW $lake.main.v_lev AS SELECT levenshtein('kitten', 'sitting') AS d")
    grant(lake, "main", "v_lev", "RO")
    allowed("alice", "lake", s"SELECT d FROM $lake.main.v_lev", Some(List("3")))
    admin("lake", s"CREATE MACRO $lake.main.levenshtein(a, b) AS 42")
    try
      val shadowed =
        s"function levenshtein is not classified: it resolves to $lake.main.levenshtein"
      refused(
        "alice",
        "lake",
        "SELECT levenshtein('kitten', 'sitting')",
        node = shadowed
      )
      refused(
        "alice",
        "lake",
        s"SELECT $lake.main.levenshtein('kitten', 'sitting')",
        node = shadowed
      )
      // The view alice is granted: its body binds to the macro now.
      refused(
        "alice",
        "lake",
        s"SELECT d FROM $lake.main.v_lev",
        node = shadowed
      )
      allowed(
        "alice",
        "lake",
        "SELECT system.main.levenshtein('kitten', 'sitting')",
        Some(List("3"))
      )
    finally admin("lake", s"DROP MACRO $lake.main.levenshtein")
    allowed("alice", "lake", "SELECT levenshtein('kitten', 'sitting')", Some(List("3")))
    allowed("alice", "lake", s"SELECT d FROM $lake.main.v_lev", Some(List("3")))
  }

  "a built-in macro's body" should "be judged where the caller's path binds it" in {
    ready()
    // Review I2-R1: DuckDB binds a built-in macro's calls on the caller's search path, so a macro
    // planted in the pool's DuckLake catalog shadows list_append inside array_append, even called
    // system.main.
    allowed("alice", "lake", "SELECT len(array_append([1], 2))", Some(List("2")))
    admin("lake", s"CREATE MACRO $lake.main.list_append(l, e) AS 'shadowed'")
    try
      val shadowed =
        s"function list_append is not classified: it resolves to $lake.main.list_append"
      refused("alice", "lake", "SELECT array_append([1], 2)", node = shadowed)
      refused(
        "alice",
        "lake",
        "SELECT system.main.array_append([1], 2)",
        node = shadowed
      )
    finally admin("lake", s"DROP MACRO $lake.main.list_append")
    allowed("alice", "lake", "SELECT len(array_append([1], 2))", Some(List("2")))
  }

  "a table's stored expressions" should "be judged where DuckDB binds them" in {
    ready()
    // Review I3-R1 (item 32a): an INSERT binds the target's column defaults on the table's own path,
    // so a macro planted in the pool's DuckLake catalog shadows lower inside one.
    admin("lake", s"CREATE TABLE $lake.main.dflt (a INTEGER, b VARCHAR DEFAULT upper(lower('Ab')))")
    grant(lake, "main", "dflt", "RW")
    allowed("alice", "lake", s"INSERT INTO $lake.main.dflt (a) VALUES (1)")
    admin("lake", s"CREATE MACRO $lake.main.lower(x) AS 'shadowed'")
    try
      refused(
        "alice",
        "lake",
        s"INSERT INTO $lake.main.dflt (a) VALUES (2)",
        node = s"function lower is not classified: it resolves to $lake.main.lower"
      )
    finally admin("lake", s"DROP MACRO $lake.main.lower")
    // A read binds a table's generated columns. DuckLake has none (it refuses them, as it does CHECK
    // constraints and triggers), so this one lives in the duckdb-file database.
    admin("files", s"CREATE TABLE $files.main.gen (a INTEGER, g VARCHAR AS (upper(lower('Ab'))))")
    admin("files", s"INSERT INTO $files.main.gen (a) VALUES (1)")
    grant(files, "main", "gen", "RO")
    allowed("alice", "files", s"SELECT g FROM $files.main.gen", Some(List("AB")))
    admin("files", s"CREATE MACRO $files.main.lower(x) AS 'shadowed'")
    try
      refused(
        "alice",
        "files",
        s"SELECT g FROM $files.main.gen",
        node = s"function lower is not classified: it resolves to $files.main.lower"
      )
    finally admin("files", s"DROP MACRO $files.main.lower")
    allowed("alice", "files", s"SELECT g FROM $files.main.gen", Some(List("AB")))
  }

  "a query() in a view's body" should "be walked like the body" in {
    ready()
    // Review I2-R2: the literal of a query() in a view body is parsed and bound at every read.
    admin(
      "lake",
      s"CREATE VIEW $lake.main.v_q AS SELECT * FROM query(" +
        "'SELECT upper(current_setting(''TimeZone'')) AS z')"
    )
    grant(lake, "main", "v_q", "RO")
    refused(
      "alice",
      "lake",
      s"SELECT z FROM $lake.main.v_q",
      node = "function current_setting is superuser-only"
    )
  }

  "a view's body" should "be judged by the calls it makes, folded or not" in {
    ready()
    // Item 32 B: DuckDB folds a call on constants that is another call's argument while binding, so
    // the plan of a view that nests one shows only the outer call; the node reads the body itself.
    admin(
      "lake",
      s"CREATE VIEW $lake.main.v_set AS SELECT upper(current_setting('TimeZone')) AS z"
    )
    grant(lake, "main", "v_set", "RO")
    refused(
      "alice",
      "lake",
      s"SELECT z FROM $lake.main.v_set",
      node = "function current_setting is superuser-only"
    )
    // An extension function loaded at runtime, which no classification lists (fts, as the unclassified
    // function case loads it).
    val loaded = stack.flight("LOAD fts", "admin", "admin", Acme, "lake", superuser = true).isRight
    assume(loaded, "the node could not load the fts extension (no network access?)")
    admin("lake", s"CREATE VIEW $lake.main.v_stem AS SELECT upper(stem('running', 'porter')) AS s")
    grant(lake, "main", "v_stem", "RO")
    refused(
      "alice",
      "lake",
      s"SELECT s FROM $lake.main.v_stem",
      node = "function stem is not classified"
    )
  }

  // ---------- read-only federated catalogs (decision 24) ----------

  /** `sql` as the system admin on `pool`, through the superuser header: rows, or the refusal. */
  private def asAdmin(pool: String, sql: String): Either[String, List[String]] =
    stack.flight(sql, "admin", "admin", Acme, pool, superuser = true)

  /** A refused write into the read-only catalog `alias`: the node's refusal, for every principal
    * (decision 24, QodNodeReadOnlySpec).
    */
  private def refusedAsReadOnly(out: Either[String, List[String]], alias: String): Unit =
    out match
      case Left(err) => err should include(s"QoD: catalog $alias is read-only")
      case Right(r)  => fail(s"expected a read-only refusal, got rows $r")

  "a read-only sql source" should "refuse a superuser's writes and DDL, its file attached READ_WRITE" in {
    ready()
    List(
      "INSERT INTO rosql.main.t VALUES (2)",
      "UPDATE rosql.main.t SET v = 3",
      "DELETE FROM rosql.main.t",
      "CREATE TABLE rosql.main.x (v INTEGER)",
      "DROP TABLE rosql.main.t"
    ).foreach(sql =>
      withClue(s"admin: $sql -> ")(refusedAsReadOnly(asAdmin("rofed", sql), "rosql"))
    )
    admin("rofed", "SELECT count(*), min(v) FROM rosql.main.t") shouldBe List("1,1")
  }

  it should "be read by a superuser and by a user granted it" in {
    ready()
    admin("rofed", "SELECT v FROM rosql.main.t") shouldBe List("1")
    allowed("alice", "rofed", "SELECT v FROM rosql.main.t", Some(List("1")))
  }

  it should "refuse a user's write, whatever her grant" in {
    ready()
    doors.foreach { d =>
      withClue(s"[$d] alice -> ")(
        refusedAsReadOnly(as(d, "alice", "rofed", "INSERT INTO rosql.main.t VALUES (2)"), "rosql")
      )
    }
    admin("rofed", "SELECT count(*) FROM rosql.main.t") shouldBe List("1")
  }

  "a read-only typed source" should "refuse a superuser's write" in {
    ready()
    val before = admin("rofed", "SELECT count(*) FROM pgro.e2e.orders")
    refusedAsReadOnly(asAdmin("rofed", "INSERT INTO pgro.e2e.orders VALUES (9, 'z')"), "pgro")
    admin("rofed", "SELECT count(*) FROM pgro.e2e.orders") shouldBe before
  }

  "a writable sql source on the same pool" should "take a superuser's and a user's writes" in {
    ready()
    admin("rofed", "INSERT INTO rwsql.main.t VALUES (2)")
    // Reading the read-only catalog to write the writable one: the nearest write that must pass.
    admin("rofed", "INSERT INTO rwsql.main.t SELECT v FROM rosql.main.t")
    doors.foreach { d =>
      withClue(s"[$d] ") {
        as(d, "alice", "rofed", "INSERT INTO rwsql.main.t VALUES (3)") shouldBe a[Right[?, ?]]
      }
    }
    admin("rofed", "SELECT count(*) FROM rwsql.main.t") shouldBe List((3 + doors.size).toString)
  }

  // ---------- catalogs: memory, a custom alias, federated, lake ----------

  "a memory database" should "be its pool's catalog, and only its pool's" in {
    ready()
    // `*.*.* ALL` covers DuckDB's built-in memory catalog on the memory database's own pool (Task 6
    // I1), never on another pool, where memory is instance-wide.
    admin("fed", "CREATE OR REPLACE TABLE memory.main.mt_admin AS SELECT 1 AS a")
    doors.foreach { d =>
      withClue(s"[$d] ") {
        as(d, "bob", "fed", s"CREATE TABLE memory.main.mt_$d AS SELECT 1 AS a") shouldBe
          a[Right[?, ?]]
        as(d, "bob", "fed", s"INSERT INTO memory.main.mt_$d VALUES (2)") shouldBe a[Right[?, ?]]
        as(d, "bob", "fed", s"SELECT count(*) FROM memory.main.mt_$d") shouldBe Right(List("2"))
      }
    }
    refused("alice", "fed", "SELECT count(*) FROM memory.main.mt_admin")
    refused("bob", "lake", "CREATE TABLE memory.main.not_here AS SELECT 1 AS a")
  }

  "a database with a custom catalog alias" should "be judged under the alias it is attached as" in {
    ready()
    // A wildcard grant reaches the alias on the node.
    allowed("bob", "aliased", s"SELECT v FROM $Alias.main.aliased_t", Some(List("5")))
    allowed("alice", "aliased", s"SELECT v FROM $Alias.main.aliased_t", Some(List("5")))
    // The USE prelude of the pool names the alias too.
    allowed("alice", "aliased", "SELECT v FROM aliased_t", Some(List("5")))
    refused("alice", "aliased", s"SELECT v FROM $Alias.main.aliased_u")
  }

  "a granted Postgres table" should "be readable and writable, an ungranted one refused" in {
    ready()
    allowed("alice", "fed", "SELECT count(*) >= 2 FROM pgfed.e2e.orders", Some(List("true")))
    allowed("alice", "fed", "INSERT INTO pgfed.e2e.orders VALUES (3, 'c')")
    refused("alice", "fed", "SELECT * FROM pgfed.e2e.other")
    // Remote SQL by function: superuser-only whatever the grants.
    refused("alice", "fed", "SELECT * FROM postgres_query('pgfed', 'SELECT 1')")
  }

  "a Postgres CREATE INDEX" should "be judged as an alter of its table (decision 19)" in {
    ready()
    // duckdb-postgres plans CREATE INDEX as an extension operator that names itself neither by name
    // nor by extension: the describer reads it from the statement as an alter of its table.
    refused(
      "alice",
      "fed",
      "CREATE INDEX e2e_orders_alice ON pgfed.e2e.orders (id)",
      node = "pgfed.e2e.orders:alter"
    )
    // `*.*.* ALL` does not reach a federated catalog: Bob is refused too, Carol owns the schema.
    refused("bob", "fed", "CREATE INDEX e2e_orders_bob ON pgfed.e2e.orders (id)")
    doors.foreach { d =>
      withClue(s"[$d] ") {
        as(d, "carol", "fed", s"CREATE INDEX e2e_orders_carol_$d ON pgfed.e2e.orders (id)") shouldBe
          a[Right[?, ?]]
      }
    }
  }

  "a granted Iceberg table" should "be readable with the user's own keys, an ungranted one refused" in {
    ready()
    assume(icebergReady, IcebergFixture.missingFixture)
    allowed("alice", "fed", s"SELECT count(*) FROM ice.$iceNs.t", Some(List("1")))
    refused("alice", "fed", s"SELECT * FROM ice.$iceNs.u")
  }

  it should "be writable with the user's own keys" in {
    ready()
    assume(icebergReady, IcebergFixture.missingFixture)
    // duckdb-iceberg (DuckDB v2.0.0) commits its manifest files without the connection's secrets,
    // so a user's credential (installed IN connection) never signs them: HTTP 403 at commit.
    pendingUntilFixed {
      allowed("alice", "fed", s"INSERT INTO ice.$iceNs.t VALUES (2)")
    }
  }

  // ---------- tenant folders and users' S3 credentials: validation, and taking effect live ----------

  "a tenant location" should "be a folder under the root, never an S3 bucket" in {
    ready()
    def addStatus(json: String): (Int, String) = stack.postStatus("/api/tenant/location/add", json)
    val tooWide = addStatus(obj("tenant" -> s(Acme), "location" -> s(s"$root/$Globex/in/")))
    tooWide._1 shouldBe 400
    tooWide._2 should include("outside_tenant_files_root")
    val relative = addStatus(obj("tenant" -> s(Acme), "location" -> s("in/")))
    relative._1 shouldBe 400
    relative._2 should include("invalid_location")
    val dotted = addStatus(obj("tenant" -> s(Acme), "location" -> s(s"$shared/../x/")))
    dotted._1 shouldBe 400
    dotted._2 should include("invalid_location")
    val bucket = addStatus(obj("tenant" -> s(Acme), "location" -> s(s"s3://$Bucket/")))
    bucket._1 shouldBe 400
    bucket._2 should include("S3 access is per user")
    val again = addStatus(obj("tenant" -> s(Acme), "location" -> s(s"$shared/")))
    again._1 shouldBe 409
    again._2 should include("location_exists")
  }

  "a user's S3 credential" should "be refused in a data folder or at an endpoint the operator did not list" in {
    ready()
    val alice                                                     = session("alice")
    def addStatus(scope: String, endpoint: String): (Int, String) =
      stack.postStatus("/api/profile/s3/add", credentialJson(scope, "AKIAX", "x", endpoint), alice)
    val elsewhere = addStatus(s"s3://$Bucket/x/", "http://evil.example.com:9000")
    elsewhere._1 shouldBe 400
    elsewhere._2 should include("endpoint_not_allowed")
    // globex's s3 data folder exists only with rustfs up.
    requireS3()
    val inData = addStatus(s"s3://$Bucket/globex_lake/", "http://localhost:9000")
    inData._1 shouldBe 400
    inData._2 should include("scope_in_data_folder")
  }

  it should "take effect on the next statement when it is added and when it is removed" in {
    ready()
    // On the spec's own endpoint: Dave holds no credential until he adds one.
    val dave = session("dave")
    refused(
      "dave",
      "lake",
      s"SELECT count(*) FROM read_csv('$FakeUrl')",
      node = "no S3 credential of yours covers it"
    )
    val id = addCredential(dave, s"s3://$FakeBucket/", "AKIADAVELATE", "dave-secret", fake.endpoint)
    // The next handle carries the new digest, so the node fetches the set: no polling.
    try
      doors.foreach { d =>
        withClue(s"[$d] ") {
          val mark = fake.requests.size
          as(d, "dave", "lake", s"SELECT count(*) FROM read_csv('$FakeUrl')") shouldBe Right(
            List("2")
          )
          fake.requests.drop(mark).map(_.keyId).distinct shouldBe List("AKIADAVELATE")
        }
      }
    finally stack.post("/api/profile/s3/remove", obj("id" -> s(id)), dave)
    val mark = fake.requests.size
    refused(
      "dave",
      "lake",
      s"SELECT count(*) FROM read_csv('$FakeUrl')",
      node = "no S3 credential of yours covers it"
    )
    fake.requests.drop(mark) shouldBe empty
  }

  it should "take effect on rustfs too, for a write and a read" in {
    ready()
    requireS3()
    val late = s"$Bucket-late"
    bucket(late) shouldBe Right(())
    val path = s"s3://$late/x.parquet"
    refused(
      "alice",
      "lake",
      s"COPY (SELECT 5 AS id) TO '$path' (FORMAT parquet)",
      node = "no S3 credential of yours covers it"
    )
    val alice = session("alice")
    val id    = addCredential(alice, s"s3://$late/", "admin", "password", Rustfs)
    allowed("alice", "lake", s"COPY (SELECT 5 AS id) TO '$path' (FORMAT parquet)")
    allowed("alice", "lake", s"SELECT id FROM read_parquet('$path')", Some(List("5")))
    stack.post("/api/profile/s3/remove", obj("id" -> s(id)), alice)
    refused(
      "alice",
      "lake",
      s"SELECT id FROM read_parquet('$path')",
      node = "no S3 credential of yours covers it"
    )
  }

  // ---------- policy version, decision outage, session context ----------

  "a revoke" should "take effect on the next statement, past the node's cached verdict" in {
    ready()
    // alice is granted the plain view, not its base table; after the revoke only a new policy
    // version keeps the node from reusing the allow it cached for this very plan.
    val sql         = s"SELECT x FROM $lake.main.v_secret"
    val secretGrant = grant(lake, "main", "secret", "RO")
    allowed("alice", "lake", sql, Some(List("42")))
    // The same plan again: the node answers from its verdict cache, asking the replica nothing.
    val cached = stack.relay.decideCalls
    allowed("alice", "lake", sql, Some(List("42")))
    withClue("decision calls for a plan the node had cached: ") {
      stack.relay.decideCalls shouldBe cached
    }
    stack.post("/api/role/permission/revoke", obj("id" -> s(secretGrant)))
    val revoked = stack.relay.decideCalls
    refused("alice", "lake", sql)
    withClue("decision calls after the revoke (a new policy version): ") {
      stack.relay.decideCalls should be > revoked
    }
    // A plain table: only the node's policy version stands between the revoke and the allow it
    // cached for this plan.
    stack.post("/api/role/permission/revoke", obj("id" -> s(ordersGrant)))
    try refused("alice", "lake", s"SELECT count(*) FROM $lake.main.orders")
    finally ordersGrant = grant(lake, "main", "orders", "RO")
    allowed("alice", "lake", s"SELECT count(*) FROM $lake.main.orders", Some(List("10")))
  }

  "a dead decision replica" should "give a retryable error, and the retry pass" in {
    ready()
    // A plan the node has never decided (no other case calls these functions on orders), so no
    // cached verdict can answer it while the replica is down.
    val sql = s"SELECT max(abs(id)), min(reverse(v)) FROM $lake.main.orders"
    stack.relay.stop()
    try
      // FlightSQL: UNAVAILABLE, the status a client retries; the front door relays the node's own
      // answer, which says to retry.
      stack.flightCall(sql, "alice", AlicePw, Acme, "lake") match
        case Left(e) =>
          e.status().code() shouldBe org.apache.arrow.flight.FlightStatusCode.UNAVAILABLE
          e.getMessage should include("QoD: authorization unavailable, retry")
        case Right(r) => fail(s"expected a refusal while the replica is down, got $r")
      if AuthzStack.cliPresent then
        stack.door(sql, "alice", AlicePw, Acme, "lake") match
          case Left(err) => err should include("QoD: authorization unavailable, retry")
          case Right(r)  => fail(s"expected a refusal while the replica is down, got $r")
    finally stack.relay.start()
    allowed("alice", "lake", sql)
  }

  "a restricted token" should "not reuse a full session's cached verdict" in {
    ready()
    val insert = s"INSERT INTO $lake.main.events VALUES (100, 'full')"
    // The full session: allowed, and the node caches the allow for its context.
    stack.flight(insert, "alice", AlicePw, Acme, "lake") shouldBe a[Right[?, ?]]
    val session = stack.login(Some(Acme), "alice", AlicePw)
    val pat     = stack
      .post(
        "/api/auth/pat/create",
        obj(
          "name"       -> s(s"e2e-branch-only-$run"),
          "dropAdmin"  -> Json.False,
          "branchOnly" -> Json.True
        ),
        token = session
      )
      .hcursor
      .get[String]("token")
      .fold(e => throw e, identity)
    // The same INSERT plan (VALUES are not in the description) under a branch-only token, behind a
    // SELECT: the gateway classifies a batch by its first statement and lets it through, so only
    // the node stands between the token and a write on the main pool, and only a verdict keyed on
    // the session context (decision 22) makes it decide instead of reusing the full session's.
    stack.mcp(pat, s"SELECT 1; INSERT INTO $lake.main.events VALUES (101, 'pat')", lake) match
      case Left(err) => err should include("QoD: ")
      case Right(t)  => fail(s"the branch-only token wrote on the main pool: $t")
    // A read-only token's plain INSERT: the node's attenuated set refuses it.
    val ro = stack
      .post(
        "/api/auth/pat/create",
        obj(
          "name"        -> s(s"e2e-ro-$run"),
          "dropAdmin"   -> Json.False,
          "branchOnly"  -> Json.False,
          "verbCeiling" -> s("RO")
        ),
        token = session
      )
      .hcursor
      .get[String]("token")
      .fold(e => throw e, identity)
    stack.mcp(ro, s"INSERT INTO $lake.main.events VALUES (101, 'ro')", lake) match
      case Left(err) => err should include("QoD: ")
      case Right(t)  => fail(s"the read-only token wrote: $t")
    // Nor a file, into a folder alice may write to: the node's verb ceiling refuses it.
    val roOut = outDir.resolve(s"ro-$run.csv")
    stack.mcp(ro, s"COPY (SELECT 1 AS id) TO '$roOut' (HEADER)", lake) match
      case Left(err) => err should include("denied by token verb ceiling on file writes")
      case Right(t)  => fail(s"the read-only token wrote a file: $t")
    Files.exists(roOut) shouldBe false
    stack.flight(
      s"SELECT count(*) FROM $lake.main.events WHERE id = 101",
      "admin",
      "admin",
      Acme,
      "lake",
      superuser = true
    ) shouldBe Right(List("0"))
  }
