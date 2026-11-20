package ai.starlake.quack.ondemand.telemetry

import ai.starlake.quack.{AuditS3Config, TelemetryConfig}
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{PutObjectRequest, PutObjectResponse, S3Exception}

import java.io.ByteArrayInputStream
import java.net.ConnectException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.zip.GZIPInputStream
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class S3AuditWriterSpec extends AnyFlatSpec with Matchers with Eventually:

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(20, Millis))

  private val noon = Instant.parse("2026-11-20T12:00:00Z")

  private def gunzip(body: Array[Byte]): String =
    new String(
      new GZIPInputStream(new ByteArrayInputStream(body)).readAllBytes(),
      StandardCharsets.UTF_8
    )

  private final class Fixture(
      interval: FiniteDuration = 1.hour,
      maxBatch: Int = 1024,
      put: (String, Array[Byte]) => Unit = (_, _) => (),
      clock: () => Instant = () => noon,
      closeWait: FiniteDuration = 5.seconds,
      prefix: String = "audit/"
  ):
    val objects = new ConcurrentLinkedQueue[(String, Array[Byte])]()
    val dropped = new AtomicInteger
    val writer  = new S3AuditWriter(
      (k, b) => { put(k, b); objects.add(k -> b); () },
      prefix,
      interval,
      maxBatch,
      replica = "host-0123456789abcdef",
      clock = clock,
      closeWait = closeWait
    )
    writer.onDropCounter { n => dropped.addAndGet(n); () }
    def lines: List[String] = objects.asScala.toList.flatMap((_, b) => gunzip(b).linesIterator)

  "S3AuditWriter" should "upload what it holds at close, in order, as one gzip NDJSON object" in {
    val f = new Fixture
    (1 to 3).foreach(i => f.writer.append(s"""{"n":$i}"""))
    f.writer.close()
    f.objects.size shouldBe 1
    gunzip(f.objects.peek()._2) shouldBe "{\"n\":1}\n{\"n\":2}\n{\"n\":3}\n"
    f.writer.pendingBytes shouldBe 0
    f.dropped.get shouldBe 0
  }

  it should "upload on the flush interval without a close" in {
    val f = new Fixture(interval = 50.millis)
    f.writer.append("a")
    eventually(f.lines shouldBe List("a"))
    f.writer.close()
  }

  it should "upload a full batch before the interval, cutting at the batch size" in {
    val f    = new Fixture(maxBatch = 1024)
    val line = "x" * 299 // 300 bytes with its newline: three fit in a batch
    (1 to 7).foreach(_ => f.writer.append(line))
    eventually(f.objects.size shouldBe 2)
    f.objects.asScala.foreach((_, b) => gunzip(b).length shouldBe 900)
    f.writer.append("y" * 2000) // longer than a batch: goes alone, at once
    f.writer.close()
    f.lines shouldBe List.fill(7)(line) :+ ("y" * 2000)
  }

  it should "name each object by prefix, UTC date, replica and a rising sequence" in {
    val now = new AtomicReference(Instant.parse("2026-11-20T23:59:59Z"))
    val f   = new Fixture(interval = 20.millis, clock = () => now.get)
    f.writer.append("a")
    eventually(f.objects.size shouldBe 1)
    now.set(Instant.parse("2026-11-21T00:00:01Z"))
    f.writer.append("b")
    eventually(f.objects.size shouldBe 2)
    f.writer.close()
    f.objects.asScala.map(_._1).toList shouldBe List(
      "audit/date=2026-11-20/host-0123456789abcdef-00000001.ndjson.gz",
      "audit/date=2026-11-21/host-0123456789abcdef-00000002.ndjson.gz"
    )
  }

  it should "give each writer its own replica id, from the host name and a random boot id" in {
    val a = S3AuditWriter.replicaId()
    val b = S3AuditWriter.replicaId()
    a should not be b
    a should fullyMatch regex "[A-Za-z0-9._-]{1,63}-[0-9a-f]{16}"
  }

  it should "send a failed batch again with the same key and bytes, losing and doubling nothing" in {
    val failures = new AtomicInteger(2)
    val tries    = new ConcurrentLinkedQueue[(String, Array[Byte])]()
    val f        = new Fixture(
      interval = 30.millis,
      put = (k, b) =>
        tries.add(k -> b)
        if failures.getAndDecrement() > 0 then throw new RuntimeException("down")
    )
    f.writer.append("a")
    f.writer.append("b")
    eventually(tries.size should be >= 3)
    tries.asScala.toList
      .groupBy(_._1)
      .values
      .foreach(sent => sent.map(s => gunzip(s._2)).distinct.size shouldBe 1)
    f.writer.append("c")
    f.writer.close()
    f.lines shouldBe List("a", "b", "c")
    f.dropped.get shouldBe 0
  }

  it should "upload once per interval under steady traffic, not once per round trip" in {
    val f = new Fixture(interval = 300.millis, maxBatch = 1 << 20, put = (_, _) => Thread.sleep(20))
    val stopAt = System.nanoTime() + 1200.millis.toNanos
    var i      = 0
    while System.nanoTime() < stopAt do
      f.writer.append(s"line $i")
      i += 1
      Thread.sleep(1)
    f.writer.close()
    f.objects.size should (be >= 2 and be <= 8)
    f.lines.size shouldBe i
  }

  it should "send the body's Content-MD5 with every upload, which an Object Lock bucket requires" in {
    val seen   = new AtomicReference[PutObjectRequest]()
    val client = new S3Client:
      override def serviceName(): String                                             = "s3"
      override def close(): Unit                                                     = ()
      override def putObject(r: PutObjectRequest, b: RequestBody): PutObjectResponse =
        seen.set(r)
        PutObjectResponse.builder().build()
    val body = "payload".getBytes(StandardCharsets.UTF_8)
    S3AuditWriter.put(client, "bucket")("k.ndjson.gz", body)
    seen.get.bucket shouldBe "bucket"
    seen.get.key shouldBe "k.ndjson.gz"
    seen.get.contentMD5 shouldBe Base64.getEncoder.encodeToString(
      MessageDigest.getInstance("MD5").digest(body)
    )
  }

  it should "drop and count lines past four batches while uploads fail, never throwing" in {
    val f = new Fixture(maxBatch = 1024, put = (_, _) => throw new RuntimeException("down"))
    (1 to 100).foreach(_ => f.writer.append("x" * 99)) // 100 bytes each: 40 fit in 4096
    f.writer.pendingBytes should be <= 4096L
    f.dropped.get shouldBe 60
    f.writer.close()
    f.dropped.get shouldBe 100
  }

  it should "never block an append on a slow upload, and give up at close after its wait" in {
    val parked = new CountDownLatch(1)
    val f     = new Fixture(maxBatch = 1024, put = (_, _) => parked.await(), closeWait = 200.millis)
    val start = System.nanoTime()
    (1 to 10000).foreach(i => f.writer.append(s"line $i"))
    (System.nanoTime() - start).nanos should be < 1.second
    val closing = System.nanoTime()
    f.writer.close()
    (System.nanoTime() - closing).nanos should be < 2.seconds
    f.dropped.get shouldBe 10000
    f.writer.append("late")
    f.dropped.get shouldBe 10001
    parked.countDown()
  }

  it should "describe an S3 failure by class, status and code, never by its message" in {
    val secret = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYSECRETKEY"
    val e      = S3Exception
      .builder()
      .statusCode(403)
      .message(s"signature with $secret")
      .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").build())
      .build()
    S3AuditWriter.describe(e) shouldBe "S3Exception 403 AccessDenied"
    S3AuditWriter.describe(new RuntimeException(secret)) shouldBe "RuntimeException"
    S3AuditWriter.describe(
      SdkClientException.create(secret, new ConnectException(secret))
    ) shouldBe "SdkClientException caused by ConnectException"
  }

  private val complete = AuditS3Config(
    bucket = "audit",
    accessKeyId = "AKIAEXAMPLE",
    secretAccessKey = "wJalrXUtnFEMI/K7MDENG"
  )

  "AuditS3Config" should "never print its keys" in {
    complete.toString should (not include "AKIAEXAMPLE" and not include "wJalrXUtnFEMI")
    complete.toString should include("secretAccessKey=(set)")
  }

  it should "read its prefix as keys use it" in {
    complete.copy(prefix = "audit").keyPrefix shouldBe "audit/"
    complete.copy(prefix = "/a/b/").keyPrefix shouldBe "a/b/"
    complete.copy(prefix = "").keyPrefix shouldBe ""
  }

  it should "refuse an incomplete or out-of-range block, naming the variable and no key" in {
    complete.validate shouldBe Right(())
    val refusals = List(
      complete.copy(bucket = "")                         -> "QOD_AUDIT_S3_BUCKET",
      complete.copy(accessKeyId = "")                    -> "QOD_AUDIT_S3_ACCESS_KEY_ID",
      complete.copy(secretAccessKey = " ")               -> "QOD_AUDIT_S3_SECRET_ACCESS_KEY",
      complete.copy(region = "")                         -> "QOD_AUDIT_S3_REGION",
      complete.copy(endpoint = "s3.example.com")         -> "QOD_AUDIT_S3_ENDPOINT",
      complete.copy(endpoint = "https://u:p@s3.example") -> "QOD_AUDIT_S3_ENDPOINT",
      complete.copy(urlStyle = "virtual")                -> "QOD_AUDIT_S3_URL_STYLE",
      complete.copy(flushIntervalSec = 0)                -> "QOD_AUDIT_S3_FLUSH_INTERVAL_SEC",
      complete.copy(maxBatchBytes = 100)                 -> "QOD_AUDIT_S3_MAX_BATCH_BYTES"
    )
    refusals.foreach { (cfg, envVar) =>
      val msg = cfg.validate.left.getOrElse(fail(s"accepted: $cfg"))
      msg should include(envVar)
      msg should (not include "AKIAEXAMPLE" and not include "wJalrXUtnFEMI")
    }
    complete.copy(endpoint = "https://s3.gra.io.cloud.ovh.net").validate shouldBe Right(())
  }

  "TelemetryConfig.validate" should "accept the s3 sink only with a complete block and a store" in {
    TelemetryConfig.validate("postgres", 7, "s3", complete) shouldBe Right(())
    TelemetryConfig.validate("postgres", 7, "s3").isLeft shouldBe true
    TelemetryConfig.validate("none", 7, "s3", complete).isLeft shouldBe true
  }
