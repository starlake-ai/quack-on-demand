package ai.starlake.quack.edge

import io.grpc.{Metadata, ServerCall, ServerCallHandler, ServerInterceptor, Status}
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
  * [[FlightProducerImpl]] judges that part.
  *
  * At most `maxCalls` of those run at once, each holding at most its headers
  * (`PROXY_MAX_HEADER_BYTES`) and its message (`PROXY_MAX_INBOUND_MESSAGE_BYTES`, in Netty's own
  * budget), whose command may only ask for little
  * ([[FlightProducerImpl.MaxAnonymousCommandBytes]]). A flood can hold them all, which refuses only
  * other calls with no credentials.
  *
  * An interceptor and not an Arrow middleware: a middleware hears only of a call the server closes,
  * and a client that leaves a call must give its place back.
  */
final class AnonymousCallGuard(maxCalls: Int) extends ServerInterceptor:
  private val inFlight = new AtomicInteger(0)

  override def interceptCall[ReqT, RespT](
      call: ServerCall[ReqT, RespT],
      headers: Metadata,
      next: ServerCallHandler[ReqT, RespT]
  ): ServerCall.Listener[ReqT] =
    if FlightEdgeServer.authHeaderOf(new MetadataAdapter(headers)).isDefined then
      next.startCall(call, headers)
    else if !AnonymousCallGuard.isOpen(call.getMethodDescriptor.getFullMethodName) then
      refuse(call, Status.UNAUTHENTICATED.withDescription("credentials required"))
    else if inFlight.incrementAndGet() > maxCalls then
      inFlight.decrementAndGet()
      refuse(call, Status.UNAVAILABLE.withDescription("too many calls with no credentials; retry"))
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
      override def onMessage(message: ReqT): Unit = listener.onMessage(message)
      override def onHalfClose(): Unit            = listener.onHalfClose()
      override def onReady(): Unit                = listener.onReady()
      override def onCancel(): Unit               =
        release()
        listener.onCancel()
      override def onComplete(): Unit =
        release()
        listener.onComplete()

object AnonymousCallGuard:
  private val Open = Set(FlightMethod.GET_FLIGHT_INFO, FlightMethod.DO_GET)

  private def isOpen(fullMethodName: String): Boolean =
    Open(FlightMethod.fromProtocol(fullMethodName))
