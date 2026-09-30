package ai.starlake.quack.ondemand.api

import ai.starlake.quack.CatalogConfig
import ai.starlake.quack.edge.{QueryResult, RouterFailure}
import ai.starlake.quack.edge.adapter.{NodeLoadTracker, QuackError, QuackResponse}
import ai.starlake.quack.model.{
  FederatedSource,
  FederatedSourceType,
  PoolKey,
  Role,
  RoleDistribution,
  RunningNode,
  Tenant,
  TenantDbKind
}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.{SessionScope, TokenRestriction}
import ai.starlake.quack.ondemand.catalog.iceberg.NodeMetadataQuery
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax.*
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.{BigIntVector, BitVector, VarCharVector, VectorSchemaRoot}
import org.apache.arrow.vector.ipc.{ArrowReader, ArrowStreamReader, ArrowStreamWriter}
import org.apache.arrow.vector.types.pojo.{ArrowType, Field, Schema}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.time.Instant
import scala.collection.mutable.ListBuffer

class IcebergCatalogHandlersSpec extends AnyFlatSpec with Matchers:
  import Dtos.given

  private val NoKey: Option[String]                   = None
  private val NoScope: String => Option[SessionScope] = _ => None

  /** The gate's scope for a token caller: an admin of tenant `acme`. */
  private val AcmeAdmin: String => Option[SessionScope] =
    _ => Some(SessionScope(superuser = false, manageableTenants = Set("acme")))

  // ---- Arrow fixtures -------------------------------------------------------------------------

  private enum K:
    case S, L, B

  /** A real Arrow IPC stream with `cols`; a `None` cell is NULL. */
  private def arrow(cols: List[(String, K)], rows: List[List[Option[Any]]]): ArrowReader =
    val allocator = new RootAllocator()
    val fields    = cols.map {
      case (n, K.S) => Field.nullable(n, new ArrowType.Utf8())
      case (n, K.L) => Field.nullable(n, new ArrowType.Int(64, true))
      case (n, K.B) => Field.nullable(n, new ArrowType.Bool())
    }
    val schema = new Schema(java.util.List.of(fields*))
    val root   = VectorSchemaRoot.create(schema, allocator)
    val out    = new ByteArrayOutputStream()
    val writer = new ArrowStreamWriter(root, null, out)
    writer.start()
    root.allocateNew()
    rows.zipWithIndex.foreach { (row, i) =>
      cols.zip(row).foreach { case ((name, kind), cell) =>
        (kind, cell) match
          case (K.S, Some(v)) =>
            root
              .getVector(name)
              .asInstanceOf[VarCharVector]
              .setSafe(i, v.toString.getBytes("UTF-8"))
          case (K.S, None)    => root.getVector(name).asInstanceOf[VarCharVector].setNull(i)
          case (K.L, Some(v)) =>
            root.getVector(name).asInstanceOf[BigIntVector].setSafe(i, v.asInstanceOf[Long])
          case (K.L, None)    => root.getVector(name).asInstanceOf[BigIntVector].setNull(i)
          case (K.B, Some(v)) =>
            root
              .getVector(name)
              .asInstanceOf[BitVector]
              .setSafe(i, if v.asInstanceOf[Boolean] then 1 else 0)
          case (K.B, None) => root.getVector(name).asInstanceOf[BitVector].setNull(i)
      }
    }
    root.setRowCount(rows.length)
    writer.writeBatch()
    writer.end()
    writer.close()
    root.close()
    new ArrowStreamReader(new ByteArrayInputStream(out.toByteArray), allocator)

  // ---- canned catalog -------------------------------------------------------------------------

  private val S4 = "7557379181527318936"
  private val S3 = "4413216593749560206"
  private val S2 = "3745365227286907608"
  private val S1 = "7761858545720969174"

  private final case class Snap(
      id: String,
      parent: Option[String],
      seq: Long,
      ts: Long,
      summary: String,
      current: Boolean
  ):
    def operation: String = io.circe.parser
      .parse(summary)
      .toOption
      .flatMap(_.hcursor.get[String]("operation").toOption)
      .getOrElse("")

  // Verbatim Task 1 fixture summaries (DuckDB 1.5.6 probe, 2026-09-29), newest first.
  private val snaps = List(
    Snap(
      S4,
      Some(S3),
      4,
      1790709486188L,
      """{"operation":"delete","total-records":"5","total-data-files":"3","added-position-deletes":"1"}""",
      true
    ),
    Snap(
      S3,
      Some(S2),
      3,
      1790709486145L,
      """{"operation":"overwrite","total-records":"5","total-data-files":"3","added-records":"1","added-position-deletes":"1","added-data-files":"1","deleted-records":"0"}""",
      false
    ),
    Snap(
      S2,
      Some(S1),
      2,
      1790709486098L,
      """{"operation":"append","total-records":"4","total-data-files":"2","added-records":"1","added-data-files":"1"}""",
      false
    ),
    Snap(
      S1,
      None,
      1,
      1790709486022L,
      """{"operation":"append","total-records":"3","total-data-files":"1","added-records":"3","added-data-files":"1"}""",
      false
    )
  )

  private val snapCols =
    List(
      "snapshot_id"  -> K.S,
      "parent_id"    -> K.S,
      "seq"          -> K.L,
      "ts_ms"        -> K.L,
      "summary_json" -> K.S,
      "is_current"   -> K.B
    )

  private val InClause  = "snapshot_id IN \\(([^)]*)\\)".r.unanchored
  private val SeqLt     = "seq < (\\d+)".r.unanchored
  private val OpEq      = "->>'operation' = '([a-z]+)'".r.unanchored
  private val TsLe      = "ts_ms <= (\\d+)".r.unanchored
  private val IsCurrent = "WHERE is_current".r.unanchored
  private val LimitN    = "LIMIT (\\d+)$".r.unanchored

  private val poolKey = PoolKey("acme", "acme_tpch1", "bi")

  private val node = RunningNode(
    nodeId = "n1",
    poolKey = poolKey,
    role = Role.ReadOnly,
    host = "127.0.0.1",
    port = 21900,
    token = "tok",
    pid = None,
    podName = None,
    startedAt = Instant.EPOCH
  )

  private val cfg = CatalogConfig(previewMaxRows = 100, previewTimeoutSec = 30)

  private val V1Message =
    "table uses Iceberg format v1 (no snapshot sequence numbers); these views need format v2 or later"

  private def supervisor(withPool: Boolean = true): PoolSupervisor =
    val store = new InMemoryControlPlaneStore()
    val sup   =
      new PoolSupervisor(StubQuackBackend.noop(countingPorts = true), new NodeLoadTracker, store)
    sup.createTenant(Tenant(id = "acme", displayName = "acme", authProvider = "db")).unsafeRunSync()
    val meta = Map(
      "pgHost"     -> "127.0.0.1",
      "pgPort"     -> "0",
      "pgUser"     -> "u",
      "pgPassword" -> "p",
      "dbName"     -> "ignored",
      "schemaName" -> "main"
    )
    sup
      .createTenantDb("acme", "tpch1", TenantDbKind.DuckLake, meta, "/tmp/qod-iceberg-test")
      .unsafeRunSync()
    sup
      .createTenantDb("acme", "other", TenantDbKind.DuckLake, meta, "/tmp/qod-iceberg-test2")
      .unsafeRunSync()
    if withPool then sup.createPool(poolKey, RoleDistribution(1, 0, 0)).unsafeRunSync()
    sup

  trait Stubs:
    def withPool: Boolean   = true
    val sup: PoolSupervisor = supervisor(withPool)
    val telemetryStore      = new RecordingTelemetryStore
    val audit               = new AuditRecorder(telemetryStore, _ => None)

    private val tdMain  = sup.findTenantDb("acme", "acme_tpch1").get.id
    private val tdOther = sup.findTenantDb("acme", "acme_other").get.id

    val sources: Map[String, List[FederatedSource]] = Map(
      tdMain -> List(
        FederatedSource("s1", tdMain, "Ice", sourceType = FederatedSourceType.IcebergRest),
        FederatedSource("s2", tdMain, "pg", setupSql = "ATTACH 'x' AS pg"),
        FederatedSource(
          "s3",
          tdMain,
          "old",
          disabled = true,
          sourceType = FederatedSourceType.IcebergRest
        )
      ),
      tdOther -> List(
        FederatedSource("s4", tdOther, "ice2", sourceType = FederatedSourceType.IcebergRest)
      )
    )

    // metadata-node stub state
    val metaSqls                        = ListBuffer.empty[String]
    var metaPool: Option[PoolKey]       = Some(poolKey)
    var metaNodes: List[RunningNode]    = List(node)
    var attached                        = true
    var attachSummary: Option[String]   = None
    var metaFailure: Option[QuackError] = None
    var formatV1                        = false
    var malformed                       = false
    // The id the stub reports as `is_current`; defaults to the fixture's newest snapshot (S4),
    // same as its baked-in `current` flags. Override to put a snapshot other than the
    // highest-sequence one behind `current-snapshot-id`, as a rollback or a WAP snapshot would.
    var currentId                            = S4
    var columnsRows: List[List[Option[Any]]] = List(
      List(Some("id"), Some("BIGINT"), Some("YES")),
      List(Some("v"), Some("VARCHAR"), Some("NO"))
    )
    var filesRows: List[List[Option[Any]]] = List(
      List(Some("s3://w/a.parquet"), Some("DATA"), Some("PARQUET"), Some(3L), Some(1L)),
      List(Some("s3://w/d.parquet"), Some("POSITION_DELETES"), Some("PARQUET"), Some(1L), Some(4L))
    )

    private def snapshotResponse(sql: String): ArrowReader =
      var rows = snaps
      sql match
        case InClause(ids) =>
          val wanted = "'(-?\\d+)'".r.findAllMatchIn(ids).map(_.group(1)).toSet
          rows = rows.filter(s => wanted(s.id))
        case _ => ()
      sql match
        case SeqLt(n) => rows = rows.filter(_.seq < n.toLong)
        case _        => ()
      sql match
        case OpEq(op) => rows = rows.filter(_.operation == op)
        case _        => ()
      sql match
        case TsLe(n) => rows = rows.filter(_.ts <= n.toLong)
        case _       => ()
      sql match
        case IsCurrent() => rows = rows.filter(_.id == currentId)
        case _           => ()
      sql match
        case LimitN(n) => rows = rows.take(n.toInt)
        case _         => ()
      arrow(
        snapCols,
        rows.map(s =>
          List(
            Some(s.id),
            s.parent,
            if formatV1 then None else Some(s.seq),
            Some(s.ts),
            Some(if malformed then "not json" else s.summary),
            Some(s.id == currentId)
          )
        )
      )

    private def respond(sql: String): ArrowReader =
      if sql.contains("iceberg_load_table_response") then snapshotResponse(sql)
      else if sql.contains("iceberg_metadata") then
        arrow(
          List(
            "file_path"                -> K.S,
            "content"                  -> K.S,
            "file_format"              -> K.S,
            "record_count"             -> K.L,
            "manifest_sequence_number" -> K.L
          ),
          filesRows
        )
      else if sql.contains("DESCRIBE") then
        arrow(List("column_name" -> K.S, "column_type" -> K.S, "null" -> K.S), columnsRows)
      else if sql.contains("duckdb_schemas") then
        arrow(List("schema_name" -> K.S), List(List(Some("probe")), List(Some("sales"))))
      else if sql.contains("duckdb_tables") then
        arrow(List("table_name" -> K.S), List(List(Some("t")), List(Some("u"))))
      else sys.error(s"unexpected metadata sql: $sql")

    def meta: NodeMetadataQuery = new NodeMetadataQuery(
      readPool = (_, _) => metaPool,
      readNodes = _ => metaNodes,
      isAttached = (_, _) => attached,
      attachSummary = (_, _) => attachSummary,
      send = (_, sql) =>
        IO {
          metaSqls += sql
          metaFailure match
            case Some(e) => QuackResponse.Failed(e, 0L)
            case None    => QuackResponse.Ok(respond(sql), 0L, () => ())
        },
      timeoutSec = 5,
      maxRows = 10000
    )

    // routed-executor stub state
    val execSqls                                             = ListBuffer.empty[String]
    val execCallers                                          = ListBuffer.empty[ExecCaller]
    var execResult: () => Either[RouterFailure, QueryResult] = () =>
      Right(
        QueryResult(
          arrow(List("id" -> K.L), List(List(Some(1L)))),
          () => (),
          "node-1",
          5L
        )
      )
    val executor: CatalogPreviewHandlers.PreviewExecutor = (caller, _, sql) =>
      IO {
        execCallers += caller
        execSqls += sql
        execResult()
      }

    def diffResult(): Either[RouterFailure, QueryResult] =
      Right(
        QueryResult(
          arrow(
            List("__qod_change" -> K.S, "id" -> K.L, "v" -> K.S),
            List(
              List(Some("removed"), Some(2L), Some("b")),
              List(Some("added"), Some(2L), Some("B"))
            )
          ),
          () => (),
          "node-1",
          5L
        )
      )

    def handlers(
        cfgOverride: CatalogConfig = cfg,
        callerOf: RestCaller = RestCaller(
          Some(IdentityFixtures.StaticKey),
          IdentityFixtures.sessionOf,
          IdentityFixtures.patOf
        )
    ): IcebergCatalogHandlers =
      new IcebergCatalogHandlers(
        sup,
        id => sources.getOrElse(id, Nil),
        meta,
        executor,
        callerOf,
        cfgOverride,
        audit
      )

    def history(
        alias: String = "Ice",
        limit: Option[Int] = None,
        before: Option[String] = None,
        operation: Option[String] = None,
        h: IcebergCatalogHandlers = handlers()
    ) =
      h.history("acme", "acme_tpch1", alias, "probe", "t", limit, before, operation, NoKey)(NoScope)
        .unsafeRunSync()

    def preview(
        asOf: Option[String] = None,
        asOfTag: Option[String] = None,
        asOfTs: Option[Instant] = None,
        limit: Option[Int] = None,
        h: IcebergCatalogHandlers = handlers(),
        apiKey: Option[String] = NoKey
    ) =
      h.preview("acme", "acme_tpch1", "Ice", "probe", "t", asOf, asOfTag, asOfTs, limit, apiKey)(
        AcmeAdmin
      ).unsafeRunSync()

    def dataDiff(
        from: String = S1,
        to: String = S4,
        limit: Option[Int] = None,
        changeType: Option[String] = None,
        h: IcebergCatalogHandlers = handlers(),
        apiKey: Option[String] = NoKey
    ) =
      h.dataDiff("acme", "acme_tpch1", "Ice", "probe", "t", from, to, limit, changeType, apiKey)(
        AcmeAdmin
      ).unsafeRunSync()

  private def errOf[T](out: Either[(StatusCode, ErrorResponse), T]): (StatusCode, String) =
    out.left.toOption.map((s, e) => (s, e.error)).getOrElse(fail(s"expected an error, got $out"))

  // ---- alias resolution -----------------------------------------------------------------------

  "the alias check" should "404 an unknown, sql, disabled or foreign alias" in new Stubs:
    val h = handlers()
    for alias <- List("nope", "pg", "old", "ice2") do
      errOf(h.schemas("acme", "acme_tpch1", alias, NoKey)(NoScope).unsafeRunSync()) shouldBe
        (StatusCode.NotFound, "catalog_not_found")
    metaSqls shouldBe empty

  it should "resolve a differently cased alias and use the folded alias in SQL" in new Stubs:
    val out = handlers().schemas("acme", "acme_tpch1", "ICE", NoKey)(NoScope).unsafeRunSync()
    out.toOption.get shouldBe List(CatalogSchemaEntry("probe", -1), CatalogSchemaEntry("sales", -1))
    metaSqls.head should include("database_name = 'ice'")

  it should "403 a tenant-B admin on tenant A, like the DuckLake views" in new Stubs:
    val foreign: String => Option[SessionScope] =
      _ => Some(SessionScope(superuser = false, manageableTenants = Set("other")))
    val out = handlers()
      .history("acme", "acme_tpch1", "Ice", "probe", "t", None, None, None, Some("tok"))(foreign)
      .unsafeRunSync()
    errOf(out)._1 shouldBe StatusCode.Forbidden
    metaSqls shouldBe empty

  // ---- metadata failures ----------------------------------------------------------------------

  "a metadata failure" should "map NotAttached to 503 with the detail" in new Stubs:
    attached = false
    attachSummary = Some("failed on 1 of 1 nodes")
    val out = history()
    errOf(out) shouldBe (StatusCode.ServiceUnavailable, "catalog_unavailable")
    out.left.toOption.get._2.message should include("failed on 1 of 1 nodes")

  it should "map NoRunningNode to 409 pool_unavailable" in new Stubs:
    metaNodes = Nil
    errOf(history()) shouldBe (StatusCode.Conflict, "pool_unavailable")

  it should "map NoPool to 404 no_pool" in new Stubs:
    metaPool = None
    errOf(history()) shouldBe (StatusCode.NotFound, "no_pool")

  it should "map Remote to 502 catalog_error" in new Stubs:
    metaFailure = Some(QuackError.Permanent("boom"))
    errOf(history()) shouldBe (StatusCode.BadGateway, "catalog_error")

  it should "refuse a format-v1 table with 400 unsupported_for_iceberg" in new Stubs:
    formatV1 = true
    val out = history()
    errOf(out) shouldBe (StatusCode.BadRequest, "unsupported_for_iceberg")
    out.left.toOption.get._2.message shouldBe V1Message

  it should "map a malformed snapshot row to 502 catalog_error" in new Stubs:
    malformed = true
    errOf(history()) shouldBe (StatusCode.BadGateway, "catalog_error")

  // ---- schemas / tables / detail --------------------------------------------------------------

  "tables" should "list the schema's tables" in new Stubs:
    handlers()
      .tables("acme", "acme_tpch1", "Ice", "probe", NoKey)(NoScope)
      .unsafeRunSync() shouldBe Right(List("t", "u"))

  "detail" should "return columns, files and the current snapshot" in new Stubs:
    val out = handlers()
      .detail("acme", "acme_tpch1", "Ice", "probe", "t", NoKey)(NoScope)
      .unsafeRunSync()
      .toOption
      .get
    out.columns shouldBe List(
      CatalogColumnEntry(1, "id", "BIGINT", nullable = true, isPrimaryKey = false),
      CatalogColumnEntry(2, "v", "VARCHAR", nullable = false, isPrimaryKey = false)
    )
    out.files shouldBe List(
      IcebergFileEntry("s3://w/a.parquet", "DATA", "PARQUET", 3L, 1L),
      IcebergFileEntry("s3://w/d.parquet", "POSITION_DELETES", "PARQUET", 1L, 4L)
    )
    out.currentSnapshot shouldBe Some(S4)
    out.alias shouldBe "ice"
    metaSqls.exists(_.contains("WHERE is_current")) shouldBe true

  it should "report the current snapshot even when it is not the newest by sequence" in new Stubs:
    // A rollback or a staged/WAP snapshot on top leaves `current-snapshot-id` pointing at a
    // row that is not the highest-sequence one; the newest-by-seq row (S4) must NOT win.
    currentId = S2
    val out = handlers()
      .detail("acme", "acme_tpch1", "Ice", "probe", "t", NoKey)(NoScope)
      .unsafeRunSync()
      .toOption
      .get
    out.currentSnapshot shouldBe Some(S2)

  // ---- history --------------------------------------------------------------------------------

  "history" should "default to 50 and map every snapshot" in new Stubs:
    val out = history().toOption.get
    metaSqls.last should include("LIMIT 51")
    out.hasMore shouldBe false
    out.snapshots.map(_.snapshotId) shouldBe List(S4, S3, S2, S1)
    val head = out.snapshots.head
    head shouldBe IcebergSnapshotEntry(
      snapshotId = S4,
      parentId = Some(S3),
      sequenceNumber = 4,
      committedAt = Instant.ofEpochMilli(1790709486188L),
      operation = Some("delete"),
      addedRecords = None,
      deletedRecords = None,
      addedDataFiles = None,
      deletedDataFiles = None,
      addedPositionDeletes = Some(1L),
      totalDataFiles = Some(3L),
      current = true
    )

  it should "clamp the limit to 1..200" in new Stubs:
    history(limit = Some(500))
    metaSqls.last should include("LIMIT 201")
    history(limit = Some(0))
    metaSqls.last should include("LIMIT 2")

  it should "report hasMore when a further snapshot exists" in new Stubs:
    val out = history(limit = Some(2)).toOption.get
    out.snapshots.map(_.snapshotId) shouldBe List(S4, S3)
    out.hasMore shouldBe true

  it should "page before a snapshot by its sequence number" in new Stubs:
    val out = history(before = Some(S3)).toOption.get
    out.snapshots.map(_.snapshotId) shouldBe List(S2, S1)
    metaSqls.last should include("seq < 3")

  it should "filter on a known operation" in new Stubs:
    history(operation = Some("append")).toOption.get.snapshots.map(_.snapshotId) shouldBe
      List(S2, S1)

  it should "400 invalid_selector for an unknown operation or a non-numeric before" in new Stubs:
    errOf(history(operation = Some("bogus"))) shouldBe (StatusCode.BadRequest, "invalid_selector")
    errOf(history(before = Some("main"))) shouldBe (StatusCode.BadRequest, "invalid_selector")
    metaSqls shouldBe empty

  it should "422 invalid_snapshot for an unknown before" in new Stubs:
    errOf(history(before = Some("123"))) shouldBe
      (StatusCode.UnprocessableEntity, "invalid_snapshot")

  it should "serialize snapshot ids as JSON strings" in new Stubs:
    val json  = history().toOption.get.asJson
    val first = json.hcursor.downField("snapshots").downArray
    first.downField("snapshotId").focus.get.isString shouldBe true
    first.downField("parentId").focus.get.isString shouldBe true
    json.noSpaces should not include "total-records"
    json.noSpaces should not include "totalRecords"

  // ---- preview --------------------------------------------------------------------------------

  "preview" should "refuse a tag selector with 400 unsupported_for_iceberg" in new Stubs:
    errOf(preview(asOfTag = Some("v1"))) shouldBe (StatusCode.BadRequest, "unsupported_for_iceberg")

  it should "400 invalid_selector for two selectors or a non-numeric id" in new Stubs:
    errOf(preview(asOf = Some(S1), asOfTs = Some(Instant.EPOCH))) shouldBe
      (StatusCode.BadRequest, "invalid_selector")
    errOf(preview(asOf = Some("main"))) shouldBe (StatusCode.BadRequest, "invalid_selector")
    execSqls shouldBe empty

  it should "422 an unknown id or a too-early timestamp" in new Stubs:
    errOf(preview(asOf = Some("123"))) shouldBe
      (StatusCode.UnprocessableEntity, "invalid_snapshot")
    errOf(preview(asOfTs = Some(Instant.ofEpochMilli(1000L)))) shouldBe
      (StatusCode.UnprocessableEntity, "invalid_snapshot")
    execSqls shouldBe empty

  it should "run the data query through the executor at the chosen snapshot" in new Stubs:
    val out = preview(asOf = Some(S1), limit = Some(5)).toOption.get
    execSqls.toList shouldBe List(
      s"""SELECT * FROM "ice"."probe"."t" AT (VERSION => $S1) LIMIT 6"""
    )
    out.snapshotId shouldBe Some(S1)
    out.rows shouldBe List(List(Json.fromLong(1L)))
    out.truncated shouldBe false
    // metadata is only used to resolve the selector, never for the data query
    metaSqls.foreach(_ should include("iceberg_load_table_response"))
    metaSqls.exists(_.startsWith("SELECT * FROM")) shouldBe false

  it should "resolve a timestamp to the newest snapshot at or before it" in new Stubs:
    preview(asOfTs = Some(Instant.ofEpochMilli(1790709486100L))).toOption.get.snapshotId shouldBe
      Some(S2)
    execSqls.last should include(s"AT (VERSION => $S2)")

  it should "read the current version without touching the metadata node" in new Stubs:
    val out = preview().toOption.get
    out.snapshotId shouldBe None
    execSqls.last shouldBe s"""SELECT * FROM "ice"."probe"."t" LIMIT 101"""
    metaSqls shouldBe empty

  it should "map AccessDenied to 403 and other failures to 502" in new Stubs:
    execResult = () => Left(RouterFailure.AccessDenied("no grant"))
    errOf(preview()) shouldBe (StatusCode.Forbidden, "acl_denied")
    execResult = () => Left(RouterFailure.Unavailable("down"))
    errOf(preview()) shouldBe (StatusCode.BadGateway, "preview_failed")

  it should "report a non-timeout executor exception's own message, not 'query timed out'" in new Stubs:
    execResult = () => throw new RuntimeException("boom")
    val out = preview()
    errOf(out) shouldBe (StatusCode.BadGateway, "preview_failed")
    out.left.toOption.get._2.message shouldBe "boom"

  it should "fall back to a generic message when a non-timeout exception carries none" in new Stubs:
    execResult = () => throw new RuntimeException()
    val out = preview()
    errOf(out) shouldBe (StatusCode.BadGateway, "preview_failed")
    out.left.toOption.get._2.message shouldBe "preview query failed"

  it should "404 no_pool when the tenant-db has no pool" in new Stubs:
    override def withPool = false
    errOf(preview()) shouldBe (StatusCode.NotFound, "no_pool")
    execSqls shouldBe empty

  it should "audit CatalogPreviewRead ok with the alias-qualified target" in new Stubs:
    preview()
    val e = telemetryStore.events.last
    e.action shouldBe AuditActions.CatalogPreviewRead
    e.outcome shouldBe "ok"
    e.target shouldBe Some("acme_tpch1/ice.probe.t")
    e.detail.get("rowsReturned") shouldBe Some("1")

  // ---- data diff ------------------------------------------------------------------------------

  "dataDiff" should "400 invalid_selector for a bad id or change type" in new Stubs:
    errOf(dataDiff(from = "main")) shouldBe (StatusCode.BadRequest, "invalid_selector")
    errOf(dataDiff(changeType = Some("update"))) shouldBe (
      StatusCode.BadRequest,
      "invalid_selector"
    )
    execSqls shouldBe empty

  it should "422 invalid_snapshot when a bound is unknown" in new Stubs:
    errOf(dataDiff(to = "123")) shouldBe (StatusCode.UnprocessableEntity, "invalid_snapshot")

  it should "413 diff_too_large when a snapshot has more data files than the cap" in new Stubs:
    errOf(dataDiff(h = handlers(cfgOverride = cfg.copy(icebergDiffMaxFiles = 2)))) shouldBe
      (StatusCode.PayloadTooLarge, "diff_too_large")
    execSqls shouldBe empty

  it should "strip the change column and split rows into IcebergDiffRow" in new Stubs:
    execResult = () => diffResult()
    val out = dataDiff().toOption.get
    out.columns.map(_.name) shouldBe List("id", "v")
    out.rows shouldBe List(
      IcebergDiffRow("removed", List(Json.fromLong(2L), Json.fromString("b"))),
      IcebergDiffRow("added", List(Json.fromLong(2L), Json.fromString("B")))
    )
    out.from shouldBe S1
    out.to shouldBe S4
    execSqls.last should include("__qod_change")
    execSqls.last should include("LIMIT 101")
    val e = telemetryStore.events.last
    e.action shouldBe AuditActions.CatalogDataDiffRead
    e.outcome shouldBe "ok"

  // ---- executor identity (RestCaller, the PAT regression of #137) -----------------------------

  import IdentityFixtures.*

  private def callers(execCallers: ListBuffer[ExecCaller]) =
    execCallers.toList.map(c => (c.identity, c.restriction, c.patId, c.system))

  "preview's executor caller" should "be the system caller for the static key" in new Stubs:
    preview(apiKey = Some(StaticKey))
    callers(execCallers) shouldBe
      List((CatalogPreviewHandlers.SuperuserIdentity, TokenRestriction.Unrestricted, None, true))

  it should "be the session's user, unrestricted, for a session" in new Stubs:
    preview(apiKey = Some(SessionTok))
    callers(execCallers) shouldBe List(("alice", TokenRestriction.Unrestricted, None, false))

  it should "be the PAT's owner with its restriction and id, never system, for a PAT" in new Stubs:
    preview(apiKey = Some(PatTok))
    callers(execCallers) shouldBe List(("alice", PatRestriction, Some(PatId), false))

  it should "cap the fetch at the PAT's maxRows" in new Stubs:
    preview(apiKey = Some(PatTok), limit = Some(50))
    execSqls.last shouldBe s"""SELECT * FROM "ice"."probe"."t" LIMIT ${PatMaxRows + 1}"""

  it should "401 an unresolvable token without calling the executor" in new Stubs:
    errOf(preview(apiKey = Some("qod_pat_unknown"))) shouldBe
      (StatusCode.Unauthorized, "unauthorized")
    execCallers shouldBe empty
    telemetryStore.events.last.outcome shouldBe "denied"

  it should "run a tenant user NAMED 'superuser' as that user, not system" in new Stubs:
    val h = handlers(callerOf = RestCaller(None, sentinelSessionOf, sentinelPatOf))
    preview(h = h, apiKey = Some(SentinelSessionTok))
    preview(h = h, apiKey = Some(SentinelPatTok))
    execCallers.toList.map(c => (c.identity, c.system, c.patId)) shouldBe
      List(("superuser", false, None), ("superuser", false, Some("pat-sentinel")))

  "dataDiff's executor caller" should "be the system caller for the static key" in new Stubs:
    execResult = () => diffResult()
    dataDiff(apiKey = Some(StaticKey))
    callers(execCallers) shouldBe
      List((CatalogPreviewHandlers.SuperuserIdentity, TokenRestriction.Unrestricted, None, true))

  it should "be the session's user, unrestricted, for a session" in new Stubs:
    execResult = () => diffResult()
    dataDiff(apiKey = Some(SessionTok))
    callers(execCallers) shouldBe List(("alice", TokenRestriction.Unrestricted, None, false))

  it should "be the PAT's owner with its restriction and id, capped at its maxRows" in new Stubs:
    execResult = () => diffResult()
    dataDiff(apiKey = Some(PatTok), limit = Some(50))
    callers(execCallers) shouldBe List(("alice", PatRestriction, Some(PatId), false))
    execSqls.last should include(s"LIMIT ${PatMaxRows + 1}")

  it should "401 an unresolvable token without calling the executor" in new Stubs:
    errOf(dataDiff(apiKey = Some("qod_pat_unknown"))) shouldBe
      (StatusCode.Unauthorized, "unauthorized")
    execCallers shouldBe empty
    telemetryStore.events.last.outcome shouldBe "denied"
