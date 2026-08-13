package ai.starlake.quack.boot

import ai.starlake.quack.ManagedObjectStoreConfig
import ai.starlake.quack.ondemand.state.ManagedPrefixRow
import ai.starlake.quack.ondemand.storage.testkit.StubManagedStoreClient
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class ManagedStoreWiringSpec extends AnyFlatSpec with Matchers:

  private val cfg = ManagedObjectStoreConfig(enabled = true, bucket = "qod-managed")

  private val keyPrefix = "acme_sales-aabbccdd/"

  private def row(id: String, kp: String): ManagedPrefixRow =
    ManagedPrefixRow(
      id = id,
      tenant = "acme",
      tenantDbName = "sales",
      prefix = s"s3://${cfg.bucket}/$kp",
      createdAt = Instant.parse("2026-01-01T00:00:00Z"),
      deletedAt = Some(Instant.parse("2026-01-02T00:00:00Z")),
      purgeEligibleAt = Some(Instant.parse("2026-01-09T00:00:00Z")),
      purgedAt = None
    )

  "tick" should "do nothing as follower and drain as leader with one bound IO" in:
    val stub = new StubManagedStoreClient
    stub.seed(keyPrefix, 3)
    var leader = false
    var purged = List.empty[(String, Instant)]
    val w      = new ManagedStoreWiring(
      cfg = cfg,
      client = stub,
      due = _ => List(row("mp-1", keyPrefix)),
      markPurged = (id, at) => purged = purged :+ (id, at),
      isLeader = () => leader
    )
    // ONE bound IO, run twice across a leadership flip: the gate must be
    // re-evaluated per run, not latched at construction.
    val t = w.tick()
    t.unsafeRunSync()
    stub.listPrefixCalls shouldBe 0
    stub.deleteBatchCalls shouldBe 0
    stub.allKeys.size shouldBe 3
    purged shouldBe empty

    leader = true
    t.unsafeRunSync()
    stub.allKeys shouldBe empty
    purged.map(_._1) shouldBe List("mp-1")

  it should "resume a large prefix across ticks and stamp purged only when empty" in:
    val stub = new StubManagedStoreClient
    stub.seed(keyPrefix, 2500)
    var purged = List.empty[String]
    val w      = new ManagedStoreWiring(
      cfg = cfg,
      client = stub,
      due = _ => List(row("mp-1", keyPrefix)),
      markPurged = (id, _) => purged = purged :+ id,
      isLeader = () => true,
      batchSize = 1000,
      maxBatchesPerPrefixPerTick = 2
    )
    val t = w.tick()
    t.unsafeRunSync()
    stub.allKeys.size shouldBe 500
    purged shouldBe empty

    t.unsafeRunSync()
    stub.allKeys shouldBe empty
    purged shouldBe List("mp-1")

  it should "isolate a failing prefix from the rest" in:
    val stub      = new StubManagedStoreClient
    val otherKeys = "globex_ops-11223344/"
    stub.seed(keyPrefix, 2)
    stub.seed(otherKeys, 2)
    stub.failNextDelete = Some("boom")
    var purged = List.empty[String]
    // `dueManagedPrefixes` only returns rows with a NULL purgedAt, so the fixture
    // drops a row once it has been stamped.
    val w = new ManagedStoreWiring(
      cfg = cfg,
      client = stub,
      due = _ =>
        List(row("mp-1", keyPrefix), row("mp-2", otherKeys)).filterNot(r => purged.contains(r.id)),
      markPurged = (id, _) => purged = purged :+ id,
      isLeader = () => true
    )
    val t = w.tick()
    t.unsafeRunSync()
    // First row's delete failed and it moved on; the second row drained fully.
    stub.allKeys.sorted shouldBe List(s"${keyPrefix}0", s"${keyPrefix}1")
    purged shouldBe List("mp-2")

    // The failed prefix is retried on the next tick.
    t.unsafeRunSync()
    stub.allKeys shouldBe empty
    purged shouldBe List("mp-2", "mp-1")

  it should "isolate a failing listPrefix from the rest" in:
    val stub      = new StubManagedStoreClient
    val otherKeys = "globex_ops-11223344/"
    stub.seed(keyPrefix, 2)
    stub.seed(otherKeys, 2)
    stub.failNextList = Some("boom")
    var purged = List.empty[String]
    // `dueManagedPrefixes` only returns rows with a NULL purgedAt, so the fixture
    // drops a row once it has been stamped.
    val w = new ManagedStoreWiring(
      cfg = cfg,
      client = stub,
      due = _ =>
        List(row("mp-1", keyPrefix), row("mp-2", otherKeys)).filterNot(r => purged.contains(r.id)),
      markPurged = (id, _) => purged = purged :+ id,
      isLeader = () => true
    )
    val t = w.tick()
    t.unsafeRunSync()
    // First row's list failed and it moved on; the second row drained fully.
    stub.allKeys.sorted shouldBe List(s"${keyPrefix}0", s"${keyPrefix}1")
    purged shouldBe List("mp-2")

    // The failed prefix is retried on the next tick.
    t.unsafeRunSync()
    stub.allKeys shouldBe empty
    purged shouldBe List("mp-2", "mp-1")

  it should "skip a row whose prefix does not live under the configured bucket" in:
    val stub = new StubManagedStoreClient
    stub.seed(keyPrefix, 2)
    var purged = List.empty[String]
    val bogus  = row("mp-1", keyPrefix).copy(prefix = "s3://someone-elses-bucket/acme_sales/")
    val w      = new ManagedStoreWiring(
      cfg = cfg,
      client = stub,
      due = _ => List(bogus),
      markPurged = (id, _) => purged = purged :+ id,
      isLeader = () => true
    )
    w.tick().unsafeRunSync()
    stub.listPrefixCalls shouldBe 0
    stub.allKeys.size shouldBe 2
    purged shouldBe empty

  "fiber" should "be inert when disabled" in:
    val stub = new StubManagedStoreClient
    stub.seed(keyPrefix, 2)
    var dueCalls = 0
    var purged   = List.empty[String]
    val w        = new ManagedStoreWiring(
      cfg = cfg.copy(enabled = false),
      client = stub,
      due = _ => { dueCalls += 1; List(row("mp-1", keyPrefix)) },
      markPurged = (id, _) => purged = purged :+ id,
      isLeader = () => true
    )
    w.fiber.flatMap(_.join).unsafeRunSync().isSuccess shouldBe true
    dueCalls shouldBe 0
    stub.listPrefixCalls shouldBe 0
    stub.allKeys.size shouldBe 2
    purged shouldBe empty
