package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.edge.adapter.*
import ai.starlake.quack.edge.authz.{NodeKeys, NodePrincipal, ReadOnlyCatalogs}
import cats.effect.unsafe.IORuntime
import cats.effect.unsafe.implicits.global
import org.apache.arrow.memory.RootAllocator
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

/** Read-only federated catalogs on a real qod-node (decision 24), reached through the manager's own
  * native client and handle mint: the read-only set travels in the session handle, and the node
  * refuses every principal's write, DDL, ATTACH or DETACH into a catalog it names, superusers
  * included, before any verdict is reused or asked for. The catalog is a local DuckDB file attached
  * READ_WRITE, as a `sql` federated source attaches one: no engine layer protects it, only the
  * handle's set. Cancelled without a qod-node build.
  */
class QodNodeReadOnlySpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val allocator         = new RootAllocator()
  private val client            = new QuackHttpClient(allocator)
  private var dir: Path         = null
  private var root: Path        = null
  private var live: LiveQodNode = null
  private val Alice             =
    NodePrincipal("u-1", "alice", Some("acme"), superuser = false, system = false)
  private val Root = LiveQodNode.Superuser

  // One node key, the views of two pools: one where the `rosql` source is read-only, one where
  // nothing is. The node holds no set of its own, only the one each handle carries.
  private val secret      = NodeKeys.randomSecret()
  private val decide      = StubDecideServer.start(secret)
  private val readOnly    = new NodeKeys(secret, decide.url, readOnlyCatalogs = _ => Set("rosql"))
  private val allWritable =
    new NodeKeys(secret, decide.url, readOnlyCatalogs = ReadOnlyCatalogs.none)

  private val ReadOnly = "QoD: catalog rosql is read-only"

  override def beforeAll(): Unit =
    if QodNodeTestBinary.path.nonEmpty then
      dir = Files.createTempDirectory("qod-node-read-only")
      root = dir.toRealPath()
      live = LiveQodNode.start(
        dir,
        s"""ATTACH '$root/ro_src.duckdb' AS rosql;
           |CREATE TABLE rosql.main.t AS SELECT 1 AS v;
           |ATTACH '$root/rw_src.duckdb' AS rwsql;
           |CREATE TABLE rwsql.main.t AS SELECT 1 AS v;
           |""".stripMargin,
        keys = readOnly
      )

  override def afterAll(): Unit =
    try
      try if live != null then live.stop()
      finally decide.stop()
      allocator.close()
    finally
      if dir != null then
        val tree = Files.walk(dir)
        try tree.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.delete(p))
        catch
          case e: Exception =>
            System.err.println(s"[QodNodeReadOnlySpec] afterAll: could not fully delete $dir: $e")
        finally tree.close()

  private def node: LiveQodNode =
    assume(live != null, "no qod-node binary (QOD_NODE_BIN or a local build)")
    live

  /** `sql` as `principal`, after a handle `keys` mint: the rows as lines, or the node's error. */
  private def as(
      keys: NodeKeys,
      sql: String,
      principal: NodePrincipal = Root
  ): Either[String, List[String]] =
    LiveQodNode.lines(
      client
        .query(node.endpoint, node.token, sql, None, () => keys.prelude(node.node, principal))
        .unsafeRunSync()
    )

  private def refused(out: Either[String, List[String]], needle: String = ReadOnly): Unit =
    out match
      case Left(m)  => m should include(needle)
      case Right(r) => fail(s"expected a refusal mentioning '$needle', got rows $r")

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

  "a read-only catalog" should "refuse a superuser's writes and DDL, and keep its rows" in {
    List(
      "INSERT INTO rosql.main.t VALUES (2)",
      "INSERT INTO rosql.main.t SELECT v FROM rwsql.main.t",
      "UPDATE rosql.main.t SET v = 3",
      "DELETE FROM rosql.main.t",
      "CREATE TABLE rosql.main.x (v INTEGER)",
      "DROP TABLE rosql.main.t",
      "ALTER TABLE rosql.main.t ADD COLUMN w INTEGER",
      "CREATE SCHEMA rosql.s"
    ).foreach(sql => withClue(s"$sql -> ")(refused(as(readOnly, sql))))
    as(allWritable, "SELECT count(*), min(v) FROM rosql.main.t") shouldBe Right(List("1,1"))
  }

  it should "refuse a superuser's DETACH of it, and an ATTACH that would replace it" in {
    List(
      "DETACH rosql",
      "ATTACH OR REPLACE ':memory:' AS rosql",
      // No AS: DuckDB names the catalog after the file, rosql again.
      s"ATTACH OR REPLACE '$root/rosql.duckdb'"
    ).foreach(sql => withClue(s"$sql -> ")(refused(as(readOnly, sql))))
    as(
      allWritable,
      "SELECT count(*) FROM duckdb_databases() WHERE database_name = 'rosql'"
    ) shouldBe
      Right(List("1"))
    as(allWritable, "SELECT count(*) FROM rosql.main.t") shouldBe Right(List("1"))
  }

  it should "be read by a superuser and by an ordinary principal" in {
    as(readOnly, "SELECT v FROM rosql.main.t") shouldBe Right(List("1"))
    as(readOnly, "SELECT v FROM rosql.main.t", Alice) shouldBe Right(List("1"))
  }

  it should "refuse an ordinary principal's write without asking for a verdict" in {
    val before = decide.requests.size
    refused(as(readOnly, "INSERT INTO rosql.main.t VALUES (2)", Alice))
    decide.requests.size shouldBe before
  }

  "a writable catalog on the same node" should "take writes, one reading the read-only catalog included" in {
    as(readOnly, "INSERT INTO rwsql.main.t VALUES (2)") shouldBe Right(List("1"))
    as(readOnly, "INSERT INTO rwsql.main.t SELECT v FROM rosql.main.t") shouldBe Right(List("1"))
    as(readOnly, "INSERT INTO rwsql.main.t VALUES (3)", Alice) shouldBe Right(List("1"))
    as(readOnly, "CREATE TABLE rwsql.main.copy AS SELECT v FROM rosql.main.t").isRight shouldBe true
  }

  "a verdict the node cached" should "not admit a write into a catalog the next handle makes read-only" in {
    val sql = "INSERT INTO rosql.main.t VALUES (5)"
    as(allWritable, sql, Alice) shouldBe Right(List("1"))
    val before = decide.requests.size
    refused(as(readOnly, sql, Alice))
    decide.requests.size shouldBe before
    // The verdict is still cached: only the read-only check stopped it.
    as(allWritable, sql, Alice) shouldBe Right(List("1"))
    decide.requests.size shouldBe before
    as(allWritable, "DELETE FROM rosql.main.t WHERE v = 5") shouldBe Right(List("2"))
  }

  "a prepared write" should "be refused once its catalog is read-only, a superuser's included" in {
    withConnection { conn =>
      first(conn, allWritable.prelude(node.node, Root)) shouldBe Right("true")
      first(conn, "PREPARE p AS INSERT INTO rosql.main.t VALUES (7)").isRight shouldBe true
      first(conn, "EXECUTE p").isRight shouldBe true
      first(conn, readOnly.prelude(node.node, Root)) shouldBe Right("true")
      first(conn, "EXECUTE p").left.toOption.get should include(ReadOnly)
      first(conn, "PREPARE q AS INSERT INTO rosql.main.t VALUES (8)").left.toOption.get should
        include(ReadOnly)
    }
    as(allWritable, "DELETE FROM rosql.main.t WHERE v = 7") shouldBe Right(List("1"))
  }

  "the manager's own statements" should "carry no read-only catalog" in {
    as(readOnly, "INSERT INTO rosql.main.t VALUES (8)", NodePrincipal.System) shouldBe Right(
      List("1")
    )
    as(allWritable, "DELETE FROM rosql.main.t WHERE v = 8") shouldBe Right(List("1"))
  }

  "the health probe" should "pass an enforcing node with keys that carry a read-only catalog" in {
    new QuackHttpAdapter(client, new NodeLoadTracker, readOnly)
      .probe(node.node)
      .unsafeRunSync() shouldBe ProbeVerdict.Ok
  }
