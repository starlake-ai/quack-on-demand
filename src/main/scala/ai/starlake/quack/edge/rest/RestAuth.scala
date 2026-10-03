package ai.starlake.quack.edge.rest

import ai.starlake.quack.edge.auth.BearerAuthProvider
import ai.starlake.quack.ondemand.api.{ExecCaller, ScimEndpoints}
import ai.starlake.quack.ondemand.auth.{PatPrincipal, TokenRestriction}
import ai.starlake.quack.ondemand.state.{PatStore, RbacUser}
import com.typesafe.scalalogging.LazyLogging

import java.nio.charset.StandardCharsets.UTF_8

/** The authenticated principal of one REST request: the owner of a PAT, or the provisioned user an
  * OIDC bearer JWT of the path tenant's own provider names.
  *
  * `restriction` is the PAT's own scope, and unrestricted for a JWT (an OIDC token carries no QoD
  * scope; the user's grants still apply in full). `jwtRoles`, `jwtGroups` and `jwtClaims` are what
  * the verified JWT said, empty for a PAT; they reach the handshake through the [[ExecCaller]].
  */
final case class RestPrincipal(
    user: RbacUser,
    restriction: TokenRestriction,
    patId: Option[String],
    jwtRoles: Set[String] = Set.empty,
    jwtGroups: Set[String] = Set.empty,
    jwtClaims: Map[String, String] = Map.empty
)

object RestPrincipal:
  def of(p: PatPrincipal): RestPrincipal = RestPrincipal(p.user, p.restriction, Some(p.patId))

/** Authentication of the REST data edge: a PAT bearer, or a JWT bearer of the path tenant's own
  * OIDC provider.
  *
  * Every failed outcome folds into ONE 401 body ([[RestError.Unauthorized]]): a missing header, two
  * `Authorization` headers, `Basic`, a bearer that is neither shape, an unknown, revoked or expired
  * token, a disabled owner, a JWT the tenant's provider does not verify, and a superuser token. The
  * static `X-API-Key`, a session cookie and `?access_token=` are never read at all, so they fall
  * into the missing-header case. There is no superuser fallback anywhere: a PAT whose owner is
  * tenant-less gets the same 401 as garbage, which is where this edge departs from
  * `McpToolArgs.tenantOf`.
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

  /** The three dot-separated base64url segments of a compact JWS. Anything else is never handed to
    * a verifier; an unsigned (`alg=none`) token, whose last segment is empty, does not match.
    */
  private val JwsShape = "[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+".r

  /** The JWT resolver of an edge that accepts PATs only. */
  val NoOidc: (String, String) => Option[RestPrincipal] = (_, _) => None

  /** The principal behind the request's `Authorization` values, or 401.
    *
    * `resolvePat` is `PatAuthenticator.resolve` in production: it checks the hash, revocation,
    * expiry and the owner's `enabled` flag, and costs one control-plane lookup, so it only runs on
    * a token that passed every local check. `resolveOidc(pathTenant, token)` is
    * [[RestOidc.resolve]]: it only ever consults `pathTenant`'s own provider.
    */
  def authenticate(
      authorization: List[String],
      resolvePat: String => Option[PatPrincipal],
      pathTenant: String = "",
      resolveOidc: (String, String) => Option[RestPrincipal] = NoOidc
  ): Either[RestError, RestPrincipal] =
    authorization match
      case List(header) =>
        ScimEndpoints
          .stripBearer(header)
          .filter(t => t.getBytes(UTF_8).length <= MaxTokenBytes)
          .flatMap { t =>
            if t.startsWith(PatStore.TokenPrefix) then resolvePat(t).map(RestPrincipal.of)
            else if JwsShape.matches(t) then resolveOidc(pathTenant, t)
            else None
          }
          .filter(_.user.tenant.isDefined)
          .toRight(RestError.Unauthorized)
      case _ => Left(RestError.Unauthorized)

  /** Tenant binding (mirroring `McpToolArgs.tenantOf`) and the tools axis (checked like `McpRoutes`
    * checks every MCP tool). An unknown path tenant gets the same 403 as another tenant's, so a
    * token holder cannot probe which tenants exist.
    */
  def admit(p: RestPrincipal, pathTenant: String): Either[RestError, Unit] =
    val own = p.user.tenant.getOrElse("")
    if pathTenant != own then Left(RestError.Forbidden(s"your token is scoped to tenant '$own'"))
    else if !p.restriction.allowsTool(TokenRestriction.RestTool) then
      Left(
        RestError.Forbidden(s"this token does not allow the '${TokenRestriction.RestTool}' tool")
      )
    else Right(())

  /** The executor caller, built as `McpDataTools.callerFor` builds one for a PAT: the connection id
    * stays stable per PAT (per user for a JWT) because `executeWith` opens one router session per
    * id, the restriction is the token's own, `source` tags audit and `SessionOpened` with
    * [[Origin]] and `edge` tags the OPA input's `client.edge` with it too. A JWT's roles, groups
    * and claims ride along, and `superuserAdmissible` is off: neither credential can speak for a
    * tenant-less row. Never `ExecCaller.unrestricted`, never `ExecCaller.system`.
    */
  def callerFor(p: RestPrincipal): ExecCaller =
    ExecCaller(
      connectionId = p.patId.fold(s"rest-oidc-${p.user.id}")(id => s"rest-$id"),
      identity = p.user.username,
      restriction = p.restriction,
      patId = p.patId,
      source = Origin,
      edge = Origin,
      jwtRoles = p.jwtRoles,
      jwtGroups = p.jwtGroups,
      jwtClaims = p.jwtClaims,
      superuserAdmissible = false
    )

