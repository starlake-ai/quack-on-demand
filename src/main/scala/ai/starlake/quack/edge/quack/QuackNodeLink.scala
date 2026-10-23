package ai.starlake.quack.edge.quack

import ai.starlake.quack.edge.adapter.{
  QuackNativeBridge,
  QuackNativeSupport,
  QuackProtocol,
  QuackTransport,
  QuackWireError
}
import ai.starlake.quack.model.RunningNode
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging

import java.net.URI

/** Why a node round trip failed, in the two classes the router already distinguishes: a transport
  * problem (5xx, refused, timeout) is retried once on another node, an `ERROR_RESPONSE` from the
  * node is final and its message goes to the client. [[HandleFailed]] is a third class, for the
  * front door: the router retries it like a transport problem, and the stamping fallback never
  * mistakes it for the failure of the stamp SQL.
  */
enum LinkFailure(val message: String):
  case Transient(msg: String) extends LinkFailure(msg)
  case Permanent(msg: String) extends LinkFailure(msg)

  /** The session handle prelude before a PREPARE failed (the node refused it, or it got no answer),
    * so the statement was not sent.
    */
  case HandleFailed(msg: String) extends LinkFailure(msg)

/** One raw Quack connection from the manager to a node, opened for one client statement.
  *
  * Unlike [[ai.starlake.quack.edge.adapter.QuackProtocol]] (which decodes results into Arrow), a
  * link moves bytes: the front door rewrites the client's header onto the node connection id and
  * forwards the body verbatim, and hands the node's response bytes back to the client as they are.
  * The only node bytes the manager reads are headers and, best effort through the native bridge,
  * the `needs_more_fetch` flag and result uuid it needs to drain its own prelude and epilogue
  * statements.
  *
  * Every PREPARE it sends follows a fresh session handle from `authorize` (`CALL
  * system.main.qod_session(...)`, its own PREPARE): [[prepare]] for a relayed client statement,
  * [[runDiscard]] for the manager's own.
  */
