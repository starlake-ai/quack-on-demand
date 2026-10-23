package ai.starlake.quack.it

import ai.starlake.quack.QuackNativeConfig
import ai.starlake.quack.edge.*
import ai.starlake.quack.edge.adapter.*
import ai.starlake.quack.edge.auth.AuthenticationService
import ai.starlake.quack.edge.config.AuthenticationConfig
import ai.starlake.quack.edge.quack.*
import ai.starlake.quack.edge.cls.{ColumnCatalog, ColumnPolicyRewriter}
import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import ai.starlake.quack.edge.sql.PostgresAclValidator
import ai.starlake.quack.model.*
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.rbac.{AuthorizedHandshake, EffectiveSet}
import ai.starlake.quack.ondemand.runtime.{LiveQodNode, QodNodeTestBinary, QuackBackend}
import ai.starlake.quack.ondemand.state.{
  InMemoryControlPlaneStore,
  RbacUser,
  RoleColumnPolicy,
  RolePermission,
  RoleRowPolicy
}
import ai.starlake.quack.spi.ManagerEventSink
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.ServerSocket
import java.nio.file.{Files, Path}
import java.time.Instant
import scala.collection.concurrent.TrieMap
import scala.sys.process.{Process, ProcessLogger}

/** Epic 5, task 4: the real DuckDB 2.0 CLI (and, when `uv` is present, the Python package) against
  * the front door, which relays to a real qod-node laid out like a QoD node (attached catalog,
  * non-default schema, USE'd on the init connection only). The front door speaks Quack protocol 3
  * only, so the `duckdb` on PATH, the client, must be a 2.0 build. Cancelled when it is missing or
  * older, or without a qod-node build, like the other real-node specs.
  *
  * The front door runs the REAL ACL gate: `PostgresAclValidator` with the metadata filter mounted,
  * and alice holds narrow grants (RW on `customer`, RO on `ids`, nothing on `secret`) PLUS a column
  * mask on `customer.c_name` and a row policy on `secret`, so the CLS and RLS rewriters run on
  * every statement too. A stand-in validator that admitted everything used to hide that the
  * client's attach-time catalog sync (`duckdb_tables() UNION ALL duckdb_views()`) was denied for
  * every ordinary principal (issue #114), and a policy-free principal then hid that the CLS
  * rewriter denied the same sync for anyone holding a column policy (the issue's second report).
  *
  * A 2.0 client sends `BEGIN TRANSACTION` when it first touches the attached catalog, and ships an
  * INSERT as `INSERT ... SELECT * FROM scan_data_from_quack_client(...)` followed by SEND_DATA
  * messages, so every attach case also runs those through the gate.
  *
  * The Python cases provision their DuckDB through `uv`: a 2.0 pre-release wheel whose bundled
  * quack extension is the nodes' own commit ([[Python20]]), and a 1.5 release that the front door
  * must refuse by version ([[Python15]]).
  */
class QuackCompatibilitySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  // -no-agent: under an AI agent (AI_AGENT / CLAUDECODE set) the 2.0 CLI switches its output format.
  // Total: with no `duckdb` on PATH, starting the process throws (IOException "Cannot run program"),
  // which must cancel every case rather than abort the suite.
  private val duckdbPresent: Boolean =
    val out = new StringBuilder
    scala.util
      .Try(
        Process(Seq("duckdb", "-no-agent", "-version"))
          .!(ProcessLogger(l => out.append(l), _ => ()))
      )
      .toOption
      .contains(0) && out.toString.trim.startsWith("v2.")
  private val uvPresent: Boolean =
    scala.util.Try(Process("which uv").!(ProcessLogger(_ => ()))).toOption.contains(0)
  // Every case needs the CLI (the client) and the qod-node build (the node behind the door).
  private val liveReady: Boolean = duckdbPresent && QodNodeTestBinary.path.nonEmpty

  private def freePort(): Int =
    val s = new ServerSocket(0)
    try s.getLocalPort
    finally s.close()

  private val nodePort  = freePort()
  private val doorPort  = freePort()
  private val nodeToken = "nodetok1"
  private val poolKey   = PoolKey("acme", "acme_db", "sales")
  private val Token     = "tenant=acme&pool=sales&user=alice&password=x"

  // The node's keys are the ones the router mints its session handles with.
  private val keys = ai.starlake.quack.edge.authz.NodeKeys.ephemeral()

  private var dir: Path                    = null
  private var live: LiveQodNode            = null
  private var server: QuackFrontDoorServer = null
  private var router: FlightSqlRouter      = null

  override def beforeAll(): Unit =
    if liveReady then
      dir = Files.createTempDirectory("qod-quack-compat")
      val init =
        s"""ATTACH '$dir/acme.duckdb' AS acme_db;
           |CREATE SCHEMA acme_db.tpch1;
           |CREATE TABLE acme_db.tpch1.customer AS SELECT range AS c_custkey, 'name' || range AS c_name FROM range(2000);
           |CREATE TABLE acme_db.tpch1.secret AS SELECT 1 AS x;
           |CREATE TABLE acme_db.tpch1.ids AS SELECT range AS id FROM range(60000);
           |CREATE SCHEMA acme_db.other;
           |CREATE TABLE acme_db.other.ids AS SELECT range AS id FROM range(7);
           |USE acme_db.tpch1;
           |""".stripMargin
      startFrontDoor(init)

  private def startFrontDoor(init: String): Unit =
    val backend = new QuackBackend:
      private val n          = TrieMap.empty[String, RunningNode]
      def start(s: NodeSpec) = IO {
        // The node's id is the one the router mints its handles for.
        live = LiveQodNode.start(
          dir,
          init,
          nodeId = s.nodeId,
          keys = keys,
          port = nodePort,
          token = nodeToken
        )
        val r = RunningNode(
          s.nodeId,
          s.poolKey,
          s.role,
          "127.0.0.1",
          nodePort,
          nodeToken,
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
    val sup     = new PoolSupervisor(backend, tracker, new InMemoryControlPlaneStore())
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
    // The real RBAC gate, as Main wires it: grant-checked per statement, with the metadata filter
    // mounted from the same flag so catalog reads are narrowed instead of denied.
    val validator = new PostgresAclValidator(
      defaultDatabase = "acme_db",
      defaultSchema = "tpch1",
      tenantCatalogs = t => if t == "t-1" then Set("acme_db") else Set.empty,
      filteredMetadata = true
    )
    val client = new QuackHttpClient(new org.apache.arrow.memory.RootAllocator())
    // The column catalog the CLS resolver needs, mirroring the node's tables.
    val columns = new ColumnCatalog.MapCatalog(
      Map(
        ("acme_db", "tpch1", "customer") -> List("c_custkey", "c_name"),
        ("acme_db", "tpch1", "ids")      -> List("id"),
        ("acme_db", "tpch1", "secret")   -> List("x")
      )
    )
    router = new FlightSqlRouter(
      sup,
      new SessionRegistry,
      tracker,
      new QuackHttpAdapter(client, tracker, keys),
      validator = validator,
      columnPolicyRewriter = new ColumnPolicyRewriter(columns),
      metadataFilterRewriter = new MetadataFilterRewriter(enabled = true)
    )
    val user   = RbacUser("u-1", Some("t-1"), "alice", kind = "user")
    val grants = List(
      RolePermission("rp-1", "r-1", "acme_db", "tpch1", "customer", "RW"),
      RolePermission("rp-2", "r-1", "acme_db", "tpch1", "ids", "RO")
    )
    val masks = List(
      RoleColumnPolicy(
        "cp-1",
        "r-1",
        "acme_db",
        "tpch1",
        "customer",
        "c_name",
        "mask",
        Some("'***'")
      )
    )
    val rowPolicies = List(RoleRowPolicy("rlp-1", "r-1", "acme_db", "tpch1", "secret", "x = 1"))
    val eff         = EffectiveSet(user, Nil, Nil, grants, Nil, masks, rowPolicies)
    val handshake   = new EdgeHandshake(
      new AuthenticationService(AuthenticationConfig.disabled, "x"),
      lookupPool = (t, p) =>
        sup.findPoolKeyByTenantAndPoolName(t, p).map(_.tenantDb).toRight(s"pool '$p' not found"),
      resolveTenant = raw => sup.getTenant(raw),
      authorize = _ => Right(AuthorizedHandshake(poolKey, "t-1", "p-1", user, eff))
    )
    val transport = new QuackProtocol.JdkHttpTransport(java.net.http.HttpClient.newHttpClient())
    val door      = new QuackFrontDoor(
      router,
      handshake,
      new QuackSessionRegistry(3600, 3600),
      transport,
      ManagerEventSink.noop,
      "test"
    )
    server = new QuackFrontDoorServer(
      QuackNativeConfig(true, "127.0.0.1", doorPort, false, "", "", 3600, 1L << 28),
      door.handle,
      door.sweep,
      door.closeAll()
    )
    server.start().unsafeRunSync()

  override def afterAll(): Unit =
    // The node stops (and its directory goes) whether or not the front door stopped cleanly.
    try if server != null then server.stop()
    finally
      if live != null then live.stop()
      if dir != null then
        Thread.sleep(200)
        try
          Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.delete(p))
        catch case e: Exception => System.err.println(s"[QuackCompatibilitySpec] cleanup: $e")

  /** Run SQL through the real DuckDB CLI; returns (exit code, stdout, stderr). */
  private def cli(sql: String): (Int, String, String) =
    val out = new StringBuilder
    val err = new StringBuilder
    val rc  = Process(Seq("duckdb", "-no-agent", "-csv", "-noheader", "-c", sql))
      .!(ProcessLogger(l => out.append(l).append('\n'), l => err.append(l).append('\n')))
    (rc, out.toString.trim, err.toString.trim)

  /** Run a script on the real DuckDB CLI's stdin. Unlike `-c`, a statement that fails does not end
    * the run; returns (exit code, stdout, stderr).
    */
  private def cliScript(sql: String): (Int, String, String) =
    val out = new StringBuilder
    val err = new StringBuilder
    val rc  = (Process(Seq("duckdb", "-no-agent", "-csv", "-noheader")) #<
      new java.io.ByteArrayInputStream(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
      .!(ProcessLogger(l => out.append(l).append('\n'), l => err.append(l).append('\n')))
    (rc, out.toString.trim, err.toString.trim)

  private def endpoint = s"quack:127.0.0.1:$doorPort"
  private def attach   = s"ATTACH '$endpoint' AS q (TYPE quack, TOKEN '$Token');"

  "quack_query through the front door" should "route a granted statement and record it" in:
    assume(liveReady, "DuckDB 2.0 CLI or a qod-node build not available")
    val (rc, out, err) =
      cli(
        s"SELECT count(*) FROM quack_query('$endpoint', 'SELECT count(*) FROM customer', token := '$Token');"
      )
    withClue(err)(rc shouldBe 0)
    out shouldBe "1"
    val (_, out2, err2) =
      cli(
        s"SELECT * FROM quack_query('$endpoint', 'SELECT count(*) FROM customer', token := '$Token');"
      )
    withClue(err2)(out2 shouldBe "2000")
    val rec = router.history.snapshot(20).find(_.sql == "SELECT count(*) FROM customer")
    rec.map(r => (r.status, r.user)) shouldBe Some(("ok", "alice"))

  it should "deny an ungranted table with the manager's own wording" in:
    assume(liveReady, "DuckDB 2.0 CLI or a qod-node build not available")
    val (rc, _, err) =
      cli(s"SELECT * FROM quack_query('$endpoint', 'SELECT * FROM secret', token := '$Token');")
    rc should not be 0
    err should include("access denied")
    err should include("lacks grants on acme_db.tpch1.secret:Read")
    router.history
      .snapshot(20)
      .exists(r => r.status == "denied" && r.sql.contains("secret")) shouldBe true

  it should "refuse a schema change batched ahead of a bare table name" in:
    assume(liveReady, "DuckDB 2.0 CLI or a qod-node build not available")
    // alice reads acme_db.tpch1.ids (60000 rows); acme_db.other.ids (7 rows) is not hers. Each
    // batch runs after the router's `USE acme_db.tpch1` prelude, so before the fix the node read
    // the 7-row table while the ACL had checked the granted one.
    List(
      "SET search_path=''acme_db.other''; SELECT count(*) FROM ids",
      "SET schema=''other''; SELECT count(*) FROM ids",
      "USE acme_db.other; SELECT count(*) FROM ids"
    ).foreach { inner =>
      val (rc, out, err) =
        cli(s"SELECT * FROM quack_query('$endpoint', '$inner', token := '$Token');")
      withClue(s"$inner -> rc=$rc out=$out err=$err") {
        out should not be "7"
        rc should not be 0
        err should include("own statement")
      }
    }
    // Sent on its own, the same SET is still accepted (and dies with the statement's connection).
    val (rc, _, err) = cli(
      s"SELECT * FROM quack_query('$endpoint', 'SET search_path=''acme_db.other''', token := '$Token');"
    )
    withClue(err)(rc shouldBe 0)

  it should "refuse a SET VARIABLE that reads an ungranted table" in:
    assume(liveReady, "DuckDB 2.0 CLI or a qod-node build not available")
    val inner =
      "SET VARIABLE v = (SELECT 41 + max(x) FROM acme_db.tpch1.secret); SELECT getvariable(''v'')"
    val (rc, out, err) =
      cli(s"SELECT * FROM quack_query('$endpoint', '$inner', token := '$Token');")
    withClue(s"rc=$rc out=$out err=$err") {
      out should not be "42"
      rc should not be 0
      err should include("access denied")
    }

  it should "refuse a malformed token at connect time" in:
    assume(liveReady, "DuckDB 2.0 CLI or a qod-node build not available")
    val (rc, _, err) = cli(s"SELECT * FROM quack_query('$endpoint', 'SELECT 1', token := 'nope');")
    rc should not be 0
    err should include("Authentication failed")

  "ATTACH through the front door" should "bootstrap the catalog and push a filtered scan down" in:
    assume(liveReady, "DuckDB 2.0 CLI or a qod-node build not available")
    val (rc, out, err) =
      cli(s"$attach SELECT count(*) FROM q.tpch1.customer WHERE c_custkey > 100;")
    withClue(err)(rc shouldBe 0)
    out shouldBe "1899"

  it should "mask a column-policy column on a scan pushed through the attached catalog" in:
    assume(liveReady, "DuckDB 2.0 CLI or a qod-node build not available")
    val (rc, out, err) =
      cli(s"$attach SELECT c_custkey, c_name FROM q.tpch1.customer WHERE c_custkey = 7;")
    withClue(err)(rc shouldBe 0)
    out shouldBe "7,***"

  it should "sync only the tables the principal is granted (issue #114)" in:
    assume(liveReady, "DuckDB 2.0 CLI or a qod-node build not available")
    // The manager narrowed the attach-time sync to alice's grants, so `secret` was never
    // described to her session: the granted tables resolve on the client, the ungranted one is
    // a client-side catalog miss, not a manager denial. (The quack extension does not enumerate
    // its catalog through duckdb_tables() on the client, so visibility is asserted by lookup.)
    val (rc, out, err) = cli(
      s"$attach SELECT column_name FROM (DESCRIBE q.tpch1.customer) ORDER BY 1; " +
        "SELECT count(*) FROM q.tpch1.ids;"
    )
    withClue(err)(rc shouldBe 0)
    out.split("\n").toList shouldBe List("c_custkey", "c_name", "60000")
    val (rc2, _, err2) = cli(s"$attach SELECT * FROM q.tpch1.secret;")
    rc2 should not be 0
    err2 should include("does not exist")
    router.history
      .snapshot(50)
      .exists(r => r.status == "ok" && r.sql.contains("duckdb_tables()")) shouldBe true
    router.history
      .snapshot(50)
      .exists(r => r.status == "denied" && r.sql.contains("duckdb_tables()")) shouldBe false

  it should "stream a large single-column result through the fetch loop" in:
    assume(liveReady, "DuckDB 2.0 CLI or a qod-node build not available")
    val (rc, out, err) = cli(s"$attach SELECT count(*), max(id) FROM q.tpch1.ids;")
    withClue(err)(rc shouldBe 0)
    out shouldBe "60000,59999"

  it should "join the attached catalog with a local table" in:
    assume(liveReady, "DuckDB 2.0 CLI or a qod-node build not available")
    val (rc, out, err) = cli(
      s"$attach CREATE TABLE vip AS SELECT range AS c_custkey FROM range(5); " +
        "SELECT count(*) FROM q.tpch1.customer c JOIN vip USING (c_custkey);"
    )
    withClue(err)(rc shouldBe 0)
    out shouldBe "5"

  it should "authorize a client-fed INSERT as a write on the named table" in:
    assume(liveReady, "DuckDB 2.0 CLI or a qod-node build not available")
    val (rc, out, err) = cli(
      s"$attach CREATE TABLE local_x AS SELECT 100000 + range AS c_custkey, 'n' || range AS c_name FROM range(3); " +
        "INSERT INTO q.tpch1.customer SELECT * FROM local_x; SELECT count(*) FROM q.tpch1.customer;"
    )
    withClue(err)(rc shouldBe 0)
    out shouldBe "2003"
    // the 2.0 client ships the INSERT as a scan of the rows it then sends on the same connection
    router.history
      .snapshot(50)
      .exists(r =>
        r.status == "ok" && r.sql.contains("scan_data_from_quack_client") &&
          r.sql.contains("customer")
      ) shouldBe true
    // `ids` is visible (RO) but not writable: the INSERT reaches the manager and is denied there.
    // `secret` would fail earlier, on the client, since it was never synced (see the case above).
    val (rc2, _, err2) = cli(s"$attach INSERT INTO q.tpch1.ids SELECT 1;")
    rc2 should not be 0
    err2 should include("access denied")
    err2 should include("acme_db.tpch1.ids:Write")

  // DuckDB 2.0 aborts a transaction whose statement failed and then refuses every statement but
  // ROLLBACK, the manager's session-handle CALL included, so the front door fences the session
  // (`txAborted`) and serves the client's ROLLBACK on a fresh link. A real client wraps its work on
  // the attached catalog in a remote transaction, and what it sends the node after the failure is
  // ROLLBACK (observed also when the script COMMITs the aborted transaction).
  it should "survive a statement that fails inside a client transaction" in:
    assume(liveReady, "DuckDB 2.0 CLI or a qod-node build not available")
    val (_, out, err) = cliScript(
      s"""$attach
         |BEGIN;
         |SELECT count(*) FROM q.tpch1.ids;
         |SELECT * FROM quack_query_by_name('q', 'SELECT error(''boom'')');
         |ROLLBACK;
         |SELECT count(*) FROM q.tpch1.ids;
         |""".stripMargin
    )
    // The failure is the only error the client sees: its ROLLBACK is answered, and the statement
    // after it runs in a new transaction.
    err should include("boom")
    err should not include "aborted"
    out.split("\n").toList shouldBe List("60000", "60000")
    val seen = router.history.snapshot(256).reverse
    val boom = seen.lastIndexWhere(r => r.sql == "SELECT error('boom')" && r.status == "permanent")
    boom should be >= 0
    // What only the fenced path leaves behind, so the case cannot pass without exercising it: the
    // node refuses the handle CALL in the aborted transaction (DuckDB's own words, booked against
    // the client's ROLLBACK), then the ROLLBACK is served on a fresh link.
    val rollbacks = seen.drop(boom + 1).filter(_.sql == "ROLLBACK")
    rollbacks.map(_.status).take(2) shouldBe List("permanent", "ok")
    rollbacks.head.error.getOrElse("") should include("(please ROLLBACK)")
    // The client's failure is no failure of the node: it is still routable, and a new session is
    // served by it.
    val snap = router.supervisor.snapshot(poolKey).getOrElse(fail("pool missing"))
    snap.nodes.map(n => snap.loadOf(n.nodeId).routable) shouldBe List(true)
    val (rc, count, err2) = cli(
      s"SELECT * FROM quack_query('$endpoint', 'SELECT count(*) FROM ids', token := '$Token');"
    )
    withClue(err2)(rc shouldBe 0)
    count shouldBe "60000"

  /** Run a Python script under a uv-provisioned `duckdb` package; returns (exit code, stdout,
    * stderr).
    */
  private def python(duckdbVersion: String, script: String): (Int, String, String) =
    val out = new StringBuilder
    val err = new StringBuilder
    val rc  = Process(
      Seq("uv", "run", "--no-project", "--with", s"duckdb==$duckdbVersion", "python", "-c", script)
    ).!(ProcessLogger(l => out.append(l).append('\n'), l => err.append(l).append('\n')))
    (rc, out.toString.trim, err.toString.trim)

  "the Python client" should "connect through a uv-provisioned DuckDB 2.0 pre-release" in:
    assume(liveReady, "DuckDB 2.0 CLI or a qod-node build not available")
    assume(uvPresent, "uv not on PATH")
    val (rc, out, err) = python(
      Python20,
      s"""import duckdb
         |c = duckdb.connect()
         |c.execute('INSTALL quack; LOAD quack')
         |print(c.execute("SELECT * FROM quack_query('$endpoint', 'SELECT count(*) FROM customer', token := '$Token')").fetchall()[0][0])
         |c.execute("ATTACH '$endpoint' AS q (TYPE quack, TOKEN '$Token')")
         |print(c.execute('SELECT count(*) FROM q.tpch1.ids').fetchall()[0][0])
         |""".stripMargin
    )
    withClue(err)(rc shouldBe 0)
    out.split("\n").toList shouldBe List("2003", "60000")

  it should "refuse a DuckDB 1.5 client by its protocol version" in:
    assume(liveReady, "DuckDB 2.0 CLI or a qod-node build not available")
    assume(uvPresent, "uv not on PATH")
    val (rc, _, err) = python(
      Python15,
      s"""import duckdb
         |c = duckdb.connect()
         |c.execute('INSTALL quack; LOAD quack')
         |c.execute("SELECT * FROM quack_query('$endpoint', 'SELECT 1', token := '$Token')").fetchall()
         |""".stripMargin
    )
    rc should not be 0
    err should include("requires a DuckDB 2.0 client")

/** The Python `duckdb` packages the spec provisions. PyPI publishes no 2.0 release yet: the
  * pre-release's bundled quack extension (974927a394) is the commit the nodes run, so it speaks the
  * same protocol 3. Bump it with the engine.
  */
private val Python20 = "2.0.0.dev2610011535"
private val Python15 = "1.5.4"
