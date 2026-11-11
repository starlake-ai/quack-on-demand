package ai.starlake.quack.edge

import ai.starlake.quack.edge.auth.{
  AuthFailure,
  AuthScope,
  AuthenticatedProfile,
  AuthenticationService,
  IdentityNaming
}
import ai.starlake.quack.model.{PoolKey, Tenant}
import ai.starlake.quack.ondemand.api.SessionTokenStore
import ai.starlake.quack.ondemand.rbac.{AuthorizedHandshake, AuthzRequest, HandshakeDenial}
import ai.starlake.quack.ondemand.telemetry.{HandshakeAudit, HandshakeRefusal}
import com.typesafe.scalalogging.LazyLogging

/** Why a handshake was refused. `Unauthenticated` covers a bad or missing credential and an
  * unresolvable target; `Unauthorized` means the principal is real but holds no grant on the pool;
  * `Unavailable` means the authorization service (OPA) could not be reached, or the password could
  * not be checked; retryable. The FlightSQL edge maps them to `CallStatus.UNAUTHENTICATED` /
  * `UNAUTHORIZED` / `UNAVAILABLE`, the Quack front door to an `ERROR_RESPONSE`.
  */
enum HandshakeFailure(val message: String):
  case Unauthenticated(msg: String) extends HandshakeFailure(msg)
  case Unauthorized(msg: String)    extends HandshakeFailure(msg)
  case Unavailable(msg: String)     extends HandshakeFailure(msg)

/** A validated, authorized principal bound to one (tenant, tenantDb, pool). `grants` holds the
  * effective set resolved here and resolves it again once the tenant's policy version moves
  * ([[SessionGrants]]), so a statement never runs under grants a revoke took away; `profile` is the
  * auth chain's view of the principal.
  */
final case class HandshakeBound(
    poolKey: PoolKey,
    user: String,
    grants: SessionGrants,
    profile: Option[AuthenticatedProfile]
)

/** The credential-to-session handshake shared by every data-plane front door (Arrow FlightSQL,
  * native Quack). Transport-agnostic: callers hand it the pieces their wire carries (a bearer, a
  * Basic pair, the tenant and pool selectors, the superuser flag) and get either a bound principal
  * or a typed failure. Extracted from `FlightEdgeServer`; the gate order is unchanged:
  *
  *   1. pre-resolve the (tenant, pool) target so the Basic chain can look up the right
  *      `qodstate_user` row (a same-named user in two tenants is two principals);
  *   2. validate the credential against the configured chain (with no provider configured, refuse
  *      every connection), and refuse as unauthenticated a profile whose user name names no one: a
  *      blank name, or one that came from an email claim the identity provider does not mark
  *      verified ([[ai.starlake.quack.edge.auth.IdentityNaming]], the rule the management login
  *      applies too);
  *   3. re-resolve the target with the validated username;
  *   4. run the user-scope and pool-access gates and compute the EffectiveSet, the tenant's policy
  *      version read first ([[SessionGrants.authorize]]).
  *
  * @param lookupPool
  *   `(tenant, pool) -> tenantDb`, enforcing the tenant and pool kill switches.
  * @param resolveTenant
  *   resolves a wire tenant (the tenant id, case-insensitive) to the tenant.
  * @param authorize
  *   gates 2-4 over an [[ai.starlake.quack.ondemand.rbac.AuthzRequest AuthzRequest]]; its
  *   `superuserAdmissible` is false when the credential was validated by a TENANT realm, so such a
  *   principal can never bind to a tenant-IS-NULL superuser row. A `HandshakeDenial.Unavailable`
  *   (OPA unreachable) becomes `HandshakeFailure.Unavailable`.
  * @param versions
  *   each tenant's policy version and the replica's policy generation: a bound session's grants are
  *   resolved again through `authorize` once either moves ([[SessionGrants]]). Required: a source
  *   that never moves would keep every session on its handshake's grants.
  * @param sessionLookup
  *   what the session store says of a bearer. With `JWT_SECRET_KEY` equal to the session secret
  *   (the Starflow pairing) the bearer chain accepts a management session, and only the store can
  *   tell one from an external token signed with that key and say whether it was signed out. Such a
  *   session is held to what it was minted for: its own or a managed tenant, and a superuser row
  *   only when it is a superuser's. Required: without it a session could name any row.
  * @param edge
  *   the front door's name (`flightsql`, `quack`), carried into the OPA input's `client.edge` and
  *   the origin of its audit rows.
  * @param audit
  *   where a refused handshake is recorded (`auth.login.failure`, issue #202): a bad or missing
  *   credential, or a principal with no access to the pool. A handshake that could not be decided
  *   (`Unavailable`) is not a refusal and records nothing. Required: a front door that recorded
  *   nothing would leave its failed logins out of the audit.
  */
