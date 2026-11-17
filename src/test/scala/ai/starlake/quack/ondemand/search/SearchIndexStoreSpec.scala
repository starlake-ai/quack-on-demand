package ai.starlake.quack.ondemand.search

import ai.starlake.quack.model.{SearchIndex, SearchIndexField, Tenant, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.state.{
  LiquibaseRunner,
  PostgresControlPlaneStore,
  SearchIndexStore
}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import scala.util.Try

/** The registry's Postgres rows (Liquibase 0059), through the IO interface the service uses and the
  * store's own transaction seam.
  */
class SearchIndexStoreSpec extends AnyFlatSpec with Matchers:
  TestPostgres.dropStrayTestDatabases("qodsix")

  private def withStore(test: (PostgresControlPlaneStore, SearchIndexStore) => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodsix_${UUID.randomUUID().toString.take(8)}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val store = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try
        store.upsertTenant(Tenant("acme"))
        List("td-lake", "td-other").foreach { id =>
          store.upsertTenantDb(
            TenantDb(id, "acme", id.stripPrefix("td-"), TenantDbKind.DuckLake, Map.empty, "")
          )
        }
        test(store, SearchIndexStore.over(store))
      finally store.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  // Postgres keeps microseconds.
  private val At = Instant.now().truncatedTo(ChronoUnit.MILLIS)

  private def index(
      td: String = "td-lake",
      tableId: Long = 7L,
      id: UUID = UUID.randomUUID()
  ): SearchIndex =
    SearchIndex(
      indexId = id,
      tenantDbId = td,
      schemaName = "main",
      tableName = "pages",
      tableId = tableId,
      fields = List(SearchIndexField("title", 2L), SearchIndexField("body", 3L)),
      bundleRoot = "s3://lake/acme_lake/",
      state = SearchIndex.Ready,
      segmentsVersion = 0L,
      createdBy = "alice",
      createdAt = At
    )

  "create" should "store an index found by its table id and by its index id" in withStore {
    (_, s) =>
      val ix = index()
      s.create(ix).unsafeRunSync() shouldBe Right(ix)
      s.find("td-lake", 7L).unsafeRunSync() shouldBe Some(ix)
      s.findByIndexId(ix.indexId).unsafeRunSync() shouldBe Some(ix)
      s.find("td-lake", 8L).unsafeRunSync() shouldBe None
      s.find("td-other", 7L).unsafeRunSync() shouldBe None
  }

  it should "refuse a second index on one table, not the same table id of another row" in
    withStore { (_, s) =>
      s.create(index()).unsafeRunSync().isRight shouldBe true
      s.create(index()).unsafeRunSync() shouldBe Left("exists")
      s.create(index(td = "td-other")).unsafeRunSync().isRight shouldBe true
    }

  "list" should "answer the indexes of the named rows only" in withStore { (_, s) =>
    val a = index(tableId = 1L)
    val b = index(tableId = 2L)
    val c = index(td = "td-other", tableId = 1L)
    List(a, b, c).foreach(ix => s.create(ix).unsafeRunSync())
    s.list(Set("td-lake")).unsafeRunSync().map(_.indexId).toSet shouldBe Set(a.indexId, b.indexId)
    s.list(Set("td-lake", "td-other")).unsafeRunSync() should have size 3
    s.list(Set.empty).unsafeRunSync() shouldBe Nil
  }

  "delete" should "remove the index with its segments and leave its tombstone" in withStore {
    (store, s) =>
      val ix = index()
      s.create(ix).unsafeRunSync()
      s.recordSegment(ix.indexId, "k1", "s3://lake/f1.parquet", 10L, 2L, 5L, 3).unsafeRunSync()
      s.delete(ix.indexId).unsafeRunSync() shouldBe true
      s.findByIndexId(ix.indexId).unsafeRunSync() shouldBe None
      store.listSearchSegments(ix.indexId) shouldBe Nil
      store.listSearchIndexTombstones().map(t => (t.indexId, t.bundleRoot)) shouldBe
        List(ix.indexId -> ix.bundleRoot)
      s.delete(ix.indexId).unsafeRunSync() shouldBe false
  }

  it should "write the tombstone and the delete in one transaction" in withStore { (store, s) =>
    val ix = index()
    s.create(ix).unsafeRunSync()
    a[RuntimeException] should be thrownBy
      store.deleteSearchIndexWith(ix.indexId, () => throw new RuntimeException("injected"))
    // The failure after the tombstone insert rolled the tombstone back: the index stays, with no
    // tombstone.
    s.findByIndexId(ix.indexId).unsafeRunSync() shouldBe Some(ix)
    store.listSearchIndexTombstones() shouldBe Nil
  }

  "recordSegment" should "upsert by path key and move segments_version once per call" in
    withStore { (store, s) =>
      val ix = index()
      s.create(ix).unsafeRunSync()
      s.recordSegment(ix.indexId, "k1", "s3://lake/f1.parquet", 10L, 2L, 5L, 3).unsafeRunSync()
      s.findByIndexId(ix.indexId).unsafeRunSync().map(_.segmentsVersion) shouldBe Some(1L)
      s.recordSegment(ix.indexId, "k1", "s3://lake/f1.parquet", 20L, 4L, 6L, 3).unsafeRunSync()
      s.recordSegment(ix.indexId, "k2", "s3://lake/f2.parquet", 30L, 6L, 7L, 3).unsafeRunSync()
      s.findByIndexId(ix.indexId).unsafeRunSync().map(_.segmentsVersion) shouldBe Some(3L)
      store.listSearchSegments(ix.indexId).map(g => (g.pathKey, g.bytes, g.docs)) shouldBe
        List(("k1", 20L, 4L), ("k2", 30L, 6L))
    }

  "setState" should "write the state" in withStore { (_, s) =>
    val ix = index()
    s.create(ix).unsafeRunSync()
    s.setState(ix.indexId, SearchIndex.Disabled).unsafeRunSync()
    s.findByIndexId(ix.indexId).unsafeRunSync().map(_.state) shouldBe Some(SearchIndex.Disabled)
  }

  it should "refuse a state the registry does not know" in withStore { (_, s) =>
    val ix = index()
    s.create(ix).unsafeRunSync()
    a[java.sql.SQLException] should be thrownBy s.setState(ix.indexId, "broken").unsafeRunSync()
  }

  "deleteForTenantDb" should "tombstone every index of the row and leave another row's" in
    withStore { (store, s) =>
      val a = index(tableId = 1L)
      val b = index(tableId = 2L)
      val c = index(td = "td-other", tableId = 1L)
      List(a, b, c).foreach(ix => s.create(ix).unsafeRunSync())
      s.deleteForTenantDb("td-lake").unsafeRunSync() shouldBe 2
      s.list(Set("td-lake", "td-other")).unsafeRunSync().map(_.indexId) shouldBe List(c.indexId)
      store.listSearchIndexTombstones().map(_.indexId).toSet shouldBe Set(a.indexId, b.indexId)
      // The row can go now: nothing holds its key.
      store.deleteTenantDb("td-lake")
    }

  "the tenant-db key" should "refuse a row's delete while it holds an index" in withStore {
    (store, s) =>
      s.create(index()).unsafeRunSync()
      a[java.sql.SQLException] should be thrownBy store.deleteTenantDb("td-lake")
  }

  "a manifest upsert" should "write names and state only, keeping the creator and segments" in
    withStore { (store, s) =>
      val ix = index()
      s.create(ix).unsafeRunSync()
      s.recordSegment(ix.indexId, "k1", "s3://lake/f1.parquet", 10L, 2L, 5L, 3).unsafeRunSync()
      store.upsertSearchIndex(ix.copy(state = SearchIndex.Disabled, createdBy = "manifest"))
      val kept = store.findSearchIndexById(ix.indexId).get
      (kept.state, kept.createdBy, kept.segmentsVersion) shouldBe (
        SearchIndex.Disabled,
        "alice",
        1L
      )
      store.listSearchSegments(ix.indexId) should have size 1
    }

  it should "refuse to change an index's identity in place, writing nothing" in withStore {
    (store, s) =>
      val ix = index()
      s.create(ix).unsafeRunSync()
      List(
        ix.copy(tableId = 9L),
        ix.copy(fields = List(SearchIndexField("title", 2L))),
        ix.copy(bundleRoot = "s3://elsewhere/"),
        ix.copy(tenantDbId = "td-other")
      ).foreach { changed =>
        withClue(changed)(
          a[java.sql.SQLException] should be thrownBy store.upsertSearchIndex(changed)
        )
      }
      store.findSearchIndexById(ix.indexId) shouldBe Some(ix)
  }

  it should "throw when an insert writes nothing (the table indexed under another id)" in withStore {
    (store, s) =>
      s.create(index()).unsafeRunSync()
      a[java.sql.SQLException] should be thrownBy store.upsertSearchIndex(index())
      store.listAllSearchIndexes() should have size 1
  }

  "an insert" should "take back the tombstone of the index id it writes" in withStore {
    (store, s) =>
      val ix = index()
      s.create(ix).unsafeRunSync()
      s.delete(ix.indexId).unsafeRunSync() shouldBe true
      store.listSearchIndexTombstones().map(_.indexId) shouldBe List(ix.indexId)
      // A restore after the drop: the index is live again, and no tombstone would let Phase 2's
      // GC collect its bundles.
      store.upsertSearchIndex(ix)
      store.findSearchIndexById(ix.indexId).map(_.indexId) shouldBe Some(ix.indexId)
      store.listSearchIndexTombstones() shouldBe Nil
  }
