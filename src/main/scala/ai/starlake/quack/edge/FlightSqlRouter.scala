package ai.starlake.quack.edge

import java.util.Locale
import ai.starlake.quack.edge.adapter._
import ai.starlake.quack.edge.authz.DecidePurpose
import ai.starlake.quack.edge.sql.{ComputedLocations, RefusedForms}
import ai.starlake.quack.model.{PoolKey, SqlLiterals, StatementKind, TenantDb}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditEvent, EventJournal, StatementEvent}
import ai.starlake.quack.route.{PoolSnapshot, Router, RoutingDecision, StatementClassifier}
import ai.starlake.quack.spi.{ManagerEvent, ManagerEventSink}
import ai.starlake.sql.{SqlCommentStripper, SqlTrivia, TooDeep}

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

/** Transport-neutral sibling of [[QueryResult]] returned by [[FlightSqlRouter.executeWith]]:
  * `value` is whatever the caller's node call produced (an Arrow reader for the Flight edge, raw
  * response bytes plus the node connection for the native Quack relay). The caller MUST invoke
  * `close()` once done; it also deregisters the statement from the kill registry.
  */
final case class Routed[A](value: A, close: () => Unit, nodeId: String, durationMs: Long)

/** Routing core extracted from the Arrow Flight surface so it can be unit-tested. The Flight
  * producer is a thin shell around `execute`.
  */
