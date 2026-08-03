package ai.starlake.quack.edge

import ai.starlake.acl.parser.TableAccess
import ai.starlake.quack.edge.adapter._
import ai.starlake.quack.edge.sql.{
  Allowed,
  Denied,
  LockdownScreen,
  StatementValidator,
  ValidationContext
}
import ai.starlake.quack.model.{PoolKey, SqlLiterals, StatementKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditEvent, EventJournal, StatementEvent}
import ai.starlake.quack.route.{PoolSnapshot, Router, RoutingDecision, StatementClassifier}
import ai.starlake.quack.spi.{ManagerEvent, ManagerEventSink}
import ai.starlake.sql.SqlCommentStripper

import ai.starlake.quack.observability.metrics.StatementInstruments
import cats.effect.IO

import scala.concurrent.duration.*

/** Streaming result; the caller MUST invoke `close()` once all batches are consumed. `nodeId` lets
  * the Flight producer soft-pin a prepared Execute to the Prepare node; `durationMs` is the
  * node-call latency (becomes prepareDurationMs on the Execute record).
  */
final case class QueryResult(
    rows: org.apache.arrow.vector.ipc.ArrowReader,
    close: () => Unit,
    nodeId: String,
    durationMs: Long
)

/** Routing core extracted from the Arrow Flight surface so it can be unit-tested. The Flight
  * producer is a thin shell around `execute`.
  */
