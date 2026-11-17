package ai.starlake.quack.edge.adapter

import ai.starlake.quack.ondemand.InProcessDuckDb
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.dictionary.Dictionary
import org.apache.arrow.vector.ipc.ArrowReader
import org.apache.arrow.vector.types.pojo.Schema
import org.duckdb.{DuckDBConnection, DuckDBResultSet}

import java.sql.{Connection, DriverManager, ResultSet}

/** Builds throw-away ArrowReader instances backed by an in-process DuckDB so tests can stub out
  * [[QuackHttpClient.query]] without standing up a real Quack node.
  */
object TestArrow:

  // One root allocator for the whole test JVM. Plenty of headroom - readers
  // built here are tiny.
  val sharedAllocator = new RootAllocator()

  Class.forName("org.duckdb.DuckDBDriver")

  // ONE in-memory database for every reader. A database owns a pool of worker threads; opening
  // one per reader (readers here are rarely closed, `okResponse` closes nothing) leaked them until
  // the test JVM hit the OS per-process thread limit and the suite stalled. A duplicate
  // connection costs no thread.
  private lazy val shared: DuckDBConnection =
    val c = DriverManager
      .getConnection("jdbc:duckdb:", InProcessDuckDb.properties)
      .asInstanceOf[DuckDBConnection]
    val s = c.createStatement()
    try s.execute("SET threads = 1")
    finally s.close()
    c

  /** Reader yielding a single row `SELECT 1 AS x`. */
  def oneRowReader(): ArrowReader =
    readerFor("SELECT 1 AS x")

  /** Reader yielding zero rows but a non-empty schema, useful for "empty result" paths without
    * losing schema information.
    */
  def emptyReader(): ArrowReader =
    readerFor("SELECT 1 AS x WHERE FALSE")

  /** Reader for arbitrary SQL evaluated against the in-process DuckDB, through DuckDB's own Arrow
    * export: every type arrives exactly as a node presents it (unsigned and HUGEINT widths,
    * temporal units, ENUM dictionaries, nested and union layouts). Batches hold at most 1024 rows.
    */
  def readerFor(sql: String): ArrowReader =
    val conn = shared.duplicate()
    try fromResultSet(conn, conn.createStatement().executeQuery(sql))
    catch
      case e: Throwable =>
        conn.close()
        throw e

  /** Wrap an open JDBC result as an ArrowReader through DuckDB's Arrow export; closing the reader
    * closes `conn`.
    */
  def fromResultSet(conn: Connection, rs: ResultSet): ArrowReader =
    val exported = rs
      .asInstanceOf[DuckDBResultSet]
      .arrowExportStream(sharedAllocator, 1024L)
      .asInstanceOf[ArrowReader]
    new ClosingReader(exported, conn)

  /** `inner`, closing `conn` after it. Every read delegates, so the root, the batches and the
    * dictionaries are the export's own.
    */
  private final class ClosingReader(inner: ArrowReader, conn: Connection)
      extends ArrowReader(sharedAllocator):
    override def getVectorSchemaRoot: VectorSchemaRoot = inner.getVectorSchemaRoot
    override def getDictionaryVectors: java.util.Map[java.lang.Long, Dictionary] =
      inner.getDictionaryVectors
    override def lookup(id: Long): Dictionary                    = inner.lookup(id)
    override def getDictionaryIds: java.util.Set[java.lang.Long] = inner.getDictionaryIds
    override def loadNextBatch(): Boolean                        = inner.loadNextBatch()
    override def bytesRead(): Long                               = inner.bytesRead()
    override def close(): Unit                                   = close(true)
    override def close(closeReadSource: Boolean): Unit           =
      try inner.close(closeReadSource)
      finally conn.close()
    override protected def closeReadSource(): Unit = ()
    override protected def readSchema(): Schema    = inner.getVectorSchemaRoot.getSchema

  /** A success response with a small reader and a no-op close. */
  def okResponse(latencyMs: Long = 5L): QuackResponse =
    QuackResponse.Ok(oneRowReader(), latencyMs, () => ())
