package ai.starlake.quack.edge

import ai.starlake.quack.edge.adapter._
import ai.starlake.quack.edge.sql.{Allowed, Denied, StatementValidator, ValidationContext}
import ai.starlake.quack.model.{PoolKey, StatementKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.route.{Router, RoutingDecision, StatementClassifier}

import ai.starlake.quack.observability.metrics.StatementInstruments
import cats.effect.IO

/** Carries a streaming result back through the router. The caller MUST invoke `close()` once all
  * batches have been consumed.
  */
final case class QueryResult(rows: org.apache.arrow.vector.ipc.ArrowReader, close: () => Unit)

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
    val classifier: StatementClassifier = StatementClassifier.default
):

  private def record(
      user: String,
      poolKey: PoolKey,
      nodeId: String,
      sql: String,
      durationMs: Long,
      status: String,
      error: Option[String]
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
        error = error
      )
    )
    stmtInstruments.record(poolKey.tenant, poolKey.pool, status, durationMs)

  def session(connectionId: String) = sessions.get(connectionId)

  /** Run a statement under the named connection. Success path yields a [[QueryResult]] streaming
    * Arrow batches from the chosen Quack node; the caller MUST close it. Failure path is a plain
    * error message.
    *
    * `effectiveSet` carries the RBAC closure pinned on ConnectionContext at handshake time. `None`
    * is the legacy (no handshake state) path -- PostgresAclValidator denies anything tenant-scoped
    * in that case to fail safe.
    */
  def execute(
      connectionId: String,
      user: String,
      poolKey: PoolKey,
      sql: String,
      effectiveSet: Option[EffectiveSet] = None
  ): IO[Either[String, QueryResult]] =
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
    validator.validate(ctx) match
      case Denied(reason) =>
        record(
          user,
          poolKey,
          nodeId = "-",
          sql = sql,
          durationMs = 0,
          status = "denied",
          error = Some(reason)
        )
        return IO.pure(Left(s"access denied: $reason"))
      case Allowed => () // fall through
    supervisor.snapshot(poolKey) match
      case None =>
        if s.txOpen then sessions.invalidatePin(connectionId)
        record(
          user,
          poolKey,
          nodeId = "-",
          sql = sql,
          durationMs = 0,
          status = "no-pool",
          error = None
        )
        IO.pure(Left(s"pool not found: $poolKey"))
      case Some(snap) =>
        val pinned = s.pinnedNodeId.filter(_ => s.txOpen)
        // Each quack_query lands in a fresh DuckDB session on the remote, so
        // an unqualified `SELECT * FROM customer` would 404 - we wrap the user
        // SQL with `USE <dbName>.<dbName>; ...` (matching the spawn script's
        // initial schema) when the pool's metastore advertises a dbName.
        val wrappedSql = wrapWithDefaultSchema(supervisor.get(poolKey), sql)
        Router.pick(snap, kind, pinned) match
          case RoutingDecision.Unavailable(reason) =>
            record(
              user,
              poolKey,
              nodeId = "-",
              sql = sql,
              durationMs = 0,
              status = "no-node",
              error = Some(reason)
            )
            IO.pure(Left(reason))

          case RoutingDecision.PinnedNodeGone(_) =>
            sessions.invalidatePin(connectionId)
            record(
              user,
              poolKey,
              nodeId = "-",
              sql = sql,
              durationMs = 0,
              status = "pin-lost",
              error = None
            )
            IO.pure(Left("pinned node disappeared; transaction lost"))

          case RoutingDecision.Use(nodeId) =>
            snap.nodes.find(_.nodeId == nodeId) match
              case None =>
                IO.pure(Left(s"node $nodeId not in snapshot"))
              case Some(node) =>
                adapter.send(node, wrappedSql, session = None).flatMap {
                  case QuackResponse.Ok(reader, latency, close) =>
                    sessions.onStatement(connectionId, kind, nodeId)
                    record(user, poolKey, nodeId, sql, latency, "ok", None)
                    IO.pure(Right(QueryResult(reader, close)))

                  case QuackResponse.Failed(QuackError.Transient(m), latency) =>
                    record(user, poolKey, nodeId, sql, latency, "transient", Some(m))
                    if s.txOpen then
                      sessions.invalidatePin(connectionId)
                      IO.pure(Left(s"transient failure inside transaction: $m"))
                    else retryOnce(connectionId, poolKey, kind, sql, exclude = nodeId)

                  case QuackResponse.Failed(QuackError.Permanent(m), latency) =>
                    record(user, poolKey, nodeId, sql, latency, "permanent", Some(m))
                    IO.pure(Left(s"permanent failure: $m"))
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
      poolKey: PoolKey,
      kind: StatementKind,
      sql: String,
      exclude: String
  ): IO[Either[String, QueryResult]] =
    supervisor.snapshot(poolKey) match
      case None          => IO.pure(Left(s"pool not found: $poolKey"))
      case Some(snapAll) =>
        val snap = snapAll.copy(nodes = snapAll.nodes.filterNot(_.nodeId == exclude))
        Router.pick(snap, kind, pinned = None) match
          case RoutingDecision.Use(nodeId) =>
            snap.nodes.find(_.nodeId == nodeId) match
              case Some(n) =>
                val wrapped = wrapWithDefaultSchema(supervisor.get(poolKey), sql)
                adapter.send(n, wrapped, None).map {
                  case QuackResponse.Ok(reader, _, close) =>
                    // Pin the session on the retry node so a follow-up
                    // COMMIT/ROLLBACK lands on the same Quack instance.
                    // Without this, a BEGIN that retried onto node B
                    // would have its COMMIT routed by load and likely
                    // hit node A - breaking transaction consistency.
                    sessions.onStatement(connectionId, kind, nodeId)
                    Right(QueryResult(reader, close))
                  case QuackResponse.Failed(QuackError.Transient(m), _) =>
                    Left(s"retry failed (transient): $m")
                  case QuackResponse.Failed(QuackError.Permanent(m), _) =>
                    Left(s"retry failed (permanent): $m")
                }
              case None =>
                IO.pure(Left("no fallback node available"))
          case _ =>
            IO.pure(Left("no fallback node available"))
