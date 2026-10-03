package ai.starlake.quack.edge.rest

import ai.starlake.quack.ondemand.api.{ExecCaller, ScimEndpoints}
import ai.starlake.quack.ondemand.auth.{PatPrincipal, TokenRestriction}
import ai.starlake.quack.ondemand.state.PatStore

import java.nio.charset.StandardCharsets.UTF_8

/** Authentication of the REST data edge: PAT bearer only.
  *
  * The edge admits exactly one credential shape, `Authorization: Bearer qod_pat_...`, and folds
  * every other outcome into ONE 401 body ([[RestError.Unauthorized]]): a missing header, two
  * `Authorization` headers, `Basic`, a non-PAT bearer, an unknown, revoked or expired token, a
  * disabled owner, and a superuser token. The static `X-API-Key`, a session cookie and
  * `?access_token=` are never read at all, so they fall into the missing-header case. There is no
  * superuser fallback anywhere: a PAT whose owner is tenant-less gets the same 401 as garbage,
  * which is where this edge departs from `McpToolArgs.tenantOf`.
  */
object RestAuth:

  /** Longest bearer token read; a longer one is refused before any store call. */
  val MaxTokenBytes = 8192

  /** This edge's name in the audit origin, statement history, `SessionOpened` and the OPA input's
    * `client.edge`. Not `rest`: that value already means the admin REST API in the audit origin and
    * the branch-creation access probe in the OPA input, and the reserved tool name `rest` on a
    * token's tools axis is a separate namespace.
    */
  val Origin = "rest-data"

  /** The principal behind the request's `Authorization` values, or 401. `resolvePat` is
    * `PatAuthenticator.resolve` in production: it checks the hash, revocation, expiry and the
    * owner's `enabled` flag, and costs one control-plane lookup, so it only runs on a token that
    * passed every local check.
    */
  def authenticate(
      authorization: List[String],
      resolvePat: String => Option[PatPrincipal]
  ): Either[RestError, PatPrincipal] =
    authorization match
      case List(header) =>
        ScimEndpoints
          .stripBearer(header)
          .filter(t => t.startsWith(PatStore.TokenPrefix))
          .filter(t => t.getBytes(UTF_8).length <= MaxTokenBytes)
          .flatMap(resolvePat)
          .filter(_.user.tenant.isDefined)
          .toRight(RestError.Unauthorized)
      case _ => Left(RestError.Unauthorized)

  /** Tenant binding (mirroring `McpToolArgs.tenantOf`) and the tools axis (checked like `McpRoutes`
    * checks every MCP tool). An unknown path tenant gets the same 403 as another tenant's, so a
    * token holder cannot probe which tenants exist.
    */
  def admit(p: PatPrincipal, pathTenant: String): Either[RestError, Unit] =
    val own = p.user.tenant.getOrElse("")
    if pathTenant != own then Left(RestError.Forbidden(s"your token is scoped to tenant '$own'"))
    else if !p.restriction.allowsTool(TokenRestriction.RestTool) then
      Left(
        RestError.Forbidden(s"this token does not allow the '${TokenRestriction.RestTool}' tool")
      )
    else Right(())

  /** The executor caller, built as `McpDataTools.callerFor` builds one for a PAT: the connection id
    * stays stable per PAT because `executeWith` opens one router session per id, the restriction is
    * the token's own, `source` tags audit and `SessionOpened` with [[Origin]] and `edge` tags the
    * OPA input's `client.edge` with it too. Never `ExecCaller.unrestricted`, never
    * `ExecCaller.system`.
    */
  def callerFor(p: PatPrincipal): ExecCaller =
    ExecCaller(
      connectionId = s"rest-${p.patId}",
      identity = p.user.username,
      restriction = p.restriction,
      patId = Some(p.patId),
      source = Origin,
      edge = Origin
    )