final class EdgeHandshake(
    authService: AuthenticationService,
    lookupPool: (String, String) => Either[String, String],
    resolveTenant: String => Option[Tenant],
    authorize: AuthzRequest => Either[HandshakeDenial, AuthorizedHandshake],
    versions: ai.starlake.quack.edge.authz.PolicyClock,
    sessionLookup: String => SessionTokenStore.LookupResult,
    audit: HandshakeAudit,
    edge: String = "flightsql"
) extends LazyLogging:

  /** `client` is the address the connection came from (its host, no port), for the audit row of a
    * refusal; None when the front door cannot tell.
    */
  def authenticate(
      bearer: Option[String],
      basicPair: Option[(String, String)],
      poolHdr: Option[String],
      tenantHdr: Option[String],
      superuser: Boolean,
      client: Option[String]
  ): Either[HandshakeFailure, HandshakeBound] =
    decide(bearer, basicPair, poolHdr, tenantHdr, superuser) match
      case Right(bound)                                                       => Right(bound)
      case Left(EdgeHandshake.Refused(f: HandshakeFailure.Unavailable, _, _)) => Left(f)
      case Left(EdgeHandshake.Refused(failure, reason, who))                  =>
        audit.refused(
          edge,
          HandshakeRefusal(
            user = who.user.orElse(basicPair.map(_._1)),
            tenant = who.tenant.orElse(tenantHdr.map(_.trim)),
            pool = who.pool.orElse(poolHdr.map(_.trim)),
            superuser = superuser,
            client = client,
            reason = reason
          )
        )
        Left(failure)

  /** The handshake proper: a bound principal, or the refusal with its audit reason. */
  private def decide(
      bearer: Option[String],
      basicPair: Option[(String, String)],
      poolHdr: Option[String],
      tenantHdr: Option[String],
      superuser: Boolean
  ): Either[EdgeHandshake.Refused, HandshakeBound] =
    import EdgeHandshake.{Refused, Who}
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

    // The target as far as the client named it, for the audit row of a refusal before the
    // credential named its user.
    val asNamed = Who(
      None,
      resolvedTenant.map(_.id).orElse(preResolved.toOption.map(_.poolKey.tenant)),
      preResolved.toOption.map(_.poolKey.pool)
    )
    def unauthenticated(msg: String, reason: String, who: Who = asNamed) =
      Refused(HandshakeFailure.Unauthenticated(msg), reason, who)

    // Second element: superuserAdmissible, true only when the credential was NOT validated by a
    // tenant-scoped authority. With no provider at all every connection is refused, superusers
    // included: nothing could check a credential. A booted manager never gets there, since
    // BootPreflight.checkEdgeAuthAvailable refuses an empty chain at boot.
    val validation: Either[Refused, (Option[AuthenticatedProfile], Boolean)] =
      if !authService.hasProviders then
        Left(unauthenticated(EdgeHandshake.NoAuthenticator, HandshakeAudit.NoAuthenticator))
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
              .left
              .map(unauthenticated(_, HandshakeAudit.InvalidCredentials))
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
                  .map {
                    case AuthFailure.Unavailable =>
                      Refused(
                        HandshakeFailure.Unavailable(AuthFailure.Unavailable.message),
                        "",
                        asNamed
                      )
                    case AuthFailure.AccountLocked =>
                      unauthenticated(
                        AuthFailure.AccountLocked.message,
                        HandshakeAudit.AccountLocked
                      )
                    case AuthFailure.PasswordChangeRequired =>
                      unauthenticated(
                        AuthFailure.PasswordChangeRequired.message,
                        HandshakeAudit.PasswordChangeRequired
                      )
                    case refused =>
                      unauthenticated(refused.message, HandshakeAudit.InvalidCredentials)
                  }
              case Left(err) =>
                Left(
                  unauthenticated(
                    s"missing tenant scope for Basic auth: $err",
                    HandshakeAudit.UnknownTarget
                  )
                )
          case _ =>
            Left(unauthenticated("no credentials presented", HandshakeAudit.NoCredentials))

    // The bearer as a management session, when it is one this manager signed.
    lazy val session    = bearer.map(sessionLookup)
    lazy val ownSession = session.collect { case SessionTokenStore.LookupResult.Ok(s) => s }

    // The identity itself is refused, before any row is looked up or any JWT role or group is
    // resolved: an address its holder may have typed could name a superuser's row. The database
    // authenticator's profile carries no email claim, so it is never refused here.
    val named: Either[Refused, (Option[AuthenticatedProfile], Boolean)] =
      validation.flatMap {
        case _ if session.exists(EdgeHandshake.signedOut) =>
          Left(unauthenticated(EdgeHandshake.SessionSignedOut, HandshakeAudit.SessionEnded))
        case (Some(p), _) if IdentityNaming.namesNoUser(p) =>
          Left(
            unauthenticated(
              "the token names no user: its user name claim is blank",
              HandshakeAudit.NoUserName
            )
          )
        case (Some(p), _) if !IdentityNaming.userNameMayBindRow(p) =>
          logger.warn(
            s"handshake refused: ${p.authMethod} named the user '${p.username}' by an email " +
              "claim the identity provider does not mark verified"
          )
          Left(
            unauthenticated(
              IdentityNaming.UnverifiedEmailRefusal,
              HandshakeAudit.UnverifiedEmail,
              asNamed.copy(user = Some(p.username))
            )
          )
        case v => Right(v)
      }

    named match
      case Left(failure)                            => Left(failure)
      case Right((profileOpt, superuserAdmissible)) =>
        val resolvedUsername = profileOpt.map(_.username).orElse(basicUsername)
        TenantSelector.resolve(
          bearer = bearer,
          headers = hdrs,
          username = resolvedUsername,
          lookupPool = lookupPool
        ) match
          case Right(resolved)
              if ownSession.exists(
                !EdgeHandshake.mayEnter(_, resolved.poolKey.tenant, resolveTenant)
              ) =>
            logger.warn(
              s"handshake refused: a session of '${resolved.user}' addressed tenant " +
                s"'${resolved.poolKey.tenant}', which it does not belong to"
            )
            // The gate's own text for a user the tenant does not hold: no existence oracle.
            Left(
              Refused(
                HandshakeFailure.Unauthorized(
                  s"permission denied: user '${resolved.user}' is not registered in tenant " +
                    s"'${resolved.poolKey.tenant}'"
                ),
                HandshakeAudit.TenantForbidden,
                Who(resolved)
              )
            )
          case Right(resolved) =>
            // Only the names an identity provider's token carries: never the profile's role, which
            // for a password login is the account kind and would stand for a tenant role of that
            // name (see AuthenticatedProfile).
            val jwtRoles  = profileOpt.map(_.tokenRoles.filter(_.nonEmpty)).getOrElse(Set.empty)
            val jwtGroups = profileOpt.map(_.groups.filter(_.nonEmpty)).getOrElse(Set.empty)
            val jwtClaims = profileOpt.map(_.claims).getOrElse(Map.empty)
            SessionGrants.authorize(
              AuthzRequest(
                resolved.poolKey.tenant,
                resolved.poolKey.pool,
                resolved.user,
                jwtRoles,
                jwtGroups,
                jwtClaims,
                // A session's own standing, never the client's superuser flag.
                superuserAdmissible = superuserAdmissible && ownSession.forall(_.scope.superuser),
                edge = edge
              ),
              authorize,
              versions
            ) match
              case Left(HandshakeDenial.Denied(err)) =>
                Left(
                  Refused(
                    HandshakeFailure.Unauthorized(s"permission denied: $err"),
                    HandshakeAudit.NoPoolAccess,
                    Who(resolved)
                  )
                )
              case Left(HandshakeDenial.Unavailable(err)) =>
                Left(
                  Refused(
                    HandshakeFailure.Unavailable(s"authorization service unavailable: $err"),
                    "",
                    Who(resolved)
                  )
                )
              case Right((_, grants)) =>
                Right(HandshakeBound(resolved.poolKey, resolved.user, grants, profileOpt))
          case Left(err) =>
            Left(
              unauthenticated(
                err,
                HandshakeAudit.UnknownTarget,
                asNamed.copy(user = resolvedUsername)
              )
            )

