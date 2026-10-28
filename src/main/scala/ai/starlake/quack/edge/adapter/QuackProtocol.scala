package ai.starlake.quack.edge.adapter

import cats.effect.{FiberIO, IO}
import cats.effect.unsafe.IORuntime
import com.typesafe.scalalogging.LazyLogging
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.{VectorLoader, VectorSchemaRoot, VectorUnloader}
import org.apache.arrow.vector.dictionary.Dictionary
import org.apache.arrow.vector.ipc.ArrowReader

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Connection lifecycle errors surfaced by [[QuackProtocol]]. Mirrors the [[QuackError.Transient]]
  * / [[QuackError.Permanent]] split in `QuackHttpClient.scala` so Task 7's adapter can map between
  * the two with a thin `match` clause.
  *
  *   - Transient: retryable. HTTP 5xx, connect refused, read timeout -- anything that says "try the
  *     same call again later".
  *   - Permanent: not retryable. HTTP 4xx, ERROR_RESPONSE from a sane server, unexpected message
  *     type -- caller should surface the error to the user.
  */
sealed trait QuackWireError extends RuntimeException

object QuackWireError:
  final case class Transient(msg: String) extends RuntimeException(msg) with QuackWireError
  final case class Permanent(msg: String) extends RuntimeException(msg) with QuackWireError

/** Pluggable POST transport. Tests inject a stub that records the request body and returns canned
  * response bytes (see `QuackProtocolSpec`); production uses [[QuackProtocol.JdkHttpTransport]]
  * which wraps a `HttpClient`.
  *
  * Why an abstraction rather than mocking `HttpClient` directly: Java's `HttpClient` is a
  * sealed-class hierarchy that does not lend itself to interception without an embedded HTTP
  * server. An injected `Transport` keeps the state-machine tests dependency-free, which is what
  * Task 6.4 asks for ("the injectable-producer approach").
  */
trait QuackTransport:
  /** Send `body` as the body of a POST to `uri` with `Content-Type: application/vnd.duckdb`. Return
    * the response bytes.
    *
    * Implementations must signal HTTP non-2xx via [[QuackWireError.Transient]] (5xx) or
    * [[QuackWireError.Permanent]] (4xx) so the protocol driver can route retries correctly.
    */
  def post(uri: URI, body: Array[Byte]): IO[Array[Byte]]

/** Owns Quack connection lifecycle over an injectable [[QuackTransport]] and produces a lazy
  * chained `ArrowReader` for callers.
  *
  * Wire-level state machine (per design §6.2):
  * {{{
  *   client                                              server
  *     │── POST CONNECTION_REQUEST(token) ─────────────> │
  *     │<── CONNECTION_RESPONSE(connId) ──────────────── │
  *     │── POST PREPARE_REQUEST(connId, sql) ──────────> │
  *     │<── PREPARE_RESPONSE(uuid, names, chunks, more?) │
  *     │── POST FETCH_REQUEST(connId, uuid) ───────────> │   (loop while more)
  *     │<── FETCH_RESPONSE(chunks)  ──────────────────── │
  *     │── POST DISCONNECT(connId) ────────────────────> │
  *     │<── SUCCESS_RESPONSE  ────────────────────────── │
  * }}}
  *
  * The driver never caches `Connection`s across logical queries (per design §6.4) -- connection
  * reuse is what gave the upstream race its legs, and JDK HttpClient already pools the underlying
  * socket.
  *
  * End-of-FETCH-loop detection. The wire does NOT carry a `needs_more_fetch` flag on
  * `FETCH_RESPONSE` -- only on `PREPARE_RESPONSE`. The upstream `quack_scan.cpp:331` client uses
  * `fetch_response->MutableResults().empty()` as its terminator ("server is done, we are done"). We
  * mirror that via [[QuackNativeBridge.fetchResponseChunkCount]] returning `0`.
  *
  * Column-name threading: design option (b). The C++ [[QuackNativeBridge.extractArrowStream]]
  * propagates `PrepareResponseMessage::Names()` directly into the Arrow C-data schema for the
  * initial PREPARE_RESPONSE-derived reader, so the Scala side does not have to rewrap the schema.
  * Subsequent FETCH batches reuse the same column names because the chained reader never re-reads
  * the schema after the first child reader hands it over.
  */
