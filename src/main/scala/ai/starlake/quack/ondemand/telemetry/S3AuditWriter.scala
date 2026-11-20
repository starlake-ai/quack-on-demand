package ai.starlake.quack.ondemand.telemetry

import ai.starlake.quack.AuditS3Config
import com.typesafe.scalalogging.LazyLogging
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.{S3Client, S3ClientBuilder, S3Configuration}

import java.io.ByteArrayOutputStream
import java.net.{InetAddress, URI}
import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.time.{Duration as JDuration, Instant, LocalDate, ZoneOffset}
import java.util.{Base64, HexFormat}
import java.util.zip.GZIPOutputStream
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** `telemetry.auditSink = s3`: the `out` of a [[StdoutTelemetrySink]], so every line it gets is
  * already rendered and redacted, on the sink's thread, never a request thread. `append` only
  * buffers; one daemon thread uploads a gzip NDJSON object every `flushInterval`, or sooner when a
  * batch is full.
  *
  * Keys are `<prefix>date=YYYY-MM-DD/<replica>-<seq>.ndjson.gz` (UTC date of the upload), the
  * replica being the host name and a random boot id, so HA replicas and restarts never share a key.
  * Append only: a failed upload is sent again with the same key and the same bytes, nothing is ever
  * deleted or overwritten with other content, and retention is the bucket's own (Object Lock
  * default retention included).
  *
  * At most [[S3AuditWriter.PendingBatches]] batches are held while uploads fail; past that a line
  * is dropped and counted through `onDropCounter`, as is every line still held when `close` gives
  * up. A crash loses what is held: there is no disk spool.
  */
final class S3AuditWriter(
    put: (String, Array[Byte]) => Unit,
    prefix: String,
    flushInterval: FiniteDuration,
    maxBatchBytes: Int,
    replica: String = S3AuditWriter.replicaId(),
    clock: () => Instant = () => Instant.now(),
    closeWait: FiniteDuration = 5.seconds
) extends LazyLogging:
  import S3AuditWriter.*

  /** `raw` is cut under the lock; it is gzipped once, outside it, and every retry sends those
    * bytes.
    */
  private final class Batch(val key: String, raw: Array[Byte], val lines: Int, val bytes: Long):
    lazy val body: Array[Byte] =
      val out = new ByteArrayOutputStream()
      val z   = new GZIPOutputStream(out)
      z.write(raw)
      z.close()
      out.toByteArray

  private val lock     = new Object
  private val buffered = mutable.Queue.empty[Array[Byte]]
  // Guarded by `lock`.
  private var bufferedBytes = 0L
  private var inFlight      = Option.empty[Batch]
  private var seq           = 0L
  private var closing       = false
  private var failing       = false
  private var lastFailure   = ""

  @volatile private var dropHook: Int => Unit = _ => ()

  /** Wire the drop counter after construction (metrics come up after the store). */
  def onDropCounter(f: Int => Unit): Unit = dropHook = f

  /** Bytes held and not yet uploaded, uncompressed. */
  def pendingBytes: Long = lock.synchronized(bufferedBytes + inFlight.fold(0L)(_.bytes))

  private val thread = new Thread(() => run(), "qod-audit-s3")
  thread.setDaemon(true)
  thread.start()

  /** Never blocks on I/O and never throws. */
  def append(line: String): Unit =
    val bytes   = (line + "\n").getBytes(StandardCharsets.UTF_8)
    val dropped = lock.synchronized {
      val pending = bufferedBytes + inFlight.fold(0L)(_.bytes)
      if closing || pending + bytes.length > PendingBatches.toLong * maxBatchBytes then true
      else
        buffered.enqueue(bytes)
        bufferedBytes += bytes.length
        if bufferedBytes >= maxBatchBytes && !failing then lock.notifyAll()
        false
    }
    if dropped then dropHook(1)

  /** One last upload of what is held, waiting at most `closeWait`; what is left is counted. A put
    * still hung at that point may land later: its lines are counted as dropped all the same.
    */
  def close(): Unit =
    lock.synchronized { closing = true; lock.notifyAll() }
    thread.join(closeWait.toMillis)
    val left = lock.synchronized {
      val n = buffered.size + inFlight.fold(0)(_.lines)
      buffered.clear(); bufferedBytes = 0; inFlight = None
      n
    }
    if left > 0 then
      dropHook(left)
      logger.error(s"audit s3 sink: $left line(s) not uploaded at close")

  private def run(): Unit =
    var nextTick = System.nanoTime() + flushInterval.toNanos
    var done     = false
    while !done do
      val tick = lock.synchronized {
        var waitNs = nextTick - System.nanoTime()
        while !closing && waitNs > 0 && (failing || bufferedBytes < maxBatchBytes) do
          lock.wait(math.max(1L, waitNs / 1000000L))
          waitNs = nextTick - System.nanoTime()
        done = closing
        closing || waitNs <= 0
      }
      flush(all = tick)
      if tick then nextTick = System.nanoTime() + flushInterval.toNanos

  /** Uploads the batch in flight, then cuts and uploads more: on a tick or at close the lines held
    * at that moment, otherwise only full batches, so steady traffic never turns a tick into one
    * small object per round trip. Stops at the first failure, keeping that batch for the next try.
    */
  private def flush(all: Boolean): Unit =
    var owed = if all then lock.synchronized(buffered.size) else 0
    var go   = true
    while go do
      lock.synchronized {
        if inFlight.isEmpty && buffered.nonEmpty && (owed > 0 || bufferedBytes >= maxBatchBytes)
        then
          val batch = cut()
          owed -= batch.lines
          inFlight = Some(batch)
      }
      lock.synchronized(inFlight) match
        case None        => go = false
        case Some(batch) =>
          try
            put(batch.key, batch.body)
            lock.synchronized { inFlight = None; failing = false; lastFailure = "" }
          catch
            case NonFatal(e) =>
              val why  = describe(e)
              val news = lock.synchronized {
                val changed = why != lastFailure
                failing = true
                lastFailure = why
                changed
              }
              if news then
                logger.error(s"audit s3 sink: upload failed ($why); retrying every $flushInterval")
              go = false

  /** Lines from the head up to `maxBatchBytes` (at least one). Called under `lock`. */
  private def cut(): Batch =
    val raw   = new ByteArrayOutputStream()
    var bytes = 0L
    var lines = 0
    while buffered.nonEmpty && (lines == 0 || bytes + buffered.head.length <= maxBatchBytes) do
      val line = buffered.dequeue()
      raw.write(line)
      bytes += line.length
      lines += 1
    bufferedBytes -= bytes
    seq += 1
    val date = LocalDate.ofInstant(clock(), ZoneOffset.UTC)
    new Batch(f"${prefix}date=$date/$replica-$seq%08d.ndjson.gz", raw.toByteArray, lines, bytes)

