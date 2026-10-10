package ai.starlake.quack.ondemand.telemetry

import ai.starlake.quack.TelemetryConfig
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import io.circe.HCursor
import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class StdoutTelemetrySinkSpec extends AnyFlatSpec with Matchers:

  private val ts = Instant.parse("2026-10-08T00:00:00Z")

  private val audit = AuditEvent(
    ts,
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

  private def stmt(i: Int) =
    StatementEvent(ts, "alice", "acme", "p1", "n1", s"SELECT $i\nFROM t", 3L, None, "ok", None)

  private final class Fixture(
      capacity: Int = 8192,
      write: String => Unit = _ => (),
      closeWait: FiniteDuration = 5.seconds
  ):
    val lines   = new ConcurrentLinkedQueue[String]()
    val dropped = new AtomicInteger
    val inner   = new RecordingTelemetryStore
    val sink    = new StdoutTelemetrySink(
      inner,
      capacity,
      out = l => { write(l); lines.add(l); () },
      closeWait = closeWait
    )
    sink.onDropCounter { n => dropped.addAndGet(n); () }

    /** Flushes the async writer, then the emitted lines in order. */
    def flushed(): List[String] =
      sink.close()
      lines.asScala.toList

  private def js(line: String): HCursor = parse(line).toOption.get.hcursor

  "StdoutTelemetrySink" should "write one tagged JSON line per audit event and forward it to the store" in {
    val f = new Fixture
    f.sink.appendAudit(List(audit))

    val lines = f.flushed()
    lines should have size 1
    val json = js(lines.head)
    json.get[String]("qodEvent") shouldBe Right("audit")
    json.get[String]("actor") shouldBe Right("alice")
    json.get[String]("outcome") shouldBe Right("denied")
    json.downField("detail").get[String]("verb") shouldBe Right("Read")
    json.downField("patId").succeeded shouldBe false
    f.inner.events.toList shouldBe List(audit)
  }

  it should "receive exactly one single-line event per statement drained by the journal" in {
    val f       = new Fixture
    val journal = new EventJournal(f.sink)
    (1 to 3).foreach(i => journal.offerStatement(stmt(i)))
    journal.drainNow()

    val lines = f.flushed()
    lines should have size 3
    lines.foreach(_ should not include "\n")
    lines.map(js(_).get[String]("sql").toOption.get) shouldBe (1 to 3).map(i =>
      s"SELECT $i\nFROM t"
    )
    lines.map(js(_).get[String]("qodEvent")).distinct shouldBe List(Right("statement"))
    f.inner.searchStatements(StatementQuery()) should have size 3
  }

  it should "redact the emitted line but hand the store the original" in {
    val f         = new Fixture
    val secretSql = """CREATE SECRET s (TYPE s3, KEY_ID 'AKIA1234', SECRET "xyz98765")"""
    f.sink.appendStatements(List(stmt(0).copy(sql = secretSql)))

    val line = f.flushed().head
    line should (not include "AKIA1234" and not include "xyz98765")
    js(line).get[String]("sql") shouldBe Right(
      """CREATE SECRET s (TYPE s3, KEY_ID '?', SECRET "?")"""
    )
    f.inner.searchStatements(StatementQuery()).map(_.event.sql) shouldBe List(secretSql)
  }

  // Review of #156, reproduced against DuckDB 1.5.5: error text echoes the secret in its own
  // quoting, on a line whose sql field is already redacted.
  it should "scrub secrets an engine error echoes, on statements and audit detail" in {
    val attach =
      "ATTACH 'host=127.0.0.1 port=1 user=u password=hunter2 dbname=x' AS p (TYPE postgres)"
    val attachErr = "IO Error: Unable to connect to Postgres at " +
      "\"host=127.0.0.1 port=1 user=u password=hunter2 dbname=x\": connection refused"
    val secret    = "CREATE SECRET s2 (TYPE s3, SECRET 'abc' 'hunter2')"
    val secretErr = "Parser Error: syntax error at or near \"'hunter2'\""

    val f = new Fixture
    f.sink.appendStatements(
      List(
        stmt(0).copy(sql = attach, status = "error", error = Some(attachErr)),
        stmt(1).copy(sql = secret, status = "error", error = Some(secretErr))
      )
    )
    f.sink.appendAudit(
      List(
        audit.copy(detail = Map("sql" -> attach, "reason" -> attachErr)),
        audit.copy(detail = Map("sql" -> secret, "error" -> secretErr))
      )
    )

    val lines = f.flushed()
    lines should have size 4
    lines.foreach(_ should not include "hunter2")
    js(lines.head).get[String]("error").toOption.get should startWith(
      "IO Error: Unable to connect to Postgres at"
    )
  }

  it should "replace a typed login name and tenant on a failed auth event with a stable keyed hash" in {
    val typed  = "correct-horse-battery"
    val failed = audit.copy(
      family = "auth",
      actor = typed,
      tenant = Some("pasted-secret-tenant"),
      action = AuditActions.AuthLoginFailure,
      detail = Map("username" -> typed)
    )
    val f = new Fixture
    f.sink.appendAudit(
      List(
        failed,
        failed,
        failed.copy(outcome = "ok"),
        failed.copy(actor = "anonymous"),
        audit.copy(family = "control-plane", detail = Map("username" -> "bob"))
      )
    )

    val lines = f.flushed()
    lines.take(2).foreach(_ should (not include typed and not include "pasted-secret-tenant"))
    val actors = lines.map(js(_).get[String]("actor").toOption.get)
    actors(0) should startWith("hmac:")
    actors(1) shouldBe actors(0)
    js(lines(0)).downField("detail").get[String]("username") shouldBe Right(actors(0))
    js(lines(0)).get[String]("tenant").toOption.get should startWith("hmac:")
    actors(2) shouldBe typed
    actors(3) shouldBe "anonymous"
    js(lines(3)).downField("detail").get[String]("username") shouldBe Right(actors(0))
    js(lines(4)).downField("detail").get[String]("username") shouldBe Right("bob")
    f.inner.events.head.actor shouldBe typed
  }

  it should "drop and count lines instead of blocking when stdout stalls" in {
    val release = new CountDownLatch(1)
    val f       = new Fixture(capacity = 1, write = _ => release.await())

    f.sink.appendAudit(List.fill(5)(audit))

    f.inner.events should have size 5
    f.dropped.get should be >= 1
    release.countDown()
    f.flushed().size + f.dropped.get shouldBe 5
  }

  it should "count a line whose write throws and keep writing the next ones" in {
    val first = new java.util.concurrent.atomic.AtomicBoolean(true)
    val f     = new Fixture(write = _ => if first.getAndSet(false) then sys.error("pipe broke"))
    f.sink.appendAudit(List.fill(3)(audit))

    f.flushed() should have size 2
    f.dropped.get shouldBe 1
  }

  it should "count the lines still queued when close gives up on a wedged stdout" in {
    val f = new Fixture(write = _ => new CountDownLatch(1).await(), closeWait = 50.millis)
    f.sink.appendAudit(List.fill(4)(audit))

    f.flushed() shouldBe empty
    f.dropped.get shouldBe 3
  }

  it should "emit the line even when the store append fails" in {
    val f = new Fixture
    f.inner.failNext = true
    a[RuntimeException] should be thrownBy f.sink.appendAudit(List(audit))
    f.flushed() should have size 1
  }

  // Review of the #156 revision: FlightSqlRouter caps sql and error at 500 chars before the event
  // exists, so a literal past the cap is not a known value and an echo can be cut mid-value.
  it should "mask quoted spans of the error when the router cap cut the sql or the error" in {
    val pastCap = ("CREATE SECRET s2 (TYPE s3, KEY_ID 'AKIAEXAMPLE', SCOPE 's3://bucket/" +
      "p" * 440 + "', SECRET 'abc' 'hunter2')").take(500)
    val dsn     = "host=h port=1 user=u password=hunter2 dbname=x options=" + "o" * 420
    val cutEcho =
      s"IO Error: Unable to connect to Postgres at \"$dsn\": connection refused".take(500)

    val f = new Fixture
    f.sink.appendStatements(
      List(
        stmt(0).copy(
          sql = pastCap,
          status = "error",
          error = Some("Parser Error: syntax error at or near \"'hunter2'\"")
        ),
        stmt(1).copy(
          sql = s"ATTACH '$dsn' AS p (TYPE postgres)",
          status = "error",
          error = Some(cutEcho)
        )
      )
    )
    f.sink.appendAudit(
      List(audit.copy(detail = Map("sql" -> pastCap, "reason" -> "near \"'hunter2'\"")))
    )

    val lines = f.flushed()
    lines should have size 3
    lines.foreach(_ should not include "hunter2")
  }

  it should "mask an error that names a double-quoted identifier (documented over-redaction)" in {
    val f = new Fixture
    f.sink.appendStatements(
      List(
        stmt(0).copy(
          sql = """SELECT * FROM "sales"."orders"""",
          status = "denied",
          error = Some("access denied to sales.orders")
        )
      )
    )
    js(f.flushed().head).get[String]("error").toOption.get should not include "orders"
  }

  "TelemetryConfig.validate" should "accept a known sink and refuse an unknown one or one without a store" in {
    TelemetryConfig.validate("postgres", 7, "stdout") shouldBe Right(())
    TelemetryConfig.validate("postgres", 7, "Stdout").isLeft shouldBe true
    TelemetryConfig.validate("none", 7, "stdout").isLeft shouldBe true
    TelemetryConfig.validate("none", 7) shouldBe Right(())
  }
