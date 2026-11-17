package ai.starlake.quack.ondemand.manifest

import ai.starlake.quack.model.{
  SearchIndex,
  SearchIndexField,
  SearchSegment,
  Tenant,
  TenantDb,
  TenantDbKind
}
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PostgresControlPlaneStore}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import io.circe.syntax.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.UUID
import scala.util.Try

/** Search indexes in a manifest (spec 6.1), on real Postgres so the non-cascading key of
  * `qodstate_search_index` on `qodstate_tenant_db` and the import's one transaction are the real
  * ones: export, import into an empty store and export again give the same manifest, a re-import
  * changes nothing, a replace drops and tombstones what the manifest leaves out, and an entry
  * naming k1, b or options is refused (OWNER RULING 2026-11-16, D10).
  */
class ManifestSearchIndexSpec extends AnyFlatSpec with Matchers:
  TestPostgres.dropStrayTestDatabases("qodmsix")

  private def withStore(test: PostgresControlPlaneStore => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodmsix_${UUID.randomUUID().toString.take(8)}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val store = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try test(store)
      finally store.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  private val IdA = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100")
  private val IdB = UUID.fromString("1f1e1d1c-1b1a-4918-9716-151413121110")
  private val IdC = UUID.fromString("2f2e2d2c-2b2a-4928-a726-252423222120")

  private def index(id: UUID, td: String, tableId: Long, state: String = SearchIndex.Ready) =
    SearchIndex(
      indexId = id,
      tenantDbId = td,
      schemaName = "main",
      tableName = s"t$tableId",
      tableId = tableId,
      fields = List(SearchIndexField("title", 2L), SearchIndexField("body", 5L)),
      bundleRoot = s"s3://lake/$td/",
      state = state,
      segmentsVersion = 0L,
      createdBy = "alice",
      createdAt = Instant.EPOCH
    )

  /** acme with DuckLake databases `lake` (two indexes, one disabled) and `logs` (one), and a memory
    * database `mem`.
    */
  private def seed(store: PostgresControlPlaneStore): Unit =
    store.upsertTenant(Tenant("acme", displayName = "acme"))
    store.ensureBuiltins("acme")
    List("lake", "logs").foreach { n =>
      store.upsertTenantDb(TenantDb(s"td-$n", "acme", n, TenantDbKind.DuckLake, Map.empty, ""))
    }
    store.upsertTenantDb(TenantDb("td-mem", "acme", "mem", TenantDbKind.InMemory, Map.empty, ""))
    store.insertSearchIndex(index(IdA, "td-lake", 1L)) shouldBe true
    store.insertSearchIndex(index(IdB, "td-lake", 2L, SearchIndex.Disabled)) shouldBe true
    store.insertSearchIndex(index(IdC, "td-logs", 1L)) shouldBe true

  private def exported(store: PostgresControlPlaneStore): ConfigManifest =
    ManifestExporter.build(store, Instant.EPOCH, "test", "host").toOption.get

  private def run(m: ConfigManifest, store: PostgresControlPlaneStore) =
    ManifestImporter.apply(m, store, requireEncryption = false)

  private def dbOf(m: ConfigManifest, name: String): ManifestTenantDb =
    m.tenants.find(_.name == "acme").get.tenantDbs.find(_.name == name).get

  private def withIndexes(m: ConfigManifest, db: String, ixs: Option[List[ManifestSearchIndex]]) =
    m.copy(tenants = m.tenants.map { t =>
      t.copy(tenantDbs =
        t.tenantDbs.map(d => if d.name == db then d.copy(searchIndexes = ixs) else d)
      )
    })

  "export" should "carry each database's indexes, with no option, segment or key" in withStore {
    store =>
      seed(store)
      val m = exported(store)
      dbOf(m, "lake").searchIndexes.get.map(ix => (ix.indexId, ix.tableId, ix.state)) shouldBe
        List((IdA.toString, 1L, "ready"), (IdB.toString, 2L, "disabled"))
      dbOf(m, "lake").searchIndexes.get.head shouldBe ManifestSearchIndex(
        IdA.toString,
        "main",
        "t1",
        1L,
        List(ManifestSearchIndexField("title", 2L), ManifestSearchIndexField("body", 5L)),
        "s3://lake/td-lake/",
        "ready"
      )
      dbOf(m, "mem").searchIndexes shouldBe None
      import ConfigManifest.given
      val json = m.asJson.noSpaces
      List("k1", "\"b\"", "options", "refusedKeys", "segmentsVersion", "macKey").foreach { k =>
        withClue(k)(json should not include k)
      }
  }

  it should "round-trip through an empty store into the same manifest" in withStore { a =>
    seed(a)
    val first = exported(a)
    withStore { b =>
      run(first, b) shouldBe Right(())
      exported(b).tenants shouldBe first.tenants
      b.findSearchIndexById(IdB).map(ix => (ix.tenantDbId.nonEmpty, ix.tableId, ix.state)) shouldBe
        Some((true, 2L, SearchIndex.Disabled))
      b.listSearchIndexTombstones() shouldBe Nil
    }
  }

  "a re-import of the same manifest" should "leave the same rows and their segments" in withStore {
    store =>
      seed(store)
      store.recordSearchSegment(
        SearchSegment(IdA, "k", "s3://lake/f.parquet", 1L, 1L, Instant.EPOCH, 1L, 3)
      )
      val before = store.listAllSearchIndexes()
      run(exported(store), store) shouldBe Right(())
      store.listAllSearchIndexes() shouldBe before
      store.listSearchSegments(IdA) should have size 1
      store.listSearchIndexTombstones() shouldBe Nil
  }

  "an import that replaces databases" should "keep the manifest's indexes and tombstone the rest" in
    withStore { store =>
      seed(store)
      val base = exported(store)
      // `logs` leaves the manifest (its row goes, with its index), and `lake` keeps only IdA.
      val m = withIndexes(base, "lake", Some(dbOf(base, "lake").searchIndexes.get.take(1))).copy(
        tenants = base.tenants.map(t => t.copy(tenantDbs = t.tenantDbs.filterNot(_.name == "logs")))
      )
      run(
        withIndexes(m, "lake", Some(dbOf(base, "lake").searchIndexes.get.take(1))),
        store
      ) shouldBe
        Right(())
      store.listAllSearchIndexes().map(_.indexId) shouldBe List(IdA)
      store.listSearchIndexTombstones().map(_.indexId).toSet shouldBe Set(IdB, IdC)
      store.listTenantDbs("acme").map(_.name).toSet shouldBe Set("lake", "mem")
    }

  "a database's absent searchIndexes" should "leave its stored indexes as they are" in withStore {
    store =>
      seed(store)
      run(withIndexes(exported(store), "lake", None), store) shouldBe Right(())
      store.listSearchIndexes(Set("td-lake")).map(_.indexId) shouldBe List(IdA, IdB)
  }

  "an index of a database that cannot hold one" should "import with no row" in withStore { store =>
    seed(store)
    val entry = dbOf(exported(store), "lake").searchIndexes.get.head
      .copy(indexId = "3f3e3d3c-3b3a-4938-b736-353433323130")
    val (result, events) = ai.starlake.quack.ondemand.runtime.testkit.LogCapture.logged(
      ManifestImporter.getClass.getName
    )(run(withIndexes(exported(store), "mem", Some(List(entry))), store))
    result shouldBe Right(())
    store.findSearchIndexById(UUID.fromString(entry.indexId)) shouldBe None
    // One ERROR line per index it could not import (review M8).
    val lines = events.filter(e =>
      e.getLevel == ch.qos.logback.classic.Level.ERROR && e.getFormattedMessage.contains(
        entry.indexId
      )
    )
    lines should have size 1
    lines.head.getFormattedMessage should include("database 'mem' not imported")
  }

  "a database whose kind leaves DuckLake" should "lose its indexes, each with its tombstone" in
    withStore { store =>
      seed(store)
      val base = exported(store)
      val m    = base.copy(tenants = base.tenants.map { t =>
        t.copy(tenantDbs = t.tenantDbs.map { d =>
          if d.name == "logs" then d.copy(kind = "memory", searchIndexes = None) else d
        })
      })
      run(m, store) shouldBe Right(())
      store.listSearchIndexes(Set("td-logs")) shouldBe Nil
      store.listSearchIndexTombstones().map(_.indexId) shouldBe List(IdC)
      store.listSearchIndexes(Set("td-lake")).map(_.indexId) shouldBe List(IdA, IdB)
    }

  "export" should "carry an empty list for a DuckLake database with none, so a restore drops a later one" in
    withStore { store =>
      seed(store)
      store.deleteSearchIndex(IdC) shouldBe true
      val backup = exported(store)
      dbOf(backup, "logs").searchIndexes shouldBe Some(Nil)
      // An index created after the backup goes when the backup is restored.
      val later = index(UUID.fromString("4f4e4d4c-4b4a-4948-8746-454443424140"), "td-logs", 9L)
      store.insertSearchIndex(later) shouldBe true
      run(backup, store) shouldBe Right(())
      store.listSearchIndexes(Set("td-logs")) shouldBe Nil
      store.listSearchIndexTombstones().map(_.indexId).toSet shouldBe Set(IdC, later.indexId)
    }

  "a restore after a drop" should "bring the index back live, with no tombstone" in withStore {
    store =>
      seed(store)
      val backup = exported(store)
      store.deleteSearchIndex(IdA) shouldBe true
      store.listSearchIndexTombstones().map(_.indexId) shouldBe List(IdA)
      run(backup, store) shouldBe Right(())
      store.findSearchIndexById(IdA).map(_.tableId) shouldBe Some(1L)
      store.listSearchIndexTombstones() shouldBe Nil
  }

  "a tenant cloned by renaming it in the manifest" should "be refused, the stored index kept" in
    withStore { store =>
      seed(store)
      val base  = exported(store)
      val clone =
        base.copy(tenants = base.tenants.map(t => t.copy(name = "acme2", displayName = "acme2")))
      val refusal =
        run(clone, store).left.toOption.map(_.mkString("; ")).getOrElse(fail("imported"))
      refusal should include(IdA.toString)
      refusal should include("stored for another database")
      store.findSearchIndexById(IdA).map(_.tenantDbId) shouldBe Some("td-lake")
    }

  "an entry changing an index's identity" should "be refused, naming the index id" in withStore {
    store =>
      seed(store)
      val base  = exported(store)
      val entry = dbOf(base, "lake").searchIndexes.get.head
      List(
        entry.copy(fields = entry.fields.take(1)),
        entry.copy(tableId = 99L),
        entry.copy(bundleRoot = "s3://elsewhere/")
      ).foreach { changed =>
        val m =
          withIndexes(base, "lake", Some(List(changed, dbOf(base, "lake").searchIndexes.get(1))))
        val refusal = run(m, store).left.toOption.map(_.mkString("; ")).getOrElse(fail("imported"))
        withClue(changed) {
          refusal should include(IdA.toString)
          refusal should include("identity never changes in place")
        }
      }
      store.findSearchIndexById(IdA).map(_.fields.size) shouldBe Some(2)
  }

  "an entry" should "be refused with a malformed id, a table twice, or k1, b or options" in
    withStore { store =>
      seed(store)
      val base                                    = exported(store)
      val entry                                   = dbOf(base, "lake").searchIndexes.get.head
      def refusal(ixs: List[ManifestSearchIndex]) =
        run(withIndexes(base, "lake", Some(ixs)), store).left.toOption.map(_.mkString("; "))
      List("not-a-uuid", IdA.toString.toUpperCase, "").foreach { bad =>
        withClue(bad)(refusal(List(entry.copy(indexId = bad))).get should include("canonical"))
      }
      refusal(List(entry, entry.copy(indexId = IdC.toString))).get should include(
        "table id 1 has more than one search index"
      )
      import ConfigManifest.given
      List(
        """"options":{"k1":1.2,"b":0.75}""",
        """"k1":1.5""",
        """"b":0.5"""
      ).foreach { extra =>
        val json =
          entry.asJson.noSpaces.stripSuffix("}") + s",$extra}"
        val decoded = io.circe.parser.decode[ManifestSearchIndex](json).toOption.get
        withClue(extra)(refusal(List(decoded)).get should include("fixed at 1.2 and 0.75"))
      }
      // Nothing was written by any refused import.
      store.listAllSearchIndexes().map(_.indexId).toSet shouldBe Set(IdA, IdB, IdC)
    }

  // Fix round 2: the import never issues a fresh row for a database it keeps. Its delete pass
  // removes only the names the manifest leaves out (ManifestImporter.scala:727-741), and a kept name
  // takes its stored id (ManifestImporter.scala:745-746), so an index id stored under it stays
  // under the same tenant-db row.
  "an import keeping a database" should "keep its tenant-db row, so its index ids stay its own" in
    withStore { store =>
      seed(store)
      val backup = exported(store)
      run(backup, store) shouldBe Right(())
      store.listTenantDbs("acme").map(d => d.name -> d.id).toMap shouldBe
        Map("lake" -> "td-lake", "logs" -> "td-logs", "mem" -> "td-mem")
      store.listSearchIndexes(Set("td-lake")).map(_.indexId) shouldBe List(IdA, IdB)
    }

  "a database replaced outside the import" should "restore from the backup under its new row" in
    withStore { store =>
      seed(store)
      val backup = exported(store)
      // database/delete then database/create: the row goes with its indexes (tombstoned), and a
      // fresh row takes the name.
      store.deleteSearchIndexesForTenantDb("td-lake") shouldBe 2
      store.deleteTenantDb("td-lake")
      store.upsertTenantDb(
        TenantDb("td-lake2", "acme", "lake", TenantDbKind.DuckLake, Map.empty, "")
      )
      run(backup, store) shouldBe Right(())
      store.listSearchIndexes(Set("td-lake2")).map(_.indexId) shouldBe List(IdA, IdB)
      store.listSearchIndexTombstones().map(_.indexId).toSet should contain noneOf (IdA, IdB)
    }
