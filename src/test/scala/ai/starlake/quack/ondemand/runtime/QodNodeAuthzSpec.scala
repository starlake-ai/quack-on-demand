package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.edge.adapter.*
import ai.starlake.quack.edge.authz.{NodeHandles, NodeKeys, NodePrincipal}
import cats.effect.unsafe.IORuntime
import cats.effect.unsafe.implicits.global
import org.apache.arrow.memory.RootAllocator
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

/** A real qod-node, reached through the manager's own native client: a statement runs only after a
  * session handle for this node, signed with its key, unused, for the connection's one principal,
  * and every plan of an ordinary principal's statement runs only under a verdict of the decision
  * endpoint (here a stand-in that signs them as the manager does). Cancelled without a qod-node
  * build.
  */
class QodNodeAuthzSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val allocator         = new RootAllocator()
  private val client            = new QuackHttpClient(allocator)
  private var dir: Path         = null
  private var live: LiveQodNode = null
  private val Alice             =
    NodePrincipal("u-1", "alice", Some("acme"), superuser = false, system = false)

  // alice is an ordinary principal: the node decides her plans through the decision endpoint, here a
  // stand-in signing with the keys the node was given.
  private val secret = NodeKeys.randomSecret()
  private val decide = StubDecideServer.start(secret)
  private val keys   = new NodeKeys(secret, decide.url)

  override def beforeAll(): Unit =
    if QodNodeTestBinary.path.nonEmpty then
      dir = Files.createTempDirectory("qod-node-authz")
      // A loose file served the way `qod serve` serves it (serve_target._view): a secure view over its
      // reader, created by the node's own init SQL. And a DuckLake catalog, as a DuckLake pool's node
      // attaches it, with a secure view over one of its tables in the node's memory catalog (DuckLake
      // has no secure views of its own).
      val root   = dir.toRealPath()
      val served = root.resolve("served.parquet").toString
      live = LiveQodNode.start(
        dir,
        s"""CREATE TABLE answers AS SELECT 42 AS v;
           |COPY (SELECT 7 AS n) TO '$served' (FORMAT parquet);
           |CREATE OR REPLACE SECURE VIEW "served" AS SELECT * FROM system.main.read_parquet('$served');
           |INSTALL ducklake;
           |LOAD ducklake;
           |ATTACH 'ducklake:$root/lake.ducklake' AS lake (DATA_PATH '$root/lake_data/');
           |CREATE TABLE lake.main.t (id INTEGER, v VARCHAR);
           |CREATE SECURE VIEW memory.main.lake_sv AS SELECT id FROM lake.main.t;
           |""".stripMargin,
        keys = keys
      )

  override def afterAll(): Unit =
    try
      try if live != null then live.stop()
      finally decide.stop()
      allocator.close()
    finally
      // The node is gone (stop waits for it), so nothing holds the directory any more.
      if dir != null then
        val tree = Files.walk(dir)
        try tree.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.delete(p))
        catch
          case e: Exception =>
            System.err.println(s"[QodNodeAuthzSpec] afterAll: could not fully delete $dir: $e")
        finally tree.close()

  private def node: LiveQodNode =
    assume(live != null, "no qod-node binary (QOD_NODE_BIN or a local build)")
    live

  private def refused(resp: QuackResponse, needle: String): Unit =
    LiveQodNode.lines(resp) match
      case Left(m) => m should include(needle)
      case other   => fail(s"expected a refusal mentioning '$needle', got $other")

  /** A node connection of the manager's own native client, kept across statements. */
  private def withConnection[A](f: Connection => A): A =
    given IORuntime = IORuntime.global
    val protocol    = new QuackProtocol(
      new QuackProtocol.JdkHttpTransport(java.net.http.HttpClient.newHttpClient()),
      allocator
    )
    val conn = protocol.open(node.endpoint, node.token).unsafeRunSync()
    try f(conn)
    finally conn.close().unsafeRunSync()

  /** `sql` on `conn`: its first value (empty without a row), or the node's error. */
  private def first(conn: Connection, sql: String): Either[String, String] =
    given IORuntime = IORuntime.global
    try
      val reader = conn.execute(sql).unsafeRunSync()
      try
        val rows  = reader.getVectorSchemaRoot
        var value = Option.empty[String]
        while reader.loadNextBatch() do
          if value.isEmpty && rows.getRowCount > 0 then
            value = Some(String.valueOf(rows.getVector(0).getObject(0)))
        Right(value.getOrElse(""))
      finally reader.close(false)
    catch case e: QuackWireError => Left(e.getMessage)

  /** True when the node attached the DuckLake catalog its init SQL names (it needs the ducklake
    * extension installed on this host).
    */
  private lazy val lakeAttached: Boolean =
    node.query(
      client,
      "SELECT count(*) FROM duckdb_databases() WHERE database_name = 'lake'"
    ) == Right(List("1"))

  "a statement" should "be refused without a session handle" in
    refused(
      client.queryWithoutHandle(node.endpoint, node.token, "SELECT 1").unsafeRunSync(),
      NodeHandles.NoPrincipalMarker
    )

  it should "run after a session handle for this node" in {
    node.query(client, "SELECT v FROM answers", Alice) shouldBe Right(List("42"))
  }

  // A verdict is cached by the plan's description, which names no constant: each case below runs a
  // statement whose description no earlier case had (another function), so the endpoint is asked.
  it should "run under the verdict the decision endpoint signed for its plan" in {
    val before = decide.requests.size
    node.query(client, "SELECT max(v) FROM answers", Alice) shouldBe Right(List("42"))
    val plans = decide.requests.drop(before).filter(_._1 == "plan").map(_._2.noSpaces)
    plans.exists(p =>
      p.contains("\"name\":\"answers\"") && p.contains("\"access\":\"read\"")
    ) shouldBe true
  }

  it should "be refused with the endpoint's reason" in {
    decide.decide = (_, d) =>
      if d.noSpaces.contains("\"name\":\"answers\"") then Left("no grant on answers") else Right(())
    try
      refused(
        client
          .query(
            node.endpoint,
            node.token,
            "SELECT v + 1 FROM answers",
            None,
            node.authorize(Alice)
          )
          .unsafeRunSync(),
        "QoD: no grant on answers"
      )
    finally decide.decide = (_, _) => Right(())
  }

  it should "be refused, retryably, while the endpoint is unavailable" in {
    decide.status = 503
    try
      refused(
        client
          .query(
            node.endpoint,
            node.token,
            "SELECT v * 2 FROM answers",
            None,
            node.authorize(Alice)
          )
          .unsafeRunSync(),
        "QoD: authorization unavailable"
      )
    finally decide.status = 200
  }

  it should "run a superuser's plan without asking the endpoint" in {
    val before = decide.requests.size
    node.query(client, "SELECT v FROM answers") shouldBe Right(List("42"))
    decide.requests.size shouldBe before
  }

  "a handle" should "not install twice" in {
    val once = node.keys.prelude(node.node, Alice)
    LiveQodNode.lines(
      client.query(node.endpoint, node.token, "SELECT 1", None, () => once).unsafeRunSync()
    ) shouldBe Right(List("1"))
    refused(
      client.query(node.endpoint, node.token, "SELECT 1", None, () => once).unsafeRunSync(),
      "already used"
    )
  }

  it should "not verify on another node, nor under another key" in {
    // Every node has its own key, so a handle minted for another node fails at the MAC, before the
    // node named in its payload is compared with this one's.
    val elsewhere = node.keys.prelude(node.node.copy(nodeId = "quack-elsewhere-1"), Alice)
    refused(
      client.query(node.endpoint, node.token, "SELECT 1", None, () => elsewhere).unsafeRunSync(),
      "does not verify"
    )
    val forged = NodeKeys.ephemeral().prelude(node.node, Alice)
    refused(
      client.query(node.endpoint, node.token, "SELECT 1", None, () => forged).unsafeRunSync(),
      "does not verify"
    )
  }

  "a connection" should "keep the principal its first handle installed" in {
    given cats.effect.unsafe.IORuntime = cats.effect.unsafe.IORuntime.global
    withConnection { conn =>
      conn.executeDiscard(node.keys.prelude(node.node, Alice)).unsafeRunSync()
      val mallory = NodePrincipal("u-2", "mallory", Some("acme"), superuser = false, system = false)
      val e       = intercept[QuackWireError.Permanent](
        conn.executeDiscard(node.keys.prelude(node.node, mallory)).unsafeRunSync()
      )
      e.msg should include("another principal")
      // The refusal left alice installed: her connection still runs statements, with no handle of
      // its own and after a fresh one of hers.
      conn.executeDiscard("SELECT 1").unsafeRunSync()
      conn.executeDiscard(node.keys.prelude(node.node, Alice)).unsafeRunSync()
      conn.executeDiscard("SELECT 1").unsafeRunSync()
    }
  }

  it should "never serve two principals, nor ask a verdict for another one (T2-X1)" in
    withConnection { conn =>
      first(conn, node.keys.prelude(node.node, Alice)) shouldBe Right("true")
      val before  = decide.principals.size
      val mallory = NodePrincipal("u-2", "mallory", Some("acme"), superuser = false, system = false)
      first(conn, node.keys.prelude(node.node, mallory)).left.toOption.get should include(
        "another principal"
      )
      // What runs next on the connection runs as alice, and is decided for her alone.
      first(conn, "SELECT min(v) FROM answers") shouldBe Right("42")
      val asked = decide.principals.drop(before)
      asked should not be empty
      asked.distinct shouldBe List("u-1")
    }

  "the parse screen" should "not be replaceable from a session" in
    refused(
      client
        .query(
          node.endpoint,
          node.token,
          "SET GLOBAL quack_authorization_function = 'x'",
          None,
          node.authorize()
        )
        .unsafeRunSync(),
      "cannot be changed"
    )

  "the health probe" should "pass an enforcing node, as the system principal" in {
    new QuackHttpAdapter(client, new NodeLoadTracker, node.keys)
      .probe(node.node)
      .unsafeRunSync() shouldBe ProbeVerdict.Ok
  }

  it should "carry the node's refusal when the manager's keys are not the node's" in {
    // A manager whose node-key secret is not the one this node's key derives from.
    new QuackHttpAdapter(client, new NodeLoadTracker, NodeKeys.ephemeral())
      .probe(node.node)
      .unsafeRunSync() match
      case refused @ ProbeVerdict.HandleRefused(answer) =>
        answer should include(NodeHandles.KeyMismatchRefusal)
        new HandleRefusalWarnings().warning(node.nodeId, refused).get should include(
          "QOD_NODE_KEY_SECRET"
        )
      case other => fail(s"expected HandleRefused, got $other")
  }

  "a file served by qod serve" should "be read by an ordinary principal through its secure view" in {
    val before = decide.requests.size
    node.query(client, "SELECT n FROM served", Alice) shouldBe Right(List("7"))
    val plan      = decide.requests.drop(before).filter(_._1 == "plan").map(_._2).last
    val objects   = plan.hcursor.downField("objects").as[List[io.circe.Json]].toOption.get
    val functions = plan.hcursor.downField("functions").as[List[io.circe.Json]].toOption.get
    // The boundary is a read of the view; the reader inside it carries the view as via, so the
    // decider judges it with the view owner's rights (a read grant on memory.main.served).
    objects.exists(o =>
      o.hcursor.get[String]("kind").contains("secure_view") && o.hcursor
        .get[String]("name")
        .contains("served")
    ) shouldBe true
    functions.exists(f =>
      f.hcursor.get[String]("name").contains("read_parquet") &&
        f.hcursor.get[String]("via").contains("memory.main.served")
    ) shouldBe true
  }

  "a DuckLake catalog" should "take an ordinary principal's writes and reads, its metadata queries never decided as hers" in {
    assume(lakeAttached, "the ducklake extension is not installed on this host")
    val before = decide.requests.size
    // As the router sends them: its USE prelude, then the statement, in one submission.
    node.query(client, "USE lake.main; INSERT INTO t VALUES (1, 'a'), (2, 'b')", Alice) shouldBe
      Right(List("2"))
    // Enough rows that DuckLake writes a data file instead of inlining them.
    node.query(
      client,
      "USE lake.main; INSERT INTO t SELECT i, 'x' FROM range(3, 1003) r(i)",
      Alice
    ) shouldBe
      Right(List("1000"))
    // A flush of the inlined rows is a superuser's (the function is classed superuser): the manager's
    // maintenance runs it. Its own metadata queries pass on the node either way.
    node.query(client, "CALL ducklake_flush_inlined_data('lake')").isRight shouldBe true
    node.query(client, "USE lake.main; SELECT count(*), min(v) FROM t", Alice) shouldBe
      Right(List("1002,a"))
    val asked = decide.requests.drop(before)
    asked.exists((_, d) => d.noSpaces.contains("\"name\":\"t\"")) shouldBe true
    asked.filter((_, d) => d.noSpaces.contains("__ducklake_metadata")) shouldBe empty
    decide.principals.drop(before).distinct shouldBe List("u-1")
  }

  "a secure view" should "be read under the path the handle carries, never one a statement set" in {
    assume(lakeAttached, "the ducklake extension is not installed on this host")
    // The keys of a pool whose USE prelude targets lake.main: every handle carries that path.
    val onLake = new NodeKeys(secret, decide.url, paths = _ => "lake.main")
    withConnection { conn =>
      def as(sql: String): Either[String, String] =
        first(conn, onLake.prelude(node.node, Alice)) shouldBe Right("true")
        first(conn, sql)
      // Her own leading USE, first on the connection, is not the path the handle names.
      as("USE memory.main; SELECT count(*) FROM memory.main.lake_sv").left.toOption.get should
        include("search path differs")
      // The manager's USE prelude sets the path the handle names.
      as("USE lake.main; SELECT count(*) FROM memory.main.lake_sv").isRight shouldBe true
      as(
        "USE lake.main; USE memory.main; SELECT 1 FROM memory.main.lake_sv"
      ).left.toOption.get should
        include("search path differs")
      as("USE lake.main; SELECT 1 FROM memory.main.lake_sv LIMIT 1").isRight shouldBe true
    }
  }
