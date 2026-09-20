package ai.starlake.quack.edge.quack

import ai.starlake.quack.edge.adapter.{
  QuackNativeBridge,
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
  * node is final and its message goes to the client.
  */
enum LinkFailure(val message: String):
  case Transient(msg: String) extends LinkFailure(msg)
  case Permanent(msg: String) extends LinkFailure(msg)

/** One raw Quack connection from the manager to a node, opened for one client statement.
  *
  * Unlike [[ai.starlake.quack.edge.adapter.QuackProtocol]] (which decodes results into Arrow), a
  * link moves bytes: the front door rewrites the client's header onto the node connection id and
  * forwards the body verbatim, and hands the node's response bytes back to the client as they are.
  * The only node bytes the manager reads are headers and, best effort through the native bridge,
  * the `needs_more_fetch` flag and result uuid it needs to drain its own prelude and epilogue
  * statements.
  */
final class QuackNodeLink(
    transport: QuackTransport,
    val url: URI,
    val nodeConnectionId: String
) extends LazyLogging:

  /** POST already-framed bytes and return the node's response bytes. */
  def relay(bytes: Array[Byte]): IO[Either[LinkFailure, Array[Byte]]] =
    transport.post(url, bytes).attempt.map {
      case Right(resp)                       => Right(resp)
      case Left(e: QuackWireError.Transient) => Left(LinkFailure.Transient(e.msg))
      case Left(e: QuackWireError.Permanent) => Left(LinkFailure.Permanent(e.msg))
      case Left(e)                           =>
        Left(LinkFailure.Transient(Option(e.getMessage).getOrElse(e.toString)))
    }

  /** Forward a client request: same body, the node's connection id in the header. */
  def forward(frame: QuackWire.Frame): IO[Either[LinkFailure, Array[Byte]]] =
    relay(QuackWire.rewriteHeader(frame, nodeConnectionId))

  /** Run `sql` on this connection and discard its result: the author-stamping prelude and the
    * COMMIT epilogue. Drains the FETCH loop so the node releases the result. A node
    * `ERROR_RESPONSE` is `Permanent(message)`.
    */
  def runDiscard(sql: String, clientQueryId: Long): IO[Either[LinkFailure, Unit]] =
    val prep = QuackWire.encodePrepareRequest(
      nodeConnectionId,
      clientQueryId,
      QuackWire.PrepareRequest(sql, None, None)
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
                case Some(uuid) =>
                  drain(QuackWire.encodeFetchRequest(nodeConnectionId, clientQueryId, uuid), 0)
          case other =>
            IO.pure(Left(LinkFailure.Permanent(s"unexpected response type $other after PREPARE")))
    }

  private def drain(fetch: Array[Byte], rounds: Int): IO[Either[LinkFailure, Unit]] =
    if rounds > QuackNodeLink.MaxDrainRounds then
      IO.pure(Left(LinkFailure.Permanent("result did not terminate while draining")))
    else
      relay(fetch).flatMap {
        case Left(f)     => IO.pure(Left(f))
        case Right(resp) =>
          if QuackWire.messageType(resp) == QuackWire.Type.ErrorResponse then
            IO.pure(Left(LinkFailure.Permanent(errorMessage(resp))))
          else if QuackWire.isTerminalFetch(resp) then IO.pure(Right(()))
          else drain(fetch, rounds + 1)
      }

  private def errorMessage(resp: Array[Byte]): String =
    QuackWire.decodeErrorMessage(resp).getOrElse("node error")

  /** Best-effort DISCONNECT; a half-dead node must not fail the caller. */
  def close(): IO[Unit] =
    transport.post(url, QuackWire.encodeDisconnect(nodeConnectionId)).void.handleError { e =>
      logger.debug(s"DISCONNECT for $nodeConnectionId failed (ignored): ${e.getMessage}")
    }

object QuackNodeLink:

  private val MaxDrainRounds = 1_000_000

  /** Open a connection to `node` on the client's behalf: the client's own hello (versions,
    * platform, client id, heartbeat) with the node token as `auth_string`, so protocol negotiation
    * runs end to end between the real client and the real node.
    */
  def open(
      transport: QuackTransport,
      node: RunningNode,
      hello: QuackWire.ConnectionRequest,
      clientQueryId: Long
  ): IO[Either[LinkFailure, (QuackNodeLink, QuackWire.ConnectionResponse)]] =
    val url  = QuackProtocol.endpointToHttp(s"quack:${node.host}:${node.port}")
    val body = QuackWire.encodeConnectionRequest(hello.copy(authString = node.token), clientQueryId)
    val probe = new QuackNodeLink(transport, url, "")
    probe.relay(body).map {
      case Left(f)     => Left(f)
      case Right(resp) =>
        QuackWire.messageType(resp) match
          case QuackWire.Type.ConnectionResponse =>
            QuackWire.decodeConnectionResponse(resp) match
              case Right((connId, r)) if connId.nonEmpty =>
                Right((new QuackNodeLink(transport, url, connId), r))
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
    try Some(QuackNativeBridge.needsMoreFetch(resp))
    catch case _: Throwable => None

  /** The result uuid of a node PREPARE_RESPONSE through the bridge, or None (same rule). */
  def nativeResultUuid(resp: Array[Byte]): Option[QuackWire.Hugeint] =
    try Some(hugeintOf(QuackNativeBridge.extractResultUuid(resp)))
    catch case _: Throwable => None