final class QuackNodeLink(
    transport: QuackTransport,
    val url: URI,
    val nodeConnectionId: String,
    authorize: () => String
) extends LazyLogging:

  /** POST already-framed bytes and return the node's response bytes. Private on purpose: it is the
    * one way to put a PREPARE on the link with no session handle in front of it, so the only ways
    * out are [[forward]] (which refuses a PREPARE), [[prepare]] and [[runDiscard]].
    */
  private def relay(bytes: Array[Byte]): IO[Either[LinkFailure, Array[Byte]]] =
    transport.post(url, bytes).attempt.map {
      case Right(resp)                       => Right(resp)
      case Left(e: QuackWireError.Transient) => Left(LinkFailure.Transient(e.msg))
      case Left(e: QuackWireError.Permanent) => Left(LinkFailure.Permanent(e.msg))
      case Left(e)                           =>
        Left(LinkFailure.Transient(Option(e.getMessage).getOrElse(e.toString)))
    }

  /** Forward a client request: same body, the node's connection id in the header. Never a PREPARE,
    * which goes through [[prepare]] after its session handle.
    */
  def forward(frame: QuackWire.Frame): IO[Either[LinkFailure, Array[Byte]]] =
    if frame.header.msgType == QuackWire.Type.PrepareRequest then
      IO.pure(Left(LinkFailure.Permanent("a PREPARE is never relayed without its session handle")))
    else relay(QuackWire.rewriteHeader(frame, nodeConnectionId))

  /** Relay `req` as this connection's next PREPARE, after a fresh session handle; the node's
    * response bytes as they are. A failed handle ([[LinkFailure.HandleFailed]]) means `req` was not
    * sent.
    */
  def prepare(
      req: QuackWire.PrepareRequest,
      clientQueryId: Long
  ): IO[Either[LinkFailure, Array[Byte]]] =
    authorized(clientQueryId).flatMap {
      case Left(f)   => IO.pure(Left(f))
      case Right(()) =>
        relay(QuackWire.encodePrepareRequest(nodeConnectionId, clientQueryId, req))
    }

  /** Run `sql` on this connection after a fresh session handle, and discard its result: the
    * author-stamping prelude, a reopened BEGIN, the COMMIT epilogue. A node `ERROR_RESPONSE` to
    * `sql` is `Permanent(message)`; a failed handle is [[LinkFailure.HandleFailed]], and `sql` was
    * not sent.
    */
  def runDiscard(sql: String, clientQueryId: Long): IO[Either[LinkFailure, Unit]] =
    authorized(clientQueryId).flatMap {
      case Left(f)   => IO.pure(Left(f))
      case Right(()) => run(sql, clientQueryId)
    }

  /** The session handle prelude, drained. Any failure of the prelude itself is `HandleFailed`, and
    * the statement is not sent: a node error on it (the node refused the handle) and a transport
    * failure on it (the handle never reached the node), each worded for what happened so that a 503
    * is not reported as a refusal. No principal to vouch for (`authorize` throws) is `Permanent`:
    * nothing could be minted, and no other node would do better.
    */
  private def authorized(clientQueryId: Long): IO[Either[LinkFailure, Unit]] =
    IO(authorize()).attempt.flatMap {
      case Left(e) =>
        IO.pure(Left(LinkFailure.Permanent(Option(e.getMessage).getOrElse(e.toString))))
      case Right(prelude) =>
        run(prelude, clientQueryId).map(_.left.map(handleFailure))
    }

  /** [[run]] answers a node error with `Permanent` and a transport failure with `Transient`. */
  private def handleFailure(f: LinkFailure): LinkFailure.HandleFailed = f match
    case LinkFailure.Permanent(m) =>
      LinkFailure.HandleFailed(s"the node refused the statement's session handle: $m")
    case LinkFailure.Transient(m) =>
      LinkFailure.HandleFailed(s"the statement's session handle did not reach the node: $m")
    case h: LinkFailure.HandleFailed => h // never produced by run; keeps the match exhaustive

  /** One PREPARE of `sql`, drained through its FETCH loop so the node releases the result. The
    * query gets its own random uuid, as a Quack v3 client names every query it prepares.
    */
  private def run(sql: String, clientQueryId: Long): IO[Either[LinkFailure, Unit]] =
    val prep = QuackWire.encodePrepareRequest(
      nodeConnectionId,
      clientQueryId,
      QuackWire.PrepareRequest(sql, Some(QuackNodeLink.randomUuid()), None)
    )
    relay(prep).flatMap {
      case Left(f)     => IO.pure(Left(f))
      case Right(resp) =>
        QuackWire.messageType(resp) match
          case QuackWire.Type.ErrorResponse =>
            IO.pure(Left(LinkFailure.Permanent(errorMessage(resp))))
          case QuackWire.Type.PrepareResponse =>
            val more = QuackNodeLink.nativeNeedsMore(resp).getOrElse(false)
            if !more then IO.pure(Right(()))
            else
              QuackNodeLink.nativeResultUuid(resp) match
                case None       => IO.pure(Right(()))
                case Some(uuid) => drain(uuid, clientQueryId, 1L)
          case other =>
            IO.pure(Left(LinkFailure.Permanent(s"unexpected response type $other after PREPARE")))
    }

  /** Fetch batch after batch (Quack v3 names each one, 1-based, and acks the ones before it) until
    * the terminal response.
    */
  private def drain(
      uuid: QuackWire.Hugeint,
      clientQueryId: Long,
      batch: Long
  ): IO[Either[LinkFailure, Unit]] =
    if batch > QuackNodeLink.MaxDrainRounds then
      IO.pure(Left(LinkFailure.Permanent("result did not terminate while draining")))
    else
      relay(QuackWire.encodeFetchRequest(nodeConnectionId, clientQueryId, uuid, batch)).flatMap {
        case Left(f)     => IO.pure(Left(f))
        case Right(resp) =>
          if QuackWire.messageType(resp) == QuackWire.Type.ErrorResponse then
            IO.pure(Left(LinkFailure.Permanent(errorMessage(resp))))
          else if QuackWire.isTerminalFetch(resp) then IO.pure(Right(()))
          else drain(uuid, clientQueryId, batch + 1)
      }

  private def errorMessage(resp: Array[Byte]): String =
    QuackWire.decodeErrorMessage(resp).getOrElse("node error")

  /** Best-effort DISCONNECT; a half-dead node must not fail the caller. */
  def close(): IO[Unit] =
    transport.post(url, QuackWire.encodeDisconnect(nodeConnectionId)).void.handleError { e =>
      logger.debug(s"DISCONNECT for $nodeConnectionId failed (ignored): ${e.getMessage}")
    }