final class QuackProtocol(
    transport: QuackTransport,
    allocator: BufferAllocator,
    nodeLeaseSec: Int = QuackProtocol.DefaultNodeLeaseSec
) extends LazyLogging:
  require(nodeLeaseSec >= 1, s"nodeLeaseSec must be >= 1, got $nodeLeaseSec")

  /** Opens a Quack connection: POST `CONNECTION_REQUEST(token)`, expect
    * `CONNECTION_RESPONSE(connId)`. Surfaces `ERROR_RESPONSE` as [[QuackWireError.Permanent]], any
    * other unexpected message type as [[QuackWireError.Permanent]].
    *
    * The returned [[Connection]] already runs its heartbeat fiber, so it holds a node session and a
    * fiber until [[Connection.close]]: acquire it under a mask and release it on every outcome
    * (error and cancel included), as `QuackHttpClient` does.
    *
    * `endpoint` is the upstream Quack endpoint string in the `quack:host:port` form that
    * `ai.starlake.quack.ondemand.runtime.LocalQuackBackend` produces. Translated to
    * `http://host:port/quack` for the actual HTTP POST.
    */
  def open(endpoint: String, token: String): IO[Connection] =
    open(endpoint, token, () => QuackNativeSupport.requireLoaded())

  /** [[open]] with the native-load guard injectable, so a test can fail it without breaking the
    * real native.
    */
  private[adapter] def open(endpoint: String, token: String, guard: () => Unit): IO[Connection] =
    IO.defer {
      // Fail this IO, not the runtime: a broken native would otherwise surface as a fatal
      // LinkageError at the first bridge call below (see QuackNativeSupport.probe).
      guard()
      // Inside the IO too: a malformed endpoint fails the IO rather than throwing at call time.
      val url     = QuackProtocol.endpointToHttp(endpoint)
      val reqBody = QuackNativeBridge.serializeConnectionRequest(token, nodeLeaseSec.toLong)
      // Uncancelable from the POST on: once the node may have granted the connection, a cancel
      // must not drop it before its heartbeat fiber is registered where close() reaches it. The
      // JDK transport's send is IO.blocking (uninterruptible) anyway, so this masks nothing a
      // cancel could have cut short.
      IO.uncancelable { _ =>
        transport
          .post(url, reqBody)
          .map { respBytes =>
            val wireOrdinal = QuackNativeBridge.parseMessageType(respBytes)
            MessageType.fromWireOrdinal(wireOrdinal) match
              case Some(MessageType.ConnectionResponse) =>
                val connId = QuackNativeBridge.extractConnectionId(respBytes)
                new Connection(transport, url, connId, allocator)
              case Some(MessageType.ErrorResponse) =>
                val msg = QuackNativeBridge.extractErrorMessage(respBytes)
                throw QuackWireError.Permanent(msg)
              case other =>
                val label = other.map(_.toString).getOrElse(wireOrdinal.toString)
                throw QuackWireError.Permanent(
                  s"unexpected response type after CONNECTION_REQUEST: $label"
                )
          }
          .flatTap { conn =>
            conn
              .startHeartbeats(QuackProtocol.heartbeatEvery(nodeLeaseSec))
              .handleErrorWith(t => conn.close() *> IO.raiseError(t))
          }
      }
    }

