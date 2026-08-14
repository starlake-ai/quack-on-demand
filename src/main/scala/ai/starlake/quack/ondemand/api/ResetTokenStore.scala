package ai.starlake.quack.ondemand.api

import com.nimbusds.jose.crypto.{MACSigner, MACVerifier}
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}

import java.security.MessageDigest
import java.time.Instant
import java.util.Date
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** Stateless single-use password-reset token: an HS256 JWT over [[secret]], carrying no server-side
  * row. "Single-use" comes from a custom `pwfp` claim -- a short fingerprint of the `password_hash`
  * at mint time -- rather than from tracking redemption: [[verify]] recomputes the fingerprint from
  * the row's CURRENT hash and rejects on mismatch, so the moment the password changes (including
  * via this very reset), every outstanding link for that user stops verifying.
  *
  * [[subjectOf]] checks signature + `exp` only (no hash), so the reset handler can look the user
  * row up (and read its current hash) before it has anything to fingerprint against.
  *
  * Mirrors [[SessionTokenStore]]'s nimbus HS256 idiom. `clock` is injectable so tests fast-forward
  * without sleeping.
  */
object ResetTokenStore:

  enum Error:
    case Invalid
    case Expired
    case FingerprintMismatch

  private val ClaimPwFp = "pwfp"

  /** `sha256(passwordHash)`, hex-encoded, truncated to 16 chars. Short on purpose: this rides in a
    * JWT claim, and 16 hex chars (64 bits) is plenty to distinguish "hash changed" from "hash
    * unchanged" without bloating the token.
    */
  private def fingerprint(passwordHash: String): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(passwordHash.getBytes("UTF-8"))
    digest.map(b => f"$b%02x").mkString.take(16)

final class ResetTokenStore(
    secret: String,
    ttl: FiniteDuration = 1.hour,
    clock: () => Instant = () => Instant.now()
):
  import ResetTokenStore.{fingerprint, Error}

  private val signer   = new MACSigner(secret)
  private val verifier = new MACVerifier(secret)

  /** Mint a reset link token bound to `userId` and the password hash at mint time. */
  def mint(userId: String, passwordHash: String): String =
    val now    = clock()
    val exp    = now.plus(java.time.Duration.ofMillis(ttl.toMillis))
    val claims = new JWTClaimsSet.Builder()
      .subject(userId)
      .issueTime(Date.from(now))
      .expirationTime(Date.from(exp))
      .claim(ResetTokenStore.ClaimPwFp, fingerprint(passwordHash))
      .build()
    val signed = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims)
    signed.sign(signer)
    signed.serialize()

  /** Signature + expiry check only -- no hash comparison. Used to resolve the token to a userId
    * before the caller has a row (and therefore a current hash) to fingerprint against.
    */
  def subjectOf(token: String): Either[Error, String] =
    parse(token).map(_.getSubject)

  /** Full check: signature, expiry, and that `currentPasswordHash` still fingerprints to the value
    * captured at mint time. Returns the userId on success.
    */
  def verify(token: String, currentPasswordHash: String): Either[Error, String] =
    parse(token).flatMap { claims =>
      val expected = Option(claims.getStringClaim(ResetTokenStore.ClaimPwFp)).getOrElse("")
      if expected == fingerprint(currentPasswordHash) then Right(claims.getSubject)
      else Left(Error.FingerprintMismatch)
    }

  // ---------- internals ----------

  /** Parse + HMAC-verify + expiry-check. Shared by [[subjectOf]] and [[verify]] since both need a
    * live, correctly-signed token before doing anything hash-specific.
    */
  private def parse(token: String): Either[Error, JWTClaimsSet] =
    try
      val jwt = SignedJWT.parse(token)
      if !jwt.verify(verifier) then Left(Error.Invalid)
      else
        val claims = jwt.getJWTClaimsSet
        val exp    = Option(claims.getExpirationTime).map(_.toInstant)
        if exp.forall(_.isBefore(clock())) then Left(Error.Expired)
        else Right(claims)
    catch case _: Throwable => Left(Error.Invalid)
