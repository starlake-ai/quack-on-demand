package ai.starlake.quack.edge.auth

import ai.starlake.quack.security.JwtTestSigner
import com.sun.net.httpserver.HttpServer
import io.circe.Json
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.IOException
import java.net.{InetAddress, InetSocketAddress, ServerSocket}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import scala.jdk.CollectionConverters.*

/** How [[OidcBearerAuthenticator]] reads the key set it verifies tokens against. The URL can come
  * from a discovery document, or from a tenant's own settings, and a tenant admin picks both:
  * anyone can then make the manager fetch it (the SSO callback, a bearer token on the data plane).
  * Nimbus' default retriever bounds the size of the answer, not the time of the read, and reads
  * `file:` URLs too. So the key set is read through [[ai.starlake.quack.edge.BoundedHttp]]: http(s)
  * only, 50 KiB, and 5 s over the whole read.
  */
class OidcBearerAuthenticatorJwksSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val Issuer   = "https://issuer.example"
  private val Audience = "qod-console"
  private val KiB      = 1024

  private val idp = new LocalIdp

  // A key set that arrives one byte every 300 ms: never over the size limit, never silent for as
  // long as a per-read timeout would take to notice.
  private val trickleEnded                = new CountDownLatch(1)
  private val executor                    = Executors.newCachedThreadPool()
  private val trickleServer               = HttpServer.create(loopback, 0)
  private def loopback: InetSocketAddress = new InetSocketAddress(InetAddress.getLoopbackAddress, 0)
  private def trickleBase                 = s"http://127.0.0.1:${trickleServer.getAddress.getPort}"
  private val keySet                      = JwtTestSigner.jwksJson()
  private def keySetPadded(bytes: Int): String =
    keySet.deepMerge(Json.obj("pad" -> Json.fromString("x" * bytes))).noSpaces

  override def beforeAll(): Unit =
    idp.serve("/jwks", 200, keySet.noSpaces)
    idp.serve("/jwks-49k", 200, keySetPadded(49 * KiB))
    idp.serve("/jwks-60k", 200, keySetPadded(60 * KiB))
    idp.serve("/jwks-500", 500, "boom")
    trickleServer.setExecutor(executor)
    trickleServer.createContext(
      "/jwks",
      ex =>
        ex.sendResponseHeaders(200, 40000L)
        try
          val out = ex.getResponseBody
          var i   = 0
          while i < 40000 do
            out.write(' ')
            out.flush()
            Thread.sleep(300)
            i += 1
        catch case _: IOException => ()
        trickleEnded.countDown()
        ex.close()
    )
    // A key set URL that answers 302 to the real one, as a proxy in front of an identity provider
    // might.
    trickleServer.createContext(
      "/redirect",
      ex =>
        ex.getResponseHeaders.add("Location", s"${idp.base}/jwks")
        ex.sendResponseHeaders(302, -1L)
        ex.close()
    )
    trickleServer.start()

  override def afterAll(): Unit =
    trickleServer.stop(0)
    executor.shutdownNow()
    idp.close()
    ()

  private def authenticatorFor(jwksUrl: String) =
    new OidcBearerAuthenticator("p", jwksUrl, Issuer, Audience, "role")

  private def token: String =
    JwtTestSigner.mint(Map("sub" -> "alice"), issuer = Issuer, audience = Some(Audience))

  private def jwksGets: Int = idp.requests.asScala.count(_._1.startsWith("/jwks"))

  "OidcBearerAuthenticator" should "verify a token against a key set it reads over http, and serve a second token from its cache" in {
    val before        = jwksGets
    val authenticator = authenticatorFor(s"${idp.base}/jwks")

    authenticator.authenticate(token).map(_.username) shouldBe Right("alice")
    authenticator.authenticate(token).map(_.username) shouldBe Right("alice")

    (jwksGets - before) shouldBe 1
  }

  it should "read a key set just under 50 KiB, and refuse one over it" in {
    authenticatorFor(s"${idp.base}/jwks-49k").authenticate(token).map(_.username) shouldBe
      Right("alice")

    val refused = authenticatorFor(s"${idp.base}/jwks-60k").authenticate(token)
    refused.isLeft shouldBe true
    refused.left.toOption.exists(_.contains("token validation failed")) shouldBe true
  }

  it should "refuse a token when the key set answers a status other than 200" in {
    authenticatorFor(s"${idp.base}/jwks-500").authenticate(token) match
      case Left(why) => why should include("JWKS HTTP 500")
      case Right(p)  => fail(s"a token was accepted as '${p.username}' on a failed key set")
  }

  it should "not follow a redirect to the key set: the JDK client follows none, so a URL must name the key set itself" in {
    val before = jwksGets

    authenticatorFor(s"$trickleBase/redirect").authenticate(token) match
      case Left(why) => why should include("JWKS HTTP 302")
      case Right(p)  => fail(s"a token was accepted as '${p.username}' through a redirect")

    (jwksGets - before) shouldBe 0
  }

  it should "give up on a key set that trickles, within about the 5 s the read is allowed" in {
    val authenticator = authenticatorFor(s"$trickleBase/jwks")
    val t0            = System.nanoTime()
    val out = PlainThread.run(stackKiB = 1024, waitSec = 25)(authenticator.authenticate(token))
    val ms  = (System.nanoTime() - t0) / 1000000L

    out match
      case Right(Left(why)) => why should include("token validation failed")
      case other            => fail(s"expected a refusal, got $other")
    // The deadline ended it: not at once (something else failed), and not at the 12000 s it would
    // take the whole key set to arrive.
    ms should (be >= 4000L and be < 10000L)
    // The cancel released the connection: the handler, with hours of bytes to go, saw it break.
    trickleEnded.await(10, TimeUnit.SECONDS) shouldBe true
  }

  it should "refuse a file: key set URL without reading it" in {
    // The file holds the very key set the token is signed against: a reader that opened it would
    // verify the token, so the refusal shows that nothing was read.
    val file = Files.createTempFile("qod-jwks", ".json")
    try
      Files.write(file, keySet.noSpaces.getBytes(StandardCharsets.UTF_8))
      val refused = authenticatorFor(file.toUri.toString).authenticate(token)

      refused.isLeft shouldBe true
      refused.left.toOption.exists(_.contains("token validation failed")) shouldBe true
    finally Files.delete(file)
  }

  it should "refuse a key set at an address nobody listens on, at once" in {
    val socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress)
    val free   = socket.getLocalPort
    socket.close()
    val t0      = System.nanoTime()
    val refused = authenticatorFor(s"http://127.0.0.1:$free/jwks").authenticate(token)
    val ms      = (System.nanoTime() - t0) / 1000000L

    refused.isLeft shouldBe true
    ms should be < 5000L
  }
