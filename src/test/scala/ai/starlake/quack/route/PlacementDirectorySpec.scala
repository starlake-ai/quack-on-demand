package ai.starlake.quack.route

import ai.starlake.quack.model.PoolKey
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PlacementDirectorySpec extends AnyFlatSpec with Matchers:

  private val poolKey             = PoolKey("acme", "acme_default", "sales")
  private val live                = Set("n1", "n2")
  private val tA                  = "db.main.a"
  private val tB                  = "db.main.b"
  private def reads(ts: String*)  = RoutingRefs(ts.toSet, Set.empty)
  private def writes(ts: String*) = RoutingRefs(Set.empty, ts.toSet)

  "record" should "claim unassigned tables for the chosen node" in:
    val d = new PlacementDirectory()
    d.record(poolKey, "n1", reads(tA), live, now = 1L) shouldBe "claim"
    d.viewFor(poolKey, Set(tA), live) shouldBe
      Map(tA -> Assignment(List(HomeEntry("n1", 0L)), 0L, 1L))

  it should "report sticky-fresh when the chosen node is a fresh home for all tables" in:
    val d = new PlacementDirectory()
    d.record(poolKey, "n1", reads(tA), live, 1L)
    d.record(poolKey, "n1", reads(tA), live, 2L) shouldBe "sticky-fresh"

  it should "add a second home on overflow instead of moving the first" in:
    val d = new PlacementDirectory()
    d.record(poolKey, "n1", reads(tA), live, 1L)
    d.record(poolKey, "n2", reads(tA), live, 2L) shouldBe "overflow-new-home"
    d.viewFor(poolKey, Set(tA), live) shouldBe
      Map(tA -> Assignment(List(HomeEntry("n2", 0L), HomeEntry("n1", 0L)), 0L, 2L))

  it should "evict the LRU home when the set is full" in:
    val d    = new PlacementDirectory()
    val four = Set("n1", "n2", "n3", "n4")
    d.record(poolKey, "n1", reads(tA), four, 1L)
    d.record(poolKey, "n2", reads(tA), four, 2L)
    d.record(poolKey, "n3", reads(tA), four, 3L)
    d.record(poolKey, "n4", reads(tA), four, 4L) shouldBe "overflow-evict-home"
    d.viewFor(poolKey, Set(tA), four)(tA).homes.map(_.nodeId) shouldBe List("n4", "n3", "n2")

  it should "bump the epoch on writes, keep the writer fresh, and leave other homes stale" in:
    val d = new PlacementDirectory()
    d.record(poolKey, "n1", reads(tA), live, 1L)
    d.record(poolKey, "n2", reads(tA), live, 2L) // homes n2, n1 at epoch 0
    d.record(poolKey, "n2", writes(tA), live, 3L) shouldBe "sticky-fresh"
    val a = d.viewFor(poolKey, Set(tA), live)(tA)
    a.currentEpoch shouldBe 1L
    a.homes shouldBe List(HomeEntry("n2", 1L), HomeEntry("n1", 0L)) // n1 now stale

  it should "re-warm a stale home when it serves a read" in:
    val d = new PlacementDirectory()
    d.record(poolKey, "n1", reads(tA), live, 1L)
    d.record(poolKey, "n2", reads(tA), live, 2L)
    d.record(poolKey, "n2", writes(tA), live, 3L) // n1 stale at epoch 1
    d.record(poolKey, "n1", reads(tA), live, 4L) shouldBe "sticky-stale"
    d.viewFor(poolKey, Set(tA), live)(tA).homes shouldBe
      List(HomeEntry("n1", 1L), HomeEntry("n2", 1L))

  it should "drop dead homes and re-claim when none survive" in:
    val d = new PlacementDirectory()
    d.record(poolKey, "n1", reads(tA), live, 1L)
    d.viewFor(poolKey, Set(tA), Set("n2")) shouldBe empty
    d.record(poolKey, "n2", reads(tA), Set("n2"), 2L) shouldBe "claim"
    d.viewFor(poolKey, Set(tA), Set("n2"))(tA).homes shouldBe List(HomeEntry("n2", 0L))

  it should "evict beyond maxTablesPerPool and clear per pool" in:
    val d = new PlacementDirectory(maxTablesPerPool = 1)
    d.record(poolKey, "n1", reads(tA), live, 1L)
    d.record(poolKey, "n1", reads(tB), live, 2L)
    d.viewFor(poolKey, Set(tA), live) shouldBe empty // evicted
    d.clear(poolKey)
    d.viewFor(poolKey, Set(tB), live) shouldBe empty

  it should "converge under concurrent claims" in:
    val d       = new PlacementDirectory()
    val threads = (1 to 8).map { i =>
      new Thread(() =>
        d.record(poolKey, if i % 2 == 0 then "n1" else "n2", reads(tA), live, i.toLong): Unit
      )
    }
    threads.foreach(_.start()); threads.foreach(_.join())
    val a = d.viewFor(poolKey, Set(tA), live)(tA)
    a.homes.map(_.nodeId).toSet.subsetOf(Set("n1", "n2")) shouldBe true
    a.homes.size should be <= Assignment.MaxHomes

  "isObjectStorePath" should "accept object-store schemes and reject local paths" in:
    PlacementDirectory.isObjectStorePath("s3://bucket/x") shouldBe true
    PlacementDirectory.isObjectStorePath("gs://bucket/x") shouldBe true
    PlacementDirectory.isObjectStorePath("az://container/x") shouldBe true
    PlacementDirectory.isObjectStorePath("/Users/me/data") shouldBe false
    PlacementDirectory.isObjectStorePath("file:///data") shouldBe false
    PlacementDirectory.isObjectStorePath("C:\\data") shouldBe false
    PlacementDirectory.isObjectStorePath("") shouldBe false
