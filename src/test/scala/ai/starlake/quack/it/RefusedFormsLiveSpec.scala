package ai.starlake.quack.it

import ai.starlake.quack.QuackNativeConfig
import ai.starlake.quack.edge.*
import ai.starlake.quack.edge.adapter.*
import ai.starlake.quack.edge.authz.{AuthzDecider, DecideRoute, MintedHandles, NodeKeys}
import ai.starlake.quack.edge.authz.location.{LocationRules, LocationRulesSource}
import ai.starlake.quack.edge.config.OpaConfig
import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import ai.starlake.quack.edge.opa.{OpaAuthorizer, OpaClient, OpaDecisionCache}
import ai.starlake.quack.edge.quack.*
import ai.starlake.quack.edge.sql.{PostgresAclValidator, StatementValidator}
import ai.starlake.quack.model.*
import ai.starlake.quack.observability.metrics.OpaInstruments
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.rbac.{AuthorizedHandshake, EffectiveSet}
import ai.starlake.quack.ondemand.runtime.{LiveQodNode, QodNodeTestBinary, QuackBackend}
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RbacUser, RolePermission}
import ai.starlake.quack.spi.ManagerEventSink
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.sun.net.httpserver.HttpServer
import org.http4s.{Header, Headers, Method, Request, Uri}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIString

import java.net.InetSocketAddress
import java.nio.file.{Files, Path}
import java.time.Instant
import scala.collection.concurrent.TrieMap
import scala.jdk.CollectionConverters.*
import scala.sys.process.{Process, ProcessLogger}

/** Task 19 on a real qod-node decided by the manager's own decision route, through both front doors
  * (the FlightSQL router's Arrow transport, and the native Quack front door driven by the DuckDB
  * 2.0 CLI's `quack_query`), for tenant users of a duckdb-file database `acme_db` (schema `tpch1`):
  * alice reads `t` and the secure view `sv` (over `secret`, which she cannot read), bob reads only
  * `other`.
  *
  * Two routers serve the same node: `gated` runs the gateway's ACL gate with the metadata filter
  * mounted, as Main wires it with the ACL on; `open` runs no gate (the ACL off), so what it refuses
  * the node refused. Cancelled without a qod-node build; the native front door cases also without a
  * DuckDB 2.0 CLI on PATH.
  */
class RefusedFormsLiveSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val duckdbPresent: Boolean =
    val out = new StringBuilder
    scala.util
      .Try(
        Process(Seq("duckdb", "-no-agent", "-version"))
          .!(ProcessLogger(l => out.append(l), _ => ()))
      )
      .toOption
      .contains(0) && out.toString.trim.startsWith("v2.")
  private val nodeReady = QodNodeTestBinary.path.nonEmpty

  private val poolKey = PoolKey("acme", "acme_db", "bi")

  /** A tenant user of acme holding `grants`, each `(schema, table)` RO or `(schema, table, verb)`.
    */
  private def user(id: String, name: String, grants: Product*): EffectiveSet =
    EffectiveSet(
      RbacUser(id, Some("acme"), name, "user"),
      Nil,
      Nil,
      grants.toList.zipWithIndex.map {
        case ((schema: String, table: String), i) =>
          RolePermission(s"p-$id-$i", s"r-$id", "acme_db", schema, table, "RO")
        case ((schema: String, table: String, verb: String), i) =>
          RolePermission(s"p-$id-$i", s"r-$id", "acme_db", schema, table, verb)
        case (other, _) => fail(s"not a grant: $other")
      },
      Nil
    )
  private val alice = user("u-alice", "alice", "tpch1" -> "t", "tpch1" -> "sv")
  private val bob   = user("u-bob", "bob", "tpch1" -> "other")
  // ADD COLUMN ... DEFAULT nextval(...): carol may alter t2 and update s2, dave only alter t2.
  private val carol = user("u-carol", "carol", ("tpch1", "t2", "ALL"), ("tpch1", "s2", "RW"))
  private val dave  = user("u-dave", "dave", ("tpch1", "t2", "ALL"))
  private val root  = EffectiveSet(RbacUser("u-root", None, "root", "admin"), Nil, Nil, Nil, Nil)
  private val users =
    Map("alice" -> alice, "bob" -> bob, "carol" -> carol, "dave" -> dave, "root" -> root)

  private val secret                 = NodeKeys.randomSecret()
  private val minted                 = new MintedHandles()
  private var dir: Path              = null
  private var live: LiveQodNode      = null
  private var decide: HttpServer     = null
  private var sup: PoolSupervisor    = null
  private var gated: FlightSqlRouter = null
  private var open: FlightSqlRouter  = null
  private val doors     = scala.collection.mutable.ListBuffer.empty[QuackFrontDoorServer]
  private var gatedPort = 0
  private var openPort  = 0

  private def freePort(): Int = LiveQodNode.freePort()

  override def beforeAll(): Unit =
    if nodeReady then
      dir = Files.createTempDirectory("qod-refused-forms")
      decide = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      val keys = new NodeKeys(
        secret,
        s"http://127.0.0.1:${decide.getAddress.getPort}",
        minted = Some(minted),
        paths = key =>
          Option(sup).flatMap(_.get(key)).flatMap(FlightSqlRouter.usePath).getOrElse("")
      )
      val init =
        s"""ATTACH '$dir/acme.duckdb' AS acme_db;
           |CREATE SCHEMA acme_db.tpch1;
           |CREATE TABLE acme_db.tpch1.t (id INTEGER NOT NULL, v VARCHAR DEFAULT 'x', d DOUBLE, ts TIMESTAMP);
           |INSERT INTO acme_db.tpch1.t VALUES (1, 'a', 1.5, TIMESTAMP '2026-01-01'), (2, 'b', NULL, NULL), (3, 'b', 3.25, TIMESTAMP '2026-02-01');
           |CREATE TABLE acme_db.tpch1.secret AS SELECT 7 AS x, 'hidden' AS y;
           |CREATE TABLE acme_db.tpch1.other AS SELECT 1 AS o;
           |CREATE SECURE VIEW acme_db.tpch1.sv AS SELECT x FROM acme_db.tpch1.secret;
           |CREATE TABLE acme_db.tpch1.t2 AS SELECT range AS id FROM range(3);
           |CREATE SEQUENCE acme_db.tpch1.s2;
           |""".stripMargin
      val nodePort = freePort()
      val backend  = new QuackBackend:
        private val n          = TrieMap.empty[String, RunningNode]
        def start(s: NodeSpec) = IO {
          live = LiveQodNode.start(dir, init, nodeId = s.nodeId, keys = keys, port = nodePort)
          val r = RunningNode(
            s.nodeId,
            s.poolKey,
            s.role,
            "127.0.0.1",
            nodePort,
            live.token,
            Some(1L),
            None,
            Instant.EPOCH,
            maxConcurrent = s.maxConcurrent
          )
          n.put(s.nodeId, r); r
        }
        def stop(key: PoolKey, id: String) = IO { n.remove(id); () }
        def isAlive(id: String)            = n.contains(id)
        def discoverExisting()             = IO.pure(n.values.toList)
        def cleanup()                      = IO(n.clear())
      val tracker = new NodeLoadTracker
      sup = new PoolSupervisor(backend, tracker, new InMemoryControlPlaneStore())
      sup.createTenant(Tenant(poolKey.tenant)).unsafeRunSync()
      sup
        .createTenantDb(
          poolKey.tenant,
          poolKey.tenantDb,
          TenantDbKind.DuckDbFile,
          Map("dbName" -> "acme_db", "schemaName" -> "tpch1"),
          s"$dir/acme.duckdb"
        )
        .unsafeRunSync()
      sup.createPool(poolKey, RoleDistribution(0, 0, 1)).unsafeRunSync()
      val decider = new AuthzDecider(
        tenantOf = t => Option.when(t == "acme")(Tenant("acme")),
        tenantCatalogs = t => if t == "acme" then Set("acme_db") else Set.empty,
        poolCatalog = key =>
          sup.get(key).flatMap(st => AuthzDecider.attachedCatalog(st.kindWire, st.metastore)),
        parentPoolsOf = _ => Nil,
        locations = new LocationRulesSource {
          def rulesFor(tenantId: String, userId: String): Either[String, LocationRules] =
            Right(LocationRules.Empty)
        },
        opa = new OpaAuthorizer(
          OpaConfig.default,
          new OpaClient(500, (_, _, _) => Left("no OPA in this spec")),
          new OpaDecisionCache(0),
          OpaInstruments.noop
        ),
        opaCfg = OpaConfig.default
      )
      val app = new DecideRoute(keys, minted, decider).routes.orNotFound
      decide.createContext(
        "/",
        exchange =>
          try
            val headers = exchange.getRequestHeaders.asScala.toList.flatMap((name, values) =>
              values.asScala.map(v => Header.Raw(CIString(name), v))
            )
            val request = Request[IO](
              Method.fromString(exchange.getRequestMethod).toOption.get,
              Uri.unsafeFromString(exchange.getRequestURI.toString),
              headers = Headers(headers)
            ).withBodyStream(fs2.Stream.emits(exchange.getRequestBody.readAllBytes().toSeq))
            val response = app.run(request).unsafeRunSync()
            val bytes    = response.body.compile.to(Array).unsafeRunSync()
            exchange.getResponseHeaders.set("Content-Type", "text/plain; charset=UTF-8")
            exchange.sendResponseHeaders(response.status.code, bytes.length.toLong)
            exchange.getResponseBody.write(bytes)
          finally exchange.close()
      )
      decide.start()
      val client = new QuackHttpClient(new org.apache.arrow.memory.RootAllocator())
      def router(validator: StatementValidator, filter: Boolean) = new FlightSqlRouter(
        sup,
        new SessionRegistry,
        tracker,
        new QuackHttpAdapter(client, tracker, keys),
        validator = validator,
        metadataFilterRewriter = new MetadataFilterRewriter(enabled = filter)
      )
      gated = router(
        new PostgresAclValidator(
          defaultDatabase = "acme_db",
          defaultSchema = "tpch1",
          tenantCatalogs = t => if t == "acme" then Set("acme_db") else Set.empty,
          filteredMetadata = true
        ),
        filter = true
      )
      open = router(StatementValidator.allowAll, filter = false)
      gatedPort = door(gated)
      openPort = door(open)

  /** A native front door for `router` on a free port, its users bound by name. */
  private def door(router: FlightSqlRouter): Int =
    val port      = freePort()
    val handshake = new EdgeHandshake(
      ai.starlake.quack.security.InMemoryAuthService.acceptingAnyPassword(),
      lookupPool = (t, p) =>
        sup.findPoolKeyByTenantAndPoolName(t, p).map(_.tenantDb).toRight(s"pool '$p' not found"),
      resolveTenant = raw => sup.getTenant(raw),
      authorize = req =>
        val eff = users(req.username)
        Right(AuthorizedHandshake(poolKey, "acme", "p-1", eff.user, eff))
    )
    val transport = new QuackProtocol.JdkHttpTransport(java.net.http.HttpClient.newHttpClient())
    val frontDoor = new QuackFrontDoor(
      router,
      handshake,
      new QuackSessionRegistry(3600, 3600),
      transport,
      ManagerEventSink.noop,
      "test"
    )
    val server = new QuackFrontDoorServer(
      QuackNativeConfig(true, "127.0.0.1", port, false, "", "", 3600, 1L << 28),
      frontDoor.handle,
      frontDoor.sweep,
      frontDoor.closeAll()
    )
    server.start().unsafeRunSync()
    doors += server
    port

  override def afterAll(): Unit =
    try doors.foreach(d => scala.util.Try(d.stop()))
    finally
      try if live != null then live.stop()
      finally
        if decide != null then decide.stop(0)
        if dir != null then
          Thread.sleep(200)
          try
            Files
              .walk(dir)
              .sorted(java.util.Comparator.reverseOrder())
              .forEach(p => Files.delete(p))
          catch case e: Exception => System.err.println(s"[RefusedFormsLiveSpec] cleanup: $e")

  private var connections = 0

  /** `sql` as `who` through `router`'s FlightSQL path: the rows as `a,b` lines, or the failure. */
  private def flight(
      router: FlightSqlRouter,
      who: String,
      sql: String
  ): Either[String, List[String]] =
    assume(nodeReady, "no qod-node binary (QOD_NODE_BIN or a local build)")
    connections += 1
    router
      .execute(s"c-$connections", who, poolKey, sql, Some(users(who)))
      .unsafeRunSync() match
      case Left(f)  => Left(f.reason)
      case Right(r) =>
        LiveQodNode.lines(QuackResponse.Ok(r.rows, r.durationMs, r.close))

  /** `sql` as `who` through the native front door on `port`, by the DuckDB CLI: (rc, rows, errors).
    */
  private def native(port: Int, who: String, sql: String): (Int, String, String) =
    assume(nodeReady, "no qod-node binary (QOD_NODE_BIN or a local build)")
    assume(duckdbPresent, "no DuckDB 2.0 CLI on PATH")
    val token = s"tenant=acme&pool=bi&user=$who&password=x"
    val query = sql.replace("'", "''")
    val out   = new StringBuilder
    val err   = new StringBuilder
    val rc    = Process(
      Seq(
        "duckdb",
        "-no-agent",
        "-csv",
        "-noheader",
        "-c",
        s"SELECT * FROM quack_query('quack:127.0.0.1:$port', '$query', token := '$token');"
      )
    ).!(ProcessLogger(l => out.append(l).append('\n'), l => err.append(l).append('\n')))
    (rc, out.toString.trim, err.toString.trim)

  "DESCRIBE" should "answer DuckDB's columns to a principal holding a read grant" in {
    flight(gated, "alice", "DESCRIBE t") shouldBe Right(
      List(
        "id,INTEGER,NO,,,",
        "v,VARCHAR,YES,,'x',",
        "d,DOUBLE,YES,,,",
        "ts,TIMESTAMP,YES,,,"
      )
    )
    flight(open, "alice", "DESCRIBE TABLE acme_db.tpch1.t").map(
      _.map(_.takeWhile(_ != ','))
    ) shouldBe
      Right(List("id", "v", "d", "ts"))
    val (rc, out, err) = native(gatedPort, "alice", "DESCRIBE t")
    withClue(err)(rc shouldBe 0)
    out.split("\n").toList.map(_.takeWhile(_ != ',')) shouldBe List("id", "v", "d", "ts")
  }

  it should "be refused by the node's grant check without a grant" in {
    flight(open, "bob", "DESCRIBE t") match
      case Left(m) => m should include("QoD: user:bob lacks grants on acme_db.tpch1.t:read")
      case other   => fail(s"expected the node's refusal, got $other")
    val (rc, _, err) = native(openPort, "bob", "DESCRIBE t")
    rc should not be 0
    err should include("lacks grants on acme_db.tpch1.t:read")
    // With the gateway's gate, the gateway refuses first, for the same missing grant.
    flight(gated, "bob", "PRAGMA table_info('t')") match
      case Left(m) => m should include("lacks grants on acme_db.tpch1.t")
      case other   => fail(s"expected a refusal, got $other")
  }

  it should "describe a secure view on its own grant" in {
    flight(gated, "alice", "DESCRIBE sv") shouldBe Right(List("x,INTEGER,YES,,,"))
    flight(open, "alice", "SHOW sv") shouldBe Right(List("x,INTEGER,YES,,,"))
    val (rc, out, err) = native(openPort, "alice", "DESCRIBE sv")
    withClue(err)(rc shouldBe 0)
    out.split("\n").toList.map(_.takeWhile(_ != ',')) shouldBe List("x")
    // ... and not the table behind it.
    flight(open, "alice", "DESCRIBE secret").isLeft shouldBe true
  }

  "PRAGMA table_info" should "answer its own column shape" in {
    flight(gated, "alice", "PRAGMA table_info('t')") shouldBe Right(
      List(
        "0,id,INTEGER,true,,",
        "1,v,VARCHAR,false,'x',",
        "2,d,DOUBLE,false,,",
        "3,ts,TIMESTAMP,false,,"
      )
    )
  }

  "SUMMARIZE" should "match DuckDB's own SUMMARIZE, which a superuser runs" in {
    val own = LiveQodNode.lines(
      new QuackHttpClient(TestArrow.sharedAllocator)
        .query(
          live.endpoint,
          live.token,
          "SUMMARIZE acme_db.tpch1.t",
          None,
          live.authorize()
        )
        .unsafeRunSync()
    )
    own.map(_.size) shouldBe Right(4)
    flight(gated, "alice", "SUMMARIZE t") shouldBe own
    flight(open, "alice", "SUMMARIZE TABLE tpch1.t") shouldBe own
    val (rc, out, err)    = native(gatedPort, "alice", "SUMMARIZE t")
    val (rc2, out2, err2) = native(gatedPort, "root", "SUMMARIZE t")
    withClue(err)(rc shouldBe 0)
    withClue(err2)(rc2 shouldBe 0)
    out shouldBe out2
  }

  it should "read its columns on the transaction's own link inside a client transaction" in {
    assume(nodeReady, "no qod-node binary (QOD_NODE_BIN or a local build)")
    assume(duckdbPresent, "no DuckDB 2.0 CLI on PATH")
    val token  = "tenant=acme&pool=bi&user=alice&password=x"
    val script =
      s"""ATTACH 'quack:127.0.0.1:$gatedPort' AS q (TYPE quack, TOKEN '$token');
         |BEGIN;
         |SELECT count(*) FROM q.tpch1.t;
         |SELECT * FROM quack_query_by_name('q', 'SUMMARIZE t');
         |COMMIT;
         |""".stripMargin
    val out = new StringBuilder
    val err = new StringBuilder
    val rc  = (Process(Seq("duckdb", "-no-agent", "-csv", "-noheader")) #<
      new java.io.ByteArrayInputStream(script.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
      .!(ProcessLogger(l => out.append(l).append('\n'), l => err.append(l).append('\n')))
    withClue(err.toString)(rc shouldBe 0)
    val lines = out.toString.trim.split("\n").toList
    lines.head shouldBe "3"
    lines.tail.map(_.takeWhile(_ != ',')) shouldBe List("id", "v", "d", "ts")
  }

  it should "be refused without a grant on the table" in {
    flight(open, "bob", "SUMMARIZE t") match
      case Left(m) => m should include("lacks grants on acme_db.tpch1.t:read")
      case other   => fail(s"expected the node's refusal, got $other")
    val (rc, _, err) = native(openPort, "bob", "SUMMARIZE t")
    rc should not be 0
    err should include("lacks grants on acme_db.tpch1.t:read")
  }

  "SHOW TABLES" should "list only the tables the principal is granted" in {
    flight(gated, "alice", "SHOW TABLES") shouldBe Right(List("sv", "t"))
    flight(gated, "bob", "SHOW TABLES") shouldBe Right(List("other"))
    val (rc, out, err) = native(gatedPort, "alice", "SHOW TABLES")
    withClue(err)(rc shouldBe 0)
    out.split("\n").toList shouldBe List("sv", "t")
    flight(gated, "alice", "SHOW ALL TABLES").map(
      _.map(_.split(",").take(3).mkString("."))
    ) shouldBe
      Right(List("acme_db.tpch1.sv", "acme_db.tpch1.t"))
  }

  "SHOW <setting>" should "stay refused" in {
    flight(gated, "alice", "SHOW threads").isLeft shouldBe true
    flight(open, "alice", "SHOW threads") match
      case Left(m) => m should include("QoD:")
      case other   => fail(s"expected the node's refusal, got $other")
    val (rc, _, _) = native(openPort, "alice", "SHOW threads")
    rc should not be 0
  }

  "a built-in macro in a bind-time slot" should "run when its body calls only allowed functions" in {
    flight(open, "alice", "SELECT count(*) FROM range(fdiv(100, 7)::BIGINT)") shouldBe Right(
      List("14")
    )
    flight(open, "alice", "SELECT id FROM t ORDER BY id LIMIT if(true, 2, 3)") shouldBe
      Right(List("1", "2"))
    val (rc, out, err) =
      native(openPort, "alice", "SELECT count(*) FROM range(fdiv(100, 7)::BIGINT)")
    withClue(err)(rc shouldBe 0)
    out shouldBe "14"
    // ... and is refused, like the function it calls, when that one is superuser-class.
    flight(open, "alice", "SELECT 1 LIMIT get_block_size('acme_db')") match
      case Left(m) => m should include("QoD: get_block_size is reserved to superusers")
      case other   => fail(s"expected the screen's refusal, got $other")
  }

  "ADD COLUMN ... DEFAULT nextval(...)" should "run for a principal that may alter the table and draw from the sequence" in {
    flight(open, "carol", "ALTER TABLE t2 ADD COLUMN n BIGINT DEFAULT nextval('s2')") shouldBe
      Right(Nil)
    flight(open, "carol", "SELECT count(DISTINCT n) FROM t2") shouldBe Right(List("3"))
    val (rc, _, err) =
      native(openPort, "carol", "ALTER TABLE t2 ADD COLUMN n2 BIGINT DEFAULT nextval('s2')")
    withClue(err)(rc shouldBe 0)
  }

  it should "be refused without a write grant on the sequence" in {
    flight(open, "dave", "ALTER TABLE t2 ADD COLUMN m BIGINT DEFAULT nextval('s2')") match
      case Left(m) => m should include("lacks grants on acme_db.tpch1.s2")
      case other   => fail(s"expected the node's refusal, got $other")
    flight(
      open,
      "carol",
      "SELECT count(*) FROM duckdb_columns() WHERE table_name = 't2' AND column_name = 'm'"
    ) shouldBe
      Right(List("0"))
  }

  it should "still refuse a superuser-class call in the default" in {
    // The gateway's own node-state screen refuses it first; the node's screen refuses it too
    // (screen_rules_test).
    flight(
      open,
      "carol",
      "ALTER TABLE t2 ADD COLUMN w VARCHAR DEFAULT current_setting('threads')"
    ) match
      case Left(m) => m should include("current_setting")
      case other   => fail(s"expected a refusal, got $other")
  }

  "a superuser's DESCRIBE" should "run as written" in {
    flight(gated, "root", "DESCRIBE acme_db.tpch1.t").map(_.map(_.takeWhile(_ != ','))) shouldBe
      Right(List("id", "v", "d", "ts"))
    gated.history.snapshot(50).exists(_.sql == "DESCRIBE acme_db.tpch1.t") shouldBe true
  }
