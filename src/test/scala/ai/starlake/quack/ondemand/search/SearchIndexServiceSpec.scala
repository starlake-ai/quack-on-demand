package ai.starlake.quack.ondemand.search

import ai.starlake.quack.model.{SearchIndex, SearchIndexField, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.api.SearchIndexCreateRequest
import ai.starlake.quack.ondemand.catalog.LiveTable
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, SearchIndexStore}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.parser.decode
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

import java.util.UUID
import scala.collection.concurrent.TrieMap

/** The registry's decisions, over an in-memory store and a fake catalog: each refusal of `create`'s
  * order with its code, the table and field ids read from the catalog, k1 and b refused whatever
  * their value (OWNER RULING 2026-11-16, D10), and `list`'s live state.
  */
class SearchIndexServiceSpec extends AnyFlatSpec with Matchers:

  private def td(
      name: String,
      kind: TenantDbKind = TenantDbKind.DuckLake,
      encrypted: Boolean = false,
      branchOf: Option[String] = None,
      defaultSchema: Option[String] = None
  ) =
    TenantDb(
      s"td-$name",
      "acme",
      name,
      kind,
      Map.empty,
      "",
      defaultSchema = defaultSchema,
      branchOf = branchOf,
      encrypted = encrypted
    )

  /** A catalog of `(schema, table) -> (table id, columns)`, live while in the map. */
  private final class FakeCatalog extends SearchCatalog:
    val tables = TrieMap(
      ("main", "pages") -> (
        41L,
        List(("id", 1L, "int64"), ("title", 2L, "varchar"), ("Body", 3L, "varchar"))
      ),
      ("docs", "notes") -> (42L, List(("txt", 1L, "varchar")))
    )
    @volatile var down                                                = false
    def liveTableColumns(td: TenantDb, schema: String, table: String) =
      if down then IO.raiseError(new java.sql.SQLException("connection to 10.0.0.9:5432 refused"))
      else
        // Matched ignoring case, as the reader does; the answer carries the catalog's spelling.
        IO.pure(tables.collectFirst {
          case ((s, t), (id, cols)) if s.equalsIgnoreCase(schema) && t.equalsIgnoreCase(table) =>
            LiveTable(id, s, t, cols)
        })
    def liveTableName(td: TenantDb, tableId: Long) =
      if down then IO.raiseError(new java.sql.SQLException("down"))
      else IO.pure(tables.collectFirst { case (name, (id, _)) if id == tableId => name })

  private final class Fixture(dataPath: Option[String] = Some("s3://lake/acme_lake/")):
    val rows = List(
      td("lake"),
      td("plain", defaultSchema = Some("docs")),
      td("mem", kind = TenantDbKind.InMemory),
      td("secret", encrypted = true),
      td("lake__br_1", branchOf = Some("td-lake"))
    )
    val store   = new InMemoryControlPlaneStore()
    val catalog = new FakeCatalog
    val ids     = Iterator.from(1).map(i => UUID.fromString(f"00000000-0000-4000-8000-$i%012d"))
    val service = new SearchIndexService(
      tenantDbOf = (t, d) => Option.when(t == "acme")(rows.find(_.name == d)).flatten,
      tenantDbsOf = t => if t == "acme" then rows else Nil,
      metastoreOf = _ => dataPath.map("dataPath" -> _).toMap,
      catalog = catalog,
      store = SearchIndexStore.over(store),
      newId = () => ids.next()
    )

    def create(
        table: String = "pages",
        columns: List[String] = List("title"),
        database: String = "lake",
        tenant: String = "acme",
        namesOptions: Boolean = false
    ) =
      service.create("alice", tenant, database, table, columns, namesOptions).unsafeRunSync()

  private val All: TenantDb => Boolean = _ => true
  import DeleteTarget.{ByIndexId, ByTable}

  private def code[A](r: Either[SearchError, A]): (StatusCode, String) =
    r match
      case Left(e)  => (e.status, e.code)
      case Right(a) => fail(s"expected a refusal, got $a")

  "create" should "resolve a bare table to the session schema with the catalog's ids" in {
    val f  = new Fixture
    val ix = f.create(columns = List("title", "body")).toOption.get
    (ix.schemaName, ix.tableName, ix.tableId) shouldBe ("main", "pages", 41L)
    // The catalog's spelling and field ids, never the request's.
    ix.fields shouldBe List(SearchIndexField("title", 2L), SearchIndexField("Body", 3L))
    ix.bundleRoot shouldBe "s3://lake/acme_lake/"
    ix.state shouldBe SearchIndex.Ready
    ix.createdBy shouldBe "alice"
    ix.indexId.toString shouldBe ix.indexId.toString.toLowerCase
    f.store.findSearchIndex("td-lake", 41L) shouldBe Some(ix)
  }

  it should "default the schema to the row's defaultSchema" in {
    val f = new Fixture
    f.create(database = "plain", table = "notes", columns = List("txt")).map(_.schemaName) shouldBe
      Right("docs")
  }

  it should "refuse, in order, with each code" in {
    val f = new Fixture
    code(f.create(database = "nope")) shouldBe (StatusCode.NotFound, "not_found")
    code(f.create(tenant = "globex")) shouldBe (StatusCode.NotFound, "not_found")
    code(f.create(database = "lake__br_1")) shouldBe
      (StatusCode.BadRequest, "search_branch_unsupported")
    code(f.create(database = "mem")) shouldBe (StatusCode.BadRequest, "search_ducklake_only")
    code(f.create(database = "secret")) shouldBe
      (StatusCode.BadRequest, "search_encrypted_unsupported")
    List("a.b.c", "pa ges", "1x", "", "main.", "\"pages\"").foreach { t =>
      withClue(t)(code(f.create(table = t)) shouldBe (StatusCode.BadRequest, "invalid_name"))
    }
    code(f.create(namesOptions = true)) shouldBe (StatusCode.BadRequest, "invalid_search_options")
    code(f.create(columns = Nil)) shouldBe (StatusCode.BadRequest, "invalid_search_columns")
    code(f.create(table = "missing")) shouldBe (StatusCode.NotFound, "table_not_found")
    code(f.create(columns = List("nope"))) shouldBe
      (StatusCode.BadRequest, "search_column_not_found")
    code(f.create(columns = List("id"))) shouldBe (StatusCode.BadRequest, "search_column_type")
    f.create().isRight shouldBe true
    code(f.create(table = "main.pages")) shouldBe (StatusCode.Conflict, "search_index_exists")
    f.catalog.down = true
    code(f.create(table = "docs.notes", columns = List("txt"))) shouldBe
      (StatusCode.ServiceUnavailable, "catalog_unavailable")
  }

  it should "say nothing of the catalog's failure" in {
    val f = new Fixture
    f.catalog.down = true
    f.create().left.toOption.get.message should not include "10.0.0.9"
  }

  it should "refuse 33 columns and a column named twice" in {
    val f = new Fixture
    code(f.create(columns = (1 to 33).map(i => s"c$i").toList)) shouldBe
      (StatusCode.BadRequest, "invalid_search_columns")
    code(f.create(columns = List("title", "title"))) shouldBe
      (StatusCode.BadRequest, "invalid_search_columns")
    code(f.create(columns = List("title", "TITLE"))) shouldBe
      (StatusCode.BadRequest, "invalid_search_columns")
  }

  it should "refuse a row with no data path to hold bundles" in {
    code(new Fixture(dataPath = None).create()) shouldBe (
      StatusCode.Conflict,
      "search_no_data_path"
    )
  }

  "a create body" should "name no option: k1 and b are refused whatever their value (D10)" in {
    val base = """"tenant":"acme","database":"lake","table":"pages","columns":["title"]"""
    def read(extra: String) = decode[SearchIndexCreateRequest](s"{$base$extra}").toOption.get
    read("").namesOptions shouldBe false
    List(
      ""","options":{"k1":1.5}""",
      ""","options":{"b":0.5}""",
      ""","options":{"k1":1.2,"b":0.75}""",
      ""","options":{}""",
      ""","k1":1.2""",
      ""","b":0.75"""
    ).foreach { extra =>
      withClue(extra) {
        val req = read(extra)
        req.namesOptions shouldBe true
        val f = new Fixture
        code(f.create(namesOptions = req.namesOptions)) shouldBe
          (StatusCode.BadRequest, "invalid_search_options")
        f.store.listAllSearchIndexes() shouldBe Nil
      }
    }
  }

  "a created index" should "store no option and answer no k1 or b" in {
    import io.circe.syntax.*
    import ai.starlake.quack.ondemand.api.SearchEndpoints.given
    val f    = new Fixture
    val ix   = f.create().toOption.get
    val json = ai.starlake.quack.ondemand.api.SearchHandlers
      .responseOf(ix, "acme", "lake", None, ai.starlake.quack.ondemand.auth.SessionScope.Superuser)
      .asJson
      .noSpaces
    json should not include "k1"
    json should not include "\"b\""
    json should not include "options"
    classOf[SearchIndex].getDeclaredFields.map(_.getName) should not contain "options"
  }

  "list" should "disable an index whose table was dropped, and show a rename's current name" in {
    val f     = new Fixture
    val pages = f.create().toOption.get
    val notes = f.create(table = "docs.notes", columns = List("txt")).toOption.get
    // notes renamed: same table id under another name; pages dropped.
    f.catalog.tables.remove(("docs", "notes"))
    f.catalog.tables.put(("docs", "memos"), (42L, List(("txt", 1L, "varchar"))))
    f.catalog.tables.remove(("main", "pages"))
    val listed = f.service.list("acme", Some("lake"), All).unsafeRunSync().toOption.get
    listed.map(l => (l.index.indexId, l.index.state, l.current)).toSet shouldBe Set(
      (pages.indexId, SearchIndex.Disabled, None),
      (notes.indexId, SearchIndex.Ready, Some(("docs", "memos")))
    )
    // A list writes nothing: the row keeps its stored state (lead decision, security M1).
    f.store.findSearchIndexById(pages.indexId).map(_.state) shouldBe Some(SearchIndex.Ready)
    // A table created again under the dropped name is another table id: the index stays disabled.
    f.catalog.tables.put(("main", "pages"), (77L, List(("title", 1L, "varchar"))))
    f.service
      .list("acme", None, All)
      .unsafeRunSync()
      .toOption
      .get
      .find(_.index.indexId == pages.indexId)
      .map(_.index.state) shouldBe Some(SearchIndex.Disabled)
  }

  it should "keep the stored state while the catalog cannot be read, and 404 an unknown database" in {
    val f = new Fixture
    f.create().toOption.get
    f.catalog.down = true
    f.service.list("acme", None, All).unsafeRunSync().toOption.get.map(_.index.state) shouldBe
      List(SearchIndex.Ready)
    code(f.service.list("acme", Some("nope"), All).unsafeRunSync()) shouldBe
      (StatusCode.NotFound, "not_found")
    f.service.list("globex", None, All).unsafeRunSync() shouldBe Right(Nil)
  }

  "delete" should "drop by the current name and leave a tombstone" in {
    val f  = new Fixture
    val ix = f.create().toOption.get
    f.service.delete("acme", "lake", ByTable("pages")).unsafeRunSync().map(_.indexId) shouldBe
      Right(ix.indexId)
    f.store.listAllSearchIndexes() shouldBe Nil
    f.store.listSearchIndexTombstones().map(_.indexId) shouldBe List(ix.indexId)
    code(f.service.delete("acme", "lake", ByTable("pages")).unsafeRunSync()) shouldBe
      (StatusCode.NotFound, "not_found")
  }

  it should "drop the index of a dropped table by its name at create" in {
    val f  = new Fixture
    val ix = f.create().toOption.get
    f.catalog.tables.remove(("main", "pages"))
    f.service.delete("acme", "lake", ByTable("main.pages")).unsafeRunSync().map(_.indexId) shouldBe
      Right(ix.indexId)
  }

  it should "drop by index id, an old disabled index under a recreated name included" in {
    val f   = new Fixture
    val old = f.create().toOption.get
    // pages dropped and created again: the name now resolves to the new table id.
    f.catalog.tables.put(("main", "pages"), (77L, List(("title", 1L, "varchar"))))
    code(f.service.delete("acme", "lake", ByTable("pages")).unsafeRunSync()) shouldBe
      (StatusCode.NotFound, "not_found")
    f.service
      .delete("acme", "lake", ByIndexId(old.indexId.toString))
      .unsafeRunSync()
      .map(_.indexId) shouldBe
      Right(old.indexId)
    f.store.listSearchIndexTombstones().map(_.indexId) shouldBe List(old.indexId)
  }

  it should "answer an index id of another database, or a malformed one, as no index" in {
    val f  = new Fixture
    val ix = f.create().toOption.get
    List(
      ("plain", ix.indexId.toString),
      ("lake", s" ${ix.indexId} "),
      ("lake", "nope"),
      ("lake", java.util.UUID.randomUUID().toString)
    ).foreach { (db, id) =>
      withClue(s"$db $id")(
        code(f.service.delete("acme", db, ByIndexId(id)).unsafeRunSync()) shouldBe
          (StatusCode.NotFound, "not_found")
      )
    }
    f.store.findSearchIndexById(ix.indexId) shouldBe Some(ix)
  }

  "list" should "leave out the databases `visible` does not admit" in {
    val f = new Fixture
    f.create().toOption.get
    f.create(database = "plain", table = "notes", columns = List("txt")).toOption.get
    f.service
      .list("acme", None, _.name == "plain")
      .unsafeRunSync()
      .map(_.map(_.tenantDb.name)) shouldBe
      Right(List("plain"))
  }

  "create" should "store the catalog's spelling of the names, whatever the request's" in {
    val f  = new Fixture
    val ix = f.create(table = "MAIN.Pages").toOption.get
    (ix.schemaName, ix.tableName) shouldBe ("main", "pages")
    f.service
      .buildTarget("acme", "lake", "Main.PAGES")
      .unsafeRunSync()
      .map(t => (t.schema, t.table)) shouldBe
      Right(("main", "pages"))
  }

  "buildTarget" should "name the fields under their current names, by field id" in {
    val f = new Fixture
    f.create(columns = List("body", "title")).toOption.get
    f.catalog.tables.put(
      ("main", "pages"),
      (41L, List(("title2", 2L, "varchar"), ("Body", 3L, "varchar")))
    )
    f.service.buildTarget("acme", "lake", "pages").unsafeRunSync().map(_.columns) shouldBe
      Right(List("Body", "title2"))
    f.catalog.tables.put(("main", "pages"), (41L, List(("Body", 3L, "varchar"))))
    code(f.service.buildTarget("acme", "lake", "pages").unsafeRunSync()) shouldBe
      (StatusCode.Conflict, "search_column_dropped")
    code(f.service.buildTarget("acme", "secret", "pages").unsafeRunSync()) shouldBe
      (StatusCode.BadRequest, "search_encrypted_unsupported")
    code(f.service.buildTarget("acme", "lake", "docs.notes").unsafeRunSync()) shouldBe
      (StatusCode.NotFound, "not_found")
  }

  "the bundle format version" should "be the library's" in {
    val lib = scala.io.Source.fromFile("native/qod-search/src/lib.rs")
    try
      val Pin = """pub const QS_BUNDLE_FORMAT_VERSION: u32 = (\d+);""".r.unanchored
      lib.mkString match
        case Pin(v) => v.toInt shouldBe SearchIndex.BundleFormatVersion
        case _      => fail("QS_BUNDLE_FORMAT_VERSION not found in native/qod-search/src/lib.rs")
    finally lib.close()
  }

  "the interim build's statements" should "quote every name, name the fields in order, key by path" in {
    import ai.starlake.quack.ondemand.api.SearchHandlers.*
    val n   = Names("acme_lake", "main", "pages")
    val sql = buildSql(
      n,
      List("Body", "ti\"tle"),
      Plan(ListedFile("s3://l/it's.parquet", 10L, 2L), snapshots = false)
    )
    sql should include(""""Body" AS "t0", "ti""tle" AS "t1" FROM "acme_lake"."main"."pages"""")
    sql should include("WHERE filename = 's3://l/it''s.parquet'")
    sql should include("'acme_lake.main.pages', 's3://l/it''s.parquet', 10::UBIGINT, 2::UBIGINT")
    sql should include("-1::BIGINT AS snapshot_id")
    flushSql(n) shouldBe
      "CALL ducklake_flush_inlined_data('acme_lake', schema_name => 'main', table_name => 'pages')"
    pathKeyOf("abc") shouldBe "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
  }
