package ai.starlake.quack.edge.adapter

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.typesafe.scalalogging.LazyLogging
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.ipc.ArrowReader
import org.duckdb.{DuckDBConnection, DuckDBResultSet}

import java.sql.{DriverManager, PreparedStatement, SQLException}

/** Forwards SQL to a Quack node, either through an embedded DuckDB (the
  * historical default) or through the JNI-backed [[QuackProtocol]] wire
  * driver introduced by Tasks 0-6 of the native-client plan.
  *
  * Embedded path. Quack's wire format is `application/vnd.duckdb` (binary),
  * and historically the only supported way to talk to a Quack server from a
  * non-DuckDB process was to embed DuckDB and use its `quack` extension's
  * `quack_query()` table function:
  *
  * {{{ SELECT * FROM quack_query('quack:host:port', '<sql>', token := '<token>') }}}
  *
  * Each call opens a fresh JDBC connection from a per-endpoint in-process
  * database so concurrent queries don't serialise across endpoints. The
  * returned [[QuackResponse.Ok]] carries an [[ArrowReader]] streaming
  * batches plus a `close` callback that tears down the result set,
  * statement and connection. The caller is responsible for invoking close
  * once it has consumed all batches.
  *
  * Native path (default on). When `nativeClient` is `true`, `query(...)`
  * routes through [[queryNative]], which uses [[QuackProtocol]] to speak
  * the Quack wire directly via the libquackwire JNI shim. This removes
  * the manager-side embedded-DuckDB + extension upstream-connection cache
  * from the request path (the source of the `Authentication failed` /
  * `Invalid connection id` loadtest flaps). The lazy `nativeProtocol`
  * ensures the JDK HttpClient is only allocated when the flag is on, so
  * the embedded path is byte-identical with the flag off.
  *
  * The class is named `QuackHttpClient` for historical compatibility with the
  * surrounding code; the wire is no longer HTTP at the embedded level either,
  * and is plain HTTP+vnd.duckdb on the native path.
  *
  * @param allocator       Arrow allocator handed to the returned reader.
  * @param nativeClient    When `true`, `query(...)` routes through the JNI
  *                        native path; when `false`, through the historical
  *                        embedded-DuckDB path. Sourced from the HOCON key
  *                        `quack-on-demand.nativeClient` (env override
  *                        `SL_QUACK_NATIVE_CLIENT`) by `Main.scala`.
  * @param nodeDisableSsl  Embedded path only: value passed to `quack_query`'s
  *                        `disable_ssl :=` argument. Sourced from the HOCON
  *                        key `quack-on-demand.nodeDisableSsl` (env override
  *                        `SL_QUACK_NODE_DISABLE_SSL`).
  * @param protocolFactory Optional test-injection seam: when supplied, the
  *                        native path constructs its [[QuackProtocol]] via
  *                        this factory instead of the production
  *                        [[QuackProtocol.JdkHttpTransport]]. Production code
  *                        passes `None`; only [[QuackHttpClientSpec]] supplies
  *                        a custom factory backed by a fake
  *                        [[QuackTransport]]. */
