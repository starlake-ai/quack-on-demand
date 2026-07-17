package ai.starlake.quack.edge

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import java.security.KeyFactory
import java.security.cert.{CertificateFactory, X509Certificate}
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import scala.jdk.CollectionConverters.*

/** Pins the openssl-free cert generation path: the edge must be able to auto-generate its
  * self-signed TLS material on a machine with no `openssl` binary (stock Windows), using only what
  * ships with the JRE.
  */
class CertGenSpec extends AnyFlatSpec with Matchers:

  private def pemBody(path: Path, header: String): Array[Byte] =
    val text = Files.readString(path)
    text should include(s"-----BEGIN $header-----")
    text should include(s"-----END $header-----")
    val body = text.linesIterator.filterNot(_.startsWith("-----")).mkString
    Base64.getDecoder.decode(body)

  "CertGen.ensureCertFiles" should "generate a localhost cert + PKCS8 key without openssl" in {
    val dir  = Files.createTempDirectory("certgen-spec")
    val cert = dir.resolve("server-cert.pem")
    val key  = dir.resolve("server-key.pem")

    CertGen.ensureCertFiles(cert.toString, key.toString)

    // The cert parses as X.509, is self-signed for localhost, and carries the
    // SANs clients validate against (dns:localhost, ip:127.0.0.1).
    val x509 = CertificateFactory
      .getInstance("X.509")
      .generateCertificate(Files.newInputStream(cert))
      .asInstanceOf[X509Certificate]
    x509.getSubjectX500Principal.getName should include("CN=localhost")
    x509.getIssuerX500Principal.getName should include("CN=localhost") // self-signed
    val sans = x509.getSubjectAlternativeNames.asScala.map(_.get(1).toString).toSet
    sans should contain("localhost")
    sans should contain("127.0.0.1")

    // The key is an unencrypted PKCS8 RSA private key - the format Arrow's
    // FlightServer.Builder.useTls consumes.
    val keyBytes = pemBody(key, "PRIVATE KEY")
    noException should be thrownBy
      KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes))
  }

  it should "reuse existing files untouched" in {
    val dir  = Files.createTempDirectory("certgen-spec-reuse")
    val cert = dir.resolve("server-cert.pem")
    val key  = dir.resolve("server-key.pem")
    Files.writeString(cert, "existing-cert")
    Files.writeString(key, "existing-key")

    CertGen.ensureCertFiles(cert.toString, key.toString)

    Files.readString(cert) shouldBe "existing-cert"
    Files.readString(key) shouldBe "existing-key"
  }
