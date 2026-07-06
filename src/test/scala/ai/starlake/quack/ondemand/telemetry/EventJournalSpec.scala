package ai.starlake.quack.ondemand.telemetry

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class EventJournalSpec extends AnyFlatSpec with Matchers:

  private def ev(i: Int) = AuditEvent(
    Instant.parse("2026-07-06T00:00:00Z"),
    "data-write",
    "alice",
    "tenant",
    Some("t-a"),
    s"sql.write.$i",
    None,
    "ok",
    "flightsql",
    Map.empty
  )

  private class RecordingStore extends TelemetryStore:
    val enabled       = true
    val batches       = scala.collection.mutable.ListBuffer.empty[List[AuditEvent]]
    val stmtBatches   = scala.collection.mutable.ListBuffer.empty[List[StatementEvent]]
    var failNext      = false
    var failNextStmts = false
    def appendAudit(events: List[AuditEvent]): Unit =
      if failNext then { failNext = false; throw new RuntimeException("pg down") }
      batches += events
    def appendStatements(events: List[StatementEvent]): Unit =
      if failNextStmts then { failNextStmts = false; throw new RuntimeException("pg stmt down") }
      stmtBatches += events
    def listAudit(q: AuditQuery): List[AuditRow]                                     = Nil
    def purgeAudit(olderThan: Instant): Int                                          = 0
    def searchStatements(q: StatementQuery): List[StatementRow]                      = Nil
    def purgeStatements(olderThan: Instant): Int                                     = 0
    def rollupWatermark(): Option[Instant]                                           = None
    def recomputeRollups(fromExclusive: Option[Instant], toInclusive: Instant): Unit = ()
    def advanceRollupWatermark(to: Instant): Unit                                    = ()
    def queryRollups(q: RollupQuery): List[RollupBucket]                             = Nil
    def purgeRollups(granularity: String, olderThan: Instant): Int                   = 0
    def queryUsage(q: UsageQuery): UsageResult = UsageResult(Nil, None)

  "offer + drainNow" should "batch queued events into one append" in {
    val store   = new RecordingStore
    val journal = new EventJournal(store)
    (1 to 3).foreach(i => journal.offer(ev(i)))
    journal.drainNow()
    store.batches.toList shouldBe List(List(ev(1), ev(2), ev(3)))
  }

  it should "split drains larger than batchMax" in {
    val store   = new RecordingStore
    val journal = new EventJournal(store, batchMax = 2)
    (1 to 5).foreach(i => journal.offer(ev(i)))
    journal.drainNow()
    store.batches.map(_.size).toList shouldBe List(2, 2, 1)
  }

  it should "drop and count when the queue is full" in {
    var dropped = 0
    val store   = new RecordingStore
    val journal = new EventJournal(store, capacity = 2, onDrop = dropped += _)
    (1 to 5).foreach(i => journal.offer(ev(i)))
    dropped shouldBe 3
    journal.drainNow()
    store.batches.flatten.size shouldBe 2
  }

  it should "count a failed append as drops and keep going" in {
    var dropped = 0
    val store   = new RecordingStore
    val journal = new EventJournal(store, onDrop = dropped += _)
    store.failNext = true
    journal.offer(ev(1))
    journal.drainNow()
    dropped shouldBe 1
    journal.offer(ev(2))
    journal.drainNow()
    store.batches.flatten.map(_.action) shouldBe List("sql.write.2")
  }

  it should "not enqueue at all when the store is disabled" in {
    var dropped = 0
    val journal = new EventJournal(NoopTelemetryStore, capacity = 1, onDrop = dropped += _)
    (1 to 5).foreach(i => journal.offer(ev(i)))
    dropped shouldBe 0
    journal.drainNow() // no-op, nothing recorded, nothing thrown
  }

  // ---------------------------------------------------------------------------
  // Statement queue
  // ---------------------------------------------------------------------------

  private def stev(i: Int) = StatementEvent(
    ts = java.time.Instant.parse("2026-07-06T00:00:00Z"),
    username = "alice",
    tenant = "acme",
    pool = "bi",
    nodeId = s"node-$i",
    sql = s"SELECT $i",
    durationMs = i.toLong,
    prepareMs = None,
    status = "ok",
    error = None
  )

  "offerStatement + drainNow" should "batch queued statement events into one appendStatements call" in {
    val store   = new RecordingStore
    val journal = new EventJournal(store)
    (1 to 3).foreach(i => journal.offerStatement(stev(i)))
    journal.drainNow()
    store.stmtBatches.toList shouldBe List(List(stev(1), stev(2), stev(3)))
  }

  it should "split statement drains larger than batchMax" in {
    val store   = new RecordingStore
    val journal = new EventJournal(store, batchMax = 2)
    (1 to 5).foreach(i => journal.offerStatement(stev(i)))
    journal.drainNow()
    store.stmtBatches.map(_.size).toList shouldBe List(2, 2, 1)
  }

  it should "count overflow on onStatementDrop and leave onDrop untouched" in {
    var auditDropped = 0
    var stmtDropped  = 0
    val store        = new RecordingStore
    val journal      = new EventJournal(
      store,
      capacity = 2,
      onDrop = auditDropped += _,
      onStatementDrop = stmtDropped += _
    )
    (1 to 5).foreach(i => journal.offerStatement(stev(i)))
    stmtDropped shouldBe 3
    auditDropped shouldBe 0
    journal.drainNow()
    store.stmtBatches.flatten.size shouldBe 2
  }

  it should "count a failed appendStatements as drops and keep the drain going" in {
    var stmtDropped = 0
    val store       = new RecordingStore
    val journal     = new EventJournal(store, onStatementDrop = stmtDropped += _)
    store.failNextStmts = true
    journal.offerStatement(stev(1))
    journal.offer(ev(1))
    journal.drainNow()
    // audit batch still lands
    store.batches.flatten.map(_.action) shouldBe List("sql.write.1")
    // statement batch was dropped
    stmtDropped shouldBe 1
    store.stmtBatches.flatten shouldBe empty
    // next drain succeeds
    journal.offerStatement(stev(2))
    journal.drainNow()
    store.stmtBatches.flatten.map(_.sql) shouldBe List("SELECT 2")
  }

  it should "be silent on offerStatement when the store is disabled" in {
    var stmtDropped = 0
    val journal     =
      new EventJournal(NoopTelemetryStore, capacity = 1, onStatementDrop = stmtDropped += _)
    (1 to 5).foreach(i => journal.offerStatement(stev(i)))
    stmtDropped shouldBe 0
    journal.drainNow()
  }

  it should "land both audit and statement events in one drainNow when interleaved" in {
    val store   = new RecordingStore
    val journal = new EventJournal(store)
    journal.offer(ev(1))
    journal.offerStatement(stev(1))
    journal.offer(ev(2))
    journal.offerStatement(stev(2))
    journal.drainNow()
    store.batches.flatten.map(_.action) shouldBe List("sql.write.1", "sql.write.2")
    store.stmtBatches.flatten.map(_.sql) shouldBe List("SELECT 1", "SELECT 2")
  }