open class QuackHttpClient(
    allocator: BufferAllocator,
    nativeClient: Boolean,
    nodeDisableSsl: Boolean,
    protocolFactory: Option[BufferAllocator => QuackProtocol] = None
) extends LazyLogging:

  // Per-endpoint DuckDB instance. The `quack` extension caches a single
  // upstream connection per database, so reusing one DuckDB across multiple
  // (host:port, token) targets produces "Authentication failed" / "Invalid
  // connection id" errors when the cached entry doesn't match the call.
  // We keep one DuckDB per `endpoint` (which already encodes host:port);
  // the rootConn below is used only for the first INSTALL / proxy SET and
  // becomes the seed for the per-endpoint pool.
  private val perEndpoint =
    new java.util.concurrent.ConcurrentHashMap[String, DuckDBConnection]()

  // JVM-wide mutex serialising every embedded-path call across all
  // endpoints. The per-endpoint mutex below already serialises within one
  // node, but the Quack extension keeps process-wide state inside libcurl
  // / the extension itself that two concurrent calls on different
  // endpoints can still corrupt (the original "Authentication failed" /
  // "Invalid connection id" failure mode the JNI native path was built to
  // eliminate). Holding this lock around the entire embedded body trades
  // throughput for correctness; operators who need the embedded path's
  // raw correctness pay one-call-at-a-time per manager and recover
  // throughput by scaling managers horizontally. The native path bypasses
  // this entirely (no embedded DuckDB in the request path) and is the
  // production-grade default — see [[nativeClient]]. */
  private val embeddedJvmLock = new Object()

  // One shared in-process DuckDB. We hand out per-call connections via
  // `duplicate()` so multiple Flight requests don't serialise.
  private val rootConn: DuckDBConnection = {
    Class.forName("org.duckdb.DuckDBDriver")
    val c = DriverManager.getConnection("jdbc:duckdb:").asInstanceOf[DuckDBConnection]
    val stmt = c.createStatement()
    try
      // DuckDB ignores HTTP_PROXY/HTTPS_PROXY env vars for `INSTALL` - the
      // download goes through libcurl with the `http_proxy` setting. Mirror
      // what `spawn-quack-node.sh` does for child Quack nodes so a corporate
      // proxy reaches both the manager-side embed and the child supervisors.
      QuackHttpClient.proxyHostPort.foreach { hp =>
        stmt.execute(s"SET http_proxy = '$hp'")
        logger.info(s"DuckDB http_proxy set to $hp for extension downloads")
      }
      stmt.execute("INSTALL quack")
      stmt.execute("LOAD quack")
    finally stmt.close()
    c
  }

  /** Execute `sql` on the Quack instance reachable at `endpoint` (a
    * `quack:host:port` URI) using `token` for auth. Returns a streaming
    * [[QuackResponse.Ok]] whose `close` callback the caller must invoke.
    *
    * Routes through the JNI native path when [[nativeClient]] is `true`,
    * otherwise through the historical embedded-DuckDB path. The public
    * surface and `QuackResponse` shape are identical across both paths
    * so callers (`QuackHttpAdapter`, `FlightSqlRouter`) are oblivious to
    * the toggle. */
  def query(
      endpoint: String,
      token: String,
      sql: String,
      session: Option[String]
  ): IO[QuackResponse] =
    if nativeClient then queryNative(endpoint, token, sql)
    else queryEmbedded(endpoint, token, sql, session)

  /** The historical embedded-DuckDB + `quack` extension path. Reached
    * when [[nativeClient]] is `false`. The body is wrapped in a JVM-wide
    * mutex ([[embeddedJvmLock]]) so all calls across all endpoints
    * serialise — the Quack extension keeps process-wide state even
    * across per-endpoint DuckDB instances, and that state is the source
    * of the `Authentication failed` / `Invalid connection id` race the
    * JNI native path eliminates by construction. The per-endpoint pool
    * below still provides clean per-DuckDB caches and remains useful
    * defence in depth. */
  private def queryEmbedded(
      endpoint: String,
      token: String,
      sql: String,
      session: Option[String]
  ): IO[QuackResponse] = IO.blocking {
    embeddedJvmLock.synchronized {
      val started = System.nanoTime()
      // Pin to one DuckDB per endpoint so the quack extension's internal
      // upstream-connection cache stays consistent for that target. The
      // per-endpoint synchronized block is redundant under the outer
      // JVM-wide lock but retained as defence in depth; if the JVM-wide
      // lock is ever relaxed (e.g. swapped for a per-pool one), the
      // per-endpoint guard remains the safety net.
      val endpointConn = perEndpoint.computeIfAbsent(endpoint, _ => freshDuckDB())
      endpointConn.synchronized {
        val conn = endpointConn.duplicate()
        queryWith(conn, endpoint, token, sql, session, started)
      }
    }
  }

  /** The JNI-backed native path. Opens a fresh [[QuackProtocol]]
    * [[Connection]], runs `sql`, and returns a [[QuackResponse.Ok]] whose
    * `close` callback closes the chained Arrow reader.
    *
    * DISCONNECT-owner rule (plan §7.2 amendment). The reader returned by
    * `Connection.execute` is a [[ChainedQuackArrowReader]] whose `close()`
    * already cascades to `Connection.close()` which fires the
    * `DISCONNECT_MESSAGE`. The `QuackResponse.Ok` close callback therefore
    * MUST call only `reader.close()` — wrapping a separate `conn.close()`
    * here would double-send DISCONNECT. The reader is the sole owner of
    * the teardown chain. */
  private def queryNative(
      endpoint: String,
      token: String,
      sql: String
  ): IO[QuackResponse] =
    IO.monotonic.flatMap { startNanos =>
      // We need IORuntime in scope so `Connection.execute(sql)` can
      // capture it for the synchronous closures the chained reader
      // calls on `loadNextBatch` / `close`. The global runtime is the
      // same one cats-effect IOApp installs for `Main.run`.
      given IORuntime = IORuntime.global
      (for
        conn   <- nativeProtocol.open(endpoint, token)
        reader <- conn.execute(sql)
      yield reader).attempt.flatMap {
        case Right(reader) =>
          IO.monotonic.map { endNanos =>
            val elapsedMs = (endNanos - startNanos).toMillis
            // DISCONNECT-owner rule: see scaladoc above. `reader.close()`
            // already cascades to `Connection.close()`; do NOT also call
            // `conn.close()` here.
            QuackResponse.Ok(reader, elapsedMs, () => reader.close())
          }
        case Left(t) =>
          IO.monotonic.map { endNanos =>
            val elapsedMs = (endNanos - startNanos).toMillis
            val err = t match
              case e: QuackWireError.Transient => QuackError.Transient(e.msg)
              case e: QuackWireError.Permanent => QuackError.Permanent(e.msg)
              case other =>
                QuackError.Permanent(Option(other.getMessage).getOrElse(other.toString))
            logger.debug(s"queryNative failed: ${t.getMessage}")
            QuackResponse.Failed(err, elapsedMs)
          }
      }
    }

  /** Lazily-constructed [[QuackProtocol]]. The transport defaults to
    * [[QuackProtocol.JdkHttpTransport]] over a fresh JDK [[java.net.http.HttpClient]];
    * tests pass a `protocolFactory` override that wires a stub transport
    * with canned response bytes. Lazy because we MUST NOT allocate any of
    * the native-path machinery (HttpClient threads, JNI lib resolution
    * for transports) when the flag is off — keeps the default path
    * byte-identical with pre-Task-7 behavior. */
  private lazy val nativeProtocol: QuackProtocol =
    protocolFactory match
      case Some(factory) => factory(allocator)
      case None =>
        new QuackProtocol(
          new QuackProtocol.JdkHttpTransport(java.net.http.HttpClient.newHttpClient()),
          allocator
        )

  /** Build a fresh DuckDB instance with the quack extension loaded. Each
    * gets its own clean upstream-connection cache. */
  private def freshDuckDB(): DuckDBConnection = {
    Class.forName("org.duckdb.DuckDBDriver")
    val c = DriverManager.getConnection("jdbc:duckdb:").asInstanceOf[DuckDBConnection]
    val stmt = c.createStatement()
    try
      QuackHttpClient.proxyHostPort.foreach(hp => stmt.execute(s"SET http_proxy = '$hp'"))
      stmt.execute("INSTALL quack")
      stmt.execute("LOAD quack")
    finally stmt.close()
    c
  }

  private def queryWith(
      conn: DuckDBConnection,
      endpoint: String,
      token: String,
      sql: String,
      session: Option[String],
      started: Long
  ): QuackResponse = {
    var ps: PreparedStatement = null
    var rs: DuckDBResultSet    = null
    try
      // The spawn-quack-node.sh script calls quack_serve without TLS options,
      // so the node serves plain HTTP on its port. The DuckDB `quack` client
      // defaults to HTTPS, so without disable_ssl the probe / query path
      // fails with "SSL connect error" and the manager marks every node
      // unhealthy. Default `disable_ssl = true` to match the spawn script;
      // see `quack-on-demand.nodeDisableSsl` in application.conf.
      ps = conn.prepareStatement(
        s"SELECT * FROM quack_query(?, ?, token := ?, disable_ssl := $nodeDisableSsl)"
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

  /** Best-effort classifier - DuckDB's SQLException doesn't always carry a
    * structured error code, so we sniff the message. */
  private def isTransient(sql: SQLException): Boolean =
    val msg = Option(sql.getMessage).getOrElse("").toLowerCase
    msg.contains("connection refused") ||
      msg.contains("timed out") ||
      msg.contains("timeout") ||
      msg.contains("503") ||
      msg.contains("502") ||
      msg.contains("504")

object QuackHttpClient:
  /** First non-empty proxy URL from the standard env-var chain, normalised
    * to `host:port` (DuckDB's `SET http_proxy` wants no scheme and no
    * trailing slash). Returns `None` when no proxy is configured so the
    * INSTALL path is unchanged in the common case. */
  private[adapter] def proxyHostPort: Option[String] =
    proxyHostPortFrom(sys.env)

  /** Pure variant for testing - reads the proxy URL out of an arbitrary
    * env map and applies the same normalisation as [[proxyHostPort]]. */
  private[adapter] def proxyHostPortFrom(env: Map[String, String]): Option[String] =
    List("HTTP_PROXY", "http_proxy", "HTTPS_PROXY", "https_proxy")
      .iterator
      .flatMap(env.get)
      .map(_.trim)
      .find(_.nonEmpty)
      .map(normaliseProxyUrl)

  private[adapter] def normaliseProxyUrl(url: String): String =
    url
      .stripPrefix("http://")
      .stripPrefix("https://")
      .stripSuffix("/")