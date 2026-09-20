package ai.starlake.quack.edge.quack

import ai.starlake.quack.edge.adapter.{QuackNativeBridge, QuackTestFixtures}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Codec contract, pinned two ways: byte fixtures captured from the DuckDB 1.5.4 CLI (quack
  * extension 40de7ba, protocol generation 1) talking to a real `quack_serve` node, and round-trips
  * against the libquackwire native serializers the outbound client already uses.
  */
class QuackWireSpec extends AnyFlatSpec with Matchers:

  import QuackWire.*

  private def hex(s: String): Array[Byte] =
    s.grouped(2).map(h => Integer.parseInt(h, 16).toByte).toArray
  private def toHex(b: Array[Byte]): String = b.map(x => f"${x & 0xff}%02x").mkString

  private val ConnId = "EA342AAE75D46794E720E88604184579"

  private val connReq =
    hex(
      "010001030003ffff010007746f6b3132333402000676312e352e340300096f73785f61726d3634040001050001ffff"
    )
  private val connResp = hex(
    "01000202002045413334324141453735443436373934453732304538383630343138343537390300ffffffffffffffffff01ffff01000676312e352e340200096f73785f61726d3634030001ffff"
  )
  private val prepareReq = hex(
    "0100030200204541333432414145373544343637393445373230453838363034313834353739030003ffff01000f53454c454354202a2046524f4d2074ffff"
  )
  private val fetchReq = hex(
    "0100070200203841424337303532463739434142344543364245373232383244454331453731030003ffff0100bd9c81aafdcdc584ae7f9cf4e3f29187b496a901ffff"
  )
  private val disconnect = hex(
    "01000b02002045413334324141453735443436373934453732304538383630343138343537390300ffffffffffffffffff01ffffffff"
  )
  private val success = hex("01000a0300ffffffffffffffffff01ffffffff")
  private val error   =
    hex("0100640300ffffffffffffffffff01ffff01001541757468656e7469636174696f6e206661696c6564ffff")
  private val appendPrefix = hex(
    "0100090200204431433245464145393544414331363631314334373637374446423546333830030006ffff0100046d61696e020001740300012c01"
  )
  private val prepareRespNoMore = hex(
    "0100040300ffffffffffffffffff01ffff01000264000effff640019ffff0200020269640173040003012c01"
  )
  private val fetchRespChunks = hex("0100080300ffffffffffffffffff01ffff01000c012c01")
  private val fetchTerminal1  = hex("0100080300ffffffffffffffffff01ffffffff")
  private val fetchTerminal3  = hex("0100080300ffffffffffffffffff01ffff020004ffff")
  private val fetchGen3More   = hex("0100080300ffffffffffffffffff01ffff010002030001ffff")

  "frame" should "read the header of every captured request" in:
    val f = frame(connReq).toOption.get
    f.header shouldBe Header(Type.ConnectionRequest, "", 3L)
    f.bodyStart shouldBe 8
    frame(prepareReq).toOption.get.header shouldBe Header(Type.PrepareRequest, ConnId, 3L)
    frame(fetchReq).toOption.get.header.msgType shouldBe Type.FetchRequest
    frame(disconnect).toOption.get.header shouldBe Header(Type.Disconnect, ConnId, InvalidIndex)

  it should "read the header of every captured response" in:
    frame(connResp).toOption.get.header shouldBe Header(
      Type.ConnectionResponse,
      ConnId,
      InvalidIndex
    )
    frame(success).toOption.get.header shouldBe Header(Type.SuccessResponse, "", InvalidIndex)
    frame(error).toOption.get.header shouldBe Header(Type.ErrorResponse, "", InvalidIndex)
    frame(prepareRespNoMore).toOption.get.header.msgType shouldBe Type.PrepareResponse

  it should "fail softly on truncated bytes" in:
    frame(Array.empty[Byte]).isLeft shouldBe true
    frame(connReq.take(5)).isLeft shouldBe true

  "decodeConnectionRequest" should "read the generation 1 hello" in:
    decodeConnectionRequest(frame(connReq).toOption.get) shouldBe Right(
      ConnectionRequest("tok1234", "v1.5.4", "osx_arm64", 1L, 1L, "", 0L)
    )

  it should "round-trip a generation 3 hello with client id and heartbeat" in:
    val hello = ConnectionRequest("t", "v2.0.0", "linux_amd64", 3L, 3L, "client-42", 30L)
    val bytes = encodeConnectionRequest(hello, 9L)
    val f     = frame(bytes).toOption.get
    f.header shouldBe Header(Type.ConnectionRequest, "", 9L)
    decodeConnectionRequest(f) shouldBe Right(hello)

  "decodePrepareRequest" should "read the generation 1 shape" in:
    decodePrepareRequest(frame(prepareReq).toOption.get) shouldBe Right(
      PrepareRequest("SELECT * FROM t", None, None)
    )

  it should "round-trip the generation 3 shape" in:
    val p     = PrepareRequest("SELECT 1", Some(Hugeint(-5L, 7L)), Some(0L))
    val bytes = encodePrepareRequest("ABC", 4L, p)
    val f     = frame(bytes).toOption.get
    f.header shouldBe Header(Type.PrepareRequest, "ABC", 4L)
    decodePrepareRequest(f) shouldBe Right(p)

  it should "encode the generation 1 shape byte for byte" in:
    toHex(encodePrepareRequest(ConnId, 3L, PrepareRequest("SELECT * FROM t", None, None))) shouldBe
      toHex(prepareReq)

  it should "reject an unknown field id without throwing" in:
    val f = frame(hex("010003ffff0900" + "01" + "ffff")).toOption.get
    decodePrepareRequest(f).swap.toOption.get.message should include("unknown field")

  "decodeAppendRequest" should "read schema and table and stop before the chunks" in:
    decodeAppendRequest(frame(appendPrefix).toOption.get) shouldBe Right(AppendRequest("main", "t"))

  "decodeFetchRequest" should "read the uuid and re-encode the same body" in:
    val f = frame(fetchReq).toOption.get
    val u = decodeFetchRequest(f).toOption.get.uuid
    toHex(encodeFetchRequest(f.header.connectionId, 3L, u)) shouldBe toHex(fetchReq)

  "encodeError / decodeErrorMessage" should "match the captured ERROR_RESPONSE" in:
    toHex(encodeError("Authentication failed")) shouldBe toHex(error)
    decodeErrorMessage(error) shouldBe Right("Authentication failed")

  "encodeSuccess" should "match the captured SUCCESS_RESPONSE" in:
    toHex(encodeSuccess()) shouldBe toHex(success)

  "encodeConnectionResponse" should "match the captured CONNECTION_RESPONSE" in:
    toHex(
      encodeConnectionResponse(ConnId, ConnectionResponse("v1.5.4", "osx_arm64", 1L, 0L))
    ) shouldBe
      toHex(connResp)

  it should "carry a heartbeat when one was negotiated" in:
    val r = ConnectionResponse("qod", "quack-on-demand", 3L, 30L)
    decodeConnectionResponse(encodeConnectionResponse("X1", r)) shouldBe Right(("X1", r))

  it should "decode the captured node response" in:
    decodeConnectionResponse(connResp) shouldBe Right(
      (ConnId, ConnectionResponse("v1.5.4", "osx_arm64", 1L, 0L))
    )

  "rewriteHeader" should "swap the connection id and keep the body" in:
    val out = rewriteHeader(frame(prepareReq).toOption.get, "B")
    toHex(out) shouldBe "010003" + "020001" + "42" + "030003" + "ffff" +
      toHex(prepareReq.drop(frame(prepareReq).toOption.get.bodyStart))

  "encodeDisconnect" should "match the captured DISCONNECT" in:
    toHex(encodeDisconnect(ConnId)) shouldBe toHex(disconnect)

  "messageType" should "read the type or -1" in:
    messageType(prepareRespNoMore) shouldBe Type.PrepareResponse
    messageType(Array[Byte](1, 0)) shouldBe -1

  "isTerminalFetch" should "recognise both generations' terminal responses only" in:
    isTerminalFetch(fetchRespChunks) shouldBe false
    isTerminalFetch(fetchTerminal1) shouldBe true
    isTerminalFetch(fetchTerminal3) shouldBe true
    isTerminalFetch(fetchGen3More) shouldBe false

  "native cross-check" should "decode what libquackwire serializes" in:
    val c = frame(QuackNativeBridge.serializeConnectionRequest("tok")).toOption.get
    decodeConnectionRequest(c).toOption.get.authString shouldBe "tok"
    val p = frame(QuackNativeBridge.serializePrepareRequest("C", "SELECT 1")).toOption.get
    p.header.connectionId shouldBe "C"
    decodePrepareRequest(p).toOption.get.sql shouldBe "SELECT 1"
    val d = frame(QuackNativeBridge.serializeDisconnect("D")).toOption.get
    d.header shouldBe Header(Type.Disconnect, "D", InvalidIndex)

  it should "produce what libquackwire parses" in:
    QuackNativeBridge.parseMessageType(encodeError("boom")) shouldBe Type.ErrorResponse
    // The native side reformats the wire message through duckdb::ErrorData (it prepends the
    // exception type), so only the suffix is stable across pins.
    QuackNativeBridge.extractErrorMessage(encodeError("boom")) should endWith("boom")
    QuackNativeBridge.parseMessageType(encodeSuccess()) shouldBe Type.SuccessResponse
    val cr = encodeConnectionResponse("ABC", ConnectionResponse("v", "p", 1L, 0L))
    QuackNativeBridge.extractConnectionId(cr) shouldBe "ABC"
    val sample = QuackTestFixtures.serializeSampleErrorResponse("native")
    decodeErrorMessage(sample) shouldBe Right("native")
