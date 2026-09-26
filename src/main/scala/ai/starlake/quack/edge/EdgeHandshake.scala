package ai.starlake.quack.edge

import ai.starlake.quack.edge.auth.{AuthScope, AuthenticatedProfile, AuthenticationService}
import ai.starlake.quack.model.{PoolKey, Tenant}
import ai.starlake.quack.ondemand.rbac.{AuthorizedHandshake, EffectiveSet}
import com.typesafe.scalalogging.LazyLogging

/** Why a handshake was refused. `Unauthenticated` covers a bad or missing credential and an
  * unresolvable target; `Unauthorized` means the principal is real but holds no grant on the pool.
  * The FlightSQL edge maps them to `CallStatus.UNAUTHENTICATED` / `UNAUTHORIZED`, the Quack front
  * door to an `ERROR_RESPONSE`.
  */
enum HandshakeFailure(val message: String):
  case Unauthenticated(msg: String) extends HandshakeFailure(msg)
  case Unauthorized(msg: String)    extends HandshakeFailure(msg)

/** A validated, authorized principal bound to one (tenant, tenantDb, pool). `effectiveSet` is
  * computed once here and cached on the session so the per-statement ACL gate never re-queries
  * Postgres; `profile` is the auth chain's view of the principal (None in trust-the-client mode).
  */
final case class HandshakeBound(
    poolKey: PoolKey,
    user: String,
    effectiveSet: EffectiveSet,
    profile: Option[AuthenticatedProfile]
)

/** The credential-to-session handshake shared by every data-plane front door (Arrow FlightSQL,
  * native Quack). Transport-agnostic: callers hand it the pieces their wire carries (a bearer, a
  * Basic pair, the tenant and pool selectors, the superuser flag) and get either a bound principal
  * or a typed failure. Extracted from `FlightEdgeServer`; the gate order is unchanged:
  *
  *   1. pre-resolve the (tenant, pool) target so the Basic chain can look up the right
  *      `qodstate_user` row (a same-named user in two tenants is two principals);
  *   2. validate the credential against the configured chain (or trust the client when no provider
  *      is configured, the v1 local-dev posture);
  *   3. re-resolve the target with the validated username;
  *   4. run the user-scope and pool-access gates and compute the EffectiveSet.
  *
  * @param lookupPool
  *   `(tenant, pool) -> tenantDb`, enforcing the tenant and pool kill switches.
  * @param resolveTenant
  *   resolves a wire tenant (the tenant id, case-insensitive) to the tenant.
  * @param authorize
  *   `(tenant, pool, username, jwtRoles, jwtGroups, superuserAdmissible)`; `superuserAdmissible` is
  *   false when the credential was validated by a TENANT realm, so such a principal can never bind
  *   to a tenant-IS-NULL superuser row.
  */
final class EdgeHandshake(
    authService: AuthenticationService,
    lookupPool: (String, String) => Either[String, String],
    resolveTenant: String => Option[Tenant],
    authorize: (String, String, String, Set[String], Set[String], Boolean) => Either[
      String,
      AuthorizedHandshake
    ]
) extends LazyLogging:

  def authenticate(
      bearer: Option[String],
      basicPair: Option[(String, String)],
      poolHdr: Option[String],
      tenantHdr: Option[String],
      superuser: Boolean
  ): Either[HandshakeFailure, HandshakeBound] =
    val basicUsername = basicPair.map(_._1)
    // The wire `tenant` is the tenant id, the key `lookupPool` / `authorize` operate on. It is
    // passed through as is: an unknown tenant then fails the next stage with the usual "pool not
    // found" rather than masking the cause.
    val resolvedTenant: Option[Tenant] = tenantHdr.flatMap(resolveTenant)
    val hdrs                           = poolHdr.map("pool" -> _).toMap ++
      tenantHdr.map("tenant" -> _).toMap
    val preResolved: Either[String, Resolved] = TenantSelector.resolve(
      bearer = bearer,
      headers = hdrs,
      username = basicUsername,
      lookupPool = lookupPool
    )

    // Second element: superuserAdmissible, true only when the credential was NOT validated by a
    // tenant-scoped authority.
    val validation: Either[String, (Option[AuthenticatedProfile], Boolean)] =
      if !authService.hasProviders then Right((None, true))
      else
        (bearer, basicPair) match
          case (Some(token), _) =>
            val bearerScope: AuthScope =
              if superuser then AuthScope.System
              else
                val tenantArg = resolvedTenant
                  .map(_.id)
                  .orElse(preResolved.toOption.map(_.poolKey.tenant))
                tenantArg match
                  case Some(t) => AuthScope.Tenant(t)
                  case None    => AuthScope.System
            authService
              .authenticateBearer(bearerScope, token)
              .map(p => (Some(p), bearerScope == AuthScope.System))
          case (None, Some((_, p))) =>
            preResolved match
              case Right(r) =>
                // qodstate_user.tenant stores the surrogate id, not the display name.
                val scope: AuthScope =
                  if superuser then AuthScope.System
                  else
                    val tenantArg = resolvedTenant
                      .orElse(resolveTenant(r.poolKey.tenant))
                      .map(_.id)
                      .getOrElse(r.poolKey.tenant)
                    AuthScope.Tenant(tenantArg)
                authService
                  .authenticateBasic(scope, r.user, p)
                  .map(profile => (Some(profile), scope == AuthScope.System))
                  .left
                  .map(_.message)
              case Left(err) =>
                Left(s"missing tenant scope for Basic auth: $err")
          case _ =>
            Left("no credentials presented")

    validation match
      case Left(err)                                => Left(HandshakeFailure.Unauthenticated(err))
      case Right((profileOpt, superuserAdmissible)) =>
        val resolvedUsername = profileOpt.map(_.username).orElse(basicUsername)
        // Bearer present but unknown AND no provider claimed it AND no Basic credentials: a
        // stale session token from a prior manager restart.
        if bearer.isDefined && profileOpt.isEmpty && resolvedUsername.isEmpty then
          Left(
            HandshakeFailure.Unauthenticated(
              "session expired; please reconnect with username/password"
            )
          )
        else
          TenantSelector.resolve(
            bearer = bearer,
            headers = hdrs,
            username = resolvedUsername,
            lookupPool = lookupPool
          ) match
            case Right(resolved) =>
              val jwtRoles  = profileOpt.map(_.role).filter(_.nonEmpty).toSet
              val jwtGroups = profileOpt.map(_.groups).getOrElse(Set.empty)
              authorize(
                resolved.poolKey.tenant,
                resolved.poolKey.pool,
                resolved.user,
                jwtRoles,
                jwtGroups,
                superuserAdmissible
              ) match
                case Left(err) =>
                  Left(HandshakeFailure.Unauthorized(s"permission denied: $err"))
                case Right(auth) =>
                  Right(
                    HandshakeBound(resolved.poolKey, resolved.user, auth.effectiveSet, profileOpt)
                  )
            case Left(err) => Left(HandshakeFailure.Unauthenticated(err))
