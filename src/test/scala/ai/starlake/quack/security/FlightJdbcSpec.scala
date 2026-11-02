package ai.starlake.quack.security

import ai.starlake.quack.edge.adapter.{QuackResponse, TestArrow}
import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import ai.starlake.quack.ondemand.InProcessDuckDb
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowReader
import org.duckdb.DuckDBConnection
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.{Connection, DriverManager, PreparedStatement, ResultSet, SQLException, Statement}
import java.util.Properties
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.jdk.CollectionConverters._

/** Issue 170 through the real Arrow Flight SQL JDBC driver. The driver sends a statement advertised
  * with no schema through DoPut, and every prepared `executeUpdate` too, then reads the acked count
  * as Avatica does: -1 means "no count, open a result set". That result set failed, so Avatica
  * retried the whole statement, DoPut and DoGet each time, and the caller saw "Failed to
  * successfully execute query after 5 attempts" for a statement that had run ten times. A prepared
  * statement hit a null signature instead. The count acked for a result with no numeric first cell
  * must therefore never be -1.
  *
  * The node executes against ONE DuckDB database shared by every call, so a second run of a write
  * shows as a second row.
  */
class FlightJdbcSpec extends AnyFlatSpec with Matchers:

  /** What a real node streams: `Success` for these, `Count` for a plain write, else the rows. */
  private val SuccessVerbs = Set("USE", "SET", "RESET", "VACUUM", "ANALYZE", "DROP")

  /** Mirrors `adapter.StampedCommitArrowReader`: the COMMIT runs when the stream is exhausted, and
    * a failure propagates to whoever is reading.
    */
  private final class CommitOnExhaustion(underlying: ArrowReader, commit: () => Unit)
      extends ArrowReader(TestArrow.sharedAllocator):
    private val attempted                 = new AtomicBoolean(false)
    override def loadNextBatch(): Boolean =
      val more = underlying.loadNextBatch()
      if !more && attempted.compareAndSet(false, true) then commit()
      more
    override def getVectorSchemaRoot(): VectorSchemaRoot = underlying.getVectorSchemaRoot
    override def getDictionaryVectors()                  = underlying.getDictionaryVectors
    override def bytesRead(): Long                       = underlying.bytesRead()
    override def close(closeReadSource: Boolean): Unit   = underlying.close(closeReadSource)
    override protected def readSchema()                  = underlying.getVectorSchemaRoot.getSchema
    override protected def closeReadSource(): Unit       = ()

  private final class Edge(
      val client: Connection,
      base: Connection,
      sent: java.util.Queue[String],
      commitOfWrites: AtomicReference[() => Unit]
  ):
    /** What the COMMIT of each later plain write (`Count` answer) does. */
    def onCommit(f: () => Unit): Unit = commitOfWrites.set(f)

    /** The statements the node received since the last call, then forgets them. */
    def drain(): List[String] =
      val all = sent.asScala.toList
      sent.clear()
      all

    def single(sql: String): Long =
      val rs = base.createStatement().executeQuery(sql)
      rs.next()
      rs.getLong(1)

  private def withEdge(body: Edge => Unit): Unit =
    val base = DriverManager
      .getConnection("jdbc:duckdb:", InProcessDuckDb.properties)
      .asInstanceOf[DuckDBConnection]
    val received = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val commit   = new AtomicReference[() => Unit](() => ())
    try
      base.createStatement().execute(OpaEdgeFixtures.OrdersSetup)
      val node: String => QuackResponse = sql =>
        received.add(sql)
        val conn = base.duplicate()
        try
          val stmt  = conn.createStatement()
          val parts = sql.split(";").map(_.trim).filter(_.nonEmpty).toList
          parts.init.foreach(stmt.execute)
          val last   = parts.last
          val verb   = last.takeWhile(!_.isWhitespace).toUpperCase(java.util.Locale.ROOT)
          val reader =
            if SuccessVerbs(verb) then
              stmt.execute(last)
              TestArrow.fromResultSet(conn, stmt.executeQuery("SELECT true AS \"Success\""))
            else if stmt.execute(last) then TestArrow.fromResultSet(conn, stmt.getResultSet)
            else
              val count = stmt.getUpdateCount
              new CommitOnExhaustion(
                TestArrow.fromResultSet(
                  conn,
                  stmt.executeQuery(s"SELECT CAST($count AS BIGINT) AS \"Count\"")
                ),
                commit.get()
              )
          QuackResponse.Ok(reader, 1L, () => ())
        catch
          case e: Throwable =>
            conn.close()
            throw e
      val h = FlightEdgeHarness.boot(
        SecurityFixtures.freshStore().store,
        wiring = FlightEdgeHarness.RouterWiring(
          nodeQuery = node,
          spawnNodes = true,
          metadataFilterRewriter = new MetadataFilterRewriter(enabled = true),
          protectedWriteGuard = ai.starlake.quack.edge.policy.ProtectedWriteGuard.disabled
        )
      )
      try
        Class.forName("org.apache.arrow.driver.jdbc.ArrowFlightJdbcDriver")
        val props = new Properties()
        props.setProperty("user", SecurityFixtures.AliceUsername)
        props.setProperty("password", SecurityFixtures.AlicePassword)
        val url = s"jdbc:arrow-flight-sql://${h.host}:${h.port}/?useEncryption=false" +
          s"&tenant=${SecurityFixtures.TenantId}&pool=${SecurityFixtures.PoolName}"
        val client = DriverManager.getConnection(url, props)
        try body(new Edge(client, base, received, commit))
        finally client.close()
      finally h.shutdown()
    finally base.close()

  private def statement[A](c: Connection)(f: Statement => A): A =
    val s = c.createStatement()
    try f(s)
    finally s.close()

  private def prepared[A](c: Connection, sql: String)(f: PreparedStatement => A): A =
    val s = c.prepareStatement(sql)
    try f(s)
    finally s.close()

  private def rows(rs: ResultSet): List[String] =
    val out = List.newBuilder[String]
    while rs.next() do out += String.valueOf(rs.getObject(1))
    out.result()

  /** The four ways a JDBC client runs a statement it expects no rows from, each with what it read:
    * the update count, and whether `execute` reported a result set.
    */
  private def noRowPaths(c: Connection, sql: String): List[(String, () => Any, Any)] = List(
    ("Statement.executeUpdate", () => statement(c)(_.executeUpdate(sql)), 0),
    ("Statement.execute", () => statement(c)(s => (s.execute(sql), s.getUpdateCount)), (false, 0)),
    ("PreparedStatement.executeUpdate", () => prepared(c, sql)(_.executeUpdate()), 0),
    (
      "PreparedStatement.execute",
      () => prepared(c, sql)(s => (s.execute(), s.getUpdateCount)),
      (false, 0)
    )
  )

  "a lone USE or SET" should "succeed once on every JDBC path" in withEdge { edge =>
    for
      sql                   <- List("USE main", "SET threads=2", "SET VARIABLE v=1", "VACUUM")
      (path, run, expected) <- noRowPaths(edge.client, sql)
    do
      withClue(s"$path [$sql]: ") {
        edge.drain()
        run() shouldBe expected
        edge.drain().count(_.contains(sql.take(3))) shouldBe 1
      }
  }

  it should "refuse executeQuery plainly, not after five attempts" in withEdge { edge =>
    val e = intercept[SQLException](statement(edge.client)(_.executeQuery("USE main")))
    e.getMessage should include("did not return a result set")
    edge.drain().count(_.startsWith("USE")) shouldBe 1
  }

  it should "still fail on a schema that does not exist" in withEdge { edge =>
    val e = intercept[SQLException](statement(edge.client)(_.executeUpdate("USE no_such_schema")))
    e.getMessage should not include "attempts"
    e.getMessage should include("no_such_schema")
  }

  // The same -1 reached every unprobed statement whose result does not start with a number. A write
  // among them landed ten times on Statement.executeUpdate, and the client still saw a failure.
  "a write answering with text" should "land exactly once" in withEdge { edge =>
    val before = edge.single("SELECT count(*) FROM orders")
    val sql    = "INSERT INTO orders VALUES (11, 'b@x.io') RETURNING email"
    statement(edge.client)(_.executeUpdate(sql)) shouldBe 0
    edge.single("SELECT count(*) FROM orders") shouldBe before + 1
    prepared(edge.client, sql)(_.executeUpdate()) shouldBe 0
    edge.single("SELECT count(*) FROM orders") shouldBe before + 2
  }

  "an unprobed read answering with text" should "run once and not fail" in withEdge { edge =>
    for sql <- List(
        "CALL pragma_version()",
        "EXPLAIN ANALYZE SELECT 1",
        "SELECT 'a' AS a; SELECT 'b' AS b"
      )
    do
      withClue(sql) {
        edge.drain()
        statement(edge.client)(_.executeUpdate(sql)) shouldBe 0
        edge.drain().size shouldBe 1
      }
  }

  // Not unprobed, and failing since before F17: a prepared executeUpdate always goes through DoPut,
  // so a DROP answering `Success` hit the null signature despite its advertised Count.
  "a prepared executeUpdate" should "survive a Success answer" in withEdge { edge =>
    statement(edge.client)(_.executeUpdate("CREATE TABLE gone(x INT)"))
    prepared(edge.client, "DROP TABLE gone")(_.executeUpdate()) shouldBe 0
  }

  // A write advertised as Count reaches JDBC through DoGet, and the driver returns from executeUpdate
  // once it holds the first batch, without reading to the end. The edge sent that batch before the
  // exhaustion that fires a stamped write's COMMIT, so the client's next statement could run before
  // the commit (`Table with name ... does not exist` right after the CTAS that created it), and it
  // never read a failed commit.
  "a write's answer" should "reach the client only after its COMMIT" in withEdge { edge =>
    val committed = new AtomicBoolean(false)
    edge.onCommit { () =>
      Thread.sleep(500)
      committed.set(true)
    }
    statement(edge.client)(_.executeUpdate("INSERT INTO orders VALUES (20, 'd@x.io')"))
    committed.get() shouldBe true
    committed.set(false)
    prepared(edge.client, "INSERT INTO orders VALUES (21, 'e@x.io')")(_.execute())
    committed.get() shouldBe true
  }

  it should "be the failure when its COMMIT fails, and the write must not run again" in withEdge {
    edge =>
      edge.onCommit(() => throw new RuntimeException("Transaction conflict"))
      edge.drain()
      intercept[SQLException] {
        statement(edge.client)(_.executeUpdate("INSERT INTO orders VALUES (22, 'f@x.io')"))
      }
      edge.drain().count(_.contains("VALUES (22,")) shouldBe 1
      intercept[SQLException] {
        prepared(edge.client, "INSERT INTO orders VALUES (23, 'g@x.io')")(_.execute())
      }
      edge.drain().count(_.contains("VALUES (23,")) shouldBe 1
  }

  "the paths that worked" should "keep working" in withEdge { edge =>
    statement(edge.client)(s => rows(s.executeQuery("SELECT 'x' AS a"))) shouldBe List("x")
    prepared(edge.client, "SELECT 'x' AS a")(s => rows(s.executeQuery())) shouldBe List("x")
    statement(edge.client)(s => rows(s.executeQuery("PRAGMA database_size"))) should not be empty
    val before = edge.single("SELECT count(*) FROM orders")
    statement(edge.client)(_.executeUpdate("INSERT INTO orders VALUES (12, 'c@x.io')"))
    edge.single("SELECT count(*) FROM orders") shouldBe before + 1
  }
