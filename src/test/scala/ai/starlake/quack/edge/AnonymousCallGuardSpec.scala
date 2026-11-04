package ai.starlake.quack.edge

import com.google.protobuf.ByteString
import io.grpc.{Attributes, Metadata, MethodDescriptor, ServerCall, ServerCallHandler, Status}
import org.apache.arrow.flight.impl.{Flight, FlightServiceGrpc}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{ByteArrayInputStream, InputStream}

/** What [[AnonymousCallGuard]] does with a call, over fake gRPC objects. The real wire is
  * `FlightEdgeLimitsSpec`'s.
  */
class AnonymousCallGuardSpec extends AnyFlatSpec with Matchers:

  private val getFlightInfo = FlightServiceGrpc.getGetFlightInfoMethod.getFullMethodName
  private val doGet         = FlightServiceGrpc.getDoGetMethod.getFullMethodName

  private val listFlights = FlightServiceGrpc.getListFlightsMethod.getFullMethodName

  private val closedMethods = List(
    FlightServiceGrpc.getHandshakeMethod,
    FlightServiceGrpc.getGetSchemaMethod,
    FlightServiceGrpc.getPollFlightInfoMethod,
    FlightServiceGrpc.getDoPutMethod,
    FlightServiceGrpc.getDoActionMethod,
    FlightServiceGrpc.getListActionsMethod,
    FlightServiceGrpc.getDoExchangeMethod
  ).map(_.getFullMethodName)

  /** A fake call never marshals anything. */
  private def unusedMarshaller[T] = new MethodDescriptor.Marshaller[T]:
    def stream(value: T): InputStream = new ByteArrayInputStream(Array.emptyByteArray)
    def parse(stream: InputStream): T = throw new UnsupportedOperationException

  /** Its requests are any object: bytes where only an event matters, a message where its size does.
    * A second close fails, as on gRPC's own call.
    */
  private final class FakeCall(method: String) extends ServerCall[AnyRef, Array[Byte]]:
    var closed: List[Status] = Nil
    private val descriptor   = MethodDescriptor
      .newBuilder[AnyRef, Array[Byte]]()
      .setType(MethodDescriptor.MethodType.UNARY)
      .setFullMethodName(method)
      .setRequestMarshaller(unusedMarshaller)
      .setResponseMarshaller(unusedMarshaller)
      .build()
    def request(numMessages: Int): Unit                 = ()
    def sendHeaders(headers: Metadata): Unit            = ()
    def sendMessage(message: Array[Byte]): Unit         = ()
    def close(status: Status, trailers: Metadata): Unit =
      if closed.nonEmpty then throw new IllegalStateException("call already closed")
      closed = status :: closed
    def isCancelled(): Boolean                                       = false
    def getMethodDescriptor(): MethodDescriptor[AnyRef, Array[Byte]] = descriptor
    override def getAttributes(): Attributes                         = connection

  /** Records the calls that reach it and what their listener hears. */
  private final class FakeHandler(fails: Boolean = false)
      extends ServerCallHandler[AnyRef, Array[Byte]]:
    var started    = 0
    var messages   = 0
    var halfCloses = 0
    var readies    = 0
    var cancelled  = 0
    var completed  = 0
    def startCall(
        call: ServerCall[AnyRef, Array[Byte]],
        headers: Metadata
    ): ServerCall.Listener[AnyRef] =
      if fails then throw new IllegalStateException("handler failed")
      started += 1
      new ServerCall.Listener[AnyRef]:
        override def onMessage(message: AnyRef): Unit = messages += 1
        override def onHalfClose(): Unit              = halfCloses += 1
        override def onReady(): Unit                  = readies += 1
        override def onCancel(): Unit                 = cancelled += 1
        override def onComplete(): Unit               = completed += 1

  /** What a fake call says of its connection. */
  private val connection =
    Attributes.newBuilder().set(Attributes.Key.create[String]("connection"), "a").build()

  private def guardOf(maxCalls: Int, onCredential: Attributes => Unit = _ => ()) =
    new AnonymousCallGuard(maxCalls, onCredential)

  private def header(name: String, value: String): Metadata =
    val headers = new Metadata()
    headers.put(Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER), value)
    headers

  private def admit(
      guard: AnonymousCallGuard,
      handler: FakeHandler,
      method: String = getFlightInfo,
      headers: Metadata = new Metadata()
  ): (FakeCall, ServerCall.Listener[AnyRef]) =
    val call = new FakeCall(method)
    (call, guard.interceptCall(call, headers, handler))

  private def codes(call: FakeCall) = call.closed.map(_.getCode)

  "the guard" should "refuse a call with no credentials on any other method, unheard by the handler" in {
    val guard   = guardOf(8)
    val handler = new FakeHandler
    closedMethods.foreach { method =>
      val (call, _) = admit(guard, handler, method)
      withClue(method)(codes(call) shouldBe List(Status.Code.UNAUTHENTICATED))
    }
    handler.started shouldBe 0
  }

  it should "pass a call with no credentials on GetFlightInfo and on DoGet" in {
    val guard   = guardOf(8)
    val handler = new FakeHandler
    List(getFlightInfo, doGet).foreach { method =>
      val (call, _) = admit(guard, handler, method)
      withClue(method)(call.closed shouldBe empty)
    }
    handler.started shouldBe 2
  }

  it should "take Authorization and x-qod-authorization alike as a credential" in {
    val guard   = guardOf(8)
    val handler = new FakeHandler
    val doPut   = FlightServiceGrpc.getDoPutMethod.getFullMethodName
    List("Authorization", "x-qod-authorization").foreach { name =>
      val (call, _) = admit(guard, handler, doPut, header(name, "Basic eDp5"))
      withClue(name)(call.closed shouldBe empty)
    }
    handler.started shouldBe 2
  }

  it should "not count a credentialed call, and pass it past the limit" in {
    val guard    = guardOf(1)
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
    val guard   = guardOf(2)
    val handler = new FakeHandler
    admit(guard, handler)._1.closed shouldBe empty
    admit(guard, handler, doGet)._1.closed shouldBe empty
    List(getFlightInfo, doGet).foreach(method =>
      codes(admit(guard, handler, method)._1) shouldBe List(Status.Code.UNAVAILABLE)
    )
    handler.started shouldBe 2
  }

  it should "tell of a credentialed call, with its connection, and of no other" in {
    val heard   = scala.collection.mutable.ListBuffer.empty[Attributes]
    val guard   = guardOf(1, heard += _)
    val handler = new FakeHandler
    val doPut   = FlightServiceGrpc.getDoPutMethod.getFullMethodName

    admit(guard, handler)._1.closed shouldBe empty
    admit(guard, handler)._1.closed should have size 1
    admit(guard, handler, doPut)._1.closed should have size 1
    heard shouldBe empty

    List("Authorization", "x-qod-authorization").foreach(name =>
      admit(guard, handler, doPut, header(name, "Basic eDp5"))
    )
    heard.toList shouldBe List(connection, connection)
  }

  it should "refuse a compressed call with no credentials, holding no place" in {
    val guard     = guardOf(1)
    val handler   = new FakeHandler
    val (call, _) = admit(guard, handler, headers = header("grpc-encoding", "gzip"))
    codes(call) shouldBe List(Status.Code.UNAUTHENTICATED)
    handler.started shouldBe 0

    val (identity, listener) = admit(guard, handler, headers = header("grpc-encoding", "identity"))
    identity.closed shouldBe empty
    listener.onComplete()

    val credentialed = header("grpc-encoding", "gzip")
    credentialed.put(
      Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER),
      "Basic eDp5"
    )
    admit(guard, handler, headers = credentialed)._1.closed shouldBe empty
  }

  it should "not hold a place for a refused call" in {
    val guard    = guardOf(1)
    val handler  = new FakeHandler
    val (_, one) = admit(guard, handler)
    (1 to 5).foreach(_ => codes(admit(guard, handler)._1) shouldBe List(Status.Code.UNAVAILABLE))
    one.onComplete()
    admit(guard, handler)._1.closed shouldBe empty
  }

  it should "take a place back on onCancel and on onComplete, and forward both" in {
    val guard   = guardOf(1)
    val handler = new FakeHandler
    val endings = List[ServerCall.Listener[AnyRef] => Unit](_.onCancel(), _.onComplete())
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
    val (_, listener) = admit(guardOf(1), handler)
    listener.onMessage(Array.emptyByteArray)
    listener.onHalfClose()
    listener.onReady()
    (handler.messages, handler.halfCloses, handler.readies) shouldBe (1, 1, 1)
  }

  it should "answer ListFlights with no credentials as the producer would, holding no place" in {
    val guard   = guardOf(1)
    val handler = new FakeHandler
    admit(guard, handler)._1.closed shouldBe empty
    codes(admit(guard, handler, listFlights)._1) shouldBe List(Status.Code.UNIMPLEMENTED)
    handler.started shouldBe 1
    // Past the limit on calls too, compressed or not, and a credential goes on to the producer.
    codes(admit(guard, handler, listFlights, header("grpc-encoding", "gzip"))._1) shouldBe
      List(Status.Code.UNIMPLEMENTED)
    admit(guard, handler, listFlights, header("Authorization", "Bearer abc"))._1.closed shouldBe
      empty
    handler.started shouldBe 2
  }

  /** Its size is `command + 5`: 2 for its type, 3 for its command's tag and length. */
  private def descriptor(command: Int): Flight.FlightDescriptor =
    Flight.FlightDescriptor
      .newBuilder()
      .setType(Flight.FlightDescriptor.DescriptorType.CMD)
      .setCmd(ByteString.copyFrom(new Array[Byte](command)))
      .build()

  private def ticket(bytes: Int): Flight.Ticket =
    Flight.Ticket.newBuilder().setTicket(ByteString.copyFrom(new Array[Byte](bytes))).build()

  private val longestMessage = FlightProducerImpl.MaxAnonymousCommandBytes + 16

  it should "pass a command at the limit on, with what wraps it, on either method" in {
    val handler = new FakeHandler
    descriptor(FlightProducerImpl.MaxAnonymousCommandBytes).getSerializedSize should be <=
      longestMessage
    descriptor(longestMessage - 5).getSerializedSize shouldBe longestMessage
    List(
      admit(guardOf(1), handler)        -> descriptor(FlightProducerImpl.MaxAnonymousCommandBytes),
      admit(guardOf(1), handler)        -> descriptor(longestMessage - 5),
      admit(guardOf(1), handler, doGet) -> ticket(FlightProducerImpl.MaxAnonymousCommandBytes)
    ).foreach { case ((call, listener), message) =>
      listener.onMessage(message)
      call.closed shouldBe empty
    }
    handler.messages shouldBe 3
  }

  it should "refuse a longer message as it arrives, and then hear only of the call's end" in {
    val guard            = guardOf(1)
    val handler          = new FakeHandler
    val (call, listener) = admit(guard, handler)
    listener.onMessage(descriptor(longestMessage - 4))
    codes(call) shouldBe List(Status.Code.UNAUTHENTICATED)
    listener.onMessage(descriptor(1))
    listener.onMessage(descriptor(5 * 1024))
    listener.onHalfClose()
    listener.onReady()
    (handler.messages, handler.halfCloses, handler.readies) shouldBe (0, 0, 0)
    codes(call) should have size 1
    // Its place is held until the call ends, and handed back then.
    codes(admit(guard, handler)._1) shouldBe List(Status.Code.UNAVAILABLE)
    listener.onComplete()
    handler.completed shouldBe 1
    admit(guard, handler)._1.closed shouldBe empty
  }

  it should "refuse a long ticket on DoGet" in {
    val (call, listener) = admit(guardOf(1), new FakeHandler, doGet)
    listener.onMessage(ticket(5 * 1024))
    codes(call) shouldBe List(Status.Code.UNAUTHENTICATED)
  }

  it should "leave alone a long message of a call that has a credential" in {
    val handler          = new FakeHandler
    val (call, listener) = admit(guardOf(1), handler, headers = header("Authorization", "Basic x"))
    listener.onMessage(descriptor(5 * 1024))
    call.closed shouldBe empty
    handler.messages shouldBe 1
  }

  it should "take a long message on a call gRPC has already closed" in {
    // A unary call's second message closes it, in gRPC's own listener, and a third can still come.
    val handler          = new FakeHandler
    val (call, listener) = admit(guardOf(1), handler)
    call.close(Status.INTERNAL, new Metadata())
    listener.onMessage(descriptor(5 * 1024))
    codes(call) shouldBe List(Status.Code.INTERNAL)
    handler.messages shouldBe 0
  }

  it should "give a place back once when a call ends twice" in {
    val guard            = guardOf(2)
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
    val guard   = guardOf(1)
    val failing = new FakeHandler(fails = true)
    (1 to 2).foreach(_ => an[IllegalStateException] should be thrownBy admit(guard, failing))
    val working = new FakeHandler
    admit(guard, working)._1.closed shouldBe empty
    working.started shouldBe 1
  }
