package ai.starlake.quack.ondemand.api

import ai.starlake.quack.CatalogConfig
import ai.starlake.quack.edge.{QueryResult, RouterFailure}
import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{PoolKey, RoleDistribution, Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.{PatPrincipal, SessionScope, TokenRestriction}
import ai.starlake.quack.ondemand.catalog.{DroppedTableEntry, DuckLakeCatalogReader}
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.{ArrowStreamReader, ArrowStreamWriter}
import org.apache.arrow.vector.types.pojo.Schema
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.concurrent.atomic.AtomicInteger

class CatalogUndropHandlersSpec extends AnyFlatSpec with Matchers:

  private val RootKey: Option[String]               = Some(IdentityFixtures.RootSessionTok)
  private val SuperuserScope: SessionScope.Resolver = _ => SessionScope.Superuser

  private def emptyReader(): org.apache.arrow.vector.ipc.ArrowReader =
    val allocator = new RootAllocator()
    val schema    = new Schema(java.util.List.of())
    val root      = VectorSchemaRoot.create(schema, allocator)
    val out       = new ByteArrayOutputStream()
    val writer    = new ArrowStreamWriter(root, null, out)
    writer.start()
    root.setRowCount(0)
    writer.writeBatch()
    writer.end()
    writer.close()
    root.close()
    new ArrowStreamReader(new ByteArrayInputStream(out.toByteArray), allocator)

  /** A write's result whose stream fails when read to its end: a stamped COMMIT refused there. */
  private def failingReader(message: String): org.apache.arrow.vector.ipc.ArrowReader =
    val under = emptyReader()
    new org.apache.arrow.vector.ipc.ArrowReader(new RootAllocator()):
      override def loadNextBatch(): Boolean          = throw new java.io.IOException(message)
      override def getVectorSchemaRoot               = under.getVectorSchemaRoot
      override def bytesRead(): Long                 = 0L
      override protected def readSchema(): Schema    = under.getVectorSchemaRoot.getSchema
      override protected def closeReadSource(): Unit = under.close()

  private def stubBackend: QuackBackend =
    StubQuackBackend.noop(portBase = 23000, countingPorts = true)

  private def supervisor(withPool: Boolean = true): (PoolSupervisor, InMemoryControlPlaneStore) =
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(stubBackend, new NodeLoadTracker, store)
    sup.createTenant(Tenant(id = "acme", displayName = "acme", authProvider = "db")).unsafeRunSync()
    sup
      .createTenantDb(
        "acme",
        "tpch1",
        TenantDbKind.DuckLake,
        Map(
          "pgHost"     -> "127.0.0.1",
          "pgPort"     -> "0",
          "pgUser"     -> "u",
          "pgPassword" -> "p",
          "dbName"     -> "ignored",
          "schemaName" -> "main"
        ),
        "/tmp/qod-undrop-test"
      )
      .unsafeRunSync()
    if withPool then
      sup.createPool(PoolKey("acme", "acme_tpch1", "bi"), RoleDistribution(0, 0, 1)).unsafeRunSync()
    (sup, store)

  /** Stub reader: `doomed` was dropped at 40 (last-live 39); snapshots 12, 39 and 42 exist, 42 is
    * max; `liveTables` gates the collision probe.
    */
  private def stubReader(
      dropped: List[DroppedTableEntry] = List(
        DroppedTableEntry("tpch1", "doomed", 40L, 39L, Some("2026-07-14T00:00:00Z"), true)
      ),
      liveTables: Set[(String, String)] = Set.empty,
      limitSink: AtomicInteger = new AtomicInteger(-1),
      tableExistsAtFn: Option[(String, String, Long) => Boolean] = None,
      // The snapshot a commit message names: none unless a case says this run's commit landed.
      ownCommit: String => Option[Long] = _ => None
  ): DuckLakeCatalogReader =
    new DuckLakeCatalogReader(null):
      override def snapshotExists(id: Long): Boolean = id == 12L || id == 39L || id == 42L
      override def maxSnapshotId(): Option[Long]     = Some(42L)
      override def listDroppedTables(limit: Int): List[DroppedTableEntry] =
        limitSink.set(limit)
        dropped.take(limit)
      override def findDroppedTable(schema: String, table: String): Option[DroppedTableEntry] =
        dropped.find(e => e.schema.equalsIgnoreCase(schema) && e.table.equalsIgnoreCase(table))
      override def findDroppedTableById(
          schema: String,
          table: String,
          tableId: Long
      ): Option[DroppedTableEntry] =
        dropped.find(e =>
          e.schema.equalsIgnoreCase(schema) && e.table.equalsIgnoreCase(table) &&
            e.tableId == tableId
        )
      override def tableExistsAt(schema: String, table: String, snapshotId: Long): Boolean =
        tableExistsAtFn match
          case Some(f) => f(schema, table, snapshotId)
          case None    => liveTables.contains((schema, table))
      override def nameHeldAt(schema: String, name: String, snapshotId: Long): Boolean =
        tableExistsAt(schema, name, snapshotId)
      override def snapshotByCommitMessage(commitMessage: String): Option[Long] =
        ownCommit(commitMessage)

  /** The commit message of an undrop of tpch1.doomed under its own name from snapshot 39. */
  private val DoomedMessage = "undrop tpch1.doomed as tpch1.doomed from snapshot 39 ["

  trait Stubs:
    val (sup, store)                  = supervisor()
    val telemetryStore                = new RecordingTelemetryStore
    val audit                         = new AuditRecorder(telemetryStore, _ => None)
    var seenSql: Option[String]       = None
    var seenPoolKey: Option[PoolKey]  = None
    var seenCallers: List[ExecCaller] = Nil
    var seenSession: Option[(String, String, ai.starlake.quack.ondemand.rbac.EffectiveSet)] = None
    var seenSessionMessage: Option[String]                                                  = None
    var closed                                                                              = false
    var executorResult: IO[Either[RouterFailure, QueryResult]]                              =
      IO.pure(Right(QueryResult(emptyReader(), () => closed = true, "node-1", 1L)))
    val executor: CatalogPreviewHandlers.PreviewExecutor =
      (caller, poolKey, sql) =>
        seenSql = Some(sql)
        seenPoolKey = Some(poolKey)
        seenCallers = seenCallers :+ caller
        executorResult

    def handlers(
        readerOverride: DuckLakeCatalogReader = stubReader(),
        cfgOverride: CatalogConfig = CatalogConfig(previewMaxRows = 100, previewTimeoutSec = 30),
        sessionsOverride: String => Option[SessionTokenStore.Session] = _ => None,
        patsOverride: String => Option[PatPrincipal] = _ => None
    ): CatalogUndropHandlers =
      new CatalogUndropHandlers(
        sup,
        executor,
        (_, _) => readerOverride,
        cfgOverride,
        IdentityFixtures.restCaller(sessionsOverride, patsOverride),
        audit = audit,
        sessionExecutor = (connectionId, user, poolKey, sql, eff, message) =>
          seenSql = Some(sql)
          seenSession = Some((connectionId, user, eff))
          seenSessionMessage = Some(message)
          executorResult
      )

    def undrop(
        h: CatalogUndropHandlers,
        tenant: String = "acme",
        tenantDb: String = "acme_tpch1",
        schema: String = "tpch1",
        table: String = "doomed",
        asName: Option[String] = None,
        fromSnapshot: Option[Long] = None,
        apiKey: Option[String] = RootKey,
        tableId: Option[Long] = None
    ) =
      h.undrop(
        UndropRequest(tenant, tenantDb, schema, table, asName, fromSnapshot, tableId),
        apiKey
      )(SuperuserScope)
        .unsafeRunSync()

  "UNDROP TABLE" should "run its CTAS as the session itself, its claims included (review I3)" in new Stubs:
    val eff = ai.starlake.quack.ondemand.rbac.EffectiveSet(
      ai.starlake.quack.ondemand.state.RbacUser("u-carol", Some("acme"), "carol", kind = "user"),
      Nil,
      Nil,
      Nil,
      Nil,
      claims = Map("contractor" -> "true")
    )
    val out = handlers()
      .undropStatement(
        "conn-7",
        "carol",
        PoolKey("acme", "acme_tpch1", "bi"),
        Some(eff),
        Some("TPCH1"),
        "DOOMED",
        Some("back")
      )
      .unsafeRunSync()
    out shouldBe a[Right[?, ?]]
    out.foreach(_.close())
    // The session's own effective set, never a re-resolved caller: its claims reach the node and OPA.
    seenSession.map(_._1) shouldBe Some("conn-7/undrop")
    seenSession.map(_._3.claims) shouldBe Some(Map("contractor" -> "true"))
    seenCallers shouldBe Nil
    seenSessionMessage.get should startWith("undrop tpch1.doomed as tpch1.back from snapshot 39 [")
    // Names compare without case (review M6): the stored names are what the CTAS names.
    seenSql shouldBe Some(
      """CREATE TABLE "tpch1"."back" AS SELECT * FROM "tpch1"."doomed" AT (VERSION => 39)"""
    )

  "recoverable" should "return the reader's dropped tables" in new Stubs:
    val out =
      handlers().recoverable("acme", "acme_tpch1", None, RootKey)(SuperuserScope).unsafeRunSync()
    val r = out.toOption.get
    r.tables.map(_.table) shouldBe List("doomed")
    r.tables.head.lastLiveSnapshot shouldBe 39L
    r.tables.head.recoverable shouldBe true

  it should "404 an unknown tenant and 403 a cross-tenant caller" in new Stubs:
    val h = handlers()
    h.recoverable("nope", "acme_tpch1", None, RootKey)(SuperuserScope)
      .unsafeRunSync()
      .left
      .toOption
      .get
      ._1 shouldBe StatusCode.NotFound
    val foreign: SessionScope.Resolver =
      _ => SessionScope(superuser = false, manageableTenants = Set("other"))
    h.recoverable("acme", "acme_tpch1", None, Some("tok"))(foreign)
      .unsafeRunSync()
      .left
      .toOption
      .get
      ._1 shouldBe StatusCode.Forbidden

  "undrop" should "recreate under the original name via a routed CTAS at the last-live snapshot" in new Stubs:
    val out = undrop(handlers())
    val r   = out.toOption.get
    r shouldBe UndropResponse("tpch1", "doomed", "doomed", 39L)
    seenSql shouldBe Some(
      """CREATE TABLE "tpch1"."doomed" AS SELECT * FROM "tpch1"."doomed" AT (VERSION => 39)"""
    )
    closed shouldBe true

  it should "not report success when the CTAS's commit fails while its result is read" in new Stubs:
    var shut = false
    executorResult = IO.pure(
      Right(QueryResult(failingReader("COMMIT failed: boom"), () => shut = true, "node-1", 1L))
    )
    val out = undrop(handlers())
    out.left.toOption.get._1 shouldBe StatusCode.BadGateway
    out.left.toOption.get._2.error shouldBe "undrop_failed"
    out.left.toOption.get._2.message should include("COMMIT failed: boom")
    telemetryStore.events.last.outcome should not be "ok"
    shut shouldBe true

  it should "report success when the result broke after the CTAS committed" in new Stubs:
    // The catalog shows this run's own commit: it landed, whatever the stream said after.
    var ran    = false
    val reader = stubReader(ownCommit = m => Option.when(ran && m.startsWith(DoomedMessage))(43L))
    executorResult = IO {
      ran = true
      Right(QueryResult(failingReader("connection reset"), () => (), "node-1", 1L))
    }
    undrop(handlers(readerOverride = reader)).isRight shouldBe true

  it should "not report success when only another writer's table holds the name" in new Stubs:
    // The answer broke; a table now holds tpch1.doomed, but no commit carries this run's message:
    // another writer created it (or another undrop did). Neither success nor plain failure.
    var ran    = false
    val reader = stubReader(tableExistsAtFn = Some((_, _, snapshot) => snapshot != 42L || ran))
    executorResult = IO {
      ran = true
      Right(QueryResult(failingReader("connection reset"), () => (), "node-1", 1L))
    }
    val out = undrop(handlers(readerOverride = reader))
    out.left.toOption.get._1 shouldBe StatusCode.BadGateway
    out.left.toOption.get._2.error shouldBe "undrop_outcome_unknown"
    out.left.toOption.get._2.message should include("tpch1.doomed")
    telemetryStore.events.last.outcome should not be "ok"

  it should "stamp each run's CTAS with a commit message unique to it" in new Stubs:
    val h = handlers()
    undrop(h).isRight shouldBe true
    undrop(h).isRight shouldBe true
    val messages = seenCallers.flatMap(_.commitMessage)
    messages.size shouldBe 2
    messages.foreach(_ should startWith(DoomedMessage))
    messages.distinct.size shouldBe 2

  it should "answer a failure as committed when the catalog shows this run's commit" in new Stubs:
    // The router could not tell (its own outcome-unknown answer); the catalog can.
    executorResult = IO.pure(
      Left(ai.starlake.quack.edge.FlightSqlRouter.outcomeUnknown("connection reset"))
    )
    val reader = stubReader(ownCommit = m => Option.when(m.startsWith(DoomedMessage))(43L))
    undrop(handlers(readerOverride = reader)).toOption.get shouldBe
      UndropResponse("tpch1", "doomed", "doomed", 39L)
    telemetryStore.events.last.outcome shouldBe "ok"

  it should "404 when no such dropped table exists" in new Stubs:
    val out = undrop(handlers(), table = "ghost")
    out.left.toOption.get._1 shouldBe StatusCode.NotFound
    out.left.toOption.get._2.error shouldBe "not_found"
    seenSql shouldBe None

  it should "410 snapshot_expired when the source snapshot is gone" in new Stubs:
    val h = handlers(
      readerOverride =
        stubReader(dropped = List(DroppedTableEntry("tpch1", "doomed", 14L, 13L, None, false)))
    )
    val out = undrop(h)
    out.left.toOption.get._1 shouldBe StatusCode.Gone
    out.left.toOption.get._2.error shouldBe "snapshot_expired"

  it should "409 name_conflict on a live target unless asName is supplied" in new Stubs:
    val h   = handlers(readerOverride = stubReader(liveTables = Set(("tpch1", "doomed"))))
    val out = undrop(h)
    out.left.toOption.get._1 shouldBe StatusCode.Conflict
    out.left.toOption.get._2.error shouldBe "name_conflict"
    val ok = undrop(h, asName = Some("doomed_restored"))
    ok.toOption.get.restoredAs shouldBe "doomed_restored"
    seenSql.get should include(""""tpch1"."doomed_restored" AS SELECT * FROM "tpch1"."doomed"""")

  // `doomed` dropped twice: table 7 (an older version, last live at 12), then table 9 (39).
  private val droppedTwice = List(
    DroppedTableEntry("tpch1", "doomed", 40L, 39L, None, true, tableId = 9L),
    DroppedTableEntry("tpch1", "doomed", 13L, 12L, None, true, tableId = 7L)
  )

  it should "recover the drop a table id names, at its own last-live snapshot" in new Stubs:
    val out = undrop(handlers(stubReader(dropped = droppedTwice)), tableId = Some(7L))
    out.toOption.get.fromSnapshot shouldBe 12L
    seenSql.get should include("AT (VERSION => 12)")

  it should "recover the newest drop of the name without a table id" in new Stubs:
    undrop(handlers(stubReader(dropped = droppedTwice))).toOption.get.fromSnapshot shouldBe 39L

  it should "404 a table id that names no drop of that table" in new Stubs:
    val out = undrop(handlers(stubReader(dropped = droppedTwice)), tableId = Some(8L))
    out.left.toOption.get._1 shouldBe StatusCode.NotFound
    seenSql shouldBe None

  it should "honor an explicit fromSnapshot when it exists" in new Stubs:
    val out = undrop(handlers(), fromSnapshot = Some(12L))
    out.toOption.get.fromSnapshot shouldBe 12L
    seenSql.get should include("AT (VERSION => 12)")

  it should "400 invalid_name on unsafe characters in schema, table, or asName" in new Stubs:
    val h = handlers()
    undrop(h, asName = Some("bad\"name")).left.toOption.get._2.error shouldBe "invalid_name"
    undrop(h, schema = "x;y").left.toOption.get._2.error shouldBe "invalid_name"
    undrop(h, table = "a b").left.toOption.get._2.error shouldBe "invalid_name"
    seenSql shouldBe None

  it should "map executor outcomes to acl_denied and undrop_failed" in new Stubs:
    executorResult = IO.pure(Left(RouterFailure.AccessDenied("no grant")))
    val h      = handlers()
    val denied = undrop(h)
    denied.left.toOption.get._1 shouldBe StatusCode.Forbidden
    denied.left.toOption.get._2.error shouldBe "acl_denied"
    executorResult = IO.pure(Left(RouterFailure.Internal("boom")))
    val failed = undrop(h)
    failed.left.toOption.get._1 shouldBe StatusCode.BadGateway
    failed.left.toOption.get._2.error shouldBe "undrop_failed"

  it should "audit ok with schema/table/restoredAs/fromSnapshot and denied on gate rejection" in new Stubs:
    val h = handlers()
    undrop(h)
    val ok = telemetryStore.events.last
    ok.action shouldBe AuditActions.CatalogUndrop
    ok.outcome shouldBe "ok"
    ok.detail.get("restoredAs") shouldBe Some("doomed")
    ok.detail.get("fromSnapshot") shouldBe Some("39")
    undrop(h, tenant = "nope")
    val denied = telemetryStore.events.last
    denied.action shouldBe AuditActions.CatalogUndrop
    denied.outcome shouldBe "denied"

  it should "422 invalid_snapshot when fromSnapshot is beyond the last-live snapshot" in new Stubs:
    val out = undrop(handlers(), fromSnapshot = Some(42L))
    out.left.toOption.get._1 shouldBe StatusCode.UnprocessableEntity
    out.left.toOption.get._2.error shouldBe "invalid_snapshot"
    seenSql shouldBe None

  it should "report success when the CTAS commits despite the timeout (post-timeout probe)" in new Stubs:
    executorResult = IO.never
    // The CTAS committed while the handler was waiting: the catalog holds this run's commit.
    val h = handlers(
      readerOverride = stubReader(ownCommit = m => Option.when(m.startsWith(DoomedMessage))(43L)),
      cfgOverride =
        CatalogConfig(previewMaxRows = 100, previewTimeoutSec = 30, undropTimeoutSec = 1)
    )
    val out = undrop(h)
    out.toOption.get shouldBe UndropResponse("tpch1", "doomed", "doomed", 39L)
    telemetryStore.events.last.outcome shouldBe "ok"

  it should "502 with a timeout message when the CTAS neither returns nor commits" in new Stubs:
    executorResult = IO.never
    val h = handlers(
      cfgOverride =
        CatalogConfig(previewMaxRows = 100, previewTimeoutSec = 30, undropTimeoutSec = 1)
    )
    val out = undrop(h)
    out.left.toOption.get._1 shouldBe StatusCode.BadGateway
    out.left.toOption.get._2.message should include("timed out after 1s")

  it should "prefer a pool with a write-eligible node over an earlier-sorted read-only pool" in new Stubs:
    sup.createPool(PoolKey("acme", "acme_tpch1", "aro"), RoleDistribution(0, 1, 0)).unsafeRunSync()
    undrop(handlers())
    seenPoolKey shouldBe Some(PoolKey("acme", "acme_tpch1", "bi"))

  "recoverable limit" should "clamp into [1, 200]" in new Stubs:
    val sink = new AtomicInteger(-1)
    val h    = handlers(readerOverride = stubReader(limitSink = sink))
    h.recoverable("acme", "acme_tpch1", Some(500), RootKey)(SuperuserScope).unsafeRunSync()
    sink.get shouldBe 200
    h.recoverable("acme", "acme_tpch1", Some(0), RootKey)(SuperuserScope).unsafeRunSync()
    sink.get shouldBe 1

  // ---------- executor identity (PAT regression) ----------

  "undrop's CTAS" should "run a superuser session as its user, never as a system caller" in new Stubs:
    undrop(handlers(), apiKey = Some(IdentityFixtures.RootSessionTok)).isRight shouldBe true
    seenCallers.map(c => (c.identity, c.restriction, c.patId, c.system)) shouldBe
      List((IdentityFixtures.RootUsername, TokenRestriction.Unrestricted, None, false))

  it should "run a session as its user, unrestricted" in new Stubs:
    val h = handlers(sessionsOverride = IdentityFixtures.sessionOf)
    undrop(h, apiKey = Some(IdentityFixtures.SessionTok)).isRight shouldBe true
    seenCallers.map(c => (c.identity, c.restriction, c.patId)) shouldBe
      List(("alice", TokenRestriction.Unrestricted, None))

  it should "run a PAT as its owner, with the PAT's restriction and id (never superuser)" in new Stubs:
    val h = handlers(patsOverride = IdentityFixtures.patOf)
    undrop(h, apiKey = Some(IdentityFixtures.PatTok)).isRight shouldBe true
    seenCallers.map(c => (c.identity, c.restriction, c.patId)) shouldBe
      List(("alice", IdentityFixtures.PatRestriction, Some(IdentityFixtures.PatId)))

  it should "401 an unresolvable token without calling the executor" in new Stubs:
    val h = handlers(
      sessionsOverride = IdentityFixtures.sessionOf,
      patsOverride = IdentityFixtures.patOf
    )
    val out = undrop(h, apiKey = Some("qod_pat_unknown"))
    out.left.toOption.map(e => (e._1, e._2.error)) shouldBe
      Some((StatusCode.Unauthorized, "unauthorized"))
    seenCallers shouldBe Nil

  it should "401 an absent credential without calling the executor" in new Stubs:
    val h = handlers(
      sessionsOverride = IdentityFixtures.sessionOf,
      patsOverride = IdentityFixtures.patOf
    )
    val out = undrop(h, apiKey = None)
    out.left.toOption.map(e => (e._1, e._2.error)) shouldBe
      Some((StatusCode.Unauthorized, "unauthorized"))
    seenCallers shouldBe Nil