final class FlightSqlRouter(
    val supervisor: PoolSupervisor,
    val sessions: SessionRegistry,
    val tracker: NodeLoadTracker,
    val adapter: QuackHttpAdapter,
    val validator: StatementValidator = StatementValidator.allowAll,
    val history: StatementHistoryStore = new StatementHistoryStore(),
    val stmtInstruments: StatementInstruments = StatementInstruments.noop,
    val classifier: StatementClassifier = StatementClassifier.default,
    val columnPolicyRewriter: ai.starlake.quack.edge.cls.ColumnPolicyRewriter =
      new ai.starlake.quack.edge.cls.ColumnPolicyRewriter(
        new ai.starlake.quack.edge.cls.ColumnCatalog.MapCatalog(Map.empty)
      ),
    val rowPolicyRewriter: ai.starlake.quack.edge.rls.RowPolicyRewriter =
      new ai.starlake.quack.edge.rls.RowPolicyRewriter(),
    val registry: ActiveStatementRegistry = new ActiveStatementRegistry(),
    val journal: EventJournal = EventJournal.noop,
    val stampWrites: Boolean = false,
    val attachedCatalogsOf: ai.starlake.quack.model.PoolKey => Set[String] = _ => Set.empty,
    val events: ManagerEventSink = ManagerEventSink.noop,
    val resumeHoldTimeout: FiniteDuration = 60.seconds,
    val resumePollInterval: FiniteDuration = 250.millis,
    val lockdownFor: PoolKey => Boolean = _ => false,
    val routingRefs: ai.starlake.quack.route.RoutingRefsCache =
      new ai.starlake.quack.route.RoutingRefsCache(),
    val refsConfigFor: PoolKey => ai.starlake.acl.model.Config = _ =>
      ai.starlake.acl.model.Config.forDuckDB(None, None),
    val locality: ai.starlake.quack.route.LocalityTracker =
      new ai.starlake.quack.route.LocalityTracker(),
    val routingInstruments: ai.starlake.quack.observability.metrics.RoutingInstruments =
      ai.starlake.quack.observability.metrics.RoutingInstruments.noop,
    val placement: ai.starlake.quack.route.PlacementDirectory =
      new ai.starlake.quack.route.PlacementDirectory(),
    val cacheAwareRouting: Boolean = true,
    val loadCapFactor: Double = 2.0
):

  /** Record a statement outcome into history, metrics, and (selectively) the audit journal:
    * "denied" journals as data-denial, "ok" DML/DDL as data-write, all other statuses journal
    * nothing. `realm` is "system" for superuser principals, "tenant" otherwise.
    */
  private def record(
      user: String,
      poolKey: PoolKey,
      nodeId: String,
      sql: String,
      durationMs: Long,
      status: String,
      error: Option[String],
      kind: StatementKind,
      deniedRefs: Set[TableAccess] = Set.empty,
      realm: String = "tenant",
      prepareDurationMs: Option[Long] = None
  ): Unit =
    history.record(
      StatementRecord(
        ts = java.time.Instant.now(),
        user = user,
        tenant = poolKey.tenant,
        pool = poolKey.pool,
        nodeId = nodeId,
        sql = sql,
        durationMs = durationMs,
        status = status,
        error = error,
        prepareDurationMs = prepareDurationMs
      )
    )
    stmtInstruments.record(poolKey.tenant, poolKey.pool, status, durationMs)
    journal.offerStatement(
      StatementEvent(
        java.time.Instant.now(),
        user,
        poolKey.tenant,
        poolKey.pool,
        nodeId,
        sql.take(500),
        durationMs,
        prepareDurationMs,
        status,
        error.map(_.take(500))
      )
    )
    if status == "denied" then
      journal.offer(
        AuditEvent(
          java.time.Instant.now(),
          "data-denial",
          user,
          realm,
          Some(poolKey.tenant),
          AuditActions.SqlDenied,
          None,
          "denied",
          "flightsql",
          Map("sql" -> sql.take(500)) ++
            Option
              .when(deniedRefs.nonEmpty)(
                "denied" -> deniedRefs.map(a => s"${a.table.canonical}:${a.verb}").mkString(",")
              )
              .toMap ++
            error.map("reason" -> _.take(500)).toMap
        )
      )
    else if status == "ok" && (kind == StatementKind.Dml || kind == StatementKind.Ddl) then
      journal.offer(
        AuditEvent(
          java.time.Instant.now(),
          "data-write",
          user,
          realm,
          Some(poolKey.tenant),
          if kind == StatementKind.Ddl then AuditActions.SqlDdl else AuditActions.SqlWrite,
          None,
          "ok",
          "flightsql",
          Map("sql" -> sql.take(500), "durationMs" -> durationMs.toString)
        )
      )

  /** Author-stamping prelude for a write, or None when stamping does not apply (DML/DDL on ducklake
    * pools outside a client-opened transaction, dbName advertised). Runs as the first PREPARE of
    * the wire bracket; the statement itself follows unmodified. All values are escaped DuckDB
    * literals: the username is client-controlled input.
    */
  private[edge] def stampPrelude(
      kind: StatementKind,
      kindWire: String,
      poolMeta: Map[String, String],
      txOpen: Boolean,
      user: String,
      tenant: String,
      sql: String
  ): Option[String] =
    val isWrite = kind == StatementKind.Dml || kind == StatementKind.Ddl
    if !stampWrites || !isWrite || kindWire != "ducklake" || txOpen then None
    else
      poolMeta.get("dbName").filter(_.nonEmpty).map { db =>
        val author   = s"tenant:$tenant/user:$user"
        val stripped = SqlCommentStripper.stripComments(sql)
        val verb     = stripped.trim.takeWhile(c => !c.isWhitespace).toLowerCase
        s"BEGIN; CALL ducklake_set_commit_message(" +
          s"${SqlLiterals.duckdbLiteral(db)}, " +
          s"${SqlLiterals.duckdbLiteral(author)}, " +
          s"${SqlLiterals.duckdbLiteral(s"flightsql $verb")})"
      }

  def session(connectionId: String) = sessions.get(connectionId)

  /** Run a statement under the named connection; the caller MUST close the [[QueryResult]].
    *
    * `effectiveSet = None` means no handshake state was attached; PostgresAclValidator denies
    * anything tenant-scoped in that case to fail safe.
    *
    * `preferredNode` is a SOFT pin (prepared Prepare + Execute on the same node for warm caches): a
    * transaction pin still overrides, and a vanished node falls back to the load-aware pick.
    *
    * `recordExecution = false` (the Prepare-time probe) suppresses the history record AND the
    * per-node load / latency bookkeeping, so the UI shows one row per user-visible query and probes
    * don't skew the dashboard; the probe's duration reaches the Execute record via
    * `prepareDurationMs`.
    */
  def execute(
      connectionId: String,
      user: String,
      poolKey: PoolKey,
      sql: String,
      effectiveSet: Option[EffectiveSet] = None,
      preferredNode: Option[String] = None,
      recordExecution: Boolean = true,
      prepareDurationMs: Option[Long] = None
  ): IO[Either[RouterFailure, QueryResult]] =
    val s = sessions.get(connectionId).getOrElse {
      val opened = sessions.open(connectionId, user, poolKey)
      // Probes (recordExecution=false) must not emit, matching every other telemetry surface.
      if recordExecution then
        events.emit(ManagerEvent.SessionOpened(poolKey.tenant, user, "flightsql"))
      opened
    }
    val kind = classifier.classify(sql)
    // Per-pool dbName/schemaName overrides feed the SQL parser so unqualified
    // table refs resolve to what the node actually sees at execution time.
    val maybeState = supervisor.get(poolKey)
    val poolMeta   = maybeState.map(_.metastore).getOrElse(Map.empty)
    val kindWire   = maybeState.map(_.kindWire).getOrElse("ducklake")

    def perKindDb: Option[String] = kindWire match
      case "ducklake" | "duckdb-file" => poolMeta.get("dbName").filter(_.nonEmpty)
      case "memory"                   => Some("memory")
      case _                          => None

    def perKindSchema: Option[String] = kindWire match
      case "ducklake" | "duckdb-file" => poolMeta.get("schemaName").filter(_.nonEmpty)
      case "memory"                   => Some("main")
      case _                          => None

    val ctx = ValidationContext(
      username = user,
      database = poolKey.toString,
      statement = sql,
      peer = connectionId,
      defaultDatabase = maybeState.flatMap(_.defaultDatabase).orElse(perKindDb),
      defaultSchema = maybeState.flatMap(_.defaultSchema).orElse(perKindSchema),
      effectiveSet = effectiveSet,
      attachedCatalogs = attachedCatalogsOf(poolKey)
    )
    // No-op for probes. deniedRefs is non-empty only on the ACL denial arm and
    // feeds the journal event's "denied" key.
    def maybeRecord(
        nodeId: String,
        durationMs: Long,
        status: String,
        error: Option[String],
        deniedRefs: Set[TableAccess] = Set.empty,
        prepMs: Option[Long] = prepareDurationMs
    ): Unit =
      if recordExecution then
        val realm = if effectiveSet.exists(_.user.tenant.isEmpty) then "system" else "tenant"
        record(
          user,
          poolKey,
          nodeId,
          sql,
          durationMs,
          status,
          error,
          kind,
          deniedRefs,
          realm,
          prepMs
        )

    // Node lockdown, resolved per pool (tri-state: pool override else global default).
    // Runs BEFORE the ACL gate so a denied statement never reaches the SQL parser;
    // effectiveSet = None screens as non-superuser (fail closed).
    val lockdownDenial =
      if lockdownFor(poolKey) && !effectiveSet.exists(_.user.tenant.isEmpty) then
        LockdownScreen.screen(sql)
      else None

    val aclCheck: Either[RouterFailure, Unit] = lockdownDenial match
      case Some(reason) =>
        maybeRecord(
          nodeId = "-",
          durationMs = 0,
          status = "denied",
          error = Some("lockdown: " + reason)
        )
        Left(RouterFailure.AccessDenied(s"access denied: lockdown: $reason"))
      case None =>
        validator.validate(ctx) match
          case Denied(reason, deniedRefs) =>
            maybeRecord(
              nodeId = "-",
              durationMs = 0,
              status = "denied",
              error = Some(reason),
              deniedRefs = deniedRefs
            )
            Left(RouterFailure.AccessDenied(s"access denied: $reason"))
          case Allowed => Right(())

    // Column-level security: enforce per-column policies before routing.
    val schemaCtx = ai.starlake.quack.edge.cls.SchemaContext(
      defaultDatabase = ctx.defaultDatabase,
      defaultSchema = ctx.defaultSchema
    )
    import cats.effect.unsafe.implicits.global
    // Shared CLS deny arm: instrument tag, journal, wire error.
    def clsDenied(tag: String, reason: String): Either[RouterFailure, String] =
      stmtInstruments.recordColumnPolicyRewrite(poolKey.tenant, poolKey.pool, tag)
      maybeRecord(nodeId = "-", durationMs = 0, status = "denied", error = Some(reason))
      Left(RouterFailure.AccessDenied(s"access denied: $reason"))

    def clsRewritten(): Either[RouterFailure, String] = effectiveSet match
      case None =>
        Right(sql) // no RBAC principal bound; rewriter would deny anything tenant-scoped
      case Some(eff) =>
        val t0        = System.nanoTime()
        val outcome   = columnPolicyRewriter.rewrite(sql, kind, eff, schemaCtx).unsafeRunSync()
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000L
        stmtInstruments.recordColumnPolicyRewriteDuration(poolKey.tenant, poolKey.pool, elapsedMs)
        outcome match
          case ai.starlake.quack.edge.cls.ColumnPolicyRewriter.Passthrough =>
            stmtInstruments.recordColumnPolicyRewrite(poolKey.tenant, poolKey.pool, "passthrough")
            Right(sql)
          case ai.starlake.quack.edge.cls.ColumnPolicyRewriter.PassthroughParseFailed =>
            // Fail closed: the principal has column policies and we cannot prove the masked
            // columns are absent; forwarding the original SQL would leak them.
            clsDenied(
              "parse_failed",
              "column policy rewrite could not parse statement; denied (fail-closed)"
            )
          case ai.starlake.quack.edge.cls.ColumnPolicyRewriter.Rewritten(s) =>
            stmtInstruments.recordColumnPolicyRewrite(poolKey.tenant, poolKey.pool, "rewritten")
            Right(s)
          case ai.starlake.quack.edge.cls.ColumnPolicyRewriter.Denied(reason) =>
            clsDenied("denied", reason)
          case ai.starlake.quack.edge.cls.ColumnPolicyRewriter.DeniedUnresolvedTable =>
            // Unresolved coordinate, not a policy match; same wire error, separate tag.
            clsDenied("unresolved_deny", "unresolved table")

    // RLS operates on the CLS output but injects at the BASE table, so the predicate
    // runs on true (unmasked) values: RLS innermost, CLS outermost.
    def rlsRewritten(rewrittenSql: String): Either[RouterFailure, String] = effectiveSet match
      case None      => Right(rewrittenSql)
      case Some(eff) =>
        val r0         = System.nanoTime()
        val schemaCtxR = ai.starlake.quack.edge.rls.SchemaContext(
          defaultDatabase = ctx.defaultDatabase,
          defaultSchema = ctx.defaultSchema
        )
        val outcome   = rowPolicyRewriter.rewrite(rewrittenSql, kind, eff, schemaCtxR)
        val elapsedMs = (System.nanoTime() - r0) / 1_000_000L
        stmtInstruments.recordRowPolicyRewriteDuration(poolKey.tenant, poolKey.pool, elapsedMs)
        outcome match
          case ai.starlake.quack.edge.rls.RowPolicyRewriter.Passthrough =>
            stmtInstruments.recordRowPolicyRewrite(poolKey.tenant, poolKey.pool, "passthrough")
            Right(rewrittenSql)
          case ai.starlake.quack.edge.rls.RowPolicyRewriter.PassthroughParseFailed =>
            // Fail closed: the principal has row policies and forwarding the original SQL
            // would return unfiltered rows.
            stmtInstruments.recordRowPolicyRewrite(poolKey.tenant, poolKey.pool, "parse_failed")
            val f = RouterFailure.AccessDenied(
              "access denied: row policy rewrite could not parse statement; denied (fail-closed)"
            )
            maybeRecord(nodeId = "-", durationMs = 0, status = "denied", error = Some(f.reason))
            Left(f)
          case ai.starlake.quack.edge.rls.RowPolicyRewriter.Rewritten(s) =>
            stmtInstruments.recordRowPolicyRewrite(poolKey.tenant, poolKey.pool, "rewritten")
            Right(s)

    // ACL -> CLS -> RLS pipeline; every denial arm has already journaled itself.
    // Bound to resultIO so one flatTap below emits exactly one StatementExecuted
    // event on every exit path, including the denial arms.
    val resultIO: IO[Either[RouterFailure, QueryResult]] =
      aclCheck.flatMap(_ => clsRewritten()).flatMap(rlsRewritten) match
        case Left(f)         => IO.pure(Left(f))
        case Right(finalSql) =>
          resolveSnapshot(poolKey).flatMap {
            case Left(f: RouterFailure.NotFound) =>
              if s.txOpen then sessions.invalidatePin(connectionId)
              maybeRecord(nodeId = "-", durationMs = 0, status = "no-pool", error = None)
              IO.pure(Left(f))
            case Left(f) =>
              maybeRecord(
                nodeId = "-",
                durationMs = 0,
                status = "resume-timeout",
                error = Some("pool is resuming")
              )
              IO.pure(Left(f))
            case Right(snap) =>
              // Tx pin wins; then the soft preferredNode if still in the snapshot;
              // else Router.pick's load-aware choice.
              val txPin  = s.pinnedNodeId.filter(_ => s.txOpen)
              val pinned =
                txPin.orElse(preferredNode.filter(id => snap.nodes.exists(_.nodeId == id)))
              // Each quack_query lands in a fresh remote DuckDB session, so unqualified
              // refs need the USE prefix (see wrapWithDefaultSchema).
              val wrappedSql = wrapWithDefaultSchema(supervisor.get(poolKey), finalSql)
              // Probes never stamp: they must not open transactions on the node.
              val prelude =
                if recordExecution then
                  stampPrelude(kind, kindWire, poolMeta, s.txOpen, user, poolKey.tenant, finalSql)
                else None
              import ai.starlake.quack.route.{PlacementDirectory, PlacementRequest, RoutingRefs}
              val refs =
                if recordExecution then routingRefs.extract(sql, refsConfigFor(poolKey))
                else RoutingRefs.empty
              val routableIds =
                snap.nodes.filter(n => snap.loadOf(n.nodeId).routable).map(_.nodeId).toSet
              val dataPath =
                supervisor.get(poolKey).map(_.metastore.getOrElse("dataPath", "")).getOrElse("")
              val placementEligible =
                cacheAwareRouting && recordExecution && refs.all.nonEmpty &&
                  routableIds.size > 1 && PlacementDirectory.isObjectStorePath(dataPath)
              val placementReq =
                if placementEligible then
                  Some(
                    PlacementRequest(
                      refs.all,
                      placement.viewFor(poolKey, refs.all, routableIds),
                      loadCapFactor
                    )
                  )
                else None
              Router.pick(snap, kind, pinned, placementReq) match
                case RoutingDecision.Unavailable(reason) =>
                  maybeRecord(
                    nodeId = "-",
                    durationMs = 0,
                    status = "no-node",
                    error = Some(reason)
                  )
                  IO.pure(Left(RouterFailure.Unavailable(reason)))

                case RoutingDecision.PinnedNodeGone(_) =>
                  sessions.invalidatePin(connectionId)
                  maybeRecord(nodeId = "-", durationMs = 0, status = "pin-lost", error = None)
                  IO.pure(
                    Left(RouterFailure.Unavailable("pinned node disappeared; transaction lost"))
                  )

                case RoutingDecision.Use(nodeId) =>
                  snap.nodes.find(_.nodeId == nodeId) match
                    case None =>
                      IO.pure(Left(RouterFailure.Internal(s"node $nodeId not in snapshot")))
                    case Some(node) =>
                      // Register before the send so the statement is visible (and killable) from
                      // the first wire byte. Known race: a kill between register and attachCancel
                      // evicts the entry but does not interrupt the stream; accepted best-effort.
                      val stmtId =
                        if recordExecution then
                          Some(registry.register(user, poolKey.tenant, poolKey.pool, nodeId, sql))
                        else None
                      // Locality + placement are computed from `refs` (pre-rewrite `sql`, NOT
                      // finalSql: per-principal RLS rewrites would defeat memoization).
                      // placement.record runs for pinned statements too: the statement really
                      // lands on nodeId, so the directory must learn it either way.
                      if recordExecution then
                        val outcome =
                          if !cacheAwareRouting then "flag-off"
                          else if refs.all.isEmpty then "no-refs-fallback"
                          else if !placementEligible then "not-eligible"
                          else
                            placement.record(
                              poolKey,
                              nodeId,
                              refs,
                              routableIds,
                              System.currentTimeMillis(),
                              pinned = pinned.isDefined
                            )
                        routingInstruments.recordDecision(poolKey.tenant, poolKey.pool, outcome)
                        // Pinned statements bypass the scorer, so an over-cap pinned node
                        // is not a cap violation worth reporting.
                        if placementEligible && pinned.isEmpty then
                          val avg = math.max(
                            1.0,
                            routableIds.iterator.map(id => snap.loadOf(id).inFlight).sum.toDouble /
                              routableIds.size
                          )
                          routingInstruments.recordLoadRatio(
                            poolKey.tenant,
                            poolKey.pool,
                            snap.loadOf(nodeId).inFlight / avg
                          )
                        if refs.all.nonEmpty then
                          val obs = locality.observe(poolKey, refs.all, nodeId)
                          routingInstruments.recordLocality(
                            poolKey.tenant,
                            poolKey.pool,
                            obs.newTables,
                            obs.repeatTables,
                            obs.stays,
                            obs.switches
                          )
                      adapter
                        .send(
                          node,
                          wrappedSql,
                          session = None,
                          recordLoad = recordExecution,
                          stampPrelude = prelude
                        )
                        .flatMap {
                          case QuackResponse.Ok(reader, latency, close) =>
                            // Idempotent close: an admin kill and the Flight producer fire the
                            // same close; the second invocation must be a no-op.
                            val closedOnce = new java.util.concurrent.atomic.AtomicBoolean(false)
                            val closeOnce: () => Unit =
                              () => if closedOnce.compareAndSet(false, true) then close()
                            stmtId.foreach(registry.attachCancel(_, closeOnce))
                            val closeAndDeregister: () => Unit = () => {
                              stmtId.foreach(registry.deregister)
                              closeOnce()
                            }
                            sessions.onStatement(connectionId, kind, nodeId)
                            maybeRecord(nodeId, latency, "ok", None)
                            IO.pure(Right(QueryResult(reader, closeAndDeregister, nodeId, latency)))

                          case QuackResponse.Failed(QuackError.Transient(m), latency) =>
                            stmtId.foreach(registry.deregister)
                            maybeRecord(nodeId, latency, "transient", Some(m))
                            if s.txOpen then
                              sessions.invalidatePin(connectionId)
                              IO.pure(
                                Left(
                                  RouterFailure
                                    .Unavailable(s"transient failure inside transaction: $m")
                                )
                              )
                            else
                              // Retry MUST send finalSql (CLS + RLS applied): retrying the
                              // pre-RLS SQL would return rows the row policy should filter.
                              retryOnce(
                                connectionId,
                                user,
                                poolKey,
                                kind,
                                finalSql,
                                exclude = nodeId,
                                recordLoad = recordExecution,
                                prelude = prelude
                              )

                          case QuackResponse.Failed(QuackError.Permanent(m), latency) =>
                            stmtId.foreach(registry.deregister)
                            maybeRecord(nodeId, latency, "permanent", Some(m))
                            IO.pure(Left(classifyPermanent(m)))
                        }
          }

    val startedAtNanos = System.nanoTime()
    // Probes must not emit the module StatementExecuted event either.
    if recordExecution then
      resultIO.flatTap { r =>
        IO(
          events.emit(
            ManagerEvent.StatementExecuted(
              tenant = poolKey.tenant,
              tenantDb = poolKey.tenantDb,
              pool = poolKey.pool,
              kind = kind.toString,
              user = user,
              durationMs = (System.nanoTime() - startedAtNanos) / 1000000L,
              ok = r.isRight
            )
          )
        )
      }
    else resultIO

  /** Prepend `USE <dbName>.<schemaName>;` so unqualified and 2-part names resolve in the remote
    * session. schemaName MUST differ from the catalog name (same-named catalog+schema is ambiguous
    * in DuckDB). The schema itself is pre-created by HealthProbe's first successful probe per node.
    * Skipped for USE / SET / txn control / ATTACH / DETACH so the operator can escape the default.
    */
  private def wrapWithDefaultSchema(
      state: Option[ai.starlake.quack.ondemand.PoolState],
      sql: String
  ): String =
    val trimmed = sql.trim.toUpperCase
    val skip    = trimmed.startsWith("USE ") || trimmed.startsWith("SET ") ||
      trimmed.startsWith("BEGIN") || trimmed.startsWith("COMMIT") ||
      trimmed.startsWith("ROLLBACK") || trimmed.startsWith("ATTACH") ||
      trimmed.startsWith("DETACH")
    state.map(_.metastore) match
      case Some(meta) if !skip =>
        meta.get("dbName").filter(_.nonEmpty) match
          case Some(db) =>
            val schema = meta.get("schemaName").filter(_.nonEmpty).getOrElse("main")
            s"USE $db.$schema; $sql"
          case None => sql
      case _ => sql

  /** Resolve the routing snapshot, waking a suspended (never a disabled) pool first: fire
    * resumePool then poll for a routable node, bounded by resumeHoldTimeout; expiry yields the
    * retryable "pool is resuming" UNAVAILABLE. resumePool errors are swallowed (.attempt):
    * reconcile retries the spawn and the poll either sees a node or times out.
    */
  private def resolveSnapshot(poolKey: PoolKey): IO[Either[RouterFailure, PoolSnapshot]] =
    supervisor.get(poolKey) match
      case None => IO.pure(Left(RouterFailure.NotFound(s"pool not found: $poolKey")))
      case Some(st) if st.suspended && !st.disabled =>
        // IO.defer is load-bearing: without it the snapshot check (and the recursive
        // poll construction) would run eagerly, BEFORE resumePool executes, and the
        // whole chain would pre-resolve to the timeout against the still-empty pool.
        def poll(remaining: FiniteDuration): IO[Either[RouterFailure, PoolSnapshot]] =
          IO.defer {
            supervisor.snapshot(poolKey) match
              case Some(snap) if snap.nodes.exists(n => snap.loadOf(n.nodeId).routable) =>
                IO.pure(Right(snap))
              case _ if remaining <= Duration.Zero =>
                IO.pure(Left(RouterFailure.Unavailable("pool is resuming, retry shortly")))
              case _ =>
                IO.sleep(resumePollInterval) *> poll(remaining - resumePollInterval)
          }
        supervisor.resumePool(poolKey, "query").attempt *> poll(resumeHoldTimeout)
      case Some(_) =>
        supervisor.snapshot(poolKey) match
          case None       => IO.pure(Left(RouterFailure.NotFound(s"pool not found: $poolKey")))
          case Some(snap) => IO.pure(Right(snap))

  private def retryOnce(
      connectionId: String,
      user: String,
      poolKey: PoolKey,
      kind: StatementKind,
      sql: String,
      exclude: String,
      recordLoad: Boolean = true,
      prelude: Option[String] = None
  ): IO[Either[RouterFailure, QueryResult]] =
    supervisor.snapshot(poolKey) match
      case None          => IO.pure(Left(RouterFailure.NotFound(s"pool not found: $poolKey")))
      case Some(snapAll) =>
        val snap = snapAll.copy(nodes = snapAll.nodes.filterNot(_.nodeId == exclude))
        Router.pick(snap, kind, pinned = None) match
          case RoutingDecision.Use(nodeId) =>
            snap.nodes.find(_.nodeId == nodeId) match
              case Some(n) =>
                val wrapped = wrapWithDefaultSchema(supervisor.get(poolKey), sql)
                adapter
                  .send(n, wrapped, None, recordLoad = recordLoad, stampPrelude = prelude)
                  .map {
                    case QuackResponse.Ok(reader, latency, close) =>
                      // Mirror the primary path: registered, killable, gated on recordLoad.
                      val stmtId =
                        if recordLoad then
                          Some(registry.register(user, poolKey.tenant, poolKey.pool, nodeId, sql))
                        else None
                      val closedOnce = new java.util.concurrent.atomic.AtomicBoolean(false)
                      val closeOnce: () => Unit =
                        () => if closedOnce.compareAndSet(false, true) then close()
                      stmtId.foreach(registry.attachCancel(_, closeOnce))
                      val closeAndDeregister: () => Unit = () => {
                        stmtId.foreach(registry.deregister)
                        closeOnce()
                      }
                      // Pin the session on the retry node: a BEGIN that retried onto node B
                      // must have its COMMIT land there too, not be re-routed by load.
                      sessions.onStatement(connectionId, kind, nodeId)
                      Right(QueryResult(reader, closeAndDeregister, nodeId, latency))
                    case QuackResponse.Failed(QuackError.Transient(m), _) =>
                      Left(RouterFailure.Unavailable(s"retry failed (transient): $m"))
                    case QuackResponse.Failed(QuackError.Permanent(m), _) =>
                      Left(classifyPermanent(s"retry failed: $m"))
                  }
              case None =>
                IO.pure(Left(RouterFailure.Unavailable("no fallback node available")))
          case _ =>
            IO.pure(Left(RouterFailure.Unavailable("no fallback node available")))

  /** Map a permanent DuckDB error to a typed failure: missing-object errors become NotFound, the
    * rest BadRequest. The "permanent failure:" prefix is preserved for operators.
    */
  private def classifyPermanent(message: String): RouterFailure =
    val lower    = message.toLowerCase
    val notFound = lower.contains("does not exist") || lower.contains("not found") ||
      (lower.contains("catalog error") && lower.contains("does not"))
    val full = s"permanent failure: $message"
    if notFound then RouterFailure.NotFound(full)
    else RouterFailure.BadRequest(full)
