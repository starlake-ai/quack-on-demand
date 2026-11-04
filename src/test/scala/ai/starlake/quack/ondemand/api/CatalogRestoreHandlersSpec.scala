package ai.starlake.quack.ondemand.api

import ai.starlake.quack.CatalogConfig
import ai.starlake.quack.edge.{FlightSqlRouter, QueryResult, RouterFailure}
import ai.starlake.quack.edge.adapter.{NodeLoadTracker, QuackHttpClient, QuackWireError}
import ai.starlake.quack.edge.auth.AuthenticatedProfile
import ai.starlake.quack.model.{PoolKey, RoleDistribution, Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.{PatPrincipal, SessionScope, TokenRestriction}
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
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
import java.time.Instant

class CatalogRestoreHandlersSpec extends AnyFlatSpec with Matchers:

  private val RootKey: Option[String]               = Some(IdentityFixtures.RootSessionTok)
  private val SuperuserScope: SessionScope.Resolver = _ => SessionScope.Superuser

  /** A write's result whose stream fails with `error` when read to its end: a stamped COMMIT
    * failing there.
    */
  private def failingReaderWith(error: Throwable): org.apache.arrow.vector.ipc.ArrowReader =
    val under = emptyReader()
    new org.apache.arrow.vector.ipc.ArrowReader(new RootAllocator()):
      override def loadNextBatch(): Boolean          = throw error
      override def getVectorSchemaRoot               = under.getVectorSchemaRoot
      override def bytesRead(): Long                 = 0L
      override protected def readSchema(): Schema    = under.getVectorSchemaRoot.getSchema
      override protected def closeReadSource(): Unit = under.close()

  /** The COMMIT's answer is lost: a broken connection says nothing of whether it landed. */
  private def failingReader(message: String): org.apache.arrow.vector.ipc.ArrowReader =
    failingReaderWith(new java.io.IOException(message))

  /** The node answers the COMMIT with an error of its own (an ERROR_RESPONSE): refused, not landed.
    */
  private def refusedReader(message: String): org.apache.arrow.vector.ipc.ArrowReader =
    failingReaderWith(QuackWireError.Permanent(message))

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

  private def stubBackend: QuackBackend =
    StubQuackBackend.noop(portBase = 23100, countingPorts = true)

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
        "/tmp/qod-restore-test"
      )
      .unsafeRunSync()
    if withPool then
      sup.createPool(PoolKey("acme", "acme_tpch1", "bi"), RoleDistribution(0, 0, 1)).unsafeRunSync()
    (sup, store)

  /** Arrow batch with (change_type VARCHAR, n BIGINT) rows for the dry-run summary decode. */
  private def summaryReader(rows: List[(String, Long)]): org.apache.arrow.vector.ipc.ArrowReader =
    val allocator = new RootAllocator()
    val ctField   = new org.apache.arrow.vector.types.pojo.Field(
      "change_type",
      org.apache.arrow.vector.types.pojo.FieldType.nullable(
        org.apache.arrow.vector.types.pojo.ArrowType.Utf8.INSTANCE
      ),
      null
    )
    val nField = new org.apache.arrow.vector.types.pojo.Field(
      "n",
      org.apache.arrow.vector.types.pojo.FieldType.nullable(
        new org.apache.arrow.vector.types.pojo.ArrowType.Int(64, true)
      ),
      null
    )
    val schema = new Schema(java.util.List.of(ctField, nField))
    val root   = VectorSchemaRoot.create(schema, allocator)
    val ct     = root.getVector("change_type").asInstanceOf[org.apache.arrow.vector.VarCharVector]
    val nv     = root.getVector("n").asInstanceOf[org.apache.arrow.vector.BigIntVector]
    rows.zipWithIndex.foreach { case ((t, c), idx) =>
      ct.setSafe(idx, t.getBytes("UTF-8")); nv.setSafe(idx, c)
    }
    root.setRowCount(rows.length)
    val out    = new ByteArrayOutputStream()
    val writer = new ArrowStreamWriter(root, null, out)
    writer.start(); writer.writeBatch(); writer.end(); writer.close(); root.close()
    new ArrowStreamReader(new ByteArrayInputStream(out.toByteArray), allocator)

  /** Stub reader for restore: snapshots 12, 39, 42 exist, max 42; `live` maps live tables to their
    * (tableId, beginSnapshot); `latest` is the table's newest touching snapshot; `ownCommit` is the
    * catalog's answer for the snapshot a commit message names (none unless a case says so).
    */
  private def stubReader(
      live: Map[(String, String), (Long, Long)] = Map(("tpch1", "orders") -> (7L, 40L)),
      latest: Long = 40L,
      existsAt: (String, String, Long) => Boolean = (_, _, _) => true,
      ownCommit: String => Option[Long] = _ => None
  ): DuckLakeCatalogReader =
    new DuckLakeCatalogReader(null):
      override def snapshotExists(id: Long): Boolean = id == 12L || id == 39L || id == 42L
      override def maxSnapshotId(): Option[Long]     = Some(42L)
      override def currentTableInfo(schema: String, table: String): Option[(Long, Long)] =
        live.get((schema, table))
      override def latestTableSnapshot(schema: String, table: String): Option[Long] =
        live.get((schema, table)).map(_ => latest)
      override def tableExistsAt(schema: String, table: String, snapshotId: Long): Boolean =
        live.contains((schema, table)) && existsAt(schema, table, snapshotId)
      override def snapshotByCommitMessage(commitMessage: String): Option[Long] =
        ownCommit(commitMessage)

  /** A catalog whose read of a commit by its message fails: its Postgres is down. */
  private def unreadableCatalog(): DuckLakeCatalogReader =
    stubReader(ownCommit = _ => throw new java.sql.SQLException("catalog down"))

  trait Stubs:
    val (sup, store)               = supervisor()
    val telemetryStore             = new RecordingTelemetryStore
    val audit                      = new AuditRecorder(telemetryStore, _ => None)
    var readSql: Option[String]    = None
    var writeSql: Option[String]   = None
    var readPool: Option[PoolKey]  = None
    var writePool: Option[PoolKey] = None
    var readUser: Option[String]   = None
    var writeUser: Option[String]  = None
    var closed                     = false
    var readResult: IO[Either[RouterFailure, QueryResult]] =
      IO.pure(
        Right(
          QueryResult(
            summaryReader(
              List(
                ("insert", 3L),
                ("delete", 1L),
                ("update_postimage", 2L),
                ("update_preimage", 2L)
              )
            ),
            () => (),
            "node-1",
            1L
          )
        )
      )
    var writeResult: IO[Either[RouterFailure, QueryResult]] =
      IO.pure(Right(QueryResult(emptyReader(), () => closed = true, "node-1", 1L)))
    // The commits that landed, by message, as the catalog tells them: the write executor records
    // the caller's own message when it answers with a result, unless a case says that result's
    // COMMIT did not land (`commitLands` false: it failed, or its answer was lost and it was
    // refused).
    var commitLands = true
    val commits     = scala.collection.mutable.Map.empty[String, Long]
    // The commit fence the replace is held by: (message, base snapshot, table id) per arm.
    val fenceArms    = scala.collection.mutable.ListBuffer.empty[(String, Long, Long)]
    val fenceDisarms = scala.collection.mutable.ListBuffer.empty[String]
    var fenceFails   = Option.empty[String]
    val fence        = new ai.starlake.quack.ondemand.branch.TableCommitFence:
      def arm(m: Map[String, String], message: String, base: Long, tableId: Long) =
        fenceArms += ((message, base, tableId))
        fenceFails.toLeft(())
      def disarm(m: Map[String, String], message: String): Unit = fenceDisarms += message
    var readCallers: List[ExecCaller]                        = Nil
    var writeCallers: List[ExecCaller]                       = Nil
    val readExecutor: CatalogPreviewHandlers.PreviewExecutor =
      (caller, poolKey, sql) => {
        readSql = Some(sql); readPool = Some(poolKey); readUser = Some(caller.identity)
        readCallers = readCallers :+ caller
        readResult
      }
    val writeExecutor: CatalogPreviewHandlers.PreviewExecutor =
      (caller, poolKey, sql) => {
        writeSql = Some(sql); writePool = Some(poolKey); writeUser = Some(caller.identity)
        writeCallers = writeCallers :+ caller
        // The answer cancelable, as a node call is; the bookkeeping of the commit it stands for
        // masked with it, so that it adds no unmasked step after the answer.
        IO.uncancelable { poll =>
          poll(writeResult).flatTap {
            case Right(_) if commitLands =>
              IO(caller.commitMessage.foreach(m => commits.put(m, 43L)): Unit)
            case _ => IO.unit
          }
        }
      }

    // The catalog as the commits above leave it.
    lazy val committedReader: DuckLakeCatalogReader = stubReader(ownCommit = commits.get)

    def handlers(
        readerOverride: DuckLakeCatalogReader = committedReader,
        cfgOverride: CatalogConfig = CatalogConfig(previewMaxRows = 100, previewTimeoutSec = 30),
        sessionsOverride: String => Option[SessionTokenStore.Session] = _ => None,
        patsOverride: String => Option[PatPrincipal] = _ => None,
        resolveOverride: Option[(String, String) => DuckLakeCatalogReader] = None
    ): CatalogRestoreHandlers =
      new CatalogRestoreHandlers(
        sup,
        store,
        readExecutor,
        writeExecutor,
        resolveOverride.getOrElse((_, _) => readerOverride),
        cfgOverride,
        IdentityFixtures.restCaller(sessionsOverride, patsOverride),
        fence,
        audit = audit
      )

    def restore(
        h: CatalogRestoreHandlers,
        to: String = "39",
        dryRun: Option[Boolean] = None,
        expected: Option[Long] = None,
        schema: String = "tpch1",
        table: String = "orders",
        apiKey: Option[String] = RootKey
    ) =
      h.restore(
        RestoreRequest("acme", "acme_tpch1", schema, table, to, dryRun, expected),
        apiKey
      )(SuperuserScope)
        .unsafeRunSync()

  "restore" should "404 an unknown tenant" in new Stubs:
    val h   = handlers()
    val out = h
      .restore(
        RestoreRequest("nope", "acme_tpch1", "tpch1", "orders", "39"),
        RootKey
      )(SuperuserScope)
      .unsafeRunSync()
    out.left.toOption.get._1 shouldBe StatusCode.NotFound
    out.left.toOption.get._2.error shouldBe "not_found"
    telemetryStore.events.last.action shouldBe AuditActions.CatalogRestore
    telemetryStore.events.last.outcome shouldBe "denied"

  it should "400 invalid_name on unsafe characters in schema" in new Stubs:
    val h   = handlers()
    val out = restore(h, schema = "tp;ch")
    out.left.toOption.get._1 shouldBe StatusCode.BadRequest
    out.left.toOption.get._2.error shouldBe "invalid_name"
    writeSql shouldBe None

  it should "400 invalid_snapshot_ref on empty, overflowing, or non-positive expected snapshot" in new Stubs:
    val h = handlers()
    restore(h, to = "").left.toOption.get._2.error shouldBe "invalid_snapshot_ref"
    restore(h, to = "9" * 25).left.toOption.get._2.error shouldBe "invalid_snapshot_ref"
    restore(h, expected = Some(0L)).left.toOption.get._2.error shouldBe "invalid_snapshot_ref"

  it should "404 an unknown tag" in new Stubs:
    val h   = handlers()
    val out = restore(h, to = "nosuchtag")
    out.left.toOption.get._1 shouldBe StatusCode.NotFound

  it should "410 snapshot_expired for a nonexistent id between existing ones" in new Stubs:
    val h   = handlers()
    val out = restore(h, to = "13")
    out.left.toOption.get._1 shouldBe StatusCode.Gone
    out.left.toOption.get._2.error shouldBe "snapshot_expired"

  it should "422 for a snapshot id beyond max" in new Stubs:
    val h   = handlers()
    val out = restore(h, to = "99")
    out.left.toOption.get._1 shouldBe StatusCode.UnprocessableEntity

  it should "404 not_found (mentioning undrop) when the table is not live" in new Stubs:
    val h   = handlers(readerOverride = stubReader(live = Map.empty))
    val out = restore(h)
    out.left.toOption.get._1 shouldBe StatusCode.NotFound
    out.left.toOption.get._2.error shouldBe "not_found"
    out.left.toOption.get._2.message should include("undrop")

  it should "422 invalid_snapshot when the table does not resolve at the target snapshot" in new Stubs:
    val h   = handlers(readerOverride = stubReader(existsAt = (_, _, _) => false))
    val out = restore(h)
    out.left.toOption.get._1 shouldBe StatusCode.UnprocessableEntity
    out.left.toOption.get._2.error shouldBe "invalid_snapshot"

  it should "409 concurrent_write when expectedCurrentSnapshot is stale" in new Stubs:
    val h   = handlers()
    val out = restore(h, expected = Some(41L))
    out.left.toOption.get._1 shouldBe StatusCode.Conflict
    out.left.toOption.get._2.error shouldBe "concurrent_write"
    writeSql shouldBe None

  it should "no-op a dry-run to a target at or after the current snapshot" in new Stubs:
    val h   = handlers()
    val out = restore(h, to = "42", dryRun = Some(true))
    val r   = out.toOption.get
    r.summary shouldBe Some(DataDiffSummary(0, 0, 0))
    r.newSnapshot shouldBe None
    r.dryRun shouldBe true
    readSql shouldBe None

  it should "run the dry-run summary query and fold the aggregate" in new Stubs:
    val h   = handlers()
    val out = restore(h, to = "39", dryRun = Some(true))
    readSql.get should include(
      "ducklake_table_deletions('acme_tpch1', 'tpch1', 'orders', 40, 40)"
    )
    val r = out.toOption.get
    r.summary shouldBe Some(DataDiffSummary(3, 1, 2))
    writeSql shouldBe None
    val ok = telemetryStore.events.last
    ok.outcome shouldBe "ok"
    ok.detail.get("dryRun") shouldBe Some("true")

  it should "prefer the dual pool over an earlier-sorted write-only pool for the dry-run read" in new Stubs:
    sup.createPool(PoolKey("acme", "acme_tpch1", "aro"), RoleDistribution(1, 0, 0)).unsafeRunSync()
    restore(handlers(), to = "39", dryRun = Some(true))
    readPool shouldBe Some(PoolKey("acme", "acme_tpch1", "bi"))

  it should "run the dry-run summary and the execute both as the caller, never as system" in new Stubs:
    val profile = AuthenticatedProfile("alice", "user", Set.empty, Map.empty, "db", Some("acme"))
    val session = SessionTokenStore.Session(
      profile,
      SessionScope(superuser = false, manageableTenants = Set("acme")),
      Instant.now()
    )
    val h = handlers(sessionsOverride = tok => if tok == "alice-tok" then Some(session) else None)
    restore(h, to = "39", dryRun = Some(true), apiKey = Some("alice-tok"))
    readUser shouldBe Some("alice")
    readCallers.map(_.system) shouldBe List(false)
    restore(h, to = "39", apiKey = Some("alice-tok"))
    writeUser shouldBe Some("alice")

  it should "execute one CREATE OR REPLACE, never a batch, and report the new snapshot" in new Stubs:
    val h               = handlers()
    val out             = restore(h, to = "39")
    val (message, _, _) = fenceArms.head
    // One statement. A batch binds each of its statements under the one session handle sent before
    // it, so a replace running past 60 s would be refused at its COMMIT's bind, for good.
    writeSql shouldBe Some(
      """CREATE OR REPLACE TABLE "tpch1"."orders" AS SELECT * FROM "tpch1"."orders" AT (VERSION => 39)"""
    )
    FlightSqlRouter.isMultiStatement(writeSql.get) shouldBe false
    // The commit message the fence singles this commit out by rides on the caller, not in the SQL:
    // the router stamps the statement with it (BEGIN and the message, the statement, the COMMIT,
    // each PREPARE after a session handle of its own).
    writeCallers.map(_.commitMessage) shouldBe List(Some(message))
    closed shouldBe true
    val r = out.toOption.get
    // The snapshot of the restore's own commit, as the catalog names it (the table's record says 40).
    r.newSnapshot shouldBe Some(43L)
    r.summary shouldBe None
    r.dryRun shouldBe false
    val ok = telemetryStore.events.last
    ok.outcome shouldBe "ok"
    ok.detail.contains("newSnapshot") shouldBe true

  it should "hold the replace's commit to the validated table state, then release it" in new Stubs:
    restore(handlers(), to = "39", expected = Some(40L)).isRight shouldBe true
    // Base: the table's newest snapshot as validated; scope: the table id the replace changes.
    fenceArms.toList.map(a => (a._2, a._3)) shouldBe List((40L, 7L))
    fenceArms.head._1 should startWith("restore tpch1.orders to snapshot 39 [")
    fenceDisarms.toList shouldBe fenceArms.map(_._1).toList

  it should "release the fence when the replace fails too" in new Stubs:
    writeResult = IO.pure(Left(RouterFailure.Internal("boom")))
    restore(handlers()).isLeft shouldBe true
    fenceDisarms.toList shouldBe fenceArms.map(_._1).toList

  it should "502 without writing when the fence cannot be armed" in new Stubs:
    fenceFails = Some("cannot arm the commit fence: connection refused")
    val out = restore(handlers(), to = "39")
    out.left.toOption.get._2.error shouldBe "restore_failed"
    writeSql shouldBe None

  it should "409 when the fence refused the commit because the table changed meanwhile" in new Stubs:
    writeResult = IO.pure(
      Left(
        RouterFailure.Internal(
          "qod commit fence: conflict, table 7 changed after snapshot 40 before the commit"
        )
      )
    )
    val out = restore(handlers(), to = "39", expected = Some(40L))
    out.left.toOption.get._1 shouldBe StatusCode.Conflict
    out.left.toOption.get._2.error shouldBe "concurrent_write"
    telemetryStore.events.last.outcome should not be "ok"

  it should "not report success when the node refuses the COMMIT read at the end of the result" in new Stubs:
    var shut = false
    commitLands = false
    writeResult = IO.pure(
      Right(QueryResult(refusedReader("COMMIT failed: boom"), () => shut = true, "node-1", 1L))
    )
    val out = restore(handlers(), to = "39")
    out.left.toOption.get._1 shouldBe StatusCode.BadGateway
    out.left.toOption.get._2.error shouldBe "restore_failed"
    out.left.toOption.get._2.message should include("COMMIT failed: boom")
    telemetryStore.events.last.outcome should not be "ok"
    shut shouldBe true
    // The node's own answer is final: nothing can land, so the fence comes off.
    fenceDisarms.toList shouldBe fenceArms.map(_._1).toList

  it should "409 when the node refuses the COMMIT with a conflict, and take the fence off" in new Stubs:
    commitLands = false
    writeResult = IO.pure(
      Right(QueryResult(refusedReader("write-write conflict"), () => (), "node-1", 1L))
    )
    val out = restore(handlers(), to = "39")
    out.left.toOption.get._1 shouldBe StatusCode.Conflict
    out.left.toOption.get._2.error shouldBe "concurrent_write"
    fenceDisarms.toList shouldBe fenceArms.map(_._1).toList

  /** What DuckLake answers when writers' commits tip a COMMIT past its retry limit, up to the query
    * it last tried.
    */
  private val RetriesExhausted =
    "TransactionContext Error: Failed to commit: Failed to commit DuckLake transaction.\n" +
      "Exceeded the maximum retry count of 10 set by the ducklake_max_retry_count setting.\n" +
      ". Consider increasing the value with: e.g., \"SET ducklake_max_retry_count = 100;\"\n" +
      "Failed to flush changes into DuckLake: Failed to execute query"

  it should "409 when DuckLake gave up on its retries, a refusal like the fence's, and take the fence off" in new Stubs:
    commitLands = false
    writeResult = IO.pure(
      Right(QueryResult(refusedReader(RetriesExhausted), () => (), "node-1", 1L))
    )
    val out = restore(handlers(), to = "39")
    out.left.toOption.get._1 shouldBe StatusCode.Conflict
    out.left.toOption.get._2.error shouldBe "concurrent_write"
    fenceDisarms.toList shouldBe fenceArms.map(_._1).toList
    telemetryStore.events.last.outcome should not be "ok"

  it should "answer that text the same way from a statement that failed before its COMMIT" in new Stubs:
    writeResult = IO.pure(Left(RouterFailure.Internal(RetriesExhausted)))
    val out = restore(handlers(), to = "39")
    out.left.toOption.get._1 shouldBe StatusCode.Conflict
    out.left.toOption.get._2.error shouldBe "concurrent_write"
    fenceDisarms.toList shouldBe fenceArms.map(_._1).toList

  it should "ask the catalog first on that text too: a commit found there landed" in new Stubs:
    writeResult = IO.pure(
      Right(QueryResult(refusedReader(RetriesExhausted), () => (), "node-1", 1L))
    )
    val out = restore(handlers(), to = "39")
    out.toOption.get.newSnapshot shouldBe Some(43L)
    telemetryStore.events.last.outcome shouldBe "ok"

  it should "keep the fence when that text comes with an answer that was lost: it can still land" in new Stubs:
    commitLands = false
    writeResult = IO.pure(
      Right(QueryResult(failingReader(RetriesExhausted), () => (), "node-1", 1L))
    )
    val out = restore(handlers(), to = "39")
    out.left.toOption.get._2.error shouldBe "restore_outcome_unknown"
    fenceDisarms shouldBe empty

  it should "answer a COMMIT whose session handle the node refused as a refusal: it never went out" in new Stubs:
    commitLands = false
    writeResult = IO.pure(
      Right(
        QueryResult(
          failingReaderWith(QuackHttpClient.HandleRefused("session handle refused: expired")),
          () => (),
          "node-1",
          1L
        )
      )
    )
    val out = restore(handlers(), to = "39")
    out.left.toOption.get._2.error shouldBe "restore_failed"
    fenceDisarms.toList shouldBe fenceArms.map(_._1).toList

  it should "settle a COMMIT the node answered with an error from the catalog too: that commit can be durable" in new Stubs:
    // Every statement error is an ERROR_RESPONSE, a connection to the catalog lost during the
    // metadata COMMIT included, and that commit can be durable. The node's error says what the node
    // saw, not what the catalog's Postgres did: it is success when the catalog holds this restore's
    // commit, and the fence comes off.
    writeResult = IO.pure(
      Right(QueryResult(refusedReader("catalog connection lost"), () => (), "node-1", 1L))
    )
    val out = restore(handlers(), to = "39")
    out.toOption.get.newSnapshot shouldBe Some(43L)
    telemetryStore.events.last.outcome shouldBe "ok"
    fenceDisarms.toList shouldBe fenceArms.map(_._1).toList

  it should "say its outcome is unknown, and keep the fence, when a COMMIT's answer is lost and no commit is visible" in new Stubs:
    // A broken connection, a 5xx: the COMMIT may still be in DuckLake's retries, and a read that
    // beats it finds nothing. restore_failed here would have a client retry a restore that lands.
    commitLands = false
    List[Throwable](
      new java.io.IOException("connection reset"),
      QuackWireError.Transient("HTTP 503 from Quack node")
    ).foreach { lost =>
      writeResult = IO.pure(Right(QueryResult(failingReaderWith(lost), () => (), "node-1", 1L)))
      val out             = restore(handlers(), to = "39")
      val (status, error) = out.left.toOption.get
      withClue(s"$lost: ") {
        status shouldBe StatusCode.BadGateway
        error.error shouldBe "restore_outcome_unknown"
        error.message should include(lost.getMessage)
        error.message should include("check tpch1.orders before retrying")
        telemetryStore.events.last.outcome shouldBe "denied"
      }
    }
    // The row stays armed: a late COMMIT is still held to the state it was validated against, and a
    // leftover matches only this restore's own message.
    fenceDisarms shouldBe empty
    fenceArms.size shouldBe 2

  it should "settle a COMMIT whose answer was lost from the catalog, by this restore's own commit" in new Stubs:
    // The COMMIT is sent when the result is read, after the replace: the stream breaking there says
    // nothing of whether it landed. The commit carrying this restore's message does.
    writeResult = IO.pure(
      Right(QueryResult(failingReader("connection reset"), () => (), "node-1", 1L))
    )
    // It landed: success, and the fence comes off.
    restore(handlers(), to = "39").toOption.get.newSnapshot shouldBe Some(43L)
    telemetryStore.events.last.outcome shouldBe "ok"
    fenceDisarms.toList shouldBe fenceArms.map(_._1).toList

  it should "say its outcome is unknown, audited, when a COMMIT fails and the catalog cannot be read" in new Stubs:
    // The catalog's Postgres being down is what makes a COMMIT fail, and what makes the read that
    // settles it fail too: neither success nor a plain failure, and never a bare 500. The node
    // reports that outage as an error of its own (an ERROR_RESPONSE, the shape a real outage takes)
    // or its answer is lost on the way back: both are unknown, and neither is a refusal.
    commitLands = false
    List(
      "an error of the node's own" -> refusedReader("connection reset"),
      "a lost answer"              -> failingReader("connection reset")
    ).foreach { (shape, reader) =>
      writeResult = IO.pure(Right(QueryResult(reader, () => (), "node-1", 1L)))
      val out             = restore(handlers(readerOverride = unreadableCatalog()), to = "39")
      val (status, error) = out.left.toOption.get
      withClue(s"$shape: ") {
        status shouldBe StatusCode.BadGateway
        error.error shouldBe "restore_outcome_unknown"
        error.message should include("connection reset")
        error.message should include("catalog down")
        error.message should include("check tpch1.orders before retrying")
        telemetryStore.events.last.outcome shouldBe "denied"
        // The audit trail names the code: it is not a refusal.
        telemetryStore.events.last.detail.get("code") shouldBe Some("restore_outcome_unknown")
      }
    }
    // The row stays armed in both: the commit may be durable, and a leftover matches only this
    // restore's own message.
    fenceDisarms shouldBe empty
    fenceArms.size shouldBe 2

  it should "say the same when a timeout leaves it to a catalog that cannot be read" in new Stubs:
    writeResult = IO.never
    val h = handlers(
      readerOverride = unreadableCatalog(),
      cfgOverride =
        CatalogConfig(previewMaxRows = 100, previewTimeoutSec = 30, restoreTimeoutSec = 1)
    )
    val out             = restore(h, to = "39")
    val (status, error) = out.left.toOption.get
    status shouldBe StatusCode.BadGateway
    error.error shouldBe "restore_outcome_unknown"
    error.message should include("timed out")
    error.message should include("check tpch1.orders before retrying")
    telemetryStore.events.last.outcome shouldBe "denied"
    fenceDisarms shouldBe empty

  it should "confirm a clean answer by its own commit, and report that commit's snapshot" in new Stubs:
    // The table's own record says 40; the commit this restore made is snapshot 43.
    val out = restore(handlers(), to = "39")
    out.toOption.get.newSnapshot shouldBe Some(43L)
    telemetryStore.events.last.detail.get("newSnapshot") shouldBe Some("43")

  it should "say its outcome is unknown, and keep the fence, when its COMMIT answered and no commit is visible" in new Stubs:
    // The result read clean to its end, yet the catalog shows no commit of this restore: a kill can
    // leave its COMMIT in flight on another thread, so the table may still be replaced. Never
    // "unchanged", and the fence stays on for the COMMIT that may yet land.
    commitLands = false
    val out = restore(handlers(), to = "39")
    out.left.toOption.get._1 shouldBe StatusCode.BadGateway
    out.left.toOption.get._2.error shouldBe "restore_outcome_unknown"
    out.left.toOption.get._2.message should include("no commit")
    out.left.toOption.get._2.message should include("may still land")
    out.left.toOption.get._2.message should include("check tpch1.orders before retrying")
    out.left.toOption.get._2.message should not include "unchanged"
    telemetryStore.events.last.outcome shouldBe "denied"
    closed shouldBe true
    fenceDisarms shouldBe empty

  it should "say its outcome is unknown when a clean answer cannot be confirmed" in new Stubs:
    val out = restore(handlers(readerOverride = unreadableCatalog()), to = "39")
    out.left.toOption.get._2.error shouldBe "restore_outcome_unknown"
    telemetryStore.events.last.outcome shouldBe "denied"
    fenceDisarms shouldBe empty

  it should "settle through a reader it resolves again, not the one it validated with" in new Stubs:
    // The reader the restore began with can be closed by the idle sweep or evicted meanwhile.
    val resolved = new java.util.concurrent.atomic.AtomicInteger(0)
    val stale    = stubReader(ownCommit = _ => throw new java.sql.SQLException("pool closed"))
    writeResult = IO.pure(
      Right(QueryResult(failingReader("connection reset"), () => (), "node-1", 1L))
    )
    val h = handlers(resolveOverride =
      Some((_, _) => if resolved.getAndIncrement() == 0 then stale else committedReader)
    )
    restore(h, to = "39").toOption.get.newSnapshot shouldBe Some(43L)

  it should "read the catalog a second time when the first read fails" in new Stubs:
    val reads = new java.util.concurrent.atomic.AtomicInteger(0)
    val flaky = stubReader(ownCommit =
      m =>
        if reads.getAndIncrement() == 0 then throw new java.sql.SQLException("blip")
        else commits.get(m)
    )
    restore(handlers(readerOverride = flaky), to = "39").toOption.get.newSnapshot shouldBe Some(43L)
    reads.get() shouldBe 2

  it should "read and close a result that arrives as the timeout fires, and settle it from the catalog" in new Stubs:
    // The executor goes on to its answer past the bound: that result must still be read to its end
    // (its COMMIT sent) and closed, never dropped with its node connection, and the catalog then
    // says whether the commit landed.
    writeResult = IO.uncancelable(_ =>
      IO.blocking {
        Thread.sleep(1500)
        Right(QueryResult(emptyReader(), () => closed = true, "node-1", 1L))
      }
    )
    val h = handlers(cfgOverride =
      CatalogConfig(previewMaxRows = 100, previewTimeoutSec = 30, restoreTimeoutSec = 1)
    )
    val out = restore(h, to = "39")
    closed shouldBe true
    out.toOption.get.newSnapshot shouldBe Some(43L)

  it should "report the snapshot its own commit carries, not what the table's record says" in new Stubs:
    val stale = stubReader(
      live = Map(("tpch1", "orders") -> (7L, 40L)),
      ownCommit = m => Option.when(m.startsWith("restore tpch1.orders to snapshot 39 ["))(44L)
    )
    restore(handlers(readerOverride = stale), to = "39").toOption.get.newSnapshot shouldBe
      Some(44L)

  it should "403 acl_denied when the executor reports AccessDenied" in new Stubs:
    writeResult = IO.pure(Left(RouterFailure.AccessDenied("nope")))
    val h   = handlers()
    val out = restore(h)
    out.left.toOption.get._1 shouldBe StatusCode.Forbidden
    out.left.toOption.get._2.error shouldBe "acl_denied"

  it should "409 concurrent_write when the failure reason mentions a conflict" in new Stubs:
    writeResult = IO.pure(Left(RouterFailure.Internal("transaction Conflict detected")))
    val h   = handlers()
    val out = restore(h)
    out.left.toOption.get._1 shouldBe StatusCode.Conflict
    out.left.toOption.get._2.error shouldBe "concurrent_write"

  it should "502 restore_failed for any other executor failure" in new Stubs:
    writeResult = IO.pure(Left(RouterFailure.Internal("boom")))
    val h   = handlers()
    val out = restore(h)
    out.left.toOption.get._1 shouldBe StatusCode.BadGateway
    out.left.toOption.get._2.error shouldBe "restore_failed"

  it should "502 restore_failed with a timeout message when the CTAS neither returns nor commits" in new Stubs:
    writeResult = IO.never
    val h = handlers(cfgOverride =
      CatalogConfig(previewMaxRows = 100, previewTimeoutSec = 30, restoreTimeoutSec = 1)
    )
    val out = restore(h)
    out.left.toOption.get._1 shouldBe StatusCode.BadGateway
    out.left.toOption.get._2.error shouldBe "restore_failed"
    out.left.toOption.get._2.message should include("timed out")
    // Cancelled before its COMMIT could go out: nothing can land, the fence comes off.
    fenceDisarms.toList shouldBe fenceArms.map(_._1).toList

  it should "report success when the CTAS commits despite the timeout (post-timeout probe)" in new Stubs:
    writeResult = IO.never
    val reader = new DuckLakeCatalogReader(null):
      override def snapshotExists(id: Long): Boolean = id == 12L || id == 39L || id == 42L
      override def maxSnapshotId(): Option[Long]     = Some(42L)
      override def currentTableInfo(schema: String, table: String): Option[(Long, Long)] =
        Some((7L, 40L))
      override def latestTableSnapshot(schema: String, table: String): Option[Long] = Some(40L)
      override def tableExistsAt(schema: String, table: String, snapshotId: Long): Boolean = true
      // This restore's own commit, found by its unique message.
      override def snapshotByCommitMessage(commitMessage: String): Option[Long] =
        Option.when(commitMessage.startsWith("restore tpch1.orders to snapshot 39 ["))(43L)
    val h = handlers(
      readerOverride = reader,
      cfgOverride =
        CatalogConfig(previewMaxRows = 100, previewTimeoutSec = 30, restoreTimeoutSec = 1)
    )
    val out = restore(h)
    out.toOption.get.newSnapshot shouldBe Some(43L)
    telemetryStore.events.last.outcome shouldBe "ok"

  it should "not report success when only another writer's replace changed the table" in new Stubs:
    // The answer is lost; meanwhile another writer replaced the table (a new table id), and this
    // restore's own commit, the one its message names, never landed.
    writeResult = IO.never
    val reader = new DuckLakeCatalogReader(null):
      override def snapshotExists(id: Long): Boolean = id == 12L || id == 39L || id == 42L
      override def maxSnapshotId(): Option[Long]     = Some(42L)
      private val calls                              = new java.util.concurrent.atomic.AtomicInteger
      override def currentTableInfo(schema: String, table: String): Option[(Long, Long)] =
        if calls.getAndIncrement() == 0 then Some((7L, 40L)) else Some((9L, 44L))
      override def latestTableSnapshot(schema: String, table: String): Option[Long] = Some(40L)
      override def tableExistsAt(schema: String, table: String, snapshotId: Long): Boolean = true
      override def snapshotByCommitMessage(commitMessage: String): Option[Long]            = None
    val h = handlers(
      readerOverride = reader,
      cfgOverride =
        CatalogConfig(previewMaxRows = 100, previewTimeoutSec = 30, restoreTimeoutSec = 1)
    )
    val out = restore(h, to = "39")
    out.left.toOption.get._1 shouldBe StatusCode.BadGateway
    out.left.toOption.get._2.error shouldBe "restore_failed"
    telemetryStore.events.last.outcome should not be "ok"

  // ---------- executor identity (PAT regression) ----------

  "restore's replace" should "run a superuser session as its user, never as a system caller" in new Stubs:
    restore(handlers(), to = "39", apiKey = Some(IdentityFixtures.RootSessionTok)).isRight shouldBe
      true
    writeCallers.map(c => (c.identity, c.restriction, c.patId, c.system)) shouldBe
      List((IdentityFixtures.RootUsername, TokenRestriction.Unrestricted, None, false))

  it should "run a session as its user, unrestricted" in new Stubs:
    val h = handlers(sessionsOverride = IdentityFixtures.sessionOf)
    restore(h, to = "39", apiKey = Some(IdentityFixtures.SessionTok)).isRight shouldBe true
    writeCallers.map(c => (c.identity, c.restriction, c.patId)) shouldBe
      List(("alice", TokenRestriction.Unrestricted, None))

  it should "run a PAT as its owner, with the PAT's restriction and id (never superuser)" in new Stubs:
    val h = handlers(patsOverride = IdentityFixtures.patOf)
    restore(h, to = "39", apiKey = Some(IdentityFixtures.PatTok)).isRight shouldBe true
    writeCallers.map(c => (c.identity, c.restriction, c.patId)) shouldBe
      List(("alice", IdentityFixtures.PatRestriction, Some(IdentityFixtures.PatId)))

  it should "run the PAT's dry run as the PAT's owner, with the PAT" in new Stubs:
    val h = handlers(patsOverride = IdentityFixtures.patOf)
    restore(
      h,
      to = "39",
      dryRun = Some(true),
      apiKey = Some(IdentityFixtures.PatTok)
    ).isRight shouldBe
      true
    readCallers.map(c => (c.identity, c.system, c.patId)) shouldBe
      List(("alice", false, Some(IdentityFixtures.PatId)))
    writeCallers shouldBe Nil

  it should "answer a dry run the node refuses the caller with 403, never retrying as system" in new Stubs:
    readResult = IO.pure(Left(RouterFailure.AccessDenied("QoD: access denied: no grant on orders")))
    val h   = handlers(patsOverride = IdentityFixtures.patOf)
    val out = restore(h, to = "39", dryRun = Some(true), apiKey = Some(IdentityFixtures.PatTok))
    out.left.toOption.map(e => (e._1, e._2.error)) shouldBe
      Some((StatusCode.Forbidden, "acl_denied"))
    readCallers.map(_.system) shouldBe List(false)

  it should "401 an unresolvable token on both legs without calling either executor" in new Stubs:
    val h = handlers(
      sessionsOverride = IdentityFixtures.sessionOf,
      patsOverride = IdentityFixtures.patOf
    )
    List(Some(true), None).foreach { dry =>
      val out = restore(h, to = "39", dryRun = dry, apiKey = Some("qod_pat_unknown"))
      out.left.toOption.map(e => (e._1, e._2.error)) shouldBe
        Some((StatusCode.Unauthorized, "unauthorized"))
    }
    readCallers shouldBe Nil
    writeCallers shouldBe Nil

  it should "401 an absent credential on both legs without calling either executor" in new Stubs:
    val h = handlers(
      sessionsOverride = IdentityFixtures.sessionOf,
      patsOverride = IdentityFixtures.patOf
    )
    List(Some(true), None).foreach { dry =>
      val out = restore(h, to = "39", dryRun = dry, apiKey = None)
      out.left.toOption.map(e => (e._1, e._2.error)) shouldBe
        Some((StatusCode.Unauthorized, "unauthorized"))
    }
    readCallers shouldBe Nil
    writeCallers shouldBe Nil
