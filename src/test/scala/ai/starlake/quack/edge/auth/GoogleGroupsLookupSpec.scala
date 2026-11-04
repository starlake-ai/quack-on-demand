package ai.starlake.quack.edge.auth

import ai.starlake.quack.security.JwtTestSigner
import io.circe.Json
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.{Base64, Locale}

/** The Directory API client of the Google groups lookup. Its URLs are the operator's and Google's,
  * not a tenant's, but the JDK offers `Upgrade: h2c` on every http:// request and its HTTP/2 reader
  * queues inbound frames with no bound, so the client speaks HTTP/1.1 only, like every client of a
  * URL that is not ours.
  */
class GoogleGroupsLookupSpec extends AnyFlatSpec with Matchers:

  /** A service account key file whose token endpoint is `tokenUri`. */
  private def serviceAccountKey(tokenUri: String): String =
    val der = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
    val pem = "-----BEGIN PRIVATE KEY-----\n" +
      der.encodeToString(JwtTestSigner.keyPair.toRSAPrivateKey.getEncoded) +
      "\n-----END PRIVATE KEY-----\n"
    Json
      .obj(
        "client_email" -> Json.fromString("svc@example.iam.gserviceaccount.com"),
        "private_key"  -> Json.fromString(pem),
        "token_uri"    -> Json.fromString(tokenUri)
      )
      .noSpaces

  "GoogleGroupsLookup" should "speak HTTP/1.1 only: its request to the token endpoint offers no upgrade to HTTP/2" in {
    val server = new MalformedServer(
      "HTTP/1.1 400 Bad Request\r\nContent-Length: 2\r\n\r\n{}"
        .getBytes(StandardCharsets.ISO_8859_1)
    )
    val file = Files.createTempFile("qod-service-account", ".json")
    try
      Files.writeString(file, serviceAccountKey(s"${server.base}/token"))
      val lookup = new GoogleGroupsLookup(file.toString)

      // The token request fails (400): the lookup answers no groups, as it does for any failure.
      lookup.getGroupsForUser("alice@example.com") shouldBe Set.empty

      val head = server.heads.headOption
        .getOrElse(fail("the token endpoint was never asked"))
        .toLowerCase(Locale.ROOT)
      head should startWith("post /token http/1.1")
      head should not include "upgrade"
      head should not include "http2-settings"
    finally
      server.close()
      Files.deleteIfExists(file)
      ()
  }
