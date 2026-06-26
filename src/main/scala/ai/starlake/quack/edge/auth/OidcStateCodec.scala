package ai.starlake.quack.edge.auth

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

final case class OidcStateClaims(
    scope: String,
    tenant: Option[String],
    nonce: String,
    returnTo: String,
    iatMillis: Long
)

final case class OidcPkce(verifier: String, challenge: String)

/** Stateless, signed `state` for the admin-UI OIDC flow plus PKCE helpers.
  *
  * The state is `base64url(payload).base64url(hmacSha256(payload))`. The payload is a `|`-joined
  * record; fields are base64url-encoded individually so a literal `|` in `returnTo` can't break
  * parsing. TTL is enforced against `iatMillis` at verify time. The signing secret is the
  * management session JWT secret (already required to be set in production).
  */
class OidcStateCodec(secret: String, ttlMillis: Long):

  private val b64  = Base64.getUrlEncoder.withoutPadding()
  private val b64d = Base64.getUrlDecoder

  private def enc(s: String): String = b64.encodeToString(s.getBytes(StandardCharsets.UTF_8))
  private def dec(s: String): String = new String(b64d.decode(s), StandardCharsets.UTF_8)

  private def hmac(payload: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
    b64.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)))

  def sign(claims: OidcStateClaims): String =
    val payload = List(
      enc(claims.scope),
      enc(claims.tenant.getOrElse("")),
      enc(claims.nonce),
      enc(claims.returnTo),
      claims.iatMillis.toString
    ).mkString("|")
    s"${enc(payload)}.${hmac(payload)}"

  def verify(token: String, nowMillis: Long): Either[String, OidcStateClaims] =
    token.split('.') match
      case Array(encPayload, sig) =>
        val payload = dec(encPayload)
        if !constantTimeEq(hmac(payload), sig) then Left("state signature mismatch")
        else
          payload.split('|') match
            case Array(s, t, n, r, iat) =>
              val iatMillis = iat.toLong
              if nowMillis - iatMillis > ttlMillis then Left("state expired")
              else
                Right(
                  OidcStateClaims(
                    scope = dec(s),
                    tenant = Option(dec(t)).filter(_.nonEmpty),
                    nonce = dec(n),
                    returnTo = dec(r),
                    iatMillis = iatMillis
                  )
                )
            case _ => Left("state payload malformed")
      case _ => Left("state token malformed")

  def genNonce(seed: String): String =
    b64.encodeToString(sha256(s"nonce:$seed:$secret"))

  def genPkce(seed: String): OidcPkce =
    val verifier  = b64.encodeToString(sha256(s"verifier:$seed:$secret"))
    val challenge = b64.encodeToString(sha256(verifier))
    OidcPkce(verifier, challenge)

  private def sha256(s: String): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))

  private def constantTimeEq(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8))
