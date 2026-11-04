package ai.starlake.quack.edge

import io.grpc.{Attributes, Grpc}
import io.grpc.netty.{GrpcHttp2ConnectionHandler, InternalProtocolNegotiator}
import io.netty.buffer.ByteBuf
import io.netty.channel.{
  Channel,
  ChannelDuplexHandler,
  ChannelFutureListener,
  ChannelHandler,
  ChannelHandlerContext
}
import io.netty.handler.codec.http2.{Http2ConnectionAdapter, Http2Settings, Http2Stream}
import io.netty.util.AsciiString
import io.netty.util.concurrent.ScheduledFuture

import java.net.SocketAddress
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

/** Bounds what a connection can hold until it has shown a working credential. gRPC keeps a stream
  * it answered, half open and with its headers, until the client ends it, it closes some streams
  * itself before any interceptor runs (an unknown method, an unknown `grpc-encoding`, an HTTP-level
  * refusal), and gRPC and Netty keep something for nearly any frame (an empty DATA frame on a
  * stream, a `grpc-message` in a compression table the client sized, a reset for a refused stream).
  * So nothing that looks at calls can bound them, and a client with no credentials could fill the
  * heap.
  *
  * A connection is on probation from the moment it is accepted until a call on it passes
  * `headerAuth` with a credential ([[prove]], which [[AnonymousCallGuard]] calls: Arrow's adapter
  * runs before it, so a credential that fails never gets there). On probation it:
  *
  *   - holds at most [[ConnectionProbation.MaxStreams]] open streams, half open ones included, so a
  *     client that never ends its streams only blocks itself;
  *   - creates at most [[ConnectionProbation.MaxTotalStreams]] streams in all, whatever became of
  *     them, so a client that resets its own to free places gets no more than that;
  *   - is read for at most `readBudget` bytes, counted as they leave the socket, ahead of TLS.
  *     Beyond its streams, which the two rules above cap, what one connection can hold grows with
  *     bytes read, so this bounds it by whatever route, named here or not. What the read under way
  *     already holds still gets decoded, so the bound has the slack of one read buffer, at most 64
  *     KiB (Netty's adaptive allocator);
  *   - lasts at most `timeoutMs`, whatever its streams;
  *   - is one of at most `maxConnections`, and one past that is closed at once, before any TLS
  *     handshake.
  *
  * All of that bounds one connection. Its calls can outlive it: a call waiting for a Flight thread
  * keeps its headers whatever became of its connection, so while the pool is stalled a client that
  * closes and reconnects adds to them each time. `maxBacklog` bounds those: while more tasks wait
  * for a thread than that, a new connection is closed at once, as one past `maxConnections` is,
  * with no place and no timer. A connection already on probation keeps the limits above.
  *
  * Past the read budget or the stream total it is not closed: closing frees its place, and a
  * reconnect would make either limit worthless. The timer ends it. A client's own frames wait in
  * its socket, so its first call, already read and still being checked, can prove the connection
  * and reading then resumes: a large first statement pauses at the budget while its credential is
  * checked. While it is still being read a client can end it sooner: Netty keeps no record of a
  * stream it refused, so DATA on one reads as a stream that never existed and closes the connection
  * (GOAWAY, PROTOCOL_ERROR). That is what a real client's next call does past the stream total, as
  * its message follows its HEADERS at once: it reads UNAVAILABLE and reconnects. Past the read
  * budget that DATA is never read, and the client waits for the timer.
  *
  * A credential ends probation at once. A connection that has shown one has no limit on its streams
  * and does not count, so a flood can neither count it nor limit it, and a large deployment meets
  * no new ceiling. It still shares event loops, the pool and the direct memory budget with everyone
  * else, so a flood can slow it.
  *
  * This internal gRPC API because nothing public binds a client that never acknowledges SETTINGS:
  * `maxConcurrentCallsPerConnection` reaches Netty as a SETTINGS value, which Netty applies on the
  * client's ACK. `newHandler` is the only place that hands code the connection's handler before any
  * frame is read. `FlightEdgeLimitsSpec` pins it, with a client that never sends that ACK.
  */
