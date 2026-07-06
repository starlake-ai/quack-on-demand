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
    val enabled  = true
    val batches  = scala.collection.mutable.ListBuffer.empty[List[AuditEvent]]
    var failNext = false
    def appendAudit(events: List[AuditEvent]): Unit =
      if failNext then { failNext = false; throw new RuntimeException("pg down") }
      batches += events
    def listAudit(q: AuditQuery): List[AuditRow] = Nil
    def purgeAudit(olderThan: Instant): Int      = 0

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
