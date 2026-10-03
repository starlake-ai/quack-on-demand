package ai.starlake.quack.edge.rest

import ai.starlake.quack.edge.auth.{BearerAuthProvider, OidcBearerAuthenticator}
import ai.starlake.quack.ondemand.auth.TokenRestriction
import ai.starlake.quack.ondemand.state.RbacUser
import ai.starlake.quack.security.{JwtTestSigner, MockOidcServer}
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.{Duration, Instant}
import java.util.Date
import scala.collection.mutable.ListBuffer

/** Bearer JWTs on the REST data edge, verified by a real [[OidcBearerAuthenticator]] against a
  * WireMock IdP: only the path tenant's own provider is asked, an `exp` claim is required, and the
  * user must be provisioned, enabled and tenant-scoped in the path tenant.
  */
class RestOidcSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private lazy val idp = MockOidcServer.boot()

  override def afterAll(): Unit = idp.shutdown()

  private val Audience = "rest-client"

  private lazy val acmeProvider =
    new OidcBearerAuthenticator("keycloak", s"${idp.baseUrl}/jwks", idp.issuer, Audience, "role")

  /** Who asked for which tenant's provider. */
  private val asked = ListBuffer.empty[String]

  private val users = List(
    RbacUser("u-alice", Some("acme"), "alice", "user"),
    RbacUser("u-bob", Some("acme"), "bob", "user", enabled = false),
    // A tenant-less superuser row: the tenant lookup returns it when no acme row of that name
    // exists, exactly as `findUserForLogin` does.
    RbacUser("u-root", None, "root", "admin"),
    RbacUser("u-gina", Some("globex"), "gina", "user")
  )

  private lazy val oidc = new RestOidc(
    tenantId = t => Set("acme", "globex").find(_ == t.toLowerCase),
    providerFor = t =>
      asked += t
      Option.when(t == "acme")(acmeProvider: BearerAuthProvider)
    ,
    findUser = (t, u) =>
      users
        .find(r => r.username == u && r.tenant.contains(t))
        .orElse(users.find(r => r.username == u && r.tenant.isEmpty))
  )

  private def claims(
      sub: String = "alice",
      exp: Option[Instant] = Some(Instant.now().plus(Duration.ofMinutes(5))),
      issuer: String = idp.issuer,
      audience: String = Audience
  ): JWTClaimsSet =
    val b = new JWTClaimsSet.Builder()
      .issuer(issuer)
      .audience(audience)
      .subject(sub)
      .issueTime(Date.from(Instant.now()))
      .claim("role", "analyst")
      .claim("groups", java.util.List.of("finance"))
    exp.foreach(e => b.expirationTime(Date.from(e)))
    b.build()

  "resolve" should "map a verified token of the tenant's provider to the provisioned user" in {
    val p = oidc.resolve("acme", JwtTestSigner.mintRaw(claims())).get
    p.user.id shouldBe "u-alice"
    p.patId shouldBe None
    p.restriction shouldBe TokenRestriction.Unrestricted
    p.jwtRoles shouldBe Set("analyst")
    p.jwtGroups should contain("finance")
    p.jwtClaims.keySet should contain allOf ("exp", "sub", "iss")
  }

  it should "refuse a token without an exp claim" in {
    oidc.resolve("acme", JwtTestSigner.mintRaw(claims(exp = None))) shouldBe None
  }

  it should "refuse an expired token, a foreign issuer and a foreign audience" in {
    val past = Some(Instant.now().minus(Duration.ofHours(1)))
    oidc.resolve("acme", JwtTestSigner.mintRaw(claims(exp = past))) shouldBe None
    oidc.resolve("acme", JwtTestSigner.mintRaw(claims(issuer = "https://evil"))) shouldBe None
    oidc.resolve("acme", JwtTestSigner.mintRaw(claims(audience = "other-app"))) shouldBe None
  }

  it should "refuse a token signed by another key, and an unsigned one" in {
    val rogue  = new RSAKeyGenerator(2048).keyID(JwtTestSigner.keyPair.getKeyID).generate()
    val forged = new SignedJWT(
      new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rogue.getKeyID).build(),
      claims()
    )
    forged.sign(new RSASSASigner(rogue.toRSAPrivateKey))
    oidc.resolve("acme", forged.serialize()) shouldBe None
    val none = JwtTestSigner.unsigned(claims())
    oidc.resolve("acme", none) shouldBe None
    // With a junk signature segment it still is not a signed token.
    oidc.resolve("acme", none + "c2ln") shouldBe None
  }

  it should "refuse a user that is unprovisioned, disabled or a tenant-less superuser" in {
    oidc.resolve("acme", JwtTestSigner.mintRaw(claims(sub = "nobody"))) shouldBe None
    oidc.resolve("acme", JwtTestSigner.mintRaw(claims(sub = "bob"))) shouldBe None
    oidc.resolve("acme", JwtTestSigner.mintRaw(claims(sub = "root"))) shouldBe None
    // A user of another tenant is not provisioned in this one.
    oidc.resolve("acme", JwtTestSigner.mintRaw(claims(sub = "gina"))) shouldBe None
  }

  it should "ask only the path tenant's provider, and never one for an unknown tenant" in {
    asked.clear()
    val token = JwtTestSigner.mintRaw(claims(sub = "gina"))
    // globex is registered but has no provider of its own: no fallback to any other provider.
    oidc.resolve("globex", token) shouldBe None
    oidc.resolve("no_such_tenant", token) shouldBe None
    // The path must name the tenant exactly.
    oidc.resolve("ACME", JwtTestSigner.mintRaw(claims())) shouldBe None
    asked.toList shouldBe List("globex")
  }

  "RestAuth.authenticate" should "hand a JWT-shaped bearer to the path tenant's resolver" in {
    val token = JwtTestSigner.mintRaw(claims())
    val out   = RestAuth.authenticate(List(s"Bearer $token"), _ => None, "acme", oidc.resolve)
    out.map(_.user.id) shouldBe Right("u-alice")
    RestAuth.authenticate(List(s"Bearer $token"), _ => None, "globex", oidc.resolve) shouldBe
      Left(RestError.Unauthorized)
    // An unsigned token never reaches the resolver at all.
    val seen = ListBuffer.empty[String]
    RestAuth.authenticate(
      List(s"Bearer ${JwtTestSigner.unsigned(claims())}"),
      _ => None,
      "acme",
      (_, t) => { seen += t; None }
    ) shouldBe Left(RestError.Unauthorized)
    seen shouldBe empty
  }