object QuackNodeLink:

  private val MaxDrainRounds = 1_000_000L

  /** A fresh query uuid for a statement the manager runs itself. */
  private[quack] def randomUuid(): QuackWire.Hugeint =
    val u = java.util.UUID.randomUUID()
    QuackWire.Hugeint(u.getMostSignificantBits, u.getLeastSignificantBits)

  /** The `authorize` of a link that sends no statement (the handshake's version probe). */
  val NoStatements: () => String =
    () => throw new IllegalStateException("this node link sends no statement")

  /** Open a connection to `node` on the client's behalf: the client's own hello (versions,
    * platform, client id, heartbeat) with the node token as `auth_string`, so protocol negotiation
    * runs end to end between the real client and the real node. The link's statements follow a
    * session handle each, from `authorize`.
    */
  def open(
      transport: QuackTransport,
      node: RunningNode,
      hello: QuackWire.ConnectionRequest,
      clientQueryId: Long,
      authorize: () => String
  ): IO[Either[LinkFailure, (QuackNodeLink, QuackWire.ConnectionResponse)]] =
    val url  = QuackProtocol.endpointToHttp(s"quack:${node.host}:${node.port}")
    val body = QuackWire.encodeConnectionRequest(hello.copy(authString = node.token), clientQueryId)
    val probe = new QuackNodeLink(transport, url, "", NoStatements)
    probe.relay(body).map {
      case Left(f)     => Left(f)
      case Right(resp) =>
        QuackWire.messageType(resp) match
          case QuackWire.Type.ConnectionResponse =>
            QuackWire.decodeConnectionResponse(resp) match
              case Right((connId, r)) if connId.nonEmpty =>
                Right((new QuackNodeLink(transport, url, connId, authorize), r))
              case Right(_) => Left(LinkFailure.Permanent("node answered without a connection id"))
              case Left(e)  =>
                Left(LinkFailure.Permanent(s"unreadable CONNECTION_RESPONSE: ${e.message}"))
          case QuackWire.Type.ErrorResponse =>
            Left(LinkFailure.Permanent(QuackWire.decodeErrorMessage(resp).getOrElse("node error")))
          case other =>
            Left(LinkFailure.Permanent(s"unexpected response type $other after CONNECTION_REQUEST"))
    }

  /** Two's-complement 128-bit BigInteger (as the native bridge returns it) to the wire pair. */
  private[quack] def hugeintOf(big: java.math.BigInteger): QuackWire.Hugeint =
    val lower = big.longValue()
    val upper = big.shiftRight(64).longValue()
    QuackWire.Hugeint(upper, lower)

  /** `needs_more_fetch` of a node PREPARE_RESPONSE through the libquackwire bridge, or None when
    * the native cannot read it (pin drift, or no native on this platform). Catches Throwable on
    * purpose: an UnsatisfiedLinkError is a LinkageError, which `Try` treats as fatal.
    */
  def nativeNeedsMore(resp: Array[Byte]): Option[Boolean] =
    try
      QuackNativeSupport.requireLoaded()
      Some(QuackNativeBridge.needsMoreFetch(resp))
    catch case _: Throwable => None

  /** The result uuid of a node PREPARE_RESPONSE through the bridge, or None (same rule). */
  def nativeResultUuid(resp: Array[Byte]): Option[QuackWire.Hugeint] =
    try
      QuackNativeSupport.requireLoaded()
      Some(hugeintOf(QuackNativeBridge.extractResultUuid(resp)))
    catch case _: Throwable => None
