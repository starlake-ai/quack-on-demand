package ai.starlake.quack.edge.quack

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8

/** Pure-JVM codec for the parts of DuckDB's Quack wire the front door has to understand.
  *
  * The wire is DuckDB's `BinarySerializer` format: a header object followed by a body object. Field
  * ids are raw little-endian `uint16`, integers are LEB128 varints (signed LEB128 for signed
  * types), strings carry a `uint32` varint length, a `bool` is one raw byte, and every object ends
  * with `0xFFFF`. Fields written with `WritePropertyWithDefault` upstream are omitted when they
  * hold their default, so every decoder treats an absent field as its default and only fails on an
  * id it does not know.
  *
  * The manager never decodes or encodes result chunks: `PREPARE_RESPONSE` and `FETCH_RESPONSE`
  * bytes travel from node to client untouched, and chunk-carrying requests (APPEND, SEND_DATA)
  * travel from client to node with only the header rewritten. See the design note
  * `docs/superpowers/specs/2026-09-20-native-quack-front-door-design.md`, section 2, for the field
  * tables of both protocol generations this codec accepts.
  */
object QuackWire:

  /** Wire ordinals of `duckdb::MessageType` (non-sequential upstream). Type 9 is APPEND_REQUEST in
    * protocol generation 1 and SEND_DATA_REQUEST in generation 3; the negotiated version tells them
    * apart.
    */
  object Type:
    val ConnectionRequest  = 1
    val ConnectionResponse = 2
    val PrepareRequest     = 3
    val PrepareResponse    = 4
    val FetchRequest       = 7
    val FetchResponse      = 8
    val DataRequest        = 9
    val SuccessResponse    = 10
    val Disconnect         = 11
    val CancelRequest      = 12
    val SendDataResponse   = 14
    val Acknowledgement    = 15
    val HeartbeatRequest   = 16
    val ErrorResponse      = 100

  /** `duckdb::hugeint_t`: signed upper 64 bits, unsigned lower 64 bits (kept in a Long). */
  final case class Hugeint(upper: Long, lower: Long)

  /** `optional_idx` unset marker (`2^64 - 1`), as the Scala Long with the same bit pattern. */
  val InvalidIndex: Long = -1L

  private val Terminator = 0xffff

  final case class Header(msgType: Int, connectionId: String, clientQueryId: Long)

  /** A framed message: the decoded header plus the offset where the body object starts. */
  final case class Frame(header: Header, bodyStart: Int, bytes: Array[Byte])

  final case class ConnectionRequest(
      authString: String,
      clientDuckdbVersion: String,
      clientPlatform: String,
      minVersion: Long,
      maxVersion: Long,
      clientId: String,
      heartbeatTimeoutSec: Long
  )

  final case class PrepareRequest(sql: String, queryUuid: Option[Hugeint], inlineRows: Option[Long])

  final case class AppendRequest(schema: String, table: String)

  final case class FetchRequest(uuid: Hugeint)

  final case class ConnectionResponse(
      serverDuckdbVersion: String,
      serverPlatform: String,
      quackVersion: Long,
      heartbeatTimeoutSec: Long
  )

  final case class DecodeError(message: String) extends RuntimeException(message)

  // ---------------------------------------------------------------------------------------------
  // Primitive reader / writer
  // ---------------------------------------------------------------------------------------------

  private final class Reader(bytes: Array[Byte]):
    var pos: Int = 0

    private def next(): Int =
      if pos >= bytes.length then throw DecodeError("truncated message")
      else
        val b = bytes(pos) & 0xff
        pos += 1
        b

    def u16(): Int =
      val lo = next()
      val hi = next()
      lo | (hi << 8)

    def varintU(): Long =
      var result = 0L
      var shift  = 0
      var b      = 0
      var more   = true
      while more do
        b = next()
        if shift < 64 then result |= (b & 0x7fL) << shift
        shift += 7
        more = (b & 0x80) != 0
        if more && shift > 70 then throw DecodeError("varint too long")
      result

    def varintS(): Long =
      var result = 0L
      var shift  = 0
      var b      = 0
      var more   = true
      while more do
        b = next()
        if shift < 64 then result |= (b & 0x7fL) << shift
        shift += 7
        more = (b & 0x80) != 0
        if more && shift > 70 then throw DecodeError("varint too long")
      if shift < 64 && (b & 0x40) != 0 then result |= -1L << shift
      result

    def string(): String =
      val len = varintU()
      if len < 0 || len > Int.MaxValue || pos + len > bytes.length then
        throw DecodeError("truncated string")
      else
        val s = new String(bytes, pos, len.toInt, UTF_8)
        pos += len.toInt
        s

    def bool(): Boolean = next() != 0

    def hugeint(): Hugeint =
      val upper = varintS()
      val lower = varintU()
      Hugeint(upper, lower)

    def remaining: Int = bytes.length - pos

  private final class Writer:
    private val out = new ByteArrayOutputStream()

    def u16(v: Int): Writer =
      out.write(v & 0xff)
      out.write((v >>> 8) & 0xff)
      this

    def varintU(v0: Long): Writer =
      var v    = v0
      var more = true
      while more do
        var b = (v & 0x7f).toInt
        v = v >>> 7
        more = v != 0
        if more then b |= 0x80
        out.write(b)
      this

    def varintS(v0: Long): Writer =
      var v    = v0
      var more = true
      while more do
        var b = (v & 0x7f).toInt
        v = v >> 7
        val done = (v == 0 && (b & 0x40) == 0) || (v == -1 && (b & 0x40) != 0)
        more = !done
        if more then b |= 0x80
        out.write(b)
      this

    def string(s: String): Writer =
      val b = s.getBytes(UTF_8)
      varintU(b.length.toLong)
      out.write(b, 0, b.length)
      this

    def bool(b: Boolean): Writer =
      out.write(if b then 1 else 0)
      this

    def hugeint(h: Hugeint): Writer =
      varintS(h.upper)
      varintU(h.lower)

    def end(): Writer = u16(Terminator)

    def raw(b: Array[Byte], from: Int): Writer =
      out.write(b, from, b.length - from)
      this

    def bytes: Array[Byte] = out.toByteArray

  private def attempt[A](what: String)(f: => A): Either[DecodeError, A] =
    try Right(f)
    catch
      case e: DecodeError      => Left(e)
      case e: RuntimeException =>
        Left(DecodeError(s"$what: ${Option(e.getMessage).getOrElse(e.toString)}"))

  // ---------------------------------------------------------------------------------------------
  // Framing
  // ---------------------------------------------------------------------------------------------

  /** Decode the header object only; the body is left in place at `bodyStart`. */
  def frame(bytes: Array[Byte]): Either[DecodeError, Frame] = attempt("header") {
    val r         = new Reader(bytes)
    var msgType   = -1
    var connId    = ""
    var clientQid = InvalidIndex
    var done      = false
    while !done do
      r.u16() match
        case Terminator => done = true
        case 1          => msgType = r.varintU().toInt
        case 2          => connId = r.string()
        case 3          => clientQid = r.varintU()
        case other      => throw DecodeError(s"unknown field $other in header")
    if msgType < 0 then throw DecodeError("header without a message type")
    else Frame(Header(msgType, connId, clientQid), r.pos, bytes)
  }

  private def bodyReader(f: Frame): Reader =
    val r = new Reader(f.bytes)
    r.pos = f.bodyStart
    r

  def decodeConnectionRequest(f: Frame): Either[DecodeError, ConnectionRequest] =
    attempt("CONNECTION_REQUEST") {
      val r    = bodyReader(f)
      var auth = ""
      var ver  = ""
      var plat = ""
      var min  = 0L
      var max  = 0L
      var cid  = ""
      var hb   = 0L
      var done = false
      while !done do
        r.u16() match
          case Terminator => done = true
          case 1          => auth = r.string()
          case 2          => ver = r.string()
          case 3          => plat = r.string()
          case 4          => min = r.varintU()
          case 5          => max = r.varintU()
          case 6          => cid = r.string()
          case 7          => hb = r.varintU()
          case other      => throw DecodeError(s"unknown field $other in CONNECTION_REQUEST")
      ConnectionRequest(auth, ver, plat, min, max, cid, hb)
    }

  def decodePrepareRequest(f: Frame): Either[DecodeError, PrepareRequest] =
    attempt("PREPARE_REQUEST") {
      val r      = bodyReader(f)
      var sql    = ""
      var uuid   = Option.empty[Hugeint]
      var inline = Option.empty[Long]
      var done   = false
      while !done do
        r.u16() match
          case Terminator => done = true
          case 1          => sql = r.string()
          case 2          => uuid = Some(r.hugeint())
          case 3          =>
            val v = r.varintU()
            inline = if v == InvalidIndex then None else Some(v)
          case other => throw DecodeError(s"unknown field $other in PREPARE_REQUEST")
      PrepareRequest(sql, uuid, inline)
    }

  /** Reads the schema and table of a generation 1 APPEND_REQUEST and stops at the chunk list (field
    * 3), which is never decoded: the bytes are forwarded to a node as they are.
    */
  def decodeAppendRequest(f: Frame): Either[DecodeError, AppendRequest] =
    attempt("APPEND_REQUEST") {
      val r      = bodyReader(f)
      var schema = Option.empty[String]
      var table  = Option.empty[String]
      var done   = false
      while !done do
        r.u16() match
          case Terminator => done = true
          case 1          => schema = Some(r.string())
          case 2          => table = Some(r.string())
          case 3          => done = true
          case other      => throw DecodeError(s"unknown field $other in APPEND_REQUEST")
      (schema, table) match
        case (Some(s), Some(t)) => AppendRequest(s, t)
        case _                  => throw DecodeError("APPEND_REQUEST without schema and table")
    }

  /** Reads the uuid (field 1) of a FETCH_REQUEST; the generation 3 batch and ack indexes are
    * skipped, since a forwarded FETCH keeps its original body bytes.
    */
  def decodeFetchRequest(f: Frame): Either[DecodeError, FetchRequest] =
    attempt("FETCH_REQUEST") {
      val r    = bodyReader(f)
      var uuid = Option.empty[Hugeint]
      var done = false
      while !done do
        r.u16() match
          case Terminator => done = true
          case 1          => uuid = Some(r.hugeint())
          case 2 | 3      => r.varintU()
          case other      => throw DecodeError(s"unknown field $other in FETCH_REQUEST")
      uuid match
        case Some(u) => FetchRequest(u)
        case None    => throw DecodeError("FETCH_REQUEST without a uuid")
    }

  def decodeErrorMessage(bytes: Array[Byte]): Either[DecodeError, String] =
    frame(bytes).flatMap { f =>
      attempt("ERROR_RESPONSE") {
        val r    = bodyReader(f)
        var msg  = ""
        var done = false
        while !done do
          r.u16() match
            case Terminator => done = true
            case 1          => msg = r.string()
            case other      => throw DecodeError(s"unknown field $other in ERROR_RESPONSE")
        msg
      }
    }

  def decodeConnectionResponse(
      bytes: Array[Byte]
  ): Either[DecodeError, (String, ConnectionResponse)] =
    frame(bytes).flatMap { f =>
      attempt("CONNECTION_RESPONSE") {
        val r    = bodyReader(f)
        var ver  = ""
        var plat = ""
        var qv   = 0L
        var hb   = 0L
        var done = false
        while !done do
          r.u16() match
            case Terminator => done = true
            case 1          => ver = r.string()
            case 2          => plat = r.string()
            case 3          => qv = r.varintU()
            case 4          => hb = r.varintU()
            case other      => throw DecodeError(s"unknown field $other in CONNECTION_RESPONSE")
        (f.header.connectionId, ConnectionResponse(ver, plat, qv, hb))
      }
    }

  // ---------------------------------------------------------------------------------------------
  // Encoders
  // ---------------------------------------------------------------------------------------------

  private def header(w: Writer, h: Header): Writer =
    w.u16(1).varintU(h.msgType.toLong)
    if h.connectionId.nonEmpty then w.u16(2).string(h.connectionId)
    w.u16(3).varintU(h.clientQueryId)
    w.end()

  def encodeHeader(h: Header): Array[Byte] = header(new Writer, h).bytes

  /** Same body, new connection id: what every forwarded request needs. */
  def rewriteHeader(f: Frame, newConnectionId: String): Array[Byte] =
    header(new Writer, f.header.copy(connectionId = newConnectionId))
      .raw(f.bytes, f.bodyStart)
      .bytes

  def encodeConnectionRequest(r: ConnectionRequest, clientQueryId: Long): Array[Byte] =
    val w = header(new Writer, Header(Type.ConnectionRequest, "", clientQueryId))
    if r.authString.nonEmpty then w.u16(1).string(r.authString)
    if r.clientDuckdbVersion.nonEmpty then w.u16(2).string(r.clientDuckdbVersion)
    if r.clientPlatform.nonEmpty then w.u16(3).string(r.clientPlatform)
    if r.minVersion != 0L then w.u16(4).varintU(r.minVersion)
    if r.maxVersion != 0L then w.u16(5).varintU(r.maxVersion)
    if r.clientId.nonEmpty then w.u16(6).string(r.clientId)
    if r.heartbeatTimeoutSec != 0L then w.u16(7).varintU(r.heartbeatTimeoutSec)
    w.end().bytes

  def encodePrepareRequest(
      connectionId: String,
      clientQueryId: Long,
      r: PrepareRequest
  ): Array[Byte] =
    val w = header(new Writer, Header(Type.PrepareRequest, connectionId, clientQueryId))
    if r.sql.nonEmpty then w.u16(1).string(r.sql)
    r.queryUuid.foreach(u => w.u16(2).hugeint(u))
    r.inlineRows.foreach(n => w.u16(3).varintU(n))
    w.end().bytes

  def encodeFetchRequest(connectionId: String, clientQueryId: Long, uuid: Hugeint): Array[Byte] =
    header(new Writer, Header(Type.FetchRequest, connectionId, clientQueryId))
      .u16(1)
      .hugeint(uuid)
      .end()
      .bytes

  def encodeDisconnect(connectionId: String): Array[Byte] =
    header(new Writer, Header(Type.Disconnect, connectionId, InvalidIndex)).end().bytes

  def encodeConnectionResponse(connectionId: String, r: ConnectionResponse): Array[Byte] =
    val w = header(new Writer, Header(Type.ConnectionResponse, connectionId, InvalidIndex))
    if r.serverDuckdbVersion.nonEmpty then w.u16(1).string(r.serverDuckdbVersion)
    if r.serverPlatform.nonEmpty then w.u16(2).string(r.serverPlatform)
    if r.quackVersion != 0L then w.u16(3).varintU(r.quackVersion)
    if r.heartbeatTimeoutSec != 0L then w.u16(4).varintU(r.heartbeatTimeoutSec)
    w.end().bytes

  def encodeSuccess(): Array[Byte] =
    header(new Writer, Header(Type.SuccessResponse, "", InvalidIndex)).end().bytes

  def encodeError(message: String): Array[Byte] =
    val w = header(new Writer, Header(Type.ErrorResponse, "", InvalidIndex))
    if message.nonEmpty then w.u16(1).string(message)
    w.end().bytes

  // ---------------------------------------------------------------------------------------------
  // Response inspection
  // ---------------------------------------------------------------------------------------------

  /** The message type of `bytes`, or -1 when the header cannot be read. */
  def messageType(bytes: Array[Byte]): Int =
    frame(bytes).map(_.header.msgType).getOrElse(-1)

  /** True when a FETCH_RESPONSE carries no chunks, in either generation: generation 1 omits the
    * empty `results` list (field 1) altogether, generation 3 omits a zero `chunk_count` (also field
    * 1). A non-terminal response of either generation starts its body with field 1 holding a
    * non-zero count. Unreadable bytes count as terminal so a broken stream is released.
    */
  def isTerminalFetch(bytes: Array[Byte]): Boolean =
    frame(bytes) match
      case Left(_)  => true
      case Right(f) =>
        attempt("FETCH_RESPONSE") {
          val r = bodyReader(f)
          if r.remaining < 2 then true
          else
            r.u16() match
              case 1 => r.varintU() == 0L
              case _ => true
        }.getOrElse(true)
