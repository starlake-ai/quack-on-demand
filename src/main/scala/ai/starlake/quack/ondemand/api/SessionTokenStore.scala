package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.auth.AuthenticatedProfile
import ai.starlake.quack.ondemand.auth.SessionScope
import com.nimbusds.jose.crypto.{MACSigner, MACVerifier}
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}

import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.{Base64, Date, UUID}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters._

/** Session token registry for UI logins. Tokens are HS256-signed JWTs over the configured
  * [[secret]]; the server holds no per-session row, only a small in-process denylist of revoked
  * `jti`s. Any replica that shares the secret can verify any token, and a pinned secret across
  * deploys means sessions survive restart.
  *
  * **Lifetime.** JWT `exp` is absolute: a session is good for exactly `maxLifetime` after
  * `mintWithScope`, regardless of activity. Revocation works while the manager process still holds
  * the `jti` in the denylist; the denylist sweeps entries past their own `exp` and is lost on
  * restart. For an absolute kill-all-sessions, rotate `secret` (env `QOD_SESSION_JWT_SECRET`).
  *
  * **Transport.** [[ManagerServer]] admits requests via `X-API-Key` header OR a `qod_session`
  * cookie carrying the same JWT. [[AuthHandlers.login]] sets the cookie HttpOnly + SameSite=Lax;
  * CLI / static-key callers stay header-only.
  *
  * The constructor argument [[secret]] is the raw HMAC key. HS256 requires >= 32 bytes; a shorter
  * key throws at construction. Use [[SessionTokenStore.randomSecret]] for an in-process default.
  *
  * `clock` is injectable so tests fast-forward without sleeping.
  */
object SessionTokenStore:

  /** Cookie name carrying the session JWT to the browser. Public so `ManagerServer` and Tapir
    * outputs name it consistently.
    */
  val CookieName: String = "qod_session"

  val DefaultMaxLifetime: FiniteDuration = 8.hours

  /** Bundle of the claims a verified session token carries. Lives on the companion so the
    * [[LookupResult]] ADT can name it without a path-dependent type.
    */
  final case class Session(
      profile: AuthenticatedProfile,
      scope: SessionScope,
      createdAt: Instant
  )

  /** Differentiated outcome of a token lookup. The legacy `get`/`isAdmin`/etc. callers collapse
    * every non-Ok arm to "absent"; `AuthHandlers.whoami` and similar boundary handlers use this ADT
    * to return precise error codes (`no_session` / `invalid` / `expired` / `revoked`) so the client
    * can tell "I never logged in" apart from "my JWT timed out".
    */
  enum LookupResult:
    case Ok(session: Session)
    case NoSession
    case Invalid
    case Expired
    case Revoked

  /** Generate a random HS256-safe (32-byte) secret encoded as a base64 string. Used as the fallback
    * when no operator secret is configured -- the encoded form is still raw bytes for [[MACSigner]]
    * so JWTs signed with this secret can be verified by any process that holds the same string.
    */
  def randomSecret(): String =
    val bytes = new Array[Byte](32)
    new SecureRandom().nextBytes(bytes)
    Base64.getEncoder.encodeToString(bytes)