/** Bearer JWTs on the REST data edge, validated ONLY by the path tenant's own OIDC provider.
  *
  *   - The provider is the tenant's per-tenant authenticator (`TenantOidcRegistry.forTenant`), so
  *     issuer, audience, signature and JWKS are checked exactly as `OidcBearerAuthenticator` checks
  *     them for that tenant. The manager-wide providers are never consulted, and neither is the
  *     FlightSQL handshake, which trusts the client when no provider is configured: a tenant with
  *     no provider of its own answers 401.
  *   - An `exp` claim is required. The authenticator rejects an expired token but accepts one with
  *     no expiry at all, which would be a bearer valid forever.
  *   - The user the token names must be provisioned in the path tenant and enabled. A tenant-less
  *     superuser row of the same name is never matched, so a tenant administrator who controls the
  *     IdP cannot mint a token for the seeded superuser.
  *
  * `tenantId` resolves a path tenant to the id of a REGISTERED tenant (None otherwise), checked
  * before the registry is asked, so an arbitrary path cannot grow the registry's per-tenant cache.
  * `findUser` is the tenant-scoped login lookup (`PoolSupervisor.findUserForLogin`).
  */
final class RestOidc(
    tenantId: String => Option[String],
    providerFor: String => Option[BearerAuthProvider],
    findUser: (String, String) => Option[RbacUser]
) extends LazyLogging:

  def resolve(pathTenant: String, token: String): Option[RestPrincipal] =
    for
      tid      <- tenantId(pathTenant).filter(_ == pathTenant)
      provider <- providerFor(tid)
      profile  <- provider.authenticate(token) match
        case Right(p) => Some(p)
        case Left(_)  =>
          // The provider's reason may quote claims; only its name is logged.
          logger.debug(s"rest: ${provider.name} refused a bearer JWT for tenant '$tid'")
          None
      if profile.claims.contains("exp")
      user <- findUser(tid, profile.username).filter(u => u.tenant.contains(tid) && u.enabled)
    yield RestPrincipal(
      user = user,
      restriction = TokenRestriction.Unrestricted,
      patId = None,
      jwtRoles = Set(profile.role).filter(_.nonEmpty),
      jwtGroups = profile.groups,
      jwtClaims = profile.claims
    )
