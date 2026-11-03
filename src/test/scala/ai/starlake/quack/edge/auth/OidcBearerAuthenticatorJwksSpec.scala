package ai.starlake.quack.edge.auth

import ai.starlake.quack.edge.BoundedHttp
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
  * only (a redirect is followed to http(s) alone, never from https to http), 50 KiB, and 5 s over
  * the whole read, every redirect included.
  */
class OidcBearerAuthenticatorJwksSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val Issuer   = "https://issuer.example"
  private val Audience = "qod-console"
  private val KiB      = 1024

  private val idp = new LocalIdp

  // A server that is not well behaved. Its /jwks arrives one byte every 300 ms: never over the size
  // limit, never silent for as long as a per-read timeout would take to notice. Its other paths
  // redirect: to the real key set, to a file: URL, and in a loop.
  private val trickleEnded                = new CountDownLatch(1)
  private val executor                    = Executors.newCachedThreadPool()
  private val oddServer                   = HttpServer.create(loopback, 0)
  private def loopback: InetSocketAddress = new InetSocketAddress(InetAddress.getLoopbackAddress, 0)
  private def oddBase                     = s"http://127.0.0.1:${oddServer.getAddress.getPort}"
  private val keySet                      = JwtTestSigner.jwksJson()
  private def keySetPadded(bytes: Int): String =
    keySet.deepMerge(Json.obj("pad" -> Json.fromString("x" * bytes))).noSpaces

  // The very key set the tokens are signed against, in a file: a reader that opened it would verify
  // a token, so a refusal of a file: URL shows that nothing was read.
  private val keySetFile = Files.createTempFile("qod-jwks", ".json")

  override def beforeAll(): Unit =
    Files.write(keySetFile, keySet.noSpaces.getBytes(StandardCharsets.UTF_8))
    idp.serve("/jwks", 200, keySet.noSpaces)
    idp.serve("/jwks-49k", 200, keySetPadded(49 * KiB))
    idp.serve("/jwks-60k", 200, keySetPadded(60 * KiB))
    idp.serve("/jwks-500", 500, "boom")
    oddServer.setExecutor(executor)
    oddServer.createContext(
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
    oddServer.createContext(
      "/redirect",
      ex =>
        ex.getResponseHeaders.add("Location", s"${idp.base}/jwks")
        ex.sendResponseHeaders(302, -1L)
        ex.close()
    )
    // A 302 to a file: URL, whose file holds the real key set.
    oddServer.createContext(
      "/redirect-file",
      ex =>
        ex.getResponseHeaders.add("Location", keySetFile.toUri.toString)
        ex.sendResponseHeaders(302, -1L)
        ex.close()
    )
    // A 302 to itself, at once.
    oddServer.createContext(
      "/loop",
      ex =>
        ex.getResponseHeaders.add("Location", "/loop")
        ex.sendResponseHeaders(302, -1L)
        ex.close()
    )
    // A 302 to itself, after 1.5 s a hop: the five redirects the JDK follows would take 7.5 s.
    oddServer.createContext(
      "/slow-loop",
      ex =>
        Thread.sleep(1500)
        ex.getResponseHeaders.add("Location", "/slow-loop")
        try ex.sendResponseHeaders(302, -1L)
        catch case _: IOException => () // the client went away
        ex.close()
    )
    oddServer.start()

  override def afterAll(): Unit =
    oddServer.stop(0)
    executor.shutdownNow()
    idp.close()
    Files.deleteIfExists(keySetFile)
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

  // Nimbus' retriever followed a redirect, so a Keycloak base URL on http behind a proxy that
  // redirects to the real address worked, and must go on working. The client follows a redirect to
  // the same scheme, or to https, never from https to http and never to another scheme, and the
  // deadline covers every hop.
  it should "follow a redirect to the key set, as the old retriever did" in {
    val before = jwksGets

    authenticatorFor(s"$oddBase/redirect").authenticate(token).map(_.username) shouldBe
      Right("alice")

    (jwksGets - before) shouldBe 1
  }

  it should "not follow a redirect to a file: URL, and read nothing" in {
    // The file holds the very key set the token is signed against: a reader that opened it would
    // verify the token.
    val refused = authenticatorFor(s"$oddBase/redirect-file").authenticate(token)

    refused.isLeft shouldBe true
    refused.left.toOption.exists(_.contains("token validation failed")) shouldBe true
  }

  it should "end a redirect loop in a refusal, within the 5 s the read is allowed" in {
    val t0      = System.nanoTime()
    val refused = authenticatorFor(s"$oddBase/loop").authenticate(token)
    val ms      = (System.nanoTime() - t0) / 1000000L

    refused match
      case Left(why) => why should include("JWKS HTTP 302")
      case Right(p)  => fail(s"a token was accepted as '${p.username}' through a loop")
    ms should be < BoundedHttp.JwksTimeoutMs
  }

  it should "end a slow redirect loop at the deadline, not after the sum of its hops" in {
    val authenticator = authenticatorFor(s"$oddBase/slow-loop")
    val t0            = System.nanoTime()
    val out = PlainThread.run(stackKiB = 1024, waitSec = 25)(authenticator.authenticate(token))
    val ms  = (System.nanoTime() - t0) / 1000000L

    out match
      case Right(Left(why)) => why should include("JWKS request timed out after 5000 ms")
      case other            => fail(s"expected the deadline to end it, got $other")
    // Five hops of 1.5 s would take 7.5 s; the deadline runs over the whole exchange.
    ms should (be >= 4000L and be < 6500L)
  }

  it should "give up on a key set that trickles, within about the 5 s the read is allowed" in {
    val authenticator = authenticatorFor(s"$oddBase/jwks")
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
    val refused = authenticatorFor(keySetFile.toUri.toString).authenticate(token)

    refused.isLeft shouldBe true
    refused.left.toOption.exists(_.contains("token validation failed")) shouldBe true
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
