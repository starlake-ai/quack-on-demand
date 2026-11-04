package ai.starlake.quack.edge

import io.grpc.{Attributes, Grpc}
import io.grpc.netty.{GrpcHttp2ConnectionHandler, InternalProtocolNegotiator}
import io.netty.channel.{
  Channel,
  ChannelFutureListener,
  ChannelHandler,
  ChannelHandlerContext,
  ChannelInboundHandlerAdapter
}
import io.netty.handler.codec.http2.Http2Settings
import io.netty.util.AsciiString
import io.netty.util.concurrent.ScheduledFuture

import java.net.SocketAddress
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

/** Bounds what a connection can hold until it has shown a working credential. gRPC keeps a stream
  * it answered, half open and with its headers, until the client ends it, and it closes some
  * streams itself before any interceptor runs (an unknown method, an unknown `grpc-encoding`, an
  * HTTP-level refusal), so nothing that looks at calls can bound them, and a client with no
  * credentials could fill the heap with streams it never ended.
  *
  * A connection is on probation from the moment it is accepted until a call on it passes
  * `headerAuth` with a credential ([[prove]], which [[AnonymousCallGuard]] calls: Arrow's adapter
  * runs before it, so a credential that fails never gets there). On probation it holds at most
  * [[ConnectionProbation.MaxStreams]] open streams, half open ones included, so a client that never
  * ends its streams only blocks itself; at most `maxConnections` connections are on probation, and
  * one past that is closed at once, before any TLS handshake; and after `timeoutMs` it is closed,
  * whatever its streams. It is not read while it cannot be written: Netty answers each stream past
  * the limit with a reset that nothing counts (neither gRPC's limit on queued control frames nor
  * its limit on resets sees one for a stream it never created), so a client that stopped reading
  * could otherwise queue them for as long as it stays. A credential ends probation at once. A
  * connection that has shown one has no limit on its streams and does not count, so a flood never
  * touches it and a large deployment meets no new ceiling.
  *
  * This internal gRPC API because nothing public binds a client that never acknowledges SETTINGS:
  * `maxConcurrentCallsPerConnection` reaches Netty as a SETTINGS value, which Netty applies on the
  * client's ACK. `newHandler` is the only place that hands code the connection's handler before any
  * frame is read. `FlightEdgeLimitsSpec` pins it, with a client that never sends that ACK.
  */
final class ConnectionProbation(
    inner: InternalProtocolNegotiator.ProtocolNegotiator,
    maxConnections: Int,
    timeoutMs: Long
) extends InternalProtocolNegotiator.ProtocolNegotiator:

  /** Holds only connections on probation, so a proven connection pays one failed lookup per call.
    */
  private val onProbation = new ConcurrentHashMap[SocketAddress, Connection]()
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
    val address = attributes.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR)
    if address != null then
      val connection = onProbation.get(address)
      if connection != null then connection.prove()

  /** Sits in the pipeline for as long as the connection is on probation, ahead of the inner
    * negotiator's handler, which it adds behind itself so that the event starting the negotiation
    * reaches that one.
    */
  private final class Connection(grpcHandler: GrpcHttp2ConnectionHandler)
      extends ChannelInboundHandlerAdapter:
    private val ended = new AtomicBoolean(false)

    // Set before the map publishes this connection to the pool's threads.
    private var channel: Channel       = null
    private var address: SocketAddress = null

    /** Read and written on the connection's event loop only. */
    private var timer: ScheduledFuture[?] = null

    /** Over the limit it closes, and neither TLS nor HTTP/2 ever start. */
    override def handlerAdded(ctx: ChannelHandlerContext): Unit =
      if held.incrementAndGet() > maxConnections then
        held.decrementAndGet()
        ctx.close()
      else
        channel = ctx.channel()
        address = channel.remoteAddress()
        onProbation.put(address, this)
        val expiry: Runnable = () => expire()
        timer = channel.eventLoop().schedule(expiry, timeoutMs, TimeUnit.MILLISECONDS)
        val closed: ChannelFutureListener = _ =>
          end()
          timer.cancel(false)
        channel.closeFuture().addListener(closed)
        ctx.pipeline().addAfter(ctx.name(), null, inner.newHandler(grpcHandler))

    override def channelWritabilityChanged(ctx: ChannelHandlerContext): Unit =
      ctx.channel().config().setAutoRead(ctx.channel().isWritable)
      ctx.fireChannelWritabilityChanged()

    def prove(): Unit =
      if end() then channel.eventLoop().execute(() => lift())

    /** True for the first of proof, expiry and close: the count drops exactly once. */
    private def end(): Boolean =
      val first = ended.compareAndSet(false, true)
      if first then
        onProbation.remove(address, this)
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
        grpcHandler.connection().remote().maxActiveStreams(Int.MaxValue)
        val ctx = channel.pipeline().context(grpcHandler)
        if ctx != null then
          val settings = new Http2Settings().maxConcurrentStreams(Int.MaxValue.toLong)
          grpcHandler.encoder().writeSettings(ctx, settings, ctx.newPromise())
          ctx.flush()
        channel.pipeline().remove(this)
        channel.config().setAutoRead(true)

object ConnectionProbation:

  /** The most open streams of a connection on probation. Also what the first SETTINGS advertises.
    */
  val MaxStreams: Int = 8

  /** gRPC closes a connection past this many RST_STREAMs from its client in a window. On any
    * connection: a client's resets cost work, and a credential does not pay for them.
    */
  val MaxResetsPerWindow: Int = 1000
  val ResetWindowSec: Int     = 10

  /** What one stream holds on probation besides its headers: gRPC's own state. */
  private val StreamStateBytes: Long = 3L * 1024

  /** What a connection on probation holds with no stream. */
  private val ConnectionBytes: Long = 100L * 1024

  /** The most a connection on probation can hold: at most [[MaxStreams]] streams, each with its
    * header list (`maxHeaderBytes`, an eighth more once decoded), gRPC's state for it and a message
    * up to [[AnonymousStreamLimit.MaxBytes]].
    */
  def heldBytes(maxHeaderBytes: Int, tls: Boolean): Long =
    val stream = maxHeaderBytes.toLong + maxHeaderBytes / 8 + StreamStateBytes +
      AnonymousStreamLimit.MaxBytes
    MaxStreams * stream + ConnectionBytes + (if tls then ConnectionBudget.TlsBytes else 0L)
