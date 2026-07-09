package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{NodeSpec, RunningNode, Tenant, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.catalog.{
  DuckLakeCatalogReader,
  HistoryOperation,
  TableHistoryFilter,
  TableHistoryPage
}
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import ai.starlake.quack.ondemand.telemetry._
import cats.effect.IO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

import java.time.Instant

class CatalogHistoryHandlersSpec extends AnyFlatSpec with Matchers:

  private val NoKey: Option[String]              = None
  private val NoScope: String => Option[Nothing] = _ => None

  private def stubBackend: QuackBackend = new QuackBackend:
    def start(s: NodeSpec): IO[RunningNode] =
      IO.pure(
        RunningNode(
          s.nodeId,
          s.poolKey,
          s.role,
          "127.0.0.1",
          21000,
          "tok",
          Some(1L),
          None,
          Instant.EPOCH,
          maxConcurrent = s.maxConcurrent
        )
      )
    def stop(id: String)    = IO.unit
    def isAlive(id: String) = true
    def discoverExisting()  = IO.pure(Nil)
    def cleanup()           = IO.unit

  private def supervisor(): PoolSupervisor =
    val store = new InMemoryControlPlaneStore()
    store.upsertTenant(Tenant(id = "acme", displayName = "acme", authProvider = "db"))
    store.upsertTenantDb(
      TenantDb(
        id = "td-default01",
        tenantId = "acme",
        name = "acme_default",
        kind = TenantDbKind.InMemory,
        metastore = Map.empty,
        dataPath = ""
      )
    )
    val sup = new PoolSupervisor(stubBackend, new NodeLoadTracker, store)
    sup.restore()
    sup

  /** Records only audit events; everything else is the Noop behavior. */
  private final class RecordingTelemetryStore extends TelemetryStore:
    val events  = scala.collection.mutable.ListBuffer.empty[AuditEvent]
    val enabled = true
    def appendAudit(es: List[AuditEvent]): Unit                                      = events ++= es
    def listAudit(q: AuditQuery): List[AuditRow]                                     = Nil
    def purgeAudit(olderThan: Instant): Int                                          = 0
    def appendStatements(es: List[StatementEvent]): Unit                             = ()
    def searchStatements(q: StatementQuery): List[StatementRow]                      = Nil
    def purgeStatements(olderThan: Instant): Int                                     = 0
    def rollupWatermark(): Option[Instant]                                           = None
    def recomputeRollups(fromExclusive: Option[Instant], toInclusive: Instant): Unit = ()
    def advanceRollupWatermark(to: Instant): Unit                                    = ()
    def queryRollups(q: RollupQuery): List[RollupBucket]                             = Nil
    def purgeRollups(granularity: String, olderThan: Instant): Int                   = 0
    def queryUsage(q: UsageQuery): UsageResult = UsageResult(Nil, None)

  private val sampleCommit = CatalogHistoryCommit(
    snapshotId = 7L,
    committedAt = "2026-07-09T00:00:00Z",
    operation = HistoryOperation.Insert,
    author = Some("tenant:acme/user:alice"),
    commitMessage = None,
    schemaChanged = false,
    schemaVersion = 1L,
    rowsAdded = 5L,
    rowsRemoved = 0L,
    filesAdded = 1,
    filesRemoved = 0
  )

  private class StubReader(page: Option[TableHistoryPage]) extends DuckLakeCatalogReader(null):
    var seenFilter: Option[TableHistoryFilter] = None
    var seenLimit: Option[Int]                 = None
    var seenBefore: Option[Long]               = None
    override def listTableHistory(
        schema: String,
        table: String,
        filter: TableHistoryFilter = TableHistoryFilter(),
        limit: Int = 50,
        before: Option[Long] = None
    ): Option[TableHistoryPage] =
      seenFilter = Some(filter)
      seenLimit = Some(limit)
      seenBefore = before
      page

  private def handlers(
      reader: StubReader,
      auditReads: Boolean = false,
      store: RecordingTelemetryStore = new RecordingTelemetryStore
  ): CatalogHistoryHandlers =
    new CatalogHistoryHandlers(
      (_, _) => reader,
      supervisor(),
      audit = new AuditRecorder(store, _ => None),
      auditReads = auditReads
    )

  "history" should "return the reader page with table identity" in {
    val reader = new StubReader(Some(TableHistoryPage(42L, List(sampleCommit), hasMore = true)))
    val res    = handlers(reader)
      .history(
        "acme",
        "acme_default",
        "tpch1",
        "nation",
        None,
        None,
        None,
        None,
        None,
        None,
        NoKey
      )(
        NoScope
      )
    res match
      case Right(r) =>
        r.table shouldBe CatalogHistoryTableRef("tpch1", "nation", 42L)
        r.commits shouldBe List(sampleCommit)
        r.hasMore shouldBe true
        reader.seenLimit shouldBe Some(50) // default page size
      case Left(e) => fail(s"expected Right, got $e")
  }

  it should "clamp limit to [1, 200] and pass filters through" in {
    val reader = new StubReader(Some(TableHistoryPage(42L, Nil, hasMore = false)))
    val from   = Instant.parse("2026-07-01T00:00:00Z")
    val to     = Instant.parse("2026-07-09T00:00:00Z")
    handlers(reader).history(
      "acme",
      "acme_default",
      "tpch1",
      "nation",
      Some(9999),
      Some(77L),
      Some(from),
      Some(to),
      Some(HistoryOperation.Delete),
      Some("tenant:acme/user:alice"),
      NoKey
    )(NoScope)
    reader.seenLimit shouldBe Some(200)
    reader.seenBefore shouldBe Some(77L)
    reader.seenFilter shouldBe Some(
      TableHistoryFilter(
        Some(from),
        Some(to),
        Some(HistoryOperation.Delete),
        Some("tenant:acme/user:alice")
      )
    )
  }

  it should "400 invalid_filter on an unknown operation value" in {
    val reader = new StubReader(Some(TableHistoryPage(42L, Nil, hasMore = false)))
    val res    = handlers(reader)
      .history(
        "acme",
        "acme_default",
        "tpch1",
        "nation",
        None,
        None,
        None,
        None,
        Some("merge"),
        None,
        NoKey
      )(NoScope)
    res.left.toOption.map(e => (e._1, e._2.error)) shouldBe
      Some((StatusCode.BadRequest, "invalid_filter"))
  }

  it should "400 invalid_filter when from is after to" in {
    val reader = new StubReader(Some(TableHistoryPage(42L, Nil, hasMore = false)))
    val res    = handlers(reader).history(
      "acme",
      "acme_default",
      "tpch1",
      "nation",
      None,
      None,
      Some(Instant.parse("2026-07-09T00:00:00Z")),
      Some(Instant.parse("2026-07-01T00:00:00Z")),
      None,
      None,
      NoKey
    )(NoScope)
    res.left.toOption.map(e => (e._1, e._2.error)) shouldBe
      Some((StatusCode.BadRequest, "invalid_filter"))
  }

  it should "404 not_found on unknown tenant, tenant-db, and table without leaking" in {
    val known = new StubReader(Some(TableHistoryPage(42L, Nil, hasMore = false)))
    val h     = handlers(known)
    List(
      h.history(
        "nope",
        "acme_default",
        "tpch1",
        "nation",
        None,
        None,
        None,
        None,
        None,
        None,
        NoKey
      )(
        NoScope
      ),
      h.history("acme", "no_db", "tpch1", "nation", None, None, None, None, None, None, NoKey)(
        NoScope
      ),
      handlers(new StubReader(None))
        .history(
          "acme",
          "acme_default",
          "tpch1",
          "ghost",
          None,
          None,
          None,
          None,
          None,
          None,
          NoKey
        )(
          NoScope
        )
    ).foreach { res =>
      res.left.toOption.map(e => (e._1, e._2.error)) shouldBe
        Some((StatusCode.NotFound, "not_found"))
    }
  }

  it should "emit zero audit events with the knob off and exactly one ok event per read with it on" in {
    val reader = new StubReader(Some(TableHistoryPage(42L, List(sampleCommit), hasMore = false)))
    val off    = new RecordingTelemetryStore
    handlers(reader, auditReads = false, store = off)
      .history(
        "acme",
        "acme_default",
        "tpch1",
        "nation",
        None,
        None,
        None,
        None,
        None,
        None,
        NoKey
      )(
        NoScope
      )
    off.events shouldBe empty

    val on = new RecordingTelemetryStore
    handlers(reader, auditReads = true, store = on)
      .history(
        "acme",
        "acme_default",
        "tpch1",
        "nation",
        None,
        None,
        None,
        None,
        None,
        None,
        NoKey
      )(
        NoScope
      )
    val recorded = on.events.filter(_.action == AuditActions.CatalogHistoryRead)
    recorded should have size 1
    recorded.head.outcome shouldBe "ok"
    recorded.head.detail.get("table") shouldBe Some("tpch1.nation")
    recorded.head.detail.get("commits") shouldBe Some("1")
  }
