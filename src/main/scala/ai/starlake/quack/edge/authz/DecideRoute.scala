package ai.starlake.quack.edge.authz

import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import io.circe.parser.parse
import org.http4s.{HttpRoutes, Response, Status}
import org.http4s.dsl.io.*
import org.typelevel.ci.CIString

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import scala.util.control.NonFatal

/** `POST /api/internal/authz/decide`: a node asks for the verdict of one statement (spec Section 5,
  * decisions 14 and 22). Mounted in front of the manager's api-key guard (a node has no session);
  * every call authenticates itself:
  *
  *   1. `X-QoD-Node` names the node and `X-QoD-Signature` is the lowercase hex HMAC-SHA256 of the
  *      body bytes under that node's key, checked (constant-time) before the body is parsed;
  *   2. the body's `handle` verifies under the same key for the same node ([[NodeHandles.verify]]);
  *   3. a user's handle names a context this replica minted ([[MintedHandles]]), consistent with
  *      the handle (user, pool, `ctx` digest).
  *
  * Answers (all `text/plain; charset=UTF-8`): 200 with a signed [[Verdict]] (allow or refuse), 401
  * when the node or the handle fails, 503 when the decision is unavailable (an expired context
  * included), 400 for a malformed request, 413 past [[DecideRoute.MaxBodyBytes]]. The log never
  * holds the body, the handle, the signature, a verdict or a key.
  */
final class DecideRoute(
    keys: NodeKeys,
    minted: MintedHandles,
    decider: AuthzDecider,
    clock: () => Long = () => System.currentTimeMillis()
) extends LazyLogging:
  import DecideRoute.*

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "api" / "internal" / "authz" / "decide" =>
      val node      = req.headers.get(CIString("X-QoD-Node")).map(_.head.value)
      val signature = req.headers.get(CIString("X-QoD-Signature")).map(_.head.value)
      req.body.take(MaxBodyBytes.toLong + 1).compile.to(Array).flatMap { body =>
        IO.blocking(answer(node, signature, body))
          .map((status, text) => Response[IO](status).withEntity(text))
      }
  }

  private final case class DecideRequest(description: Array[Byte], handle: String, kind: String)

  private def answer(
      node: Option[String],
      signature: Option[String],
      body: Array[Byte]
  ): (Status, String) =
    (node.filter(NodeIdText.matches), signature.filter(MacText.matches)) match
      case (Some(nodeId), Some(sig)) =>
        if body.length > MaxBodyBytes then (Status.PayloadTooLarge, "request body too large")
        else
          val key      = keys.key(nodeId)
          val expected = NodeHandles.hex(NodeHandles.hmac(key, body))
          if !MessageDigest.isEqual(
              expected.getBytes(StandardCharsets.US_ASCII),
              sig.getBytes(StandardCharsets.US_ASCII)
            )
          then refused(nodeId, "signature does not verify")
          else
            parsed(body) match
              case Left(why) => (Status.BadRequest, why)
              case Right(r)  =>
                NodeHandles.verify(r.handle, key, nodeId, clock()) match
                  case Left(why) => refused(nodeId, s"handle refused: $why")
                  case Right(h)  => judge(nodeId, key, h, r)
      case _ =>
        refused(
          node.filter(NodeIdText.matches).getOrElse("unknown"),
          "missing or malformed X-QoD-Node or X-QoD-Signature"
        )

  private def refused(nodeId: String, why: String): (Status, String) =
    logger.warn(s"authz decide: refused a call from node '$nodeId': $why")
    (Status.Unauthorized, why)

  private def judge(
      nodeId: String,
      key: Array[Byte],
      h: VerifiedHandle,
      r: DecideRequest
  ): (Status, String) =
    val p        = h.principal
    val needsCtx = !(p.superuser || p.system)
    val context  = if needsCtx then minted.get(h.nonce) else None
    if needsCtx && context.isEmpty then
      logger.info(s"authz decide: no context for a handle of ${p.name} on node '$nodeId'")
      (Status.ServiceUnavailable, AuthzDecider.ContextExpired)
    else if context.exists(c => !consistent(h, c)) then
      refused(nodeId, "handle does not match its minted context")
    else
      val who         = DecidePrincipal(p, context)
      val fingerprint = NodeHandles.hex(MessageDigest.getInstance("SHA-256").digest(r.description))
      val brief       = s"${r.kind} ${fingerprint.take(12)} for ${p.name} on node '$nodeId'"
      // Whatever a decision fails to read, thrown included, is unavailable: never a verdict. A
      // screen verdict expires at once; a plan verdict lives for the decider's lifetime.
      val (decision, lifetimeMs) =
        try
          if r.kind == "plan" then
            val d = Description
              .decode(r.description)
              .fold(
                why => Decision.Refuse(s"malformed description: $why"),
                decider.decidePlan(who, _)
              )
            (d, decider.lifetimeMs(who))
          else
            val d = ScreenDescription
              .decode(r.description)
              .fold(
                why => Decision.Refuse(s"malformed description: $why"),
                decider.decideScreen(who, _)
              )
            (d, 0L)
        catch
          case NonFatal(e) =>
            logger.warn(s"authz decide: $brief failed (${e.getClass.getSimpleName})")
            (Decision.Unavailable(DecisionFailed), 0L)
      decision match
        case Decision.Unavailable(reason) =>
          logger.warn(s"authz decide: $brief unavailable: $reason")
          (Status.ServiceUnavailable, reason)
        case d =>
          val (verdict, reason) = d match
            case Decision.Refuse(why) => ("refuse", why)
            case _                    => ("allow", "")
          if verdict == "refuse" then logger.info(s"authz decide: $brief refused: $reason")
          else logger.debug(s"authz decide: $brief allowed")
          val v = Verdict(
            kind = r.kind,
            decision = verdict,
            reason = reason,
            retryable = false,
            principal = p.id,
            policyVersion = h.policyVersion,
            fingerprint = fingerprint,
            expiresAt = clock() + lifetimeMs,
            ctx = h.ctx
          )
          (Status.Ok, Verdicts.sign(v, key))

  /** The registered context is the one the handle was minted with. */
  private def consistent(h: VerifiedHandle, c: DecideContext): Boolean =
    c.effective.user.id == h.principal.id && NodeHandles.pool(c.poolKey) == h.pool &&
      NodeHandles.contextDigest(Some(c)) == h.ctx

  private def parsed(body: Array[Byte]): Either[String, DecideRequest] =
    parse(new String(body, StandardCharsets.UTF_8)) match
      case Left(_)     => Left("malformed request body")
      case Right(json) =>
        val c = json.hcursor
        for
          d <- c.get[String]("description").left.map(_ => "request has no description")
          h <- c.get[String]("handle").left.map(_ => "request has no handle")
          k <- c.get[String]("kind").left.map(_ => "request has no kind")
          _ <- Either.cond(
            k == "plan" || k == "screen",
            (),
            "request kind is neither plan nor screen"
          )
          b <-
            try Right(Base64.getUrlDecoder.decode(d))
            catch case _: IllegalArgumentException => Left("description is not base64url")
        yield DecideRequest(b, h, k)

object DecideRoute:

  val Path: String = "/api/internal/authz/decide"

  /** A decision that threw (a store read failed mid-decision): retryable. */
  val DecisionFailed: String = "authorization decision unavailable; retry"

  /** The largest request accepted: a description is a few kilobytes. */
  val MaxBodyBytes: Int = 1 << 20

  private val NodeIdText = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}".r
  private val MacText    = "[0-9a-f]{64}".r
