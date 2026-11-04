package ai.starlake.quack.edge

import java.io.IOException
import java.nio.file.{Files, Path}
import java.security.KeyStore
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}
import scala.jdk.CollectionConverters.*

/** Key material for the cases that perform a real TLS exchange, which is the production path of
  * every identity provider and most OPA servers. It is made the way `CertGen` makes the edge's:
  * with the `keytool` of the JRE running the tests, so no binary and no library beyond the JDK is
  * needed, and no key outlives the fixture (a temporary folder, removed by [[close]]).
  *
  * Two server identities: [[good]] answers to `127.0.0.1` and `localhost`, [[wrongHost]] only to
  * `wrong.example`. [[clientContext]] trusts BOTH certificates, so that an exchange refused for the
  * name on a certificate is refused for the name alone, not for an unknown issuer.
  */
final class TlsFixture extends AutoCloseable:
  private val dir  = Files.createTempDirectory("qod-tls-spec")
  private val pass = "changeit"

  /** The identity of a server that answers to the loopback address. */
  val good: KeyStore = identityOf("good", "CN=localhost", "SAN=ip:127.0.0.1,dns:localhost")

  /** The identity of a server whose certificate names another host only. */
  val wrongHost: KeyStore = identityOf("wrong", "CN=wrong.example", "SAN=dns:wrong.example")

  /** A TLS context in which a server presents `store`. */
  def serverContext(store: KeyStore): SSLContext =
    val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keys.init(store, pass.toCharArray)
    val context = SSLContext.getInstance("TLS")
    context.init(keys.getKeyManagers, null, null)
    context

  /** The context to install as the JVM default for a case: it trusts both certificates. */
  val clientContext: SSLContext =
    val trust = KeyStore.getInstance("PKCS12")
    trust.load(null, null)
    List(good, wrongHost).zipWithIndex.foreach { (store, i) =>
      trust.setCertificateEntry(s"server-$i", store.getCertificate(store.aliases().asScala.next()))
    }
    val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    managers.init(trust)
    val context = SSLContext.getInstance("TLS")
    context.init(null, managers.getTrustManagers, null)
    context

  private def identityOf(alias: String, dn: String, san: String): KeyStore =
    val file    = dir.resolve(s"$alias.p12")
    val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool")
    val command = List(
      keytool.toString,
      "-genkeypair",
      "-alias",
      alias,
      "-keyalg",
      "RSA",
      "-keysize",
      "2048",
      "-validity",
      "30",
      "-dname",
      dn,
      "-ext",
      san,
      "-storetype",
      "PKCS12",
      "-keystore",
      file.toString,
      "-storepass",
      pass
    )
    val process = new ProcessBuilder(command*).redirectErrorStream(true).start()
    val output  = new String(process.getInputStream.readAllBytes())
    if process.waitFor() != 0 then
      throw new IOException(s"keytool failed to make the $alias test identity: $output")
    val store = KeyStore.getInstance("PKCS12")
    val in    = Files.newInputStream(file)
    try store.load(in, pass.toCharArray)
    finally in.close()
    store

  override def close(): Unit =
    val files = Files.list(dir)
    try files.iterator().asScala.foreach(Files.deleteIfExists(_))
    finally files.close()
    Files.deleteIfExists(dir)
    ()
