package ai.starlake.quack.it

import ai.starlake.quack.{EmbeddedPostgresConfig, RestEdgeConfig}
import ai.starlake.quack.boot.{EmbeddedControlPlane, RoutedExecutor}
import ai.starlake.quack.edge.{FlightSqlRouter, SessionRegistry}
import ai.starlake.quack.edge.adapter.{NodeLoadTracker, QuackHttpAdapter, QuackHttpClient}
import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import ai.starlake.quack.edge.rest.{RestEdgeHandlers, RestEdgeServer}
import ai.starlake.quack.edge.sql.PostgresAclValidator
import ai.starlake.quack.model.{
  NodeSpec,
  PoolKey,
  RoleDistribution,
  RunningNode,
  Tenant,
  TenantDbKind
}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.{PatAuthenticator, TokenRestriction}
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import ai.starlake.quack.ondemand.rbac.UserMemberships
import ai.starlake.quack.ondemand.runtime.{LocalQuackBackend, QuackBackend}
import ai.starlake.quack.ondemand.state.{
  LiquibaseRunner,
  PatStore,
  PostgresControlPlaneStore,
  UserGrant,
  UserStore
}
import ai.starlake.quack.ondemand.telemetry.{EventJournal, StatementQuery}
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import ai.starlake.quack.route.StatementClassifier
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import org.apache.arrow.memory.RootAllocator
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.{HttpURLConnection, ServerSocket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.sys.process.{Process, ProcessLogger}
import scala.util.{Failure, Success, Try}

/** End-to-end spec of the REST data edge: real HTTP against a [[RestEdgeServer]] whose handlers run
  * every statement through the router's full pipeline (the real `PostgresAclValidator` with the
  * metadata filter mounted, the audit journal, statement history) and on to live Quack nodes
  * spawned through [[LocalQuackBackend]], the backend `PoolSupervisor.createPool` uses in
  * production. Credentials are real PATs minted into a real `qodstate_pat` table and resolved by
  * [[PatAuthenticator]].
  *
  * The executor is the production [[RoutedExecutor]] (the pools axis, `branchOnly`, the handshake,
  * attenuation, then [[RoutedExecutor.viaRouter]]), built over this spec's router exactly as `Main`
  * builds it, so what a PAT caller may do is decided by the same code.
  *
  * It stays green while a developer manager runs on `:20900`:
  *   - every listener binds [[freePort]]: the edge, a second edge for the cold-start case, and the
  *     embedded Postgres;
  *   - nodes lease ports from [[NodePortMin]]-[[NodePortMax]], outside the manager's 21900-22500
  *     and away from the embedded control plane's production default (25432);
  *   - the control plane and the DuckLake catalog live in an embedded Postgres started on a free
  *     port in a temporary data directory, never the live `qod` database or its tenant-db ones;
  *   - teardown deletes only the pools this spec created, then stops its own backend's children; it
  *     never runs `kill-quack-nodes.sh`.
  *
  * Environment gate: every test CANCELS (never fails, never aborts the suite) when `duckdb` is not
  * on PATH, when it cannot load the `quack`, `ducklake`, `postgres` and `httpfs` extensions, when
  * the embedded Postgres cannot start, or when a node never answers. Setup runs lazily inside the
  * first test rather than in `beforeAll`, because a cancel raised from `beforeAll` aborts the whole
  * suite. A setup step that fails for any other reason is a bug and FAILS every test.
  */
class RestEdgeEndToEndSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  import RestEdgeEndToEndSpec.*

  // ---- environment ---------------------------------------------------------------------------

  private def freePort(): Int =
    val s = new ServerSocket(0)
    try s.getLocalPort
    finally s.close()

  private val duckdbPresent: Boolean = Process("which duckdb").!(ProcessLogger(_ => ())) == 0

  /** Run SQL in a fresh `duckdb` CLI session that stops at the first error; (exit, stderr). */
  private def duckdb(sql: String): (Int, String) =
    val err = new StringBuilder
    val rc  = Process(Seq("duckdb", "-bail", "-c", sql))
      .!(ProcessLogger(_ => (), l => err.append(l).append('\n')))
    (rc, err.toString.trim)

  /** Everything a node of this spec loads. `httpfs` is what an encrypted database needs (see the
    * encryption section of CLAUDE.md: without OpenSSL an encrypted file comes up read-only).
    */
  private val ExtensionProbe =
    "INSTALL quack; LOAD quack; INSTALL ducklake; LOAD ducklake; " +
      "INSTALL postgres; LOAD postgres; INSTALL httpfs; LOAD httpfs;"

  // Teardown actions in creation order, run in reverse by afterAll, so a setup that stopped half
  // way still releases exactly what it had created.
  private val teardown                                    = ListBuffer.empty[(String, () => Unit)]
  private def onTeardown(label: String)(f: => Unit): Unit = teardown += (label -> (() => f))

  /** Built once, by the first test that asks. Left = the environment cannot run this spec. */
  private lazy val setup: Try[Either[String, Env]] = Try(buildEnv())

  private def env: Env = setup match
    case Success(Right(e))     => e
    case Success(Left(reason)) => cancel(reason)
    case Failure(t)            => fail(s"end-to-end setup failed: $t", t)

  override def afterAll(): Unit =
    teardown.reverseIterator.foreach { case (label, f) =>
      try f()
      catch case t: Throwable => System.err.println(s"[RestEdgeEndToEndSpec] teardown $label: $t")
    }

  private def buildEnv(): Either[String, Env] =
    if !duckdbPresent then Left("duckdb CLI not on PATH")
    else
      val (rc, err) = duckdb(ExtensionProbe)
      if rc != 0 then Left(s"duckdb extensions unavailable (quack/ducklake/postgres/httpfs): $err")
      else startControlPlane().flatMap(buildOn)

  private def startControlPlane(): Either[String, EmbeddedControlPlane] =
    val dir = Files.createTempDirectory("qod-rest-e2e-pg")
    onTeardown("pg data dir")(deleteTree(dir))
    Try(
      EmbeddedControlPlane.start(
        EmbeddedPostgresConfig(enabled = true, port = freePort(), dataDir = dir.toString)
      )
    ) match
      case Failure(t)  => Left(s"embedded Postgres could not start: $t")
      case Success(cp) =>
        onTeardown("embedded Postgres")(cp.stop())
        Right(cp)

  /** The data a node serves, written before any pool exists: a DuckLake catalog in its own database
    * of the embedded server, and an encrypted duckdb file.
    */
  private final case class Seeded(
      lakeMeta: Map[String, String],
      lakeData: Path,
      filePath: Path,
      attachLake: String
  )

  private def seed(cp: EmbeddedControlPlane, work: Path): Either[String, Seeded] =
    val lakeMeta = Map(
      "pgHost"     -> cp.host,
      "pgPort"     -> cp.port.toString,
      "pgUser"     -> cp.user,
      "pgPassword" -> cp.password,
      "dbName"     -> LakeDb,
      "schemaName" -> LakeSchema
    )
    val lakeData = Files.createDirectories(work.resolve("lake-data"))
    val filePath = work.resolve("acme_file.duckdb")
    cp.ensureDatabase(LakeDb)
    val attachLake        = attachLakeSql(lakeMeta, lakeData)
    val (seedRc, seedErr) = duckdb(attachLake + SeedLakeSql)
    if seedRc != 0 then Left(s"DuckLake seed failed (extension or catalog): $seedErr")
    else
      val (fileRc, fileErr) = duckdb(seedFileSql(filePath))
      if fileRc != 0 then Left(s"encrypted duckdb-file seed failed: $fileErr")
      else Right(Seeded(lakeMeta, lakeData, filePath, attachLake))

  private def buildOn(cp: EmbeddedControlPlane): Either[String, Env] =
    val work = Files.createTempDirectory("qod-rest-e2e")
    onTeardown("work dir")(deleteTree(work))
    seed(cp, work).flatMap(wire(cp, _))

  private def wire(cp: EmbeddedControlPlane, seeded: Seeded): Either[String, Env] =
    import seeded.*

    // ---- control plane: the tables Liquibase owns, one database, like a real manager
    cp.ensureDatabase(ControlDb)
    val url = s"jdbc:postgresql://${cp.host}:${cp.port}/$ControlDb"
    new LiquibaseRunner(url, cp.user, cp.password).run()
    val store = new PostgresControlPlaneStore(url, cp.user, cp.password, poolSize = 4)
    onTeardown("control-plane store")(store.close())
    val users = new UserStore(url, cp.user, cp.password, poolSize = 2)
    onTeardown("user store")(users.close())
    val pats = new PatStore(url, cp.user, cp.password, poolSize = 2)
    onTeardown("pat store")(pats.close())
    val patAuth =
      new PatAuthenticator(pats, users.userById, u => List(UserGrant(u.tenant, u.kind)))

    // ---- supervisor over the real local backend, with a spawn gate for the cold-start case
    val tracker = new NodeLoadTracker
    val backend = new GatedBackend(new LocalQuackBackend(NodePortMin, NodePortMax))
    // The catalog is already seeded; the initializer would only re-ATTACH it through JDBC.
    val sup   = new PoolSupervisor(backend, tracker, store, duckLakeInitializer = (_, _) => ())
    val pools = ListBuffer.empty[PoolKey]
    // Registered before any pool exists: deletes only what this spec created, then reaps the
    // backend's own children (never anything else on the machine).
    onTeardown("pools and nodes") {
      pools.foreach(k => Try(sup.deletePool(k, force = true).unsafeRunSync()))
      backend.cleanup().unsafeRunSync()
    }

    ok(sup.createTenant(Tenant(Acme)))
    ok(sup.createTenant(Tenant(Globex)))
    ok(
      sup.createTenantDb(Acme, "lake", TenantDbKind.DuckLake, lakeMeta, lakeData.toString)
    )
    ok(
      sup.createTenantDb(
        Acme,
        "file",
        TenantDbKind.DuckDbFile,
        Map("dbName" -> FileDb, "schemaName" -> FileSchema, "encryptionKey" -> FileKey),
        filePath.toString,
        encrypted = true
      )
    )
    ok(
      sup.createTenantDb(
        Acme,
        "mem",
        TenantDbKind.InMemory,
        Map.empty,
        "",
        initSql = SeedMemorySql
      )
    )

    // ---- principals: alice reads a chosen set in acme, bob belongs to globex
    val alice  = ok(sup.createUser(Some(Acme), "alice", "alice-pw-1", "user", users, UserMemberships.IdpManaged))
    val bob    = ok(sup.createUser(Some(Globex), "bob", "bob-pw-1", "user", users, UserMemberships.IdpManaged))
    val reader = ok(sup.createRole(Acme, "rest_reader"))
    List(
      (LakeDb, LakeSchema, "orders"),
      (LakeDb, LakeSchema, "v_orders"),
      (LakeDb, LakeSchema, "notes"),
      (FileDb, FileSchema, "f"),
      ("memory", "main", "m")
    ).foreach { case (c, s, t) => ok(sup.grantRolePermission(reader.id, c, s, t, "RO")) }
    ok(sup.addUserRole(alice.id, reader.id))
    ok(sup.grantPoolPermission(Acme, None, Some(alice.id), None))
    ok(sup.grantPoolPermission(Globex, None, Some(bob.id), None))

    def mint(uid: String, name: String, r: TokenRestriction) =
      val (rec, raw) = pats.mint(uid, name, r, None, 0)
      Pat(rec.id, raw)
    val unrestricted = TokenRestriction.Unrestricted
    val tokens       = Tokens(
      alice = mint(alice.id, "e2e-alice", unrestricted),
      aliceCapped = mint(alice.id, "e2e-capped", unrestricted.copy(maxRows = Some(2))),
      aliceRestTool = mint(alice.id, "e2e-rest", unrestricted.copy(tools = Some(Set("rest")))),
      aliceMcpOnly = mint(alice.id, "e2e-mcp", unrestricted.copy(tools = Some(Set("run_sql")))),
      aliceRevoked = mint(alice.id, "e2e-revoked", unrestricted),
      bob = mint(bob.id, "e2e-bob", unrestricted)
    )
    pats.revoke(alice.id, tokens.aliceRevoked.id)

    // ---- the pipeline: validator + metadata filter from ONE flag, audit journal, history
    val telemetry = new RecordingTelemetryStore
    val journal   = new EventJournal(telemetry)
    val allocator = new RootAllocator()
    onTeardown("arrow allocator")(allocator.close())
    val client = new QuackHttpClient(allocator)
    def routerWith(hold: FiniteDuration) = new FlightSqlRouter(
      sup,
      new SessionRegistry,
      tracker,
      new QuackHttpAdapter(client, tracker),
      validator = new PostgresAclValidator(
        // As BootFactories wires it: a wildcard catalog grant covers the tenant's own databases.
        tenantCatalogs = tenantId =>
          sup
            .getTenantById(tenantId)
            .map(t => sup.listTenantDbsByTenant(t.id).map(_.name).toSet)
            .getOrElse(Set.empty),
        filteredMetadata = true
      ),
      journal = journal,
      resumeHoldTimeout = hold,
      metadataFilterRewriter = new MetadataFilterRewriter(enabled = true)
    )
    // The production default hold (`resumeHoldTimeoutSec`, 60 s).
    val router = routerWith(60.seconds)

    // ---- pools: one per kind, plus a DuckLake pool that starts suspended. `zcold` sorts
    // after `sales`, so PoolPicks.readPoolKey never picks it for a request that names no pool.
    val salesKey = PoolKey(Acme, LakeDb, "sales")
    val coldKey  = PoolKey(Acme, LakeDb, "zcold")
    val fileKey  = PoolKey(Acme, FileDb, "files")
    val memKey   = PoolKey(Acme, MemDb, "mem")
    List(salesKey, fileKey, memKey).foreach { k =>
      pools += k
      sup.createPool(k, RoleDistribution(0, 0, 1)).unsafeRunSync()
    }
    pools += coldKey
    sup.createPool(coldKey, RoleDistribution(0, 0, 1), startSuspended = true).unsafeRunSync()

    val readiness = List(
      salesKey -> s"SELECT count(*) FROM \"$LakeDb\".\"$LakeSchema\".\"orders\"",
      fileKey  -> s"SELECT count(*) FROM \"$FileDb\".\"$FileSchema\".\"f\"",
      memKey   -> "SELECT count(*) FROM \"memory\".\"main\".\"m\""
    )
    // A plain router: the readiness probe asks whether the node serves the data, not whether a
    // principal may read it.
    val probeRouter = new FlightSqlRouter(sup, new SessionRegistry, tracker, router.adapter)
    readiness
      .foldLeft[Either[String, Unit]](Right(())) { case (acc, (key, sql)) =>
        acc.flatMap(_ => awaitNode(sup, probeRouter, key, sql))
      }
      .map { _ =>
        val readers = TrieMap.empty[String, DuckLakeCatalogReader]
        onTeardown("catalog readers")(readers.values.foreach(r => Try(r.close())))
        def catalogReader(tenant: String, db: String): DuckLakeCatalogReader =
          readers.getOrElseUpdate(
            db,
            DuckLakeCatalogReader(
              sup.findTenantDb(tenant, db).map(_.metastore).getOrElse(Map.empty) ++
                Map("dataPath" -> lakeData.toString)
            )
          )
        def edgeOver(r: FlightSqlRouter): (RestEdgeServer, String) =
          val cfg      = edgeConfig(freePort())
          val handlers = new RestEdgeHandlers(
            cfg,
            sup,
            patAuth.resolve,
            RoutedExecutor(sup, StatementClassifier.default, RoutedExecutor.viaRouter(r))(
              recordExecution = true
            ),
            RoutedExecutor(sup, StatementClassifier.default, RoutedExecutor.viaRouter(r))(
              recordExecution = false
            ),
            catalogReader,
            (t, db, tag) => store.findSnapshotTag(t, db, tag).map(_.snapshotId)
          )
          val server = new RestEdgeServer(cfg, RestEdgeServer.serverEndpoints(handlers))
          server.start().unsafeRunSync()
          onTeardown(s"edge :${cfg.port}")(server.stop())
          (server, s"http://127.0.0.1:${cfg.port}")
        val (_, base) = edgeOver(router)
        // The cold-start case's second edge: the same supervisor behind a router whose hold is 1 s.
        val (_, shortHoldBase) = edgeOver(routerWith(1.second))
        Env(
          base = base,
          shortHoldBase = shortHoldBase,
          sup = sup,
          backend = backend,
          router = router,
          journal = journal,
          telemetry = telemetry,
          tokens = tokens,
          coldKey = coldKey,
          attachLake = attachLake,
          catalogReader = catalogReader
        )
      }

  /** A setup call that must succeed; its failure is a bug in this spec, not an environment gap. */
  private def ok[E, A](io: IO[Either[E, A]]): A =
    io.unsafeRunSync()
      .fold(e => throw new IllegalStateException(s"setup step refused: $e"), identity)

  /** Waits for `key`'s node to answer HTTP, then for `sql` to run on it; Left (a cancel) when it
    * never does, which is what a node that cannot load its extensions or attach looks like.
    */
  private def awaitNode(
      sup: PoolSupervisor,
      router: FlightSqlRouter,
      key: PoolKey,
      sql: String
  ): Either[String, Unit] =
    sup.snapshot(key).flatMap(_.nodes.headOption) match
      case None       => Left(s"pool $key spawned no node")
      case Some(node) =>
        val deadline = System.currentTimeMillis() + 60000
        var up       = false
        var last     = ""
        while !up && System.currentTimeMillis() < deadline do
          up =
            try
              val c = URI
                .create(s"http://${node.host}:${node.port}/quack")
                .toURL
                .openConnection()
                .asInstanceOf[HttpURLConnection]
              c.setConnectTimeout(300); c.setReadTimeout(300)
              c.getResponseCode; c.disconnect(); true
            catch
              case e: Throwable =>
                last = e.toString
                Thread.sleep(300); false
        if !up then Left(s"quack node of $key at ${node.host}:${node.port} never came up ($last)")
        else
          router
            .execute(s"e2e-ready-${key.pool}", "e2e-readiness", key, sql, recordExecution = false)
            .unsafeRunSync() match
            case Right(qr) => qr.close(); Right(())
            case Left(f)   => Left(s"quack node of $key is up but cannot serve its data: $f")

  private def edgeConfig(port: Int) = RestEdgeConfig(
    enabled = true,
    host = "127.0.0.1",
    port = port,
    tlsEnabled = false,
    tlsCertChain = "",
    tlsPrivateKey = "",
    defaultLimit = DefaultLimit,
    maxRows = MaxRows,
    maxResponseBytes = 64L * 1024 * 1024,
    // Above the router's 60 s hold, so a cold start is answered by the router's own verdict
    // rather than cut by the edge's clock.
    stmtTimeoutSec = 120,
    maxConnections = 16,
    maxHeaderBytes = 16384,
    headerReceiveTimeoutSec = 10,
    idleTimeoutSec = 60
  )

  private def deleteTree(p: Path): Unit =
    if Files.exists(p) then
      Files
        .walk(p)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(f => Files.deleteIfExists(f))

  // ---- HTTP ----------------------------------------------------------------------------------

  private val http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build()

  private final case class Resp(status: Int, headers: Map[String, String], body: String):
    def header(name: String): Option[String] = headers.get(name.toLowerCase)
    def json: Json              = parse(body).fold(e => fail(s"not JSON ($e): $body"), identity)
    def error: (String, String) =
      val c = json.hcursor
      (c.get[String]("error").getOrElse(""), c.get[String]("message").getOrElse(""))

  private def get(base: String, path: String, token: Option[Pat], query: String = ""): Resp =
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

  private def lake(rest: String)    = s"$Acme/database/$LakeDb/$rest"
  private def lakeTable(t: String)  = lake(s"schemas/$LakeSchema/tables/$t")
  private def rowsOf(t: String)     = lakeTable(t) + "/rows"
  private def alice                 = Some(env.tokens.alice)
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

  // ---- every endpoint, JSON and CSV, on a real DuckLake table and view --------------------

  "the schemas listing" should "list exactly the granted schema, in JSON and CSV" in {
    val r = okResp(get(env.base, lake("schemas"), alice))
    strings(r, "name") shouldBe List(LakeSchema)
    r.header("Content-Type").get should startWith("application/json")
    r.header("Cache-Control") shouldBe Some("private, no-cache")
    r.header("Vary") shouldBe Some("Authorization, Accept")
    val csv = okResp(get(env.base, lake("schemas"), alice, "format=csv"))
    csv.header("Content-Type").get should startWith("text/csv")
    csv.body shouldBe s"name\r\n$LakeSchema\r\n"
  }

  "the tables listing" should "list the granted tables and view, typed, never the ungranted one" in {
    val r = okResp(get(env.base, lake(s"schemas/$LakeSchema/tables"), alice))
    strings(r, "name") shouldBe List("notes", "orders", "v_orders")
    strings(r, "type") shouldBe List("table", "table", "view")
    okResp(get(env.base, lake(s"schemas/$LakeSchema/tables"), alice, "format=csv")).body shouldBe
      "name,type\r\nnotes,table\r\norders,table\r\nv_orders,view\r\n"
  }

  "the table detail" should "describe the probed columns of a table and of a view" in {
    val t = okResp(get(env.base, lakeTable("orders"), alice))
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
    val v = okResp(get(env.base, lakeTable("v_orders"), alice))
    v.json.hcursor.get[String]("type").toOption shouldBe Some("view")
    // A view is read at the current state: no snapshot pin, so no header and no caching.
    v.header("X-QoD-Snapshot") shouldBe None
    v.header("Cache-Control") shouldBe Some("private, no-cache")
    okResp(get(env.base, lakeTable("v_orders"), alice, "format=csv")).body shouldBe
      // RFC 4180: a field holding a comma is quoted.
      "name,type\r\no_id,INTEGER\r\no_total,\"DECIMAL(12,2)\"\r\n"
  }

  "/rows" should "filter, order and encode a DuckLake table in JSON" in {
    val r = okResp(
      get(
        env.base,
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
      get(
        env.base,
        rowsOf("orders"),
        alice,
        "select=o_customer,o_id&o_id=in.(10,20)&order=o_id&format=csv"
      )
    ).body shouldBe "o_customer,o_id\r\nalice,10\r\nbob,20\r\n"
  }

  it should "match ilike case-insensitively and negate with not." in {
    ints(
      okResp(
        get(
          env.base,
          rowsOf("orders"),
          alice,
          "select=o_id&o_customer=ilike.AL*&order=o_id&limit=6"
        )
      ),
      "o_id"
    ) shouldBe
      List(10, 30, 50, 70, 90)
    ints(
      okResp(get(env.base, rowsOf("orders"), alice, "select=o_id&o_id=not.lte.80&order=o_id")),
      "o_id"
    ) shouldBe
      List(90, 100)
  }

  it should "read a DuckLake view like a table" in {
    ints(
      okResp(get(env.base, rowsOf("v_orders"), alice, "o_total=lt.40&order=o_id")),
      "o_id"
    ) shouldBe
      List(10, 20)
    okResp(get(env.base, rowsOf("v_orders"), alice, "o_id=eq.10&format=csv")).body shouldBe
      "o_id,o_total\r\n10,15.00\r\n"
  }

  it should "answer an ungranted table exactly like a missing one" in {
    val denied  = get(env.base, rowsOf("secret"), alice)
    val missing = get(env.base, rowsOf("no_such_table"), alice)
    denied.status shouldBe 404
    (denied.status, denied.body) shouldBe (missing.status, missing.body)
    val deniedDetail = get(env.base, lakeTable("secret"), alice)
    (deniedDetail.status, deniedDetail.body) shouldBe
      (get(env.base, lakeTable("no_such_table"), alice).status, missing.body)
  }

  // ---- a stable page 2 under concurrent writes --------------------------------------------

  "pagination" should "keep page 2 stable under an earlier insert and a delete, by sending asOf back" in {
    val e     = env
    val page1 = okResp(get(e.base, rowsOf("orders"), alice, "select=o_id&order=o_id&limit=4"))
    ints(page1, "o_id") shouldBe List(10, 20, 30, 40)
    val pinned = snapshotOf(page1)
    // 15 sorts BEFORE the page-2 cursor (offset 4) and 60 sits inside page 2: without the pin,
    // page 2 would repeat 40 and skip 60.
    val (rc, err) = duckdb(
      e.attachLake +
        s"INSERT INTO \"$LakeDb\".\"$LakeSchema\".orders VALUES " +
        "(15, 'carol', 22.50, TIMESTAMP '2026-02-01 00:00:00');" +
        s"DELETE FROM \"$LakeDb\".\"$LakeSchema\".orders WHERE o_id = 60;"
    )
    withClue(err)(rc shouldBe 0)
    try
      val page2 = okResp(
        get(
          e.base,
          rowsOf("orders"),
          alice,
          s"select=o_id&order=o_id&limit=4&offset=4&asOf=$pinned"
        )
      )
      ints(page2, "o_id") shouldBe List(50, 60, 70, 80)
      snapshotOf(page2) shouldBe pinned
      page2.header("Content-Range") shouldBe Some("4-7/*")
      // Pinned content is immutable: briefly cacheable, still private to the Authorization.
      page2.header("Cache-Control") shouldBe Some("private, max-age=300")
      page2.header("Vary") shouldBe Some("Authorization, Accept")

      // The control: the same page unpinned sees the writes, at a newer snapshot.
      val drifted =
        okResp(get(e.base, rowsOf("orders"), alice, "select=o_id&order=o_id&limit=4&offset=4"))
      ints(drifted, "o_id") shouldBe List(40, 50, 70, 80)
      snapshotOf(drifted) should be > pinned

      // A view takes no time travel: asOf on it is refused rather than answered with rows of
      // an unverified snapshot, and an unpinned read of it carries no X-QoD-Snapshot.
      val view = get(
        e.base,
        rowsOf("v_orders"),
        alice,
        s"select=o_id&order=o_id&limit=4&offset=4&asOf=$pinned"
      )
      view.status shouldBe 400
      view.error._1 shouldBe "invalid_selector"
      okResp(get(e.base, rowsOf("v_orders"), alice, "select=o_id&order=o_id"))
        .header("X-QoD-Snapshot") shouldBe None
    finally
      // Restore the seeded rows for the specs that follow.
      duckdb(
        e.attachLake +
          s"DELETE FROM \"$LakeDb\".\"$LakeSchema\".orders WHERE o_id = 15;" +
          s"INSERT INTO \"$LakeDb\".\"$LakeSchema\".orders VALUES " +
          "(60, 'bob', 90.00, TIMESTAMP '2026-01-01 06:00:00');"
      )
  }

  // ---- tenant binding and credentials -----------------------------------------------------

  "a tenant-B PAT" should "be refused on a tenant-A path, exactly like on an unknown tenant" in {
    val e       = env
    val bob     = Some(e.tokens.bob)
    val foreign = get(e.base, rowsOf("orders"), bob)
    foreign.status shouldBe 403
    foreign.error shouldBe ("forbidden", s"your token is scoped to tenant '$Globex'")
    val unknown = get(e.base, s"no_such_tenant/database/$LakeDb/schemas", bob)
    (unknown.status, unknown.body) shouldBe (foreign.status, foreign.body)
  }

  "the credential" should "be a live PAT that allows the rest tool" in {
    val e = env
    get(e.base, lake("schemas"), None).status shouldBe 401
    get(e.base, lake("schemas"), Some(e.tokens.aliceRevoked)).status shouldBe 401
    get(e.base, lake("schemas"), Some(Pat("x", "qod_pat_not-a-token"))).status shouldBe 401
    okResp(get(e.base, lake("schemas"), Some(e.tokens.aliceRestTool)))
    val mcpOnly = get(e.base, lake("schemas"), Some(e.tokens.aliceMcpOnly))
    mcpOnly.status shouldBe 403
    mcpOnly.error._1 shouldBe "forbidden"
  }

  // ---- cap precedence ---------------------------------------------------------------------

  "the row cap" should "be min(limit or defaultLimit, maxRows, the token's maxRows)" in {
    val e                           = env
    def page(token: Pat, q: String) =
      okResp(get(e.base, rowsOf("orders"), Some(token), s"select=o_id&order=o_id$q"))
    // No limit: defaultLimit (3) cut the page, so it is flagged.
    val dflt = page(e.tokens.alice, "")
    ints(dflt, "o_id") shouldBe List(10, 20, 30)
    dflt.header("X-QoD-Truncated") shouldBe Some("true")
    dflt.header("Content-Range") shouldBe Some("0-2/*")
    // The client's own limit is not a truncation.
    val own = page(e.tokens.alice, "&limit=5")
    ints(own, "o_id") should have size 5
    own.header("X-QoD-Truncated") shouldBe None
    // maxRows (6) wins over a larger limit.
    val server = page(e.tokens.alice, "&limit=8")
    ints(server, "o_id") should have size MaxRows
    server.header("X-QoD-Truncated") shouldBe Some("true")
    // The token's maxRows (2) wins over both.
    val token = page(e.tokens.aliceCapped, "&limit=5")
    ints(token, "o_id") shouldBe List(10, 20)
    token.header("X-QoD-Truncated") shouldBe Some("true")
    // A cap that did not bind flags nothing.
    val small = page(e.tokens.alice, "&o_id=in.(10,20)&limit=5")
    ints(small, "o_id") shouldBe List(10, 20)
    small.header("X-QoD-Truncated") shouldBe None
  }

  // ---- audit and history ------------------------------------------------------------------

  "the audit trail" should "history the data statement with the PAT id, never the probe" in {
    val e = env
    // The probe of an ungranted table is refused unrecorded (it is plumbing, like FlightSQL's
    // prepare-time probe): the client gets the plain 404 and no data-denial row is written.
    get(e.base, rowsOf("secret"), alice).status shouldBe 404
    okResp(get(e.base, rowsOf("orders"), alice, "select=o_id&o_id=eq.10"))
    e.journal.drainNow()
    e.telemetry.events.filter(ev =>
      ev.action == "sql.denied" && ev.detail.get("sql").exists(_.contains("\"secret\""))
    ) shouldBe empty
    e.telemetry
      .searchStatements(StatementQuery(tenants = Some(Set(Acme)), limit = 500))
      .map(_.event)
      .filter(_.sql.endsWith("LIMIT 0")) shouldBe empty
    val history = e.telemetry
      .searchStatements(StatementQuery(tenants = Some(Set(Acme)), limit = 500))
      .map(_.event)
      .filter(s =>
        s.status == "ok" && s.sql.contains(s"FROM \"$LakeDb\".\"$LakeSchema\".\"orders\"")
      )
    history should not be empty
    history.map(_.patId).distinct shouldBe List(Some(e.tokens.alice.id))
    history.map(_.username).distinct shouldBe List("alice")
    // The ring the UI reads carries the same statements.
    e.router.history
      .snapshot(200)
      .exists(h => h.user == "alice" && h.sql.contains("\"orders\"")) shouldBe true
  }

  // ---- cold start -------------------------------------------------------------------------

  "a suspended pool" should "resume within the router's hold and answer 200" in {
    val e = env
    e.sup.get(e.coldKey).map(_.suspended) shouldBe Some(true)
    // LocalQuackBackend.start returns once the process is launched, and a fresh node counts as
    // routable before `quack_serve` listens, so the router's hold can end before the node answers.
    // A 503 pool_unavailable here has that cause, which lies in the router's hold, not the edge.
    val r = okResp(
      get(e.base, rowsOf("orders"), alice, s"select=o_id&order=o_id&limit=2&pool=${e.coldKey.pool}")
    )
    ints(r, "o_id") shouldBe List(10, 20)
    e.sup.get(e.coldKey).map(_.suspended) shouldBe Some(false)
  }

  it should "answer 503 pool_resuming with Retry-After once the hold expires" in {
    val e = env
    e.sup.suspendPool(e.coldKey, "idle").unsafeRunSync().isRight shouldBe true
    // No node can appear within the 1 s hold of the second edge's router: the router cannot tell
    // this from a node that is still starting, which is the case the hold exists for.
    e.backend.refuse(e.coldKey)
    try
      val r = get(e.shortHoldBase, rowsOf("orders"), alice, s"select=o_id&pool=${e.coldKey.pool}")
      r.status shouldBe 503
      r.error._1 shouldBe "pool_resuming"
      r.header("Retry-After") shouldBe Some("5")
      r.header("Cache-Control") shouldBe Some("private, no-cache")
    finally e.backend.allow(e.coldKey)
  }

  // ---- the LIKE cost, live -------------------------------------------------------------

  "the worst admitted LIKE pattern" should "stay linear on a live node" in {
    val e = env
    // 4 wildcards (the cap) against a 4 KiB value that almost matches everywhere: the shape a
    // backtracking matcher pays length^(wildcards-1) for.
    for op <- List("like", "ilike") do
      val t0 = System.nanoTime()
      val r  = okResp(get(e.base, rowsOf("notes"), alice, s"select=n_id&body=$op.*a*a*a*b"))
      val ms = (System.nanoTime() - t0) / 1000000
      r.body shouldBe "[]"
      withClue(s"$op took $ms ms: ")(ms should be < 5000L)
  }

  // ---- every kind -------------------------------------------------------------------------

  "an encrypted duckdb-file database" should "be served, without a snapshot, and refuse asOf" in {
    val e    = env
    val base = s"$Acme/database/$FileDb"
    strings(okResp(get(e.base, s"$base/schemas", alice)), "name") shouldBe List(FileSchema)
    val r = okResp(get(e.base, s"$base/schemas/$FileSchema/tables/f/rows", alice, "order=f_id"))
    ints(r, "f_id") shouldBe List(1, 2, 3)
    strings(r, "f_label") shouldBe List("row1", "row2", "row3")
    r.header("X-QoD-Snapshot") shouldBe None
    val tt = get(e.base, s"$base/schemas/$FileSchema/tables/f/rows", alice, "asOf=1")
    tt.status shouldBe 400
    tt.error._1 shouldBe "invalid_kind"
  }

  "a memory database" should "be qualified as \"memory\".\"main\" and refuse asOf" in {
    val e    = env
    val base = s"$Acme/database/$MemDb"
    strings(okResp(get(e.base, s"$base/schemas", alice)), "name") shouldBe List("main")
    val r = okResp(get(e.base, s"$base/schemas/main/tables/m/rows", alice, "order=id&format=csv"))
    r.body shouldBe "id,label\r\n1,m1\r\n2,m2\r\n"
    r.header("X-QoD-Snapshot") shouldBe None
    e.router.history.snapshot(200).exists(_.sql.contains("\"memory\".\"main\".\"m\"")) shouldBe true
    val tt = get(e.base, s"$base/schemas/main/tables/m/rows", alice, "asOfTs=2026-01-01T00:00:00Z")
    tt.status shouldBe 400
    tt.error._1 shouldBe "invalid_kind"
  }

  "a DuckLake database" should "pin every read to a snapshot the catalog knows" in {
    val e = env
    val r = okResp(get(e.base, rowsOf("orders"), alice, "select=o_id&o_id=eq.10"))
    val s = snapshotOf(r)
    e.catalogReader(Acme, LakeDb).snapshotExists(s) shouldBe true
    okResp(get(e.base, rowsOf("orders"), alice, s"select=o_id&o_id=eq.10&asOf=$s"))
      .header("Cache-Control") shouldBe Some("private, max-age=300")
  }

object RestEdgeEndToEndSpec:

  val Acme   = "acme"
  val Globex = "globex"

  /** Tenant-db names; for the attaching kinds also the DuckDB catalog alias (`dbName`), and for the
    * DuckLake kind the Postgres database of the catalog, inside the spec's own embedded server.
    */
  val LakeDb     = "acme_lake"
  val LakeSchema = "tpch1"
  val FileDb     = "acme_file"
  val FileSchema = "app"
  val MemDb      = "acme_mem"
  val FileKey    = "e2e0123456789abcdef0123456789abc"
  val ControlDb  = "qod_rest_e2e"

  val DefaultLimit = 3
  val MaxRows      = 6

  /** Node ports: outside the manager's 21900-22500 and the embedded default 25432. */
  val NodePortMin = 27300
  val NodePortMax = 27399

  final case class Pat(id: String, raw: String)

  final case class Tokens(
      alice: Pat,
      aliceCapped: Pat,
      aliceRestTool: Pat,
      aliceMcpOnly: Pat,
      aliceRevoked: Pat,
      bob: Pat
  )

  final case class Env(
      base: String,
      shortHoldBase: String,
      sup: PoolSupervisor,
      backend: GatedBackend,
      router: FlightSqlRouter,
      journal: EventJournal,
      telemetry: RecordingTelemetryStore,
      tokens: Tokens,
      coldKey: PoolKey,
      attachLake: String,
      catalogReader: (String, String) => DuckLakeCatalogReader
  )

  /** The real local backend, with one seam for the cold-start case: a pool on the refused list
    * cannot spawn, so a resume produces no node within the router's hold.
    */
  final class GatedBackend(inner: LocalQuackBackend) extends QuackBackend:
    @volatile private var refused: Set[PoolKey] = Set.empty
    def refuse(k: PoolKey): Unit                = synchronized(refused += k)
    def allow(k: PoolKey): Unit                 = synchronized(refused -= k)
    def start(spec: NodeSpec): IO[RunningNode]  =
      IO.defer(
        if refused.contains(spec.poolKey) then
          IO.raiseError(new IllegalStateException(s"spawn of ${spec.poolKey} refused by the spec"))
        else inner.start(spec)
      )
    def stop(key: PoolKey, nodeId: String): IO[Unit] = inner.stop(key, nodeId)
    def isAlive(nodeId: String): Boolean             = inner.isAlive(nodeId)
    def discoverExisting(): IO[List[RunningNode]]    = inner.discoverExisting()
    def cleanup(): IO[Unit]                          = inner.cleanup()

  def attachLakeSql(meta: Map[String, String], dataPath: Path): String =
    s"""INSTALL ducklake; LOAD ducklake; INSTALL postgres; LOAD postgres;
       |ATTACH 'ducklake:postgres:host=${meta("pgHost")} port=${meta("pgPort")} dbname=${meta(
        "dbName"
      )} user=${meta("pgUser")} password=${meta("pgPassword")}' AS "${meta("dbName")}"
       |  (DATA_PATH '$dataPath');
       |""".stripMargin

  /** `orders`: ids 10..100, odd steps alice and even ones bob, totals 1.5 x id, one hour apart;
    * `v_orders` over it; `secret`, never granted; `notes`, one 4 KiB value for the LIKE cost case.
    * The view names its table with the same catalog alias the node attaches the catalog under.
    */
  val SeedLakeSql: String =
    val c = s""""$LakeDb"."$LakeSchema""""
    s"""CREATE SCHEMA "$LakeDb"."$LakeSchema";
       |CREATE TABLE $c.orders (o_id INTEGER NOT NULL, o_customer VARCHAR, o_total DECIMAL(12,2), o_ts TIMESTAMP);
       |INSERT INTO $c.orders
       |  SELECT (i * 10)::INTEGER, CASE WHEN i % 2 = 1 THEN 'alice' ELSE 'bob' END,
       |         (i * 15)::DECIMAL(12,2), TIMESTAMP '2026-01-01 00:00:00' + to_hours(i)
       |  FROM range(1, 11) t(i);
       |CREATE VIEW $c.v_orders AS SELECT o_id, o_total FROM $c.orders;
       |CREATE TABLE $c.secret (x INTEGER);
       |INSERT INTO $c.secret VALUES (42);
       |CREATE TABLE $c.notes (n_id INTEGER, body VARCHAR);
       |INSERT INTO $c.notes VALUES (1, repeat('a', 4096));
       |CALL ducklake_flush_inlined_data('$LakeDb');
       |""".stripMargin

  /** The encrypted file, written with httpfs loaded (OpenSSL: without it an encrypted database
    * refuses writes).
    */
  def seedFileSql(path: Path): String =
    s"""INSTALL httpfs; LOAD httpfs;
       |ATTACH '$path' AS "$FileDb" (ENCRYPTION_KEY '$FileKey');
       |CREATE SCHEMA "$FileDb"."$FileSchema";
       |CREATE TABLE "$FileDb"."$FileSchema".f AS
       |  SELECT i::INTEGER AS f_id, 'row' || i AS f_label FROM range(1, 4) t(i);
       |""".stripMargin

  /** The `memory` kind has nothing on disk: each node builds its table from the tenant-db's
    * initSql, which the spawn script runs before the node serves.
    */
  val SeedMemorySql: String =
    "CREATE TABLE memory.main.m AS SELECT i::INTEGER AS id, 'm' || i AS label FROM range(1, 3) t(i);"