object S3AuditWriter:

  /** Batches held while uploads fail; a constant, not a setting. */
  val PendingBatches = 4

  def apply(cfg: AuditS3Config): S3AuditWriter =
    new S3AuditWriter(
      put(clientBuilder(cfg).build(), cfg.bucket),
      cfg.keyPrefix,
      cfg.flushIntervalSec.seconds,
      cfg.maxBatchBytes
    )

  /** The block's own static keys only (no credential chain), no network call at build. The SDK adds
    * no checksum of its own (`WHEN_REQUIRED`, no aws-chunked trailer, which several S3-compatible
    * stores refuse); `put` sends a Content-MD5 header, which an Object Lock bucket requires (it or
    * a flexible checksum) on every upload and which every S3-compatible store knows.
    */
  private[telemetry] def clientBuilder(cfg: AuditS3Config): S3ClientBuilder =
    val builder = S3Client
      .builder()
      .region(Region.of(cfg.region))
      .credentialsProvider(
        StaticCredentialsProvider.create(
          AwsBasicCredentials.create(cfg.accessKeyId, cfg.secretAccessKey)
        )
      )
      .serviceConfiguration(
        S3Configuration
          .builder()
          .pathStyleAccessEnabled(cfg.urlStyle == "path")
          .chunkedEncodingEnabled(false)
          .build()
      )
      .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
      .overrideConfiguration(o =>
        o.apiCallTimeout(JDuration.ofSeconds(30)).apiCallAttemptTimeout(JDuration.ofSeconds(10))
      )
    if cfg.endpoint.nonEmpty then builder.endpointOverride(URI.create(cfg.endpoint)) else builder

  private[telemetry] def put(client: S3Client, bucket: String)(
      key: String,
      body: Array[Byte]
  ): Unit =
    client.putObject(
      PutObjectRequest
        .builder()
        .bucket(bucket)
        .key(key)
        .contentType("application/gzip")
        .contentMD5(Base64.getEncoder.encodeToString(MessageDigest.getInstance("MD5").digest(body)))
        .build(),
      RequestBody.fromBytes(body)
    )

  /** The exception class (and its root cause's, which tells DNS, TLS and a refused connection
    * apart), plus a server answer's status and error code; never a message, which can quote a
    * request (the repo rule: only a server answer's status code is quoted).
    */
  private[telemetry] def describe(t: Throwable): String = t match
    case e: AwsServiceException =>
      val code = Option(e.awsErrorDetails()).flatMap(d => Option(d.errorCode())).getOrElse("-")
      s"${e.getClass.getSimpleName} ${e.statusCode()} $code"
    case e =>
      val root = Iterator.iterate(e: Throwable)(_.getCause).takeWhile(_ != null).toList.last
      if root eq e then e.getClass.getSimpleName
      else s"${e.getClass.getSimpleName} caused by ${root.getClass.getSimpleName}"

  private[telemetry] def replicaId(): String =
    val host =
      try InetAddress.getLocalHost.getHostName
      catch case NonFatal(_) => "manager"
    val id = new Array[Byte](8)
    SecureRandom().nextBytes(id)
    s"${host.replaceAll("[^A-Za-z0-9._-]", "-").take(63)}-${HexFormat.of().formatHex(id)}"
