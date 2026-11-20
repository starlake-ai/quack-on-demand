package ai.starlake.quack.ondemand.telemetry

import ai.starlake.quack.AuditS3Config
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*

import java.io.{ByteArrayInputStream, FileInputStream}
import java.net.{InetSocketAddress, ServerSocket, Socket, URI}
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.GZIPInputStream
import javax.net.ssl.TrustManagerFactory
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** `auditSink = s3` against a real S3 API with the production client and PUT, on an Object Lock
  * bucket. The server comes from `QOD_TEST_S3_ENDPOINT`, `QOD_TEST_S3_ACCESS_KEY_ID`,
  * `QOD_TEST_S3_SECRET_ACCESS_KEY` (`QOD_TEST_S3_REGION`, `QOD_TEST_S3_URL_STYLE`, a PEM
  * `QOD_TEST_S3_CA_FILE` and an existing lock bucket `QOD_TEST_S3_BUCKET` optional);
  * `scripts/dev/audit-s3-emulator.sh` starts the AWS Emulator under podman and prints them. With no
  * reachable server every case but the last is cancelled, which is not a pass.
  */
class S3AuditSinkSpec extends AnyFlatSpec with Matchers with Eventually:

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(20, Seconds), interval = Span(200, Millis))

  private def env(name: String): Option[String] = sys.env.get(name).map(_.trim).filter(_.nonEmpty)

  private lazy val endpoint = env("QOD_TEST_S3_ENDPOINT")

  private lazy val reachable = endpoint.exists { e =>
    val u    = URI.create(e)
    val port = if u.getPort > 0 then u.getPort else if u.getScheme == "https" then 443 else 80
    Try {
      val s = new Socket(); s.connect(new InetSocketAddress(u.getHost, port), 500); s.close()
    }.isSuccess
  }

  private def requireServer(): Unit =
    assume(reachable, "no S3 server: set QOD_TEST_S3_* (scripts/dev/audit-s3-emulator.sh env)")

  private lazy val cfg = AuditS3Config(
    endpoint = endpoint.getOrElse(""),
    region = env("QOD_TEST_S3_REGION").getOrElse("us-east-1"),
    bucket = env("QOD_TEST_S3_BUCKET").getOrElse("qod-audit-it-" + hex(4)),
    prefix = s"it-${hex(4)}/",
    urlStyle = env("QOD_TEST_S3_URL_STYLE").getOrElse("path"),
    accessKeyId = env("QOD_TEST_S3_ACCESS_KEY_ID").getOrElse(""),
    secretAccessKey = env("QOD_TEST_S3_SECRET_ACCESS_KEY").getOrElse(""),
    flushIntervalSec = 1
  )

  /** The production builder; only TLS trust of the test's CA file is added, in this client only. */
  private lazy val client: S3Client =
    val builder = S3AuditWriter.clientBuilder(cfg)
    env("QOD_TEST_S3_CA_FILE").foreach { pem =>
      val ks = KeyStore.getInstance(KeyStore.getDefaultType)
      ks.load(null, null)
      val certs =
        scala.util.Using.resource(new FileInputStream(pem))(
          CertificateFactory.getInstance("X.509").generateCertificates(_)
        )
      certs.asScala.zipWithIndex.foreach((c, i) => ks.setCertificateEntry(s"ca$i", c))
      val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
      tmf.init(ks)
      builder.httpClientBuilder(
        ApacheHttpClient.builder().tlsTrustManagersProvider(() => tmf.getTrustManagers)
      )
    }
    builder.build()

  /** The lock bucket: the given one, or one made here with GOVERNANCE default retention of a day.
    */
  private lazy val bucket: String =
    if env("QOD_TEST_S3_BUCKET").isEmpty then
      client.createBucket(
        CreateBucketRequest.builder().bucket(cfg.bucket).objectLockEnabledForBucket(true).build()
      )
      client.putObjectLockConfiguration(
        PutObjectLockConfigurationRequest
          .builder()
          .bucket(cfg.bucket)
          .objectLockConfiguration(
            ObjectLockConfiguration
              .builder()
              .objectLockEnabled(ObjectLockEnabled.ENABLED)
              .rule(
                ObjectLockRule
                  .builder()
                  .defaultRetention(
                    DefaultRetention
                      .builder()
                      .mode(ObjectLockRetentionMode.GOVERNANCE)
                      .days(1)
                      .build()
                  )
                  .build()
              )
              .build()
          )
          .build()
      )
    cfg.bucket

  private def hex(n: Int): String =
    val b = new Array[Byte](n)
    java.security.SecureRandom().nextBytes(b)
    HexFormat.of().formatHex(b)

  private def objects(prefix: String): List[(String, String)] =
    client
      .listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())
      .contents()
      .asScala
      .toList
      .map { o =>
        val body =
          client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(o.key()).build())
        o.key() -> new String(
          new GZIPInputStream(new ByteArrayInputStream(body.asByteArray())).readAllBytes(),
          StandardCharsets.UTF_8
        )
      }

  private def writer(prefix: String): S3AuditWriter =
    new S3AuditWriter(S3AuditWriter.put(client, bucket), prefix, 1.second, cfg.maxBatchBytes)

  private def stmt(i: Int) =
    StatementEvent(Instant.now(), "alice", "acme", "p1", "n1", s"SELECT $i", 3L, None, "ok", None)

  private val audit = AuditEvent(
    Instant.now(),
    "data-denial",
    "alice",
    "tenant",
    Some("acme"),
    "sql.denied",
    Some("sales.orders"),
    "denied",
    "flightsql",
    Map("verb" -> "Read")
  )

  "the s3 audit sink" should "put every line the sink renders into the bucket, as gzip NDJSON" in {
    requireServer()
    val prefix = cfg.keyPrefix + "e2e/"
    val w      = writer(prefix)
    val seen   = new ConcurrentLinkedQueue[String]()
    val sink   = new StdoutTelemetrySink(
      new RecordingTelemetryStore,
      out = l => { seen.add(l); w.append(l) },
      onClosed = () => w.close()
    )
    sink.appendAudit(List(audit))
    sink.appendStatements((1 to 20).map(stmt).toList)
    eventually(objects(prefix).flatMap(_._2.linesIterator).size shouldBe 21)
    sink.close()
    objects(prefix).flatMap(_._2.linesIterator) shouldBe seen.asScala.toList
    seen.asScala
      .map(l => io.circe.parser.parse(l).toOption.get.hcursor.get[String]("qodEvent").toOption.get)
      .toSet shouldBe
      Set("audit", "statement")
  }

  it should "give two writers disjoint keys, each written once" in {
    requireServer()
    val prefix = cfg.keyPrefix + "keys/"
    val (a, b) = (writer(prefix), writer(prefix))
    (1 to 3).foreach { i => a.append(s"a$i"); b.append(s"b$i") }
    a.close(); b.close()
    val keys = objects(prefix).map(_._1)
    keys.size shouldBe 2
    keys.distinct.size shouldBe 2
    val versions = client.listObjectVersions(
      ListObjectVersionsRequest.builder().bucket(bucket).prefix(prefix).build()
    )
    versions
      .versions()
      .asScala
      .map(_.key())
      .groupBy(identity)
      .values
      .map(_.size)
      .toSet shouldBe Set(1)
  }

  it should "upload into an Object Lock bucket under its default retention, which then refuses a delete" in {
    requireServer()
    val prefix = cfg.keyPrefix + "lock/"
    val w      = writer(prefix)
    w.append("locked")
    w.close()
    val version = client
      .listObjectVersions(ListObjectVersionsRequest.builder().bucket(bucket).prefix(prefix).build())
      .versions()
      .asScala
      .head
    val retention = client
      .getObjectRetention(
        GetObjectRetentionRequest
          .builder()
          .bucket(bucket)
          .key(version.key())
          .versionId(version.versionId())
          .build()
      )
      .retention()
    retention.mode() should not be null
    retention.retainUntilDate().isAfter(Instant.now()) shouldBe true
    Try(
      client.deleteObject(
        DeleteObjectRequest
          .builder()
          .bucket(bucket)
          .key(version.key())
          .versionId(version.versionId())
          .build()
      )
    ).isFailure shouldBe true
  }

  it should "never slow an append when the store is unreachable, and show the backlog" in {
    val closed = { val s = new ServerSocket(0); val p = s.getLocalPort; s.close(); p }
    val w      = S3AuditWriter(
      AuditS3Config(
        endpoint = s"http://127.0.0.1:$closed",
        bucket = "nowhere",
        accessKeyId = "AKIAEXAMPLE",
        secretAccessKey = "secret",
        flushIntervalSec = 1,
        maxBatchBytes = 1024
      )
    )
    val dropped = new java.util.concurrent.atomic.AtomicInteger
    w.onDropCounter { n => dropped.addAndGet(n); () }
    val start = System.nanoTime()
    (1 to 10000).foreach(i => w.append(s"line $i"))
    (System.nanoTime() - start).nanos should be < 1.second
    w.pendingBytes should be > 0L
    dropped.get should be > 0
    val closing = System.nanoTime()
    w.close()
    (System.nanoTime() - closing).nanos should be < 10.seconds
  }
