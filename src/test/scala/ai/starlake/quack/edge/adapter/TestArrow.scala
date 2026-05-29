package ai.starlake.quack.edge.adapter

import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.ipc.ArrowReader
import org.duckdb.{DuckDBConnection, DuckDBResultSet}

import java.sql.DriverManager

/** Builds throw-away ArrowReader instances backed by an in-process DuckDB so
  * tests can stub out [[QuackHttpClient.query]] without standing up a real
  * Quack node. */
object TestArrow:

  // One root allocator for the whole test JVM. Plenty of headroom - readers
  // built here are tiny.
  val sharedAllocator = new RootAllocator()

  Class.forName("org.duckdb.DuckDBDriver")

  /** Reader yielding a single row `SELECT 1 AS x`. */
  def oneRowReader(): ArrowReader =
    readerFor("SELECT 1 AS x")

  /** Reader yielding zero rows but a non-empty schema, useful for "empty result"
    * paths without losing schema information. */
  def emptyReader(): ArrowReader =
    readerFor("SELECT 1 AS x WHERE FALSE")

  /** Reader for arbitrary SQL evaluated against the in-process DuckDB. */
  def readerFor(sql: String): ArrowReader =
    val conn = DriverManager.getConnection("jdbc:duckdb:").asInstanceOf[DuckDBConnection]
    val stmt = conn.createStatement()
    val rs   = stmt.executeQuery(sql).asInstanceOf[DuckDBResultSet]
    rs.arrowExportStream(sharedAllocator, 1024L).asInstanceOf[ArrowReader]

  /** A success response with a small reader and a no-op close. */
  def okResponse(latencyMs: Long = 5L): QuackResponse =
    QuackResponse.Ok(oneRowReader(), latencyMs, () => ())