final class FlightSqlRouter(
    val supervisor: PoolSupervisor,
    val sessions: SessionRegistry,
    val tracker: NodeLoadTracker,
    val adapter: QuackHttpAdapter,
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
    val loadCapFactor: Double = 2.0,
    /** System-catalog filter: narrows a tenant user's catalog listings to the granted objects, and
      * its `narrows` gates the COPY-of-a-listing refusal (D-m1). Required, not defaulted (Phase D
      * decision 31): a default would let a construction site that forgets it narrow nothing. `Main`
      * mounts it always; only a spec isolating the node passes an unmounted one. At the TAIL
      * because `Main` passes the leading arguments positionally, so a mid-list insertion would
      * silently shift `registry` / `journal`; every caller names it.
      */
    val metadataFilterRewriter: ai.starlake.quack.edge.meta.MetadataFilterRewriter,
    /** Protected-write guard: denies a non-SELECT statement whose read side exposes a CLS-masked or
      * RLS-protected table (closes the write-wrapping bypass where a masked or filtered read
      * launders into a CTAS / INSERT ... SELECT), and on every statement of a policy holder the
      * spellings the rewriters cannot read (decision 27). Required, not defaulted (final review
      * I1): a default would let a construction site that forgets it admit every such statement
      * unmasked. Only a spec isolating something else passes [[ProtectedWriteGuard.disabled]]. TAIL
      * param for the same reason as `metadataFilterRewriter`: `Main` passes the leading arguments
      * positionally.
      */
    val protectedWriteGuard: ai.starlake.quack.edge.policy.ProtectedWriteGuard,
    /** SQL admin dialect dispatch: when set, a claimed statement (GRANT/REVOKE/CREATE ROLE/etc, see
      * [[ai.starlake.quack.edge.admin.AdminSqlParser.claims]]) is answered by the manager - or
      * rejected - and is NEVER forwarded to a node. None (the default) leaves every construction
      * site that does not wire it on the pre-dialect routed path. TAIL param for the same reason as
      * `metadataFilterRewriter` / `protectedWriteGuard`: `Main` passes the leading arguments
      * positionally.
      */
    val adminExecutor: Option[ai.starlake.quack.edge.admin.AdminStatementExecutor] = None
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
      /** The statement ran as the manager's system principal (`EffectiveSet.system`, a module's
        * system SQL): its values and their echo in the error are masked
        * ([[ai.starlake.quack.ondemand.telemetry.StatementValueMask]]), since history and audit are
        * shown to the tenant's admins and the manager's own statements may carry platform
        * credentials. Required, so no record site can leave it out.
        */
      systemPrincipal: Boolean,
      realm: String = "tenant",
      prepareDurationMs: Option[Long] = None,
      /** The acting personal-access-token id, when the resolved principal authenticated with one.
        * `None` for session-authenticated callers (and for the raw FlightSQL wire, which has no PAT
        * concept). Threaded into both the statement-history row and the paired audit row (denial or
        * write) so the two always agree on who acted.
        */
      patId: Option[String] = None,
      /** Audit origin of the statement: `"flightsql"` for the Arrow edge, `"quack"` for the native
        * Quack front door. Recorded on the denial and write audit events.
        */
      source: String = "flightsql",
      /** What the manager put into the statement that ran, merged into its denial and write events
        * (the computed file locations it substituted, `locations`). Never a reserved key.
        */
      detail: Map[String, String] = Map.empty
  ): Unit =
    // A claim-shaped statement is redacted before it reaches ANY sink below, whether it was
    // ultimately admitted, denied, or (with the dialect off or adminDispatch=false) simply
    // routed here unanswered: a CREATE/ALTER USER ... PASSWORD statement carries its literal
    // in the raw text, and the routed path is exactly the one an unwired dialect or an
    // adminDispatch=false caller (MCP/preview) falls back to. `error` is redacted alongside
    // `sql`: for a claim-shaped statement admitted by the ACL (dialect off, or
    // adminDispatch=false) and forwarded to a node, DuckDB's own parser error quotes the
    // offending line back verbatim ("LINE 1: CREATE USER alice PASSWORD 'topsecret';"), which
    // would otherwise carry the same literal into history/journal/audit through a different
    // field. A claim-shaped statement's node error is by construction a syntax error quoting
    // the statement, so nothing of diagnostic value survives redacting it anyway.
    val claimed     = ai.starlake.quack.edge.admin.AdminSqlParser.claims(sql)
    val recordedSql =
      if claimed then ai.starlake.quack.edge.admin.AdminSqlParser.RedactedPlaceholder
      else if systemPrincipal then ai.starlake.quack.ondemand.telemetry.StatementValueMask.sql(sql)
      else sql
    val recordedError =
      if claimed then
        error.map(_ => ai.starlake.quack.edge.admin.AdminSqlParser.RedactedPlaceholder)
      else if systemPrincipal then
        error.map(ai.starlake.quack.ondemand.telemetry.StatementValueMask.error(_, sql))
      else error
    history.record(
      StatementRecord(
        ts = java.time.Instant.now(),
        user = user,
        tenant = poolKey.tenant,
        pool = poolKey.pool,
        nodeId = nodeId,
        sql = recordedSql,
        durationMs = durationMs,
        status = status,
        error = recordedError,
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
        recordedSql.take(500),
        durationMs,
        prepareDurationMs,
        status,
        recordedError.map(_.take(500)),
        patId
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
          source,
          // QoD's own audit keys always win: a detail key of a reserved name is dropped, never
          // allowed to overwrite (or forge) the recorded sql / reason.
          detail.removedAll(FlightSqlRouter.ReservedAuditKeys) ++
            Map("sql" -> recordedSql.take(500)) ++
            recordedError.map("reason" -> _.take(500)).toMap,
          patId
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
          source,
          detail.removedAll(FlightSqlRouter.ReservedAuditKeys) ++
            Map("sql" -> recordedSql.take(500), "durationMs" -> durationMs.toString),
          patId
        )
      )

  /** Statement-history parity for the admin dialect: a claimed statement answered by
    * `adminExecutor` never reaches `routedExecute`'s `record` (see `execute` below), so without
    * this the UI's statement list would show nothing for GRANT/REVOKE/CREATE ROLE/etc. Always
    * redacts to the constant placeholder - every admin-dispatched statement is claim-shaped by
    * construction, so the raw text (which may carry a CREATE/ALTER USER ... PASSWORD literal) must
    * never reach `history`. Deliberately narrower than `record`: no AuditEvent journal entry.
    * `SqlWrite`/`SqlDdl`/`SqlDenied` are the routed data-plane path's vocabulary; the admin
    * dialect's own family-specific [[AuditActions]] (RoleCreate, UserCreate, ...), emitted
    * synchronously from `AdminStatementExecutor`'s mutation arms, are the audit trail for these.
    */
  private def recordAdmin(
      user: String,
      poolKey: PoolKey,
      durationMs: Long,
      status: String
  ): Unit =
    history.record(
      StatementRecord(
        ts = java.time.Instant.now(),
        user = user,
        tenant = poolKey.tenant,
        pool = poolKey.pool,
        nodeId = "manager",
        sql = ai.starlake.quack.edge.admin.AdminSqlParser.RedactedPlaceholder,
        durationMs = durationMs,
        status = status,
        error = None
      )
    )
    stmtInstruments.record(poolKey.tenant, poolKey.pool, status, durationMs)

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
      Option(TenantDb.catalogAlias(poolMeta)).filter(_.nonEmpty).map { db =>
        val author = s"tenant:$tenant/user:$user"
        // Reuses the same first-token reader `StatementClassifier` classified `kind` with (see
        // `SqlTrivia.firstToken`), so a leading or interior trivia character that made `kind`
        // Dml/Ddl in the first place (e.g. `INSERT<NBSP>INTO`) cannot also survive into this
        // verb. Once the classifier started seeing through such trivia, this path became
        // reachable for exactly those statements, and this reader had not caught up: an
        // invisible character leaked into the DuckLake commit ledger's verb field (rendering as
        // `flightsql insert<NBSP>into`) -- a ledger-integrity regression, not an injection risk
        // (`SqlLiterals.duckdbLiteral` below escapes it regardless).
        val verb = SqlTrivia.firstToken(sql).toLowerCase(Locale.ROOT)
        s"BEGIN; CALL ducklake_set_commit_message(" +
          s"${SqlLiterals.duckdbLiteral(db)}, " +
          s"${SqlLiterals.duckdbLiteral(author)}, " +
          s"${SqlLiterals.duckdbLiteral(s"flightsql $verb")})"
      }

  def session(connectionId: String) = sessions.get(connectionId)

  /** Run a statement under the named connection; the caller MUST close the [[QueryResult]].
    *
    * `effectiveSet = None` means no handshake state was attached: the statement binds no principal
    * ([[ai.starlake.quack.edge.authz.NodePrincipal.Unbound]]), so no session handle is minted for
    * it and it never reaches a node.
    *
    * `preferredNode` is a SOFT pin (prepared Prepare + Execute on the same node for warm caches; a
    * native Quack session's catalog reads on one node): a transaction pin still overrides, and a
    * node that vanished, is no longer routable, does not carry a role the statement accepts or is
    * at capacity falls back to the load-aware pick (`Router.softPinUsable`).
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
      prepareDurationMs: Option[Long] = None,
      /** The acting PAT id, when the resolved principal authenticated with one. `None` for the raw
        * FlightSQL wire (session-authenticated, no PAT concept) and for every caller that has not
        * been narrowed to one. Threaded straight into the statement-history and paired audit rows;
        * see [[record]]. Also recorded on the ActiveStatementRegistry entry so a token revocation
        * can find and kill the statement (killByPats).
        */
      patId: Option[String] = None,
      /** Gate on top of `adminExecutor` being wired: TAIL param, defaulted `true` so the raw
        * FlightSQL wire (every `FlightProducerImpl` call site) is unaffected. `Main` sets this
        * `false` on the single `fsRouter.execute` call backing `PreviewExecutor` (preview, data
        * diff, restore, undrop, and the MCP `run_sql`/`describe_table` tools all route through that
        * one closure) so a claimed admin statement reaching it still takes the pre-dialect routed
        * path instead of the dialect's authorization, which does not account for PAT attenuation
        * the way the routed ACL path does.
        */
      adminDispatch: Boolean = true,
      /** Audit origin recorded on denial and write events and on the SessionOpened module event:
        * `"flightsql"` for the raw wire, `"quack"` for the native Quack front door. Independent of
        * `edge` below: metering and audit must not move when a caller only wants to tag the OPA
        * input differently.
        */
      source: String = "flightsql",
      /** Read by nothing: every statement is the node's to decide, and the node's OPA input carries
        * client.edge `node`. Kept so callers need no change.
        */
      edge: String = ""
  ): IO[Either[RouterFailure, QueryResult]] =
    adminExecutor match
      case Some(exec) if adminDispatch && ai.starlake.quack.edge.admin.AdminSqlParser.claims(sql) =>
        // Claimed admin statements are answered by the manager (or rejected) and are
        // never forwarded to a node - the fail-closed contract of the admin dialect.
        // recordExecution follows the same probe-suppression contract as the routed path
        // (Prepare-time DESCRIBE probes must not show up in the UI's statement list).
        if ai.starlake.quack.edge.admin.AdminSqlParser.isUndrop(sql) &&
          sessions.get(connectionId).exists(_.txOpen)
        then IO.pure(Left(FlightSqlRouter.UndropInTransaction))
        else if !recordExecution then exec.execute(user, poolKey, sql, effectiveSet, connectionId)
        else
          IO.monotonic.flatMap { t0 =>
            exec.execute(user, poolKey, sql, effectiveSet, connectionId).flatMap { result =>
              IO.monotonic.map { t1 =>
                recordAdmin(
                  user,
                  poolKey,
                  (t1 - t0).toMillis,
                  status = if result.isRight then "ok" else "denied"
                )
                result
              }
            }
          }
      case _ =>
        executeWith(
          connectionId,
          user,
          poolKey,
          sql,
          effectiveSet,
          adapterSend,
          source = source,
          edge = edge,
          preferredNode = preferredNode,
          recordExecution = recordExecution,
          prepareDurationMs = prepareDurationMs,
          patId = patId,
          evaluate = Some(adapterEvaluate),
          purpose = DecidePurpose.Live
        ).map(_.map(r => QueryResult(r.value, r.close, r.nodeId, r.durationMs)))

  /** One statement as the session `effectiveSet` (its claims, JWT roles and groups), on a
    * connection of its own (`connectionId`), opened before it without a SessionOpened event (it is
    * part of the caller's own session, already metered) and closed after it, whatever the outcome:
    * UNDROP TABLE's CTAS (review N2). Recorded like any statement; never the admin dialect.
    */
  def executeOneShot(
      connectionId: String,
      user: String,
      poolKey: PoolKey,
      sql: String,
      effectiveSet: EffectiveSet
  ): IO[Either[RouterFailure, QueryResult]] =
    // A per-call nonce (review 5 R4): two concurrent calls on one connection get a session each, so
    // the first one's close never drops the second's. Deferred: execute opens (and meters) its
    // session while it is built, before this one exists.
    // The nonce is drawn per run of the returned IO, never once per call to this method.
    IO(s"$connectionId#${java.util.UUID.randomUUID()}").flatMap { oneShot =>
      IO(sessions.open(oneShot, user, poolKey)) *>
        IO.defer(
          execute(
            oneShot,
            user,
            poolKey,
            sql,
            effectiveSet = Some(effectiveSet),
            recordExecution = true,
            adminDispatch = false
          )
        ).guarantee(IO(sessions.close(oneShot)))
    }

  /** The Arrow transport as a [[FlightSqlRouter.NodeSend]]: what [[execute]] runs the pipeline
    * around. `session` is always None on the outbound wire.
    */
  private val adapterSend: FlightSqlRouter.NodeSend[org.apache.arrow.vector.ipc.ArrowReader] =
    (node, wrappedSql, prelude, recordLoad, principal) =>
      adapter
        .send(
          node,
          wrappedSql,
          principal,
          session = None,
          recordLoad = recordLoad,
          stampPrelude = prelude
        )
        .map(NodeOutcome.fromQuackResponse)

  /** The Arrow transport reading a small result back for the manager ([[NodeRows]]): the statement
    * runs as [[adapterSend]] runs it, its rows are read and its reader closed inside the call.
    */
  private val adapterEvaluate: FlightSqlRouter.NodeSend[NodeRows] =
    (node, wrappedSql, prelude, recordLoad, principal) =>
      adapter
        .send(node, wrappedSql, principal, session = None, recordLoad = recordLoad)
        .flatMap {
          case QuackResponse.Ok(rows, latency, close) =>
            IO.blocking {
              try NodeRows.read(rows)
              finally close()
            }.map {
              case Right(read) => NodeOutcome.Ok(read, latency, () => ())
              case Left(why)   => NodeOutcome.Permanent(why, latency)
            }
          case QuackResponse.Failed(QuackError.Transient(m), latency) =>
            IO.pure(NodeOutcome.Transient(m, latency))
          case QuackResponse.Failed(QuackError.Permanent(m), latency) =>
            IO.pure(NodeOutcome.Permanent(m, latency))
        }

  /** The OPA dry run's run (decision 25): `sql` through [[executeWith]] for `effectiveSet`'s
    * principal on a node of `poolKey`, as a live statement runs (the USE prelude, the policy
    * rewriters, the node's parse screen and describer), under a decide context marked
    * [[DecidePurpose.DryRun]]: the decision endpoint never answers its plans an allow (but for the
    * session's own USE prelude), so it runs nothing. That holds only for a tenant user: a node
    * admits a superuser's or the system's statements without asking the decision endpoint, so the
    * context would not stop them, and a dry run for either is refused here
    * ([[FlightSqlRouter.DryRunPrincipalRequired]]) before any snapshot, handle or node call, as
    * [[executeWith]] refuses it too. No history row, no load booked, no computed location
    * evaluated, no pool woken. Answers the router's failure (None only when the node ran the
    * statement, which a dry run allows for a USE of the session's own path alone) and the nonce of
    * every handle minted for it, in mint order.
    */
  def dryRun(
      user: String,
      poolKey: PoolKey,
      sql: String,
      effectiveSet: EffectiveSet
  ): IO[(Option[RouterFailure], List[String])] =
    if !FlightSqlRouter.dryRunnable(Some(effectiveSet)) then
      IO.pure((Some(FlightSqlRouter.dryRunRefused), Nil))
    else dryRunAsTenantUser(user, poolKey, sql, effectiveSet)

  /** [[dryRun]] for a tenant user, the one principal the decide context binds. */
  private def dryRunAsTenantUser(
      user: String,
      poolKey: PoolKey,
      sql: String,
      effectiveSet: EffectiveSet
  ): IO[(Option[RouterFailure], List[String])] =
    val connectionId = s"opa-dry-run-${java.util.UUID.randomUUID()}"
    val nonces       = new java.util.concurrent.ConcurrentLinkedQueue[String]()
    val send: FlightSqlRouter.NodeSend[org.apache.arrow.vector.ipc.ArrowReader] =
      (node, wrappedSql, prelude, recordLoad, principal) =>
        adapter
          .send(
            node,
            wrappedSql,
            principal,
            session = None,
            recordLoad = recordLoad,
            stampPrelude = prelude,
            onMint = m => {
              nonces.add(m.nonce)
              ()
            }
          )
          .map(NodeOutcome.fromQuackResponse)
    executeWith(
      connectionId,
      user,
      poolKey,
      sql,
      Some(effectiveSet),
      send,
      source = "dry-run",
      recordExecution = false,
      purpose = DecidePurpose.DryRun
    ).map { routed =>
      routed.foreach(_.close())
      (routed.left.toOption, nonces.toArray(Array.empty[String]).toList)
    }.guarantee(IO(sessions.close(connectionId)))

  /** Run a statement through the whole pipeline (session, client-scan refusal, protected-write
    * guard, CLS, RLS, metadata filter, pool resume, pin and placement, kill registry, history and
    * audit, transient retry) around a caller-supplied node call. This is the transport-neutral
    * core: [[execute]] passes the Arrow adapter, the native Quack front door passes a relay that
    * opens its own node connection and returns the node's raw response bytes.
    *
    * `send` receives the routed node, the fully rewritten and `USE`-prefixed SQL, the author
    * stamping prelude when one applies, whether load should be booked (false for probes), and the
    * principal the statement runs for. The admin dialect is NOT dispatched here: it needs an Arrow
    * renderer, so callers that want it go through [[execute]].
    *
    * `source` is the audit origin recorded on denial and write events and on the SessionOpened
    * module event (`"flightsql"` or `"quack"`). `edge` is read by nothing (see `execute`).
    *
    * `clientDataScans` is set by the native Quack front door alone, the one caller that relays
    * SEND_DATA on the statement's node connection; it lets a DuckDB 2.0 client's
    * scan_data_from_quack_client INSERT past [[FlightSqlRouter.clientDataScanRefusal]] and the
    * protected-write guard. It is deliberately not derived from `source` or `edge`. Every other
    * caller leaves it false.
    */
  def executeWith[A](
      connectionId: String,
      user: String,
      poolKey: PoolKey,
      sql: String,
      effectiveSet: Option[EffectiveSet],
      send: FlightSqlRouter.NodeSend[A],
      source: String = "flightsql",
      preferredNode: Option[String] = None,
      recordExecution: Boolean = true,
      prepareDurationMs: Option[Long] = None,
      patId: Option[String] = None,
      edge: String = "",
      clientDataScans: Boolean = false,
      /** Honor `preferredNode` even when that node is at its `maxConcurrent` cap (it must still be
        * routable and role-compatible). Set by the native front door for its catalog reads, whose
        * two listings are only coherent on one node; see `Router.softPinUsable`.
        */
      softPinOverCapacity: Boolean = false,
      /** How this front door runs a statement for the principal and reads its small result back
        * itself, on its own node connection (inside a client transaction, the transaction's):
        * SUMMARIZE learns its columns this way ([[RefusedForms]]). None (the default) leaves such a
        * statement unchanged, so the node refuses it as before.
        */
      evaluate: Option[FlightSqlRouter.NodeSend[NodeRows]] = None,
      /** Set by a front door whose node connection carries the client's transaction (the native
        * one): inside a client transaction a computed file location fails hard instead of falling
        * back to the unchanged statement (CONTRACT Addendum 4 items 25-25b). The evaluation runs on
        * the transaction's own link (`evaluate`); its failure is answered as its own, prefixed
        * "computing the file location: " (a failure the node answers has aborted the transaction,
        * as DuckDB does; a refusal by the gateway leaves it open), and a value no literal may stand
        * for aborts the transaction through this fence. The router also fences through it when the
        * transaction's pinned node has left the pool (Phase C review D-m8): the front door's link
        * is gone with it, and its next statement must not run elsewhere, outside the transaction.
        * None (FlightSQL, whose statements share no node connection): a failing evaluation leaves
        * the statement unchanged, as outside a transaction, and a lost pin only fails the
        * statement.
        */
      failInTransaction: Option[FlightSqlRouter.TransactionFence] = None,
      /** Why the statement's decisions are taken (decision 25): `DryRun` for the OPA dry run
        * ([[dryRun]]), whose plan verdicts are never an allow, so it runs nothing, and which never
        * wakes a suspended pool; `Live` everywhere else. Required: no caller gets either by
        * default. A dry run for anyone but a tenant user is refused before anything else
        * ([[FlightSqlRouter.DryRunPrincipalRequired]]): a node admits a superuser's or the system's
        * statements without asking the decision endpoint, so it would run.
        */
      purpose: DecidePurpose
  ): IO[Either[RouterFailure, Routed[A]]] =
    if purpose == DecidePurpose.DryRun && !FlightSqlRouter.dryRunnable(effectiveSet) then
      IO.pure(Left(FlightSqlRouter.dryRunRefused))
    else
      executeFor(
        connectionId,
        user,
        poolKey,
        sql,
        effectiveSet,
        send,
        source,
        preferredNode,
        recordExecution,
        prepareDurationMs,
        patId,
        edge,
        clientDataScans,
        softPinOverCapacity,
        evaluate,
        failInTransaction,
        purpose
      )

  /** [[executeWith]] past its dry-run principal check. */
  private def executeFor[A](
      connectionId: String,
      user: String,
      poolKey: PoolKey,
      sql: String,
      effectiveSet: Option[EffectiveSet],
      send: FlightSqlRouter.NodeSend[A],
      source: String,
      preferredNode: Option[String],
      recordExecution: Boolean,
      prepareDurationMs: Option[Long],
      patId: Option[String],
      edge: String,
      clientDataScans: Boolean,
      softPinOverCapacity: Boolean,
      evaluate: Option[FlightSqlRouter.NodeSend[NodeRows]],
      failInTransaction: Option[FlightSqlRouter.TransactionFence],
      purpose: DecidePurpose
  ): IO[Either[RouterFailure, Routed[A]]] =
    def run(
        statement: String,
        pin: Option[String],
        detail: Map[String, String] = Map.empty
    ): IO[Either[RouterFailure, Routed[A]]] =
      executeGuarded(
        connectionId,
        user,
        poolKey,
        statement,
        sql,
        effectiveSet,
        send,
        source,
        pin,
        recordExecution,
        prepareDurationMs,
        patId,
        edge,
        clientDataScans,
        softPinOverCapacity,
        purpose,
        detail = detail,
        pinLost = failInTransaction
      )
    // The client's statement answered with `failure` before anything ran it: its history row.
    def refused(failure: RouterFailure): IO[Either[RouterFailure, Routed[A]]] =
      IO {
        if recordExecution then
          record(
            user,
            poolKey,
            nodeId = "-",
            sql,
            durationMs = 0,
            FlightSqlRouter.failureStatus(failure),
            Some(failure.reason),
            classifier.classify(sql),
            systemPrincipal = effectiveSet.exists(_.system),
            realm = "tenant",
            patId = patId,
            source = source
          )
        Left(failure)
      }
    // Task 19: a statement the node refuses whose answer an ordinary statement gets, for a tenant
    // principal only (a superuser's statement runs as written). The rewrite is what runs from here
    // on, through every gateway step, as if the client had written it; history records the client's.
    val form =
      if !FlightSqlRouter.rewritable(effectiveSet) then None
      else
        TooDeep.orElse(Option.empty[RefusedForms.Form])(
          RefusedForms.recognize(
            sql,
            supervisor.get(poolKey).flatMap(FlightSqlRouter.sessionPath),
            name => ai.starlake.quack.edge.authz.SettingClasses.of(name).isDefined,
            attachedCatalogs = attachedCatalogsOf(poolKey)
          )
        )
    form match
      case None =>
        // Task 19 part D: a file location the statement computes, evaluated by the node as the
        // principal and put in its place as a literal, which the node then judges.
        val locations =
          if !FlightSqlRouter.rewritable(effectiveSet) || evaluate.isEmpty then Nil
          else TooDeep.orElse(List.empty[ComputedLocations.Location])(ComputedLocations.find(sql))
        val fence = failInTransaction.filter(_ => sessions.get(connectionId).exists(_.txOpen))
        (evaluate, locations) match
          case (Some(ev), _ :: _) =>
            openSession(connectionId, user, poolKey, recordExecution, source)
            literals(
              connectionId,
              user,
              poolKey,
              locations,
              effectiveSet,
              ev,
              source,
              edge,
              patId,
              purpose,
              failInTransaction
            )
              .flatMap {
                case Right(values) =>
                  run(
                    ComputedLocations.substitute(sql, locations.zip(values)),
                    preferredNode,
                    Map("locations" -> values.mkString(", ").take(500))
                  )
                case Left(failure) =>
                  (fence, failure) match
                    // A killed evaluation stops the statement: nothing else runs for it.
                    case (_, FlightSqlRouter.LocationFailure.Evaluation(f))
                        if f.reason == FlightSqlRouter.KilledReason =>
                      refused(f)
                    // Outside a native transaction: the node refuses the unchanged statement.
                    case (None, _) => run(sql, preferredNode)
                    case (Some(_), FlightSqlRouter.LocationFailure.Evaluation(f)) =>
                      refused(FlightSqlRouter.computingLocation(f))
                    case (Some(abort), FlightSqlRouter.LocationFailure.Rejected(why)) =>
                      val answer = s"transaction aborted: computing the file location: $why; " +
                        "ROLLBACK to continue"
                      abort(answer) *> refused(RouterFailure.BadRequest(answer))
              }
          case _ => run(sql, preferredNode)
      case Some(RefusedForms.Form.Rewritten(other))           => run(other, preferredNode)
      case Some(RefusedForms.Form.Summarize(columns, target)) =>
        evaluate match
          case None     => run(sql, preferredNode)
          case Some(ev) =>
            // The columns are read first, judged like any statement; the summary then runs on the
            // node that answered (a soft pin), so both see the same catalog.
            openSession(connectionId, user, poolKey, recordExecution, source)
            readBack(
              connectionId,
              user,
              poolKey,
              columns,
              effectiveSet,
              ev,
              source,
              edge,
              patId,
              purpose,
              failInTransaction
            )
              .flatMap {
                case Left(failure)         => refused(failure)
                case Right((rows, nodeId)) =>
                  FlightSqlRouter.summarizeColumns(rows) match
                    case Some(cols) => run(RefusedForms.summarizeSql(target, cols), Some(nodeId))
                    case None       => run(sql, preferredNode)
              }

  /** The literal of each computed location ([[ComputedLocations]]), each evaluated in turn by the
    * node as the principal ([[readBack]]), or why the first one failed: its evaluation refused or
    * failing, or a value no literal may stand for ([[executeWith]] decides what that answers).
    */
  private def literals(
      connectionId: String,
      user: String,
      poolKey: PoolKey,
      locations: List[ComputedLocations.Location],
      effectiveSet: Option[EffectiveSet],
      evaluate: FlightSqlRouter.NodeSend[NodeRows],
      source: String,
      edge: String,
      patId: Option[String],
      purpose: DecidePurpose,
      pinLost: Option[FlightSqlRouter.TransactionFence]
  ): IO[Either[FlightSqlRouter.LocationFailure, List[String]]] =
    val none: Either[FlightSqlRouter.LocationFailure, List[String]] = Right(Nil)
    locations.foldLeft(IO.pure(none)) { (done, location) =>
      done.flatMap {
        case Left(failure) => IO.pure(Left(failure))
        case Right(values) =>
          readBack(
            connectionId,
            user,
            poolKey,
            ComputedLocations.evaluation(location),
            effectiveSet,
            evaluate,
            source,
            edge,
            patId,
            purpose,
            pinLost
          ).map {
            case Right((rows, _)) =>
              ComputedLocations
                .literal(location, rows)
                .map(values :+ _)
                .left
                .map(FlightSqlRouter.LocationFailure.Rejected(_))
            case Left(failure) => Left(FlightSqlRouter.LocationFailure.Evaluation(failure))
          }
      }
    }

  /** Run `statement` for `effectiveSet`'s principal through the whole pipeline and read its small
    * result back ([[NodeRows]]) instead of handing it to a client, with no history row, no load
    * booked and no rewrite of its own, but in the kill registry while it runs (`internal`):
    * SUMMARIZE's column listing, a computed file location's value. The node that answered comes
    * with the rows.
    */
  private def readBack(
      connectionId: String,
      user: String,
      poolKey: PoolKey,
      statement: String,
      effectiveSet: Option[EffectiveSet],
      evaluate: FlightSqlRouter.NodeSend[NodeRows],
      source: String,
      edge: String,
      patId: Option[String],
      purpose: DecidePurpose,
      pinLost: Option[FlightSqlRouter.TransactionFence]
  ): IO[Either[RouterFailure, (NodeRows, String)]] =
    executeGuarded[NodeRows](
      connectionId,
      user,
      poolKey,
      statement,
      statement,
      effectiveSet,
      evaluate,
      source,
      preferredNode = None,
      recordExecution = false,
      prepareDurationMs = None,
      patId = patId,
      edge = edge,
      clientDataScans = false,
      softPinOverCapacity = false,
      purpose = purpose,
      internal = true,
      pinLost = pinLost
    ).flatMap {
      case Left(failure) => IO.pure(Left(failure))
      case Right(routed) => IO(routed.close()).as(Right((routed.value, routed.nodeId)))
    }

  private def executeGuarded[A](
      connectionId: String,
      user: String,
      poolKey: PoolKey,
      sql: String,
      clientSql: String,
      effectiveSet: Option[EffectiveSet],
      send: FlightSqlRouter.NodeSend[A],
      source: String,
      preferredNode: Option[String],
      recordExecution: Boolean,
      prepareDurationMs: Option[Long],
      patId: Option[String],
      edge: String,
      clientDataScans: Boolean,
      softPinOverCapacity: Boolean,
      purpose: DecidePurpose,
      internal: Boolean = false,
      detail: Map[String, String] = Map.empty,
      pinLost: Option[FlightSqlRouter.TransactionFence]
  ): IO[Either[RouterFailure, Routed[A]]] =
    // Backstop behind the analysis steps' own TooDeep guards: the screens, gates and rewriters run
    // synchronously here, before any IO, so a walk none of them guards that overflows is denied
    // instead of escaping as a StackOverflowError, which would halt the manager.
    TooDeep.orElse(
      tooDeepDenied(user, poolKey, clientSql, effectiveSet, recordExecution, patId, source)
    )(
      executeUnguarded(
        connectionId,
        user,
        poolKey,
        sql,
        clientSql,
        effectiveSet,
        send,
        source,
        preferredNode,
        recordExecution,
        prepareDurationMs,
        patId,
        edge,
        clientDataScans,
        softPinOverCapacity,
        purpose,
        internal,
        detail,
        pinLost
      )
    )

  /** The catalog and schema the SQL parser resolves `poolKey`'s unqualified names against: the
    * per-pool dbName/schemaName overrides, so they resolve to what the node actually sees at
    * execution time.
    */
  private[edge] def defaultsOf(poolKey: PoolKey): (Option[String], Option[String]) =
    val maybeState = supervisor.get(poolKey)
    val poolMeta   = maybeState.map(_.metastore).getOrElse(Map.empty)
    val kindWire   = maybeState.map(_.kindWire).getOrElse("ducklake")
    val perKindDb  = kindWire match
      case "ducklake" | "duckdb-file" =>
        Option(TenantDb.catalogAlias(poolMeta)).filter(_.nonEmpty)
      case "memory" => Some("memory")
      case _        => None
    val perKindSchema = kindWire match
      case "ducklake" | "duckdb-file" => poolMeta.get("schemaName").filter(_.nonEmpty)
      case "memory"                   => Some("main")
      case _                          => None
    (
      maybeState.flatMap(_.defaultDatabase).orElse(perKindDb),
      maybeState.flatMap(_.defaultSchema).orElse(perKindSchema)
    )

  /** The router session of `connectionId`, opened (and announced, unless `recordExecution` is
    * false: probes emit nothing) on its first statement.
    */
  private def openSession(
      connectionId: String,
      user: String,
      poolKey: PoolKey,
      recordExecution: Boolean,
      source: String
  ): Session =
    sessions.get(connectionId).getOrElse {
      val opened = sessions.open(connectionId, user, poolKey)
      if recordExecution then events.emit(ManagerEvent.SessionOpened(poolKey.tenant, user, source))
      opened
    }

  private def tooDeepDenied[A](
      user: String,
      poolKey: PoolKey,
      sql: String,
      effectiveSet: Option[EffectiveSet],
      recordExecution: Boolean,
      patId: Option[String],
      source: String
  ): IO[Either[RouterFailure, Routed[A]]] =
    if recordExecution then
      record(
        user,
        poolKey,
        nodeId = "-",
        sql,
        durationMs = 0,
        status = "denied",
        error = Some(TooDeep.Reason),
        kind = TooDeep.orElse(StatementKind.Other)(classifier.classify(sql)),
        systemPrincipal = effectiveSet.exists(_.system),
        realm = if effectiveSet.exists(_.user.tenant.isEmpty) then "system" else "tenant",
        patId = patId,
        source = source
      )
    IO.pure(Left(RouterFailure.AccessDenied(s"access denied: ${TooDeep.Reason}")))

  /** `sql` is the statement that runs, `clientSql` the one the client sent (they differ when a
    * refused form was rewritten): history, audit and the kill registry show the client's.
    * `internal`: a statement the manager runs for the client ([[readBack]]), which is in the kill
    * registry while it runs although it records nothing. `detail`: merged into the audit events.
    * `pinLost`: the front door's fence for its client transaction, invoked when the transaction's
    * pinned node has left the pool.
    */
  private def executeUnguarded[A](
      connectionId: String,
      user: String,
      poolKey: PoolKey,
      sql: String,
      clientSql: String,
      effectiveSet: Option[EffectiveSet],
      send: FlightSqlRouter.NodeSend[A],
      source: String,
      preferredNode: Option[String],
      recordExecution: Boolean,
      prepareDurationMs: Option[Long],
      patId: Option[String],
      edge: String,
      clientDataScans: Boolean,
      softPinOverCapacity: Boolean,
      purpose: DecidePurpose,
      internal: Boolean,
      detail: Map[String, String],
      pinLost: Option[FlightSqlRouter.TransactionFence]
  ): IO[Either[RouterFailure, Routed[A]]] =
    // Probes (recordExecution=false) must not emit, matching every other telemetry surface.
    val s    = openSession(connectionId, user, poolKey, recordExecution, source)
    val kind = classifier.classify(sql)
    // With the session's authorization context: the handle digests it and the decision endpoint
    // judges the statement's plan with it (MintedHandles).
    val principal = ai.starlake.quack.edge.authz.NodePrincipal.of(effectiveSet, poolKey, purpose)
    // The text the active-statement registry shows (ActiveStatementHandlers.list, to the tenant's
    // admins): a system statement's values masked, as history and audit mask them (review M1).
    val activeSql =
      if effectiveSet.exists(_.system) then
        ai.starlake.quack.ondemand.telemetry.StatementValueMask.sql(clientSql)
      else clientSql
    // Per-pool dbName/schemaName overrides feed the SQL parser so unqualified
    // table refs resolve to what the node actually sees at execution time.
    val maybeState               = supervisor.get(poolKey)
    val poolMeta                 = maybeState.map(_.metastore).getOrElse(Map.empty)
    val kindWire                 = maybeState.map(_.kindWire).getOrElse("ducklake")
    val (defaultDb, defaultSchm) = defaultsOf(poolKey)

    val attachedCatalogs = attachedCatalogsOf(poolKey)
    // No-op for probes.
    def maybeRecord(
        nodeId: String,
        durationMs: Long,
        status: String,
        error: Option[String],
        prepMs: Option[Long] = prepareDurationMs
    ): Unit =
      if recordExecution then
        val realm = if effectiveSet.exists(_.user.tenant.isEmpty) then "system" else "tenant"
        record(
          user,
          poolKey,
          nodeId,
          clientSql,
          durationMs,
          status,
          error,
          kind,
          effectiveSet.exists(_.system),
          realm,
          prepMs,
          patId,
          source,
          detail
        )

    // Table access, functions, settings, files and OPA are the node's to decide (spec decision 29).
    // What the gateway keeps: a client data scan only the native front door can feed, then the
    // policy steps (protected-write guard, column and row policy rewriters, metadata filter), which
    // read the statement with the gateway's parser until part 2.
    def clientScan(): Either[RouterFailure, Unit] =
      FlightSqlRouter.clientDataScanRefusal(sql, clientDataScans) match
        case None         => Right(())
        case Some(reason) =>
          maybeRecord(nodeId = "-", durationMs = 0, status = "denied", error = Some(reason))
          Left(RouterFailure.AccessDenied(s"access denied: $reason"))

    // Column-level security: enforce per-column policies before routing.
    val schemaCtx = ai.starlake.quack.edge.cls.SchemaContext(
      defaultDatabase = defaultDb,
      defaultSchema = defaultSchm,
      attachedCatalogs = attachedCatalogs
    )

    // Protected-write guard: runs BEFORE the CLS/RLS rewriters. A non-SELECT statement whose read
    // side exposes a masked or row-filtered table is denied outright (the rewriters wrap SELECTs,
    // not the read half of a write, so without this step a CTAS / INSERT ... SELECT would launder
    // protected values). On every kind it also denies what the rewriters cannot read as DuckDB
    // does (ProtectedWriteGuard's scaladoc). effectiveSet = None skips: no RBAC principal is bound,
    // so no policy applies, and no handle can be minted for the statement (NodePrincipal.Unbound).
    def protectedWrite(): Either[RouterFailure, Unit] = effectiveSet match
      case None      => Right(())
      case Some(eff) =>
        val g0        = System.nanoTime()
        val outcome   = protectedWriteGuard.check(sql, kind, eff, schemaCtx, clientDataScans)
        val elapsedMs = (System.nanoTime() - g0) / 1_000_000L
        stmtInstruments.recordProtectedWriteDuration(poolKey.tenant, poolKey.pool, elapsedMs)
        outcome match
          case ai.starlake.quack.edge.policy.GuardOutcome.Allow =>
            stmtInstruments.recordProtectedWrite(poolKey.tenant, poolKey.pool, "allow")
            Right(())
          case ai.starlake.quack.edge.policy.GuardOutcome.Deny(reason) =>
            stmtInstruments.recordProtectedWrite(poolKey.tenant, poolKey.pool, "deny")
            maybeRecord(nodeId = "-", durationMs = 0, status = "denied", error = Some(reason))
            Left(RouterFailure.AccessDenied(reason))
    import cats.effect.unsafe.implicits.global
    // Shared CLS deny arm: instrument tag, journal, wire error.
    def clsDenied(tag: String, reason: String): Either[RouterFailure, String] =
      stmtInstruments.recordColumnPolicyRewrite(poolKey.tenant, poolKey.pool, tag)
      maybeRecord(nodeId = "-", durationMs = 0, status = "denied", error = Some(reason))
      Left(RouterFailure.AccessDenied(s"access denied: $reason"))

    def clsRewritten(): Either[RouterFailure, String] = effectiveSet match
      case None =>
        Right(sql) // no RBAC principal bound: no policy applies
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
          defaultDatabase = defaultDb,
          defaultSchema = defaultSchm
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
          case ai.starlake.quack.edge.rls.RowPolicyRewriter.Refused(reason) =>
            // Fail closed with the rewriter's fixed reason (never the SQL): the statement holds a
            // shape the row filter cannot apply to, and the caller can rephrase it.
            stmtInstruments.recordRowPolicyRewrite(poolKey.tenant, poolKey.pool, "refused")
            val f = RouterFailure.AccessDenied(s"access denied: $reason")
            maybeRecord(nodeId = "-", durationMs = 0, status = "denied", error = Some(f.reason))
            Left(f)
          case ai.starlake.quack.edge.rls.RowPolicyRewriter.Failed(_) =>
            // Fail closed: a stored row policy could not be applied to this statement, so
            // forwarding it would return unfiltered rows. See RowPolicyRewriter.Failed.
            stmtInstruments.recordRowPolicyRewrite(poolKey.tenant, poolKey.pool, "failed")
            val f = RouterFailure.AccessDenied(
              "row policy failed to apply - contact an administrator"
            )
            maybeRecord(nodeId = "-", durationMs = 0, status = "denied", error = Some(f.reason))
            Left(f)

    // System-catalog filtering runs LAST, on the CLS+RLS output: its substitutions inject
    // derived tables the other two rewriters have no policy for, and running it first would
    // hand them SQL that no longer names the tables their policies key off.
    def metadataFiltered(rewrittenSql: String): Either[RouterFailure, String] =
      effectiveSet match
        // No RBAC principal bound: nothing to filter for.
        case None      => Right(rewrittenSql)
        case Some(eff) =>
          // The filter cannot read a COPY: one it narrows a listing for is refused unless the
          // lone COPY's stand-in would pass through it unchanged (copyListingRefusal).
          FlightSqlRouter.copyListingRefusal(
            sql,
            metadataFilterRewriter.narrows(eff, schemaCtx),
            standIn => metadataFilterRewriter.rewrite(standIn, eff, schemaCtx)
          ) match
            case Some(reason) =>
              stmtInstruments.recordMetadataFilter(poolKey.tenant, poolKey.pool, "denied")
              maybeRecord(nodeId = "-", durationMs = 0, status = "denied", error = Some(reason))
              Left(RouterFailure.AccessDenied(s"access denied: $reason"))
            case None => metadataFilteredStatement(rewrittenSql, eff)

    def metadataFilteredStatement(
        rewrittenSql: String,
        eff: EffectiveSet
    ): Either[RouterFailure, String] =
      val m0        = System.nanoTime()
      val outcome   = metadataFilterRewriter.rewrite(rewrittenSql, eff, schemaCtx)
      val elapsedMs = (System.nanoTime() - m0) / 1_000_000L
      stmtInstruments.recordMetadataFilterDuration(poolKey.tenant, poolKey.pool, elapsedMs)
      outcome match
        case ai.starlake.quack.edge.meta.MetadataFilterOutcome.Passthrough =>
          stmtInstruments.recordMetadataFilter(poolKey.tenant, poolKey.pool, "passthrough")
          Right(rewrittenSql)
        case ai.starlake.quack.edge.meta.MetadataFilterOutcome.Rewritten(s) =>
          stmtInstruments.recordMetadataFilter(poolKey.tenant, poolKey.pool, "rewritten")
          Right(s)
        case ai.starlake.quack.edge.meta.MetadataFilterOutcome.Denied(reason) =>
          stmtInstruments.recordMetadataFilter(poolKey.tenant, poolKey.pool, "denied")
          val f = RouterFailure.AccessDenied(s"access denied: $reason")
          maybeRecord(nodeId = "-", durationMs = 0, status = "denied", error = Some(reason))
          Left(f)

    // Defense in depth behind the three rewriters: each prints a NEW statement, and the node runs
    // that one. A rewrite that reaches a table the original did not (the column policy rewrite
    // once schema-qualified a CTE reference, turning it into a read of the real table of that
    // name) would read past the policy that covers it. So the rewritten statement must reach
    // nothing beyond the original's tables, verbs and file reader calls (RewriteScope, parse only:
    // the node judges the rewritten statement's grants); anything else denies.
    def rewriteRevalidated(rewrittenSql: String): Either[RouterFailure, String] =
      if rewrittenSql == sql then Right(rewrittenSql)
      else
        val parserCfg =
          ai.starlake.acl.model.Config.forDuckDB(defaultDb, defaultSchm, attachedCatalogs)
        ai.starlake.quack.edge.policy.RewriteScope.reachesBeyond(
          sql,
          rewrittenSql,
          parserCfg,
          defaultDb.getOrElse(""),
          clientDataScans
        ) match
          case None         => Right(rewrittenSql)
          case Some(reason) =>
            val denial = s"policy rewrite: $reason"
            maybeRecord(nodeId = "-", durationMs = 0, status = "denied", error = Some(denial))
            Left(RouterFailure.AccessDenied(s"access denied: $denial"))

    // client scan -> protected write -> CLS -> RLS -> metadata filter -> rewrite scope; every
    // denial arm has already journaled itself. Bound to resultIO so one flatTap below emits
    // exactly one StatementExecuted event on every exit path, including the denial arms.
    val resultIO: IO[Either[RouterFailure, Routed[A]]] =
      clientScan()
        .flatMap(_ => protectedWrite())
        .flatMap(_ => clsRewritten())
        .flatMap(rlsRewritten)
        .flatMap(metadataFiltered)
        .flatMap(rewriteRevalidated) match
        case Left(f)         => IO.pure(Left(f))
        case Right(finalSql) =>
          resolveSnapshot(poolKey, wake = purpose == DecidePurpose.Live).flatMap {
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
              // Tx pin wins; then the soft preferredNode while it is still in the snapshot,
              // routable, role-compatible and under capacity; else Router.pick's load-aware
              // choice.
              val txPin  = s.pinnedNodeId.filter(_ => s.txOpen)
              val pinned =
                txPin.orElse(
                  preferredNode.filter(id =>
                    Router.softPinUsable(snap, kind, id, ignoreCapacity = softPinOverCapacity)
                  )
                )
              // Each statement lands in a fresh remote DuckDB session, so unqualified
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
                  // A front door whose node connection carries the client's transaction (the
                  // native one) ends it there too, as abortTx does for a failed link: else its
                  // next statement would run on another node, outside the transaction the client
                  // still believes open (Phase C review D-m8).
                  pinLost.fold(IO.unit)(fence => fence(FlightSqlRouter.PinLostAnswer)) *>
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
                        if recordExecution || internal then
                          Some(
                            registry
                              .register(
                                user,
                                poolKey.tenant,
                                poolKey.pool,
                                nodeId,
                                activeSql,
                                patId
                              )
                          )
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
                      send(node, wrappedSql, prelude, recordExecution, principal)
                        .flatMap {
                          case NodeOutcome.Ok(_, _, close)
                              if internal && stmtId.exists(id => !registry.isRegistered(id)) =>
                            // Killed while the node ran it (a PAT revoked mid-evaluation): its
                            // entry had no cancel to fire, so the kill only evicted it. The value
                            // must not feed the statement that follows, which would register anew
                            // and run.
                            close()
                            IO.pure(Left(RouterFailure.AccessDenied(FlightSqlRouter.KilledReason)))

                          case NodeOutcome.Ok(reader, latency, close) =>
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
                            IO.pure(Right(Routed(reader, closeAndDeregister, nodeId, latency)))

                          case NodeOutcome.Transient(m, latency) =>
                            stmtId.foreach(registry.deregister)
                            maybeRecord(nodeId, latency, "transient", Some(m))
                            if s.txOpen then
                              // Only the client's own statement drops the transaction's pin. A
                              // statement that records nothing fails alone and keeps it: the
                              // manager's own read (`internal`: a SUMMARIZE's columns, a computed
                              // location) and the FlightSQL Prepare-time schema probe
                              // (FlightProducerImpl runs it with recordExecution = false), whose
                              // statement the client then executes on the pinned node, where it
                              // is answered (Phase C review D-m7).
                              if recordExecution then sessions.invalidatePin(connectionId)
                              IO.pure(
                                Left(
                                  RouterFailure
                                    .Unavailable(s"transient failure inside transaction: $m")
                                )
                              )
                            else
                              // Retry MUST send finalSql (CLS + RLS + metadata filter applied):
                              // retrying the caller's text would return rows the row policy
                              // should filter and catalog rows the metadata filter should hide.
                              retryOnce(
                                connectionId,
                                user,
                                poolKey,
                                kind,
                                finalSql,
                                clientSql = activeSql,
                                exclude = nodeId,
                                send = send,
                                principal = principal,
                                recordLoad = recordExecution,
                                internal = internal,
                                prelude = prelude,
                                patId = patId
                              )

                          case NodeOutcome.Permanent(m, latency) =>
                            stmtId.foreach(registry.deregister)
                            // The node's own refusal is a denial, journaled like the gateway's.
                            val status =
                              if FlightSqlRouter.isNodeRefusal(m) then "denied" else "permanent"
                            maybeRecord(nodeId, latency, status, Some(m))
                            IO.pure(
                              Left(classifyPermanent(m, FlightSqlRouter.isMultiStatement(finalSql)))
                            )
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

  /** Prepend `USE <dbName>.<schema>;` so unqualified and 2-part names resolve in the remote
    * session, where `schema` is the tenant-db's `defaultSchema` when set and its metastore
    * `schemaName` otherwise. It MUST differ from the catalog name (same-named catalog+schema is
    * ambiguous in DuckDB). The schema itself is pre-created by HealthProbe's first successful probe
    * per node. Skipped for a LONE USE / SET / txn control / ATTACH / DETACH so the operator can
    * escape the default.
    *
    * Never skipped for a multi-statement submission, whatever its first statement: the policy
    * rewriters qualified every bare name in it against that schema, and a node connection the
    * session keeps (a client transaction on the native front door, the embedded client's cached
    * session) may still carry an earlier lone `USE` or `SET search_path`. `BEGIN; SELECT * FROM t`
    * used to run there with no prelude and read whichever `t` that left current. Prepending is
    * harmless for such a batch: whatever its own statements select still runs after the prelude.
    *
    * One exception to "prepend": a submission opening with statements that END a transaction
    * (ROLLBACK / ABORT / COMMIT / END) gets the prelude right after that leading run instead,
    * spliced into the original text. Inside an aborted transaction DuckDB refuses every statement
    * but ROLLBACK, the prelude's `USE` included, so `USE ...; ROLLBACK; ...` failed and left the
    * session aborted. Those statements read no table and cannot move name resolution (a search path
    * is not transactional on DuckDB: it survives the ROLLBACK), so every statement that can read
    * still runs after the prelude. With nothing after the run there is nothing to resolve and the
    * text goes as is.
    */
  private def wrapWithDefaultSchema(
      state: Option[ai.starlake.quack.ondemand.PoolState],
      sql: String
  ): String =
    val trimmed        = sql.trim.toUpperCase(Locale.ROOT)
    lazy val fragments = SqlCommentStripper.splitStatements(sql)
    val endsTxFirst    = FlightSqlRouter.endsTransaction(sql)
    val skip           = (trimmed.startsWith("USE ") || trimmed.startsWith("SET ") ||
      trimmed.startsWith("BEGIN") || trimmed.startsWith("COMMIT") ||
      trimmed.startsWith("ROLLBACK") || trimmed.startsWith("ATTACH") ||
      trimmed.startsWith("DETACH")) && fragments.lengthCompare(2) < 0
    // The alias is `catalogAlias` when set (branch catalogs attach under their parent's alias),
    // else `dbName`: see TenantDb.catalogAlias.
    state match
      case Some(st) if !skip =>
        FlightSqlRouter.usePath(st) match
          case Some(path) =>
            val use = s"USE $path;"
            if endsTxFirst then
              // Splice into the ORIGINAL text at the start of the first statement that does not
              // end a transaction (just past its separating `;`), so what reaches the node is
              // byte for byte what the gateway read, plus the USE.
              SqlCommentStripper
                .statementSpans(sql)
                .find((from, until) =>
                  !FlightSqlRouter.endsTransaction(sql.substring(from, until))
                ) match
                case None            => sql
                case Some((from, _)) => s"${sql.substring(0, from)} $use${sql.substring(from)}"
            else s"$use $sql"
          case None => sql
      case Some(st) if trimmed.startsWith("USE ") =>
        FlightSqlRouter
          .qualifyBareUse(Option(TenantDb.catalogAlias(st.metastore)).filter(_.nonEmpty), sql)
      case _ => sql

  /** Resolve the routing snapshot, waking a suspended (never a disabled) pool first: fire
    * resumePool then poll for a routable node, bounded by resumeHoldTimeout; expiry yields the
    * retryable "pool is resuming" UNAVAILABLE. resumePool errors are swallowed (.attempt):
    * reconcile retries the spawn and the poll either sees a node or times out. With `wake` false
    * (the OPA dry run) a suspended pool is answered [[FlightSqlRouter.PoolNotWoken]] instead.
    */
  private def resolveSnapshot(
      poolKey: PoolKey,
      wake: Boolean
  ): IO[Either[RouterFailure, PoolSnapshot]] =
    supervisor.get(poolKey) match
      case None => IO.pure(Left(RouterFailure.NotFound(s"pool not found: $poolKey")))
      case Some(st) if st.suspended && !st.disabled && !wake =>
        IO.pure(Left(RouterFailure.Unavailable(FlightSqlRouter.PoolNotWoken)))
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

  /** One more try of a statement that failed in transport, on another node (never inside a client
    * transaction). Mirrors the primary path: `sql` (the fully rewritten statement) runs, the kill
    * registry shows `clientSql` (the caller's text, a system statement's values masked), the entry
    * is registered before the send so a kill reaches the retry from its first wire byte, and the
    * manager's own reads (`internal`) are registered too, a kill while one runs stopping the
    * statement that would use its value.
    */
  private def retryOnce[A](
      connectionId: String,
      user: String,
      poolKey: PoolKey,
      kind: StatementKind,
      sql: String,
      clientSql: String,
      exclude: String,
      send: FlightSqlRouter.NodeSend[A],
      principal: ai.starlake.quack.edge.authz.NodePrincipal,
      recordLoad: Boolean,
      internal: Boolean,
      prelude: Option[String],
      patId: Option[String]
  ): IO[Either[RouterFailure, Routed[A]]] =
    supervisor.snapshot(poolKey) match
      case None          => IO.pure(Left(RouterFailure.NotFound(s"pool not found: $poolKey")))
      case Some(snapAll) =>
        val snap = snapAll.copy(nodes = snapAll.nodes.filterNot(_.nodeId == exclude))
        Router.pick(snap, kind, pinned = None) match
          case RoutingDecision.Use(nodeId) =>
            snap.nodes.find(_.nodeId == nodeId) match
              case Some(n) =>
                val wrapped = wrapWithDefaultSchema(supervisor.get(poolKey), sql)
                val stmtId  =
                  if recordLoad || internal then
                    Some(
                      registry
                        .register(user, poolKey.tenant, poolKey.pool, nodeId, clientSql, patId)
                    )
                  else None
                send(n, wrapped, prelude, recordLoad, principal)
                  .map {
                    case NodeOutcome.Ok(_, _, close)
                        if internal && stmtId.exists(id => !registry.isRegistered(id)) =>
                      // Killed while the node ran it, as on the primary path.
                      close()
                      Left(RouterFailure.AccessDenied(FlightSqlRouter.KilledReason))
                    case NodeOutcome.Ok(reader, latency, close) =>
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
                      Right(Routed(reader, closeAndDeregister, nodeId, latency))
                    case NodeOutcome.Transient(m, _) =>
                      stmtId.foreach(registry.deregister)
                      Left(RouterFailure.Unavailable(s"retry failed (transient): $m"))
                    case NodeOutcome.Permanent(m, _) =>
                      stmtId.foreach(registry.deregister)
                      Left(
                        classifyPermanent(
                          m,
                          FlightSqlRouter.isMultiStatement(sql),
                          context = "retry failed: "
                        )
                      )
                  }
              case None =>
                IO.pure(Left(RouterFailure.Unavailable("no fallback node available")))
          case _ =>
            IO.pure(Left(RouterFailure.Unavailable("no fallback node available")))

  /** Map a permanent DuckDB error to a typed failure: the node's refusal for a decision it could
    * not get (FlightSqlRouter.NodeAuthzUnavailable: the replica is down or slow) becomes
    * Unavailable, which the client may retry, for a single-statement submission only: in a batch
    * (`multiStatement`) the statements before the refused one may have run, each autocommitting,
    * and a retry would run them twice (Phase C review D-m5), so the batch gets a BadRequest saying
    * so ([[FlightSqlRouter.BatchNotRetried]]); any other refusal of the node's
    * ([[FlightSqlRouter.isNodeRefusal]]) becomes AccessDenied; missing-object errors become
    * NotFound; the rest BadRequest. The "permanent failure:" prefix is preserved for operators on
    * the last two. `context` (the retry's "retry failed: ") goes into the reason after the prefix,
    * never into the node's message the checks read: they match that message's start.
    */
  private def classifyPermanent(
      message: String,
      multiStatement: Boolean,
      context: String = ""
  ): RouterFailure =
    val lower    = message.toLowerCase(Locale.ROOT)
    val notFound = lower.contains("does not exist") || lower.contains("not found") ||
      (lower.contains("catalog error") && lower.contains("does not"))
    val full = s"permanent failure: $context$message"
    if FlightSqlRouter.isNodeAuthzUnavailable(message) then
      if multiStatement then RouterFailure.BadRequest(s"$full (${FlightSqlRouter.BatchNotRetried})")
      else RouterFailure.Unavailable(full)
    else if FlightSqlRouter.isNodeRefusal(message) then
      RouterFailure.AccessDenied(s"access denied: $context$message")
    else if notFound then RouterFailure.NotFound(full)
    else RouterFailure.BadRequest(full)

object FlightSqlRouter:

  /** `UNDROP TABLE` inside a client transaction (review M5): refused on both doors, since its
    * recovery commits on a connection of its own and the client's ROLLBACK would not undo it.
    */
  val UndropInTransaction: RouterFailure = RouterFailure.BadRequest(
    "UNDROP TABLE cannot run inside a transaction: its recovery commits on its own; COMMIT or " +
      "ROLLBACK first"
  )

  /** How qod-node words a statement it refused because no verdict could be had (the decision
    * replica unreachable, slow or answering 503): retryable, unlike every other node refusal.
    */
  val NodeAuthzUnavailable: String = "QoD: authorization unavailable, retry"

  /** Whether a node's error `message` is that refusal, in the exact shapes the node raises it: a
    * statement's (DuckDB's PermissionException) or the parse screen's (quack's authorization
    * callback wrapping it). A user's own text that quotes the marker (error('...'), an identifier
    * echoed in a catalog error) stays a permanent failure.
    */
  def isNodeAuthzUnavailable(message: String): Boolean =
    NodeAuthzUnavailablePrefixes.exists(message.startsWith)

  private val NodeAuthzUnavailablePrefixes: List[String] = List(
    s"Permission Error: $NodeAuthzUnavailable",
    s"Invalid Input Error: Authorization callback failed: Permission Error: $NodeAuthzUnavailable"
  )

  /** Why a batch's "authorization unavailable" refusal is not marked retryable. */
  val BatchNotRetried: String =
    "not retryable as a whole: the statements before the refused one may have run"

  /** Whether `sql` holds more than one statement, split as DuckDB's lexer splits it. */
  def isMultiStatement(sql: String): Boolean =
    SqlCommentStripper.splitStatements(sql).lengthCompare(1) > 0

  /** Whether a node's error `message` is its authorization refusal, other than
    * [[NodeAuthzUnavailable]]: `QoD: ` right after DuckDB's Permission Error, in a statement's or
    * the parse screen's shape. The node decided the principal may not run the statement, so the
    * router books it as a denial, like the gateway's own refusals. A user's text quoting the marker
    * (error('QoD: ...'), an identifier echoed in a catalog error) stays a permanent failure.
    */
  def isNodeRefusal(message: String): Boolean =
    !isNodeAuthzUnavailable(message) && NodeRefusalPrefixes.exists(message.startsWith)

  private val NodeRefusalPrefixes: List[String] = List(
    "Permission Error: QoD: ",
    "Invalid Input Error: Authorization callback failed: Permission Error: QoD: "
  )

  /** The target of the USE prelude [[wrapWithDefaultSchema]] sends before a pool's statements,
    * `<alias>.<schema>`, or None when it sends none (no catalog alias). Every session handle for
    * the pool carries it as its `path` (NodeKeys), and the node judges secure views against it
    * (CONTRACT Addendum 4 item 22), so both read it here.
    *
    * The tenant-db's own `defaultSchema` wins over the metastore's `schemaName`. The gateway's
    * parser default (`defaultsOf`) reads the same field, so reading a different one here made the
    * gateway and the engine disagree about which schema is current for the same statement: the
    * gateway qualified unqualified refs against `defaultSchema` while the engine ran
    * `USE <db>.<schemaName>`. With the demo's settings (defaultSchema=tpch1, schemaName=main) that
    * surfaced as issue #112, where a native `quack:` client pushes down a schema-relative name and
    * the node could not resolve it. Where two schemas hold a same-named table it would instead let
    * the policies match one table while the engine read the other.
    */
  def usePath(st: ai.starlake.quack.ondemand.PoolState): Option[String] =
    sessionPath(st).map(p => s"${p.catalog}.${p.schema}")

  /** [[usePath]] as its catalog and schema: the session's current catalog and schema for every
    * statement of the pool, which a refused form is qualified with ([[RefusedForms]]).
    */
  def sessionPath(st: ai.starlake.quack.ondemand.PoolState): Option[RefusedForms.SessionPath] =
    Option(TenantDb.catalogAlias(st.metastore)).filter(_.nonEmpty).map { db =>
      RefusedForms.SessionPath(db, defaultSchemaOf(st))
    }

  private def defaultSchemaOf(st: ai.starlake.quack.ondemand.PoolState): String =
    st.defaultSchema
      .filter(_.nonEmpty)
      .orElse(st.metastore.get("schemaName").filter(_.nonEmpty))
      .getOrElse("main")

  /** Whether the statements of `effectiveSet`'s principal get Task 19's rewrites: a tenant
    * principal's, never a superuser's (its statement runs as written), the system principal's, or a
    * statement no principal is bound for (which the node refuses).
    */
  def rewritable(effectiveSet: Option[EffectiveSet]): Boolean =
    effectiveSet.exists(e => e.user.tenant.nonEmpty && !e.system)

  /** Ends the client's transaction on the front door's node connection, answering `answer` to the
    * statement and every later one until ROLLBACK (QuackFrontDoor's `abortTx`).
    */
  type TransactionFence = String => IO[Unit]

  /** The fence's answer when the transaction's pinned node has left the pool: the node, and the
    * transaction with it, are gone.
    */
  val PinLostAnswer: String =
    "transaction aborted: the transaction's node left the pool; ROLLBACK to continue"

  /** The answer to a COMMIT in that state: the fence lifts with it (DuckDB ends a transaction whose
    * COMMIT fails), so it gives no ROLLBACK advice, only that nothing was committed.
    */
  val PinLostCommitAnswer: String =
    "transaction aborted: the transaction's node left the pool; nothing was committed"

  /** Why a statement the manager ran for the client ([[readBack]]) failed when it was killed while
    * the node ran it: the client's statement then stops there.
    */
  val KilledReason: String = "access denied: the statement was killed"

  /** Why a statement that may not wake its pool (the OPA dry run, decision 25) was not run: the
    * pool is suspended.
    */
  val PoolNotWoken: String = "pool is suspended; a dry run never wakes it"

  /** Why a dry run for a superuser, the system or no principal was refused: a node admits the first
    * two without asking the decision endpoint, so the statement would run.
    */
  val DryRunPrincipalRequired: String =
    "access denied: a dry run runs only for a tenant user"

  /** The refusal of a dry run whose principal is not a tenant user (an access denial: the statement
    * form of the dry run answers it `refused_before_opa`).
    */
  private[edge] def dryRunRefused: RouterFailure =
    RouterFailure.AccessDenied(DryRunPrincipalRequired)

  /** Whether a dry run may run for `effectiveSet`: a tenant user, never a superuser (no tenant),
    * the system caller or no principal, whose statements no decide context binds.
    */
  def dryRunnable(effectiveSet: Option[EffectiveSet]): Boolean =
    effectiveSet.exists(e => e.user.tenant.nonEmpty && !e.system)

  /** Why a computed file location got no literal: its evaluation failed (`Evaluation`, the
    * gateway's or the node's answer), or the value it yielded is no location (`Rejected`).
    */
  enum LocationFailure:
    case Evaluation(failure: RouterFailure)
    case Rejected(reason: String)

  /** `failure`, an evaluation's, as the answer to the client's statement (CONTRACT Addendum 4 item
    * 25): of the same kind, its reason prefixed, since the client did not write the evaluated SQL.
    */
  def computingLocation(failure: RouterFailure): RouterFailure =
    val reason = s"computing the file location: ${failure.reason}"
    failure match
      case RouterFailure.AccessDenied(_)  => RouterFailure.AccessDenied(reason)
      case RouterFailure.NotFound(_)      => RouterFailure.NotFound(reason)
      case RouterFailure.BadRequest(_)    => RouterFailure.BadRequest(reason)
      case RouterFailure.Unavailable(_)   => RouterFailure.Unavailable(reason)
      case RouterFailure.Internal(_)      => RouterFailure.Internal(reason)
      case RouterFailure.AlreadyExists(_) => RouterFailure.AlreadyExists(reason)

  /** The history status of a failure answered before any node ran the statement. */
  def failureStatus(failure: RouterFailure): String = failure match
    case RouterFailure.AccessDenied(_) => "denied"
    case RouterFailure.Unavailable(_)  => "transient"
    case _                             => "permanent"

  /** SUMMARIZE's columns from the rows of its DESCRIBE rewrite (`column_name`, `column_type`
    * first), or None when a row is not two VARCHARs or there is none.
    */
  def summarizeColumns(rows: NodeRows): Option[List[(String, String)]] =
    val cols = rows.rows.map {
      case NodeValue.Text(name) :: NodeValue.Text(tpe) :: _ => Some((name, tpe))
      case _                                                => None
    }
    Option.when(cols.nonEmpty && cols.forall(_.isDefined))(cols.flatten)

  /** Audit-detail keys QoD writes itself on the routed path; a statement's `detail` can never set
    * them.
    */
  val ReservedAuditKeys: Set[String] = Set("sql", "denied", "reason", "durationMs")

  /** Why `sql` may not run for a principal whose catalog reads the edge metadata filter narrows
    * (`narrowed`, [[ai.starlake.quack.edge.meta.MetadataFilterRewriter.narrows]]), or None. The
    * filter rewrites SELECTs and SHOW TABLES; it cannot read a COPY, and qod-node narrows nothing
    * (spec Section 12 item 6), so a COPY could carry a listing the filter would have narrowed to a
    * file the principal may write. Refused:
    *   - any COPY the gateway cannot read as the one statement of the submission
    *     ([[ai.starlake.quack.edge.sql.FileLocations.unreadCopy]]: in a batch, behind EXPLAIN or
    *     PREPARE, from a SHOW / DESCRIBE / SUMMARIZE source, or in a shape the reader stops short
    *     of; Phase C review D-m1);
    *   - a lone COPY whose stand-in (its table side,
    *     [[ai.starlake.quack.edge.sql.FileLocations.CopyUse]]) the filter would change (`filter`,
    *     the principal's): an information_schema or catalog-function source.
    * Nothing for a principal the filter leaves alone (a superuser, `*.*.* ALL`, an explicit
    * information_schema grant, or the filter not mounted): it reads the same listing unfiltered
    * directly, so its COPY is the node's to judge.
    */
  def copyListingRefusal(
      sql: String,
      narrowed: Boolean,
      filter: String => ai.starlake.quack.edge.meta.MetadataFilterOutcome
  ): Option[String] =
    if !narrowed then None
    else if ai.starlake.quack.edge.sql.FileLocations.unreadCopy(sql) then Some(UnreadCopyRefusal)
    else
      ai.starlake.quack.edge.sql.FileLocations
        .copy(sql)
        .flatMap { c =>
          val untouched =
            filter(c.tableSide) == ai.starlake.quack.edge.meta.MetadataFilterOutcome.Passthrough
          Option.unless(untouched)(ListingCopyRefusal)
        }

  val UnreadCopyRefusal: String =
    "a COPY in a batch, behind EXPLAIN or PREPARE, or from a SHOW / DESCRIBE / SUMMARIZE source " +
      "cannot be checked against the edge metadata filter: send one COPY of a query or table"

  val ListingCopyRefusal: String =
    "a COPY may not write catalog metadata the edge filters (information_schema, SHOW) to a " +
      "file: query the listing directly"

  /** Why `sql` may not run from an edge that cannot feed a client data scan, or None.
    * `scan_data_from_quack_client` reads rows a DuckDB 2.0 client sends on its own connection
    * (SEND_DATA), which only the native Quack front door relays (`clientDataScans`): from any other
    * edge the node would wait for rows nobody sends. Read lexically, literal bodies included, so a
    * name in a string over-refuses (the safe direction), and a statement nesting literals too
    * deeply to scan is refused too. Not an authorization rule: it holds for every caller.
    */
  def clientDataScanRefusal(sql: String, clientDataScans: Boolean): Option[String] =
    if clientDataScans then None
    else
      val scan = ai.starlake.quack.edge.sql.FunctionCalls.scan(sql)
      if scan.calls.exists(_.name == ClientDataScan) then
        Some(
          s"$ClientDataScan reads rows only the native Quack front door relays; it cannot run " +
            "from this edge"
        )
      else if scan.truncated then
        Some(
          s"statement nests string literals too deeply to check for $ClientDataScan; it cannot " +
            "run from this edge"
        )
      else None

  private val ClientDataScan = "scan_data_from_quack_client"

  /** One node call for [[FlightSqlRouter.executeWith]]: `(node, wrappedSql, stampPrelude,
    * recordLoad, principal) => outcome`. `principal` is who the statement runs for: the call sends
    * a fresh session handle for it before every PREPARE
    * ([[ai.starlake.quack.edge.adapter.QuackHttpAdapter.authorizer]]). Implementations must book
    * load through [[ai.starlake.quack.edge.adapter.QuackHttpAdapter.tracked]] (or `send`) so both
    * transports feed the per-node stats identically.
    */
  type NodeSend[A] =
    (
        ai.starlake.quack.model.RunningNode,
        String,
        Option[String],
        Boolean,
        ai.starlake.quack.edge.authz.NodePrincipal
    ) => IO[ai.starlake.quack.edge.adapter.NodeOutcome[A]]

  private val TransactionEnds = Set("ROLLBACK", "ABORT", "COMMIT", "END")

  /** True when the first statement of `sql` ends a transaction (its verb as the engine reads it,
    * past leading comments and trivia).
    */
  private[edge] def endsTransaction(sql: String): Boolean =
    TransactionEnds.contains(
      SqlTrivia.firstToken(sql).takeWhile(_.isLetter).toUpperCase(Locale.ROOT)
    )

  // A bare one-part USE target: an unquoted identifier or one quoted identifier
  // (quoted may contain anything but a quote, including dots). Two-part
  // `USE a.b` deliberately does not match and passes through untouched.
  private val BareUseRe =
    """(?is)^\s*use\s+("[^"]+"|[a-z_][a-z0-9_$]*)\s*;?\s*$""".r

  /** Qualify a bare one-part `USE x` into `USE <dbName>.x`. On the node the DuckLake catalog is
    * attached under the tenant-db name and the session's current catalog is the transient memory
    * db, so a client's `USE star1` fails with "No catalog + schema named star1 found" even though
    * saleh_default.star1 exists -- and the client has no way to know the physical db name.
    * `USE a.b`, `USE <dbName>` and `USE memory` pass through so catalog switching stays possible.
    */
  private[edge] def qualifyBareUse(dbName: Option[String], sql: String): String =
    dbName match
      case None     => sql
      case Some(db) =>
        sql match
          case BareUseRe(token) =>
            val name = token.stripPrefix("\"").stripSuffix("\"")
            if name.equalsIgnoreCase(db) || name.equalsIgnoreCase("memory") then sql
            else s"USE $db.$token"
          case _ => sql
