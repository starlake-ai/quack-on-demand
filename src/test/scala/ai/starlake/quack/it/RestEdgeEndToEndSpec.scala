package ai.starlake.quack.it

import ai.starlake.quack.it.authz.AuthzStack
import ai.starlake.quack.ondemand.runtime.QodNodeTestBinary
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.security.{JwtTestSigner, MockOidcServer}
import com.github.tomakehurst.wiremock.client.WireMock.{get as wmGet, okJson, urlPathEqualTo}
import io.circe.Json
import io.circe.parser.parse
import org.apache.arrow.memory.RootAllocator
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** End-to-end spec of the REST data edge: real HTTP against the edge of a real manager booted
  * in-process ([[AuthzStack]], `quack-rest` turned on), whose nodes are qod-node processes deciding
  * every statement of a tenant user through the manager's decision endpoint. Nothing on the request
  * path is a test double: the PATs are minted and revoked through the `/api/auth/pat` routes, the
  * bearer JWTs are verified by acme's own OIDC provider (a WireMock IdP with a local JWKS,
  * configured through `/api/tenant/setAuth`), and the data is written by the seeded superuser
  * through the FlightSQL edge.
  *
  * Environment gate: every test CANCELS (never fails, never aborts the suite) on Windows (the stack
  * spawns nodes through the bash script and reaps them with kill-quack-nodes.sh), without the test
  * Postgres (`SL_TEST_PG_*`), or without a qod-node build (`QOD_NODE_BIN`). A setup step that fails
  * for any other reason is a bug and FAILS every test.
  */
class RestEdgeEndToEndSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  import RestEdgeEndToEndSpec.*

  private val run    = java.lang.Long.toHexString(System.nanoTime() & 0xffffffL)
  // Tenant ids are letters and digits only.
  private val Acme   = s"acmerestx$run"
  private val Globex = s"globexrestx$run"

  private val AlicePw = "alice-rest-e2e-pw-1"
  private val BobPw   = "bob-rest-e2e-pw-1"

  private lazy val missing: Option[String] =
    if scala.util.Properties.isWin then Some("the bash spawn script and kill-quack-nodes.sh")
    else if !TestPostgres.reachable then
      Some(s"test Postgres not reachable at ${TestPostgres.pgHost}:${TestPostgres.pgPort}")
    else if QodNodeTestBinary.path.isEmpty then Some("no qod-node build (QOD_NODE_BIN)")
    else None

  // The real paths: the node resolves symlinks before judging (macOS /var is /private/var).
  private lazy val work: Path = Files.createTempDirectory("qod-rest-e2e").toRealPath()

  private var stack: AuthzStack               = null
  private var idp: MockOidcServer.Server      = null
  private var tokens: Tokens                  = null
  private var lakeDb: String                  = "" // acme's DuckLake tenant-db, also its catalog
  private var fileDb: String                  = "" // acme's encrypted duckdb-file tenant-db
  private var memDb: String                   = "" // acme's memory tenant-db
  private var setupFailure: Option[Throwable] = None

  // ---------- setup ----------

  override def beforeAll(): Unit =
    if missing.isEmpty then
      try
        idp = MockOidcServer.boot()
        // acme's provider is a Keycloak realm on the mock IdP: its JWKS at the realm's path.
        idp.wireMockServer.stubFor(
          wmGet(urlPathEqualTo(s"/realms/$Realm/protocol/openid-connect/certs"))
            .willReturn(okJson(JwtTestSigner.jwksJson().noSpaces))
        )
        stack = new AuthzStack(
          "rest_e2e",
          restPort = 20991,
          flightPort = 31391,
          quackPort = 29491,
          relayPort = 20992,
          minNodePort = 23300,
          maxNodePort = 23309,
          filesRoot = work,
          relayed = false,
          stackPrefixes = List("acmerestx", "globexrestx"),
          extraConfig = s"""quack-rest {
                           |  enabled = true
                           |  host = "127.0.0.1"
                           |  port = $RestDataPort
                           |  tlsEnabled = false
                           |  defaultLimit = $DefaultLimit
                           |  maxRows = $MaxRows
                           |  stmtTimeoutSec = 120
                           |}""".stripMargin
        )
        stack.boot()
        provisionWorld()
        provisionData()
        provisionCredentials()
        provisionOidc()
      catch case t: Throwable => setupFailure = Some(t)

  override def afterAll(): Unit =
    try if stack != null then stack.stop()
    finally
      Try(Option(idp).foreach(_.shutdown()))
      Try {
        val tree = Files.walk(work)
        try tree.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
        finally tree.close()
      }

  /** Cancels without the environment, fails every test after a broken setup. */
  override def withFixture(test: NoArgTest): org.scalatest.Outcome =
    missing match
      case Some(m) => org.scalatest.Canceled(s"REST end-to-end needs $m")
      case None    =>
        setupFailure match
          case Some(t) => org.scalatest.Failed(s"end-to-end setup failed: $t", t)
          case None    => super.withFixture(test)

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

  private def createPool(
      tenant: String,
      tenantDb: String,
      pool: String,
      suspended: Boolean = false
  ): Unit =
    stack.post(
      "/api/pool/create",
      s"""{"tenant":"$tenant","tenantDb":"$tenantDb","pool":"$pool","size":1,
         |"roleDistribution":{"writeonly":0,"readonly":0,"dual":1},
         |"startSuspended":$suspended}""".stripMargin
    )

  private def provisionWorld(): Unit =
    List(Acme, Globex).foreach { t =>
      stack.post(
        "/api/tenant/create",
        obj("id" -> s(t), "displayName" -> s(t), "metastore" -> Json.obj())
      )
    }
    lakeDb = createDb(Acme, "lake", "ducklake", "dataPath" -> s(s"${work.resolve("lake")}/"))
    // A duckdb-file database names its catalog: the tenant-db name, as the other kinds get.
    fileDb = createDb(
      Acme,
      "file",
      "duckdb-file",
      "dataPath"  -> s(work.resolve("acme_file.duckdb").toString),
      "metastore" -> Json.obj("dbName" -> s(s"${Acme}_file"), "schemaName" -> s(FileSchema)),
      "encrypted" -> Json.True
    )
    require(fileDb == s"${Acme}_file", s"tenant-db $fileDb is not named as its catalog")
    // The memory kind has nothing on disk: each node builds its table from the initSql.
    memDb = createDb(Acme, "mem", "memory", "initSql" -> s(SeedMemorySql))
    createPool(Acme, lakeDb, "sales")
    createPool(Acme, fileDb, "files")
    createPool(Acme, memDb, "mem")
    // Sorts after `sales`, so a request that names no pool never picks it.
    createPool(Acme, lakeDb, ColdPool, suspended = true)

    // alice reads a chosen set in acme through one role; bob belongs to globex.
    val reader = stack
      .post("/api/role/create", obj("tenant" -> s(Acme), "name" -> s("rest_reader")))
      .hcursor
      .get[String]("id")
      .fold(e => throw e, identity)
    List(
      (lakeDb, LakeSchema, "orders"),
      (lakeDb, LakeSchema, "v_orders"),
      (lakeDb, LakeSchema, "notes"),
      (fileDb, FileSchema, "f"),
      ("memory", "main", "m")
    ).foreach { case (c, sch, t) =>
      stack.post(
        "/api/role/permission/grant",
        obj(
          "roleId"  -> s(reader),
          "catalog" -> s(c),
          "schema"  -> s(sch),
          "table"   -> s(t),
          "verb"    -> s("RO")
        )
      )
    }
    def user(tenant: String, name: String, pw: String, role: String): Unit =
      stack.post(
        "/api/user/create",
        obj(
          "tenant"   -> s(tenant),
          "username" -> s(name),
          "password" -> s(pw),
          "roles"    -> Json.arr(s(role)),
          "groups"   -> Json.arr(s("qod_all_pools"))
        )
      )
    user(Acme, "alice", AlicePw, "rest_reader")
    user(Globex, "bob", BobPw, "qod_all_tables")

  /** `sql` as the seeded superuser on `pool` (FlightSQL). */
  private def admin(pool: String, sql: String): List[String] =
    stack
      .flight(sql, "admin", "admin", Acme, pool, superuser = true)
      .fold(err => throw new IllegalStateException(s"admin on $pool: $sql -> $err"), identity)

  /** Until the pool's node serves (its first node is still starting right after the create). */
  private def awaitPool(pool: String, probe: String = "SELECT 1"): Unit =
    stack.await(s"pool $pool serving", 120.seconds) {
      stack.flight(probe, "admin", "admin", Acme, pool, superuser = true).toOption
    }

  /** `orders`: ids 10..100, odd steps alice and even ones bob, totals 1.5 x id, one hour apart;
    * `v_orders` over it; `secret`, never granted; `notes`, one 4 KiB value for the LIKE cost case.
    * The encrypted file holds `app.f`; the memory database's `m` comes from its initSql.
    */
  private def provisionData(): Unit =
    List("sales", "files").foreach(awaitPool(_))
    awaitPool("mem", "SELECT count(*) FROM memory.main.m")
    val c = s""""$lakeDb"."$LakeSchema""""
    List(
      s"""CREATE SCHEMA "$lakeDb"."$LakeSchema"""",
      s"CREATE TABLE $c.orders (o_id INTEGER NOT NULL, o_customer VARCHAR, " +
        "o_total DECIMAL(12,2), o_ts TIMESTAMP)",
      s"INSERT INTO $c.orders SELECT (i * 10)::INTEGER, " +
        "CASE WHEN i % 2 = 1 THEN 'alice' ELSE 'bob' END, (i * 15)::DECIMAL(12,2), " +
        "TIMESTAMP '2026-01-01 00:00:00' + to_hours(i) FROM range(1, 11) t(i)",
      s"CREATE VIEW $c.v_orders AS SELECT o_id, o_total FROM $c.orders",
      s"CREATE TABLE $c.secret (x INTEGER)",
      s"INSERT INTO $c.secret VALUES (42)",
      s"CREATE TABLE $c.notes (n_id INTEGER, body VARCHAR)",
      s"INSERT INTO $c.notes VALUES (1, repeat('a', 4096))"
    ).foreach(admin("sales", _))
    val f = s""""$fileDb"."$FileSchema""""
    List(
      s"""CREATE SCHEMA IF NOT EXISTS "$fileDb"."$FileSchema"""",
      s"CREATE TABLE $f.f AS SELECT i::INTEGER AS f_id, 'row' || i AS f_label FROM range(1, 4) t(i)"
    ).foreach(admin("files", _))

  private def provisionCredentials(): Unit =
    val alice = stack.login(Some(Acme), "alice", AlicePw)
    val bob   = stack.login(Some(Globex), "bob", BobPw)
    def mint(session: String, name: String, extra: (String, Json)*): Pat =
      val c = stack
        .post(
          "/api/auth/pat/create",
          obj((Seq("name" -> s(s"$name-$run")) ++ extra)*),
          token = session
        )
        .hcursor
      Pat(
        c.get[String]("id").fold(e => throw e, identity),
        c.get[String]("token").fold(e => throw e, identity)
      )
    tokens = Tokens(
      alice = mint(alice, "e2e-alice"),
      aliceCapped = mint(alice, "e2e-capped", "maxRows" -> Json.fromInt(2)),
      aliceRestTool = mint(alice, "e2e-rest", "tools" -> Json.arr(s("rest"))),
      aliceMcpOnly = mint(alice, "e2e-mcp", "tools" -> Json.arr(s("run_sql"))),
      aliceRevoked = mint(alice, "e2e-revoked"),
      bob = mint(bob, "e2e-bob")
    )
    stack.post("/api/auth/pat/revoke", obj("id" -> s(tokens.aliceRevoked.id)), token = alice)

  /** acme's own OIDC provider; globex has none, so its paths verify no JWT. Set last: a tenant with
    * a provider of its own logs its users in through it, and the PATs above are minted from
    * password sessions.
    */
  private def provisionOidc(): Unit =
    stack.post(
      "/api/tenant/setAuth",
      obj(
        "name"         -> s(Acme),
        "authProvider" -> s("keycloak"),
        "authConfig"   -> Json.obj(
          "baseUrl"  -> s(idp.baseUrl),
          "realm"    -> s(Realm),
          "clientId" -> s(OidcAudience)
        )
      )
    )

  /** A bearer JWT of acme's own OIDC provider naming `user`. */
  private def jwtFor(user: String): Pat =
    Pat(
      s"oidc-$user",
      JwtTestSigner.mint(Map("sub" -> user), s"${idp.baseUrl}/realms/$Realm", Some(OidcAudience))
    )

  // ---- HTTP ----------------------------------------------------------------------------------

  private val http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build()

  private val base = s"http://127.0.0.1:$RestDataPort"

  private final case class Resp(status: Int, headers: Map[String, String], body: String):
    def header(name: String): Option[String] = headers.get(name.toLowerCase)
    def json: Json              = parse(body).fold(e => fail(s"not JSON ($e): $body"), identity)
    def error: (String, String) =
      val c = json.hcursor
      (c.get[String]("error").getOrElse(""), c.get[String]("message").getOrElse(""))

  private def get(path: String, token: Option[Pat], query: String = ""): Resp =
    val q = if query.isEmpty then "" else s"?$query"
    val b = HttpRequest
      .newBuilder(URI.create(s"$base/api/v1/tenant/$path$q"))
      .timeout(java.time.Duration.ofSeconds(180))
      .GET()
    token.foreach(t => b.header("Authorization", s"Bearer ${t.raw}"))
    val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    Resp(
      r.statusCode(),
      r.headers().map().asScala.toMap.map((k, v) => k.toLowerCase -> v.asScala.mkString(",")),
      r.body()
    )

  /** A GET whose body is read as bytes (a streamed format): (status, lower-cased headers, body). */
  private def getBytes(
      path: String,
      token: Option[Pat],
      query: String
  ): (Int, Map[String, String], Array[Byte]) =
    val b = HttpRequest
      .newBuilder(URI.create(s"$base/api/v1/tenant/$path?$query"))
      .timeout(java.time.Duration.ofSeconds(180))
      .GET()
    token.foreach(t => b.header("Authorization", s"Bearer ${t.raw}"))
    val r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())
    (
      r.statusCode(),
      r.headers().map().asScala.toMap.map((k, v) => k.toLowerCase -> v.asScala.mkString(",")),
      r.body()
    )

  private def lake(rest: String)    = s"$Acme/database/$lakeDb/$rest"
  private def lakeTable(t: String)  = lake(s"schemas/$LakeSchema/tables/$t")
  private def rowsOf(t: String)     = lakeTable(t) + "/rows"
  private def alice                 = Some(tokens.alice)
  private def okResp(r: Resp): Resp =
    withClue(s"${r.status} ${r.body}: ")(r.status shouldBe 200)
    r

  /** The `name` (or `key`) field of every object of a JSON array body. */
  private def field(r: Resp, key: String): List[Json] =
    r.json.asArray
      .getOrElse(fail(s"not an array: ${r.body}"))
      .toList
      .map(_.hcursor.downField(key).focus.getOrElse(Json.Null))

  private def ints(r: Resp, key: String): List[Int] =
    field(r, key).map(
      _.asNumber.flatMap(_.toInt).getOrElse(fail(s"$key not an integer: ${r.body}"))
    )

  private def strings(r: Resp, key: String): List[String] =
    field(r, key).map(_.asString.getOrElse(fail(s"$key not a string: ${r.body}")))

  private def snapshotOf(r: Resp): Long =
    r.header("X-QoD-Snapshot").flatMap(_.toLongOption).getOrElse(fail(s"no snapshot header: $r"))

  /** The manager's recent-statement ring, as the admin UI reads it. */
  private def history(): List[(String, String, String, String)] =
    stack
      .get("/api/node/statements?limit=500")
      .hcursor
      .downField("statements")
      .as[List[Json]]
      .fold(e => throw e, identity)
      .map { j =>
        val c = j.hcursor
        (
          c.get[String]("tenant").getOrElse(""),
          c.get[String]("user").getOrElse(""),
          c.get[String]("status").getOrElse(""),
          c.get[String]("sql").getOrElse("")
        )
      }
      .filter(_._1 == Acme)

  // ---- every endpoint, JSON and CSV, on a real DuckLake table and view --------------------

  "the schemas listing" should "list exactly the granted schema, in JSON and CSV" in {
    val r = okResp(get(lake("schemas"), alice))
    strings(r, "name") shouldBe List(LakeSchema)
    r.header("Content-Type").get should startWith("application/json")
    r.header("Cache-Control") shouldBe Some("private, no-cache")
    r.header("Vary") shouldBe Some("Authorization, Accept")
    val csv = okResp(get(lake("schemas"), alice, "format=csv"))
    csv.header("Content-Type").get should startWith("text/csv")
    csv.body shouldBe s"name\r\n$LakeSchema\r\n"
  }

  "the tables listing" should "list the granted tables and view, typed, never the ungranted one" in {
    val r = okResp(get(lake(s"schemas/$LakeSchema/tables"), alice))
    strings(r, "name") shouldBe List("notes", "orders", "v_orders")
    strings(r, "type") shouldBe List("table", "table", "view")
    okResp(get(lake(s"schemas/$LakeSchema/tables"), alice, "format=csv")).body shouldBe
      "name,type\r\nnotes,table\r\norders,table\r\nv_orders,view\r\n"
  }

  "the table detail" should "describe the probed columns of a table and of a view" in {
    val t = okResp(get(lakeTable("orders"), alice))
    t.json.hcursor.get[String]("type").toOption shouldBe Some("table")
    t.json.hcursor.downField("columns").as[List[Json]].toOption.get.map { c =>
      (c.hcursor.get[String]("name").toOption.get, c.hcursor.get[String]("type").toOption.get)
    } shouldBe List(
      "o_id"       -> "INTEGER",
      "o_customer" -> "VARCHAR",
      "o_total"    -> "DECIMAL(12,2)",
      "o_ts"       -> "TIMESTAMP"
    )
    snapshotOf(t) should be > 0L
    val v = okResp(get(lakeTable("v_orders"), alice))
    v.json.hcursor.get[String]("type").toOption shouldBe Some("view")
    // A view is read at the current state: no snapshot pin, so no header and no caching.
    v.header("X-QoD-Snapshot") shouldBe None
    v.header("Cache-Control") shouldBe Some("private, no-cache")
    okResp(get(lakeTable("v_orders"), alice, "format=csv")).body shouldBe
      // RFC 4180: a field holding a comma is quoted.
      "name,type\r\no_id,INTEGER\r\no_total,\"DECIMAL(12,2)\"\r\n"
  }

  "/rows" should "filter, order and encode a DuckLake table in JSON" in {
    val r = okResp(
      get(
        rowsOf("orders"),
        alice,
        "select=o_id,o_customer,o_total,o_ts&o_customer=eq.alice&o_total=gte.40&order=o_id.desc" +
          "&limit=4"
      )
    )
    ints(r, "o_id") shouldBe List(90, 70, 50, 30)
    strings(r, "o_customer").distinct shouldBe List("alice")
    // DECIMAL is an exact number (never through a double), timestamps ISO-8601 without a zone.
    field(r, "o_total").map(_.asNumber.flatMap(_.toBigDecimal)) shouldBe
      List(135, 105, 75, 45).map(v => Some(BigDecimal(v)))
    strings(r, "o_ts") shouldBe List(
      "2026-01-01T09:00:00",
      "2026-01-01T07:00:00",
      "2026-01-01T05:00:00",
      "2026-01-01T03:00:00"
    )
    r.json.asArray.get.head.asObject.get.keys.toList shouldBe
      List("o_id", "o_customer", "o_total", "o_ts")
    r.header("Content-Range") shouldBe Some("0-3/*")
    r.header("X-QoD-Truncated") shouldBe None
    snapshotOf(r) should be > 0L
    // Unpinned: current data, so revalidate every time.
    r.header("Cache-Control") shouldBe Some("private, no-cache")
  }

  it should "answer RFC 4180 CSV with CRLF, in select order" in {
    okResp(
      get(rowsOf("orders"), alice, "select=o_customer,o_id&o_id=in.(10,20)&order=o_id&format=csv")
    ).body shouldBe "o_customer,o_id\r\nalice,10\r\nbob,20\r\n"
  }

  it should "match ilike case-insensitively and negate with not." in {
    ints(
      okResp(get(rowsOf("orders"), alice, "select=o_id&o_customer=ilike.AL*&order=o_id&limit=6")),
      "o_id"
    ) shouldBe List(10, 30, 50, 70, 90)
    ints(
      okResp(get(rowsOf("orders"), alice, "select=o_id&o_id=not.lte.80&order=o_id")),
      "o_id"
    ) shouldBe List(90, 100)
  }

  it should "read a DuckLake view like a table" in {
    ints(okResp(get(rowsOf("v_orders"), alice, "o_total=lt.40&order=o_id")), "o_id") shouldBe
      List(10, 20)
    okResp(get(rowsOf("v_orders"), alice, "o_id=eq.10&format=csv")).body shouldBe
      "o_id,o_total\r\n10,15.00\r\n"
  }

  it should "stream Arrow IPC with the effective row limit up front" in {
    val (status, headers, body) =
      getBytes(rowsOf("orders"), alice, "select=o_id&order=o_id&limit=4&format=arrow")
    withClue(new String(body, java.nio.charset.StandardCharsets.UTF_8))(status shouldBe 200)
    headers.get("content-type") shouldBe Some("application/vnd.apache.arrow.stream")
    headers.get("x-qod-limit") shouldBe Some("4")
    headers.get("x-qod-snapshot").flatMap(_.toLongOption) should not be empty
    val alloc  = new RootAllocator()
    val reader = new org.apache.arrow.vector.ipc.ArrowStreamReader(
      new java.io.ByteArrayInputStream(body),
      alloc
    )
    try
      val ids = ListBuffer.empty[Int]
      while reader.loadNextBatch() do
        val root = reader.getVectorSchemaRoot
        val v    = root.getVector("o_id").asInstanceOf[org.apache.arrow.vector.IntVector]
        (0 until root.getRowCount).foreach(i => ids += v.get(i))
      ids.toList shouldBe List(10, 20, 30, 40)
    finally
      reader.close()
      alloc.close()
  }

  it should "stream a Parquet file of the page with the effective row limit up front" in {
    assume(
      ai.starlake.quack.edge.rest.RestParquetWriter.available,
      "no POSIX named pipes on this host"
    )
    val (status, headers, body) =
      getBytes(rowsOf("orders"), alice, "select=o_id,o_total&order=o_id&limit=4&format=parquet")
    withClue(new String(body, java.nio.charset.StandardCharsets.UTF_8))(status shouldBe 200)
    headers.get("content-type") shouldBe Some("application/vnd.apache.parquet")
    headers.get("x-qod-limit") shouldBe Some("4")
    val f = Files.createTempFile("rest-e2e", ".parquet")
    try
      Files.write(f, body)
      val c = java.sql.DriverManager.getConnection("jdbc:duckdb:")
      try
        val rs  = c.createStatement().executeQuery(s"SELECT o_id, o_total FROM read_parquet('$f')")
        val got = ListBuffer.empty[(Int, BigDecimal)]
        while rs.next() do got += ((rs.getInt(1), BigDecimal(rs.getBigDecimal(2))))
        got.toList shouldBe List(10, 20, 30, 40).map(id => (id, BigDecimal(id) * 1.5))
      finally c.close()
    finally Files.deleteIfExists(f)
  }

  // The node decides the grant: an ungranted table's probe is refused there, and the edge answers
  // the refusal exactly as it answers a table that does not exist.
  it should "answer an ungranted table exactly like a missing one" in {
    val denied  = get(rowsOf("secret"), alice)
    val missing = get(rowsOf("no_such_table"), alice)
    denied.status shouldBe 404
    (denied.status, denied.body) shouldBe (missing.status, missing.body)
    val deniedDetail = get(lakeTable("secret"), alice)
    (deniedDetail.status, deniedDetail.body) shouldBe
      (get(lakeTable("no_such_table"), alice).status, missing.body)
  }

  // ---- a stable page 2 under concurrent writes --------------------------------------------

  "pagination" should "keep page 2 stable under an earlier insert and a delete, by sending asOf back" in {
    val page1 = okResp(get(rowsOf("orders"), alice, "select=o_id&order=o_id&limit=4"))
    ints(page1, "o_id") shouldBe List(10, 20, 30, 40)
    val pinned = snapshotOf(page1)
    // 15 sorts BEFORE the page-2 cursor (offset 4) and 60 sits inside page 2: without the pin,
    // page 2 would repeat 40 and skip 60.
    val c = s""""$lakeDb"."$LakeSchema".orders"""
    admin("sales", s"INSERT INTO $c VALUES (15, 'carol', 22.50, TIMESTAMP '2026-02-01 00:00:00')")
    admin("sales", s"DELETE FROM $c WHERE o_id = 60")
    try
      val page2 = okResp(
        get(rowsOf("orders"), alice, s"select=o_id&order=o_id&limit=4&offset=4&asOf=$pinned")
      )
      ints(page2, "o_id") shouldBe List(50, 60, 70, 80)
      snapshotOf(page2) shouldBe pinned
      page2.header("Content-Range") shouldBe Some("4-7/*")
      // Pinned content is immutable: briefly cacheable, still private to the Authorization.
      page2.header("Cache-Control") shouldBe Some("private, max-age=300")
      page2.header("Vary") shouldBe Some("Authorization, Accept")

      // The control: the same page unpinned sees the writes, at a newer snapshot.
      val drifted = okResp(get(rowsOf("orders"), alice, "select=o_id&order=o_id&limit=4&offset=4"))
      ints(drifted, "o_id") shouldBe List(40, 50, 70, 80)
      snapshotOf(drifted) should be > pinned

      // A view takes no time travel: asOf on it is refused rather than answered with rows of
      // an unverified snapshot, and an unpinned read of it carries no X-QoD-Snapshot.
      val view =
        get(rowsOf("v_orders"), alice, s"select=o_id&order=o_id&limit=4&offset=4&asOf=$pinned")
      view.status shouldBe 400
      view.error._1 shouldBe "invalid_selector"
      okResp(get(rowsOf("v_orders"), alice, "select=o_id&order=o_id"))
        .header("X-QoD-Snapshot") shouldBe None
    finally
      // Restore the seeded rows for the specs that follow.
      admin("sales", s"DELETE FROM $c WHERE o_id = 15")
      admin("sales", s"INSERT INTO $c VALUES (60, 'bob', 90.00, TIMESTAMP '2026-01-01 06:00:00')")
  }

  // ---- tenant binding and credentials -----------------------------------------------------

  "a tenant-B PAT" should "be refused on a tenant-A path, exactly like on an unknown tenant" in {
    val bob     = Some(tokens.bob)
    val foreign = get(rowsOf("orders"), bob)
    foreign.status shouldBe 403
    foreign.error shouldBe ("forbidden", s"your token is scoped to tenant '$Globex'")
    val unknown = get(s"no_such_tenant/database/$lakeDb/schemas", bob)
    (unknown.status, unknown.body) shouldBe (foreign.status, foreign.body)
  }

  "the credential" should "be a live PAT that allows the rest tool" in {
    get(lake("schemas"), None).status shouldBe 401
    get(lake("schemas"), Some(tokens.aliceRevoked)).status shouldBe 401
    get(lake("schemas"), Some(Pat("x", "qod_pat_not-a-token"))).status shouldBe 401
    okResp(get(lake("schemas"), Some(tokens.aliceRestTool)))
    val mcpOnly = get(lake("schemas"), Some(tokens.aliceMcpOnly))
    mcpOnly.status shouldBe 403
    mcpOnly.error._1 shouldBe "forbidden"
  }

  "an OIDC bearer of the tenant's own provider" should "read as its user, through its grants" in {
    val jwt = Some(jwtFor("alice"))
    ints(okResp(get(rowsOf("orders"), jwt, "select=o_id&order=o_id&limit=3")), "o_id") shouldBe
      List(10, 20, 30)
    // The same grants as alice's PAT: the ungranted table is the missing one's 404.
    val denied  = get(rowsOf("secret"), jwt)
    val missing = get(rowsOf("no_such_table"), jwt)
    denied.status shouldBe 404
    (denied.status, denied.body) shouldBe (missing.status, missing.body)
    // Verified by acme's provider only: globex has none, and a user acme never provisioned
    // (bob is globex's) is refused like a bad token.
    get(s"$Globex/database/$lakeDb/schemas", jwt).status shouldBe 401
    get(lake("schemas"), Some(jwtFor("bob"))).status shouldBe 401
  }

  // ---- cap precedence ---------------------------------------------------------------------

  "the row cap" should "be min(limit or defaultLimit, maxRows, the token's maxRows)" in {
    def page(token: Pat, q: String) =
      okResp(get(rowsOf("orders"), Some(token), s"select=o_id&order=o_id$q"))
    // No limit: defaultLimit (3) cut the page, so it is flagged.
    val dflt = page(tokens.alice, "")
    ints(dflt, "o_id") shouldBe List(10, 20, 30)
    dflt.header("X-QoD-Truncated") shouldBe Some("true")
    dflt.header("Content-Range") shouldBe Some("0-2/*")
    // The client's own limit is not a truncation.
    val own = page(tokens.alice, "&limit=5")
    ints(own, "o_id") should have size 5
    own.header("X-QoD-Truncated") shouldBe None
    // maxRows (6) wins over a larger limit.
    val server = page(tokens.alice, "&limit=8")
    ints(server, "o_id") should have size MaxRows
    server.header("X-QoD-Truncated") shouldBe Some("true")
    // The token's maxRows (2) wins over both.
    val token = page(tokens.aliceCapped, "&limit=5")
    ints(token, "o_id") shouldBe List(10, 20)
    token.header("X-QoD-Truncated") shouldBe Some("true")
    // A cap that did not bind flags nothing.
    val small = page(tokens.alice, "&o_id=in.(10,20)&limit=5")
    ints(small, "o_id") shouldBe List(10, 20)
    small.header("X-QoD-Truncated") shouldBe None
  }

  // ---- audit and history ------------------------------------------------------------------

  "the audit trail" should "history the data statement, never the probe" in {
    // The probe of an ungranted table is refused unrecorded (it is plumbing, like FlightSQL's
    // prepare-time probe): the client gets the plain 404 and no data-denial row is written.
    get(rowsOf("secret"), alice).status shouldBe 404
    // A filter value no other test sends, so only this request's statement is matched here.
    okResp(get(rowsOf("orders"), alice, "select=o_id&o_id=eq.70"))
    val denials = stack
      .get(s"/api/audit/list?tenant=$Acme&action=sql.denied&limit=500")
      .hcursor
      .downField("events")
      .as[List[Json]]
      .fold(e => throw e, identity)
    denials.filter(_.noSpaces.contains("secret")) shouldBe empty
    val recent = history()
    recent.filter(_._4.endsWith("LIMIT 0")) shouldBe empty
    val data = recent.filter((_, _, status, sql) =>
      status == "ok" && sql.contains(s"FROM \"$lakeDb\".\"$LakeSchema\".\"orders\"") &&
        sql.contains("'70'")
    )
    data should not be empty
    data.map(_._2).distinct shouldBe List("alice")
  }

  // ---- cold start -------------------------------------------------------------------------

  "a suspended pool" should "resume within the router's hold and answer 200" in {
    def suspended: Option[Boolean] =
      stack
        .get(s"/api/pool/$Acme/$lakeDb/$ColdPool/status")
        .hcursor
        .get[Boolean]("suspended")
        .toOption
    suspended shouldBe Some(true)
    val r = okResp(
      get(rowsOf("orders"), alice, s"select=o_id&order=o_id&limit=2&pool=$ColdPool")
    )
    ints(r, "o_id") shouldBe List(10, 20)
    suspended shouldBe Some(false)
  }

  // ---- the LIKE cost, live -------------------------------------------------------------

  "the worst admitted LIKE pattern" should "stay linear on a live node" in {
    // 4 wildcards (the cap) against a 4 KiB value that almost matches everywhere: the shape a
    // backtracking matcher pays length^(wildcards-1) for.
    for op <- List("like", "ilike") do
      val t0 = System.nanoTime()
      val r  = okResp(get(rowsOf("notes"), alice, s"select=n_id&body=$op.*a*a*a*b"))
      val ms = (System.nanoTime() - t0) / 1000000
      r.body shouldBe "[]"
      withClue(s"$op took $ms ms: ")(ms should be < 5000L)
  }

  // ---- every kind -------------------------------------------------------------------------

  "an encrypted duckdb-file database" should "be served, without a snapshot, and refuse asOf" in {
    val db = s"$Acme/database/$fileDb"
    strings(okResp(get(s"$db/schemas", alice)), "name") shouldBe List(FileSchema)
    val r = okResp(get(s"$db/schemas/$FileSchema/tables/f/rows", alice, "order=f_id"))
    ints(r, "f_id") shouldBe List(1, 2, 3)
    strings(r, "f_label") shouldBe List("row1", "row2", "row3")
    r.header("X-QoD-Snapshot") shouldBe None
    val tt = get(s"$db/schemas/$FileSchema/tables/f/rows", alice, "asOf=1")
    tt.status shouldBe 400
    tt.error._1 shouldBe "invalid_kind"
  }

  "a memory database" should "be qualified as \"memory\".\"main\" and refuse asOf" in {
    val db = s"$Acme/database/$memDb"
    strings(okResp(get(s"$db/schemas", alice)), "name") shouldBe List("main")
    val r = okResp(get(s"$db/schemas/main/tables/m/rows", alice, "order=id&format=csv"))
    r.body shouldBe "id,label\r\n1,m1\r\n2,m2\r\n"
    r.header("X-QoD-Snapshot") shouldBe None
    history().exists(_._4.contains("\"memory\".\"main\".\"m\"")) shouldBe true
    val tt = get(s"$db/schemas/main/tables/m/rows", alice, "asOfTs=2026-01-01T00:00:00Z")
    tt.status shouldBe 400
    tt.error._1 shouldBe "invalid_kind"
  }

  "a DuckLake database" should "pin every read to a snapshot the catalog knows" in {
    val r = okResp(get(rowsOf("orders"), alice, "select=o_id&o_id=eq.10"))
    val s = snapshotOf(r)
    admin(
      "sales",
      s"SELECT snapshot_id FROM ducklake_snapshots('$lakeDb') WHERE snapshot_id = $s"
    ) should not be empty
    okResp(get(rowsOf("orders"), alice, s"select=o_id&o_id=eq.10&asOf=$s"))
      .header("Cache-Control") shouldBe Some("private, max-age=300")
  }

object RestEdgeEndToEndSpec:

  /** The REST data edge's port, beside the stack's manager, FlightSQL and Quack listeners. */
  val RestDataPort = 31392

  val LakeSchema = "tpch1"
  val FileSchema = "app"
  val ColdPool   = "zcold"

  /** acme's Keycloak realm on the mock IdP, and the audience it expects: its client id. */
  val Realm        = "e2e"
  val OidcAudience = "qod-rest-e2e"

  val DefaultLimit = 3
  val MaxRows      = 6

  final case class Pat(id: String, raw: String)

  final case class Tokens(
      alice: Pat,
      aliceCapped: Pat,
      aliceRestTool: Pat,
      aliceMcpOnly: Pat,
      aliceRevoked: Pat,
      bob: Pat
  )

  /** The `memory` kind has nothing on disk: each node builds its table from the tenant-db's
    * initSql, which the node runs before it serves.
    */
  val SeedMemorySql: String =
    "CREATE TABLE memory.main.m AS SELECT i::INTEGER AS id, 'm' || i AS label FROM range(1, 3) t(i);"
