package ai.starlake.quack.edge

import io.grpc.{Attributes, Grpc}
import io.grpc.netty.{GrpcHttp2ConnectionHandler, InternalProtocolNegotiator}
import io.netty.buffer.Unpooled
import io.netty.channel.{ChannelHandler, ChannelInboundHandlerAdapter, WriteBufferWaterMark}
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http2.{
  DefaultHttp2Connection,
  DefaultHttp2ConnectionDecoder,
  DefaultHttp2ConnectionEncoder,
  DefaultHttp2FrameReader,
  DefaultHttp2FrameWriter,
  Http2Settings
}
import io.netty.util.AsciiString
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.TimeUnit

/** What [[ConnectionProbation]] does to one Netty channel, where a socket could not show it: a
  * connection that cannot be written is not read, which on a real socket takes buffers full of
  * resets. The real wire is `FlightEdgeLimitsSpec`'s.
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

  /** The negotiator's own handler, which only has to be there. */
  private final class Marker extends ChannelInboundHandlerAdapter

  private def probation(maxConnections: Int = 8, timeoutMs: Long = 60000L) =
    val inner = new InternalProtocolNegotiator.ProtocolNegotiator:
      def scheme(): AsciiString                                           = AsciiString.of("http")
      def newHandler(handler: GrpcHttp2ConnectionHandler): ChannelHandler = new Marker
      def close(): Unit                                                   = ()
    new ConnectionProbation(inner, maxConnections, timeoutMs)

  private def attributesOf(channel: EmbeddedChannel): Attributes =
    Attributes.newBuilder().set(Grpc.TRANSPORT_ATTR_REMOTE_ADDR, channel.remoteAddress()).build()

  private def open(on: ConnectionProbation): (EmbeddedChannel, DefaultHttp2Connection) =
    val http2 = new DefaultHttp2Connection(true)
    (new EmbeddedChannel(on.newHandler(new Grpc2Handler(http2))), http2)

  "a connection on probation" should "not be read while it cannot be written" in {
    val (channel, _) = open(probation())
    try
      channel.config().setWriteBufferWaterMark(new WriteBufferWaterMark(1, 2))
      channel.config().isAutoRead shouldBe true
      channel.write(Unpooled.wrappedBuffer(new Array[Byte](64)))
      channel.isWritable shouldBe false
      channel.config().isAutoRead shouldBe false
      channel.flush()
      channel.isWritable shouldBe true
      channel.config().isAutoRead shouldBe true
    finally channel.finishAndReleaseAll()
  }

  it should "pass its stream limit to Netty before any frame, and have the negotiator's handler" in {
    val (channel, http2) = open(probation())
    try
      http2.remote().maxActiveStreams() shouldBe ConnectionProbation.MaxStreams
      channel.pipeline().get(classOf[Marker]) should not be null
    finally channel.finishAndReleaseAll()
  }

  it should "go when proven: no limit on streams, read again, no timer left" in {
    val on               = probation(timeoutMs = 1000L)
    val (channel, http2) = open(on)
    try
      channel.config().setAutoRead(false)
      on.prove(attributesOf(channel))
      channel.runPendingTasks()
      http2.remote().maxActiveStreams() shouldBe Int.MaxValue
      channel.config().isAutoRead shouldBe true
      channel.pipeline().get(classOf[Marker]) should not be null
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
      second.pipeline().get(classOf[Marker]) shouldBe null
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
