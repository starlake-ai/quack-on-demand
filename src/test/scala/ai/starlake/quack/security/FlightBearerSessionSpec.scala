package ai.starlake.quack.security

import ai.starlake.quack.edge.FlightEdgeServer
import ai.starlake.quack.edge.auth.JwtBearerAuthenticator
import ai.starlake.quack.edge.config.JwtAuthConfig
import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import ai.starlake.quack.edge.policy.ProtectedWriteGuard
import com.nimbusds.jose.{JWSAlgorithm, JWSHeader}
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.{Connection, DriverManager, SQLException}
import java.util.{Date, Properties}

/** A client that sends its identity provider's token on every call (the Arrow Flight SQL JDBC
  * driver's `token=` property, which ignores the session bearer the edge answers with) keeps one
  * session across its calls: a statement it prepares in one call runs in the next. Before, each
  * call bound a session of its own and the driver's first statement was refused with "prepared
  * statement handle does not belong to this session".
  */
class FlightBearerSessionSpec extends AnyFlatSpec with Matchers:

  private val Secret = "flight-bearer-session-spec-secret-0123456789"

  private def token(user: String, secret: String = Secret): String =
    val claims = new JWTClaimsSet.Builder()
      .subject(user)
      .claim("preferred_username", user)
      .expirationTime(new Date(System.currentTimeMillis() + 300_000L))
      .build()
    val jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims)
    jwt.sign(new MACSigner(secret))
    jwt.serialize()

  private def withEdge(body: (String, Int) => Unit): Unit =
    val fix  = SecurityFixtures.freshStore()
    val auth = InMemoryAuthService.realServiceWithChains(
      basic = List(new InMemoryAuthService.InMemoryBasicAuthProvider(fix.store)),
      bearer = List(
        new JwtBearerAuthenticator(
          JwtAuthConfig(secretKey = Secret, publicKeyPath = "", issuer = "", audience = ""),
          "role"
        )
      ),
      tenantOidcRegistry = None
    )
    val h = FlightEdgeHarness.boot(
      fix.store,
      wiring = FlightEdgeHarness.RouterWiring(
        spawnNodes = true,
        metadataFilterRewriter = new MetadataFilterRewriter(enabled = true),
        protectedWriteGuard = ProtectedWriteGuard.disabled
      ),
      authService = Some(auth)
    )
    try body(h.host, h.port)
    finally h.shutdown()

  private def connect(host: String, port: Int, tok: String): Connection =
    Class.forName("org.apache.arrow.driver.jdbc.ArrowFlightJdbcDriver")
    val props = new Properties()
    props.setProperty("token", tok)
    DriverManager.getConnection(
      s"jdbc:arrow-flight-sql://$host:$port/?useEncryption=false" +
        s"&tenant=${SecurityFixtures.TenantId}&pool=${SecurityFixtures.PoolName}",
      props
    )

  "a client sending its bearer on every call" should "run statements and prepared statements" in
    withEdge { (host, port) =>
      val c = connect(host, port, token(SecurityFixtures.AliceUsername))
      try
        (1 to 3).foreach { _ =>
          val rs = c.createStatement().executeQuery("SELECT 1")
          rs.next() shouldBe true
        }
        val ps = c.prepareStatement("SELECT 1")
        (1 to 2).foreach(_ => ps.executeQuery().next() shouldBe true)
        ps.close()
      finally c.close()
    }

  it should "still be refused with a token the chain does not accept" in withEdge { (host, port) =>
    val good = connect(host, port, token(SecurityFixtures.AliceUsername))
    try
      good.createStatement().executeQuery("SELECT 1").next() shouldBe true
      val e = intercept[SQLException] {
        val bad = connect(host, port, token(SecurityFixtures.AliceUsername, "x" * 48))
        try bad.createStatement().executeQuery("SELECT 1")
        finally bad.close()
      }
      e.getMessage should include("UNAUTHENTICATED")
    finally good.close()
  }

  "bearerSessionKey" should "differ per token and per target, and never hold the token" in {
    val t = token(SecurityFixtures.AliceUsername)
    val k = FlightEdgeServer.bearerSessionKey(t, Some("acme"), Some("sales"), false, None)
    k should not include t
    k shouldBe FlightEdgeServer.bearerSessionKey(t, Some("acme"), Some("sales"), false, None)
    List(
      FlightEdgeServer.bearerSessionKey(t + "x", Some("acme"), Some("sales"), false, None),
      FlightEdgeServer.bearerSessionKey(t, Some("globex"), Some("sales"), false, None),
      FlightEdgeServer.bearerSessionKey(t, Some("acme"), Some("bi"), false, None),
      FlightEdgeServer.bearerSessionKey(t, Some("acme"), Some("sales"), true, None),
      FlightEdgeServer.bearerSessionKey(t, Some("acme"), Some("sales"), false, Some("b1"))
    ).foreach(_ should not be k)
  }
