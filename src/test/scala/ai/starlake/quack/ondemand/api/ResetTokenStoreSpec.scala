package ai.starlake.quack.ondemand.api

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.duration.DurationInt

/** Pins the stateless single-use password-reset token: HS256 JWT whose custom `pwfp` claim
  * fingerprints the password hash at mint time. `verify` recomputes the fingerprint from the row's
  * CURRENT hash, so the link stops verifying the moment the password changes -- that's the
  * single-use property, with no server-side row to track redemption.
  */
class ResetTokenStoreSpec extends AnyFlatSpec, Matchers:

  /** 32 ASCII bytes -- satisfies HS256 minimum key length. */
  private val secret = "0123456789abcdef0123456789abcdef"
  private val store  = new ResetTokenStore(secret)

  "mint/verify" should "round-trip the userId when the hash is unchanged" in {
    val t = store.mint("u-1", "hashA")
    store.verify(t, "hashA") shouldBe Right("u-1")
  }

  it should "reject once the password hash changed (single-use)" in {
    val t = store.mint("u-1", "hashA")
    store.verify(t, "hashB") shouldBe Left(ResetTokenStore.Error.FingerprintMismatch)
  }

  it should "reject an expired token" in {
    var now = Instant.parse("2026-01-01T00:00:00Z")
    val s   = new ResetTokenStore(secret, ttl = 1.minute, clock = () => now)
    val t   = s.mint("u-1", "hashA")
    now = now.plusSeconds(120)
    s.verify(t, "hashA") shouldBe Left(ResetTokenStore.Error.Expired)
  }

  it should "reject a token signed with a different secret" in {
    val other = new ResetTokenStore("ffffffffffffffffffffffffffffffff").mint("u-1", "hashA")
    store.verify(other, "hashA") shouldBe Left(ResetTokenStore.Error.Invalid)
  }

  "subjectOf" should "return the userId for a valid unexpired token regardless of hash" in {
    val t = store.mint("u-1", "hashA")
    store.subjectOf(t) shouldBe Right("u-1") // no hash needed
    store.subjectOf("garbage") shouldBe Left(ResetTokenStore.Error.Invalid)
  }