object QuackProtocol:

  /** Default node lease (Quack v3 `heartbeat_timeout_seconds`), config `nodeLeaseSec`. */
  val DefaultNodeLeaseSec: Int = 30

  /** A third of the lease, at least one second. The node checks the lease when a message arrives
    * and counts whole elapsed seconds, so a beat renews in time while the gap since the previous
    * message stays under the lease: from 3 s up, one lost beat is tolerated (two periods stay under
    * the lease, three do not). The 1 s floor leaves less below 3 s: at 2 s no beat may be lost, and
    * at 1 s the period equals the lease, so a gap of one period already lapses it unless other
    * traffic renews it first.
    */
  def heartbeatEvery(leaseSec: Int): FiniteDuration = math.max(1, leaseSec / 3).seconds

  /** "quack:host:port" -> "http://host:port/quack". The leading `quack:` scheme is what
    * `LocalQuackBackend` and `KubernetesQuackBackend` publish; we never see a `quack://` two-slash
    * form on the wire side.
    */
  def endpointToHttp(s: String): URI =
    require(s != null, "endpoint must not be null")
    require(s.startsWith("quack:"), s"endpoint must start with 'quack:' but was: $s")
    val hostPort = s.substring("quack:".length).stripSuffix("/")
    URI.create(s"http://$hostPort/quack")

  /** JDK `HttpClient`-backed transport. Used in production; tests use a stub [[QuackTransport]]
    * instead.
    *
    * `IO.blocking` wraps the synchronous send because the JDK HttpClient's `send` blocks the
    * calling thread until the full response body is received. cats-effect routes blocking calls to
    * a dedicated thread pool so they do not starve compute fibers.
    */
  final class JdkHttpTransport(http: HttpClient) extends QuackTransport with LazyLogging:
    def post(uri: URI, body: Array[Byte]): IO[Array[Byte]] = IO.blocking {
      val req = HttpRequest
        .newBuilder(uri)
        .header("Content-Type", "application/vnd.duckdb")
        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        .build()
      // Refused or timed out while connecting: nothing reached the node, so the call is safe to
      // retry, as the Transient contract above says (a node still starting, or one that died).
      val resp =
        try http.send(req, HttpResponse.BodyHandlers.ofByteArray())
        catch
          case e @ (_: java.net.ConnectException | _: java.net.http.HttpConnectTimeoutException) =>
            throw QuackWireError.Transient(s"cannot connect to Quack node at $uri: $e")
      resp.statusCode() match
        case 200                         => resp.body()
        case sc if sc >= 500 && sc < 600 =>
          throw QuackWireError.Transient(s"HTTP $sc from Quack node at $uri")
        case sc =>
          throw QuackWireError.Permanent(s"HTTP $sc from Quack node at $uri")
    }

/** Per logical query handle. Construction by [[QuackProtocol.open]] captures the server-assigned
  * `connectionId`. [[execute]] runs one SQL statement and returns a streaming `ArrowReader`;
  * [[close]] sends `DISCONNECT_MESSAGE` best-effort.
  *
  * Concurrency note: `Connection` is one-shot -- call `execute` once, drain the returned reader to
  * completion (or close it), then `close()`. Reusing a `Connection` across two `execute` calls is
  * not supported because the wire's `result_uuid` is bound to the connection by PREPARE_RESPONSE; a
  * second PREPARE on the same connection would overwrite that UUID and confuse any half-drained
  * chained reader still holding the old one. Sequential statements via [[executeDiscard]] +
  * [[execute]] on the same connection ARE supported (the stamped-write bracket pattern): the
  * prohibition is on overlapping half-drained readers, not on fully-drained sequential PREPAREs.
  */