final class SessionTokenStore(
    secret: String = SessionTokenStore.randomSecret(),
    maxLifetime: FiniteDuration = SessionTokenStore.DefaultMaxLifetime,
    clock: () => Instant = () => Instant.now(),
    onRevoke: (String, Instant) => Unit = (_, _) => ()
):

  // Absolute max session age from mint. Exposed under the `idleTtl` field
  // name because that's how external callers read it.
  val idleTtl: FiniteDuration = maxLifetime

  /** Cookie-friendly Max-Age (seconds, signed Long matches Tapir's CookieValueWithMeta API). */
  val maxAgeSeconds: Long = maxLifetime.toSeconds

  private val signer   = new MACSigner(secret)
  private val verifier = new MACVerifier(secret)

  // jti -> original exp. Entries are swept past their own exp on revoke
  // calls and on each lookup miss, so the map stays small.
  private val denylist = new ConcurrentHashMap[String, Instant]()

  /** Type alias for the verified-session payload returned by [[get]] / [[lookupResult]]. The actual
    * case class now lives on the companion (so the [[SessionTokenStore.LookupResult]] ADT can name
    * it without a path-dependent type).
    */
  type Session = SessionTokenStore.Session

  /** Mint a JWT carrying the profile + scope. Returns the compact serialization, ready to drop into
    * `X-API-Key` or `Set-Cookie: qod_session=...`.
    */
  def mintWithScope(profile: AuthenticatedProfile, scope: SessionScope): String =
    val now    = clock()
    val exp    = now.plusSeconds(maxAgeSeconds)
    val jti    = UUID.randomUUID().toString
    val claims = new JWTClaimsSet.Builder()
      .subject(profile.username)
      .issueTime(Date.from(now))
      .expirationTime(Date.from(exp))
      .jwtID(jti)
      .claim("tenant", profile.tenant.orNull)
      .claim("role", profile.role)
      .claim("authMethod", profile.authMethod)
      .claim("superuser", scope.superuser)
      .claim("manageableTenants", scope.manageableTenants.toList.sorted.asJava)
      .build()
    val signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims)
    signed.sign(signer)
    signed.serialize()

  /** Resolve a token: verify the HMAC signature, check `exp`, check the denylist. Collapses every
    * failure reason to `None`; callers that need to tell them apart (e.g. `AuthHandlers.whoami`)
    * use [[lookupResult]] instead.
    */
  def get(token: String): Option[Session] = lookup(token)

  /** Resolve a token with a structured failure reason. The categories distinguish:
    *   - [[SessionTokenStore.LookupResult.NoSession]] -- caller sent no token at all
    *   - [[SessionTokenStore.LookupResult.Invalid]] -- not a JWT, bad signature, missing jti
    *   - [[SessionTokenStore.LookupResult.Expired]] -- jwt `exp` is in the past
    *   - [[SessionTokenStore.LookupResult.Revoked]] -- jti was explicitly revoked
    *
    * Order matters: we report `Invalid` before `Expired` so a bad-signature token from a different
    * deploy doesn't look like a routine session timeout (which would tell the user to re-login,
    * masking a config bug).
    */
  def lookupResult(token: String): SessionTokenStore.LookupResult =
    import SessionTokenStore.LookupResult
    if token == null || token.isEmpty then LookupResult.NoSession
    else
      parse(token) match
        case None                => LookupResult.Invalid
        case Some((claims, jti)) =>
          val now = clock()
          val exp = Option(claims.getExpirationTime).map(_.toInstant)
          if exp.exists(_.isBefore(now)) then
            sweepDenylist()
            LookupResult.Expired
          else if denylist.containsKey(jti) then LookupResult.Revoked
          else LookupResult.Ok(reconstruct(claims))

  /** Mark a token revoked. Stores its `jti` in a small denylist that expires alongside the token's
    * natural `exp`. Manager restart wipes the denylist; that's the documented stateless trade-off.
    */
  def revoke(token: String): Unit =
    parse(token).foreach { case (claims, jti) =>
      val exp = Option(claims.getExpirationTime).map(_.toInstant).getOrElse(clock())
      denylist.put(jti, exp)
      sweepDenylist()
      onRevoke(jti, exp)
    }

  /** Denylist a jti learned from a peer replica or the revocation table. Does NOT fire onRevoke:
    * that hook is for locally initiated revocations only, otherwise replicas would echo
    * notifications forever.
    */
  def addRevoked(jti: String, exp: Instant): Unit =
    denylist.put(jti, exp)
    sweepDenylist()

  def seedRevoked(entries: Iterable[(String, Instant)]): Unit =
    entries.foreach { case (jti, exp) => denylist.put(jti, exp) }

  /** True when the token resolves to a session whose `role = admin`. The login handler refuses to
    * mint anything but admin, so a successful lookup is sufficient; the role check stays for
    * forward-compat.
    */
  def isAdmin(token: String): Boolean =
    lookup(token).exists(_.profile.role.equalsIgnoreCase("admin"))

  def isSuperuser(token: String): Boolean =
    lookup(token).exists(_.scope.superuser)

  def canManage(token: String, tenant: String): Boolean =
    lookup(token).exists(s => s.scope.superuser || s.scope.manageableTenants.contains(tenant))

  def scopeOf(token: String): Option[SessionScope] = lookup(token).map(_.scope)

  /** Test hook: current size of the revocation denylist. There's no "live session count" with JWT;
    * sessions are claims on signed tokens, not rows in a map.
    */
  def revokedJtiCount: Int = denylist.size

  // ---------- internals ----------

  private def lookup(token: String): Option[Session] =
    lookupResult(token) match
      case SessionTokenStore.LookupResult.Ok(session) => Some(session)
      case _                                          => None

  /** Parse + HMAC-verify + sanity-check `jti`. Returns None on any failure (malformed JWT, bad
    * signature, missing jti).
    */
  private def parse(token: String): Option[(JWTClaimsSet, String)] =
    try
      val jwt = SignedJWT.parse(token)
      if !jwt.verify(verifier) then None
      else
        val claims = jwt.getJWTClaimsSet
        val jti    = Option(claims.getJWTID).getOrElse("")
        if jti.isEmpty then None else Some((claims, jti))
    catch case _: Throwable => None

  private def reconstruct(claims: JWTClaimsSet): Session =
    val profile = AuthenticatedProfile(
      username = Option(claims.getSubject).getOrElse(""),
      role = Option(claims.getStringClaim("role")).getOrElse("admin"),
      // Groups + claims map were not round-tripped through the JWT -- the
      // management-plane session-checks don't read them.
      groups = Set.empty,
      claims = Map.empty,
      authMethod = Option(claims.getStringClaim("authMethod")).getOrElse("unknown"),
      tenant = Option(claims.getStringClaim("tenant"))
    )
    val scope = SessionScope(
      superuser = Option(claims.getBooleanClaim("superuser")).exists(_.booleanValue),
      manageableTenants = Option(claims.getStringListClaim("manageableTenants"))
        .map(_.asScala.toSet)
        .getOrElse(Set.empty)
    )
    val createdAt = Option(claims.getIssueTime).map(_.toInstant).getOrElse(clock())
    SessionTokenStore.Session(profile, scope, createdAt)

  private def sweepDenylist(): Unit =
    val now = clock()
    denylist.entrySet().removeIf(e => e.getValue.isBefore(now))
