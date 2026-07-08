package ai.starlake.quack.ondemand.catalog

import ai.starlake.quack.model.{Names, SnapshotTag}
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import ai.starlake.quack.ondemand.state.testkit.PostgresFixture
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PinnedSetResolverSpec extends AnyFlatSpec with Matchers with PostgresFixture:

  private def tag(t: String, db: String, name: String, snap: Long, prot: Boolean) =
    SnapshotTag(Names.newSurrogateId("stag"), t, db, name, snap, isProtected = prot)

  "pinnedSnapshots" should "return only protected tags' snapshots for the scope" in {
    val store = new InMemoryControlPlaneStore()
    store.createSnapshotTag(tag("acme", "acme_db", "keep", 10L, prot = true))
    store.createSnapshotTag(tag("acme", "acme_db", "note", 11L, prot = false))
    store.createSnapshotTag(tag("acme", "acme_other", "keep2", 12L, prot = true))
    store.createSnapshotTag(tag("globex", "globex_db", "keep3", 13L, prot = true))
    val resolver =
      new PinnedSetResolver(store, (_, _) => fail("no reader needed for snapshot ids"))
    resolver.pinnedSnapshots("acme", "acme_db") shouldBe Set(10L)
    resolver.pinnedSnapshots("acme", "acme_missing") shouldBe empty
  }

  "pinnedFiles" should "union the files of every pinned snapshot" in
    withCatalog("tpch") { (reader, _) =>
      val store = new InMemoryControlPlaneStore()
      val snaps = reader.listSnapshots().map(_.snapshotId).sorted
      val s     = snaps.lastOption.getOrElse(fail("tpch fixture produced no snapshots"))
      store.createSnapshotTag(tag("acme", "acme_db", "keep", s, prot = true))
      val resolver = new PinnedSetResolver(store, (_, _) => reader)
      resolver.pinnedFiles("acme", "acme_db") shouldBe reader.filesReferencedAt(s)
      resolver.pinnedFiles("acme", "empty_db") shouldBe empty
    }
