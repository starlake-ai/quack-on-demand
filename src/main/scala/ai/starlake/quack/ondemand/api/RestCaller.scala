package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.auth.PatPrincipal
import sttp.model.StatusCode

/** Resolves a REST handler's `apiKey` seam (the `Endpoints.authToken` input: `X-API-Key` header,
  * else the `qod_session` cookie) into the [[ExecCaller]] a handler hands the routed executor. It
  * is the one place the catalog preview / data-diff / undrop / restore handlers learn who is
  * running their statement, so it mirrors the credentials `ManagerServer.apiKeyGuard` admits:
  *
  *   - a session JWT: its username, unrestricted (a login session carries no token restriction),
  *     superuser-admissible exactly when the session is a superuser's (`scope.superuser`);
  *   - a personal access token: its owner's username, the PAT's own `TokenRestriction` and its id,
  *     superuser-admissible exactly when the owning row is tenant-less (the token speaks for its
  *     owner's realm, whatever its admin scope), the same shape `McpDataTools.callerFor` builds, so
  *     per-table ACL, column masking, row filtering and the token's pools / maxRows / branchOnly
  *     all apply;
  *   - no credential, or any other token (unknown, expired, revoked, a session that died between
  *     the guard and the handler): 401 `unauthorized`, and the executor must not be called.
  *
  * A REST caller is never [[ExecCaller.system]]: that flag is reserved for the manager's own work.
  */
final class RestCaller(
    sessionOf: String => Option[SessionTokenStore.Session],
    patOf: String => Option[PatPrincipal]
):

  /** The ONE credential resolution (session, then PAT, else 401) shared by the executor caller
    * below, the branch actor ([[BranchHandlers.actorResolver]]) and the profile pool listing.
    */
  def resolve(apiKey: Option[String]): Either[(StatusCode, ErrorResponse), RestCaller.Principal] =
    apiKey
      .flatMap(t =>
        sessionOf(t)
          .map(RestCaller.Principal.Session(_))
          .orElse(patOf(t).map(RestCaller.Principal.Pat(_)))
      )
      .toRight(RestCaller.Unauthorized)

  def apply(
      connectionId: String,
      apiKey: Option[String]
  ): Either[(StatusCode, ErrorResponse), ExecCaller] =
    resolve(apiKey).map(RestCaller.callerOf(connectionId, _))

object RestCaller:

  /** What a REST credential resolved to. */
  enum Principal:
    case Session(session: SessionTokenStore.Session)
    case Pat(pat: PatPrincipal)

  /** The executor caller a resolved principal runs as: what [[RestCaller.apply]] hands the routed
    * executor, and the caller `GET /api/profile/pools` asks the handshake about, so both decide on
    * the same request. Always the principal's own user, never [[ExecCaller.system]].
    */
  def callerOf(connectionId: String, principal: Principal): ExecCaller = principal match
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

  /** With no lookup wired, every credential is refused. */
  def apply(
      sessionOf: String => Option[SessionTokenStore.Session] = _ => None,
      patOf: String => Option[PatPrincipal] = _ => None
  ): RestCaller = new RestCaller(sessionOf, patOf)

  val Unauthorized: (StatusCode, ErrorResponse) =
    StatusCode.Unauthorized -> ErrorResponse(
      "unauthorized",
      "the credential does not resolve to a live session or personal access token"
    )