final class Connection private[adapter] (
    transport: QuackTransport,
    url: URI,
    val connectionId: String,
    allocator: BufferAllocator
) extends LazyLogging:

  @volatile private var heartbeat: Option[FiberIO[Nothing]] = None
  private val closing: AtomicBoolean                        = AtomicBoolean(false)

  /** Renews the node lease until [[close]]. A failed beat is ignored: a dead node already fails the
    * in-flight request, and the next beat retries. The bytes are built inside the beat's IO, so a
    * native failure fails (and logs) that beat instead of the open that already holds a session.
    *
    * `.start` and the registration run under one mask: a cancel between them would leave a fiber
    * that [[close]] cannot reach.
    */
  private[adapter] def startHeartbeats(every: FiniteDuration): IO[Unit] =
    val beat = IO
      .defer {
        // No new beat once close() began, so none is SENT after the DISCONNECT.
        if closing.get() then IO.unit
        else transport.post(url, QuackNativeBridge.serializeHeartbeat(connectionId)).void
      }
      .handleError(e =>
        logger.debug(s"HEARTBEAT for $connectionId failed (ignored): ${e.getMessage}")
      )
    IO.uncancelable(_ =>
      (IO.sleep(every) *> beat).foreverM.start.flatMap(f => IO { heartbeat = Some(f) })
    )

  /** Stops the heartbeats without waiting for them. `fiber.cancel` would wait for an in-flight
    * beat, whose JDK send is uninterruptible and has no timeout, so a hung node would hang close()
    * and the DISCONNECT behind it. Instead the cancel runs on its own fiber and the `closing` flag
    * keeps any beat from starting from here on. A beat already in flight may still reach the node
    * after the DISCONNECT; the node then finds no such connection and answers "Invalid connection
    * id" before touching any lease (quack_server.cpp DispatchMessage), so it neither renews nor
    * resurrects the session, and the error is swallowed by the beat's own handler.
    */
  private def stopHeartbeats: IO[Unit] = IO.defer {
    closing.set(true)
    heartbeat.fold(IO.unit)(_.cancel.start.void)
  }

  /** Posts a PREPARE_REQUEST for `sql` and returns a streaming `ArrowReader` that chains the
    * initial PREPARE_RESPONSE batches with subsequent FETCH_RESPONSE batches until the server
    * signals end-of-stream (empty `results` vector on a FETCH_RESPONSE -- see upstream
    * `quack_scan.cpp:331`).
    *
    * The returned reader's `close()` cascades to [[close]] on this connection, so callers only
    * manage one resource. The reader is synchronous (Arrow Java's contract); the IO it needs to
    * perform FETCH round-trips is materialised via the supplied `IORuntime` -- the standard pattern
    * for bridging IO into a synchronous caller, mirroring what http4s does for its IO ←→
    * blocking-API edges.
    */
  def execute(sql: String)(using runtime: IORuntime): IO[ArrowReader] = IO.defer {
    // Inside the IO: a native throw must fail the IO, so the caller's error path closes us.
    val prepBody = QuackNativeBridge.serializePrepareRequest(connectionId, sql)
    transport.post(url, prepBody).map { respBytes =>
      val wireOrdinal = QuackNativeBridge.parseMessageType(respBytes)
      MessageType.fromWireOrdinal(wireOrdinal) match
        case Some(MessageType.PrepareResponse) =>
          val needsMore  = QuackNativeBridge.needsMoreFetch(respBytes)
          val resultUuid = QuackNativeBridge.extractResultUuid(respBytes)
          val streamPtr  = QuackNativeBridge.extractArrowStream(respBytes)
          val initial    = QuackArrowImport.importStream(allocator, streamPtr)
          // The reader runs synchronous network I/O via this closure.
          // Materialising the IO with the caller-supplied runtime
          // keeps the reader implementation free of cats-effect
          // imports and free of any global-runtime assumption.
          // Quack v3 FETCHes name their batch: 1, 2, ... in order, one per call.
          val nextBatch                    = java.util.concurrent.atomic.AtomicLong(0L)
          val fetchSync: () => Array[Byte] =
            () => fetch(resultUuid, nextBatch.incrementAndGet()).unsafeRunSync()
          val closeSync: () => Unit = () => close().unsafeRunSync()
          // The import is lazy: the schema is exported only when the reader is built, and that
          // throws for a column type with no Arrow mapping. Until the reader exists nothing else
          // owns `initial`, whose close is the only release of the native result.
          try
            new ChainedQuackArrowReader(
              allocator,
              initial,
              fetchSync,
              closeSync,
              needsMore
            )
          catch
            case NonFatal(e) =>
              try initial.close()
              catch case NonFatal(c) => e.addSuppressed(c)
              throw unexportable(e).getOrElse(e)
        case Some(MessageType.ErrorResponse) =>
          val msg = QuackNativeBridge.extractErrorMessage(respBytes)
          throw QuackWireError.Permanent(msg)
        case other =>
          val label = other.map(_.toString).getOrElse(wireOrdinal.toString)
          throw QuackWireError.Permanent(
            s"unexpected response type after PREPARE_REQUEST: $label"
          )
    }
  }

  /** DuckDB's own words when a result column's type has no Arrow mapping (`TYPE`, and any nested
    * type holding one), raised when the imported stream's schema is exported.
    */
  private val UnsupportedArrowType = """Unsupported Arrow type ([^"\\]+)""".r

  /** The error for a result the manager cannot export to Arrow, naming the type and the way out, or
    * None when `e` is some other failure.
    */
  private def unexportable(e: Throwable): Option[QuackWireError] =
    Iterator
      .iterate(e)(_.getCause)
      .takeWhile(_ != null)
      .take(16) // a cause cycle must not spin
      .flatMap(t => Option(t.getMessage))
      .flatMap(m => UnsupportedArrowType.findFirstMatchIn(m))
      .nextOption()
      .map { m =>
        val tpe = m.group(1).trim
        QuackWireError.Permanent(
          s"a result column of type $tpe has no Arrow mapping and cannot be returned; " +
            "cast it to VARCHAR"
        )
      }

  /** Run `sql` on this connection and throw away the result. Used by the stamped-write bracket
    * (EPIC P1) for its BEGIN/CALL prelude and COMMIT epilogue, whose results are empty Success
    * shells the caller never streams. Drains through the normal reader so multi-batch responses
    * release their native chunks, then closes the reader WITHOUT the DISCONNECT cascade
    * (`closeReadSource = false`) so the connection stays usable for the next PREPARE. Wire errors
    * surface exactly like [[execute]] ([[QuackWireError]]).
    */
  def executeDiscard(sql: String)(using runtime: IORuntime): IO[Unit] =
    execute(sql).map { reader =>
      try while reader.loadNextBatch() do ()
      finally reader.close(false)
    }

  /** Stops the heartbeats (without waiting for an in-flight one), then sends a best-effort
    * DISCONNECT_MESSAGE. A half-dead node must not crash the caller's `IO`, so we swallow any
    * transport exception. Do not count on the node to reap a connection that is never closed: it
    * checks a lease only when a message for that connection arrives, so an abandoned connection
    * keeps its node session until DISCONNECT or node shutdown.
    */
  def close(): IO[Unit] = stopHeartbeats *> transport
    .post(url, QuackNativeBridge.serializeDisconnect(connectionId))
    .void
    .handleError { e =>
      logger.debug(s"DISCONNECT for $connectionId failed (ignored): ${e.getMessage}")
      ()
    }

  /** Internal hook used by [[ChainedQuackArrowReader]] to fire the next FETCH_REQUEST.
    * Public-package-private because the reader is in the same package.
    */
  private[adapter] def fetch(resultUuid: java.math.BigInteger, batchIndex: Long): IO[Array[Byte]] =
    transport.post(
      url,
      QuackNativeBridge.serializeFetchRequest(connectionId, resultUuid, batchIndex)
    )

