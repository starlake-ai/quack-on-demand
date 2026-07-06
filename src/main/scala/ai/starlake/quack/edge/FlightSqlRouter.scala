package ai.starlake.quack.edge

import ai.starlake.acl.parser.TableAccess
import ai.starlake.quack.edge.adapter._
import ai.starlake.quack.edge.sql.{Allowed, Denied, StatementValidator, ValidationContext}
import ai.starlake.quack.model.{PoolKey, StatementKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.telemetry.{AuditEvent, EventJournal}
import ai.starlake.quack.route.{Router, RoutingDecision, StatementClassifier}

import ai.starlake.quack.observability.metrics.StatementInstruments
import cats.effect.IO

/** Carries a streaming result back through the router. The caller MUST invoke `close()` once all
  * batches have been consumed. `nodeId` is the Quack node that produced the stream, exposed so the
  * Flight producer can soft-pin a prepared statement's later Execute back to the same node.
  * `durationMs` is the wall-clock latency the adapter measured on the node call; the Flight
  * producer captures it during the Prepare-time probe and threads it onto the matching Execute
  * record as `prepareDurationMs` so the UI can render "57 ms / prep 28 ms" on a single row.
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
    val journal: EventJournal = EventJournal.noop
):

  /** Record a statement outcome into the in-memory history, the metrics instruments, and
    * (selectively) the audit journal.
    *
    * Journal emission rules:
    *   - status "denied" -> family "data-denial", action "sql.denied".
    *   - status "ok" && kind Dml -> family "data-write", action "sql.write".
    *   - status "ok" && kind Ddl -> family "data-write", action "sql.ddl".
    *   - All other statuses (transient, permanent, no-node, ...) -> no journal event.
    *
    * `realm`: "system" when the actor is a superuser principal (user.tenant.isEmpty); "tenant"
    * otherwise. Computed by the caller (maybeRecord) from the session's EffectiveSet so that
    * `record` does not need a reference to it.
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
    if status == "denied" then
      journal.offer(
        AuditEvent(
          java.time.Instant.now(),
          "data-denial",
          user,
          realm,
          Some(poolKey.tenant),
          "sql.denied",
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
          if kind == StatementKind.Ddl then "sql.ddl" else "sql.write",
          None,
          "ok",
          "flightsql",
          Map("sql" -> sql.take(500), "durationMs" -> durationMs.toString)
        )
      )

  def session(connectionId: String) = sessions.get(connectionId)

  /** Run a statement under the named connection. Success path yields a [[QueryResult]] streaming
    * Arrow batches from the chosen Quack node; the caller MUST close it. Failure path is a plain
    * error message.
    *
    * `effectiveSet` carries the RBAC closure pinned on ConnectionContext at handshake time. `None`
    * means no handshake state was attached; PostgresAclValidator denies anything tenant-scoped in
    * that case to fail safe.
    *
    * `preferredNode` is a SOFT pin: when set and still present in the pool snapshot, the router
    * routes here regardless of load. A transaction pin (set by an open BEGIN on this connection)
    * still overrides. When the preferred node has disappeared, fall back to the load-aware pick.
    * Used by the FlightSQL prepared-statement path to keep Prepare + Execute on the same Quack
    * process so DuckDB-side caches stay warm across the two halves of one client `execute()`.
    *
    * `recordExecution=false` suppresses the in-memory history record AND the per-node load /
    * latency bookkeeping ([[ai.starlake.quack.edge.adapter.NodeLoadTracker]]: inFlight,
    * totalServed, ewmaMs, p50/p95/p99). The FlightSQL Prepare-time LIMIT-0 probe uses this so the
    * UI shows ONE history row per user-visible query instead of two, AND so the probe doesn't
    * inflate the dashboard's Total Served / QPS / avg latency / percentiles. The probe's duration
    * is then attached to the matching Execute record via [[prepareDurationMs]] so operators can
    * still see it.
    *
    * `prepareDurationMs` is folded into the resulting [[StatementRecord]] so the UI can render it
    * as subtext under the Execute duration ("57 ms / prep 28 ms").
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
    val s    = sessions.get(connectionId).getOrElse(sessions.open(connectionId, user, poolKey))
    val kind = classifier.classify(sql)
    // ACL / SQL validation gate. Runs before routing so denied
    // statements never touch a Quack node and don't burn capacity.
    // Per-pool dbName/schemaName overrides feed the SQL parser so
    // unqualified table refs resolve to what the Quack node actually
    // sees at execution time.
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
      effectiveSet = effectiveSet
    )
    // No-op variant when the caller is the Prepare-time probe -- we don't want the LIMIT-0
    // wrapper to clutter the operator's history view or skew the per-pool metrics.
    // `deniedRefs` carries the unauthorized TableAccess set from the ACL validator; it is
    // non-empty only on the ACL denial arm and forwarded into the journal event's "denied" key.
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

    validator.validate(ctx) match
      case Denied(reason, deniedRefs) =>
        maybeRecord(
          nodeId = "-",
          durationMs = 0,
          status = "denied",
          error = Some(reason),
          deniedRefs = deniedRefs
        )
        return IO.pure(Left(RouterFailure.AccessDenied(s"access denied: $reason")))
      case Allowed => () // fall through

    // Column-level security: enforce per-column policies before routing.
    val schemaCtx = ai.starlake.quack.edge.cls.SchemaContext(
      defaultDatabase = ctx.defaultDatabase,
      defaultSchema = ctx.defaultSchema
    )
    import cats.effect.unsafe.implicits.global
    val rewrittenSql: String = effectiveSet match
      case None =>
        sql // no RBAC principal bound; rewriter would deny anything tenant-scoped
      case Some(eff) =>
        val t0        = System.nanoTime()
        val outcome   = columnPolicyRewriter.rewrite(sql, kind, eff, schemaCtx).unsafeRunSync()
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000L
        stmtInstruments.recordColumnPolicyRewriteDuration(poolKey.tenant, poolKey.pool, elapsedMs)
        outcome match
          case ai.starlake.quack.edge.cls.ColumnPolicyRewriter.Passthrough =>
            stmtInstruments.recordColumnPolicyRewrite(poolKey.tenant, poolKey.pool, "passthrough")
            sql
          case ai.starlake.quack.edge.cls.ColumnPolicyRewriter.PassthroughParseFailed =>
            // Fail closed: the rewriter only reaches a parse attempt once the principal has
            // column policies, so a parse failure means we cannot prove the masked columns are
            // absent. Forwarding the original SQL would leak them. Deny instead of passthrough.
            // See security-audit-2026-07-02 #5a. (CLS is experimental / default-off.)
            stmtInstruments.recordColumnPolicyRewrite(poolKey.tenant, poolKey.pool, "parse_failed")
            val reason = "column policy rewrite could not parse statement; denied (fail-closed)"
            maybeRecord(nodeId = "-", durationMs = 0, status = "denied", error = Some(reason))
            return IO.pure(Left(RouterFailure.AccessDenied(s"access denied: $reason")))
          case ai.starlake.quack.edge.cls.ColumnPolicyRewriter.Rewritten(s) =>
            stmtInstruments.recordColumnPolicyRewrite(poolKey.tenant, poolKey.pool, "rewritten")
            s
          case ai.starlake.quack.edge.cls.ColumnPolicyRewriter.Denied(reason) =>
            stmtInstruments.recordColumnPolicyRewrite(poolKey.tenant, poolKey.pool, "denied")
            maybeRecord(nodeId = "-", durationMs = 0, status = "denied", error = Some(reason))
            return IO.pure(Left(RouterFailure.AccessDenied(s"access denied: $reason")))
          case ai.starlake.quack.edge.cls.ColumnPolicyRewriter.DeniedUnresolvedTable =>
            // Deny path where the cause was an unresolved table/schema/catalog coordinate
            // rather than a policy match. Same wire error as Denied but tagged separately.
            stmtInstruments
              .recordColumnPolicyRewrite(poolKey.tenant, poolKey.pool, "unresolved_deny")
            val reason = "unresolved table"
            maybeRecord(nodeId = "-", durationMs = 0, status = "denied", error = Some(reason))
            return IO.pure(Left(RouterFailure.AccessDenied(s"access denied: $reason")))

    // Row-level security: filter rows AFTER column masking is decided, but injected at the BASE
    // table so the predicate runs on the true (unmasked) values. Operates on the CLS output so the
    // two rewriters compose (RLS innermost, CLS outermost).
    val finalSqlEither: Either[RouterFailure, String] = effectiveSet match
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
            // Fail closed: the rewriter only reaches a parse attempt once the principal has row
            // policies, so a parse failure means we cannot prove the filtered rows are excluded.
            // Forwarding the original SQL would return unfiltered rows. Deny instead of passthrough.
            // See security-audit-2026-07-02 #5a. (RLS is experimental / default-off.)
            stmtInstruments.recordRowPolicyRewrite(poolKey.tenant, poolKey.pool, "parse_failed")
            Left(
              RouterFailure.AccessDenied(
                "access denied: row policy rewrite could not parse statement; denied (fail-closed)"
              )
            )
          case ai.starlake.quack.edge.rls.RowPolicyRewriter.Rewritten(s) =>
            stmtInstruments.recordRowPolicyRewrite(poolKey.tenant, poolKey.pool, "rewritten")
            Right(s)

    val finalSql: String = finalSqlEither match
      case Right(s) => s
      case Left(f)  =>
        maybeRecord(nodeId = "-", durationMs = 0, status = "denied", error = Some(f.reason))
        return IO.pure(Left(f))

    supervisor.snapshot(poolKey) match
      case None =>
        if s.txOpen then sessions.invalidatePin(connectionId)
        maybeRecord(nodeId = "-", durationMs = 0, status = "no-pool", error = None)
        IO.pure(Left(RouterFailure.NotFound(s"pool not found: $poolKey")))
      case Some(snap) =>
        // Tx pin wins; otherwise honor the soft preferredNode if it still exists in the
        // current snapshot; otherwise None lets Router.pick run its load-aware choice.
        val txPin  = s.pinnedNodeId.filter(_ => s.txOpen)
        val pinned = txPin.orElse(preferredNode.filter(id => snap.nodes.exists(_.nodeId == id)))
        // Each quack_query lands in a fresh DuckDB session on the remote, so
        // an unqualified `SELECT * FROM customer` would 404 - we wrap the user
        // SQL with `USE <dbName>.<dbName>; ...` (matching the spawn script's
        // initial schema) when the pool's metastore advertises a dbName.
        val wrappedSql = wrapWithDefaultSchema(supervisor.get(poolKey), finalSql)
        Router.pick(snap, kind, pinned) match
          case RoutingDecision.Unavailable(reason) =>
            maybeRecord(nodeId = "-", durationMs = 0, status = "no-node", error = Some(reason))
            IO.pure(Left(RouterFailure.Unavailable(reason)))

          case RoutingDecision.PinnedNodeGone(_) =>
            sessions.invalidatePin(connectionId)
            maybeRecord(nodeId = "-", durationMs = 0, status = "pin-lost", error = None)
            IO.pure(Left(RouterFailure.Unavailable("pinned node disappeared; transaction lost")))

          case RoutingDecision.Use(nodeId) =>
            snap.nodes.find(_.nodeId == nodeId) match
              case None =>
                IO.pure(Left(RouterFailure.Internal(s"node $nodeId not in snapshot")))
              case Some(node) =>
                // Register just before the send so the statement appears in the operator's
                // active-statement view from the first byte on the wire. Only when
                // recordExecution is true; the Prepare-time probe is ephemeral and should
                // not appear in the active-statement list.
                // Known race: a kill arriving between register and attachCancel fires the
                // noop handle seeded by register, which evicts the entry from the registry
                // but does not interrupt the stream. Accepted best-effort semantics.
                val stmtId =
                  if recordExecution then
                    Some(registry.register(user, poolKey.tenant, poolKey.pool, nodeId, sql))
                  else None
                adapter
                  .send(node, wrappedSql, session = None, recordLoad = recordExecution)
                  .flatMap {
                    case QuackResponse.Ok(reader, latency, close) =>
                      // Idempotent close: an admin kill fires the same close the Flight
                      // producer will fire later; the second invocation must be a no-op.
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
                            RouterFailure.Unavailable(s"transient failure inside transaction: $m")
                          )
                        )
                      else
                        // Retry MUST send finalSql (RLS-wrapped, CLS-applied), NOT rewrittenSql
                        // (CLS only, pre-RLS) -- otherwise a retried query returns rows the row
                        // policy should have filtered. See security-audit-2026-07-02 #5b.
                        retryOnce(
                          connectionId,
                          user,
                          poolKey,
                          kind,
                          finalSql,
                          exclude = nodeId,
                          recordLoad = recordExecution
                        )

                    case QuackResponse.Failed(QuackError.Permanent(m), latency) =>
                      stmtId.foreach(registry.deregister)
                      maybeRecord(nodeId, latency, "permanent", Some(m))
                      IO.pure(Left(classifyPermanent(m)))
                  }

  /** Prepend `USE <dbName>.<schemaName>;` so the remote DuckDB session lands in the pool's
    * catalog+schema, letting unqualified table names AND 2-part `"schema"."table"` paths resolve.
    * `schemaName` comes from the pool's metastore (defaults to `main`). It MUST differ from the
    * catalog name - same-named catalog+schema is an ambiguous reference in DuckDB, which JDBC
    * clients hit on 2-part identifier resolution.
    *
    * Prerequisite: the schema must already exist on the node. That is the `HealthProbe`'s job - on
    * its first successful probe per node it runs `CREATE SCHEMA IF NOT EXISTS <db>.<schema>`
    * exactly once, so by the time client traffic flows this `USE` always resolves. See `Main.scala`
    * where the probe is constructed.
    *
    * Skipped for explicit USE / SET / BEGIN / COMMIT / ROLLBACK / ATTACH / DETACH so the operator
    * can still escape the default.
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

  private def retryOnce(
      connectionId: String,
      user: String,
      poolKey: PoolKey,
      kind: StatementKind,
      sql: String,
      exclude: String,
      recordLoad: Boolean = true
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
                adapter.send(n, wrapped, None, recordLoad = recordLoad).map {
                  case QuackResponse.Ok(reader, latency, close) =>
                    // Mirror the primary path: register the statement so it appears in
                    // the active-statement view and is killable. Registration is gated on
                    // the same recordLoad flag used by the primary path.
                    val stmtId =
                      if recordLoad then
                        Some(registry.register(user, poolKey.tenant, poolKey.pool, nodeId, sql))
                      else None
                    val closedOnce            = new java.util.concurrent.atomic.AtomicBoolean(false)
                    val closeOnce: () => Unit =
                      () => if closedOnce.compareAndSet(false, true) then close()
                    stmtId.foreach(registry.attachCancel(_, closeOnce))
                    val closeAndDeregister: () => Unit = () => {
                      stmtId.foreach(registry.deregister)
                      closeOnce()
                    }
                    // Pin the session on the retry node so a follow-up
                    // COMMIT/ROLLBACK lands on the same Quack instance.
                    // Without this, a BEGIN that retried onto node B
                    // would have its COMMIT routed by load and likely
                    // hit node A - breaking transaction consistency.
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

  /** Map a permanent DuckDB error envelope to a typed failure. DuckDB stamps user-input errors with
    * prefixes like `Parser Error`, `Binder Error`, `Catalog Error` - we route the ones about
    * missing objects to [[RouterFailure.NotFound]] and the rest to [[RouterFailure.BadRequest]].
    * The `permanent failure:` prefix is preserved in the reason for operators reading metrics /
    * history.
    */
  private def classifyPermanent(message: String): RouterFailure =
    val lower    = message.toLowerCase
    val notFound = lower.contains("does not exist") || lower.contains("not found") ||
      (lower.contains("catalog error") && lower.contains("does not"))
    val full = s"permanent failure: $message"
    if notFound then RouterFailure.NotFound(full)
    else RouterFailure.BadRequest(full)
