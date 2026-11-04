package ai.starlake.quack.edge

import io.grpc.{Context, Metadata, ServerStreamTracer}
import org.apache.arrow.flight.grpc.MetadataAdapter

import java.util.concurrent.TimeoutException

/** A stream with no credential header may send at most [[MaxBytes]] of message bytes, on any
  * connection: what such a call needs fits well under it (a command of at most
  * [[FlightProducerImpl.MaxAnonymousCommandBytes]]), and gRPC keeps a complete message until the
  * client half closes. Past that the stream is reset, before a large message accumulates or is
  * parsed.
  *
  * The count comes from `inboundWireSize`, which gRPC's deframer calls as bytes reach a message's
  * body, on the connection's event loop. gRPC has no call to reset a stream, but it resets one
  * whose context passed its deadline (`ServerImpl`'s ServerStreamCancellationListener, added once
  * the interceptors have returned). So such a stream's context gets a cancellable parent, cancelled
  * with a timeout as its cause. No legitimate client sees that reset.
  *
  * Compressed messages never get this far with no credential: [[AnonymousCallGuard]] refuses them,
  * as the limit counts wire bytes and 8 KiB of gzip inflates to 8 MiB.
  */
object AnonymousStreamLimit:
  val MaxBytes: Long = 8192L

  private val PastLimit = new TimeoutException("a stream with no credential sent too much")

  /** Keeps nothing of the `Metadata` it is given, which holds up to the header limit. */
  val TracerFactory: ServerStreamTracer.Factory = new ServerStreamTracer.Factory:
    override def newServerStreamTracer(
        fullMethodName: String,
        headers: Metadata
    ): ServerStreamTracer =
      if FlightEdgeServer.authHeaderOf(new MetadataAdapter(headers)).isDefined then Unlimited
      else new Limited

  private object Unlimited extends ServerStreamTracer

  /** Everything here runs on the connection's event loop. */
  private final class Limited extends ServerStreamTracer:
    private var parent: Context.CancellableContext = null
    private var seen                               = 0L

    override def filterContext(context: Context): Context =
      parent = context.withCancellation()
      parent

    override def inboundWireSize(bytes: Long): Unit =
      seen += bytes
      if seen > MaxBytes && parent != null then parent.cancel(PastLimit)
