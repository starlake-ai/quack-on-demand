package ai.starlake.quack.edge.cls

import ai.starlake.quack.model.PoolKey
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.collection.mutable.ListBuffer

/** [[CachedColumnCatalog.historyStable]]'s cache over stub reads: what an answer is kept under, and
  * what a failed read answers. The reads themselves are `DuckLakeCatalogReaderTimeTravelSpec`'s,
  * and the wiring that picks a pool's catalog `EdgeRewritersHistorySpec`'s.
  */
class CachedColumnCatalogSpec extends AnyFlatSpec with Matchers:

  private val Parent = Some(PoolKey("acme", "acme_lake", "p"))
  private val Branch = Some(PoolKey("acme", "acme_lake__br_ab12cd34", "p"))

  /** One catalog whose newest snapshot and history answer the test sets, counting the reads. */
  private final class Stub:
    val snapshot      = new AtomicReference[IO[Option[Long]]](IO.pure(Some(7L)))
    val answer        = new AtomicReference[IO[Boolean]](IO.pure(true))
    val snapshotReads = new AtomicInteger
    val historyReads  = new AtomicInteger
    val catalog       = new CachedColumnCatalog(
      fetch = (_, _, _, _, _) => IO.pure(Nil),
      history = (_, _, _, _, _) => IO.defer { historyReads.incrementAndGet(); answer.get },
      latestSnapshot = (_, _, _) => IO.defer { snapshotReads.incrementAndGet(); snapshot.get }
    )
    def check(pool: Option[PoolKey] = Parent, table: String = "customer"): Boolean =
      catalog.historyStable("acme", pool, "lake", "main", table).unsafeRunSync()

  "historyStable" should "read a true again once the catalog has a newer snapshot" in {
    val s = new Stub
    s.check() shouldBe true
    // An admin renames a column onto a masked name: one commit, so a new snapshot.
    s.snapshot.set(IO.pure(Some(8L)))
    s.answer.set(IO.pure(false))
    s.check() shouldBe false
    s.historyReads.get shouldBe 2
  }

  it should "reuse a true while the catalog's newest snapshot is unchanged, reading that each time" in {
    val s = new Stub
    s.check() shouldBe true
    s.check() shouldBe true
    s.historyReads.get shouldBe 1
    s.snapshotReads.get shouldBe 2
  }

  it should "file a true under the snapshot read before it, so a commit landing after is seen" in {
    val s = new Stub
    // The rename commits right after the history read answered: what that read saw is what the
    // snapshot read before it saw, or older, never newer.
    s.answer.set(IO.delay {
      s.snapshot.set(IO.pure(Some(8L)))
      s.answer.set(IO.pure(false))
      true
    })
    s.check() shouldBe true
    s.check() shouldBe false
  }

  it should "answer false when the history read fails, and keep that refusal under its snapshot" in {
    val s = new Stub
    s.answer.set(IO.raiseError(new java.sql.SQLException("canceling statement due to timeout")))
    s.check() shouldBe false
    s.answer.set(IO.pure(true))
    s.check() shouldBe false
    s.historyReads.get shouldBe 1
  }

  it should "answer false, reading no history, when the newest snapshot cannot be read" in {
    val failing = new Stub
    failing.snapshot.set(IO.raiseError(new java.sql.SQLException("connection refused")))
    failing.check() shouldBe false
    // Kept for a tick: a metadata Postgres that does not answer costs one bounded read, not one per
    // statement.
    failing.check() shouldBe false
    failing.snapshotReads.get shouldBe 1
    failing.historyReads.get shouldBe 0

    val empty = new Stub
    empty.snapshot.set(IO.pure(None))
    empty.check() shouldBe false
    empty.historyReads.get shouldBe 0
  }

  it should "answer each pool and each table from its own reads, so a branch never takes its parent's" in {
    val pools     = ListBuffer.empty[Option[PoolKey]]
    val snapshots = ListBuffer.empty[Option[PoolKey]]
    val catalog   = new CachedColumnCatalog(
      fetch = (_, _, _, _, _) => IO.pure(Nil),
      // The branch renamed a column of `customer` since its fork; its parent did not.
      history =
        (_, pool, _, _, table) => IO { pools += pool; pool == Parent && table == "customer" },
      latestSnapshot = (_, pool, _) => IO { snapshots += pool; Some(7L) }
    )
    def check(pool: Option[PoolKey], table: String = "customer") =
      catalog.historyStable("acme", pool, "lake", "main", table).unsafeRunSync()
    check(Parent) shouldBe true
    check(Branch) shouldBe false
    check(Parent, "orders") shouldBe false
    check(Parent) shouldBe true
    pools.toList shouldBe List(Parent, Branch, Parent)
    snapshots.toList shouldBe List(Parent, Branch, Parent, Parent)
  }