final class ConnectionProbation(
    inner: InternalProtocolNegotiator.ProtocolNegotiator,
    maxConnections: Int,
    timeoutMs: Long,
    readBudget: Long,
    backlog: () => Int,
    maxBacklog: Int
) extends InternalProtocolNegotiator.ProtocolNegotiator:

  /** Holds only connections on probation, so a proven connection pays one failed lookup per call.
    * By both addresses: a listener on several addresses can have two connections from one remote
    * address and port.
    */
  private val onProbation = new ConcurrentHashMap[(SocketAddress, SocketAddress), Connection]()
  private val held        = new AtomicInteger(0)

  override def scheme(): AsciiString = inner.scheme()

  override def close(): Unit = inner.close()

  /** Runs per accepted connection, before any frame is read. */
  override def newHandler(grpcHandler: GrpcHttp2ConnectionHandler): ChannelHandler =
    grpcHandler.connection().remote().maxActiveStreams(ConnectionProbation.MaxStreams)
    new Connection(grpcHandler)

  /** A call's credential passed `headerAuth`: its connection leaves probation. Called on the pool's
    * threads.
    */
  def prove(attributes: Attributes): Unit =
    val connection = onProbation.get(
      (
        attributes.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR),
        attributes.get(Grpc.TRANSPORT_ATTR_LOCAL_ADDR)
      )
    )
    if connection != null then connection.prove()

  /** Sits in the pipeline for as long as the connection is on probation, next to the socket and
    * ahead of the inner negotiator's handler, which it adds behind itself so that the event
    * starting the negotiation reaches that one. So it sees raw bytes before TLS, and every read
    * that TLS and HTTP/2 ask for on their way to the socket.
    */
  private final class Connection(grpcHandler: GrpcHttp2ConnectionHandler)
      extends ChannelDuplexHandler:
    private val ended = new AtomicBoolean(false)

    // Set before the map publishes this connection to the pool's threads.
    private var channel: Channel                    = null
    private var key: (SocketAddress, SocketAddress) = null

    // Read and written on the connection's event loop only.
    private var timer: ScheduledFuture[?] = null
    private var bytesRead                 = 0L
    private var paused                    = false
    private var streamsCreated            = 0

    /** Past the total, every later HEADERS is refused in the same read. An ACK of the first
      * SETTINGS can set 8 again, once, and the next stream added sets 0 again: 17 at most.
      */
    private val streamTotal = new Http2ConnectionAdapter:
      override def onStreamAdded(stream: Http2Stream): Unit =
        streamsCreated += 1
        if streamsCreated >= ConnectionProbation.MaxTotalStreams then
          grpcHandler.connection().remote().maxActiveStreams(0)

    /** Over either limit it closes, and neither TLS nor HTTP/2 ever start. */
    override def handlerAdded(ctx: ChannelHandlerContext): Unit =
      if backlog() > maxBacklog then ctx.close()
      else if held.incrementAndGet() > maxConnections then
        held.decrementAndGet()
        ctx.close()
      else
        channel = ctx.channel()
        key = (channel.remoteAddress(), channel.localAddress())
        onProbation.put(key, this)
        val expiry: Runnable = () => expire()
        timer = channel.eventLoop().schedule(expiry, timeoutMs, TimeUnit.MILLISECONDS)
        val closed: ChannelFutureListener = _ =>
          end()
          timer.cancel(false)
        channel.closeFuture().addListener(closed)
        grpcHandler.connection().addListener(streamTotal)
        ctx.pipeline().addAfter(ctx.name(), null, inner.newHandler(grpcHandler))

    override def channelRead(ctx: ChannelHandlerContext, message: Object): Unit =
      message match
        case bytes: ByteBuf =>
          bytesRead += bytes.readableBytes()
          if bytesRead >= readBudget && !paused then pause()
        case _ => ()
      ctx.fireChannelRead(message)

    /** Stops reading, which takes both of these. With autoRead off, `Http2ConnectionHandler` and
      * `SslHandler` ask for a read after every read, so autoRead alone stops nothing once HTTP/2 is
      * up: those reads come through [[read]], which drops them. And dropping them alone stops
      * nothing either: with autoRead on, Netty's read loop goes on and keeps its read interest,
      * which it only removes when no read is pending and autoRead is off.
      */
    private def pause(): Unit =
      paused = true
      channel.config().setAutoRead(false)

    override def read(ctx: ChannelHandlerContext): Unit =
      if !paused then ctx.read()

    def prove(): Unit =
      if end() then channel.eventLoop().execute(() => lift())

    /** True for the first of proof, expiry and close: the count drops exactly once. */
    private def end(): Boolean =
      val first = ended.compareAndSet(false, true)
      if first then
        onProbation.remove(key, this)
        held.decrementAndGet()
      first

    /** Not `channel.close()`, which reaches gRPC's handler: its close is graceful and waits for
      * every stream to end, with no limit, so half open streams would hold it open for good. A
      * close from the first context travels toward the socket and never reaches that handler.
      */
    private def expire(): Unit =
      if end() then
        val first = channel.pipeline().firstContext()
        if first != null then first.close() else channel.close()

    /** Netty applies a SETTINGS value on the client's ACK, overwriting the limit with the 8 the
      * first SETTINGS advertised, so this raises the limit directly and advertises the raise, for a
      * client to learn it may open more and for its ACK to set the same.
      */
    private def lift(): Unit =
      timer.cancel(false)
      if channel.isActive then
        grpcHandler.connection().removeListener(streamTotal)
        grpcHandler.connection().remote().maxActiveStreams(Int.MaxValue)
        val ctx = channel.pipeline().context(grpcHandler)
        if ctx != null then
          val settings = new Http2Settings().maxConcurrentStreams(Int.MaxValue.toLong)
          grpcHandler.encoder().writeSettings(ctx, settings, ctx.newPromise())
          ctx.flush()
        paused = false
        channel.config().setAutoRead(true)
        channel.pipeline().remove(this)

