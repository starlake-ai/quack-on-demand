package ai.starlake.quack.edge.auth

import com.nimbusds.jose.{EncryptionMethod, JWEAlgorithm, JWEHeader, JWEObject, Payload}
import com.nimbusds.jose.crypto.ECDHEncrypter
import com.nimbusds.jose.jwk.{Curve, ECKey}

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.util.Try
import scala.util.control.NonFatal

/** Where a JDBC driver's `authenticator=externalbrowser` login hands its token back: the port of
  * the driver's listener on `127.0.0.1`, the driver's ephemeral P-256 public key (a JWK, base64url
  * of its JSON) and the driver's own state, which the driver checks on the way back.
  *
  * The handoff rides inside the manager-signed OAuth `state` (`OidcStateClaims.handoff`, the
  * SQL-token flow's state), so it is bound to that one login: a later console login in the same
  * browser carries no handoff, and nobody but the manager can mint a state that carries one. The
  * callback encrypts the IdP's `id_token` to the key (`ECDH-ES`, `A256GCM`) and redirects the
  * browser to the driver's listener, always on `127.0.0.1`: the host is never the caller's choice.
  * Whoever sends a victim to a start URL naming their own key gets a token encrypted to them, but
  * sent to the victim's own loopback.
  */
final case class DriverHandoff(port: Int, key: String, state: String):
  /** The handoff as one token for a signed state: no field holds a `.`. */
  def encoded: String = s"$port.$key.$state"

object DriverHandoff:
  val CallbackPath = "/qod/callback"

  val MinPort       = 1024
  val MaxPort       = 65535
  val MaxKeyChars   = 512
  val MinStateChars = 16
  val MaxStateChars = 128

  /** Error codes a callback may hand the driver: a fixed set, never text the IdP chose. */
  val OidcNotConfigured = "oidc_not_configured"
  val IdpError          = "idp_error"
  val EncryptionFailed  = "encryption_failed"

  private val B64Url   = "^[A-Za-z0-9_-]+$".r
  private val CodeLike = "^[a-z_]{1,64}$".r

  private def b64url(s: String): Boolean = B64Url.matches(s)

  /** The driver's start parameters, checked. The reason names the parameter, never its value. */
  def parse(
      port: Option[String],
      key: Option[String],
      state: Option[String]
  ): Either[String, DriverHandoff] =
    for
      p <- port
        .flatMap(_.toIntOption)
        .filter(n => n >= MinPort && n <= MaxPort)
        .toRight(s"port must be a number from $MinPort to $MaxPort")
      k <- key
        .filter(k => k.nonEmpty && k.length <= MaxKeyChars && b64url(k))
        .toRight(s"key must be base64url, at most $MaxKeyChars characters")
      _ <- publicKey(k)
      s <- state
        .filter(s => s.length >= MinStateChars && s.length <= MaxStateChars && b64url(s))
        .toRight(s"state must be $MinStateChars to $MaxStateChars base64url characters")
    yield DriverHandoff(p, k, s)

  /** A handoff read back from a signed state, checked again as at start. */
  def decode(encoded: String): Option[DriverHandoff] =
    encoded.split('.') match
      case Array(p, k, s) => parse(Some(p), Some(k), Some(s)).toOption
      case _              => None

  /** The driver's key: a public P-256 EC JWK, base64url of its JSON. */
  def publicKey(key: String): Either[String, ECKey] =
    Try(new String(Base64.getUrlDecoder.decode(key), StandardCharsets.UTF_8)).toOption
      .flatMap(json => Try(ECKey.parse(json)).toOption)
      .filter(_.getCurve == Curve.P_256)
      .filter(!_.isPrivate)
      .toRight("key must be a public P-256 EC JWK")

  /** `idToken` as a compact JWE only the holder of the driver's private key can read. */
  def encrypt(h: DriverHandoff, idToken: String): Either[String, String] =
    publicKey(h.key).flatMap { k =>
      try
        val jwe = new JWEObject(
          new JWEHeader(JWEAlgorithm.ECDH_ES, EncryptionMethod.A256GCM),
          new Payload(idToken)
        )
        jwe.encrypt(new ECDHEncrypter(k.toECPublicKey))
        Right(jwe.serialize())
      catch case NonFatal(e) => Left(e.getClass.getSimpleName)
    }

  private def enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

  private def base(h: DriverHandoff): String =
    s"http://127.0.0.1:${h.port}$CallbackPath?state=${enc(h.state)}"

  def successLocation(h: DriverHandoff, jwe: String): String = s"${base(h)}&result=${enc(jwe)}"

  /** Where a failed login sends the browser, so the driver fails at once rather than at its
    * timeout. A code that is not one of ours (an IdP's `error` parameter) is passed only when it
    * reads as a code, else it becomes [[IdpError]].
    */
  def errorLocation(h: DriverHandoff, code: String): String =
    val shown = if CodeLike.matches(code) then code else IdpError
    s"${base(h)}&error=${enc(shown)}"

  /** The location of a successful login: the token encrypted to the driver, or the error. */
  def deliver(h: DriverHandoff, idToken: String): String =
    encrypt(h, idToken).fold(_ => errorLocation(h, EncryptionFailed), successLocation(h, _))
