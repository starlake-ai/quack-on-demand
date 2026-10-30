package ai.starlake.quack.edge.authz

import ai.starlake.quack.model.PoolKey
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import io.circe.parser.parse
import org.http4s.{HttpRoutes, Status}
import org.http4s.dsl.io.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import scala.util.control.NonFatal

/** `POST /api/internal/authz/decide`: a node asks for the verdict of one statement (spec Section 5,
  * decisions 14 and 22). Mounted in front of the manager's api-key guard (a node has no session);
  * every call authenticates itself:
  *
  *   1. `X-QoD-Node` names the node and `X-QoD-Signature` is the lowercase hex HMAC-SHA256 of the
  *      body bytes under that node's key: both headers are checked before the body is read, the MAC
  *      (constant-time) before it is parsed ([[NodeCalls.serve]]);
  *   2. the body's `handle` verifies under the same key for the same node ([[NodeHandles.verify]]);
  *   3. a user's handle names a context this replica minted ([[MintedHandles]]), consistent with
  *      the handle (user, pool, `ctx` digest).
  *
  * A context minted for the OPA dry run ([[DecidePurpose.DryRun]], decision 25) is judged and never
  * run: its plans are refused whatever the rules decide (with their reason, or
  * [[AuthzDecider.DryRunReason]] when they allow), retryably so the node never caches the refusal,
  * and the OPA call the decision made is kept in `dryRuns` under the handle's nonce for the REST
  * handler. The one plan allowed is the router's USE prelude to the pool's own session default path
  * (`paths`, the function [[NodeKeys]] carries into every handle): it runs before the statement and
  * moves nothing.
  *
  * Answers (all `text/plain; charset=UTF-8`): 200 with a signed [[Verdict]] (allow or refuse), 401
  * when the node or the handle fails, 503 when the decision is unavailable (an expired context
  * included), 400 for a malformed request, 413 past [[DecideRoute.MaxBodyBytes]]. The log never
  * holds the body, the handle, the signature, a verdict or a key; a node's repeated refusals go to
  * DEBUG ([[NodeCalls.RefusalLog]]).
  */
final class DecideRoute(
    keys: NodeKeys,
    minted: MintedHandles,
    decider: AuthzDecider,
    dryRuns: DryRunResults,
    paths: PoolKey => String,
    clock: () => Long = () => System.currentTimeMillis()
) extends LazyLogging:
  import DecideRoute.*

  private val refusals = new NodeCalls.RefusalLog(logger)

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "api" / "internal" / "authz" / "decide" =>
      NodeCalls.serve(req, MaxBodyBytes)(refused)(answer)
  }

  private final case class DecideRequest(description: Array[Byte], handle: String, kind: String)

  /** One call whose headers are well formed and whose body is within the bound
    * ([[NodeCalls.serve]]).
    */
  private def answer(nodeId: String, sig: String, body: Array[Byte]): (Status, String) =
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

  private def refused(nodeId: String, why: String): (Status, String) =
    refusals.refused(nodeId, s"authz decide: refused a call from node '$nodeId': $why")
    (Status.Unauthorized, why)

  private def judge(
      nodeId: String,
      key: Array[Byte],
      h: VerifiedHandle,
      r: DecideRequest
  ): (Status, String) =
    val p        = h.principal
    val needsCtx = !(p.superuser || p.system)
    val entry    = if needsCtx then minted.get(h.nonce) else None
    if needsCtx && entry.isEmpty then
      logger.info(s"authz decide: no context for a handle of ${p.name} on node '$nodeId'")
      (Status.ServiceUnavailable, AuthzDecider.ContextExpired)
    else if entry.exists(e => !consistent(h, e)) then
      refused(nodeId, "handle does not match its minted context")
    else
      val who         = DecidePrincipal(p, entry.map(_.context))
      val fingerprint = NodeHandles.hex(MessageDigest.getInstance("SHA-256").digest(r.description))
      val brief       = s"${r.kind} ${fingerprint.take(12)} for ${p.name} on node '$nodeId'"
      // A dry run's plan is judged, never run (judgeDryRun).
      val dryRun = r.kind == "plan" && entry.exists(_.context.purpose == DecidePurpose.DryRun)
      // Whatever a decision fails to read, thrown included, is unavailable: never a verdict. A
      // screen verdict expires at once; a plan verdict lives for the decider's lifetime.
      val (decision, lifetimeMs) =
        try
          if r.kind == "plan" then
            val d = Description
              .decode(r.description)
              .fold(
                why => Decision.Refuse(s"malformed description: $why"),
                plan =>
                  entry match
                    case Some(e) if dryRun => judgeDryRun(h.nonce, who, e.context, plan)
                    case _                 => decider.decidePlan(who, plan)
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
            // The node never caches a retryable refusal: the next dry run of the same statement
            // (same ctx digest, same description) is judged, and recorded, again.
            retryable = dryRun && verdict == "refuse",
            principal = p.id,
            policyVersion = h.policyVersion,
            fingerprint = fingerprint,
            expiresAt = clock() + lifetimeMs,
            ctx = h.ctx
          )
          (Status.Ok, Verdicts.sign(v, key))

  /** A dry run's plan (decision 25). The pool's own USE prelude, which the router sends before
    * every statement, is allowed as for any session. Any other plan is judged with the tenant's OPA
    * asked past its cache; the OPA call's outcome is kept under the handle's nonce, and the answer
    * is a refusal whatever the rules decided: their reason, or [[AuthzDecider.DryRunReason]].
    */
  private def judgeDryRun(
      nonce: String,
      who: DecidePrincipal,
      context: DecideContext,
      d: Description
  ): Decision =
    if isUsePrelude(d, paths(context.poolKey)) then Decision.Allow
    else
      val (decision, asked) = decider.dryRunPlan(who, d)
      asked.foreach(dryRuns.put(nonce, _))
      decision match
        case Decision.Allow => Decision.Refuse(AuthzDecider.DryRunReason)
        case other          => other

  /** The registered context is the one the handle was minted with: its user, its pool and the `ctx`
    * digest computed at mint (never recomputed per call).
    */
  private def consistent(h: VerifiedHandle, e: MintedHandles.Minted): Boolean =
    e.context.effective.user.id == h.principal.id && NodeHandles.pool(
      e.context.poolKey
    ) == h.pool &&
      e.ctx == h.ctx

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

  /** Whether `d` is the router's USE prelude for a pool whose session default path is `path`
    * (`FlightSqlRouter.usePath`, which the describer reports as the `schema` setting's value): one
    * session-scope `schema` setting to exactly that path, and nothing else. No path, no prelude.
    */
  private[authz] def isUsePrelude(d: Description, path: String): Boolean =
    path.nonEmpty && d.objects.isEmpty && d.functions.isEmpty && d.control.isEmpty &&
      d.settings == List(DescribedSetting("schema", "session", Some(path)))
