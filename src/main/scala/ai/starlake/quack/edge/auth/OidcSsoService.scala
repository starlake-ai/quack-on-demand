package ai.starlake.quack.edge.auth

import ai.starlake.quack.ManagementOidcConfig

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object OidcSsoService:
  val STATE_COOKIE  = "qod_oidc_state"
  val CALLBACK_PATH = "/api/auth/oidc/callback"

/** Result of `startAuth`: where to send the browser, plus the signed state to set as a cookie. */
final case class OidcAuthRequest(
    redirectLocation: String,
    stateCookie: String,
    stateCookieValue: String
)

/** Result of a successful `completeAuth`: the validated identity + the scope + where to land. */
final case class OidcCallbackResult(
    profile: AuthenticatedProfile,
    scope: OidcScope,
    returnTo: String,
    idToken: String
)

/** Server-side authorization-code flow for admin-UI SSO. Provider-agnostic: endpoints come from
  * `OidcEndpointResolver` (OIDC Discovery). `httpExchange` (token endpoint POST) and
  * `buildValidator` (JWKS id_token validation) are injected so the unit suite runs without network
  * or a live IdP.
  */
class OidcSsoService(
    resolver: OidcEndpointResolver,
    mgmt: ManagementOidcConfig,
    codec: OidcStateCodec,
    roleClaim: String,
    publicBaseUrlOf: () => String,
    httpExchange: (String, String) => Either[String, String],
    buildValidator: OidcEndpoints => OidcBearerAuthenticator,
    nowMillis: () => Long = () => System.currentTimeMillis()
):
  import OidcSsoService.*

  private def enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

  private def scopeTag(s: OidcScope): String = s match
    case OidcScope.System    => "system"
    case OidcScope.Tenant(_) => "tenant"

  def startAuth(
      scope: OidcScope,
      returnTo: String,
      seed: String
  ): Either[OidcSsoError, OidcAuthRequest] =
    resolver.endpointsFor(scope, mgmt).map { ep =>
      val nonce  = codec.genNonce(seed)
      val pkce   = codec.genPkce(seed)
      val tenant = scope match
        case OidcScope.Tenant(t) => Some(t)
        case OidcScope.System    => None
      val claims =
        OidcStateClaims(scopeTag(scope), tenant, nonce, safeReturn(returnTo), nowMillis())
      val state    = codec.sign(claims)
      val location = s"${ep.authorizeUrl}?response_type=code" +
        s"&client_id=${enc(ep.clientId)}" +
        s"&redirect_uri=${enc(publicBaseUrlOf() + CALLBACK_PATH)}" +
        s"&scope=${enc(ep.scopes)}" +
        s"&state=${enc(state)}" +
        s"&nonce=${enc(nonce)}" +
        s"&code_challenge=${enc(pkce.challenge)}" +
        s"&code_challenge_method=S256"
      OidcAuthRequest(location, STATE_COOKIE, state)
    }

  def completeAuth(
      code: String,
      state: String,
      stateCookieValue: String,
      now: Long
  ): Either[OidcSsoError, OidcCallbackResult] =
    if state.isEmpty || state != stateCookieValue then Left(OidcSsoError.InvalidState)
    else
      codec.verify(state, now) match
        case Left(_)       => Left(OidcSsoError.InvalidState)
        case Right(claims) =>
          val scope = claims.tenant match
            case Some(t) => OidcScope.Tenant(t)
            case None    => OidcScope.System
          resolver
            .endpointsFor(scope, mgmt)
            .left
            .map(_ => OidcSsoError.IdpError)
            .flatMap { ep =>
              val form = s"grant_type=authorization_code&code=${enc(code)}" +
                s"&redirect_uri=${enc(publicBaseUrlOf() + CALLBACK_PATH)}" +
                s"&client_id=${enc(ep.clientId)}&client_secret=${enc(ep.clientSecret)}"
              httpExchange(ep.tokenUrl, form).left
                .map(_ => OidcSsoError.IdpError)
                .flatMap { body =>
                  extractField(body, "id_token").toRight(OidcSsoError.IdpError).flatMap { idToken =>
                    buildValidator(ep).authenticate(idToken) match
                      case Left(_)        => Left(OidcSsoError.IdpError)
                      case Right(profile) =>
                        if profile.claims.get("nonce").contains(claims.nonce) then
                          Right(OidcCallbackResult(profile, scope, claims.returnTo, idToken))
                        else Left(OidcSsoError.IdpError)
                  }
                }
            }

  def endSessionUrl(scope: OidcScope, idTokenHint: Option[String]): Option[String] =
    resolver
      .endpointsFor(scope, mgmt)
      .toOption
      .filter(_.endSessionUrl.nonEmpty)
      .map { ep =>
        val base =
          s"${ep.endSessionUrl}?post_logout_redirect_uri=${enc(publicBaseUrlOf() + "/ui/")}" +
            s"&client_id=${enc(ep.clientId)}"
        idTokenHint.fold(base)(t => s"$base&id_token_hint=${enc(t)}")
      }

  /** Only allow local, same-origin return paths (open-redirect guard). */
  private def safeReturn(returnTo: String): String =
    if returnTo.startsWith("/") && !returnTo.startsWith("//") then returnTo else "/ui/"

  private def extractField(json: String, field: String): Option[String] =
    val pattern = s""""$field"\\s*:\\s*"([^"]+)"""".r
    pattern.findFirstMatchIn(json).map(_.group(1))