object EdgeHandshake:

  /** Who a refused handshake named, as far as it got: the user, the tenant id and the pool. */
  private final case class Who(user: Option[String], tenant: Option[String], pool: Option[String])

  private object Who:
    def apply(resolved: Resolved): Who =
      Who(Some(resolved.user), Some(resolved.poolKey.tenant), Some(resolved.poolKey.pool))

  /** A refusal with its audit reason ([[HandshakeAudit]]); the reason of an `Unavailable` is empty,
    * as it is never recorded.
    */
  private final case class Refused(failure: HandshakeFailure, reason: String, who: Who)

  /** The refusal of a management session that was signed out or has expired. */
  val SessionSignedOut: String = "this session has ended: sign in again"

  private def signedOut(result: SessionTokenStore.LookupResult): Boolean = result match
    case SessionTokenStore.LookupResult.Revoked | SessionTokenStore.LookupResult.Expired => true
    case _                                                                               => false

  /** Whether a management session may address `tenant`: a superuser's anywhere, any other only its
    * own tenant or one it manages, compared by resolved id.
    */
  private def mayEnter(
      session: SessionTokenStore.Session,
      tenant: String,
      resolveTenant: String => Option[Tenant]
  ): Boolean =
    session.scope.superuser || {
      val own = (session.profile.tenant.toSet ++ session.scope.manageableTenants)
        .flatMap(t => resolveTenant(t).map(_.id))
      resolveTenant(tenant).exists(t => own(t.id))
    }

  /** The refusal of every connection when the authentication service has no provider. */
  val NoAuthenticator: String =
    "no authenticator is configured on this manager: every connection is refused"
