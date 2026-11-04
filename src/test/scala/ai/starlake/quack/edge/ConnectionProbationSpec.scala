package ai.starlake.quack.edge

import io.grpc.{Attributes, Grpc}
import io.grpc.netty.{GrpcHttp2ConnectionHandler, InternalProtocolNegotiator}
import io.netty.buffer.Unpooled
import io.netty.channel.{
  ChannelHandler,
  ChannelHandlerContext,
  ChannelInboundHandlerAdapter,
  ChannelOutboundHandlerAdapter
}
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http2.{
  DefaultHttp2Connection,
  DefaultHttp2ConnectionDecoder,
  DefaultHttp2ConnectionEncoder,
  DefaultHttp2FrameReader,
  DefaultHttp2FrameWriter,
  Http2Exception,
  Http2Settings
}
import io.netty.util.AsciiString
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

/** What [[ConnectionProbation]] does to one Netty channel, where a socket could not show it: which
  * reads get past it, and what it leaves Netty's limit on streams at. The real wire is
  * `FlightEdgeLimitsSpec`'s.
  */
class ConnectionProbationSpec extends AnyFlatSpec with Matchers:

  private final class Grpc2Handler private (
      connection: DefaultHttp2Connection,
      encoder: DefaultHttp2ConnectionEncoder
  ) extends GrpcHttp2ConnectionHandler(
        null,
        new DefaultHttp2ConnectionDecoder(connection, encoder, new DefaultHttp2FrameReader()),
        encoder,
        new Http2Settings(),
        null
      ):
    def this(connection: DefaultHttp2Connection) =
      this(connection, new DefaultHttp2ConnectionEncoder(connection, new DefaultHttp2FrameWriter()))

  /** The negotiator's own handler, standing for what follows it in the pipeline: as
    * `Http2ConnectionHandler` and `SslHandler` do, it asks for a read after every read while
    * autoRead is off, whatever that read would bring. A pipeline without this proves nothing about
    * not reading, as autoRead alone would stop everything here.
    */
  private final class AsksToRead extends ChannelInboundHandlerAdapter:
    var asked                                                          = 0
    override def channelReadComplete(ctx: ChannelHandlerContext): Unit =
      if !ctx.channel().config().isAutoRead then
        asked += 1
        ctx.read()
      ctx.fireChannelReadComplete()

  /** Next to the socket, it counts the reads that get that far. */
  private final class ReadsAtTheSocket extends ChannelOutboundHandlerAdapter:
    var reads                                           = 0
    override def read(ctx: ChannelHandlerContext): Unit =
      reads += 1
      ctx.read()

  private def probation(
      maxConnections: Int = 8,
      timeoutMs: Long = 60000L,
      readBudget: Long = 1000L,
      behind: () => ChannelHandler = () => new AsksToRead,
      backlog: () => Int = () => 0,
      maxBacklog: Int = 4
  ) =
    val inner = new InternalProtocolNegotiator.ProtocolNegotiator:
      def scheme(): AsciiString                                           = AsciiString.of("http")
      def newHandler(handler: GrpcHttp2ConnectionHandler): ChannelHandler = behind()
      def close(): Unit                                                   = ()
    new ConnectionProbation(inner, maxConnections, timeoutMs, readBudget, backlog, maxBacklog)

  private def attributesOf(channel: EmbeddedChannel): Attributes =
    Attributes
      .newBuilder()
      .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, channel.remoteAddress())
      .set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, channel.localAddress())
      .build()

  private def open(on: ConnectionProbation): (EmbeddedChannel, DefaultHttp2Connection) =
    val http2 = new DefaultHttp2Connection(true)
    (new EmbeddedChannel(on.newHandler(new Grpc2Handler(http2))), http2)

  private def bytes(count: Int) = Unpooled.wrappedBuffer(new Array[Byte](count))

  private def onProbation(channel: EmbeddedChannel): Boolean =
    channel.pipeline().names().asScala.exists(_.contains("ConnectionProbation$Connection"))

  /** A channel with [[ReadsAtTheSocket]] next to the socket and [[AsksToRead]] behind probation. */
  private def counting(readBudget: Long) =
    val socket  = new ReadsAtTheSocket
    val behind  = new AsksToRead
    val on      = probation(readBudget = readBudget, behind = () => behind)
    val handler = on.newHandler(new Grpc2Handler(new DefaultHttp2Connection(true)))
    (on, new EmbeddedChannel(socket, handler), socket, behind)

  "a connection on probation" should "pass its stream limit to Netty before any frame, and have the negotiator's handler" in {
    val (channel, http2) = open(probation())
    try
      http2.remote().maxActiveStreams() shouldBe ConnectionProbation.MaxStreams
      channel.pipeline().get(classOf[AsksToRead]) should not be null
    finally channel.finishAndReleaseAll()
  }

  it should "go when proven: no limit on streams, no timer left" in {
    val on               = probation(timeoutMs = 1000L)
    val (channel, http2) = open(on)
    try
      onProbation(channel) shouldBe true
      on.prove(attributesOf(channel))
      channel.runPendingTasks()
      http2.remote().maxActiveStreams() shouldBe Int.MaxValue
      channel.pipeline().get(classOf[AsksToRead]) should not be null
      onProbation(channel) shouldBe false
      channel.advanceTimeBy(2, TimeUnit.SECONDS)
      channel.runScheduledPendingTasks()
      channel.isOpen shouldBe true
    finally channel.finishAndReleaseAll()
  }

  it should "be closed at the timeout, and not before" in {
    val (channel, _) = open(probation(timeoutMs = 1000L))
    try
      channel.advanceTimeBy(999, TimeUnit.MILLISECONDS)
      channel.runScheduledPendingTasks()
      channel.isOpen shouldBe true
      channel.advanceTimeBy(1, TimeUnit.MILLISECONDS)
      channel.runScheduledPendingTasks()
      channel.isOpen shouldBe false
    finally channel.finishAndReleaseAll()
  }

  it should "not take a proof from a connection with another local address" in {
    // The same remote address and port can reach a listener on two addresses.
    val on               = probation()
    val (channel, http2) = open(on)
    try
      val other = Attributes
        .newBuilder()
        .set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, channel.remoteAddress())
        .set(Grpc.TRANSPORT_ATTR_LOCAL_ADDR, new InetSocketAddress("127.0.0.1", 1))
        .build()
      on.prove(other)
      channel.runPendingTasks()
      http2.remote().maxActiveStreams() shouldBe ConnectionProbation.MaxStreams
      onProbation(channel) shouldBe true
    finally channel.finishAndReleaseAll()
  }

  // ---- the read budget -----------------------------------------------------------------------

  "the read budget" should "let every read through until it is spent" in {
    val (_, channel, socket, behind) = counting(readBudget = 100L)
    try
      val before = socket.reads
      channel.read()
      socket.reads shouldBe before + 1
      channel.writeInbound(bytes(99))
      channel.config().isAutoRead shouldBe true
      behind.asked shouldBe 0
    finally channel.finishAndReleaseAll()
  }

  it should "stop every read once it is spent, though the handlers behind ask for them" in {
    val (_, channel, socket, behind) = counting(readBudget = 100L)
    try
      channel.writeInbound(bytes(60))
      channel.writeInbound(bytes(60))
      channel.config().isAutoRead shouldBe false
      channel.isOpen shouldBe true
      // What the read under way holds still goes on: that is the slack in the bound.
      channel.inboundMessages().size shouldBe 2
      val reads = socket.reads
      val asked = behind.asked
      asked should be > 0
      (1 to 3).foreach(_ => channel.pipeline().fireChannelReadComplete())
      channel.read()
      behind.asked shouldBe asked + 3
      socket.reads shouldBe reads
    finally channel.finishAndReleaseAll()
  }

  it should "count only what it is given to read, whatever the buffer's capacity" in {
    val (_, channel, _, _) = counting(readBudget = 100L)
    try
      channel.writeInbound(Unpooled.buffer(4096).writeZero(99))
      channel.config().isAutoRead shouldBe true
      channel.writeInbound(bytes(1))
      channel.config().isAutoRead shouldBe false
    finally channel.finishAndReleaseAll()
  }

  it should "let reads through again when proven, the first of them from turning autoRead on" in {
    val (on, channel, socket, _) = counting(readBudget = 100L)
    try
      channel.writeInbound(bytes(100))
      channel.config().isAutoRead shouldBe false
      val reads = socket.reads
      on.prove(attributesOf(channel))
      channel.runPendingTasks()
      channel.config().isAutoRead shouldBe true
      socket.reads shouldBe reads + 1
      onProbation(channel) shouldBe false
      channel.read()
      socket.reads shouldBe reads + 2
      channel.writeInbound(bytes(1000))
      channel.config().isAutoRead shouldBe true
    finally channel.finishAndReleaseAll()
  }

  it should "be closed at the timeout while it reads nothing" in {
    val (channel, _) = open(probation(timeoutMs = 1000L, readBudget = 100L))
    try
      channel.writeInbound(bytes(100))
      channel.config().isAutoRead shouldBe false
      channel.advanceTimeBy(1, TimeUnit.SECONDS)
      channel.runScheduledPendingTasks()
      channel.isOpen shouldBe false
    finally channel.finishAndReleaseAll()
  }

  // ---- the total of streams ------------------------------------------------------------------

  /** Creates one more stream as a client's HEADERS does, and closes it, as a client's reset does.
    */
  private def createAndClose(http2: DefaultHttp2Connection): Unit =
    http2.remote().createStream(http2.remote().incrementAndGetNextStreamId(), false).close()

  "the total of streams" should "allow 16 in all, whatever became of them, and not close" in {
    val (channel, http2) = open(probation())
    try
      (1 to ConnectionProbation.MaxTotalStreams).foreach { _ =>
        http2.remote().maxActiveStreams() shouldBe ConnectionProbation.MaxStreams
        createAndClose(http2)
      }
      http2.numActiveStreams() shouldBe 0
      http2.remote().maxActiveStreams() shouldBe 0
      an[Http2Exception] should be thrownBy createAndClose(http2)
      channel.isOpen shouldBe true
    finally channel.finishAndReleaseAll()
  }

  it should "come back at the next stream after the ACK of the first SETTINGS" in {
    val (channel, http2) = open(probation())
    try
      (1 to ConnectionProbation.MaxTotalStreams).foreach(_ => createAndClose(http2))
      // Netty applies a SETTINGS value on its ACK.
      http2.remote().maxActiveStreams(ConnectionProbation.MaxStreams)
      createAndClose(http2)
      http2.remote().maxActiveStreams() shouldBe 0
    finally channel.finishAndReleaseAll()
  }

  it should "go when proven: it does not come back" in {
    val on               = probation()
    val (channel, http2) = open(on)
    try
      (1 to ConnectionProbation.MaxTotalStreams).foreach(_ => createAndClose(http2))
      on.prove(attributesOf(channel))
      channel.runPendingTasks()
      http2.remote().maxActiveStreams() shouldBe Int.MaxValue
      (1 to ConnectionProbation.MaxTotalStreams).foreach(_ => createAndClose(http2))
      http2.remote().maxActiveStreams() shouldBe Int.MaxValue
    finally channel.finishAndReleaseAll()
  }

  /** The count drops exactly once: with room for one, a second connection tells. */
  private def roomForOne(on: ConnectionProbation): Boolean =
    val (channel, _) = open(on)
    try channel.isOpen
    finally channel.finishAndReleaseAll()

  "the limit on connections" should "close one past it, with no negotiator's handler" in {
    val on          = probation(maxConnections = 1)
    val (first, _)  = open(on)
    val (second, _) = open(on)
    try
      first.isOpen shouldBe true
      second.isOpen shouldBe false
      second.pipeline().get(classOf[AsksToRead]) shouldBe null
    finally
      first.finishAndReleaseAll()
      second.finishAndReleaseAll()
  }

  it should "count a connection once, whichever of proof, timeout and close comes first" in {
    // Proof, then the same connection's close, each freeing a place would leave room for two.
    val on         = probation(maxConnections = 2)
    val (first, _) = open(on)
    on.prove(attributesOf(first))
    first.runPendingTasks()
    first.finishAndReleaseAll()
    val (second, _) = open(on)
    val (third, _)  = open(on)
    val (fourth, _) = open(on)
    try
      second.isOpen shouldBe true
      third.isOpen shouldBe true
      fourth.isOpen shouldBe false
    finally
      second.finishAndReleaseAll()
      third.finishAndReleaseAll()
      fourth.finishAndReleaseAll()
  }

  it should "give a place back when a connection closes" in {
    val on         = probation(maxConnections = 1)
    val (first, _) = open(on)
    first.finishAndReleaseAll()
    roomForOne(on) shouldBe true
  }

  it should "give a place back at the timeout" in {
    val on         = probation(maxConnections = 1, timeoutMs = 1000L)
    val (first, _) = open(on)
    first.advanceTimeBy(1, TimeUnit.SECONDS)
    first.runScheduledPendingTasks()
    first.isOpen shouldBe false
    roomForOne(on) shouldBe true
  }

  // ---- what waits for a thread ---------------------------------------------------------------

  "the limit on what waits for a thread" should "close a new connection past it at once, with no negotiator's handler, no timer and no place" in {
    var waiting      = 5
    val on           = probation(maxConnections = 1, backlog = () => waiting, maxBacklog = 4)
    val (refused, _) = open(on)
    try
      refused.isOpen shouldBe false
      refused.pipeline().get(classOf[AsksToRead]) shouldBe null
      refused.runScheduledPendingTasks() shouldBe -1L
    finally refused.finishAndReleaseAll()
    // Room for one: had the refused one taken a place, this one would not get it.
    waiting = 0
    roomForOne(on) shouldBe true
  }

  it should "take a new connection at the limit" in {
    roomForOne(probation(backlog = () => 4, maxBacklog = 4)) shouldBe true
  }

  it should "take the next one when what waits falls" in {
    var waiting = 100
    val on      = probation(backlog = () => waiting, maxBacklog = 4)
    roomForOne(on) shouldBe false
    waiting = 5
    roomForOne(on) shouldBe false
    waiting = 4
    roomForOne(on) shouldBe true
  }

  it should "leave a connection already on probation to its own limits and its proof" in {
    var waiting          = 0
    val on               = probation(backlog = () => waiting, maxBacklog = 4)
    val (channel, http2) = open(on)
    try
      waiting = 100
      channel.writeInbound(bytes(10))
      createAndClose(http2)
      channel.isOpen shouldBe true
      onProbation(channel) shouldBe true
      on.prove(attributesOf(channel))
      channel.runPendingTasks()
      http2.remote().maxActiveStreams() shouldBe Int.MaxValue
      channel.isOpen shouldBe true
    finally channel.finishAndReleaseAll()
  }
