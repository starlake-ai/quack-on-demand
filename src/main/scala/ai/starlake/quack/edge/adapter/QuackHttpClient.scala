package ai.starlake.quack.edge.adapter

import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.ipc.ArrowReader
import org.duckdb.{DuckDBConnection, DuckDBResultSet}

import java.sql.{DriverManager, PreparedStatement, SQLException}

/** Forwards SQL to a Quack node through an embedded DuckDB.
  *
  * Quack's wire format is `application/duckdb` (binary), not JSON — the only
  * supported way to talk to a Quack server from a non-DuckDB process is to
  * embed DuckDB and use its `quack` extension's `quack_query()` table function:
  *
  * {{{ SELECT * FROM quack_query('quack:host:port', '<sql>', token := '<token>') }}}
  *
  * Each call opens a fresh JDBC connection from a shared in-process database
  * so concurrent queries don't serialise. The returned [[QuackResponse.Ok]]
  * carries an [[ArrowReader]] streaming batches plus a `close` callback that
  * tears down the result set, statement and connection. The caller is
  * responsible for invoking close once it has consumed all batches.
  *
  * The class is named `QuackHttpClient` for historical compatibility with the
  * surrounding code; the wire is no longer HTTP. */
open class QuackHttpClient(allocator: BufferAllocator) extends LazyLogging:

  // One shared in-process DuckDB. We hand out per-call connections via
  // `duplicate()` so multiple Flight requests don't serialise.
  private val rootConn: DuckDBConnection = {
    Class.forName("org.duckdb.DuckDBDriver")
    val c = DriverManager.getConnection("jdbc:duckdb:").asInstanceOf[DuckDBConnection]
    val stmt = c.createStatement()
    try
      stmt.execute("INSTALL quack")
      stmt.execute("LOAD quack")
    finally stmt.close()
    c
  }

  /** Execute `sql` on the Quack instance reachable at `endpoint` (a
    * `quack:host:port` URI) using `token` for auth. Returns a streaming
    * [[QuackResponse.Ok]] whose `close` callback the caller must invoke. */
  def query(
      endpoint: String,
      token: String,
      sql: String,
      session: Option[String]
  ): IO[QuackResponse] = IO.blocking {
    val started = System.nanoTime()
    val conn = rootConn.duplicate()
    var ps: PreparedStatement = null
    var rs: DuckDBResultSet    = null
    try
      ps = conn.prepareStatement(
        "SELECT * FROM quack_query(?, ?, token := ?)"
      )
      ps.setString(1, endpoint)
      ps.setString(2, sql)
      ps.setString(3, token)
      rs = ps.executeQuery().asInstanceOf[DuckDBResultSet]
      val reader = rs.arrowExportStream(allocator, 2048L).asInstanceOf[ArrowReader]
      val elapsed = (System.nanoTime() - started) / 1_000_000
      val closeAll: () => Unit = () => {
        try reader.close() catch case _: Throwable => ()
        try rs.close()     catch case _: Throwable => ()
        try ps.close()     catch case _: Throwable => ()
        try conn.close()   catch case _: Throwable => ()
      }
      QuackResponse.Ok(reader, elapsed, closeAll)
    catch
      case t: Throwable =>
        if rs != null then try rs.close() catch case _: Throwable => ()
        if ps != null then try ps.close() catch case _: Throwable => ()
        try conn.close() catch case _: Throwable => ()
        val elapsed = (System.nanoTime() - started) / 1_000_000
        val err = t match
          case sql: SQLException if isTransient(sql) =>
            QuackError.Transient(sql.getMessage)
          case _ => QuackError.Permanent(t.getMessage)
        logger.debug(s"quack_query failed: ${t.getMessage}")
        QuackResponse.Failed(err, elapsed)
  }

  /** Best-effort classifier — DuckDB's SQLException doesn't always carry a
    * structured error code, so we sniff the message. */
  private def isTransient(sql: SQLException): Boolean =
    val msg = Option(sql.getMessage).getOrElse("").toLowerCase
    msg.contains("connection refused") ||
      msg.contains("timed out") ||
      msg.contains("timeout") ||
      msg.contains("503") ||
      msg.contains("502") ||
      msg.contains("504")