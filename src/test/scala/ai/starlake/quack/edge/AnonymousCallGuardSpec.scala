package ai.starlake.quack.edge

import io.grpc.{Metadata, MethodDescriptor, ServerCall, ServerCallHandler, Status}
import org.apache.arrow.flight.impl.FlightServiceGrpc
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{ByteArrayInputStream, InputStream}

/** What [[AnonymousCallGuard]] does with a call, over fake gRPC objects. The real wire is
  * `FlightEdgeLimitsSpec`'s.
  */
class AnonymousCallGuardSpec extends AnyFlatSpec with Matchers:

  private val getFlightInfo = FlightServiceGrpc.getGetFlightInfoMethod.getFullMethodName
  private val doGet         = FlightServiceGrpc.getDoGetMethod.getFullMethodName

  private val closedMethods = List(
    FlightServiceGrpc.getHandshakeMethod,
    FlightServiceGrpc.getListFlightsMethod,
    FlightServiceGrpc.getGetSchemaMethod,
    FlightServiceGrpc.getPollFlightInfoMethod,
    FlightServiceGrpc.getDoPutMethod,
    FlightServiceGrpc.getDoActionMethod,
    FlightServiceGrpc.getListActionsMethod,
    FlightServiceGrpc.getDoExchangeMethod
  ).map(_.getFullMethodName)

  private object Bytes extends MethodDescriptor.Marshaller[Array[Byte]]:
    def stream(value: Array[Byte]): InputStream = new ByteArrayInputStream(value)
    def parse(stream: InputStream): Array[Byte] = stream.readAllBytes()

  private final class FakeCall(method: String) extends ServerCall[Array[Byte], Array[Byte]]:
    var closed: List[Status] = Nil
    private val descriptor   = MethodDescriptor
      .newBuilder[Array[Byte], Array[Byte]]()
      .setType(MethodDescriptor.MethodType.UNARY)
      .setFullMethodName(method)
      .setRequestMarshaller(Bytes)
      .setResponseMarshaller(Bytes)
      .build()
    def request(numMessages: Int): Unit                 = ()
    def sendHeaders(headers: Metadata): Unit            = ()
    def sendMessage(message: Array[Byte]): Unit         = ()
    def close(status: Status, trailers: Metadata): Unit = closed = status :: closed
    def isCancelled(): Boolean                          = false
    def getMethodDescriptor(): MethodDescriptor[Array[Byte], Array[Byte]] = descriptor

  /** Records the calls that reach it and what their listener hears. */
  private final class FakeHandler(fails: Boolean = false)
      extends ServerCallHandler[Array[Byte], Array[Byte]]:
    var started    = 0
    var messages   = 0
    var halfCloses = 0
    var readies    = 0
    var cancelled  = 0
    var completed  = 0
    def startCall(
        call: ServerCall[Array[Byte], Array[Byte]],
        headers: Metadata
    ): ServerCall.Listener[Array[Byte]] =
      if fails then throw new IllegalStateException("handler failed")
      started += 1
      new ServerCall.Listener[Array[Byte]]:
        override def onMessage(message: Array[Byte]): Unit = messages += 1
        override def onHalfClose(): Unit                   = halfCloses += 1
        override def onReady(): Unit                       = readies += 1
        override def onCancel(): Unit                      = cancelled += 1
        override def onComplete(): Unit                    = completed += 1

  private def header(name: String, value: String): Metadata =
    val headers = new Metadata()
    headers.put(Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER), value)
    headers

  private def admit(
      guard: AnonymousCallGuard,
      handler: FakeHandler,
      method: String = getFlightInfo,
      headers: Metadata = new Metadata()
  ): (FakeCall, ServerCall.Listener[Array[Byte]]) =
    val call = new FakeCall(method)
    (call, guard.interceptCall(call, headers, handler))

  private def codes(call: FakeCall) = call.closed.map(_.getCode)

  "the guard" should "refuse a call with no credentials on any other method, unheard by the handler" in {
    val guard   = new AnonymousCallGuard(8)
    val handler = new FakeHandler
    closedMethods.foreach { method =>
      val (call, _) = admit(guard, handler, method)
      withClue(method)(codes(call) shouldBe List(Status.Code.UNAUTHENTICATED))
    }
    handler.started shouldBe 0
  }

  it should "pass a call with no credentials on GetFlightInfo and on DoGet" in {
    val guard   = new AnonymousCallGuard(8)
    val handler = new FakeHandler
    List(getFlightInfo, doGet).foreach { method =>
      val (call, _) = admit(guard, handler, method)
      withClue(method)(call.closed shouldBe empty)
    }
    handler.started shouldBe 2
  }

  it should "take Authorization and x-qod-authorization alike as a credential" in {
    val guard   = new AnonymousCallGuard(8)
    val handler = new FakeHandler
    val doPut   = FlightServiceGrpc.getDoPutMethod.getFullMethodName
    List("Authorization", "x-qod-authorization").foreach { name =>
      val (call, _) = admit(guard, handler, doPut, header(name, "Basic eDp5"))
      withClue(name)(call.closed shouldBe empty)
    }
    handler.started shouldBe 2
  }

  it should "not count a credentialed call, and pass it past the limit" in {
    val guard    = new AnonymousCallGuard(1)
    val handler  = new FakeHandler
    val (one, _) = admit(guard, handler)
    one.closed shouldBe empty
    val credentialed =
      (1 to 3).map(_ => admit(guard, handler, headers = header("Authorization", "Bearer abc")))
    credentialed.foreach((call, _) => call.closed shouldBe empty)
    handler.started shouldBe 4
    // Neither took a place, nor do they give one back when they end.
    credentialed.foreach((_, listener) => listener.onComplete())
    codes(admit(guard, handler)._1) shouldBe List(Status.Code.UNAVAILABLE)
  }

  it should "refuse a call with no credentials past the limit, on either method" in {
    val guard   = new AnonymousCallGuard(2)
    val handler = new FakeHandler
    admit(guard, handler)._1.closed shouldBe empty
    admit(guard, handler, doGet)._1.closed shouldBe empty
    List(getFlightInfo, doGet).foreach(method =>
      codes(admit(guard, handler, method)._1) shouldBe List(Status.Code.UNAVAILABLE)
    )
    handler.started shouldBe 2
  }

  it should "not hold a place for a refused call" in {
    val guard    = new AnonymousCallGuard(1)
    val handler  = new FakeHandler
    val (_, one) = admit(guard, handler)
    (1 to 5).foreach(_ => codes(admit(guard, handler)._1) shouldBe List(Status.Code.UNAVAILABLE))
    one.onComplete()
    admit(guard, handler)._1.closed shouldBe empty
  }

  it should "take a place back on onCancel and on onComplete, and forward both" in {
    val guard   = new AnonymousCallGuard(1)
    val handler = new FakeHandler
    val endings = List[ServerCall.Listener[Array[Byte]] => Unit](_.onCancel(), _.onComplete())
    endings.foreach { end =>
      val (call, listener) = admit(guard, handler)
      call.closed shouldBe empty
      codes(admit(guard, handler)._1) shouldBe List(Status.Code.UNAVAILABLE)
      end(listener)
    }
    handler.cancelled shouldBe 1
    handler.completed shouldBe 1
    admit(guard, handler)._1.closed shouldBe empty
  }

  it should "pass a counted call's events on to the handler's listener" in {
    val handler       = new FakeHandler
    val (_, listener) = admit(new AnonymousCallGuard(1), handler)
    listener.onMessage(Array.emptyByteArray)
    listener.onHalfClose()
    listener.onReady()
    (handler.messages, handler.halfCloses, handler.readies) shouldBe (1, 1, 1)
  }

  it should "give a place back once when a call ends twice" in {
    val guard            = new AnonymousCallGuard(2)
    val handler          = new FakeHandler
    val (_, listener)    = admit(guard, handler)
    val (_, stillInside) = admit(guard, handler)
    listener.onCancel()
    listener.onComplete()
    listener.onCancel()
    // One place is free and one is held: a second give-back would have freed two.
    admit(guard, handler)._1.closed shouldBe empty
    codes(admit(guard, handler)._1) shouldBe List(Status.Code.UNAVAILABLE)
    stillInside.onComplete()
  }

  it should "give the place back when the handler throws, and let the exception through" in {
    val guard   = new AnonymousCallGuard(1)
    val failing = new FakeHandler(fails = true)
    (1 to 2).foreach(_ => an[IllegalStateException] should be thrownBy admit(guard, failing))
    val working = new FakeHandler
    admit(guard, working)._1.closed shouldBe empty
    working.started shouldBe 1
  }