/** Lazy chained [[ArrowReader]] that pulls batches from the initial PREPARE_RESPONSE reader, then
  * issues FETCH_REQUEST/FETCH_RESPONSE round-trips until the server signals end-of-stream (empty
  * `results` on a FETCH_RESPONSE per upstream `quack_scan.cpp:331`).
  *
  * Lifetime contract: this reader owns the `current` child reader plus the underlying
  * [[Connection]]. `close()` cascades to both, exactly once (idempotent via [[closed]]).
  *
  * `getVectorSchemaRoot` and `getDictionaryVectors` delegate to the current child reader. The
  * parent [[ArrowReader]] base class's internal `root` and `dictionaries` fields are NEVER
  * initialised by us (we override every public surface that would call `ensureInitialized`); the
  * abstract `readSchema` / `closeReadSource` implementations exist solely to satisfy the
  * abstract-class signature.
  *
  * Why this shape rather than a fresh stream wired through `Data.importArrayStream`: chaining at
  * the C-data level would require a single `ArrowArrayStream` whose `get_next` lazily fires FETCH
  * on the network. That mixes IO into a callback the Arrow Java importer holds on the JVM heap,
  * which is exactly the kind of tangle we are removing from the embedded-DuckDB path. Owning the
  * chain in Scala keeps the IO boundary explicit.
  */
