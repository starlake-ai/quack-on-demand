package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.{QueryResult, RouterFailure}
import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{PoolKey, RoleDistribution, Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
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

/** Search Phase 0 prototype (plan Task 12 Step 2): `POST /api/search/prototype/build-segments`. A
  * stub executor records `(caller, pool, sql)` and answers canned listings.
  */
class SearchPrototypeHandlersSpec extends AnyFlatSpec with Matchers:

  private val Key    = PoolKey("acme", "acme_lake", "bi")
  private val Alias  = "acme_lake"
  private val Root   = "/data/acme_lake/"
  private val Affine = "/data/acme_lake/main/pages/ducklake-a.parquet"
  private val Merged = "/data/acme_lake/main/pages/ducklake-b.parquet"
  private val Dead   = "/data/acme_lake/main/pages/ducklake-c.parquet"

  private val Superuser: SessionScope.Resolver   = _ => SessionScope.Superuser
  private val TenantAdmin: SessionScope.Resolver =
    _ => SessionScope(superuser = false, manageableTenants = Set("acme"))
  private val PlainUser: SessionScope.Resolver =
    _ => SessionScope(superuser = false, manageableTenants = Set.empty)

  /** A cell: a string (Left) or a BIGINT (Right). */
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
      mergedRows: Long = 50,
      root: String = Root
  ):
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
        else if sql.contains("parquet_schema(" + SearchPrototypeHandlers.lit(Affine)) then
          reader(
            List("name", "num_rows"),
            List("duckdb_schema", "id", "body").map(c => List(s(c), n(100)))
          )
        else if sql.contains("parquet_schema(" + SearchPrototypeHandlers.lit(Merged)) then
          reader(List("name", "num_rows"), mergedSchema.map(c => List(s(c), n(mergedRows))))
        else if sql.contains("qod_search_build_segment") then
          val docs = if sql.contains(Affine) then 100L else 50L
          reader(
            List("path", "docs", "bytes"),
            List(List(s("bundle-" + docs), n(docs), n(docs * 10)))
          )
        else fail(s"unexpected statement: $sql")
      IO.pure(Right(QueryResult(answer, () => answer.close(), "node-1", 1L)))
    val handlers = new SearchPrototypeHandlers(
      _ => Right(SearchPrototypeHandlers.Target(Alias, root)),
      executor,
      audit
    )
    def call(
        scope: SessionScope.Resolver,
        table: String = "pages",
        column: String = "body"
    ): Either[(StatusCode, ErrorResponse), SearchBuildResponse] =
      handlers
        .buildSegments(SearchBuildRequest("acme", "acme_lake", "bi", table, column), Some("tok"))(
          scope
        )
        .unsafeRunSync()
    def auditRows = telemetryStore.events.toList.filter(_.action == AuditActions.SearchBuild)

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
  private val expectedBuildAffine =
    "SELECT path, docs, bytes FROM qod_search_build_segment((SELECT file_row_number AS pos, rowid " +
      "AS row_id, -1::BIGINT AS snapshot_id, \"body\" AS text FROM \"acme_lake\".\"main\".\"pages\" " +
      s"WHERE filename = '$Affine' ORDER BY pos), 'acme_lake.main.pages', 'body', '$Root', " +
      s"'$Affine', 1000::UBIGINT, 100::UBIGINT)"
  private val expectedBuildMerged =
    "SELECT path, docs, bytes FROM qod_search_build_segment((SELECT file_row_number AS pos, rowid " +
      "AS row_id, snapshot_id AS snapshot_id, \"body\" AS text FROM \"acme_lake\".\"main\".\"pages\" " +
      s"WHERE filename = '$Merged' ORDER BY pos), 'acme_lake.main.pages', 'body', '$Root', " +
      s"'$Merged', 2000::UBIGINT, 200::UBIGINT)"

  "build-segments" should "refuse a tenant admin and a plain user, running no statement" in {
    for scope <- List(TenantAdmin, PlainUser) do
      val stub = new Stub()
      stub.call(scope) match
        case Left((status, err)) =>
          status shouldBe StatusCode.Forbidden
          err.error shouldBe "superuser_required"
        case Right(r) => fail(s"expected a refusal, got $r")
      stub.seen shouldBe empty
      stub.auditRows.map(_.outcome) shouldBe List("denied")
  }

  it should "run the five statement kinds in order as the system caller, for a superuser" in {
    val stub = new Stub()
    val resp = stub.call(Superuser).fold(e => fail(s"refused: $e"), identity)
    stub.seen.map(_._3).toList shouldBe List(
      expectedFlush,
      expectedList,
      expectedLive,
      expectedSchema(Affine),
      expectedSchema(Merged),
      expectedBuildAffine,
      expectedBuildMerged
    )
    for (caller, key, _) <- stub.seen do
      caller.system shouldBe true
      caller.connectionId shouldBe "search-build-acme-acme_lake-bi"
      caller.identity shouldBe SearchPrototypeHandlers.Identity
      caller.identity shouldBe "search-prototype-build"
      key shouldBe Key
    resp.flushed shouldBe true
    resp.files.map(f => (f.path, f.bundle, f.docs, f.bytes)) shouldBe List(
      (Affine, "bundle-100", 100L, 1000L),
      (Merged, "bundle-50", 50L, 500L)
    )
    resp.files.foreach(_.ms should be >= 0L)
    val rows = stub.auditRows
    rows.map(r => (r.actor, r.actorRealm, r.outcome, r.target)) shouldBe List(
      ("system", "system", "ok", Some("acme_lake/bi"))
    )
    rows.head.detail.get("table") shouldBe Some("pages")
    rows.head.detail.get("requestedBy") shouldBe Some("anonymous")
  }

  it should "refuse a table or column name that is not a plain identifier, running no statement" in {
    for (table, column) <- List(
        ("pa\"ges", "body"),
        ("pages;", "body"),
        ("pages", "bo dy"),
        ("pages", "body'--"),
        ("1pages", "body")
      )
    do
      val stub = new Stub()
      stub.call(Superuser, table, column) match
        case Left((status, err)) =>
          status shouldBe StatusCode.BadRequest
          err.error shouldBe "invalid_name"
        case Right(r) => fail(s"expected invalid_name, got $r")
      stub.seen shouldBe empty
  }

  it should "refuse a non-affine file without _ducklake_internal_row_id, before any build" in {
    val stub = new Stub(mergedSchema = List("duckdb_schema", "id", "body"))
    stub.call(Superuser) match
      case Left((status, err)) =>
        status shouldBe StatusCode.Conflict
        err.error shouldBe "search_build_nonaffine"
        err.message should include("pages")
      case Right(r) => fail(s"expected search_build_nonaffine, got $r")
    stub.seen.exists(_._3.contains("qod_search_build_segment")) shouldBe false
  }

  it should "refuse a file holding deleted rows, before any build" in {
    // 50 rows in the file, 49 live: the table scan's positions would skip the deleted one.
    val stub = new Stub(
      live = List(
        List(s(Affine), n(0), n(0), n(100), n(0), n(99)),
        List(s(Merged), n(-5), n(40), n(49), n(0), n(49))
      )
    )
    stub.call(Superuser) match
      case Left((status, err)) =>
        status shouldBe StatusCode.Conflict
        err.error shouldBe "search_build_deleted_rows"
      case Right(r) => fail(s"expected search_build_deleted_rows, got $r")
    stub.seen.exists(_._3.contains("qod_search_build_segment")) shouldBe false
  }

  it should "skip a listed file with no live row" in {
    val stub = new Stub()
    val resp = stub.call(Superuser).fold(e => fail(s"refused: $e"), identity)
    resp.skipped shouldBe List(Dead)
    stub.seen.exists(_._3.contains(Dead)) shouldBe false
  }

  it should "write the bundles under the resolver's bundle root" in {
    val stub = new Stub(root = "/elsewhere/root/")
    stub.call(Superuser).isRight shouldBe true
    val builds = stub.seen.map(_._3).filter(_.contains("qod_search_build_segment"))
    builds should have size 2
    builds.foreach(_ should include(", 'body', '/elsewhere/root/', "))
  }

  it should "answer an executor's refusal with no bundle" in {
    val stub     = new Stub()
    val refusing = new SearchPrototypeHandlers(
      _ => Right(SearchPrototypeHandlers.Target(Alias, Root)),
      (_, _, _) => IO.pure(Left(RouterFailure.AccessDenied("access denied: no"))),
      stub.audit
    )
    refusing
      .buildSegments(SearchBuildRequest("acme", "acme_lake", "bi", "pages", "body"), None)(
        Superuser
      )
      .unsafeRunSync() match
      case Left((status, err)) =>
        status shouldBe StatusCode.Forbidden
        err.error shouldBe "search_build_denied"
      case Right(r) => fail(s"expected a refusal, got $r")
  }

  "resolverOf" should "give the tenant-db's catalog alias and the decider's bundle root" in {
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(
      StubQuackBackend.noop(portBase = 23100, countingPorts = true),
      new NodeLoadTracker,
      store
    )
    sup.createTenant(Tenant(id = "acme", displayName = "acme", authProvider = "db")).unsafeRunSync()
    sup
      .createTenantDb(
        "acme",
        "lake",
        TenantDbKind.DuckLake,
        Map(
          "pgHost"     -> "127.0.0.1",
          "pgPort"     -> "0",
          "pgUser"     -> "u",
          "pgPassword" -> "p",
          "dbName"     -> "ignored",
          "schemaName" -> "main"
        ),
        "/tmp/qod-search-proto-test/",
        writerTrusted = true
      )
      .unsafeRunSync()
    val tdName = sup.findTenantDb("acme", "acme_lake").map(_.name).getOrElse(fail("no tenant-db"))
    sup.createPool(PoolKey("acme", tdName, "bi"), RoleDistribution(0, 0, 1)).unsafeRunSync()
    val resolve = SearchPrototypeHandlers.resolverOf(
      sup,
      ai.starlake.quack.boot.BootFactories.searchBundleRootOf(sup)
    )
    val target = resolve(PoolKey("acme", tdName, "bi")).fold(e => fail(s"refused: $e"), identity)
    target.bundleRoot shouldBe "/tmp/qod-search-proto-test/"
    target.catalogAlias shouldBe ai.starlake.quack.model.TenantDb.catalogAlias(
      sup.effectiveMetastoreFor("acme", tdName),
      tdName
    )
    resolve(PoolKey("acme", tdName, "nope")).left.map(_._2.error) shouldBe Left("pool_not_found")
    resolve(PoolKey("acme", "acme_nope", "bi")).left.map(_._2.error) shouldBe
      Left("tenant_db_not_found")
  }

  it should "refuse a database encrypted at rest, whose bundles would hold its text in the clear" in {
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(
      StubQuackBackend.noop(portBase = 23150, countingPorts = true),
      new NodeLoadTracker,
      store
    )
    sup.createTenant(Tenant(id = "acme", displayName = "acme", authProvider = "db")).unsafeRunSync()
    sup
      .createTenantDb(
        "acme",
        "sealed",
        TenantDbKind.DuckLake,
        Map(
          "pgHost"     -> "127.0.0.1",
          "pgPort"     -> "0",
          "pgUser"     -> "u",
          "pgPassword" -> "p",
          "dbName"     -> "ignored",
          "schemaName" -> "main"
        ),
        "/tmp/qod-search-proto-test-sealed/",
        encrypted = true,
        writerTrusted = true
      )
      .unsafeRunSync()
      .fold(e => fail(s"create refused: $e"), identity)
    val tdName = sup.findTenantDb("acme", "acme_sealed").map(_.name).getOrElse(fail("no tenant-db"))
    sup.findTenantDb("acme", tdName).map(_.encrypted) shouldBe Some(true)
    sup.createPool(PoolKey("acme", tdName, "bi"), RoleDistribution(0, 0, 1)).unsafeRunSync()
    val resolve = SearchPrototypeHandlers.resolverOf(
      sup,
      ai.starlake.quack.boot.BootFactories.searchBundleRootOf(sup)
    )
    resolve(PoolKey("acme", tdName, "bi")).left.map(r => (r._1, r._2.error)) shouldBe
      Left((StatusCode.BadRequest, "search_encrypted_unsupported"))
  }
