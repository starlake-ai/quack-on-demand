package ai.starlake.quack.edge

import io.grpc.{Context, Metadata, ServerStreamTracer}

import java.util.concurrent.TimeoutException

/** Ends the HTTP/2 stream of a call the edge refuses before reading it. Closing a call only sends
  * its status: gRPC then keeps the stream, half open, until the client ends it, and Arrow's
  * ServerHeaderMiddleware keeps the call's headers in the call's context. So a refused call that
  * its client never ended held its headers and about 2 KiB more for as long as the connection
  * lasted: 30,000 of them with 14 KiB of headers, one connection and no credentials, took a 512 MiB
  * heap to an OutOfMemoryError in 1.1 s. MAX_CONCURRENT_STREAMS is no answer: it binds a client
  * only once the client acknowledges the server's SETTINGS.
  *
  * gRPC has no call to reset a stream, but it resets one whose context passed its deadline
  * (`ServerImpl`'s ServerStreamCancellationListener, added once the interceptors have returned, so
  * after the status they sent). So each stream's context gets a cancellable parent, and [[reset]]
  * cancels that with a timeout as its cause. A client has its answer by then: the status ends the
  * response, and the reset that follows it is a frame on a closed stream. One that stops reading
  * gets cut off: gRPC closes a connection with 10,000 resets waiting to be written.
  */
object RefusedStream:
  private val Parent  = Context.key[Context.CancellableContext]("qod-refused-stream-parent")
  private val Refused = new TimeoutException("call refused")

  /** Installed on the server builder: what makes [[reset]] possible. */
  val TracerFactory: ServerStreamTracer.Factory = new ServerStreamTracer.Factory:
    override def newServerStreamTracer(
        fullMethodName: String,
        headers: Metadata
    ): ServerStreamTracer =
      new ServerStreamTracer:
        override def filterContext(context: Context): Context =
          val parent = context.withCancellation()
          parent.withValue(Parent, parent)

  /** Resets the stream of the call starting on this thread, once its interceptors have returned.
    * Does nothing outside a call.
    */
  def reset(): Unit =
    val parent = Parent.get()
    if parent != null then parent.cancel(Refused)