private[adapter] final class ChainedQuackArrowReader(
    allocator: BufferAllocator,
    initial: ArrowReader,
    fetchSync: () => Array[Byte],
    closeSync: () => Unit,
    initialNeedsMoreFetch: Boolean
) extends ArrowReader(allocator)
    with LazyLogging:

  private val currentRef: AtomicReference[ArrowReader] = AtomicReference(initial)
  // FETCH_RESPONSE has no `needs_more_fetch` flag (see design §6.2).
  // We start the FETCH loop iff PREPARE_RESPONSE said so, and stop
  // when the next FETCH_RESPONSE comes back with zero chunks.
  private val moreFetchPending: AtomicBoolean = AtomicBoolean(initialNeedsMoreFetch)
  private val closed: AtomicBoolean           = AtomicBoolean(false)

  // `close` may come from another thread while a batch is being read: the REST data edge forces a
  // stalled read to return by closing the result under it. That close must not release `out` or a
  // child the read is still using (a FETCH answered after it would import a child nobody closes,
  // loaded into a freed root), so it only marks the reader closed and disconnects; the reading
  // thread releases them once its read returns. `reading` is guarded by `readLock`.
  private val readLock        = new Object
  private var reading         = false
  private val batchesReleased = AtomicBoolean(false)

  // One STABLE root for the whole chained stream. Each child reader (the
  // initial PREPARE_RESPONSE plus every FETCH_RESPONSE) imports its own
  // `VectorSchemaRoot` from the C-data ABI; we copy each loaded child batch
  // into `out` so consumers always see the same root object. This honours
  // the `ArrowReader` contract that `getVectorSchemaRoot()` returns a stable
  // root re-populated by each `loadNextBatch()` -- the Flight edge's
  // `streamArrow` captures the root once via `listener.start(root)` and then
  // loops `putNext()`, so a per-FETCH child swap would otherwise flush a
  // closed (released) child root and emit a corrupt batch with a stale row
  // count over empty columns.
  //
  // `VectorLoader.load` shares the child buffers by reference (same
  // `allocator` for every child), so this is zero-copy; the next `load` (or
  // `close`) releases the previous batch's buffers, which is why we keep the
  // exhausted child open until after its batch has been loaded.
  private val out: VectorSchemaRoot =
    VectorSchemaRoot.create(initial.getVectorSchemaRoot.getSchema, allocator)
  private val loader: VectorLoader = new VectorLoader(out)

  /** Pull the next batch from `child` (if any) into the stable `out` root. */
  private def loadInto(child: ArrowReader): Boolean =
    if child.loadNextBatch() then
      val batch = new VectorUnloader(child.getVectorSchemaRoot).getRecordBatch
      try loader.load(batch)
      finally batch.close()
      true
    else false

  override def loadNextBatch(): Boolean =
    val entered = readLock.synchronized {
      val open = !closed.get()
      if open then reading = true
      open
    }
    if !entered then return false
    val loaded =
      try readNext()
      finally
        val closedMeanwhile = readLock.synchronized {
          reading = false
          closed.get()
        }
        if closedMeanwhile then releaseBatches()
    // A batch loaded into a root released since is no batch.
    if closed.get() then throw new IllegalStateException("result closed while reading")
    loaded

  private def readNext(): Boolean =
    // Drain the current child first. If it has more batches, return.
    // Otherwise loop: close it, fire next FETCH, swap, retry -- until
    // either we land a batch or the FETCH loop terminates.
    if loadInto(currentRef.get()) then return true
    while moreFetchPending.get() do
      // Close the exhausted child so its native ArrowArrayStream's
      // `release` callback runs (freeing the moved-out DataChunkWrappers)
      // before we open the next one. Otherwise two C-side holders would
      // briefly co-exist. `out` already holds its own reference to the last
      // loaded batch's buffers, so closing the child does not free them.
      currentRef.get().close()
      val fetchBytes = fetchSync()
      val wireOrd    = QuackNativeBridge.parseMessageType(fetchBytes)
      MessageType.fromWireOrdinal(wireOrd) match
        case Some(MessageType.FetchResponse) =>
          val chunkCount = QuackNativeBridge.fetchResponseChunkCount(fetchBytes)
          if chunkCount == 0 then
            // Mirror upstream quack_scan.cpp:331: empty results -> done.
            moreFetchPending.set(false)
            return false
          val ptr       = QuackNativeBridge.extractArrowStream(fetchBytes)
          val nextChild = QuackArrowImport.importStream(allocator, ptr)
          currentRef.set(nextChild)
          // Closed while the FETCH was in flight: the child is recorded for the release, and the
          // read fails rather than end the stream as if it were complete.
          if closed.get() then throw new IllegalStateException("result closed while reading")
          // A non-empty FETCH_RESPONSE may still contain only zero-row
          // batches; if loadNextBatch returns false we loop again
          // rather than recurse (avoids stack-overflow on a pathological
          // server that keeps sending non-empty-but-rowless responses).
          if loadInto(nextChild) then return true
          // else: fall through to loop, fire another FETCH.
        case Some(MessageType.ErrorResponse) =>
          throw QuackWireError.Permanent(QuackNativeBridge.extractErrorMessage(fetchBytes))
        case other =>
          val label = other.map(_.toString).getOrElse(wireOrd.toString)
          throw QuackWireError.Permanent(
            s"unexpected response type after FETCH_REQUEST: $label"
          )
    end while
    // moreFetchPending dropped to false without producing a batch -- done.
    false

  override def getVectorSchemaRoot(): VectorSchemaRoot = out

  override def getDictionaryVectors(): java.util.Map[java.lang.Long, Dictionary] =
    currentRef.get().getDictionaryVectors

  override def bytesRead(): Long =
    // Best-effort: only the current reader's bytesRead is visible --
    // exhausted children have been closed. The Arrow C-data reader
    // currently returns 0 here anyway (see
    // `ArrowArrayStreamReader.bytesRead`), so this is correct.
    currentRef.get().bytesRead()

  override def close(closeReadSource: Boolean): Unit =
    if closed.compareAndSet(false, true) then
      // A read in progress releases the batches itself when it returns.
      if readLock.synchronized(!reading) then releaseBatches()
      if closeReadSource then
        try closeSync()
        catch case _: Throwable => ()

  /** Once, with no read in progress. Releases the stable root first: it holds a reference to the
    * last loaded batch's buffers (shared from the current child), so it must drop that reference
    * before (or alongside) the child's own release.
    */
  private def releaseBatches(): Unit =
    if batchesReleased.compareAndSet(false, true) then
      try out.close()
      catch case _: Throwable => ()
      try currentRef.get().close()
      catch case _: Throwable => ()

  /** Never called: we override `getVectorSchemaRoot` so the parent class's `ensureInitialized` path
    * (which is what invokes `readSchema`) never runs. Implementation exists solely to satisfy the
    * abstract-class signature.
    */
  override protected def readSchema(): org.apache.arrow.vector.types.pojo.Schema =
    out.getSchema

  /** Closing of the underlying resources is handled by our `close` override directly. The parent
    * class only calls this if its own `initialized` flag is set, which never happens for us.
    */
  override protected def closeReadSource(): Unit = ()
