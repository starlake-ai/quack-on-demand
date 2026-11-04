package ai.starlake.quack.ondemand.catalog

import ai.starlake.quack.model.{Branch, BranchStatus, Names, SnapshotTag, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PinnedSetResolverSpec extends AnyFlatSpec with Matchers:

  private def tag(t: String, db: String, name: String, snap: Long, prot: Boolean) =
    SnapshotTag(Names.newSurrogateId("stag"), t, db, name, snap, isProtected = prot)

  "pinnedSnapshots" should "return only protected tags' snapshots for the scope" in {
    val store = new InMemoryControlPlaneStore()
    store.createSnapshotTag(tag("acme", "acme_db", "keep", 10L, prot = true))
    store.createSnapshotTag(tag("acme", "acme_db", "note", 11L, prot = false))
    store.createSnapshotTag(tag("acme", "acme_other", "keep2", 12L, prot = true))
    store.createSnapshotTag(tag("globex", "globex_db", "keep3", 13L, prot = true))
    val resolver = new PinnedSetResolver(store)
    resolver.pinnedSnapshots("acme", "acme_db") shouldBe Set(10L)
    resolver.pinnedSnapshots("acme", "acme_missing") shouldBe empty
  }

  "hasLiveBranch" should "be true only while the tenant-db has an open or proposed branch" in {
    val store  = new InMemoryControlPlaneStore()
    val parent = TenantDb("tdb-1", "acme", "acme_db", TenantDbKind.DuckLake, Map.empty, "/d")
    store.upsertTenantDb(parent)
    store.upsertTenantDb(
      TenantDb("tdb-2", "acme", "acme_other", TenantDbKind.DuckLake, Map.empty, "/o")
    )
    def branch(name: String, status: BranchStatus) =
      Branch(
        Names.newSurrogateId("br"),
        "acme",
        parent.id,
        parent.name,
        name,
        s"td-$name",
        s"acme_db__br_$name",
        s"__br_$name",
        s"/d__br_$name/",
        5L,
        "alice",
        status,
        None
      )
    val resolver = new PinnedSetResolver(store)
    resolver.hasLiveBranch("acme", "acme_db") shouldBe false
    store.createBranch(branch("gone", BranchStatus.Discarded)).isRight shouldBe true
    store.createBranch(branch("done", BranchStatus.Merged)).isRight shouldBe true
    resolver.hasLiveBranch("acme", "acme_db") shouldBe false
    store.createBranch(branch("prop", BranchStatus.Proposed)).isRight shouldBe true
    resolver.hasLiveBranch("acme", "acme_db") shouldBe true
    resolver.hasLiveBranch("acme", "acme_other") shouldBe false
    resolver.hasLiveBranch("acme", "acme_missing") shouldBe false
  }

  "expiryFloor" should "be the oldest live branch's fork, and none without a live branch" in {
    val store  = new InMemoryControlPlaneStore()
    val parent = TenantDb("tdb-1", "acme", "acme_db", TenantDbKind.DuckLake, Map.empty, "/d")
    store.upsertTenantDb(parent)
    def branch(name: String, fork: Long, status: BranchStatus) =
      Branch(
        Names.newSurrogateId("br"),
        "acme",
        parent.id,
        parent.name,
        name,
        s"td-$name",
        s"acme_db__br_$name",
        s"__br_$name",
        s"/d__br_$name/",
        fork,
        "alice",
        status,
        None
      )
    val resolver = new PinnedSetResolver(store)
    resolver.expiryFloor("acme", "acme_db") shouldBe None
    store.createBranch(branch("gone", 1L, BranchStatus.Merged)).isRight shouldBe true
    resolver.expiryFloor("acme", "acme_db") shouldBe None
    store.createBranch(branch("newer", 9L, BranchStatus.Open)).isRight shouldBe true
    store.createBranch(branch("older", 5L, BranchStatus.Proposed)).isRight shouldBe true
    resolver.expiryFloor("acme", "acme_db") shouldBe Some(5L)
  }
