package ai.starlake.quack.edge

import com.typesafe.scalalogging.LazyLogging

import java.nio.file.{Files, Path}
import java.security.{KeyStore, MessageDigest}
import java.util.Base64

/** Self-signed TLS material for the FlightSQL edge, generated without any external binary.
  *
  * The primary path runs the JRE's own `keytool` (present in every runtime the manager can boot on:
  * system JDKs, the launcher's auto-provisioned Temurin JRE, the eclipse-temurin Docker images)
  * into a throwaway PKCS12 keystore, then extracts the key + cert in-process via the `KeyStore` API
  * and writes standard PEMs. `openssl` remains only as a fallback for exotic JREs stripped of
  * keytool - it is NOT on PATH on stock Windows, which is why it cannot be the primary (a
  * `qod start` on Windows previously died here).
  */
object CertGen extends LazyLogging:

  /** Auto-generate a self-signed cert/key pair at the configured paths when either file is missing.
    * Dev convenience so `useEncryption=true` works out of the box; production deploys should supply
    * CA-signed material. CN + SAN are bound to `localhost`.
    */
  def ensureCertFiles(certPath: String, keyPath: String): Unit =
    val certFile = Path.of(certPath)
    val keyFile  = Path.of(keyPath)
    if Files.exists(certFile) && Files.exists(keyFile) then
      logger.info(s"TLS: reusing existing cert at $certPath")
    else
      Option(certFile.getParent).foreach(Files.createDirectories(_))
      Option(keyFile.getParent).foreach(Files.createDirectories(_))
      keytoolBinary match
        case Some(keytool) => generateWithKeytool(keytool, certFile, keyFile)
        case None          => generateWithOpenssl(certPath, keyPath)
      val fp = sha256Fingerprint(Files.readAllBytes(certFile))
      logger.warn(
        s"TLS: generated self-signed cert (CN=localhost) at $certPath / $keyPath. " +
          s"SHA-256 fingerprint: $fp. " +
          "JDBC clients: jdbc:arrow-flight-sql://localhost:PORT?useEncryption=true&disableCertificateVerification=true " +
          "(or import the cert into a trust store for verified TLS)."
      )

  /** The JRE's keytool, if this runtime ships one. */
  private def keytoolBinary: Option[Path] =
    val name =
      if sys.props.getOrElse("os.name", "").toLowerCase.contains("win") then "keytool.exe"
      else "keytool"
    Option(System.getProperty("java.home"))
      .map(home => Path.of(home, "bin", name))
      .filter(Files.isExecutable(_))

  private def generateWithKeytool(keytool: Path, certFile: Path, keyFile: Path): Unit =
    val alias     = "qod"
    val storePass = "qod-certgen" // throwaway keystore, deleted below
    val p12       = Files.createTempFile("qod-certgen", ".p12")
    try
      Files.delete(p12) // keytool refuses to write into an existing (empty) file
      run(
        List(
          keytool.toString,
          "-genkeypair",
          "-alias",
          alias,
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-validity",
          "3650",
          "-dname",
          "CN=localhost",
          "-ext",
          "SAN=dns:localhost,ip:127.0.0.1",
          "-storetype",
          "PKCS12",
          "-keystore",
          p12.toString,
          "-storepass",
          storePass
        ),
        "keytool failed to generate cert"
      )
      val ks = KeyStore.getInstance("PKCS12")
      val in = Files.newInputStream(p12)
      try ks.load(in, storePass.toCharArray)
      finally in.close()
      val key  = ks.getKey(alias, storePass.toCharArray)
      val cert = ks.getCertificate(alias)
      Files.writeString(keyFile, pem("PRIVATE KEY", key.getEncoded))
      Files.writeString(certFile, pem("CERTIFICATE", cert.getEncoded))
    finally Files.deleteIfExists(p12)

  private def pem(header: String, der: Array[Byte]): String =
    val b64 = Base64.getMimeEncoder(64, "\n".getBytes).encodeToString(der)
    s"-----BEGIN $header-----\n$b64\n-----END $header-----\n"

  private def generateWithOpenssl(certPath: String, keyPath: String): Unit =
    run(
      List(
        "openssl",
        "req",
        "-x509",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-days",
        "3650",
        "-keyout",
        keyPath,
        "-out",
        certPath,
        "-subj",
        "/CN=localhost",
        "-addext",
        "subjectAltName=DNS:localhost,IP:127.0.0.1"
      ),
      "openssl failed to generate cert (and this JRE has no keytool)"
    )

  private def run(cmd: List[String], failurePrefix: String): Unit =
    val pb     = new java.lang.ProcessBuilder(cmd*).redirectErrorStream(true)
    val proc   = pb.start()
    val output = new String(proc.getInputStream.readAllBytes())
    val rc     = proc.waitFor()
    if rc != 0 then
      throw new RuntimeException(
        s"$failurePrefix (rc=$rc): $output. " +
          "Supply your own cert/key at the configured paths (PROXY_TLS_CERT_CHAIN / PROXY_TLS_PRIVATE_KEY)."
      )

  private def sha256Fingerprint(certBytes: Array[Byte]): String =
    val md = MessageDigest.getInstance("SHA-256")
    md.digest(certBytes).map(b => f"$b%02X").mkString(":")
