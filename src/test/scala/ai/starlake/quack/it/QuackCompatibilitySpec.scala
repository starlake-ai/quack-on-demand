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
import ai.starlake.quack.ondemand.runtime.QuackBackend
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

import java.net.{HttpURLConnection, ServerSocket, URI}
import java.nio.file.{Files, Path}
import java.time.Instant
import scala.collection.concurrent.TrieMap
import scala.sys.process.{Process, ProcessLogger}

/** Epic 5, task 4: the real DuckDB CLI (and, when `uv` is present, the Python package) against the
  * front door, which relays to a real `quack_serve` node laid out like a QoD node (attached
  * catalog, non-default schema, USE'd on the init connection only). Cancelled when `duckdb` is not
  * on PATH, like the other real-node specs.
  *
  * The front door runs the REAL ACL gate: `PostgresAclValidator` with the metadata filter mounted,
  * and alice holds narrow grants (RW on `customer`, RO on `ids`, nothing on `secret`) PLUS a column
  * mask on `customer.c_name` and a row policy on `secret`, so the CLS and RLS rewriters run on
  * every statement too. A stand-in validator that admitted everything used to hide that the
  * client's attach-time catalog sync (`duckdb_tables() UNION ALL duckdb_views()`) was denied for
  * every ordinary principal (issue #114), and a policy-free principal then hid that the CLS
  * rewriter denied the same sync for anyone holding a column policy (the issue's second report).
  *
  * Result sizes stay inside the upstream client's working envelope (spec section 2.4): the
  * generation 1 client fails on multi-column results larger than its inline batch, so the fetch
  * loop is exercised on a single column.
  */
class QuackCompatibilitySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val duckdbPresent: Boolean = Process("which duckdb").!(ProcessLogger(_ => ())) == 0
  private val uvPresent: Boolean     = Process("which uv").!(ProcessLogger(_ => ())) == 0

  private def freePort(): Int =
    val s = new ServerSocket(0)
    try s.getLocalPort
    finally s.close()

  private val nodePort  = freePort()
  private val doorPort  = freePort()
  private val nodeToken = "nodetok1"
  private val poolKey   = PoolKey("acme", "acme_db", "sales")
  private val Token     = "tenant=acme&pool=sales&user=alice&password=x"

  private var dir: Path                    = null
  private var node: Process                = null
  private var server: QuackFrontDoorServer = null
  private var router: FlightSqlRouter      = null

  override def beforeAll(): Unit =
    if duckdbPresent then
      dir = Files.createTempDirectory("qod-quack-compat")
      val init =
        s"""INSTALL quack; LOAD quack;
           |ATTACH '$dir/acme.duckdb' AS acme_db;
           |CREATE SCHEMA acme_db.tpch1;
           |CREATE TABLE acme_db.tpch1.customer AS SELECT range AS c_custkey, 'name' || range AS c_name FROM range(2000);
           |CREATE TABLE acme_db.tpch1.secret AS SELECT 1 AS x;
           |CREATE TABLE acme_db.tpch1.ids AS SELECT range AS id FROM range(60000);
           |USE acme_db.tpch1;
           |CALL quack_serve('quack:127.0.0.1:$nodePort', token := '$nodeToken');
           |.shell sleep 600
           |""".stripMargin
      Files.writeString(dir.resolve("init.sql"), init)
      node = Process(Seq("duckdb", "-init", dir.resolve("init.sql").toString, "-batch"), dir.toFile)
        .run(ProcessLogger(_ => (), _ => ()))
      waitForNode()
      startFrontDoor()

  private def waitForNode(): Unit =
    val deadline = System.currentTimeMillis() + 30000
    var up       = false
    while !up && System.currentTimeMillis() < deadline do
      up =
        try
          val c = URI
            .create(s"http://127.0.0.1:$nodePort/")
            .toURL
            .openConnection()
            .asInstanceOf[HttpURLConnection]
          c.setConnectTimeout(300); c.setReadTimeout(300)
          c.getResponseCode; c.disconnect(); true
        catch { case _: Throwable => Thread.sleep(300); false }
    up shouldBe true

  private def startFrontDoor(): Unit =
    val backend = new QuackBackend:
      private val n          = TrieMap.empty[String, RunningNode]
      def start(s: NodeSpec) = IO {
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
    val client = new QuackHttpClient(new org.apache.arrow.memory.RootAllocator(), true, true)
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
      new QuackHttpAdapter(client, tracker),
      validator = validator,
      columnPolicyRewriter = new ColumnPolicyRewriter(columns),
      metadataFilterRewriter = new MetadataFilterRewriter(enabled = true)
    )
    val user   = RbacUser("u-1", Some("t-1"), "alice", role = "user")
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
      authorize = (_, _, _, _, _, _) => Right(AuthorizedHandshake(poolKey, "t-1", "p-1", user, eff))
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
    if server != null then server.stop()
    if node != null then node.destroy()
    if dir != null then
      Thread.sleep(200)
      try Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.delete(p))
      catch case e: Exception => System.err.println(s"[QuackCompatibilitySpec] cleanup: $e")

  /** Run SQL through the real DuckDB CLI; returns (exit code, stdout, stderr). */
  private def cli(sql: String): (Int, String, String) =
    val out = new StringBuilder
    val err = new StringBuilder
    val rc  = Process(Seq("duckdb", "-csv", "-noheader", "-c", sql))
      .!(ProcessLogger(l => out.append(l).append('\n'), l => err.append(l).append('\n')))
    (rc, out.toString.trim, err.toString.trim)

  private def endpoint = s"quack:127.0.0.1:$doorPort"
  private def attach   = s"ATTACH '$endpoint' AS q (TYPE quack, TOKEN '$Token');"

  "quack_query through the front door" should "route a granted statement and record it" in:
    assume(duckdbPresent, "duckdb CLI not on PATH")
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
    assume(duckdbPresent, "duckdb CLI not on PATH")
    val (rc, _, err) =
      cli(s"SELECT * FROM quack_query('$endpoint', 'SELECT * FROM secret', token := '$Token');")
    rc should not be 0
    err should include("access denied")
    err should include("lacks grants on acme_db.tpch1.secret:Read")
    router.history
      .snapshot(20)
      .exists(r => r.status == "denied" && r.sql.contains("secret")) shouldBe true

  it should "refuse a malformed token at connect time" in:
    assume(duckdbPresent, "duckdb CLI not on PATH")
    val (rc, _, err) = cli(s"SELECT * FROM quack_query('$endpoint', 'SELECT 1', token := 'nope');")
    rc should not be 0
    err should include("Authentication failed")

  "ATTACH through the front door" should "bootstrap the catalog and push a filtered scan down" in:
    assume(duckdbPresent, "duckdb CLI not on PATH")
    val (rc, out, err) =
      cli(s"$attach SELECT count(*) FROM q.tpch1.customer WHERE c_custkey > 100;")
    withClue(err)(rc shouldBe 0)
    out shouldBe "1899"

  it should "mask a column-policy column on a scan pushed through the attached catalog" in:
    assume(duckdbPresent, "duckdb CLI not on PATH")
    val (rc, out, err) =
      cli(s"$attach SELECT c_custkey, c_name FROM q.tpch1.customer WHERE c_custkey = 7;")
    withClue(err)(rc shouldBe 0)
    out shouldBe "7,***"

  it should "sync only the tables the principal is granted (issue #114)" in:
    assume(duckdbPresent, "duckdb CLI not on PATH")
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
    assume(duckdbPresent, "duckdb CLI not on PATH")
    val (rc, out, err) = cli(s"$attach SELECT count(*), max(id) FROM q.tpch1.ids;")
    withClue(err)(rc shouldBe 0)
    out shouldBe "60000,59999"

  it should "join the attached catalog with a local table" in:
    assume(duckdbPresent, "duckdb CLI not on PATH")
    val (rc, out, err) = cli(
      s"$attach CREATE TABLE vip AS SELECT range AS c_custkey FROM range(5); " +
        "SELECT count(*) FROM q.tpch1.customer c JOIN vip USING (c_custkey);"
    )
    withClue(err)(rc shouldBe 0)
    out shouldBe "5"

  it should "authorize an INSERT (shipped as an append) as a write on the named table" in:
    assume(duckdbPresent, "duckdb CLI not on PATH")
    val (rc, out, err) = cli(
      s"$attach CREATE TABLE local_x AS SELECT 100000 + range AS c_custkey, 'n' || range AS c_name FROM range(3); " +
        "INSERT INTO q.tpch1.customer SELECT * FROM local_x; SELECT count(*) FROM q.tpch1.customer;"
    )
    withClue(err)(rc shouldBe 0)
    out shouldBe "2003"
    // `ids` is visible (RO) but not writable: the append reaches the manager and is denied there.
    // `secret` would fail earlier, on the client, since it was never synced (see the case above).
    val (rc2, _, err2) = cli(s"$attach INSERT INTO q.tpch1.ids SELECT 1;")
    rc2 should not be 0
    err2 should include("access denied")
    err2 should include("acme_db.tpch1.ids:Write")

  "the Python client" should "connect through uv-provisioned DuckDB 1.5.4" in:
    assume(duckdbPresent, "duckdb CLI not on PATH")
    assume(uvPresent, "uv not on PATH")
    val script =
      s"""import duckdb
         |c = duckdb.connect()
         |c.execute('INSTALL quack; LOAD quack')
         |print(c.execute("SELECT * FROM quack_query('$endpoint', 'SELECT count(*) FROM customer', token := '$Token')").fetchall()[0][0])
         |c.execute("ATTACH '$endpoint' AS q (TYPE quack, TOKEN '$Token')")
         |print(c.execute('SELECT count(*) FROM q.tpch1.ids').fetchall()[0][0])
         |""".stripMargin
    val out = new StringBuilder
    val err = new StringBuilder
    val rc  =
      Process(Seq("uv", "run", "--no-project", "--with", "duckdb==1.5.4", "python", "-c", script))
        .!(ProcessLogger(l => out.append(l).append('\n'), l => err.append(l).append('\n')))
    withClue(err)(rc shouldBe 0)
    out.toString.trim.split("\n").toList shouldBe List("2003", "60000")
