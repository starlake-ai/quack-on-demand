package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.auth.PatPrincipal
import sttp.model.StatusCode

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Resolves a REST handler's `apiKey` seam (the `Endpoints.authToken` input: `X-API-Key` header,
  * else the `qod_session` cookie) into the [[ExecCaller]] a handler hands the routed executor. It
  * is the one place the catalog preview / data-diff / undrop / restore handlers learn who is
  * running their statement, so it mirrors the credentials `ManagerServer.apiKeyGuard` admits:
  *
  *   - the configured static key (constant-time compare, like `SessionScope.staticKeyLookup`): the
  *     synthetic superuser, unrestricted;
  *   - a session JWT: its username, unrestricted (a login session carries no token restriction),
  *     superuser-admissible exactly when the session is a superuser's (`scope.superuser`);
  *   - a personal access token: its owner's username, the PAT's own `TokenRestriction` and its id,
  *     superuser-admissible exactly when the owning row is tenant-less (the token speaks for its
  *     owner's realm, whatever its admin scope), the same shape `McpDataTools.callerFor` builds, so
  *     per-table ACL, column masking, row filtering and the token's pools / maxRows / branchOnly
  *     all apply;
  *   - any other token (unknown, expired, revoked, a session that died between the guard and the
  *     handler): 401 `unauthorized`. Never the superuser: the executor must not be called.
  *
  * `None` is the static key. Over REST the guard never lets a credential-less request through, and
  * the MCP layer curries `None` for its own static-key principal (`McpPrincipal.rawToken`). A token
  * string is never mistaken for `None`: an unresolvable one is refused.
  */
final class RestCaller(
    staticKey: Option[String],
    sessionOf: String => Option[SessionTokenStore.Session],
    patOf: String => Option[PatPrincipal]
):
  private val staticBytes =
    staticKey.filter(_.nonEmpty).map(_.getBytes(StandardCharsets.UTF_8))

  /** Constant-time match against the configured static key; an empty key never matches. */
  def isStaticKey(token: String): Boolean =
    staticBytes.exists(k => MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), k))

  /** The ONE credential resolution (static key, then session, then PAT, else 401) shared by the
    * executor caller below and the branch actor ([[BranchHandlers.actorResolver]]).
    */
  def resolve(apiKey: Option[String]): Either[(StatusCode, ErrorResponse), RestCaller.Principal] =
    apiKey match
      case None                      => Right(RestCaller.Principal.System)
      case Some(t) if isStaticKey(t) => Right(RestCaller.Principal.System)
      case Some(t)                   =>
        sessionOf(t) match
          case Some(s) => Right(RestCaller.Principal.Session(s))
          case None    =>
            patOf(t) match
              case Some(p) => Right(RestCaller.Principal.Pat(p))
              case None    => Left(RestCaller.Unauthorized)

  def apply(
      connectionId: String,
      apiKey: Option[String]
  ): Either[(StatusCode, ErrorResponse), ExecCaller] =
    resolve(apiKey).map(RestCaller.callerOf(connectionId, _))

object RestCaller:

  /** What a REST credential resolved to. `System` = the static key (or the absent credential). */
  enum Principal:
    case System
    case Session(session: SessionTokenStore.Session)
    case Pat(pat: PatPrincipal)

  /** The executor caller a resolved principal runs as: what [[RestCaller.apply]] hands the routed
    * executor, and the caller `GET /api/profile/pools` asks the handshake about, so both decide on
    * the same request.
    */
  def callerOf(connectionId: String, principal: Principal): ExecCaller = principal match
    case Principal.System     => ExecCaller.system(connectionId)
    case Principal.Session(s) =>
      ExecCaller.unrestricted(
        connectionId,
        s.profile.username,
        superuserAdmissible = s.scope.superuser
      )
    case Principal.Pat(p) =>
      ExecCaller(
        connectionId,
        p.user.username,
        p.restriction,
        superuserAdmissible = p.user.tenant.isEmpty,
        patId = Some(p.patId)
      )

  /** A resolver that knows no credential but the static key (`None`): every token is refused. */
  val staticOnly: RestCaller = new RestCaller(None, _ => None, _ => None)

  def apply(
      staticKey: Option[String] = None,
      sessionOf: String => Option[SessionTokenStore.Session] = _ => None,
      patOf: String => Option[PatPrincipal] = _ => None
  ): RestCaller = new RestCaller(staticKey, sessionOf, patOf)

  val Unauthorized: (StatusCode, ErrorResponse) =
    StatusCode.Unauthorized -> ErrorResponse(
      "unauthorized",
      "the credential does not resolve to a live session, token or the static key"
    )
