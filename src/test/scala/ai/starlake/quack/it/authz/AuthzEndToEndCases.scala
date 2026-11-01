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
  *   - erin (acme): a tenant admin (kind `admin`) with the default memberships;
  *   - olga (acme): role `e2e_one_table` (RO lake events, nothing else), group `qod_all_pools`;
  *   - pete (acme): role `e2e_all_ro` (`*.*.* RO`, nothing named), group `qod_all_pools`;
  *   - fran (acme): role `e2e_feed` (RO lake `feed_a`, nothing else), group `qod_all_pools`; tess
  *     (acme): a tenant admin with only that role and group (the REST data diff); mona (acme): role
  *     `e2e_feed_masked` (RO lake `feed_a` with a mask on its `email`, and RO lake `vault_now`),
  *     group `qod_all_pools`;
  *   - nico (acme): role `e2e_vault` (RO lake `vault`, the table created after the first `vault`
  *     was renamed `vault_now`), group `qod_all_pools`; vera (acme): role `e2e_vault_now` (RO lake
  *     `vault_now`), group `qod_all_pools`;
  *   - dina (acme): role `e2e_drop` (RO lake `drop_free`, `drop_reused` and `rn_final`, tables
  *     dropped by the dropped-table cases), group `qod_all_pools`;
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
  private val ErinPw    = "erin-e2e-pw-1"
  private val OlgaPw    = "olga-e2e-pw-1"
  private val PetePw    = "pete-e2e-pw-1"
  private val FranPw    = "fran-e2e-pw-1"
  private val TessPw    = "tess-e2e-pw-1"
  private val MonaPw    = "mona-e2e-pw-1"
  private val NicoPw    = "nico-e2e-pw-1"
  private val VeraPw    = "vera-e2e-pw-1"
  private val DinaPw    = "dina-e2e-pw-1"
  private val Passwords = Map(
    "alice" -> AlicePw,
    "bob"   -> BobPw,
    "carol" -> CarolPw,
    "dave"  -> DavePw,
    "erin"  -> ErinPw,
    "olga"  -> OlgaPw,
    "pete"  -> PetePw,
    "fran"  -> FranPw,
    "tess"  -> TessPw,
    "mona"  -> MonaPw,
    "nico"  -> NicoPw,
    "vera"  -> VeraPw,
    "dina"  -> DinaPw
  )

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
    // RO on everything the wildcard covers, nothing named: the filtered wildcard reader.
    val allRo = createRole("e2e_all_ro")
    grant("*", "*", "*", "RO", allRo)
    stack.post(
      "/api/user/create",
      obj(
        "tenant"   -> s(Acme),
        "username" -> s("pete"),
        "password" -> s(PetePw),
        "kind"     -> s("user"),
        "roles"    -> Json.arr(s("e2e_all_ro")),
        "groups"   -> Json.arr(s("qod_all_pools"))
      )
    )
    // RO on one table, nothing else: the reader of the catalog listing cases.
    val oneTable = createRole("e2e_one_table")
    grant(lake, "main", "events", "RO", oneTable)
    stack.post(
      "/api/user/create",
      obj(
        "tenant"   -> s(Acme),
        "username" -> s("olga"),
        "password" -> s(OlgaPw),
        "kind"     -> s("user"),
        "roles"    -> Json.arr(s("e2e_one_table")),
        "groups"   -> Json.arr(s("qod_all_pools"))
      )
    )
    // DuckLake's change feed: RO on lake feed_a alone, as a user, as a tenant admin (the REST data
    // diff) and under a mask on its email.
    val feed = createRole("e2e_feed")
    grant(lake, "main", "feed_a", "RO", feed)
    val feedMasked = createRole("e2e_feed_masked")
    grant(lake, "main", "feed_a", "RO", feedMasked)
    grant(lake, "main", "vault_now", "RO", feedMasked)
    // A table renamed since a snapshot: `vault` (the new one) alone, and `vault_now` (the old) alone.
    val vault = createRole("e2e_vault")
    grant(lake, "main", "vault", "RO", vault)
    grant(lake, "main", "rn_first", "RO", vault)
    val drop = createRole("e2e_drop")
    List("drop_free", "drop_reused", "rn_final").foreach(t => grant(lake, "main", t, "RO", drop))
    grant(lake, "main", "rorders", "RO", feed)
    val vaultNow = createRole("e2e_vault_now")
    grant(lake, "main", "vault_now", "RO", vaultNow)
    stack.post(
      "/api/role/column-policy/create",
      obj(
        "roleId"       -> s(feedMasked),
        "catalogName"  -> s(lake),
        "schemaName"   -> s("main"),
        "tableName"    -> s("feed_a"),
        "columnName"   -> s("email"),
        "action"       -> s("mask"),
        "transformSql" -> s("'***'")
      )
    )
    List(
      ("fran", FranPw, "user", "e2e_feed"),
      ("tess", TessPw, "admin", "e2e_feed"),
      ("mona", MonaPw, "user", "e2e_feed_masked"),
      ("nico", NicoPw, "user", "e2e_vault"),
      ("vera", VeraPw, "user", "e2e_vault_now"),
      ("dina", DinaPw, "user", "e2e_drop")
    ).foreach { (user, pw, kind, role) =>
      stack.post(
        "/api/user/create",
        obj(
          "tenant"   -> s(Acme),
          "username" -> s(user),
          "password" -> s(pw),
          "kind"     -> s(kind),
          "roles"    -> Json.arr(s(role)),
          "groups"   -> Json.arr(s("qod_all_pools"))
        )
      )
    }
    // A tenant admin, with the default memberships: no superuser on the node (decision 29).
    stack.post(
      "/api/user/create",
      obj(
        "tenant"   -> s(Acme),
        "username" -> s("erin"),
        "password" -> s(ErinPw),
        "kind"     -> s("admin")
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

  /** `sql` as `user` on `pool` fails through every door: DuckDB's own "does not exist" (replacement
    * scans are off for users, so a path in a table position binds to no table) or a node refusal.
    */
  private def missingOrRefused(user: String, pool: String, sql: String): Unit =
    doors.foreach { d =>
      withClue(s"[$d] $user: $sql -> ") {
        as(d, user, pool, sql) match
          case Left(err) => err should (include("does not exist") or include("QoD: "))
          case Right(r)  => fail(s"expected a refusal, got rows $r")
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

  it should "reach a connection held open across it on its next statement, and a re-grant too" in {
    ready()
    // The handshake resolved the session's grants; only their following the tenant's policy
    // version (the revoke moves it) keeps the session from being judged on them after the revoke.
    val sql                  = s"SELECT x FROM $lake.main.v_secret"
    var secretGrant          = grant(lake, "main", "secret", "RO")
    def revokeSecret(): Unit =
      stack.post("/api/role/permission/revoke", obj("id" -> s(secretGrant))); ()
    def regrantSecret(): Unit = secretGrant = grant(lake, "main", "secret", "RO")
    try
      val held = stack.heldFlight("alice", AlicePw, Acme, "lake")
      try
        withClue("[Flight] one connection, before the revoke: ") {
          held.run(sql) shouldBe Right(List("42"))
        }
        revokeSecret()
        withClue("[Flight] the same connection, after the revoke: ") {
          held.run(sql) match
            case Left(err) => err should include("QoD: ")
            case Right(r)  => fail(s"expected a refusal, got rows $r")
        }
        regrantSecret()
        withClue("[Flight] the same connection, after the re-grant: ") {
          held.run(sql) shouldBe Right(List("42"))
        }
      finally held.close()
      if AuthzStack.cliPresent then
        val out = stack.heldDoor("alice", AlicePw, Acme, "lake", List(sql, sql, sql)) {
          case 0 => revokeSecret()
          case 1 => regrantSecret()
          case _ => ()
        }
        withClue("[Native] one session, before the revoke, after it, after the re-grant: ") {
          out(0) shouldBe Right(List("42"))
          out(1) match
            case Left(err) => err should include("QoD: ")
            case Right(r)  => fail(s"expected a refusal, got rows $r")
          out(2) shouldBe Right(List("42"))
        }
    finally stack.postStatus("/api/role/permission/revoke", obj("id" -> s(secretGrant)))
  }

  "a pool permission revoke" should "refuse the next statement of a connection held open across it" in {
    ready()
    // frank holds lake only through a pool grant of his own: qod_no_pools opens nothing.
    val frankPw = "frank-e2e-pw-1"
    val frankId = stack
      .post(
        "/api/user/create",
        obj(
          "tenant"   -> s(Acme),
          "username" -> s("frank"),
          "password" -> s(frankPw),
          "kind"     -> s("user"),
          "roles"    -> Json.arr(s("e2e_reader")),
          "groups"   -> Json.arr(s("qod_no_pools"))
        )
      )
      .hcursor
      .get[String]("id")
      .fold(e => throw e, identity)
    // Every pool grant made for frank, revoked or not: the finally removes what is left.
    val grants              = scala.collection.mutable.ListBuffer.empty[String]
    def poolGrant(): String =
      val id = stack
        .post("/api/pool/permission/grant", obj("tenant" -> s(Acme), "userId" -> s(frankId)))
        .hcursor
        .get[String]("id")
        .fold(e => throw e, identity)
      grants += id
      id
    def revoke(id: String): Unit =
      stack.post("/api/pool/permission/revoke", obj("id" -> s(id))); ()
    val sql  = s"SELECT count(*) FROM $lake.main.orders"
    val lost = "has no access to pool"
    try
      val first = poolGrant()
      val held  = stack.heldFlight("frank", frankPw, Acme, "lake")
      try
        withClue("[Flight] one connection, before the revoke: ") {
          held.run(sql) shouldBe Right(List("10"))
        }
        revoke(first)
        withClue("[Flight] the same connection, after the revoke: ") {
          held.run(sql) match
            case Left(err) => err should include(lost)
            case Right(r)  => fail(s"expected a refusal, got rows $r")
        }
      finally held.close()
      if AuthzStack.cliPresent then
        val second = poolGrant()
        val out    = stack.heldDoor("frank", frankPw, Acme, "lake", List(sql, sql)) {
          case 0 => revoke(second)
          case _ => ()
        }
        withClue("[Native] one session, before the revoke and after it: ") {
          out(0) shouldBe Right(List("10"))
          out(1) match
            case Left(err) => err should include(lost)
            case Right(r)  => fail(s"expected a refusal, got rows $r")
        }
    finally
      // Status only: a grant the case already revoked answers an error, which is expected here.
      grants.foreach(id => stack.postStatus("/api/pool/permission/revoke", obj("id" -> s(id))))
      stack.postStatus("/api/user/delete", obj("id" -> s(frankId)))
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

  // ---------- Phase D acceptance (Task 7): the deleted gateway screens' cases, on the node ----------

  "a superuser-only function" should "be refused to users, however it is reached" in {
    ready()
    List(
      "SELECT * FROM quack_active_connections()",
      "SELECT count(*) FROM \"quack_active_connections\"()",
      "FROM quack_active_connections()",
      "SELECT * FROM quack_server_list()",
      "SELECT * FROM truncate_duckdb_logs()",
      "SELECT * FROM enable_logging()",
      "CALL enable_logging(storage = 'file', storage_path = '/tmp/qod-authz-logs')",
      "SELECT * FROM query('SELECT * FROM quack_active_connections()')",
      "SELECT 1; SELECT * FROM quack_server_list()",
      "CALL dbgen(sf = 0)",
      "SELECT * FROM shell_history()"
    ).foreach(sql => refused("bob", "lake", sql))
  }

  "the node's state" should "be hidden from users: settings, secrets, databases, sessions" in {
    ready()
    List(
      "SELECT * FROM duckdb_settings()",
      "SELECT name, value FROM duckdb_settings() WHERE name LIKE 's3%'",
      "SELECT * FROM system.main.duckdb_secrets()",
      "SELECT * FROM `duckdb_secrets`()",
      "SELECT * FROM which_secret('s3://x/y', 's3')",
      "SELECT * FROM duckdb_databases()",
      "SELECT * FROM duckdb_memory()",
      "SELECT * FROM duckdb_extensions()",
      "SELECT current_setting('s3_secret_access_key')",
      "SELECT current_setting('s3_' || 'secret_access_key')",
      "SELECT current_setting('TimeZone')",
      "SELECT * FROM whoami()",
      "PRAGMA database_list",
      s"PRAGMA storage_info('$lake.main.orders')",
      "SELECT * FROM query('SELECT * FROM duckdb_secrets()')",
      "SELECT 1; SELECT * FROM duckdb_settings()"
    ).foreach(sql => refused("bob", "lake", sql))
    // The bare view spelling: DuckDB's own "does not exist" or a node refusal.
    missingOrRefused("bob", "lake", "SELECT name, value FROM duckdb_settings WHERE name LIKE 's3%'")
    // A tenant admin is no superuser on the node.
    refused("erin", "lake", "SELECT * FROM duckdb_settings()", node = "duckdb_settings")
    refused("erin", "lake", "SET threads = 2", node = "threads")
  }

  "the DuckLake metadata catalog" should "be refused to users, even under *.*.* ALL" in {
    ready()
    // DuckLake keeps its metadata catalog out of duckdb_databases() and duckdb_tables(); on
    // Postgres (no METADATA_SCHEMA in the node's ATTACH) its tables live in schema public. The
    // superuser's read proves the name, so every refusal below is the node's, not a missing table.
    val meta     = s"__ducklake_metadata_$lake"
    val schema   = "public"
    val dataFile = s"$meta.$schema.ducklake_data_file"
    admin("lake", s"SELECT count(*) >= 0 FROM $dataFile") shouldBe List("true")
    val upper = meta.toUpperCase(java.util.Locale.ROOT)
    refused("bob", "lake", s"SELECT count(*) FROM $dataFile", node = "DuckLake catalog metadata")
    List(
      s"SELECT count(*) FROM \"$upper\".$schema.ducklake_data_file",
      s"SELECT count(*) FROM $meta . $schema . ducklake_data_file",
      s"USE $meta.$schema; SELECT count(*) FROM ducklake_data_file",
      s"SET search_path = '$meta.$schema'; SELECT count(*) FROM ducklake_data_file",
      s"SELECT * FROM query_table('$dataFile')",
      s"SELECT * FROM query('SELECT count(*) FROM $dataFile')",
      s"SELECT * FROM query_table('__duck' || 'lake_metadata_$lake.$schema.ducklake_data_file')",
      s"PREPARE meta_p AS SELECT count(*) FROM $dataFile; EXECUTE meta_p",
      s"EXPLAIN ANALYZE SELECT count(*) FROM $dataFile",
      s"SELECT 1; SELECT count(*) FROM $dataFile",
      s"SELECT * FROM ducklake_list_files('$lake', 'orders')",
      "SELECT * FROM json_execute_serialized_sql('{}')",
      s"DETACH $meta"
    ).foreach(sql => refused("bob", "lake", sql))
    // A plain view's body is judged where it is read, from the expanded plan (spec Section 6): bob
    // may create one over the metadata catalog in a schema he holds (the gateway refused that), and
    // every read through it is refused.
    val view = s"$lake.main.qod_e2e_meta"
    try
      allowed("bob", "lake", s"CREATE OR REPLACE VIEW $view AS SELECT * FROM $dataFile")
      refused("bob", "lake", s"SELECT count(*) FROM $view", node = "DuckLake catalog metadata")
      refused("erin", "lake", s"SELECT count(*) FROM $view", node = "DuckLake catalog metadata")
      refused(
        "bob",
        "lake",
        s"SELECT * FROM query_table('$view')",
        node = "DuckLake catalog metadata"
      )
      refused(
        "erin",
        "lake",
        s"SELECT * FROM query_table('$view')",
        node = "DuckLake catalog metadata"
      )
    finally admin("lake", s"DROP VIEW IF EXISTS $view")
    // The data tables stay readable with an ordinary grant.
    allowed("alice", "lake", s"SELECT count(*) FROM $lake.main.orders", Some(List("10")))
  }

  // ---------- DuckLake's change feed: the data diff and the branch change counts ----------

  /** feed_a and feed_b on the lake, and the snapshot range `(from, to]` of their writes: three rows
    * inserted into feed_a, one updated, one deleted; one row inserted into feed_b. Written once by
    * the superuser.
    */
  private lazy val feedRange: (Long, Long) =
    def head(): Long =
      admin("lake", s"SELECT max(snapshot_id) FROM ducklake_snapshots('$lake')").head.toLong
    admin("lake", s"CREATE TABLE $lake.main.feed_a (id INTEGER, v VARCHAR, email VARCHAR)")
    admin("lake", s"CREATE TABLE $lake.main.feed_b (id INTEGER, v VARCHAR)")
    val from = head()
    admin(
      "lake",
      s"INSERT INTO $lake.main.feed_a VALUES (1, 'a', 'a@x.io'), (2, 'b', 'b@x.io'), " +
        "(3, 'c', 'c@x.io')"
    )
    admin("lake", s"UPDATE $lake.main.feed_a SET v = 'B' WHERE id = 2")
    admin("lake", s"DELETE FROM $lake.main.feed_a WHERE id = 3")
    admin("lake", s"INSERT INTO $lake.main.feed_b VALUES (9, 'z')")
    (from, head())

  /** The data diff's and the branch change counter's statement (DataDiffSql.summarySql). */
  private def feedSummary(table: String): String =
    val (from, to) = feedRange
    ai.starlake.quack.ondemand.api.DataDiffSql.summarySql(lake, "main", table, from, to)

  /** The data diff's page statement's shape, over its own change feed call. */
  private def feedPage(table: String, columns: String = "change_type, id, v"): String =
    val (from, to) = feedRange
    s"SELECT $columns FROM " +
      ai.starlake.quack.ondemand.api.DataDiffSql.diffFn(lake, "main", table, from, to) +
      " ORDER BY snapshot_id, rowid, change_type"

  private def restDataDiff(user: String, table: String): (Int, String) =
    val (from, to) = feedRange
    stack.getStatus(
      s"/api/catalog/tenant/$Acme/database/$lake/schemas/main/tables/$table/data-diff" +
        s"?from=$from&to=$to",
      session(user)
    )

  "DuckLake's change feed" should "give a user the data diff and change counts of a table they may read" in {
    ready()
    // The superuser's answer, the reference: three inserts, one update (pre and post), one delete.
    val counts = admin("lake", feedSummary("feed_a")).sorted
    counts shouldBe List("delete,1", "insert,3", "update_postimage,1", "update_preimage,1")
    doors.foreach { d =>
      withClue(s"[$d] fran: the counts of feed_a -> ") {
        as(d, "fran", "lake", feedSummary("feed_a")).map(_.sorted) shouldBe Right(counts)
      }
    }
    allowed("fran", "lake", feedPage("feed_a"), Some(admin("lake", feedPage("feed_a"))))
    // The REST data diff runs the same statements as its caller: a tenant admin holding only
    // feed_a's grant gets feed_a's.
    val (code, body) = restDataDiff("tess", "feed_a")
    withClue(body)(code shouldBe 200)
    val summary = io.circe.parser.parse(body).toOption.get.hcursor.downField("summary")
    summary.get[Long]("inserted") shouldBe Right(3L)
    summary.get[Long]("deleted") shouldBe Right(1L)
    summary.get[Long]("updated") shouldBe Right(1L)
  }

  it should "refuse a table the user holds no grant on, and a table named by anything but literals" in {
    ready()
    admin("lake", feedSummary("feed_b")) shouldBe List("insert,1")
    refused("fran", "lake", feedSummary("feed_b"), node = s"lacks grants on $lake.main.feed_b:read")
    refused("fran", "lake", feedPage("feed_b"), node = s"lacks grants on $lake.main.feed_b:read")
    val (from, to) = feedRange
    List(
      s"SELECT count(*) FROM ducklake_table_changes('$lake', 'main', 'feed_' || 'a', $from, $to)",
      s"SELECT count(*) FROM ducklake_table_insertions('$lake', lower('MAIN'), 'feed_a', $from, $to)",
      s"SELECT count(*) FROM ducklake_table_deletions(current_database(), 'main', 'feed_a', $from, $to)"
    ).foreach(sql => refused("fran", "lake", sql, node = "as literals"))
    // DuckLake's per-schema macro and the rest of its family stay the superusers'.
    refused("fran", "lake", s"SELECT count(*) FROM $lake.table_changes('feed_a', $from, $to)")
    refused("fran", "lake", s"SELECT * FROM ducklake_table_info('$lake')")
    val (code, body) = restDataDiff("tess", "feed_b")
    withClue(body)(code should not be 200)
    body should include(s"lacks grants on $lake.main.feed_b:read")
  }

  it should "never hand a user under a mask unmasked change data" in {
    ready()
    feedRange
    // The mask holds on a plain read of feed_a.
    allowed("mona", "lake", s"SELECT email FROM $lake.main.feed_a WHERE id = 1", Some(List("***")))
    // The change rows are no relation the policy rewriters can mask: refused to her before the
    // node, whatever columns the statement names.
    List(
      feedSummary("feed_a"),
      feedPage("feed_a", "email"),
      feedPage("feed_a", "*"),
      s"SELECT email FROM ducklake_table_insertions('$lake', 'main', 'feed_a', ${feedRange._1}, " +
        s"${feedRange._2})"
    ).foreach { sql =>
      doors.foreach { d =>
        withClue(s"[$d] mona: $sql -> ") {
          as(d, "mona", "lake", sql) match
            case Left(err) =>
              err should include("ducklake_table_")
              err should include("not available to tenant-scoped principals")
            case Right(rows) => fail(s"expected a refusal, got rows $rows")
        }
      }
    }
  }

  // ---------- a DuckLake table at an earlier snapshot: judged as the table it is now (review M1) ----------

  /** `vault` with one row, renamed `vault_now` (its `email` renamed `contact`), then a new `vault`
    * with another row; and `gone_t`, dropped. The snapshot of the old vault's row and of gone_t's.
    * Written once by the superuser.
    */
  private lazy val vaultHistory: (Long, Long) =
    def head(): Long =
      admin("lake", s"SELECT max(snapshot_id) FROM ducklake_snapshots('$lake')").head.toLong
    admin("lake", s"CREATE TABLE $lake.main.vault (id INTEGER, email VARCHAR)")
    admin("lake", s"INSERT INTO $lake.main.vault VALUES (1, 'old@x.io')")
    val old = head()
    admin("lake", s"ALTER TABLE $lake.main.vault RENAME TO vault_now")
    admin("lake", s"ALTER TABLE $lake.main.vault_now RENAME COLUMN email TO contact")
    admin("lake", s"CREATE TABLE $lake.main.vault (id INTEGER, email VARCHAR)")
    admin("lake", s"INSERT INTO $lake.main.vault VALUES (2, 'new@x.io')")
    admin("lake", s"CREATE TABLE $lake.main.gone_t (id INTEGER)")
    admin("lake", s"INSERT INTO $lake.main.gone_t VALUES (3)")
    val gone = head()
    admin("lake", s"DROP TABLE $lake.main.gone_t")
    (old, gone)

  "a DuckLake table read at an earlier snapshot" should "be judged as the table it is now, never the one holding its old name" in {
    ready()
    val (old, _) = vaultHistory
    val atOld    = s"SELECT id, email FROM $lake.main.vault AT (VERSION => $old)"
    val feedOld  =
      s"SELECT id, email FROM ducklake_table_insertions('$lake', 'main', 'vault', 1, $old)"
    // nico holds only the new vault: its old name's history is vault_now's.
    refused("nico", "lake", atOld, node = s"lacks grants on $lake.main.vault_now:read")
    refused("nico", "lake", feedOld, node = s"lacks grants on $lake.main.vault_now:read")
    allowed("nico", "lake", s"SELECT id, email FROM $lake.main.vault", Some(List("2,new@x.io")))
    // vera holds vault_now: its history is hers under either name (the rule judges the table, not
    // the name the statement wrote).
    allowed("vera", "lake", atOld, Some(List("1,old@x.io")))
    allowed("vera", "lake", feedOld, Some(List("1,old@x.io")))
    allowed(
      "vera",
      "lake",
      s"SELECT id, contact FROM $lake.main.vault_now",
      Some(List("1,old@x.io"))
    )
    refused(
      "vera",
      "lake",
      s"SELECT id FROM $lake.main.vault",
      node = s"lacks grants on $lake.main.vault:read"
    )
  }

  it should "be judged by its last name when the table was dropped since, a wildcard grant included" in {
    ready()
    val (_, gone) = vaultHistory
    val sql       = s"SELECT id FROM $lake.main.gone_t AT (VERSION => $gone)"
    // bob holds `*.*.* ALL`, which covers the free name gone_t; nico holds no grant on it.
    allowed("bob", "lake", sql, Some(List("3")))
    refused("nico", "lake", sql, node = s"lacks grants on $lake.main.gone_t:read")
    admin("lake", sql) shouldBe List("3")
  }

  it should "be refused under an old name to a principal with column or row policies" in {
    ready()
    val (old, _) = vaultHistory
    // mona's mask is on feed_a, and she holds vault_now: its current name reads, its old one cannot
    // carry her policies.
    // The column policy rewriter cannot parse an AT clause and refuses it first (fail closed); the
    // node's `renamed` rule stands behind it (AuthzDeciderSpec).
    allowed("mona", "lake", s"SELECT id FROM $lake.main.vault_now", Some(List("1")))
    doors.foreach { d =>
      withClue(s"[$d] mona: an AT read under the old name -> ") {
        as(d, "mona", "lake", s"SELECT id FROM $lake.main.vault AT (VERSION => $old)") match
          case Left(err) =>
            err should (include("column policy rewrite could not parse statement") or
              include("not available to principals with column or row policies"))
          case Right(rows) => fail(s"expected a refusal, got rows $rows")
      }
    }
  }

  // ---------- a dropped DuckLake table: judged by its last live name; a replace is the same table ----------

  /** The snapshots before each drop: `drop_free` (dropped, its name free), `drop_reused` (dropped,
    * then a new `drop_reused` created) and `rn_first` (renamed `rn_final`, then dropped). Written
    * once by the superuser.
    */
  private lazy val dropHistory: (Long, Long, Long) =
    def head(): Long =
      admin("lake", s"SELECT max(snapshot_id) FROM ducklake_snapshots('$lake')").head.toLong
    admin("lake", s"CREATE TABLE $lake.main.drop_free (id INTEGER)")
    admin("lake", s"INSERT INTO $lake.main.drop_free VALUES (1)")
    val free = head()
    admin("lake", s"DROP TABLE $lake.main.drop_free")
    admin("lake", s"CREATE TABLE $lake.main.drop_reused (id INTEGER)")
    admin("lake", s"INSERT INTO $lake.main.drop_reused VALUES (2)")
    val reused = head()
    admin("lake", s"DROP TABLE $lake.main.drop_reused")
    admin("lake", s"CREATE TABLE $lake.main.drop_reused (id INTEGER, other VARCHAR)")
    admin("lake", s"CREATE TABLE $lake.main.rn_first (id INTEGER)")
    admin("lake", s"INSERT INTO $lake.main.rn_first VALUES (3)")
    val renamed = head()
    admin("lake", s"ALTER TABLE $lake.main.rn_first RENAME TO rn_final")
    admin("lake", s"DROP TABLE $lake.main.rn_final")
    (free, reused, renamed)

  "a dropped DuckLake table" should "be readable under its free last name with a grant on it, and only so" in {
    ready()
    val (free, _, _) = dropHistory
    val sql          = s"SELECT id FROM $lake.main.drop_free AT (VERSION => $free)"
    allowed("dina", "lake", sql, Some(List("1")))
    allowed(
      "dina",
      "lake",
      s"SELECT id FROM ducklake_table_insertions('$lake', 'main', 'drop_free', 1, $free)",
      Some(List("1"))
    )
    refused("olga", "lake", sql, node = s"lacks grants on $lake.main.drop_free:read")
  }

  it should "be refused once its name belongs to another table, even with a grant on the name" in {
    ready()
    val (_, reused, _) = dropHistory
    refused(
      "dina",
      "lake",
      s"SELECT id FROM $lake.main.drop_reused AT (VERSION => $reused)",
      node = "its name now belongs to another table"
    )
    admin("lake", s"SELECT id FROM $lake.main.drop_reused AT (VERSION => $reused)") shouldBe
      List("2")
  }

  it should "be judged on its last name when it was renamed before the drop" in {
    ready()
    val (_, _, renamed) = dropHistory
    val sql             = s"SELECT id FROM $lake.main.rn_first AT (VERSION => $renamed)"
    allowed("dina", "lake", sql, Some(List("3")))
    // nico holds the name it had at that snapshot, not its last one.
    refused("nico", "lake", sql, node = s"lacks grants on $lake.main.rn_final:read")
  }

  // ---------- a replaced table (CREATE OR REPLACE, QoD's restore) keeps its history ----------

  /** `rorders`, three versions: one row, two rows, three rows, the snapshot after each insert. */
  private lazy val rordersHistory: (Long, Long, Long) =
    def head(): Long =
      admin("lake", s"SELECT max(snapshot_id) FROM ducklake_snapshots('$lake')").head.toLong
    admin("lake", s"CREATE TABLE $lake.main.rorders (id INTEGER)")
    admin("lake", s"INSERT INTO $lake.main.rorders VALUES (1)")
    val one = head()
    admin("lake", s"INSERT INTO $lake.main.rorders VALUES (2)")
    val two = head()
    admin("lake", s"INSERT INTO $lake.main.rorders VALUES (3)")
    (one, two, head())

  private def restore(user: String, to: Long): (Int, String) =
    stack.postStatus(
      "/api/catalog/restore",
      obj(
        "tenant"   -> s(Acme),
        "tenantDb" -> s(lake),
        "schema"   -> s("main"),
        "table"    -> s("rorders"),
        "to"       -> s(to.toString)
      ),
      session(user)
    )

  "a replaced DuckLake table" should "keep its history: a tenant admin restores it twice" in {
    ready()
    val (one, two, _) = rordersHistory
    val (c1, b1)      = restore("erin", two)
    withClue(b1)(c1 shouldBe 200)
    admin("lake", s"SELECT count(*) FROM $lake.main.rorders") shouldBe List("2")
    // The second restore reads rorders before the first one replaced it.
    val (c2, b2) = restore("erin", one)
    withClue(b2)(c2 shouldBe 200)
    admin("lake", s"SELECT count(*) FROM $lake.main.rorders") shouldBe List("1")
  }

  it should "be previewed and diffed across the replace by a user granted it" in {
    ready()
    val (one, two, _) = rordersHistory
    // After the restores above (or a replace of its own when run alone).
    admin("lake", s"CREATE OR REPLACE TABLE $lake.main.rorders AS SELECT * FROM $lake.main.rorders")
    allowed(
      "fran",
      "lake",
      s"SELECT count(*) FROM $lake.main.rorders AT (VERSION => $two)",
      Some(List("2"))
    )
    val token    = session("tess")
    val base     = s"/api/catalog/tenant/$Acme/database/$lake/schemas/main/tables/rorders"
    val (pc, pb) = stack.getStatus(s"$base/preview?asOf=$two", token)
    withClue(pb)(pc shouldBe 200)
    val now =
      admin("lake", s"SELECT max(snapshot_id) FROM ducklake_snapshots('$lake')").head.toLong
    val (dc, db) = stack.getStatus(s"$base/data-diff?from=$one&to=$now", token)
    withClue(db)(dc shouldBe 200)
  }

  // ---------- UNDROP TABLE (the gateway's dialect), on both doors, as its caller ----------

  "UNDROP TABLE" should "recover a dropped table as its caller, on both doors" in {
    ready()
    admin("lake", s"CREATE TABLE $lake.main.ud_t (id INTEGER)")
    admin("lake", s"INSERT INTO $lake.main.ud_t VALUES (7)")
    admin("lake", s"DROP TABLE $lake.main.ud_t")
    doors.foreach { d =>
      withClue(s"[$d] bob: ") {
        val back = s"ud_back_${d.toString.toLowerCase(java.util.Locale.ROOT)}"
        as(d, "bob", "lake", s"UNDROP TABLE main.ud_t AS $back") match
          case Right(rows) => rows.mkString should include(s"restored as main.$back")
          case Left(err)   => fail(err)
        as(d, "bob", "lake", s"SELECT id FROM $lake.main.$back") shouldBe Right(List("7"))
      }
    }
  }

  it should "say in plain words when nothing is dropped, the name is taken, or it is not permitted" in {
    ready()
    admin("lake", s"CREATE TABLE $lake.main.ud_taken (id INTEGER)")
    admin("lake", s"DROP TABLE $lake.main.ud_taken")
    admin("lake", s"CREATE TABLE $lake.main.ud_taken (id INTEGER)")
    admin("lake", s"CREATE TABLE $lake.main.ud_denied (id INTEGER)")
    admin("lake", s"DROP TABLE $lake.main.ud_denied")
    doors.foreach { d =>
      withClue(s"[$d] ") {
        as(d, "bob", "lake", "UNDROP TABLE main.ud_nothing") match
          case Left(err) => err should include("there is no dropped table main.ud_nothing")
          case Right(r)  => fail(s"expected a refusal, got $r")
        as(d, "bob", "lake", "UNDROP TABLE main.ud_taken") match
          case Left(err) => err should include("use UNDROP TABLE main.ud_taken AS <new name>")
          case Right(r)  => fail(s"expected a refusal, got $r")
        // nico holds no grant on ud_denied and no DDL in main.
        as(d, "nico", "lake", "UNDROP TABLE main.ud_denied AS ud_nico") match
          case Left(err) => err should include("not permitted")
          case Right(r)  => fail(s"expected a refusal, got $r")
      }
    }
  }

  // ---------- self-service undrop: a regular user's own profile surface ----------

  private def recoverableOf(user: String): List[String] =
    val (code, body) = stack.getStatus(s"/api/profile/recoverable?tenantDb=$lake", session(user))
    withClue(body)(code shouldBe 200)
    io.circe.parser
      .parse(body)
      .toOption
      .get
      .hcursor
      .downField("tables")
      .values
      .toList
      .flatten
      .flatMap(_.hcursor.get[String]("table").toOption)

  "self-service undrop" should "list to a regular user only the dropped tables they may read" in {
    ready()
    dropHistory
    val dina = recoverableOf("dina")
    // Granted and free: drop_free, rn_final (its last name). drop_reused's name is taken again.
    dina should contain allOf ("drop_free", "rn_final")
    dina should not contain "drop_reused"
    dina should not contain "gone_t"
    recoverableOf("olga") should not contain "drop_free"
  }

  it should "recover a table as the regular user, the node judging the read and the create" in {
    ready()
    dropHistory
    // dina may read drop_free but holds no DDL in main: the create is refused.
    val (dc, db) = stack.postStatus(
      "/api/profile/undrop",
      obj(
        "tenantDb" -> s(lake),
        "schema"   -> s("main"),
        "table"    -> s("drop_free"),
        "asName"   -> s("drop_free_dina")
      ),
      session("dina")
    )
    withClue(db)(dc shouldBe 403)
    // The node's refusal of the create (review M8), not a scope or pool refusal.
    db should include("acl_denied")
    db should include("drop_free_dina")
    // bob (`*.*.* ALL`, a regular user) recovers it under a new name, and reads it.
    val (bc, bb) = stack.postStatus(
      "/api/profile/undrop",
      obj(
        "tenantDb" -> s(lake),
        "schema"   -> s("main"),
        "table"    -> s("drop_free"),
        "asName"   -> s("drop_free_bob")
      ),
      session("bob")
    )
    withClue(bb)(bc shouldBe 200)
    allowed("bob", "lake", s"SELECT id FROM $lake.main.drop_free_bob", Some(List("1")))
  }

  private def patOf(user: String, name: String, fields: (String, Json)*): String =
    stack
      .post(
        "/api/auth/pat/create",
        obj((List("name" -> s(s"$name-$run"), "dropAdmin" -> Json.False) ++ fields)*),
        token = session(user)
      )
      .hcursor
      .get[String]("token")
      .fold(e => throw e, identity)

  it should "honour every axis of a token: databases, verb ceiling (review I1)" in {
    ready()
    admin("lake", s"CREATE TABLE $lake.main.ud_scope (id INTEGER)")
    admin("lake", s"DROP TABLE $lake.main.ud_scope")
    val elsewhere = patOf("bob", "ud-elsewhere", "databases" -> Json.arr(s(files)))
    val (lc, lb)  = stack.getStatus(s"/api/profile/recoverable?tenantDb=$lake", elsewhere)
    withClue(lb)(lc shouldBe 403)
    lb should include("token_scope")
    val undropBody =
      obj("tenantDb" -> s(lake), "schema" -> s("main"), "table" -> s("ud_scope"))
    val (uc, ub) = stack.postStatus("/api/profile/undrop", undropBody, elsewhere)
    withClue(ub)(uc shouldBe 403)
    ub should include("token_scope")
    val mcpArgs = Json.obj("database" -> s(lake), "schema" -> s("main"), "table" -> s("ud_scope"))
    stack.mcpTool(elsewhere, "list_recoverable", Json.obj("database" -> s(lake))) match
      case Left(err) => err should include("not permitted for this token")
      case Right(t)  => fail(s"a token scoped elsewhere listed: $t")
    stack.mcpTool(elsewhere, "undrop_table", mcpArgs) match
      case Left(err) => err should include("not permitted for this token")
      case Right(t)  => fail(s"a token scoped elsewhere recovered: $t")
    // A read-only token may not create the table a recovery creates.
    val readOnly = patOf("bob", "ud-ro", "verbCeiling" -> s("RO"))
    val (rc, rb) = stack.postStatus("/api/profile/undrop", undropBody, readOnly)
    withClue(rb)(rc shouldBe 403)
    rb should include("verb ceiling")
    stack.mcpTool(readOnly, "undrop_table", mcpArgs) match
      case Left(err) => err should include("verb ceiling")
      case Right(t)  => fail(s"a read-only token recovered: $t")
  }

  it should "recover through MCP with a regular user's token, and refuse AS on a taken name" in {
    ready()
    admin("lake", s"CREATE TABLE $lake.main.ud_mcp (id INTEGER)")
    admin("lake", s"INSERT INTO $lake.main.ud_mcp VALUES (5)")
    admin("lake", s"DROP TABLE $lake.main.ud_mcp")
    val pat = patOf("bob", "ud-mcp")
    // AS on a name a live table holds: refused before anything runs.
    stack.mcpTool(
      pat,
      "undrop_table",
      Json.obj(
        "database" -> s(lake),
        "schema"   -> s("main"),
        "table"    -> s("ud_mcp"),
        "as_name"  -> s("orders")
      )
    ) match
      case Left(err) => err should include("exists")
      case Right(t)  => fail(s"recovered over a live table: $t")
    val (tc, tb) = stack.postStatus(
      "/api/profile/undrop",
      obj(
        "tenantDb" -> s(lake),
        "schema"   -> s("main"),
        "table"    -> s("ud_mcp"),
        "asName"   -> s("orders")
      ),
      session("bob")
    )
    withClue(tb)(tc shouldBe 409)
    tb should include("name_conflict")
    stack.mcpTool(
      pat,
      "undrop_table",
      Json.obj("database" -> s(lake), "schema" -> s("main"), "table" -> s("ud_mcp"))
    ) shouldBe a[Right[?, ?]]
    allowed("bob", "lake", s"SELECT id FROM $lake.main.ud_mcp", Some(List("5")))
  }

  it should "refuse AS when the SOURCE name was reused: the node refuses the read (review N6)" in {
    ready()
    dropHistory
    // drop_reused was dropped, then a new drop_reused created: AS frees the target, not the source.
    val (code, body) = stack.postStatus(
      "/api/profile/undrop",
      obj(
        "tenantDb" -> s(lake),
        "schema"   -> s("main"),
        "table"    -> s("drop_reused"),
        "asName"   -> s("drop_reused_back")
      ),
      session("bob")
    )
    withClue(body)(code shouldBe 403)
    body should include("acl_denied")
    body should include("its name now belongs to another table or view")
  }

  "the catalog REST endpoints that run SQL as the caller" should "honour a token's databases axis and verb ceiling (review N5)" in {
    ready()
    val (one, two, _) = rordersHistory
    val elsewhere     = patOf("erin", "cat-elsewhere", "databases" -> Json.arr(s(files)))
    val base          = s"/api/catalog/tenant/$Acme/database/$lake/schemas/main/tables/rorders"
    List(s"$base/preview?asOf=$two", s"$base/data-diff?from=$one&to=$two").foreach { path =>
      val (c, b) = stack.getStatus(path, elsewhere)
      withClue(s"$path: $b")(c shouldBe 403)
      b should include("token_scope")
    }
    val restoreBody = obj(
      "tenant"   -> s(Acme),
      "tenantDb" -> s(lake),
      "schema"   -> s("main"),
      "table"    -> s("rorders"),
      "to"       -> s(two.toString)
    )
    val (rc, rb) = stack.postStatus("/api/catalog/restore", restoreBody, elsewhere)
    withClue(rb)(rc shouldBe 403)
    rb should include("token_scope")
    // A read-only token may preview but not restore (a restore replaces the table).
    val readOnly = patOf("erin", "cat-ro", "verbCeiling" -> s("RO"))
    val (pc, pb) = stack.getStatus(s"$base/preview?asOf=$two", readOnly)
    withClue(pb)(pc shouldBe 200)
    val (wc, wb) = stack.postStatus("/api/catalog/restore", restoreBody, readOnly)
    withClue(wb)(wc shouldBe 403)
    wb should include("verb ceiling")
  }

  "UNDROP TABLE names" should "compare without case, as DuckDB resolves them (review M6)" in {
    ready()
    admin("lake", s"CREATE TABLE $lake.main.ud_case (id INTEGER)")
    admin("lake", s"INSERT INTO $lake.main.ud_case VALUES (8)")
    admin("lake", s"DROP TABLE $lake.main.ud_case")
    stack.flight("UNDROP TABLE MAIN.UD_CASE AS ud_case_back", "bob", BobPw, Acme, "lake") match
      case Right(rows) => rows.mkString should include("restored as main.ud_case_back")
      case Left(err)   => fail(err)
    allowed("bob", "lake", s"SELECT id FROM $lake.main.ud_case_back", Some(List("8")))
  }

  "UNDROP TABLE inside a transaction" should "be out of reach over FlightSQL, which opens none across requests (review M5)" in {
    ready()
    admin("lake", s"CREATE TABLE $lake.main.ud_tx (id INTEGER)")
    admin("lake", s"DROP TABLE $lake.main.ud_tx")
    val held = stack.heldFlight("bob", BobPw, Acme, "lake")
    try
      // Each FlightSQL request runs on a node connection of its own: a BEGIN that would hold a
      // transaction across requests is refused, so no later UNDROP runs "inside" one.
      held.run("BEGIN TRANSACTION") match
        case Left(err) => err should include("opens a transaction it does not end")
        case Right(r)  => fail(s"a FlightSQL BEGIN was admitted: $r")
      held.run("ROLLBACK") match
        case Left(err) => err should include("ends a transaction it did not open")
        case Right(r)  => fail(s"a FlightSQL ROLLBACK was admitted: $r")
    finally held.close()
  }

  "a plain view over a renamed table's history" should "be refused by the node to a principal with policies" in {
    ready()
    val (old, _) = vaultHistory
    val view     = s"$lake.main.v_vault_then"
    try
      admin(
        "lake",
        s"CREATE OR REPLACE VIEW $view AS SELECT id FROM $lake.main.vault AT (VERSION => $old)"
      )
      // The policy rewriters see a plain view, no AT clause: only the node's rule stands.
      refused(
        "mona",
        "lake",
        s"SELECT id FROM $view",
        node = "not available to principals with column or row policies"
      )
    finally admin("lake", s"DROP VIEW IF EXISTS $view")
  }

  "DuckLake maintenance, CHECKPOINT and option setting" should "be the superusers'" in {
    ready()
    List(
      "CHECKPOINT",
      s"CHECKPOINT $lake",
      "FORCE CHECKPOINT",
      "/* c */ CHECKPOINT",
      s"CALL checkpoint('$lake')",
      s"SELECT * FROM checkpoint('$lake')",
      s"CALL force_checkpoint('$lake')",
      s"CALL ducklake_merge_adjacent_files('$lake')",
      s"CALL ducklake_cleanup_old_files('$lake', dry_run => true)",
      s"CALL ducklake_expire_snapshots('$lake', dry_run => true, versions => [1])",
      s"CALL ducklake_flush_inlined_data('$lake')",
      s"SELECT * FROM ducklake_set_option('$lake', 'parquet_compression', 'zstd')",
      s"SELECT * FROM ducklake_snapshots('$lake')",
      "FROM merge_adjacent_files()",
      s"FROM $lake.set_option('expire_older_than', '1 second')",
      s"SELECT * FROM query('CALL ducklake_flush_inlined_data(''$lake'')')"
    ).foreach(sql => refused("bob", "lake", sql))
    // The nearest admitted: a superuser's maintenance read (a CHECKPOINT of the DuckLake catalog
    // would run its maintenance and could expire what later cases read).
    admin("lake", s"SELECT count(*) >= 1 FROM ducklake_snapshots('$lake')") shouldBe List("true")
  }

  "a node-wide, resource or path setting" should "be refused to users in every form" in {
    ready()
    List(
      "SET threads = 2",
      "SET worker_threads = 2",
      "SET memory_limit = '100GB'",
      "PRAGMA memory_limit = '100GB'",
      "RESET memory_limit",
      "SET max_temp_directory_size = '1TB'",
      "SET temp_directory = '/tmp/qod-authz-temp'",
      "SET enable_profiling = 'json'",
      "SET profiling_output = '/tmp/qod-authz-profile'",
      "SET checkpoint_threshold = '1GB'",
      "SET file_search_path = '/etc'",
      "SET home_directory = '/etc'",
      "SET allow_asterisks_in_http_paths = true",
      "SET enable_logging = true",
      "SET logging_level = 'DEBUG'",
      "SET force_mbedtls_unsafe = true",
      "SET allowed_directories = ['/']",
      "SET enable_external_access = true",
      "SET disabled_filesystems = ''",
      "SET lock_configuration = false",
      "SET allow_extension_repositories = 'allowed'",
      "SET pg_pool_max_connections = 1",
      "SET ducklake_retry_wait_ms = 86400000",
      "SET \"threads\" = 2",
      "SET`threads`=2",
      "SET/* c */threads = 2",
      " SET threads = 2",
      "EXPLAIN ANALYZE SET threads = 2",
      "SELECT 1; SET threads = 2",
      "RESET GLOBAL search_path",
      "SET qod_no_such_setting = 1"
    ).foreach(sql => refused("bob", "lake", sql))
  }

  "a session setting whose default scope is global" should "be refused unscoped and admitted SET SESSION" in {
    ready()
    // DuckDB 2.0 resolves an unscoped SET of a GLOBAL-default setting to the node's shared config;
    // the describer reports the scope DuckDB resolves (describer.cpp AddSetting).
    List(
      "SET default_collation = 'nocase'",
      "SET default_collation TO 'nocase'",
      "RESET default_collation",
      "PRAGMA default_collation = 'nocase'",
      "SET GLOBAL default_collation = 'nocase'"
    ).foreach(sql => refused("alice", "lake", sql, node = "at global scope"))
    allowed("alice", "lake", "SET SESSION default_collation = 'nocase'")
    allowed("alice", "lake", "RESET SESSION default_collation")
    allowed("alice", "lake", "SET SESSION integer_division = true")
  }

  "a parser or credential setting" should "be refused to users, and changeable by superusers" in {
    ready()
    List(
      "SET dialect_compatibility_mode = 'spark'",
      "RESET dialect_compatibility_mode",
      "RESET preserve_identifier_case",
      "RESET SESSION table_function_identifier_conversion",
      "RESET show_behavior",
      "SET redact_http_logs = false",
      "RESET allow_unredacted_secrets",
      "SET \"redact_http_logs\" = false",
      "EXPLAIN ANALYZE RESET preserve_identifier_case",
      "SELECT 1; RESET dialect_compatibility_mode",
      "PRAGMA import_database('/tmp/qod-authz-import')",
      "IMPORT DATABASE '/tmp/qod-authz-import'",
      "SELECT * FROM query('PRAGMA import_database(''/tmp/qod-authz-import'')')"
    ).foreach(sql => refused("bob", "lake", sql))
    // Intentional change (decision 29): NodeSettingScreen refused these to superusers too; the node
    // leaves a superuser's statement to the superuser. Session-scoped settings, harmless here.
    admin("lake", "RESET preserve_identifier_case")
    admin("lake", "RESET SESSION dialect_compatibility_mode")
    admin("lake", "RESET redact_http_logs")
    // An empty export (no schema, no data): what IMPORT DATABASE replays is the superuser's own.
    val dump = outDir.resolve(s"empty-export-$run")
    Files.createDirectories(dump)
    Files.writeString(dump.resolve("schema.sql"), "")
    Files.writeString(dump.resolve("load.sql"), "")
    // An empty import returns no result set: the trailing SELECT gives the call one to answer.
    admin("lake", s"IMPORT DATABASE '$dump'; SELECT 'imported'") shouldBe List("imported")
    // qod-node is not the DuckDB CLI: shell_history() does not exist there, so a superuser gets
    // DuckDB's catalog error, never a QoD refusal.
    stack.flight(
      "SELECT * FROM shell_history()",
      "admin",
      "admin",
      Acme,
      "lake",
      superuser = true
    ) match
      case Left(err) => err should not include "QoD: "
      case Right(r)  => fail(s"shell_history() answered on a node: $r")
  }

  "a SQL-valued setting" should "be refused to a user without policies, in every form" in {
    ready()
    // The guard screens these only for principals with policies (Task 4): bob and erin hold none,
    // so only the node stands between them and SQL (or a callback) the node runs later, outside
    // every policy: the postgres extension's staleness and health-check queries, quack's
    // authentication and authorization callbacks.
    List("pg_staleness_query", "pg_pool_health_check_query").foreach { name =>
      List(
        s"SET $name = 'SELECT 1'",
        s"SET SESSION $name = 'SELECT 1'",
        s"SET GLOBAL $name = 'SELECT 1'",
        s"RESET $name",
        s"PRAGMA $name = 'SELECT 1'",
        s"EXPLAIN ANALYZE SET $name = 'SELECT 1'",
        s"SELECT 1; SET $name = 'SELECT 1'"
      ).foreach(sql => refused("bob", "lake", sql, node = name))
      refused("erin", "lake", s"SET $name = 'SELECT 1'", node = name)
    }
    List("quack_authentication_function", "quack_authorization_function").foreach { name =>
      List(
        s"SET $name = 'qod_e2e_cb'",
        s"SET GLOBAL $name = 'qod_e2e_cb'",
        s"RESET $name",
        s"RESET GLOBAL $name",
        s"PRAGMA $name = 'qod_e2e_cb'",
        s"EXPLAIN ANALYZE SET GLOBAL $name = 'qod_e2e_cb'",
        s"SELECT 1; RESET $name"
      ).foreach(sql => refused("bob", "lake", sql, node = name))
      refused("erin", "lake", s"SET GLOBAL $name = 'qod_e2e_cb'", node = name)
    }
  }

  "a file reached by another route than a reader" should "be refused outside the authorized folders" in {
    ready()
    List(
      "SELECT * FROM glob('/etc/*')",
      "SELECT count(*) FROM read_blob('/etc/hosts')",
      s"SELECT * FROM parquet_metadata('$lakeData/probe.parquet')",
      "SELECT * FROM sql_auto_complete('SELECT * FROM ''/etc/ho')",
      "PREPARE 'prep_s' AS COPY (SELECT 1) TO '/tmp/qod-authz-prep.csv'",
      "PREPARE $$prep_d$$ AS COPY (SELECT 1) TO '/tmp/qod-authz-prep.csv'",
      "SET file_search_path = '/etc'; SELECT count(*) FROM \"hosts\"",
      s"EXPORT DATABASE '$outDir/exp-$run'",
      s"IMPORT DATABASE '$inDir'",
      s"ATTACH '$outDir/att-$run.duckdb' AS qod_e2e_att",
      "LOAD '/tmp/qod-authz-evil.duckdb_extension'",
      "INSTALL '/tmp/qod-authz-evil.duckdb_extension'",
      "FORCE INSTALL httpfs FROM 'http://127.0.0.1:1'",
      s"COPY FROM DATABASE $lake TO memory",
      "SELECT 1; SELECT * FROM read_text('/etc/hosts')"
    ).foreach(sql => refused("bob", "lake", sql))
    // A table macro whose body reads query_table(source): the path binds to no table.
    List(
      "SELECT * FROM histogram('/etc/hosts', a)",
      "SELECT * FROM histogram_values('/etc/hosts', a)",
      s"SELECT * FROM histogram('$lakeData/probe.parquet', id)"
    ).foreach(sql => missingOrRefused("bob", "lake", sql))
    List("/tmp/qod-authz-prep.csv", s"$outDir/exp-$run", s"$outDir/att-$run.duckdb").foreach { p =>
      withClue(p)(Files.exists(Paths.get(p)) shouldBe false)
    }
    // The nearest admitted: the histogram aggregate over a granted table.
    allowed(
      "bob",
      "lake",
      s"SELECT count(*) FROM (SELECT histogram(id) FROM $lake.main.orders)",
      Some(List("1"))
    )
  }

  "ATTACH, DETACH, INSTALL, LOAD, CONNECT and extension repositories" should "be the superusers'" in {
    ready()
    List(
      "ATTACH ':memory:' AS qod_e2e_mem",
      "DETACH IF EXISTS qod_e2e_absent",
      "INSTALL fts",
      "FORCE INSTALL fts",
      "LOAD httpfs",
      "UPDATE EXTENSIONS",
      "EXPLAIN ANALYZE INSTALL fts",
      "SELECT 1; LOAD httpfs",
      "CONNECT 'quack:127.0.0.1:1'",
      "DISCONNECT",
      "CREATE EXTENSION REPOSITORY qod_e2e_repo WITH PREFIX 'https://127.0.0.1:1/'"
    ).foreach(sql => refused("bob", "lake", sql))
  }

  "a call that leaves the node's catalogs" should "be the superusers'" in {
    ready()
    List(
      "SELECT * FROM postgres_scan('host=127.0.0.1 port=1 dbname=x', 'public', 't')",
      "SELECT * FROM postgres_scan_pushdown('host=127.0.0.1 port=1 dbname=x', 'public', 't')",
      "CALL postgres_execute('pgfed', 'SELECT 1')",
      "SELECT * FROM postgres_query('pgfed', 'SELECT 1')",
      "SELECT * FROM postgres_attach('host=127.0.0.1 port=1 dbname=x')",
      "CALL postgres_configure_pool(health_check_query := 'SELECT 1')",
      "SELECT * FROM quack_query('quack:127.0.0.1:1', 'SELECT 1')",
      "SELECT * FROM destroy_external_resource('read' || '_text', '/etc/hosts')",
      "SELECT * FROM create_external_resource('t', {}, 'r')",
      "SELECT * FROM query('CALL postgres_execute(''pgfed'', ''SELECT 1'')')",
      "FROM query('FROM postgres_' || 'execute(''pgfed'', ''SELECT 1'')')"
    ).foreach(sql => refused("bob", "fed", sql))
  }

  "trigger DDL" should "be the superusers', tenant admins included (decision 29)" in {
    ready()
    admin("files", s"CREATE OR REPLACE TABLE $files.main.trg_src (a INTEGER)")
    admin("files", s"CREATE OR REPLACE TABLE $files.main.trg_log (a INTEGER)")
    admin(
      "files",
      s"CREATE TRIGGER trg_admin AFTER INSERT ON $files.main.trg_src FOR EACH STATEMENT " +
        s"INSERT INTO $files.main.trg_log VALUES (1)"
    )
    val create =
      s"CREATE TRIGGER trg_user AFTER INSERT ON $files.main.trg_src FOR EACH STATEMENT " +
        s"INSERT INTO $files.main.trg_log VALUES (2)"
    List("bob", "erin").foreach { user =>
      List(
        create,
        s"CREATE OR REPLACE TRIGGER trg_admin AFTER INSERT ON $files.main.trg_src " +
          s"FOR EACH STATEMENT INSERT INTO $files.main.trg_log VALUES (3)",
        s"DROP TRIGGER trg_admin ON $files.main.trg_src",
        s"EXPLAIN ANALYZE $create",
        s"SELECT 1; $create"
      ).foreach(sql => refused(user, "files", sql, node = "trigger"))
    }
    // The superuser's trigger still fires for a user who may write the table and its log.
    allowed("bob", "files", s"INSERT INTO $files.main.trg_src VALUES (1)")
    admin("files", s"SELECT count(*) FROM $files.main.trg_log") shouldBe List(doors.size.toString)
  }

  "a macro or a secure view" should "be created by superusers only" in {
    ready()
    List(
      "CREATE MACRO qod_e2e_m(x) AS x * 2",
      "CREATE TEMP MACRO qod_e2e_tm(x) AS x * 2",
      "CREATE MACRO qod_e2e_tbl() AS TABLE SELECT 1 AS a",
      s"CREATE SECURE VIEW $files.main.qod_e2e_sv AS SELECT 1 AS a"
    ).foreach(sql => refused("bob", "files", sql))
  }

  "a grant" should "cover exactly its verb, on the table DuckDB binds" in {
    ready()
    // alice: RW on events, RO on orders, nothing on secret.
    allowed("alice", "lake", s"UPDATE $lake.main.events SET v = 'u' WHERE id = 1")
    allowed("alice", "lake", s"DELETE FROM $lake.main.events WHERE id = 100")
    allowed("alice", "lake", s"SELECT count(*) >= 0 FROM $lake.main.events", Some(List("true")))
    List(
      s"UPDATE $lake.main.orders SET v = 'x'",
      s"DELETE FROM $lake.main.orders",
      s"TRUNCATE $lake.main.orders",
      s"MERGE INTO $lake.main.orders o USING (SELECT 1 AS id) s ON o.id = s.id " +
        "WHEN MATCHED THEN DELETE",
      s"ALTER TABLE $lake.main.events ADD COLUMN w INTEGER",
      s"DROP TABLE $lake.main.orders",
      s"CREATE TABLE $lake.main.qod_e2e_new (a INTEGER)",
      s"CREATE TABLE $lake.main.qod_e2e_ctas AS SELECT * FROM $lake.main.orders",
      s"CREATE VIEW $lake.main.qod_e2e_v AS SELECT * FROM $lake.main.orders",
      s"INSERT INTO $lake.main.events SELECT x, 'leak' FROM $lake.main.secret",
      s"UPDATE $lake.main.events SET v = (SELECT max(x)::VARCHAR FROM $lake.main.secret)",
      s"MERGE INTO $lake.main.events e USING (SELECT x AS id FROM $lake.main.secret) s " +
        "ON e.id = s.id WHEN MATCHED THEN DELETE",
      s"WITH s AS (SELECT x FROM $lake.main.secret) INSERT INTO $lake.main.events " +
        "SELECT x, 'cte' FROM s",
      s"EXPLAIN ANALYZE DELETE FROM $lake.main.orders",
      s"SELECT 1; INSERT INTO $lake.main.orders VALUES (99, 'batch')",
      s"/* c */ INSERT INTO $lake.main.orders VALUES (97, 'comment')",
      s" INSERT INTO $lake.main.orders VALUES (96, 'nbsp')",
      s"COPY $lake.main.secret TO '$outDir/leak-$run.csv' (HEADER)",
      s"COPY $lake.main.orders FROM '$inDir/x.csv' (HEADER)"
    ).foreach(sql => refused("alice", "lake", sql, node = "lacks grants"))
    Files.exists(outDir.resolve(s"leak-$run.csv")) shouldBe false
    admin("lake", s"SELECT count(*) FROM $lake.main.orders") shouldBe List("10")
  }

  "a table read in any position" should "need its grant" in {
    ready()
    val secret = s"$lake.main.secret"
    val orders = s"$lake.main.orders"
    List(
      s"SELECT (SELECT max(x) FROM $secret)",
      s"SELECT count(*) FROM $orders WHERE EXISTS (SELECT 1 FROM $secret)",
      s"SELECT count(*) FROM $orders WHERE id IN (SELECT x FROM $secret)",
      s"SELECT * FROM $orders o, LATERAL (SELECT x FROM $secret) s",
      s"SELECT * FROM ($orders JOIN $secret ON true)",
      s"SELECT id FROM $orders UNION ALL SELECT x FROM $secret",
      s"WITH secret AS (SELECT 1 AS x) SELECT * FROM $secret",
      s"SELECT MAP {'k': (SELECT max(x) FROM $secret)}",
      s"SELECT json_object('k', (SELECT max(x) FROM $secret))",
      s"SELECT ARRAY(SELECT x FROM $secret)",
      s"SELECT 42 = ANY (SELECT x FROM $secret)",
      s"SELECT ARRAY(TABLE $secret)",
      s"SELECT 1 WHERE 42 = ANY (TABLE $secret)",
      s"SELECT id FROM $orders ORDER BY (SELECT max(x) FROM $secret)",
      s"FROM $secret",
      s"PIVOT $secret ON x IN (42) USING count(*)",
      s"SET VARIABLE leak = (SELECT max(x) FROM $secret); SELECT getvariable('leak')",
      s"PREPARE leak_p AS SELECT x FROM $secret; EXECUTE leak_p",
      s"SELECT * FROM query('SELECT x FROM $secret')",
      s"SELECT * FROM query_table('$secret')",
      s"SELECT 1 AS x$$$$; SELECT x FROM $secret; -- $$$$"
    ).foreach(sql => refused("alice", "lake", sql, node = "lacks grants"))
    // The nearest admitted: the same shapes over the granted table, and a CTE named like the secret.
    List(
      s"SELECT (SELECT max(id) FROM $orders)"                       -> "9",
      "WITH secret AS (SELECT 1 AS x) SELECT x FROM secret"         -> "1",
      s"PREPARE ok_p AS SELECT count(*) FROM $orders; EXECUTE ok_p" -> "10",
      s"SELECT * FROM query('SELECT count(*) FROM $orders')"        -> "10",
      s"SELECT count(*) FROM query_table('$orders')"                -> "10",
      s"SELECT len(ARRAY(TABLE $orders))"                           -> "10"
    ).foreach((sql, row) => allowed("alice", "lake", sql, Some(List(row))))
  }

  "a batch that moves the schema or search path" should "be judged on the table DuckDB resolves" in {
    ready()
    admin("lake", s"CREATE SCHEMA IF NOT EXISTS $lake.other")
    admin("lake", s"CREATE OR REPLACE TABLE $lake.other.orders AS SELECT 'other' AS v")
    List(
      s"SET search_path = '$lake.other'; SELECT count(*) FROM orders",
      s"SET SESSION search_path TO '$lake.other,$lake.main'; SELECT count(*) FROM orders",
      "SET schema = 'other'; SELECT count(*) FROM orders",
      s"USE $lake.other; SELECT count(*) FROM orders",
      "USE other; SELECT count(*) FROM orders",
      s"PRAGMA search_path = '$lake.other'; SELECT count(*) FROM orders",
      s"SET \"search_path\" = '$lake.other'; SELECT count(*) FROM orders",
      s"/* c */ SET search_path = '$lake.other'; SELECT count(*) FROM orders",
      s"EXPLAIN ANALYZE SET search_path = '$lake.other'; SELECT count(*) FROM orders",
      s"SELECT 1 AS x$$$$; SET search_path = '$lake.other'; SELECT count(*) FROM orders; -- $$$$"
    ).foreach(sql => refused("alice", "lake", sql, node = s"$lake.other.orders"))
    // A batch that resolves to the granted table: admitted (the gateway refused every such batch).
    allowed("alice", "lake", s"USE $lake.main; SELECT count(*) FROM orders", Some(List("10")))
    allowed("alice", "lake", "SET schema = 'main'; SELECT count(*) FROM orders", Some(List("10")))
  }

  "a quoted name" should "be judged as the one table DuckDB reads" in {
    ready()
    // alice holds RO on pub only; each quoted spelling below is another table to DuckDB, which the
    // gateway's parser read as pub (or main.pub).
    admin("files", s"CREATE OR REPLACE TABLE $files.main.pub AS SELECT 'pub' AS v")
    List("\"main.pub\"", "\"[pub]\"", "\"pub@x\"", "\"pub\"\"x\"").foreach { q =>
      admin("files", s"CREATE OR REPLACE TABLE $files.main.$q AS SELECT 'leak' AS v")
    }
    grant(files, "main", "pub", "RO")
    List(
      "SELECT v FROM \"main.pub\"",
      s"SELECT v FROM $files.main.\"main.pub\"",
      "SELECT v FROM `main.pub`",
      "SELECT v FROM \"[pub]\"",
      "SELECT v FROM `[pub]`",
      "SELECT v FROM \"pub@x\"",
      "SELECT v FROM \"pub\"\"x\"",
      "SELECT 1; SELECT v FROM \"main.pub\"",
      "EXPLAIN ANALYZE SELECT v FROM \"main.pub\""
    ).foreach(sql => refused("alice", "files", sql, node = "lacks grants"))
    List(
      "SELECT v FROM pub",
      "SELECT v FROM \"main\".\"pub\"",
      "SELECT v FROM `pub`",
      s"SELECT v FROM $files.main.pub"
    ).foreach(sql => allowed("alice", "files", sql, Some(List("pub"))))
  }

  "a two-part name" should "be judged as DuckDB resolves it" in {
    ready()
    allowed("alice", "lake", s"SELECT count(*) FROM $lake.orders", Some(List("10")))
    allowed("alice", "lake", "SELECT count(*) FROM main.orders", Some(List("10")))
    refused("alice", "lake", s"SELECT * FROM $lake.secret", node = "lacks grants")
    refused("alice", "lake", s"INSERT INTO $lake.orders VALUES (98, 'two')", node = "lacks grants")
    refused("alice", "lake", s"DROP TABLE $lake.events", node = "lacks grants")
    refused("alice", "aliased", s"SELECT v FROM $Alias.aliased_u", node = "lacks grants")
    allowed("alice", "aliased", s"SELECT v FROM $Alias.aliased_t", Some(List("5")))
  }

  "a federated table" should "be judged beside the pool's own catalog, by its own grant" in {
    ready()
    // The PostgresAclValidator cases of PostgresFederationE2ESpec, on the node: alice holds RW on
    // pgfed.e2e.orders only; the fed pool's own catalog is DuckDB's memory.
    admin("fed", "CREATE OR REPLACE TABLE memory.main.fed_local AS SELECT 1 AS id")
    val join = "SELECT count(*) FROM memory.main.fed_local l JOIN pgfed.e2e.orders o ON l.id = o.id"
    // Only the federated side granted: refused on the local side.
    refused("alice", "fed", join, node = "memory.main.fed_local")
    val local = grant("memory", "main", "fed_local", "RO")
    try
      allowed("alice", "fed", join, Some(List("1")))
      // Only the local side granted: refused on the federated side.
      refused(
        "alice",
        "fed",
        "SELECT count(*) FROM memory.main.fed_local l JOIN pgfed.e2e.other o ON true",
        node = "pgfed.e2e.other"
      )
      // A federated grant takes effect on the next statement, and so does its revoke.
      val other = grant("pgfed", "e2e", "other", "RO")
      allowed("alice", "fed", "SELECT count(*) FROM pgfed.e2e.other", Some(List("0")))
      stack.post("/api/role/permission/revoke", obj("id" -> s(other)))
      refused("alice", "fed", "SELECT count(*) FROM pgfed.e2e.other", node = "pgfed.e2e.other")
    finally stack.post("/api/role/permission/revoke", obj("id" -> s(local)))
    // A catalog-wide federated grant (carol's pgfed.e2e.* ALL) covers the source; *.*.* does not.
    allowed("carol", "fed", "SELECT count(*) FROM pgfed.e2e.other", Some(List("0")))
    refused("bob", "fed", "SELECT count(*) FROM pgfed.e2e.other", node = "pgfed.e2e.other")
  }

  "a catalog listing" should "show a user only the tables they hold a grant on" in {
    ready()
    val sql = s"SELECT table_name FROM duckdb_tables() WHERE database_name = '$lake' " +
      "AND schema_name = 'main' ORDER BY 1"
    doors.foreach { d =>
      withClue(s"[$d] ") {
        val mine = as(d, "alice", "lake", sql)
        mine shouldBe a[Right[?, ?]]
        mine.toOption.get should contain allOf ("events", "orders")
        mine.toOption.get should not contain "secret"
        val all = as(d, "bob", "lake", sql)
        all shouldBe a[Right[?, ?]]
        all.toOption.get should contain("secret")
      }
    }
  }

  it should "not reach other tables' names or view definitions through DuckDB's built-in views" in {
    ready()
    val marker = s"qod-c1-$run"
    admin("lake", s"CREATE OR REPLACE VIEW $lake.main.v_c1 AS SELECT '$marker' AS m")
    // Each lists every attached catalog on DuckDB 2.0.0 as the superuser sees it: pg_views prints
    // every view's SQL, the others the names of tables olga holds no grant on.
    val listings = List(
      "SELECT viewname, definition FROM pg_catalog.pg_views",
      "SELECT viewname, definition FROM pg_views",
      "SELECT schemaname, tablename FROM pg_tables",
      "SELECT attname FROM pg_attribute",
      "SELECT relname FROM pg_catalog.pg_class",
      "SELECT name, sql FROM sqlite_master",
      "SELECT table_name, sql FROM duckdb_tables",
      "SELECT view_name, sql FROM system.main.duckdb_views",
      "SELECT table_catalog, table_name FROM system.information_schema.tables",
      "SELECT table_name, column_name FROM system.information_schema.columns",
      "SELECT table_name, view_definition FROM system.information_schema.views",
      "SET search_path = 'system.information_schema'; SELECT table_name FROM tables",
      "SELECT * FROM query('SELECT table_name FROM information_schema./**/tables')",
      "SELECT * FROM query('SELECT table_name FROM information_schema.--x\ntables')",
      "SELECT * FROM query(E'SELECT table_name FROM information\\x5fschema.tables')",
      "SELECT * FROM query('SELECT view_definition FROM information_schema.\"views\"')",
      "SELECT * FROM query_table('information_schema.tables')",
      "SELECT * FROM query('SELECT definition FROM pg_views')"
    ) ++
      // Re-review C1-a: a comment inside the name, in a statement jsqlparser cannot parse (`^@`).
      (for
        view    <- List("tables", "columns", "views")
        comment <- List("/**/", "--x\n")
      yield
        val cols = if view == "views" then "table_name, view_definition" else "table_name"
        s"SELECT table_catalog, $cols FROM information_schema$comment.$view " +
          "WHERE table_name ^@ ''") ++ List(
        // Re-review C1-b: DuckDB's SHOW family binds PragmaShowTables over the listing functions.
        "SHOW",
        "DESCRIBE",
        "DESC",
        s"SHOW TABLES FROM $lake.main",
        "SHOW TABLES FROM main",
        "SELECT * FROM (SHOW ALL TABLES)",
        "SELECT * FROM (SHOW)",
        "SELECT * FROM (SHOW TABLES FROM main)",
        "WITH x AS (DESCRIBE) SELECT * FROM x",
        "SELECT * FROM query('SHOW ALL TABLES')",
        "SELECT * FROM query('DESCRIBE')"
      )
    // The superuser's probe sees what olga must not, so a refusal or an empty answer is sound.
    admin("lake", "SELECT viewname, definition FROM pg_catalog.pg_views")
      .exists(_.contains(marker)) shouldBe true
    def leaks(rows: List[String]): List[String] =
      rows.filter(r =>
        r.contains(marker) || r.contains("__ducklake_metadata") ||
          r.split(',').exists(c => c == "secret" || c == "orders")
      )
    // Rows whose answer names no table or view SQL the leak check could spot (column names, oids):
    // only a refusal is safe for them.
    val refusalOnly =
      Set("SELECT attname FROM pg_attribute", "SELECT relname FROM pg_catalog.pg_class")
    // olga reaches the pool on every door: a refusal below is a refusal, never a failed sign-in.
    allowed("olga", "lake", s"SELECT count(*) FROM $lake.main.events")
    // Every statement's outcome on every door, so one run shows each that leaks.
    val leaked = doors.flatMap { d =>
      listings.flatMap { sql =>
        as(d, "olga", "lake", sql) match
          // Refused by the node, by the gateway's metadata filter, or by DuckDB itself.
          case Left(_)                      => None
          case Right(r) if refusalOnly(sql) => Some(s"[$d] $sql -> answered ${r.size} rows")
          case Right(r) if leaks(r).isEmpty => None
          case Right(r)                     => Some(s"[$d] $sql -> ${leaks(r).take(3)}")
      }
    }
    withClue(s"olga:\n${leaked.mkString("\n")}\n")(leaked shouldBe empty)
    doors.foreach { d =>
      // The nearest admitted: the filtered listings, which name only olga's table.
      withClue(s"[$d] olga: the filtered listings -> ") {
        as(
          d,
          "olga",
          "lake",
          s"SELECT table_name FROM information_schema.tables " +
            s"WHERE table_catalog = '$lake' ORDER BY 1"
        ) shouldBe Right(List("events"))
        as(
          d,
          "olga",
          "lake",
          s"SELECT table_name FROM duckdb_tables() " +
            s"WHERE database_name = '$lake' ORDER BY 1"
        ) shouldBe Right(List("events"))
      }
    }
  }

  it should "keep SHOW TABLES and SHOW ALL TABLES for every tenant user, filtered to their grants" in {
    ready()
    doors.foreach { d =>
      withClue(s"[$d] ") {
        as(d, "olga", "lake", "SHOW TABLES") shouldBe Right(List("events"))
        val all = as(d, "olga", "lake", "SHOW ALL TABLES")
        all shouldBe a[Right[?, ?]]
        all.toOption.get.map(_.split(',').take(3).mkString(",")) shouldBe List(s"$lake,main,events")
        // A wildcard-ALL principal, whose listings the filter leaves whole, keeps both forms.
        val bobs = as(d, "bob", "lake", "SHOW TABLES")
        bobs shouldBe a[Right[?, ?]]
        bobs.toOption.get should contain allOf ("events", "orders", "secret")
        as(d, "bob", "lake", "SHOW ALL TABLES") shouldBe a[Right[?, ?]]
        // DESCRIBE of a granted table still answers; of an ungranted one the node refuses.
        as(d, "olga", "lake", "DESCRIBE events").map(_.map(_.split(',').head)) shouldBe
          Right(List("id", "v"))
        as(d, "olga", "lake", "DESCRIBE secret") shouldBe a[Left[?, ?]]
      }
    }
  }

  // Re-review I-a: the filtered information_schema rows were pinned to a schema and a name, never
  // to a catalog, so a grant on lake.main.events also listed every other attached catalog's
  // main.events, its view SQL included.
  it should "pin the filtered information_schema listings to the granted catalog" in {
    ready()
    val marker = s"qod-ia-$run"
    admin("lake", s"CREATE OR REPLACE VIEW memory.main.events AS SELECT '$marker' AS m")
    try
      admin(
        "lake",
        "SELECT view_definition FROM information_schema.views WHERE " +
          "table_catalog = 'memory'"
      ).exists(_.contains(marker)) shouldBe true
      doors.foreach { d =>
        withClue(s"[$d] ") {
          as(
            d,
            "olga",
            "lake",
            "SELECT table_catalog, view_definition FROM " +
              "information_schema.views"
          ) match
            case Left(err) => fail(s"the filtered views listing was refused: $err")
            case Right(r)  => r.filter(_.contains(marker)) shouldBe empty
          as(
            d,
            "olga",
            "lake",
            "SELECT DISTINCT table_catalog FROM information_schema.tables " +
              "WHERE table_name = 'events'"
          ) shouldBe Right(List(lake))
          as(
            d,
            "olga",
            "lake",
            "SELECT DISTINCT table_catalog FROM information_schema.columns " +
              "WHERE table_name = 'events'"
          ) shouldBe Right(List(lake))
        }
      }
    finally admin("lake", "DROP VIEW IF EXISTS memory.main.events")
  }

  // Re-review I-a2: one session-catalog pin for every grant hid a granted federated catalog from
  // the listings, GetTables and DESCRIBE. A grant naming the catalog lists its objects there; the
  // wildcard catalog covers the pool's own catalog only, on the node and so in the listings.
  it should "list a granted federated catalog's tables, and nothing of it to anyone else" in {
    ready()
    val inPgfed = "SELECT table_catalog, table_schema, table_name FROM information_schema.tables " +
      "WHERE table_catalog = 'pgfed' ORDER BY 3"
    doors.foreach { d =>
      withClue(s"[$d] ") {
        // alice holds RW on pgfed.e2e.orders by name, and nothing else of pgfed.
        as(d, "alice", "fed", inPgfed) shouldBe Right(List("pgfed,e2e,orders"))
        as(
          d,
          "alice",
          "fed",
          "SELECT table_name FROM duckdb_tables() " +
            "WHERE database_name = 'pgfed'"
        ) shouldBe Right(List("orders"))
        val described = as(d, "alice", "fed", "DESCRIBE pgfed.e2e.orders")
        described shouldBe a[Right[?, ?]]
        described.toOption.get should not be empty
        // The wildcard covers the pool's own catalog, never a federated one: pete reads none of
        // pgfed, so none of it is listed to him; olga holds nothing on this pool's catalogs.
        as(d, "pete", "fed", "SELECT count(*) FROM pgfed.e2e.orders") shouldBe a[Left[?, ?]]
        as(d, "pete", "fed", inPgfed) shouldBe Right(Nil)
        as(d, "olga", "fed", inPgfed) shouldBe Right(Nil)
        as(
          d,
          "olga",
          "fed",
          "SELECT table_name FROM duckdb_tables() " +
            "WHERE database_name = 'pgfed'"
        ) shouldBe Right(Nil)
      }
    }
    val tables = stack.flightTables("alice", AlicePw, Acme, "fed")
    tables shouldBe a[Right[?, ?]]
    tables.toOption.get.filter(_.startsWith("pgfed,")) shouldBe List("pgfed,e2e,orders,TABLE")
    stack.flightTables("olga", OlgaPw, Acme, "fed").map(_.filter(_.startsWith("pgfed,"))) shouldBe
      Right(Nil)
  }

  "a view's definition" should "not be readable through pg_get_viewdef without being a superuser" in {
    ready()
    val marker = s"qod-def-$run"
    admin("files", s"CREATE OR REPLACE VIEW $files.main.v_def AS SELECT '$marker' AS m")
    // A duckdb-file catalog keeps its entries' oids for the node's life: the superuser's probe
    // finds the definition, so the probe is sound and only a refusal is a safe answer.
    val oid = admin(
      "files",
      s"SELECT view_oid FROM duckdb_views() WHERE database_name = '$files' AND view_name = 'v_def'"
    ) match
      case List(one) => one.toLong
      case other     => fail(s"v_def has no single oid: $other")
    val probe = s"SELECT count(*) FROM range(${oid - 100000}, ${oid + 100000}) r(o) " +
      s"WHERE pg_get_viewdef(o) LIKE '%$marker%'"
    admin("files", probe) shouldBe List("1")
    refused("alice", "files", probe, node = "pg_get_viewdef")
    refused("bob", "files", probe, node = "pg_get_viewdef")
    refused("alice", "files", "SELECT pg_catalog.pg_get_viewdef(1)", node = "pg_get_viewdef")
    refused("alice", "files", "SELECT \"pg_get_viewdef\"(1)", node = "pg_get_viewdef")
    refused("alice", "files", "SELECT pg_get_constraintdef(1)", node = "pg_get_constraintdef")
    // The nearest admitted: a pg_catalog macro classed allowed.
    allowed("alice", "files", "SELECT pg_typeof(1) IS NOT NULL", Some(List("true")))
  }

  "a client data scan on the FlightSQL path" should "be refused at once, never wait for rows no client sends" in {
    ready()
    val sql = s"INSERT INTO $lake.main.events SELECT * FROM scan_data_from_quack_client(" +
      "'0f8e2c3a-1b2c-4d5e-8f90-0123456789ab', NULL::STRUCT(id INTEGER, v VARCHAR), " +
      "ordered := true)"
    val pending = scala.concurrent.Future(stack.flight(sql, "alice", AlicePw, Acme, "lake"))(using
      scala.concurrent.ExecutionContext.global
    )
    // Task 4's gateway refusal, before any node connection: never a wait for rows no client sends.
    scala.concurrent.Await.result(pending, 60.seconds) match
      case Left(err) => err should include("scan_data_from_quack_client")
      case Right(r)  => fail(s"a client data scan ran on the FlightSQL path: $r")
  }

  "a client-fed INSERT through an ATTACHed front door" should "be judged on its target" in {
    ready()
    assume(AuthzStack.cliPresent, "no DuckDB 2.0 CLI on PATH")
    stack.attached(
      "INSERT INTO q.main.events VALUES (300, 'door');",
      "alice",
      AlicePw,
      Acme,
      "lake"
    ) shouldBe a[Right[?, ?]]
    stack.attached(
      "INSERT INTO q.main.orders VALUES (301, 'door');",
      "alice",
      AlicePw,
      Acme,
      "lake"
    ) match
      case Left(err) => err should include("QoD: ")
      case Right(r)  => fail(s"alice wrote orders through the front door: $r")
    admin("lake", s"SELECT count(*) FROM $lake.main.events WHERE id = 300") shouldBe List("1")
    admin("lake", s"SELECT count(*) FROM $lake.main.orders WHERE id = 301") shouldBe List("0")
  }

  "a read-only federated catalog" should "refuse every write, in every spelling, to everyone" in {
    ready()
    // Task 2's fixture: pool rofed; rosql (sql source over a file attached READ_WRITE, read-only in
    // QoD, table main.t holding one row, alice RW on it), pgro (typed Postgres, read-only, no user
    // grant). Task 2 pins the plain INSERT / UPDATE / DELETE / CREATE TABLE / DROP TABLE; these are
    // the CatalogWriteScreenSpec forms only the gateway's text screen refused before Task 4.
    Files.writeString(inDir.resolve("ro.csv"), "v\n9\n")
    val ro = "catalog rosql is read-only"
    List(
      "ALTER TABLE rosql.main.t ADD COLUMN y INTEGER",
      "CREATE SCHEMA rosql.qod_e2e_s",
      "CREATE TABLE rosql.main.qod_e2e_ctas AS SELECT v FROM rwsql.main.t",
      "CREATE VIEW rosql.main.qod_e2e_v AS SELECT 1 AS a",
      s"COPY rosql.main.t FROM '${inDir.resolve("ro.csv")}' (HEADER)",
      "INSERT INTO rosql.main.t SELECT v FROM rwsql.main.t",
      "MERGE INTO rosql.main.t d USING (SELECT 1 AS v) s ON d.v = s.v WHEN MATCHED THEN DELETE",
      "SELECT 1; INSERT INTO rosql.main.t VALUES (2)",
      "INSERT INTO rosql.main.t VALUES (3); SELECT 1",
      "SELECT 1; INSERT INTO rosql.main.t VALUES (4); SELECT 2",
      "PREPARE ro_w AS INSERT INTO rosql.main.t VALUES (5); EXECUTE ro_w",
      "USE rosql.main; INSERT INTO t VALUES (6)",
      "/* c */ INSERT INTO rosql.main.t VALUES (7)",
      "\u00a0INSERT INTO rosql.main.t VALUES (8)",
      "INSERT INTO rosql.main.t VALUES (9) /* \u00a0 */",
      "EXPLAIN ANALYZE INSERT INTO rosql.main.t VALUES (10)",
      "BEGIN; INSERT INTO rosql.main.t VALUES (11); COMMIT"
    ).foreach { sql =>
      refused("alice", "rofed", sql, node = ro)
      withClue(s"admin: $sql -> ") {
        stack.flight(sql, "admin", "admin", Acme, "rofed", superuser = true) match
          case Left(err) => err should include(ro)
          case Right(r)  => fail(s"a superuser wrote a read-only catalog: $r")
      }
    }
    // The alias folded as DuckDB folds it: a quoted upper-case spelling is the same catalog.
    refused("alice", "rofed", "INSERT INTO \"ROSQL\".main.t VALUES (12)", node = "is read-only")
    // Attach and detach naming a read-only catalog, and the typed source: superusers too.
    List(
      "DETACH rosql",
      "INSERT INTO pgro.e2e.orders SELECT * FROM pgro.e2e.orders WHERE false",
      "UPDATE pgro.e2e.orders SET v = v WHERE false",
      "DELETE FROM pgro.e2e.orders WHERE false",
      "USE pgro.e2e; DELETE FROM orders WHERE false"
    ).foreach { sql =>
      withClue(s"admin: $sql -> ") {
        stack.flight(sql, "admin", "admin", Acme, "rofed", superuser = true) match
          case Left(err) => err should include("is read-only")
          case Right(r)  => fail(s"a superuser wrote or detached a read-only catalog: $r")
      }
    }
    // Reads stay admitted, and nothing was written.
    allowed("alice", "rofed", "SELECT count(*), min(v) FROM rosql.main.t", Some(List("1,1")))
    admin("rofed", "SELECT count(*), min(v) FROM rosql.main.t") shouldBe List("1,1")
  }

  "an OPA tenant's statements" should "be decided by its policy on the node, fail closed" in {
    ready()
    val opa = new FakeOpa(denyTable = "secret")
    opa.start()
    def setAcl(fields: (String, Json)*): Unit =
      stack.post("/api/tenant/setAcl", obj((("name" -> s(Acme)) +: fields)*))
    setAcl("mode" -> s("opa"), "opaUrl" -> s(opa.url))
    try
      // The policy decides, whatever the QoD grants say: alice holds no grant on aliased_u.
      allowed("alice", "aliased", s"SELECT max(v) + 1 FROM $Alias.main.aliased_u", Some(List("7")))
      refused(
        "alice",
        "lake",
        s"SELECT min(x) FROM $lake.main.secret",
        node = "denied by policy"
      )
      // A local rule refuses before OPA is asked.
      val asked = opa.statementCalls
      refused("alice", "lake", "SELECT current_setting('TimeZone')", node = "current_setting")
      refused("alice", "lake", "SELECT * FROM duckdb_secrets()", node = "duckdb_secrets")
      opa.statementCalls shouldBe asked
      // An outage is retryable, never an allow: a plan no case decided before.
      opa.failStatements.set(true)
      refused(
        "alice",
        "lake",
        s"SELECT max(length(v)) FROM $lake.main.orders",
        node = "authorization unavailable, retry"
      )
      opa.failStatements.set(false)
      // Superusers never reach OPA.
      admin("lake", s"SELECT min(x) FROM $lake.main.secret") shouldBe List("42")
    finally
      try setAcl("mode" -> s("qod"))
      finally opa.stop()
    allowed("alice", "lake", s"SELECT count(*) FROM $lake.main.orders", Some(List("10")))
  }