object ConnectionProbation:

  /** The most open streams of a connection on probation. Also what the first SETTINGS advertises.
    */
  val MaxStreams: Int = 8

  /** The most streams a connection on probation creates, whatever became of them. */
  val MaxTotalStreams: Int = 16

  /** The most connections on probation at once, unless an operator says otherwise. */
  val DefaultMaxConnections: Int = 1024

  /** Room in the read budget for what comes with a first call's headers: the preface, SETTINGS, a
    * TLS handshake and a small message.
    */
  private val ReadMarginBytes: Long = 16L * 1024

  /** How far past the read budget a read can go: the largest buffer of Netty's adaptive allocator.
    */
  private val ReadSlackBytes: Long = 64L * 1024

  /** What one stream holds on probation besides its headers: gRPC's own state. */
  private val StreamStateBytes: Long = 3L * 1024

  /** What a connection on probation holds with no stream. */
  private val ConnectionBytes: Long = 100L * 1024

  /** Heap per byte read at the highest rate found: a 9-byte HEADERS refused past the stream limit,
    * whose id is not consumed, so that the next can use it too, each answered by a reset that a
    * client that does not read leaves queued, 232 B. An upper bound, since the kernel's socket
    * buffer takes the first resets.
    */
  private val HeapPerByteRead: Long = 26L

  /** The same with TLS, whose queue costs about 100 B per reset. */
  private val HeapPerByteReadTls: Long = 11L

  /** What waits for a thread gets one part in this many of the heap. */
  private val BacklogHeapParts: Long = 20L

  /** How many bytes of a connection on probation are read: a first call's headers at their limit,
    * and room for what comes with them.
    */
  def readBudget(maxHeaderBytes: Int): Long = maxHeaderBytes.toLong + ReadMarginBytes

  /** What a call holds in the heap from its first read to its end: gRPC's state and its headers in
    * their worst shape, one-byte names with one-byte values, 1927 pairs, 108 KiB at the 64 KiB
    * limit.
    */
  def callBytes(maxHeaderBytes: Int): Long = maxHeaderBytes.toLong * 5 / 3 + StreamStateBytes

  /** What a connection on probation can hold in the heap: [[MaxStreams]] of [[callBytes]], the read
    * budget and its slack at the highest rate found, and the connection itself, with TLS if it has
    * it. Computed from field counts, not measured: a heap histogram should replace these constants.
    * Not what a stalled pool's calls hold once their connection has gone: [[maxBacklog]] counts
    * those.
    */
  def heldBytes(maxHeaderBytes: Int, tls: Boolean): Long =
    val rate = if tls then HeapPerByteReadTls else HeapPerByteRead
    MaxStreams * callBytes(maxHeaderBytes) +
      (readBudget(maxHeaderBytes) + ReadSlackBytes) * rate + ConnectionBytes +
      (if tls then ConnectionBudget.TlsBytes else 0L)

  /** How many tasks may wait for a Flight thread before a new connection is closed: as many as the
    * pool has threads, and no more than fit a twentieth of the heap at [[callBytes]] each.
    *
    * It counts tasks, not calls. gRPC queues a call as one task, a `SerializingExecutor` that runs
    * its lookup, its start and its later events in turn, and that task keeps the call's headers
    * until a thread takes it, whatever became of its client. A client that leaves adds a second
    * task (`ContextCloser`), and the first stays. So a queue this long holds at most this many
    * calls.
    *
    * With every thread busy, a flood's new connections are then closed and its calls stop growing,
    * at this many plus what connections accepted before can still add, 17 calls per place. At 2 GiB
    * with TLS that is 512 plus 17 times 76, 1804 calls at about 110 KiB, 193 MiB, about a tenth of
    * the heap, and 55 MiB more for the calls the 512 threads hold. Computed, not measured. A
    * connection that has shown a credential is not limited here, and its calls count in the queue.
    */
  def maxBacklog(flightThreads: Int, maxHeaderBytes: Int, maxHeapBytes: Long): Int =
    ConnectionBudget.fit(flightThreads, callBytes(maxHeaderBytes), maxHeapBytes, BacklogHeapParts)
