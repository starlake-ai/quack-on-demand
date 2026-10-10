package ai.starlake.quack.ondemand.telemetry

import ai.starlake.quack.edge.admin.AdminSqlParser
import ai.starlake.quack.ondemand.federation.iceberg.AttachErrorRedactor
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax.*
import io.circe.{Encoder, Json}

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.{ArrayBlockingQueue, ThreadPoolExecutor, TimeUnit}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** telemetry.auditSink = stdout: tees every audit and statement event to stdout as one JSON line,
  * then hands it to the wrapped store. Wrapping the store catches both write paths (the synchronous
  * [[AuditRecorder]] and the async [[EventJournal]]). `qodEvent` (`audit` | `statement`) sets these
  * lines apart from regular log output for a log shipper to route.
  *
  * Encoding and writing run on one daemon thread behind a bounded queue, so a slow stdout pipe
  * never stalls the store path or a request thread. A line that cannot go out (queue full, a render
  * or write that throws, still queued when `close` gives up) is dropped and counted through
  * `onDropCounter` (`qod_journal_dropped_total{table="stdout"}`). That counter is per destination
  * like its `audit` / `stmt_history` siblings, which count rows the store did not persist even when
  * the stdout line went out.
  *
  * Log pipelines are a wider audience than the control plane, so the emitted copy (never the
  * store's) is redacted:
  *   - SQL through [[AdminSqlParser.redactLiterals]] (every quoted span masked).
  *   - Engine error text (`error`, audit `reason` / `error`) through [[AttachErrorRedactor.scrub]]
  *     with the statement's own quoted values as the known credentials, since an error echoes them
  *     in its own quoting. When `FlightSqlRouter`'s 500-char cap cut the SQL or the error, that
  *     value set is incomplete or the echo is cut mid-value, so every quoted span of the error is
  *     masked too.
  *   - On a failed `auth` event, the typed login name and tenant (where a mistyped password lands)
  *     are replaced by a keyed hash, so repeated failures stay correlatable within one process.
  *
  * Known limits: a value written unquoted is not a literal and goes out as is; a quoted value under
  * 4 characters is not chased through error text (the scrubber's floor); and a double-quoted
  * identifier is chased like any value, so an error naming it is masked too (the scrubber's
  * over-redaction trade-off).
  */
final class StdoutTelemetrySink(
    inner: TelemetryStore,
    capacity: Int = 8192,
    out: String => Unit = println,
    closeWait: FiniteDuration = 5.seconds
) extends TelemetryStore:
  import StdoutTelemetrySink.{line, RouterCap, given}

  @volatile private var dropHook: Int => Unit = _ => ()

  /** Wire the drop counter after construction (metrics come up after the store). */
  def onDropCounter(f: Int => Unit): Unit = dropHook = f

  private val pseudonymKey =
    val bytes = new Array[Byte](32)
    SecureRandom().nextBytes(bytes)
    new SecretKeySpec(bytes, "HmacSHA256")

  private val writer = new ThreadPoolExecutor(
    1,
    1,
    0L,
    TimeUnit.MILLISECONDS,
    new ArrayBlockingQueue[Runnable](capacity),
    r => { val t = new Thread(r, "qod-audit-stdout"); t.setDaemon(true); t },
    (_, _) => dropHook(1)
  )

  def enabled: Boolean = inner.enabled

  def appendAudit(events: List[AuditEvent]): Unit =
    events.foreach(e => emit(line("audit", redact(e).asJson)))
    inner.appendAudit(events)

  def appendStatements(events: List[StatementEvent]): Unit =
    events.foreach(e => emit(line("statement", redact(e).asJson)))
    inner.appendStatements(events)

  /** Writes out what is queued (bounded wait), counts what is left, then closes the wrapped store.
    */
  override def close(): Unit =
    writer.shutdown()
    if !writer.awaitTermination(closeWait.toMillis, TimeUnit.MILLISECONDS) then
      dropHook(writer.shutdownNow().size)
    inner.close()

  /** Renders and writes on the writer thread, so the caller pays for neither. */
  private def emit(render: => String): Unit =
    writer.execute { () =>
      try out(render)
      catch case NonFatal(_) => dropHook(1)
    }

  private def redact(e: StatementEvent): StatementEvent =
    e.copy(
      sql = AdminSqlParser.redactLiterals(e.sql),
      error = e.error.map(scrubError(_, e.sql))
    )

  private def redact(e: AuditEvent): AuditEvent =
    val sql    = e.detail.getOrElse("sql", "")
    val detail = e.detail.map {
      case ("sql", v)                    => "sql" -> AdminSqlParser.redactLiterals(v)
      case (k @ ("reason" | "error"), v) => k     -> scrubError(v, sql)
      case kv                            => kv
    }
    e.detail.get("username") match
      case Some(typed) if e.family == "auth" && e.outcome != "ok" =>
        val name = pseudonym(typed)
        e.copy(
          actor = if e.actor == typed then name else e.actor,
          tenant = e.tenant.map(pseudonym),
          detail = detail.updated("username", name)
        )
      case _ => e.copy(detail = detail)

  private def scrubError(text: String, sql: String): String =
    val scrubbed = AttachErrorRedactor.scrub(text, AdminSqlParser.quotedValues(sql))
    if sql.length >= RouterCap || text.length >= RouterCap then
      AdminSqlParser.redactLiterals(scrubbed)
    else scrubbed

  // Per-process key: HA replicas and restarts do not correlate. Derive it from a shared secret if
  // cross-replica correlation is ever needed.
  private def pseudonym(name: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(pseudonymKey)
    "hmac:" + HexFormat.of().formatHex(mac.doFinal(name.getBytes(StandardCharsets.UTF_8))).take(16)

  def listAudit(q: AuditQuery): List[AuditRow]                = inner.listAudit(q)
  def purgeAudit(olderThan: Instant): Int                     = inner.purgeAudit(olderThan)
  def searchStatements(q: StatementQuery): List[StatementRow] = inner.searchStatements(q)
  def purgeStatements(olderThan: Instant): Int                = inner.purgeStatements(olderThan)
  def rollupWatermark(): Option[Instant]                      = inner.rollupWatermark()
  def recomputeRollups(fromExclusive: Option[Instant], toInclusive: Instant): Unit =
    inner.recomputeRollups(fromExclusive, toInclusive)
  def advanceRollupWatermark(to: Instant): Unit                  = inner.advanceRollupWatermark(to)
  def queryRollups(q: RollupQuery): List[RollupBucket]           = inner.queryRollups(q)
  def purgeRollups(granularity: String, olderThan: Instant): Int =
    inner.purgeRollups(granularity, olderThan)
  def queryUsage(q: UsageQuery): UsageResult = inner.queryUsage(q)

object StdoutTelemetrySink:

  /** `FlightSqlRouter.record` caps `sql` and `error` at this many characters before the event
    * exists.
    */
  private val RouterCap = 500

  private given Encoder[AuditEvent]     = deriveEncoder
  private given Encoder[StatementEvent] = deriveEncoder

  private def line(kind: String, body: Json): String =
    body.mapObject(("qodEvent" -> Json.fromString(kind)) +: _).dropNullValues.noSpaces
