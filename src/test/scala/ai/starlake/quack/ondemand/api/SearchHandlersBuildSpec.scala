package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.{QueryResult, RouterFailure}
import ai.starlake.quack.model.{PoolKey, SearchIndex, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.auth.{PatPrincipal, SessionScope, TokenRestriction}
import ai.starlake.quack.ondemand.catalog.LiveTable
import ai.starlake.quack.ondemand.search.{SearchCatalog, SearchIndexService}
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RbacUser, SearchIndexStore}
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.{BigIntVector, FieldVector, VarCharVector, VectorSchemaRoot}
import org.apache.arrow.vector.ipc.{ArrowStreamReader, ArrowStreamWriter}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*

/** The interim build (D7) on a stub executor, ported from the Phase 0 prototype's spec: the
  * statements in order as the system caller, the refusals of a file the build cannot cover before
  * any bundle, the skipped files, and one segment row per built file with its path key, each moving
  * `segments_version` (review I2). The live run is Task 7's.
  */
class SearchHandlersBuildSpec extends AnyFlatSpec with Matchers:

  private val Key    = PoolKey("acme", "acme_lake", "bi")
  private val Affine = "/data/acme_lake/main/pages/ducklake-a.parquet"
  private val Merged = "/data/acme_lake/main/pages/ducklake-b.parquet"
  private val Dead   = "/data/acme_lake/main/pages/ducklake-c.parquet"

  private val Superuser: SessionScope.Resolver   = _ => SessionScope.Superuser
  private val TenantAdmin: SessionScope.Resolver =
    _ => SessionScope(superuser = false, manageableTenants = Set("acme"))

  private type Cell = Either[String, Long]

  private def reader(columns: List[String], rows: List[List[Cell]]): ArrowStreamReader =
    val allocator                  = new RootAllocator()
    val vectors: List[FieldVector] = columns.zipWithIndex.map { (name, i) =>
      val isText = rows.headOption.forall(_(i).isLeft)
      if isText then new VarCharVector(name, allocator) else new BigIntVector(name, allocator)
    }
    vectors.foreach(_.allocateNew())
    for (row, r) <- rows.zipWithIndex; (cell, c) <- row.zipWithIndex do
      (vectors(c), cell) match
        case (v: VarCharVector, Left(s)) => v.setSafe(r, s.getBytes(StandardCharsets.UTF_8))
        case (v: BigIntVector, Right(n)) => v.setSafe(r, n)
        case _                           => fail("a canned cell of the wrong type")
    vectors.foreach(_.setValueCount(rows.size))
    val root = new VectorSchemaRoot(vectors.asJava)
    root.setRowCount(rows.size)
    val out    = new ByteArrayOutputStream()
    val writer = new ArrowStreamWriter(root, null, out)
    writer.start()
    writer.writeBatch()
    writer.end()
    writer.close()
    root.close()
    new ArrowStreamReader(new ByteArrayInputStream(out.toByteArray), allocator)

  private def s(v: String): Cell = Left(v)
  private def n(v: Long): Cell   = Right(v)

  /** `main.pages` (table id 41): `title` (field 2) renamed `headline` since the index was made, and
    * `body` (field 3).
    */
  private val catalog: SearchCatalog = new SearchCatalog:
    def liveTableColumns(td: TenantDb, schema: String, table: String) =
      IO.pure(
        Option.when(schema == "main" && table == "pages")(
          LiveTable(
            41L,
            "main",
            "pages",
            List(("headline", 2L, "varchar"), ("body", 3L, "varchar"))
          )
        )
      )
    def liveTableName(td: TenantDb, tableId: Long) = IO.pure(Some(("main", "pages")))

  /** Canned answers: the affine file (offset 0, 100 rows, all live, no internal columns), the
    * merged file (non-affine, internal row id and snapshot columns, 50 rows all live) and a listed
    * file with no live row.
    */
  final class Stub(
      live: List[List[Cell]] = List(
        List(s(Affine), n(0), n(0), n(100), n(0), n(99)),
        List(s(Merged), n(-5), n(40), n(50), n(0), n(49))
      ),
      mergedSchema: List[String] = List(
        "duckdb_schema",
        "id",
        "body",
        "_ducklake_internal_row_id",
        "_ducklake_internal_snapshot_id"
      ),
      mergedRows: Long = 50
  ):
    val store = new InMemoryControlPlaneStore()
    val td    = TenantDb("td-lake", "acme", "acme_lake", TenantDbKind.DuckLake, Map.empty, "")
    val ix    = SearchIndex(
      java.util.UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100"),
      td.id,
      "main",
      "pages",
      41L,
      List(
        ai.starlake.quack.model.SearchIndexField("title", 2L),
        ai.starlake.quack.model.SearchIndexField("body", 3L)
      ),
      "/data/acme_lake/",
      SearchIndex.Ready,
      0L,
      "alice",
      java.time.Instant.EPOCH
    )
    store.insertSearchIndex(ix) shouldBe true
    val seen           = scala.collection.mutable.ListBuffer.empty[(ExecCaller, PoolKey, String)]
    val telemetryStore = new RecordingTelemetryStore
    val audit          = new AuditRecorder(telemetryStore, _ => None)
    val executor: CatalogPreviewHandlers.PreviewExecutor = (caller, key, sql) =>
      seen += ((caller, key, sql))
      val answer =
        if sql.startsWith("CALL ducklake_flush_inlined_data") then
          reader(
            List("schema_name", "table_name", "rows_flushed"),
            List(List(s("main"), s("pages"), n(0)))
          )
        else if sql.startsWith("SELECT data_file") then
          reader(
            List("data_file", "data_file_size_bytes", "data_file_footer_size"),
            List(
              List(s(Affine), n(1000), n(100)),
              List(s(Merged), n(2000), n(200)),
              List(s(Dead), n(3000), n(300))
            )
          )
        else if sql.startsWith("SELECT filename") then
          reader(List("filename", "lo", "hi", "c", "first", "last"), live)
        else if sql.contains("parquet_schema(" + SearchHandlers.lit(Affine)) then
          reader(
            List("name", "num_rows"),
            List("duckdb_schema", "id", "body").map(c => List(s(c), n(100)))
          )
        else if sql.contains("parquet_schema(" + SearchHandlers.lit(Merged)) then
          reader(List("name", "num_rows"), mergedSchema.map(c => List(s(c), n(mergedRows))))
        else if sql.contains("qod_search_build_segment") then
          val docs = if sql.contains(Affine) then 100L else 50L
          reader(
            List("path", "docs", "bytes"),
            List(List(s("bundle-" + docs), n(docs), n(docs * 10)))
          )
        else fail(s"unexpected statement: $sql")
      IO.pure(Right(QueryResult(answer, () => answer.close(), "node-1", 1L)))
    def handlersWith(exec: CatalogPreviewHandlers.PreviewExecutor) = new SearchHandlers(
      service = new SearchIndexService(
        tenantDbOf = (t, d) => Option.when(t == "acme" && d == td.name)(td),
        tenantDbsOf = _ => List(td),
        metastoreOf = _ => Map("dataPath" -> "/data/acme_lake/"),
        catalog = catalog,
        store = SearchIndexStore.over(store)
      ),
      store = SearchIndexStore.over(store),
      poolExists = _ == Key,
      aliasOf = _.name,
      executor = exec,
      audit = audit,
      callerOf = RestCaller(patOf =
        _ =>
          Some(
            PatPrincipal(
              RbacUser("u-root", None, "root", "admin"),
              "pat-1",
              SessionScope.Superuser,
              isAdmin = true,
              TokenRestriction.Unrestricted
            )
          )
      ),
      databaseRefusal = (_, _, _) => None
    )
    val handlers = handlersWith(executor)
    def call(
        scope: SessionScope.Resolver
    ): Either[(StatusCode, ErrorResponse), SearchBuildResponse] =
      handlers
        .build(SearchIndexBuildRequest("acme", "acme_lake", "pages", "bi"), Some("tok"))(scope)
        .unsafeRunSync()
    def builds    = seen.map(_._3).filter(_.contains("qod_search_build_segment")).toList
    def auditRows = telemetryStore.events.toList.filter(_.action == AuditActions.SearchIndexBuild)

  private val expectedFlush =
    "CALL ducklake_flush_inlined_data('acme_lake', schema_name => 'main', table_name => 'pages')"
  private val expectedList =
    "SELECT data_file, data_file_size_bytes, data_file_footer_size FROM ducklake_list_files(" +
      "'acme_lake', 'pages', schema => 'main')"
  private val expectedLive =
    "SELECT filename, min(rowid - file_row_number), max(rowid - file_row_number), count(*), " +
      "min(file_row_number), max(file_row_number) FROM \"acme_lake\".\"main\".\"pages\" GROUP BY " +
      "filename"
  private def expectedSchema(path: String) =
    s"SELECT s.name, m.num_rows FROM parquet_schema('$path') s, parquet_file_metadata('$path') m"
  private def expectedBuild(path: String, snapshot: String, size: Long, footer: Long) =
    "SELECT path, docs, bytes FROM qod_search_build_segment((SELECT file_row_number AS pos, rowid " +
      s"AS row_id, $snapshot AS snapshot_id, \"headline\" AS \"t0\", \"body\" AS \"t1\" FROM " +
      s"\"acme_lake\".\"main\".\"pages\" WHERE filename = '$path' ORDER BY pos), " +
      s"'acme_lake.main.pages', '$path', $size::UBIGINT, $footer::UBIGINT)"

  "build" should "refuse a tenant admin, running no statement" in {
    val stub = new Stub()
    stub.call(TenantAdmin).left.map(e => (e._1, e._2.error)) shouldBe
      Left((StatusCode.Forbidden, "superuser_required"))
    stub.seen shouldBe empty
    stub.auditRows.map(_.outcome) shouldBe List("denied")
  }

  it should "run the five statement kinds in order as the system caller, the fields by field id" in {
    val stub = new Stub()
    val resp = stub.call(Superuser).fold(e => fail(s"refused: $e"), identity)
    stub.seen.map(_._3).toList shouldBe List(
      expectedFlush,
      expectedList,
      expectedLive,
      expectedSchema(Affine),
      expectedSchema(Merged),
      expectedBuild(Affine, "-1::BIGINT", 1000, 100),
      expectedBuild(Merged, "snapshot_id", 2000, 200)
    )
    for (caller, key, _) <- stub.seen do
      caller.system shouldBe true
      // The call site flags its statements so the record route serves their system handles.
      caller.searchRecords shouldBe true
      caller.connectionId shouldBe "search-build-acme-acme_lake-bi"
      caller.identity shouldBe SearchHandlers.Identity
      key shouldBe Key
    resp.flushed shouldBe true
    resp.files.map(f => (f.path, f.bundle, f.docs, f.bytes)) shouldBe List(
      (Affine, "bundle-100", 100L, 1000L),
      (Merged, "bundle-50", 50L, 500L)
    )
    stub.auditRows.map(r => (r.actor, r.actorRealm, r.outcome, r.target)) shouldBe List(
      ("system", "system", "ok", Some("acme_lake/bi"))
    )
  }

  it should "record one segment row per built file, keyed by its path, each moving the version" in {
    val stub = new Stub()
    stub.call(Superuser).isRight shouldBe true
    stub.store
      .listSearchSegments(stub.ix.indexId)
      .map(g => (g.pathKey, g.path, g.docs, g.bytes, g.formatVersion))
      .toSet shouldBe Set(
      (SearchHandlers.pathKeyOf(Affine), Affine, 100L, 1000L, SearchIndex.BundleFormatVersion),
      (SearchHandlers.pathKeyOf(Merged), Merged, 50L, 500L, SearchIndex.BundleFormatVersion)
    )
    stub.store.findSearchIndexById(stub.ix.indexId).map(_.segmentsVersion) shouldBe Some(2L)
  }

  it should "refuse a non-affine file without _ducklake_internal_row_id, before any build" in {
    val stub = new Stub(mergedSchema = List("duckdb_schema", "id", "body"))
    stub.call(Superuser).left.map(e => (e._1, e._2.error)) shouldBe
      Left((StatusCode.Conflict, "search_build_nonaffine"))
    stub.builds shouldBe empty
    stub.store.listSearchSegments(stub.ix.indexId) shouldBe Nil
  }

  it should "refuse a file holding deleted rows, before any build, the affine one included" in {
    // 50 rows in the file, 49 live: the table scan's positions would skip the deleted one.
    val stub = new Stub(
      live = List(
        List(s(Affine), n(0), n(0), n(100), n(0), n(99)),
        List(s(Merged), n(-5), n(40), n(49), n(0), n(49))
      )
    )
    stub.call(Superuser).left.map(e => (e._1, e._2.error)) shouldBe
      Left((StatusCode.Conflict, "search_build_deleted_rows"))
    stub.builds shouldBe empty
    stub.store.findSearchIndexById(stub.ix.indexId).map(_.segmentsVersion) shouldBe Some(0L)
  }

  it should "skip a listed file with no live row" in {
    val stub = new Stub()
    stub.call(Superuser).fold(e => fail(s"refused: $e"), identity).skipped shouldBe List(Dead)
    stub.seen.exists(_._3.contains(Dead)) shouldBe false
  }

  it should "answer an executor's refusal with no bundle and no segment row" in {
    val stub     = new Stub()
    val refusing =
      stub.handlersWith((_, _, _) => IO.pure(Left(RouterFailure.AccessDenied("access denied: no"))))
    refusing
      .build(SearchIndexBuildRequest("acme", "acme_lake", "pages", "bi"), Some("tok"))(Superuser)
      .unsafeRunSync()
      .left
      .map(e => (e._1, e._2.error)) shouldBe Left((StatusCode.Forbidden, "search_build_denied"))
    stub.store.listSearchSegments(stub.ix.indexId) shouldBe Nil
  }

  it should "answer an unknown pool and a disabled index before any statement" in {
    val stub = new Stub()
    stub.handlers
      .build(SearchIndexBuildRequest("acme", "acme_lake", "pages", "nope"), Some("tok"))(Superuser)
      .unsafeRunSync()
      .left
      .map(_._2.error) shouldBe Left("pool_not_found")
    stub.store.setSearchIndexState(stub.ix.indexId, SearchIndex.Disabled)
    stub.call(Superuser).left.map(_._2.error) shouldBe Left("search_index_disabled")
    stub.seen shouldBe empty
  }
