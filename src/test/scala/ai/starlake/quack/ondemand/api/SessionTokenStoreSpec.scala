package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.auth.AuthenticatedProfile
import ai.starlake.quack.ondemand.auth.SessionScope
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/** Pins the HA revocation-persistence hooks added to [[SessionTokenStore]]:
  *   - `onRevoke` fires on locally-initiated revocations
  *   - `addRevoked` / `seedRevoked` denylist peer-notified jtis WITHOUT firing `onRevoke`
  */
class SessionTokenStoreSpec extends AnyFlatSpec, Matchers:

  private val profile = AuthenticatedProfile(
    username = "alice",
    role = "admin",
    groups = Set.empty,
    claims = Map.empty,
    authMethod = "db",
    tenant = None
  )
  private val scope = SessionScope(superuser = false, manageableTenants = Set("t-a"))

  /** 32 ASCII bytes -- satisfies HS256 minimum key length. */
  private val TestSecret = "0123456789abcdef0123456789abcdef"

  "SessionTokenStore HA hooks" should "fire onRevoke with the jti and exp on revoke" in {
    var captured = Option.empty[(String, Instant)]
    val store    = new SessionTokenStore(
      secret = TestSecret,
      onRevoke = (jti, exp) => captured = Some((jti, exp))
    )
    val token = store.mintWithScope(profile, scope)
    store.revoke(token)
    captured.isDefined shouldBe true
    store.get(token) shouldBe None
  }

  it should "deny tokens added via addRevoked and seedRevoked without firing onRevoke" in {
    var fired = 0
    val store = new SessionTokenStore(
      secret = TestSecret,
      onRevoke = (_, _) => fired += 1
    )
    val t1                      = store.mintWithScope(profile, scope)
    val t2                      = store.mintWithScope(profile, scope)
    val jtiOf: String => String =
      tok => com.nimbusds.jwt.SignedJWT.parse(tok).getJWTClaimsSet.getJWTID
    val far = Instant.now().plusSeconds(3600)
    store.addRevoked(jtiOf(t1), far)
    store.seedRevoked(List(jtiOf(t2) -> far))
    store.get(t1) shouldBe None
    store.get(t2) shouldBe None
    fired shouldBe 0
  }
