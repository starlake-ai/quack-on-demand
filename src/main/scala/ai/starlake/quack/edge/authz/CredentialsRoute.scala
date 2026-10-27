package ai.starlake.quack.edge.authz

import ai.starlake.quack.model.UserS3Credential
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import io.circe.Json
import io.circe.parser.parse
import org.http4s.{HttpRoutes, Status}
import org.http4s.dsl.io.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/** `POST /api/internal/authz/credentials` (decision 23): a node fetches the S3 credentials of the
  * principal its connection's handle names, to install them as that connection's secrets
  * `qod_uc_<id8>`. Mounted ahead of the manager's api-key guard (a node has no session); every call
  * authenticates itself, in order, each failure answered before the next step reads anything:
  *
  *   1. `X-QoD-Node` names the node and `X-QoD-Signature` is the lowercase hex HMAC-SHA256 of the
  *      body bytes under that node's key: both headers are checked before the body is read, the MAC
  *      (constant-time) before it is parsed ([[NodeCalls.serve]]);
  *   2. the body's `handle` verifies under the same key for the same node ([[NodeHandles.verify]]);
  *   3. the handle is one this replica minted, for that node and that principal
  *      ([[IssuedHandles]]): a node user who read the node key cannot mint a handle of their own
  *      and fetch another user's keys.
  *
  * Answers `200 text/plain` with `<base64url(JSON)>.<hex HMAC-SHA256(node key, JSON bytes)>` (the
  * verdicts' format, decision 14), the JSON canonical: `{"credentials":[{"endpoint","id","keyId",
  * "region","scope","secret","sessionToken","urlStyle","useSsl"}],"creds":"<digest>","v":1}`, every
  * member present (null when unset), by id, `creds` the digest of the set served; 401 when the node
  * or the handle fails; 503 [[CredentialsRoute.ContextExpired]] for a handle this replica did not
  * mint or no longer remembers, and 503 [[CredentialsRoute.Unavailable]] while the principal's set
  * cannot be served ([[UserS3Credentials.current]]: a stale cache, an unreadable deny set), both
  * retryable; 400 for a malformed request; 413 past [[CredentialsRoute.MaxBodyBytes]]. The system
  * principal has no credential: an empty set. The log never holds the body, the handle, the
  * signature, the answer or a key; a node's repeated refusals go to DEBUG
  * ([[NodeCalls.RefusalLog]]).
  */
final class CredentialsRoute(
    keys: NodeKeys,
    issued: IssuedHandles,
    creds: UserS3Credentials,
    clock: () => Long = () => System.currentTimeMillis()
) extends LazyLogging:
  import CredentialsRoute.*

  private val refusals = new NodeCalls.RefusalLog(logger)

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "api" / "internal" / "authz" / "credentials" =>
      NodeCalls.serve(req, MaxBodyBytes)(refused)(answer)
  }

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
      handleOf(body) match
        case Left(why)   => (Status.BadRequest, why)
        case Right(text) =>
          NodeHandles.verify(text, key, nodeId, clock()) match
            case Left(why) => refused(nodeId, s"handle refused: $why")
            case Right(h)  => serve(nodeId, key, h)

  private def serve(nodeId: String, key: Array[Byte], h: VerifiedHandle): (Status, String) =
    val p = h.principal
    if p.system then (Status.Ok, signed(key, Nil))
    else
      issued.get(h.nonce) match
        case Some(i) if i.node == nodeId && i.principalId == p.id =>
          creds.current(p.id) match
            case Right(set) =>
              logger.info(s"authz credentials: ${set.size} for ${p.name} on node '$nodeId'")
              (Status.Ok, signed(key, set))
            case Left(why) =>
              logger.warn(s"authz credentials: none served to ${p.name} on node '$nodeId': $why")
              (Status.ServiceUnavailable, Unavailable)
        case Some(_) => refused(nodeId, "handle does not match the one this replica minted")
        case None    =>
          logger.info(s"authz credentials: no minted handle for ${p.name} on node '$nodeId'")
          (Status.ServiceUnavailable, ContextExpired)

  private def refused(nodeId: String, why: String): (Status, String) =
    refusals.refused(nodeId, s"authz credentials: refused a call from node '$nodeId': $why")
    (Status.Unauthorized, why)

  private def signed(key: Array[Byte], set: List[UserS3Credential]): String =
    val bytes =
      CanonicalJson
        .render(json(set, UserS3Credentials.digestOf(set)))
        .getBytes(StandardCharsets.UTF_8)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes) + "." +
      NodeHandles.hex(NodeHandles.hmac(key, bytes))

  private def handleOf(body: Array[Byte]): Either[String, String] =
    parse(new String(body, StandardCharsets.UTF_8)) match
      case Left(_)     => Left("malformed request body")
      case Right(json) => json.hcursor.get[String]("handle").left.map(_ => "request has no handle")

object CredentialsRoute:

  val Path: String = "/api/internal/authz/credentials"

  /** The largest request accepted: a handle is under a kilobyte. */
  val MaxBodyBytes: Int = 64 * 1024

  /** A handle this replica did not mint, or no longer remembers: the decide endpoint's wording. */
  val ContextExpired: String = "authorization context expired; retry"

  /** The principal's credentials cannot be vouched for now (the store or the data-folder deny set
    * could not be read): retryable.
    */
  val Unavailable: String = "S3 credentials unavailable; retry"

  /** The answer's JSON, before its canonical rendering: the set by id, every member present. */
  def json(set: List[UserS3Credential], digest: String): Json =
    def opt(v: Option[String]): Json = v.fold(Json.Null)(Json.fromString)
    Json.obj(
      "v"           -> Json.fromInt(1),
      "creds"       -> Json.fromString(digest),
      "credentials" -> Json.arr(set.sortBy(_.id).map { c =>
        Json.obj(
          "id"           -> Json.fromString(c.id),
          "scope"        -> Json.fromString(c.scope),
          "keyId"        -> Json.fromString(c.accessKeyId),
          "secret"       -> Json.fromString(c.secretAccessKey),
          "sessionToken" -> opt(c.sessionToken),
          "region"       -> opt(c.region),
          "endpoint"     -> opt(c.endpoint.map(_ => c.endpointHost)),
          "urlStyle"     -> opt(c.urlStyle),
          "useSsl"       -> Json.fromBoolean(c.useSsl)
        )
      }*)
    )
