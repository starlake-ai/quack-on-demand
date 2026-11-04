package ai.starlake.quack.edge

import com.google.protobuf.MessageLite
import io.grpc.{Attributes, Metadata, ServerCall, ServerCallHandler, ServerInterceptor, Status}
import org.apache.arrow.flight.FlightMethod
import org.apache.arrow.flight.grpc.MetadataAdapter

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

/** What a call with no credentials may do. gRPC holds a call's headers for as long as its stream
  * stays open and has no limit on streams, and Arrow runs a DoPut on a thread that waits for the
  * first message with no time limit: anonymous clients filled the heap and the container that way.
  * So such a call is refused before any of its messages is read, unless its method is one that
  * server metadata travels on, as some FlightSQL clients (the Arrow Flight SQL ODBC driver behind
  * Power BI and Excel) ask for GetSqlInfo and GetXdbcTypeInfo before they replay the session
  * bearer. Which command such a call carries is only known once its message is read, so
  * [[FlightProducerImpl]] judges that part. ListFlights answers UNIMPLEMENTED, as the producer does
  * with a credential: pyarrow's `wait_for_available()` calls it with no options, and takes only an
  * unavailable or a not implemented error for an answer.
  *
  * At most `maxCalls` of those run at once, each holding at most its headers
  * (`PROXY_MAX_HEADER_BYTES`) and a message of at most
  * [[FlightProducerImpl.MaxAnonymousCommandBytes]] and what wraps it. A longer one is refused as it
  * arrives, parsed once on its way to being dropped and not held until half close. A flood can hold
  * them all, which refuses only other calls with no credentials. A compressed one is refused too:
  * [[ConnectionProbation]]'s read budget counts wire bytes, and 80 KiB of gzip inflates to 80 MiB.
  *
  * A refused call answers its status and its stream then stays half open, with its headers, until
  * its client ends it: [[ConnectionProbation]] bounds how many such streams a client with no
  * credentials can hold, and for how long.
  *
  * A call with a credential that gets here has passed `headerAuth`, since Arrow's adapter runs
  * first and refuses one that fails: `onCredential` hears of it, with the attributes of its
  * connection.
  *
  * An interceptor and not an Arrow middleware: a middleware hears only of a call the server closes,
  * and a client that leaves a call must give its place back.
  */
final class AnonymousCallGuard(maxCalls: Int, onCredential: Attributes => Unit)
    extends ServerInterceptor:
  private val inFlight = new AtomicInteger(0)

  override def interceptCall[ReqT, RespT](
      call: ServerCall[ReqT, RespT],
      headers: Metadata,
      next: ServerCallHandler[ReqT, RespT]
  ): ServerCall.Listener[ReqT] =
    if FlightEdgeServer.authHeaderOf(new MetadataAdapter(headers)).isDefined then
      onCredential(call.getAttributes)
      next.startCall(call, headers)
    else
      val method = FlightMethod.fromProtocol(call.getMethodDescriptor.getFullMethodName)
      if method == FlightMethod.LIST_FLIGHTS then refuse(call, AnonymousCallGuard.NoListing)
      else if !AnonymousCallGuard.Open(method) || AnonymousCallGuard.isCompressed(headers) then
        refuse(call, AnonymousCallGuard.NoCredentials)
      else if inFlight.incrementAndGet() > maxCalls then
        inFlight.decrementAndGet()
        refuse(
          call,
          Status.UNAVAILABLE.withDescription("too many calls with no credentials; retry")
        )
      else counted(call, headers, next)

  private def refuse[ReqT, RespT](
      call: ServerCall[ReqT, RespT],
      status: Status
  ): ServerCall.Listener[ReqT] =
    call.close(status, new Metadata())
    new ServerCall.Listener[ReqT] {}

  private def counted[ReqT, RespT](
      call: ServerCall[ReqT, RespT],
      headers: Metadata,
      next: ServerCallHandler[ReqT, RespT]
  ): ServerCall.Listener[ReqT] =
    val held            = new AtomicBoolean(true)
    def release(): Unit = if held.compareAndSet(true, false) then inFlight.decrementAndGet()
    val listener        =
      try next.startCall(call, headers)
      catch
        case t: Throwable =>
          release()
          throw t
    // Forwarded by hand: from Scala, super.onCancel() of gRPC's SimpleForwardingServerCallListener
    // names its package-private parent and fails with an IllegalAccessError.
    new ServerCall.Listener[ReqT]:
      // gRPC calls a listener one event at a time. Past a refusal only a call's end still counts.
      private var refused = false

      override def onMessage(message: ReqT): Unit =
        if !refused then
          if AnonymousCallGuard.tooLong(message) then
            refused = true
            // A unary call's second message has already closed it, in gRPC's own listener.
            try call.close(AnonymousCallGuard.NoCredentials, new Metadata())
            catch case _: IllegalStateException => ()
          else listener.onMessage(message)
      override def onHalfClose(): Unit = if !refused then listener.onHalfClose()
      override def onReady(): Unit     = if !refused then listener.onReady()
      override def onCancel(): Unit    =
        release()
        listener.onCancel()
      override def onComplete(): Unit =
        release()
        listener.onComplete()

object AnonymousCallGuard:
  private val Open = Set(FlightMethod.GET_FLIGHT_INFO, FlightMethod.DO_GET)

  private val NoCredentials = Status.UNAUTHENTICATED.withDescription("credentials required")

  /** What the producer answers ListFlights with, at no cost in places or threads. */
  private val NoListing = Status.UNIMPLEMENTED.withDescription("Not implemented.")

  /** What a FlightDescriptor or a Ticket adds to the command it carries. */
  private val WrappingBytes = 16

  private def tooLong(message: Any): Boolean = message match
    case proto: MessageLite =>
      proto.getSerializedSize > FlightProducerImpl.MaxAnonymousCommandBytes + WrappingBytes
    case _ => false

  private val Encoding = Metadata.Key.of("grpc-encoding", Metadata.ASCII_STRING_MARSHALLER)

  private def isCompressed(headers: Metadata): Boolean =
    Option(headers.get(Encoding)).exists(_ != "identity")
