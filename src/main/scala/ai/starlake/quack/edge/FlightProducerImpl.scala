package ai.starlake.quack.edge

import java.util.Locale
import cats.effect.unsafe.implicits.global
import com.google.protobuf.{Any => ProtoAny, ByteString}
import com.typesafe.scalalogging.LazyLogging
import org.apache.arrow.flight.*
import org.apache.arrow.flight.sql.NoOpFlightSqlProducer
import org.apache.arrow.flight.sql.FlightSqlProducer.Schemas
import org.apache.arrow.flight.sql.impl.FlightSql
import org.apache.arrow.memory.{BufferAllocator, RootAllocator}
import org.apache.arrow.vector.{BigIntVector, VectorLoader, VectorSchemaRoot, VectorUnloader}
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch
import org.apache.arrow.vector.types.pojo.{ArrowType, Field, FieldType, Schema}

import java.nio.charset.StandardCharsets
import java.util.Collections
import scala.jdk.CollectionConverters.*

/** Minimal FlightSqlProducer that forwards SQL to the [[FlightSqlRouter]]. The router talks to the
  * chosen Quack node through the native Quack wire client and hands us back an
  * [[org.apache.arrow.vector.ipc.ArrowReader]] that we stream batch-by-batch to the Flight client.
  *
  * `preparedIdleTtlSec`: a prepared handle unused this long is dropped ([[PreparedHandles]]); the
  * edge passes its session TTL. The edge also drops a session's handles when the session leaves
  * [[ConnectionContext]] ([[dropSession]], through [[FlightEdgeServer.sessionEvicted]]).
  *
  * `maxPutDrains`: how many DoPuts may wait for their data at once. Arrow runs a DoPut task on the
  * executor that delivers its data, so waiting tasks must never fill it ([[FlightEdgeServer]]
  * passes a quarter of its threads). `maxPutDrainBytes`: how much parameter data one DoPut may
  * send, all of it ignored. `putDrainTimeoutMs`: how long one DoPut may wait for its first message
  * and its data.
  */
final class FlightProducerImpl(
    router: FlightSqlRouter,
    preparedIdleTtlSec: Long,
    maxPutDrains: Int = FlightProducerImpl.DefaultMaxPutDrains,
    maxPutDrainBytes: Long = FlightProducerImpl.DefaultMaxPutDrainBytes,
    putDrainTimeoutMs: Long = FlightProducerImpl.DefaultPutDrainTimeoutMs
) extends NoOpFlightSqlProducer
    with LazyLogging:

  private val allocator: BufferAllocator = new RootAllocator()

  private val putDrainSlots = new java.util.concurrent.Semaphore(maxPutDrains)

  /** DEBUG statement logging must never leak a SQL admin dialect password literal (`CREATE USER ...
    * PASSWORD '...'`, `ALTER USER ... PASSWORD '...'`): those statements are otherwise logged
    * verbatim at the three call sites below. `AdminSqlParser.claims` is the same cheap,
    * few-tokens-only check the router uses to decide interception, so redacting on it drops the
    * whole statement behind a constant placeholder. A per-statement "first keyword" extract was
    * considered and rejected: taking the raw text's leading non-whitespace run is not safe against
    * comment-obfuscated statements (`CREATE/**/USER/**/a/**/PASSWORD/**/'secret'` tokenizes to a
    * single whitespace-free run, so that run - literal included - would be the whole statement).
    * This redacts every claim-shaped statement, not only the ones carrying a password, and does so
    * even when `quack-on-demand.sqlAdmin.enabled` is off: an unwired dialect still denies a
    * claim-shaped statement at the router, so its raw text is equally sensitive either way, and
    * consistent redaction avoids a flag-dependent log format. scalalogging's `logger.debug` is
    * macro-generated to lazily evaluate its interpolated arguments, so this scan only runs when
    * DEBUG is actually enabled - not on every statement of the hot path.
    */
  private def loggableSql(sql: String): String =
    if ai.starlake.quack.edge.admin.AdminSqlParser.claims(sql) then
      ai.starlake.quack.edge.admin.AdminSqlParser.RedactedPlaceholder
    else sql

  /** Per-handle execution context, captured at Prepare time. Arrow batches are not cached (a reader
    * cannot be replayed), so the SQL re-executes through the router on every Execute: one handle
    * drives many Executes until ClosePreparedStatement, as the spec requires.
    *
    * `ownerPeer` binds the handle to the creating session: Execute/Update reject other peers, so a
    * leaked handle cannot be replayed. The EffectiveSet is deliberately NOT stored: Execute
    * re-reads it live from [[ConnectionContext]], so mid-session revocation is honored and an
    * expired session kills the handle.
    *
    * `preferredNode` soft-pins Execute to the node that served Prepare (warm DuckDB caches);
    * `prepareDurationMs` rides into the Execute's [[StatementRecord]] for the UI. Both None for
    * SkipExecute. `datasetSchema` answers getSchemaPreparedStatement without a second probe; None
    * when the statement was left unprobed ([[PrepareStrategy.Unprobed]]): its schema is advertised
    * as absent and the Execute stream carries it.
    */
  private final case class PreparedExec(
      sql: String,
      ownerPeer: String,
      connId: String,
      user: String,
      poolKey: ai.starlake.quack.model.PoolKey,
      preferredNode: Option[String],
      prepareDurationMs: Option[Long],
      datasetSchema: Option[Schema]
  )

  /** Live per-call plan resolved for a prepared Execute/Update: the captured statement identity
    * plus the FRESH [[EffectiveSet]] read from [[ConnectionContext]] at call time.
    */
  private final case class PreparedCall(
      sql: String,
      connId: String,
      user: String,
      poolKey: ai.starlake.quack.model.PoolKey,
      effectiveSet: Option[ai.starlake.quack.ondemand.rbac.EffectiveSet],
      preferredNode: Option[String],
      prepareDurationMs: Option[Long],
      datasetSchema: Option[Schema]
  )

  /** Zero-field schema: the "dispatch through executeUpdate" marker, and the `parameter_schema`
    * value since Quack has no parameter binding.
    */
  private val emptySchema: Schema =
    new Schema(java.util.Collections.emptyList[Field]())

  private val emptySchemaBytes: ByteString =
    serializeSchema(emptySchema)

  /** DuckDB streams SOME non-result statements as a single-row `Count BIGINT` -- but not all of
    * them; see [[coerceSuccessToCount]]'s doc for the disproven "every non-result statement"
    * premise and the actual split. Both FlightInfo paths must advertise THIS schema, not an empty
    * one, for the ones this DOES apply to: ADBC enforces FlightInfo.schema == DoGet stream schema.
    */
  private val countSchema: Schema =
    new Schema(
      Collections.singletonList(
        new Field("Count", FieldType.nullable(new ArrowType.Int(64, true)), null)
      )
    )

  private val countSchemaBytes: ByteString =
    serializeSchema(countSchema)

  /** Two nullable utf8 columns: the schema every SQL admin dialect MUTATION actually delivers
    * (`AdminResults.ok` / the executor's other mutation arms - see AdminStatementExecutor). A
    * mutating admin statement (`CREATE ROLE`, `GRANT`, ...) classifies as DDL, so both
    * `createPreparedStatement`'s SkipExecute branch AND `getFlightInfoStatement`'s
    * `probeStatementSchema(...).getOrElse(...)` fallback used to advertise `countSchema` for it
    * unconditionally, same as any other DDL - the Prepare/Execute path for prepared statements, and
    * the FlightInfo/DoGet path for literal (unprepared) `CommandStatementQuery` statements.
    * ADBC/JDBC's advertised-schema check is STRICT on both paths: the client rejected the real
    * `(status, detail)` result against the advertised `Count: int64`, so the dialect could not be
    * driven through its primary clients via EITHER path (release blocker, fixed alongside the
    * (status, detail) unification below). SHOW forms are unaffected on either path -
    * `StatementClassifier` puts `SHOW` in the `select` bucket, so `PrepareStrategy.choose` routes
    * it to `FullExecute`/a real probe, which advertises the REAL schema already; only the
    * DML/DDL-shaped (SkipExecute / probe-returns-None) branches needed this substitution, and
    * neither executes the claimed statement early - a claimed mutation would otherwise
    * double-execute.
    */
  private val adminStatusSchema: Schema =
    new Schema(
      ai.starlake.quack.edge.admin.AdminResults.MutationColumns
        .map(c => new Field(c, FieldType.nullable(new ArrowType.Utf8), null))
        .asJava
    )

  private val adminStatusSchemaBytes: ByteString =
    serializeSchema(adminStatusSchema)

  private val preparedStatements =
    new PreparedHandles[PreparedExec](
      preparedIdleTtlSec * 1000L,
      () => System.currentTimeMillis(),
      _.connId
    )

  /** Drop every prepared handle of the session `connectionId`, which just left
    * [[ConnectionContext]]: a later use of one answers "no such prepared statement".
    */
  def dropSession(connectionId: String): Unit =
    val n = preparedStatements.dropConnection(connectionId)
    if n > 0 then logger.debug(s"dropped $n prepared handle(s) of evicted session $connectionId")

  /** Drop the prepared handles unused for `preparedIdleTtlSec`; run by the edge's session sweeper.
    */
  def expireIdlePrepared(): Int = preparedStatements.expireIdle()

  /** Visible for tests. */
  private[edge] def preparedCount: Int = preparedStatements.size

  /** Serialize an Arrow schema as a single IPC `Schema` message. */
  private def serializeSchema(schema: org.apache.arrow.vector.types.pojo.Schema): ByteString =
    val baos = new java.io.ByteArrayOutputStream()
    org.apache.arrow.vector.ipc.message.MessageSerializer
      .serialize(
        new org.apache.arrow.vector.ipc.WriteChannel(
          java.nio.channels.Channels.newChannel(baos)
        ),
        schema
      )
    ByteString.copyFrom(baos.toByteArray)

  /** UNAUTHENTICATED failure for a peer with no bound (or expired) session. */
  private def noContext(
      peer: String,
      description: String => String = p => s"no connection context for peer $p"
  ): RuntimeException =
    CallStatus.UNAUTHENTICATED.withDescription(description(peer)).toRuntimeException()

  /** The session bound to `peer` and the effective set its statement runs under, resolved again
    * first when its tenant's policy version moved ([[SessionGrants]]). Left: no session
    * (`missing`), or its grants now refuse it (UNAUTHORIZED) or could not be resolved
    * (UNAVAILABLE).
    */
  private def liveSession(
      peer: String,
      missing: => Throwable
  ): Either[
    Throwable,
    (ConnectionContext.Entry, Option[ai.starlake.quack.ondemand.rbac.EffectiveSet])
  ] =
    ConnectionContext.entry(peer) match
      case None    => Left(missing)
      case Some(e) => e.effectiveSet().left.map(toFlightException).map(eff => (e, eff))

  /** Standard single-endpoint FlightInfo for a metadata RPC: the command packed into the one
    * Ticket, unknown record / byte counts.
    */
  private def singleEndpointInfo(
      command: com.google.protobuf.Message,
      schema: Schema,
      descriptor: FlightDescriptor
  ): FlightInfo =
    val ticket = new Ticket(ProtoAny.pack(command).toByteArray)
    new FlightInfo(
      schema,
      descriptor,
      Collections.singletonList(new FlightEndpoint(ticket)),
      -1L,
      -1L
    )

  /** An anonymous peer ([[AnonymousCallGuard]]) gets server metadata only: GetSqlInfo and
    * GetXdbcTypeInfo, what ODBC asks before it replays its bearer, in a command the guard has
    * limited to about [[FlightProducerImpl.MaxAnonymousCommandBytes]]. Anything else, static
    * metadata included, needs a credential.
    */
  private def refuseAnonymous(
      context: FlightProducer.CallContext,
      command: => Array[Byte]
  ): Unit =
    val peer = Option(context.peerIdentity()).getOrElse("")
    if peer.startsWith(FlightEdgeServer.AnonymousPeerPrefix) then
      val bytes   = command
      val allowed = scala.util
        .Try(ProtoAny.parseFrom(bytes))
        .toOption
        .exists(any =>
          any.is(classOf[FlightSql.CommandGetSqlInfo]) ||
            any.is(classOf[FlightSql.CommandGetXdbcTypeInfo])
        )
      if !allowed then throw noContext(peer, _ => "no pool bound to session; authenticate first")

  override def getFlightInfo(
      context: FlightProducer.CallContext,
      descriptor: FlightDescriptor
  ): FlightInfo =
    refuseAnonymous(
      context,
      if descriptor.isCommand then descriptor.getCommand else Array.emptyByteArray
    )
    super.getFlightInfo(context, descriptor)

  /** Logs the DoGet peer and ticket shape at DEBUG, and refuses an anonymous peer, before
    * forwarding to the FlightSqlProducer dispatcher.
    */
  override def getStream(
      context: FlightProducer.CallContext,
      ticket: Ticket,
      listener: FlightProducer.ServerStreamListener
  ): Unit =
    refuseAnonymous(context, ticket.getBytes)
    if logger.underlying.isDebugEnabled then
      val peer        = Option(context.peerIdentity()).getOrElse("(null)")
      val ticketBytes = ticket.getBytes
      logger.debug(
        s"getStream: peer=$peer ticketLen=${ticketBytes.length} ticketPrefix=" +
          ticketBytes.take(8).map(b => f"$b%02x").mkString
      )
    super.getStream(context, ticket, listener)

  /** Map a typed [[RouterFailure]] to its Flight `CallStatus` so connectors can branch on the
    * status code without parsing the description string.
    */
  private def toFlightException(f: RouterFailure): Throwable =
    val status = f match
      case RouterFailure.AccessDenied(_)  => CallStatus.UNAUTHORIZED
      case RouterFailure.NotFound(_)      => CallStatus.NOT_FOUND
      case RouterFailure.BadRequest(_)    => CallStatus.INVALID_ARGUMENT
      case RouterFailure.Unavailable(_)   => CallStatus.UNAVAILABLE
      case RouterFailure.Internal(_)      => CallStatus.INTERNAL
      case RouterFailure.AlreadyExists(_) => CallStatus.ALREADY_EXISTS
    status.withDescription(f.reason).toRuntimeException()

  /** INTERNAL Flight exception for an unexpected throwable. The raw message may leak SQL, hostnames
    * or paths, so the client gets only a short random error id; the full detail is logged
    * server-side under that id. Typed failures go through [[toFlightException]].
    */
  private def internalError(context: String, t: Throwable): Throwable =
    val errorId = java.util.UUID.randomUUID().toString.take(8)
    logger.error(s"$context threw [errorId=$errorId]: ${t.getMessage}", t)
    CallStatus.INTERNAL
      .withDescription(s"internal error (errorId=$errorId)")
      .toRuntimeException()

  /** #106 signature 1: a stamped write's COMMIT (see the `adapter.StampedCommitArrowReader` doc)
    * fires from INSIDE `streamArrow`'s `loadNextBatch()` loop, before a Count answer is streamed
    * ([[streamAfterExhaustion]]), and from DoPut's drain before the update count is acknowledged
    * ([[ackUpdateResult]]). When that COMMIT loses a genuine DuckLake optimistic-concurrency race,
    * the exception used to fall into the generic [[internalError]] bucket: a scary opaque "internal
    * error" for what is, underneath, an ordinary concurrent-write conflict.
    *
    * The matcher below recognizes ONLY DuckLake's known-transient
    * write-write/concurrent-modification signature -- reusing the word check already pinned against
    * a live DuckLake probe by [[ai.starlake.quack.ondemand.api.CatalogRestoreHandlers.isConflict]]
    * (`docs/superpowers/plans/2026-07-17-restore-rollback.md`, `docs/duckdb-pin-bump-checklist.md`
    * #7): DuckLake's optimistic-concurrency loser message contains the word "conflict". It does NOT
    * match DuckLake's generic commit-failure wrapper (`Failed to commit: Failed to commit DuckLake
    * transaction. <cause>`), which DuckLake emits for EVERY failure during commit regardless of
    * cause. The exemplar reproduced empirically 2026-09-18 by hammering concurrent
    * `CREATE OR REPLACE TABLE` DDL against one ducklake-backed node -- `Invalid Input Error: Failed
    * to commit: Failed to commit DuckLake transaction. Failed to load DuckLake - table with id N
    * references schema id M that does not exist` -- does NOT contain "conflict" and is deliberately
    * NOT matched here: that hammering left the pool PERSISTENTLY corrupted (see the #106 report),
    * i.e. that exact signature was not something a client retry would ever resolve. Matching the
    * generic wrapper text alone would launder permanent failures (disk full, a corrupted catalog,
    * revoked write credentials) into an unbounded client retry loop, so this fails CLOSED toward
    * [[internalError]] -- only the narrower conflict marker earns a retryable UNAVAILABLE. DuckLake
    * gives no typed exception across the wire (it comes back as a plain
    * [[ai.starlake.quack.edge.adapter.QuackWireError.Permanent]] message string), so this matches
    * on text, same as the precedent it reuses.
    *
    * This does not fix the underlying race -- retrying a DDL/DML statement safely is a separate,
    * larger design question (see the #106 report) -- it only stops mis-classifying a
    * known-transient condition as an unexplained crash. Always logged at ERROR with a fresh
    * correlation id (same shape as [[internalError]]'s), even when reclassified to UNAVAILABLE: a
    * reclassified conflict is still an operator-relevant event, and this used to log at WARN,
    * invisible under the manager's default ERROR log level.
    */
  private[edge] def commitConflictOrInternal(context: String, t: Throwable): Throwable =
    val msg = Option(t.getMessage).getOrElse("")
    if msg.toLowerCase(Locale.ROOT).contains("conflict") then
      val errorId = java.util.UUID.randomUUID().toString.take(8)
      logger.error(
        s"$context: DuckLake write conflict [errorId=$errorId], client should retry: $msg",
        t
      )
      CallStatus.UNAVAILABLE
        .withDescription(
          s"concurrent write conflict committing the transaction (errorId=$errorId); " +
            "retry the statement"
        )
        .toRuntimeException()
    else internalError(context, t)

  /** #106 signature 2: DuckDB (verified against 1.5.x) does NOT stream every non-result statement
    * as the single-row `Count BIGINT` [[countSchema]] predicts. It splits:
    *   - `Count: BIGINT` for CREATE TABLE (AS), CREATE OR REPLACE TABLE, INSERT, UPDATE, MERGE,
    *     DELETE, CREATE INDEX, TRUNCATE
    *   - `Success: BOOLEAN` for BEGIN, COMMIT (txn control), VACUUM, ANALYZE, DROP, ALTER, SET
    *
    * Of that second bucket, only BEGIN/COMMIT/ROLLBACK/DROP/ALTER (`StatementKind.SkipExecute`
    * territory -- see [[PrepareStrategy.choose]]) actually reach this coercion: the edge advertises
    * `countSchema` for those WITHOUT probing, so a real `Success: BOOLEAN` reply drifts from what
    * was promised. VACUUM/ANALYZE/SET classify as `StatementKind.Other` -> `Unprobed`, meaning the
    * edge advertises no schema for them, so nothing is promised and coercion never triggers -- they
    * are DuckDB facts, not statements this code path handles.
    *
    * The split does not track the classifier's Read/Write/Ddl buckets (CREATE INDEX -> Count but
    * DROP INDEX -> Success), so predicting it per statement kind is a trap, not a fix -- this
    * coerces the ACTUAL stream instead, at the one place both the literal and prepared paths funnel
    * through ([[streamArrow]]). Triggers on the SCHEMA shape alone (single column named `Success`,
    * type BOOLEAN), not on row count: verified live against a real BEGIN over the native transport
    * that the row count backing that schema can be 0, not 1 (DuckDB reports zero materialized rows
    * for a txn-control statement even though the advertised column is `Success: BOOLEAN`) -- gating
    * on `rowCount == 1` silently never fired for that real case. A real SELECT, the admin dialect's
    * own `(status, detail)` substitution, and any other multi-column or differently-named result
    * all pass through untouched. The coerced row's Count is always 0 -- these statements have no
    * meaningful row count, and 0 documents "ran, produced no count" rather than inventing a number.
    *
    * This shape is assumed impossible for a genuine SELECT/RETURNING result (nothing in DML/DDL
    * should legitimately return real rows under a single nullable `Success: BOOLEAN` column), but
    * if it ever is -- e.g. `INSERT ... RETURNING <bool> AS "Success"` -- those rows are silently
    * replaced by the synthetic `Count=0` row; [[streamArrow]] logs a WARN when that happens so the
    * otherwise-silent data loss is at least auditable.
    */
  private[edge] def coerceSuccessToCount(root: VectorSchemaRoot): VectorSchemaRoot =
    if !isSuccessShape(root) then root
    else
      val coerced = VectorSchemaRoot.create(countSchema, allocator)
      val vec     = coerced.getVector("Count").asInstanceOf[BigIntVector]
      vec.allocateNew(1)
      vec.set(0, 0L)
      vec.setValueCount(1)
      coerced.setRowCount(1)
      coerced

  private def isSuccessShape(root: VectorSchemaRoot): Boolean =
    val fields = root.getSchema.getFields
    fields.size() == 1 &&
    fields.get(0).getName == "Success" &&
    fields.get(0).getType.isInstanceOf[ArrowType.Bool]

  /** Resolve a prepared handle into an executable plan, enforcing two invariants: the calling peer
    * must be the handle's creator (a leaked handle cannot be replayed), and the [[EffectiveSet]] is
    * read live from [[ConnectionContext]] (resolved again once the tenant's policy version moved,
    * so mid-session revocation is honored; an expired session denies instead of running).
    */
  private def resolvePreparedCall(
      handle: String,
      context: FlightProducer.CallContext
  ): Either[Throwable, PreparedCall] =
    val callerPeer = Option(context.peerIdentity()).getOrElse("anonymous")
    preparedStatements.get(handle) match
      case None =>
        Left(
          CallStatus.INVALID_ARGUMENT
            .withDescription(s"no such prepared statement: $handle")
            .toRuntimeException()
        )
      case Some(p) if p.ownerPeer != callerPeer =>
        logger.warn(
          s"prepared handle $handle owned by ${p.ownerPeer} but executed by $callerPeer; denying"
        )
        Left(
          CallStatus.UNAUTHORIZED
            .withDescription("prepared statement handle does not belong to this session")
            .toRuntimeException()
        )
      case Some(p) =>
        ConnectionContext.entry(callerPeer) match
          case Some(e) if e.user == p.user =>
            e.effectiveSet().left.map(toFlightException).map { eff =>
              PreparedCall(
                sql = p.sql,
                connId = p.connId,
                user = p.user,
                poolKey = p.poolKey,
                effectiveSet = eff,
                preferredNode = p.preferredNode,
                prepareDurationMs = p.prepareDurationMs,
                datasetSchema = p.datasetSchema
              )
            }
          case _ =>
            // Session gone (expired / unbound) or rebound to a different user: the handle
            // outlived the authorization that created it. Force a re-prepare.
            Left(
              CallStatus.UNAUTHENTICATED
                .withDescription(
                  "session expired or no longer bound; re-authenticate and re-prepare"
                )
                .toRuntimeException()
            )

  /** How a schema request may obtain `sql`'s result schema ([[PrepareStrategy]]). Pure. */
  private def strategyOf(sql: String): PrepareStrategy =
    PrepareStrategy.choose(sql, router.classifier.classify(sql))

  override def createPreparedStatement(
      request: FlightSql.ActionCreatePreparedStatementRequest,
      context: FlightProducer.CallContext,
      listener: FlightProducer.StreamListener[Result]
  ): Unit =
    val sql  = request.getQuery
    val peer = Option(context.peerIdentity()).getOrElse("anonymous")

    liveSession(peer, noContext(peer)) match
      case Right((ConnectionContext.Entry(poolKey, connId, user, _, _), eff)) =>
        // Registers the handle and answers the Prepare with the advertised schema (None: absent).
        // `answered` is what the Prepare result itself says, when it differs from `schema`.
        def prepared(
            schema: Option[Schema],
            node: Option[String],
            durationMs: Option[Long],
            answered: Option[ByteString] = None
        ) =
          val handle = java.util.UUID.randomUUID().toString
          preparedStatements.put(
            handle,
            PreparedExec(
              sql,
              ownerPeer = peer,
              connId,
              user,
              poolKey,
              preferredNode = node,
              prepareDurationMs = durationMs,
              datasetSchema = schema
            )
          )
          val resp = FlightSql.ActionCreatePreparedStatementResult
            .newBuilder()
            .setPreparedStatementHandle(ByteString.copyFromUtf8(handle))
            .setDatasetSchema(
              answered.getOrElse(schema.map(serializeSchema).getOrElse(ByteString.EMPTY))
            )
            // The ODBC driver throws on an ABSENT parameter_schema, so advertise
            // zero parameters explicitly via the empty schema.
            .setParameterSchema(emptySchemaBytes)
            .build()
          listener.onNext(new Result(ProtoAny.pack(resp).toByteArray))
          listener.onCompleted()

        // recordExecution = false: the operator-visible history row is the matching Execute,
        // which carries the probe's duration via prepareDurationMs.
        def probeThenPrepare(probeSql: String): Unit =
          scala.util.Try(
            router
              .execute(connId, user, poolKey, probeSql, eff, recordExecution = false)
              .unsafeRunSync()
          ) match
            case scala.util.Success(Right(result)) =>
              val schema =
                try result.rows.getVectorSchemaRoot.getSchema
                finally result.close()
              prepared(Some(schema), Some(result.nodeId), Some(result.durationMs))
            case scala.util.Success(Left(f)) =>
              listener.onError(toFlightException(f))
            case scala.util.Failure(t) =>
              listener.onError(internalError("createPreparedStatement", t))

        strategyOf(sql) match
          case PrepareStrategy.SkipExecute =>
            // No node call; advertise countSchema (see its doc for the ADBC why), except a claimed
            // SQL admin mutation, which always delivers (status, detail) - advertise
            // adminStatusSchema instead (see its doc for the incident this fixes). Not executed
            // here either way - only the schema advertised at Prepare differs.
            //
            // A Count result answers Prepare itself with zero fields, while GetFlightInfo and
            // GetSchema keep advertising Count. The Arrow JDBC driver decides from this answer
            // alone: with Count it takes the write for a query, and Statement.executeUpdate
            // returns -1 without reading the count from its result set. With zero fields it goes
            // through DoPut and gets the count. The answer is present rather than absent as the
            // ODBC driver refuses an absent parameter_schema; it reads the result schema from
            // FlightInfo and GetSchema, like ADBC.
            if ai.starlake.quack.edge.admin.AdminSqlParser.claims(sql) then
              prepared(Some(adminStatusSchema), None, None)
            else prepared(Some(countSchema), None, None, answered = Some(emptySchemaBytes))

          case PrepareStrategy.Unprobed =>
            // No node call: running the text to read its schema would run its effects, and the
            // Execute runs them again (audit F17). The schema is advertised as absent, which
            // FlightSQL clients read as "take it from the stream".
            prepared(None, None, None)

          case PrepareStrategy.ProbeWrap(probe) => probeThenPrepare(probe)
          case PrepareStrategy.FullExecute      => probeThenPrepare(sql)
      case Left(err) =>
        listener.onError(err)

  override def closePreparedStatement(
      request: FlightSql.ActionClosePreparedStatementRequest,
      context: FlightProducer.CallContext,
      listener: FlightProducer.StreamListener[Result]
  ): Unit =
    val handle = request.getPreparedStatementHandle.toStringUtf8
    preparedStatements.remove(handle)
    listener.onCompleted()

  /** Return the cached Prepare-time schema: the ODBC driver calls GetSchema before fetching rows,
    * and the NoOp default (UNIMPLEMENTED) breaks Power BI.
    */
  override def getSchemaPreparedStatement(
      command: FlightSql.CommandPreparedStatementQuery,
      context: FlightProducer.CallContext,
      descriptor: FlightDescriptor
  ): SchemaResult =
    val handle = command.getPreparedStatementHandle.toStringUtf8
    preparedStatements.get(handle) match
      case Some(p) => new SchemaResult(p.datasetSchema.getOrElse(emptySchema))
      case None    =>
        throw CallStatus.INVALID_ARGUMENT
          .withDescription(s"no such prepared statement: $handle")
          .toRuntimeException()

  override def getFlightInfoPreparedStatement(
      command: FlightSql.CommandPreparedStatementQuery,
      context: FlightProducer.CallContext,
      descriptor: FlightDescriptor
  ): FlightInfo =
    val handle = command.getPreparedStatementHandle.toStringUtf8
    preparedStatements.get(handle) match
      case None =>
        throw CallStatus.INVALID_ARGUMENT
          .withDescription(s"no such prepared statement: $handle")
          .toRuntimeException()
      case Some(p) =>
        val ticket   = new Ticket(ProtoAny.pack(command).toByteArray)
        val endpoint = new FlightEndpoint(ticket)
        // The ODBC driver reads the result schema from FlightInfo.schema (not the
        // GetSchema RPC): a null here breaks Power BI, and the cached Prepare-time
        // schema matches the DoGet stream so ADBC's mismatch guard stays happy. An unprobed
        // statement has no schema to give: absent (null) lets ADBC take the stream's, where a
        // zero-field schema would fail its guard. The Arrow JDBC driver never gets here for one:
        // it reads the absent schema as an update and goes through DoPut, so it sees no rows.
        new FlightInfo(
          p.datasetSchema.orNull,
          descriptor,
          Collections.singletonList(endpoint),
          -1L,
          -1L
        )

  override def getStreamPreparedStatement(
      command: FlightSql.CommandPreparedStatementQuery,
      context: FlightProducer.CallContext,
      listener: FlightProducer.ServerStreamListener
  ): Unit =
    val handle = command.getPreparedStatementHandle.toStringUtf8
    // Re-execute on every Execute so the handle stays valid until
    // ClosePreparedStatement; auth invariants live in resolvePreparedCall.
    resolvePreparedCall(handle, context) match
      case Left(err) => listener.error(err)
      case Right(p)  =>
        router
          .execute(
            p.connId,
            p.user,
            p.poolKey,
            p.sql,
            p.effectiveSet,
            p.preferredNode,
            prepareDurationMs = p.prepareDurationMs
          )
          .unsafeToFuture()
          .onComplete {
            case scala.util.Success(Right(result)) =>
              // p.datasetSchema is exactly what Prepare advertised (cached, not recomputed): the
              // SkipExecute branch of createPreparedStatement sets it to countSchema (or
              // adminStatusSchema for a claimed admin mutation), same rule as the literal path.
              val advertisedWasCount = p.datasetSchema.contains(countSchema)
              try streamArrow(result.rows, listener, advertisedWasCount)
              catch
                case t: Throwable =>
                  listener.error(commitConflictOrInternal("streaming Arrow batches", t))
              finally result.close()
            case scala.util.Success(Left(f)) =>
              listener.error(toFlightException(f))
            case scala.util.Failure(t) =>
              listener.error(internalError("getStreamPreparedStatement re-execute", t))
          }(using scala.concurrent.ExecutionContext.global)

  // -----------------------------------------------------------------
  //  Metadata endpoints: each translates the Flight SQL request into a
  //  query against the node's information_schema, via the router.
  // -----------------------------------------------------------------

  private def quote(s: String): String = s.replace("'", "''")

  override def getFlightInfoCatalogs(
      command: FlightSql.CommandGetCatalogs,
      context: FlightProducer.CallContext,
      descriptor: FlightDescriptor
  ): FlightInfo =
    singleEndpointInfo(command, Schemas.GET_CATALOGS_SCHEMA, descriptor)

  override def getStreamCatalogs(
      context: FlightProducer.CallContext,
      listener: FlightProducer.ServerStreamListener
  ): Unit =
    runStatement(
      """SELECT DISTINCT catalog_name FROM information_schema.schemata
        |WHERE catalog_name NOT IN ('information_schema', 'pg_catalog', 'system', 'temp', 'memory')
        |  AND catalog_name NOT LIKE '\_\_ducklake\_metadata\_%' ESCAPE '\'
        |ORDER BY 1""".stripMargin,
      context,
      listener,
      Some(Schemas.GET_CATALOGS_SCHEMA)
    )

  override def getFlightInfoSchemas(
      command: FlightSql.CommandGetDbSchemas,
      context: FlightProducer.CallContext,
      descriptor: FlightDescriptor
  ): FlightInfo =
    singleEndpointInfo(command, Schemas.GET_SCHEMAS_SCHEMA, descriptor)

  override def getStreamSchemas(
      command: FlightSql.CommandGetDbSchemas,
      context: FlightProducer.CallContext,
      listener: FlightProducer.ServerStreamListener
  ): Unit =
    val catalog       = if command.hasCatalog then Some(command.getCatalog) else None
    val schemaPattern =
      if command.hasDbSchemaFilterPattern then Some(command.getDbSchemaFilterPattern) else None
    val filters = (
      // LIKE not = : the ODBC driver forwards the SQLTables wildcard '%' as a
      // literal catalog value; literal names still match themselves.
      catalog.map(c => s"catalog_name LIKE '${quote(c)}'") ::
        schemaPattern.map(p => s"schema_name LIKE '${quote(p)}'") ::
        Some("schema_name NOT IN ('information_schema', 'pg_catalog')") :: Nil
    ).flatten.mkString(" AND ")
    runStatement(
      s"""SELECT catalog_name, schema_name AS db_schema_name
         |FROM information_schema.schemata
         |WHERE $filters
         |ORDER BY 1, 2""".stripMargin,
      context,
      listener,
      Some(Schemas.GET_SCHEMAS_SCHEMA)
    )

  override def getFlightInfoTables(
      command: FlightSql.CommandGetTables,
      context: FlightProducer.CallContext,
      descriptor: FlightDescriptor
  ): FlightInfo =
    // include_schema=true emits a fifth `table_schema` column (the per-table
    // Arrow schema, IPC-serialized) on top of GET_TABLES_SCHEMA_NO_SCHEMA.
    val schema =
      if command.getIncludeSchema then Schemas.GET_TABLES_SCHEMA
      else Schemas.GET_TABLES_SCHEMA_NO_SCHEMA
    singleEndpointInfo(command, schema, descriptor)

  override def getStreamTables(
      command: FlightSql.CommandGetTables,
      context: FlightProducer.CallContext,
      listener: FlightProducer.ServerStreamListener
  ): Unit =
    val catalog       = if command.hasCatalog then Some(command.getCatalog) else None
    val schemaPattern =
      if command.hasDbSchemaFilterPattern then Some(command.getDbSchemaFilterPattern) else None
    val tablePattern =
      if command.hasTableNameFilterPattern then Some(command.getTableNameFilterPattern) else None
    val filters = (
      catalog.map(c => s"table_catalog LIKE '${quote(c)}'") ::
        schemaPattern.map(p => s"table_schema LIKE '${quote(p)}'") ::
        tablePattern.map(p => s"table_name LIKE '${quote(p)}'") ::
        Some("table_schema NOT IN ('information_schema', 'pg_catalog')") :: Nil
    ).flatten.mkString(" AND ")
    val listSql =
      s"""SELECT
         |  table_catalog AS catalog_name,
         |  table_schema  AS db_schema_name,
         |  table_name,
         |  CASE WHEN table_type = 'BASE TABLE' THEN 'TABLE' ELSE table_type END AS table_type
         |FROM information_schema.tables
         |WHERE $filters
         |ORDER BY 1, 2, 3""".stripMargin
    if !command.getIncludeSchema then
      runStatement(listSql, context, listener, Some(Schemas.GET_TABLES_SCHEMA_NO_SCHEMA))
    else streamTablesWithSchema(listSql, context, listener)

  /** include_schema=true variant of getStreamTables: each row carries the table's Arrow schema as
    * IPC bytes. Probes each table with LIMIT 0 and emits a locally built root (the per-table-schema
    * shape isn't expressible as one SQL).
    */
  private def streamTablesWithSchema(
      listSql: String,
      context: FlightProducer.CallContext,
      listener: FlightProducer.ServerStreamListener
  ): Unit =
    val peer = Option(context.peerIdentity()).getOrElse("anonymous")
    liveSession(peer, noContext(peer)) match
      case Right((ConnectionContext.Entry(poolKey, connId, user, _, _), eff)) =>
        val tablesAttempt = scala.util.Try(
          router.execute(connId, user, poolKey, listSql, eff).unsafeRunSync()
        )
        tablesAttempt match
          case scala.util.Failure(t) =>
            listener.error(internalError("streamTablesWithSchema list", t))
          case scala.util.Success(Left(f)) =>
            listener.error(toFlightException(f))
          case scala.util.Success(Right(listResult)) =>
            val rows        = collectRowsAndClose(listResult)
            val withSchemas = rows.map { case (cat, sch, name, typ) =>
              val schemaBytes = probeTableSchema(connId, user, poolKey, cat, sch, name, eff)
              (cat, sch, name, typ, schemaBytes)
            }
            emitTablesWithSchema(withSchemas, listener)
      case Left(err) =>
        listener.error(err)

  /** Drain a QueryResult into a List of (catalog, schema, name, type) tuples. */
  private def collectRowsAndClose(
      result: QueryResult
  ): List[(String, String, String, String)] =
    try
      val buf    = scala.collection.mutable.ListBuffer.empty[(String, String, String, String)]
      val root   = result.rows.getVectorSchemaRoot
      val catVec =
        root.getVector("catalog_name").asInstanceOf[org.apache.arrow.vector.VarCharVector]
      val schVec =
        root.getVector("db_schema_name").asInstanceOf[org.apache.arrow.vector.VarCharVector]
      val nameVec = root.getVector("table_name").asInstanceOf[org.apache.arrow.vector.VarCharVector]
      val typVec  = root.getVector("table_type").asInstanceOf[org.apache.arrow.vector.VarCharVector]
      while result.rows.loadNextBatch() do
        var i = 0
        while i < root.getRowCount do
          val cat =
            if catVec.isNull(i) then "" else new String(catVec.get(i), StandardCharsets.UTF_8)
          val sch =
            if schVec.isNull(i) then "" else new String(schVec.get(i), StandardCharsets.UTF_8)
          val name =
            if nameVec.isNull(i) then "" else new String(nameVec.get(i), StandardCharsets.UTF_8)
          val typ =
            if typVec.isNull(i) then "" else new String(typVec.get(i), StandardCharsets.UTF_8)
          buf += ((cat, sch, name, typ))
          i += 1
      buf.toList
    finally result.close()

  /** Probe one table's schema via LIMIT 0; empty bytes on failure so one bad table doesn't break
    * the whole metadata response.
    */
  private def probeTableSchema(
      connId: String,
      user: String,
      poolKey: ai.starlake.quack.model.PoolKey,
      cat: String,
      sch: String,
      name: String,
      effectiveSet: Option[ai.starlake.quack.ondemand.rbac.EffectiveSet] = None
  ): Array[Byte] =
    val ident = s""""${cat.replace("\"", "\"\"")}"."${sch
        .replace("\"", "\"\"")}"."${name.replace("\"", "\"\"")}""""
    val probe = s"SELECT * FROM $ident LIMIT 0"
    scala.util.Try(router.execute(connId, user, poolKey, probe, effectiveSet).unsafeRunSync()) match
      case scala.util.Success(Right(qr)) =>
        try
          // Drain one batch so the IPC schema message is fully parsed.
          qr.rows.loadNextBatch()
          serializeSchema(qr.rows.getVectorSchemaRoot.getSchema).toByteArray
        catch
          case t: Throwable =>
            logger.warn(s"probe schema for $ident failed: ${t.getMessage}")
            Array.emptyByteArray
        finally qr.close()
      case other =>
        logger.warn(s"probe schema for $ident failed: $other")
        Array.emptyByteArray

  /** Build a single-batch VectorSchemaRoot conforming to the Flight SQL
    * `GetTables(include_schema=true)` schema, fill it, and stream to listener.
    */
  private def emitTablesWithSchema(
      rows: List[(String, String, String, String, Array[Byte])],
      listener: FlightProducer.ServerStreamListener
  ): Unit =
    import org.apache.arrow.vector.types.pojo.{ArrowType, FieldType}
    import org.apache.arrow.vector.{VarBinaryVector, VarCharVector}
    val utf8       = new ArrowType.Utf8()
    val binary     = new ArrowType.Binary()
    val nullable   = FieldType.nullable(utf8)
    val notNull    = new FieldType(false, utf8, null)
    val binNotNull = new FieldType(false, binary, null)
    val schema     = new Schema(
      java.util.Arrays.asList(
        new Field("catalog_name", nullable, null),
        new Field("db_schema_name", nullable, null),
        new Field("table_name", notNull, null),
        new Field("table_type", notNull, null),
        new Field("table_schema", binNotNull, null)
      )
    )
    val root = VectorSchemaRoot.create(schema, allocator)
    try
      root.allocateNew()
      val catVec    = root.getVector("catalog_name").asInstanceOf[VarCharVector]
      val schVec    = root.getVector("db_schema_name").asInstanceOf[VarCharVector]
      val nameVec   = root.getVector("table_name").asInstanceOf[VarCharVector]
      val typVec    = root.getVector("table_type").asInstanceOf[VarCharVector]
      val schemaVec = root.getVector("table_schema").asInstanceOf[VarBinaryVector]
      rows.zipWithIndex.foreach { case ((cat, sch, name, typ, bytes), i) =>
        if cat.isEmpty then catVec.setNull(i)
        else catVec.setSafe(i, cat.getBytes(StandardCharsets.UTF_8))
        if sch.isEmpty then schVec.setNull(i)
        else schVec.setSafe(i, sch.getBytes(StandardCharsets.UTF_8))
        nameVec.setSafe(i, name.getBytes(StandardCharsets.UTF_8))
        typVec.setSafe(i, typ.getBytes(StandardCharsets.UTF_8))
        schemaVec.setSafe(i, bytes)
      }
      root.setRowCount(rows.size)
      listener.start(root)
      listener.putNext()
      listener.completed()
    finally root.close()

  override def getFlightInfoTableTypes(
      command: FlightSql.CommandGetTableTypes,
      context: FlightProducer.CallContext,
      descriptor: FlightDescriptor
  ): FlightInfo =
    singleEndpointInfo(command, Schemas.GET_TABLE_TYPES_SCHEMA, descriptor)

  override def getStreamTableTypes(
      context: FlightProducer.CallContext,
      listener: FlightProducer.ServerStreamListener
  ): Unit =
    runStatement(
      "SELECT 'TABLE' AS table_type UNION ALL SELECT 'VIEW' AS table_type",
      context,
      listener,
      Some(Schemas.GET_TABLE_TYPES_SCHEMA)
    )

  // -----------------------------------------------------------------
  //  Key-metadata endpoints: DuckLake enforces no key constraints, so
  //  return empty result sets with the canonical schemas, not UNIMPLEMENTED.
  // -----------------------------------------------------------------

  private def emitEmpty(
      schema: Schema,
      listener: FlightProducer.ServerStreamListener
  ): Unit =
    val root = VectorSchemaRoot.create(schema, allocator)
    try
      root.allocateNew()
      root.setRowCount(0)
      listener.start(root)
      listener.putNext()
      listener.completed()
    finally root.close()

  override def getFlightInfoPrimaryKeys(
      command: FlightSql.CommandGetPrimaryKeys,
      context: FlightProducer.CallContext,
      descriptor: FlightDescriptor
  ): FlightInfo =
    singleEndpointInfo(command, Schemas.GET_PRIMARY_KEYS_SCHEMA, descriptor)

  override def getStreamPrimaryKeys(
      command: FlightSql.CommandGetPrimaryKeys,
      context: FlightProducer.CallContext,
      listener: FlightProducer.ServerStreamListener
  ): Unit =
    emitEmpty(Schemas.GET_PRIMARY_KEYS_SCHEMA, listener)

  override def getFlightInfoImportedKeys(
      command: FlightSql.CommandGetImportedKeys,
      context: FlightProducer.CallContext,
      descriptor: FlightDescriptor
  ): FlightInfo =
    singleEndpointInfo(command, Schemas.GET_IMPORTED_KEYS_SCHEMA, descriptor)

  override def getStreamImportedKeys(
      command: FlightSql.CommandGetImportedKeys,
      context: FlightProducer.CallContext,
      listener: FlightProducer.ServerStreamListener
  ): Unit =
    emitEmpty(Schemas.GET_IMPORTED_KEYS_SCHEMA, listener)

  override def getFlightInfoExportedKeys(
      command: FlightSql.CommandGetExportedKeys,
      context: FlightProducer.CallContext,
      descriptor: FlightDescriptor
  ): FlightInfo =
    singleEndpointInfo(command, Schemas.GET_EXPORTED_KEYS_SCHEMA, descriptor)

  override def getStreamExportedKeys(
      command: FlightSql.CommandGetExportedKeys,
      context: FlightProducer.CallContext,
      listener: FlightProducer.ServerStreamListener
  ): Unit =
    emitEmpty(Schemas.GET_EXPORTED_KEYS_SCHEMA, listener)

  override def getFlightInfoCrossReference(
      command: FlightSql.CommandGetCrossReference,
      context: FlightProducer.CallContext,
      descriptor: FlightDescriptor
  ): FlightInfo =
    singleEndpointInfo(command, Schemas.GET_CROSS_REFERENCE_SCHEMA, descriptor)

  override def getStreamCrossReference(
      command: FlightSql.CommandGetCrossReference,
      context: FlightProducer.CallContext,
      listener: FlightProducer.ServerStreamListener
  ): Unit =
    emitEmpty(Schemas.GET_CROSS_REFERENCE_SCHEMA, listener)

  /** Every DoPut starts here. Arrow's own acceptPut parses the command of the stream's first
    * message, and it waits for that message with no time limit, past the client's disconnect
    * (`FlightStream` completes its descriptor only from a message). So the session is checked
    * before any wait; then one slot and one deadline cover the wait and the drain. A task that gets
    * past them re-checks its session and runs its statement holding no slot.
    */
  override def acceptPut(
      context: FlightProducer.CallContext,
      flightStream: FlightStream,
      ackStream: FlightProducer.StreamListener[PutResult]
  ): Runnable =
    val peer     = Option(context.peerIdentity()).getOrElse("anonymous")
    val admitted =
      liveSession(peer, noContext(peer, _ => "no pool bound to session; authenticate first"))
        .flatMap(_ =>
          withinPutLimits(flightStream, arrowAcceptPut(context, flightStream, ackStream))
        )
    admitted match
      case Right(task) => task
      case Left(err)   => () => ackStream.onError(err)

  private def arrowAcceptPut(
      context: FlightProducer.CallContext,
      flightStream: FlightStream,
      ackStream: FlightProducer.StreamListener[PutResult]
  ): Runnable = super.acceptPut(context, flightStream, ackStream)

  /** `firstMessage` then the drain, under a slot and a deadline. Left when no slot is free, when
    * the client sends more than `maxPutDrainBytes`, or at the deadline. A failure of the stream
    * itself (its client gone, an unknown command) propagates to Arrow, which answers it on the ack
    * stream.
    */
  private def withinPutLimits(
      flightStream: FlightStream,
      firstMessage: => Runnable
  ): Either[Throwable, Runnable] =
    if !putDrainSlots.tryAcquire() then
      Left(
        CallStatus.UNAVAILABLE
          .withDescription("too many concurrent updates; retry")
          .toRuntimeException()
      )
    else
      try
        FlightProducerImpl
          .interruptAfter(putDrainTimeoutMs) {
            val task = firstMessage
            drainPutStream(flightStream, 0L).map(_ => task)
          }
          .getOrElse(
            Left(
              CallStatus.TIMED_OUT
                .withDescription(s"no data from the client within $putDrainTimeoutMs ms")
                .toRuntimeException()
            )
          )
      finally putDrainSlots.release()

  /** Drain and discard the DoPut request stream: half-closing without reading the client's
    * parameter batches breaks its in-flight write ("UNAVAILABLE: io exception").
    */
  @scala.annotation.tailrec
  private def drainPutStream(flightStream: FlightStream, seen: Long): Either[Throwable, Unit] =
    if !flightStream.next() then Right(())
    else
      val batch =
        if flightStream.hasRoot then
          flightStream.getRoot.getFieldVectors.asScala.map(_.getBufferSize.toLong).sum
        else 0L
      val total = seen + math.max(batch, FlightProducerImpl.MinBatchBytes)
      if total > maxPutDrainBytes then
        Left(
          CallStatus.INVALID_ARGUMENT
            .withDescription("bound parameters are not supported")
            .toRuntimeException()
        )
      else drainPutStream(flightStream, total)

  /** Literal executeUpdate entrypoint: run the SQL through the router and ack the affected-row
    * count as a DoPutUpdateResult. Bound parameters are never read: [[acceptPut]] has already
    * drained `flightStream`, so this does not touch it.
    */
  override def acceptPutStatement(
      command: FlightSql.CommandStatementUpdate,
      context: FlightProducer.CallContext,
      flightStream: FlightStream,
      ackStream: FlightProducer.StreamListener[PutResult]
  ): Runnable =
    val sql  = command.getQuery
    val peer = Option(context.peerIdentity()).getOrElse("anonymous")
    new Runnable:
      def run(): Unit =
        liveSession(
          peer,
          noContext(peer, _ => "no pool bound to session; authenticate first")
        ) match
          case Right((ConnectionContext.Entry(poolKey, connId, user, _, _), eff)) =>
            logger.debug(s"acceptPutStatement pool=$poolKey sql='${loggableSql(sql)}'")
            ackUpdateResult(
              scala.util.Try(router.execute(connId, user, poolKey, sql, eff).unsafeRunSync()),
              ackStream,
              "acceptPutStatement"
            )
          case Left(err) =>
            ackStream.onError(err)

  /** Prepared-update entrypoint: Arrow JDBC / ADBC / DBeaver prepare every statement, so literal
    * DML lands here rather than [[acceptPutStatement]]. Replays the Prepare-time SQL and acks the
    * count. Literal updates only: bound parameters are never read, as [[acceptPut]] has already
    * drained `flightStream`, so this does not touch it.
    */
  override def acceptPutPreparedStatementUpdate(
      command: FlightSql.CommandPreparedStatementUpdate,
      context: FlightProducer.CallContext,
      flightStream: FlightStream,
      ackStream: FlightProducer.StreamListener[PutResult]
  ): Runnable =
    val handle = command.getPreparedStatementHandle.toStringUtf8
    new Runnable:
      def run(): Unit =
        resolvePreparedCall(handle, context) match
          case Left(err) => ackStream.onError(err)
          case Right(p)  =>
            logger.debug(
              s"acceptPutPreparedStatementUpdate pool=${p.poolKey} sql='${loggableSql(p.sql)}'"
            )
            ackUpdateResult(
              scala.util.Try(
                router
                  .execute(
                    p.connId,
                    p.user,
                    p.poolKey,
                    p.sql,
                    p.effectiveSet,
                    p.preferredNode,
                    prepareDurationMs = p.prepareDurationMs
                  )
                  .unsafeRunSync()
              ),
              ackStream,
              "acceptPutPreparedStatementUpdate"
            )

  /** Shared tail of the two update entrypoints: outcome -> DoPutUpdateResult ack or typed error on
    * the ack stream. Closes the result either way.
    *
    * The result is drained to exhaustion BEFORE the count is acknowledged: a stamped write's
    * deferred COMMIT (`adapter.StampedCommitArrowReader`) fires on exhaustion and propagates a
    * failure there, while on close it is best-effort and swallowed. Acknowledging after a bare
    * close told the client a write had landed that the node rolled back (a DuckLake write-write
    * conflict, a node gone, a lapsed lease). A failure while draining is classified like DoGet's
    * ([[commitConflictOrInternal]]).
    */
  private def ackUpdateResult(
      attempt: scala.util.Try[Either[RouterFailure, QueryResult]],
      ackStream: FlightProducer.StreamListener[PutResult],
      label: String
  ): Unit =
    attempt match
      case scala.util.Success(Right(result)) =>
        val drained =
          try Right(drainUpdateCount(result.rows))
          catch case t: Throwable => Left(t)
          finally result.close()
        drained match
          case Right(count) => emitUpdateCount(count, ackStream)
          case Left(t)      => ackStream.onError(commitConflictOrInternal(label, t))
      case scala.util.Success(Left(f)) =>
        ackStream.onError(toFlightException(f))
      case scala.util.Failure(t) =>
        ackStream.onError(internalError(label, t))

  /** Drain `reader` to exhaustion and return the affected-row count: see [[updateCountOf]]. A
    * failure to read the stream (the deferred COMMIT among them) propagates.
    *
    * Never -1, though FlightSQL defines it as "unknown". The Arrow JDBC driver hands the value to
    * Avatica, where -1 means "no count, open a result set". On a prepared statement that signature
    * is null, so the client hits an NPE. On a plain statement the result set fails, Avatica retries
    * the statement, and each of its 5 attempts runs it again through DoPut and DoGet (issue 170).
    */
  private def drainUpdateCount(reader: org.apache.arrow.vector.ipc.ArrowReader): Long =
    var count = 0L
    while reader.loadNextBatch() do count += updateCountOf(reader.getVectorSchemaRoot).max(0L)
    count

  /** What one batch adds to the count: DuckDB's `Count` cell, 0 for its `Success` answer ("ran,
    * produced no count", as in [[coerceSuccessToCount]]), and the rows of any other result. Only
    * the `Count` column carries a count: the first cell of a `RETURNING id` is an id.
    */
  private def updateCountOf(root: VectorSchemaRoot): Long =
    val fields = root.getSchema.getFields
    if isSuccessShape(root) then 0L
    else if fields.size() == 1 && fields.get(0).getName == "Count" && root.getRowCount >= 1 then
      root.getVector(0).getObject(0) match
        case n: java.lang.Number => n.longValue()
        case _                   => root.getRowCount.toLong
    else root.getRowCount.toLong

  /** Ack the update count; the buffer is released only after onNext + onCompleted have consumed it.
    */
  private def emitUpdateCount(
      count: Long,
      ackStream: FlightProducer.StreamListener[PutResult]
  ): Unit =
    val result = FlightSql.DoPutUpdateResult.newBuilder().setRecordCount(count).build()
    val buf    = allocator.buffer(result.getSerializedSize.toLong)
    try
      buf.writeBytes(result.toByteArray)
      ackStream.onNext(PutResult.metadata(buf))
      ackStream.onCompleted()
    finally buf.close()

  // -----------------------------------------------------------------
  //  Type info + SQL info: ODBC clients read these to learn the type
  //  system and SQL capabilities (NoOp base throws UNIMPLEMENTED).
  // -----------------------------------------------------------------

  override def getFlightInfoTypeInfo(
      request: FlightSql.CommandGetXdbcTypeInfo,
      context: FlightProducer.CallContext,
      descriptor: FlightDescriptor
  ): FlightInfo =
    singleEndpointInfo(request, Schemas.GET_TYPE_INFO_SCHEMA, descriptor)

  override def getStreamTypeInfo(
      request: FlightSql.CommandGetXdbcTypeInfo,
      context: FlightProducer.CallContext,
      listener: FlightProducer.ServerStreamListener
  ): Unit =
    val filter = if request.hasDataType then Some(request.getDataType) else None
    emitTypeInfo(TypeInfoCatalog.filterByDataType(filter), listener)

  private def emitTypeInfo(
      rows: List[TypeInfoRow],
      listener: FlightProducer.ServerStreamListener
  ): Unit =
    val schema = Schemas.GET_TYPE_INFO_SCHEMA
    val root   = VectorSchemaRoot.create(schema, allocator)
    try
      root.allocateNew()
      val typeNameVec =
        root.getVector("type_name").asInstanceOf[org.apache.arrow.vector.VarCharVector]
      val dataTypeVec =
        root.getVector("data_type").asInstanceOf[org.apache.arrow.vector.IntVector]
      val columnSizeVec =
        root.getVector("column_size").asInstanceOf[org.apache.arrow.vector.IntVector]
      val literalPrefixVec =
        root.getVector("literal_prefix").asInstanceOf[org.apache.arrow.vector.VarCharVector]
      val literalSuffixVec =
        root.getVector("literal_suffix").asInstanceOf[org.apache.arrow.vector.VarCharVector]
      val createParamsVec =
        root.getVector("create_params").asInstanceOf[org.apache.arrow.vector.complex.ListVector]
      val nullableVec =
        root.getVector("nullable").asInstanceOf[org.apache.arrow.vector.IntVector]
      val caseSensitiveVec =
        root.getVector("case_sensitive").asInstanceOf[org.apache.arrow.vector.BitVector]
      val searchableVec =
        root.getVector("searchable").asInstanceOf[org.apache.arrow.vector.IntVector]
      val unsignedAttributeVec =
        root.getVector("unsigned_attribute").asInstanceOf[org.apache.arrow.vector.BitVector]
      val fixedPrecScaleVec =
        root.getVector("fixed_prec_scale").asInstanceOf[org.apache.arrow.vector.BitVector]
      val autoIncrementVec =
        root.getVector("auto_increment").asInstanceOf[org.apache.arrow.vector.BitVector]
      val localTypeNameVec =
        root.getVector("local_type_name").asInstanceOf[org.apache.arrow.vector.VarCharVector]
      val minimumScaleVec =
        root.getVector("minimum_scale").asInstanceOf[org.apache.arrow.vector.IntVector]
      val maximumScaleVec =
        root.getVector("maximum_scale").asInstanceOf[org.apache.arrow.vector.IntVector]
      val sqlDataTypeVec =
        root.getVector("sql_data_type").asInstanceOf[org.apache.arrow.vector.IntVector]
      val datetimeSubcodeVec =
        root.getVector("datetime_subcode").asInstanceOf[org.apache.arrow.vector.IntVector]
      val numPrecRadixVec =
        root.getVector("num_prec_radix").asInstanceOf[org.apache.arrow.vector.IntVector]
      val intervalPrecisionVec =
        root.getVector("interval_precision").asInstanceOf[org.apache.arrow.vector.IntVector]

      rows.zipWithIndex.foreach { case (r, i) =>
        typeNameVec.setSafe(i, r.typeName.getBytes(StandardCharsets.UTF_8))
        dataTypeVec.setSafe(i, r.dataType)
        setOptionInt(columnSizeVec, i, r.columnSize)
        setOptionStr(literalPrefixVec, i, r.literalPrefix)
        setOptionStr(literalSuffixVec, i, r.literalSuffix)
        // Null list: ODBC consumers only use create_params when issuing DDL.
        createParamsVec.setNull(i)
        nullableVec.setSafe(i, r.nullable)
        caseSensitiveVec.setSafe(i, if r.caseSensitive then 1 else 0)
        searchableVec.setSafe(i, r.searchable)
        setOptionBit(unsignedAttributeVec, i, r.unsignedAttribute)
        fixedPrecScaleVec.setSafe(i, if r.fixedPrecScale then 1 else 0)
        setOptionBit(autoIncrementVec, i, r.autoIncrement)
        setOptionStr(localTypeNameVec, i, r.localTypeName)
        setOptionInt(minimumScaleVec, i, r.minimumScale)
        setOptionInt(maximumScaleVec, i, r.maximumScale)
        sqlDataTypeVec.setSafe(i, r.sqlDataType)
        setOptionInt(datetimeSubcodeVec, i, r.datetimeSubcode)
        setOptionInt(numPrecRadixVec, i, r.numPrecRadix)
        setOptionInt(intervalPrecisionVec, i, r.intervalPrecision)
      }
      root.setRowCount(rows.size)
      listener.start(root)
      listener.putNext()
      listener.completed()
    finally root.close()

  private def setOptionInt(
      vec: org.apache.arrow.vector.IntVector,
      i: Int,
      v: Option[Int]
  ): Unit = v match
    case Some(x) => vec.setSafe(i, x)
    case None    => vec.setNull(i)

  private def setOptionStr(
      vec: org.apache.arrow.vector.VarCharVector,
      i: Int,
      v: Option[String]
  ): Unit = v match
    case Some(x) => vec.setSafe(i, x.getBytes(StandardCharsets.UTF_8))
    case None    => vec.setNull(i)

  private def setOptionBit(
      vec: org.apache.arrow.vector.BitVector,
      i: Int,
      v: Option[Boolean]
  ): Unit = v match
    case Some(x) => vec.setSafe(i, if x then 1 else 0)
    case None    => vec.setNull(i)

  /** Full standard SqlInfo set: any omitted code surfaces to ODBC clients as "Unknown GetInfo type:
    * N". Values reflect DuckDB's actual behavior; max-length codes use 0 ("no fixed limit"),
    * function lists use JDBC escape names.
    */
  private val sqlInfoBuilder: org.apache.arrow.flight.sql.SqlInfoBuilder =
    import org.apache.arrow.flight.sql.impl.FlightSql.*
    new org.apache.arrow.flight.sql.SqlInfoBuilder()
      // ---- Server identification + top-level capabilities ----
      .withFlightSqlServerName("Quack on Demand")
      .withFlightSqlServerVersion("1.0")
      .withFlightSqlServerArrowVersion("18.3.0")
      .withFlightSqlServerReadOnly(false)
      .withFlightSqlServerSql(true)
      .withFlightSqlServerSubstrait(false)
      // NONE: inline BEGIN/COMMIT/ROLLBACK work via the query path, but the FlightSql
      // BeginTransaction/EndTransaction actions are UNIMPLEMENTED; advertising
      // TRANSACTION made ADBC autocommit-off clients call them and hard-fail.
      .withFlightSqlServerTransaction(
        SqlSupportedTransaction.SQL_SUPPORTED_TRANSACTION_NONE
      )
      .withFlightSqlServerCancel(false)
      // ---- DDL surface ----
      .withSqlDdlCatalog(true)
      .withSqlDdlSchema(true)
      .withSqlDdlTable(true)
      // ---- Identifier handling ----
      .withSqlIdentifierCase(
        SqlSupportedCaseSensitivity.SQL_CASE_SENSITIVITY_LOWERCASE
      )
      .withSqlIdentifierQuoteChar("\"")
      // DuckDB preserves quoted identifiers as-is (no folding). The proto enum
      // has no "mixed case" / "preserve" value, so `UNKNOWN` is the closest fit.
      .withSqlQuotedIdentifierCase(
        SqlSupportedCaseSensitivity.SQL_CASE_SENSITIVITY_UNKNOWN
      )
      .withSqlAllTablesAreSelectable(true)
      .withSqlNullOrdering(SqlNullOrdering.SQL_NULLS_SORTED_AT_END)
      .withSqlKeywords(Array.empty[String])
      // ---- Function lists (JDBC escape names) ----
      .withSqlNumericFunctions(
        Array(
          "ABS",
          "ACOS",
          "ASIN",
          "ATAN",
          "ATAN2",
          "CEILING",
          "COS",
          "COT",
          "DEGREES",
          "EXP",
          "FLOOR",
          "LOG",
          "LOG10",
          "MOD",
          "PI",
          "POWER",
          "RADIANS",
          "RAND",
          "ROUND",
          "SIGN",
          "SIN",
          "SQRT",
          "TAN",
          "TRUNCATE"
        )
      )
      .withSqlStringFunctions(
        Array(
          "ASCII",
          "CHAR",
          "CHAR_LENGTH",
          "CHARACTER_LENGTH",
          "CONCAT",
          "LCASE",
          "LEFT",
          "LENGTH",
          "LOCATE",
          "LOWER",
          "LTRIM",
          "OCTET_LENGTH",
          "POSITION",
          "REPEAT",
          "REPLACE",
          "RIGHT",
          "RTRIM",
          "SPACE",
          "SUBSTRING",
          "TRIM",
          "UCASE",
          "UPPER"
        )
      )
      .withSqlSystemFunctions(Array("DATABASE", "IFNULL", "USER"))
      .withSqlDatetimeFunctions(
        Array(
          "CURRENT_DATE",
          "CURRENT_TIME",
          "CURRENT_TIMESTAMP",
          "DAYNAME",
          "DAYOFMONTH",
          "DAYOFWEEK",
          "DAYOFYEAR",
          "EXTRACT",
          "HOUR",
          "MINUTE",
          "MONTH",
          "MONTHNAME",
          "NOW",
          "QUARTER",
          "SECOND",
          "WEEK",
          "YEAR"
        )
      )
      .withSqlSearchStringEscape("\\")
      .withSqlExtraNameCharacters("$_")
      // No CAST source/target matrix advertised. Clients fall back to TRY_CAST /
      // their own type-promotion rules instead of relying on the matrix.
      .withSqlSupportsConvert(java.util.Collections.emptyMap[Integer, java.util.List[Integer]])
      // ---- Grammar / dialect ----
      .withSqlSupportsColumnAliasing(true)
      .withSqlNullPlusNullIsNull(true)
      .withSqlSupportsTableCorrelationNames(true)
      .withSqlSupportsDifferentTableCorrelationNames(true)
      .withSqlSupportsExpressionsInOrderBy(true)
      .withSqlSupportsOrderByUnrelated(true)
      .withSqlSupportedGroupBy(
        SqlSupportedGroupBy.SQL_GROUP_BY_UNRELATED,
        SqlSupportedGroupBy.SQL_GROUP_BY_BEYOND_SELECT
      )
      .withSqlSupportsLikeEscapeClause(true)
      .withSqlSupportsNonNullableColumns(true)
      .withSqlSupportedGrammar(
        SupportedSqlGrammar.SQL_CORE_GRAMMAR,
        SupportedSqlGrammar.SQL_MINIMUM_GRAMMAR,
        SupportedSqlGrammar.SQL_EXTENDED_GRAMMAR
      )
      .withSqlAnsi92SupportedLevel(
        SupportedAnsi92SqlGrammarLevel.ANSI92_ENTRY_SQL,
        SupportedAnsi92SqlGrammarLevel.ANSI92_INTERMEDIATE_SQL,
        SupportedAnsi92SqlGrammarLevel.ANSI92_FULL_SQL
      )
      .withSqlSupportsIntegrityEnhancementFacility(false)
      .withSqlOuterJoinSupportLevel(
        SqlOuterJoinsSupportLevel.SQL_FULL_OUTER_JOINS,
        SqlOuterJoinsSupportLevel.SQL_LIMITED_OUTER_JOINS
      )
      // ---- Vocabulary ----
      .withSqlSchemaTerm("schema")
      .withSqlProcedureTerm("procedure")
      .withSqlCatalogTerm("database")
      .withSqlCatalogAtStart(true)
      .withSqlSchemasSupportedActions(
        SqlSupportedElementActions.SQL_ELEMENT_IN_PROCEDURE_CALLS,
        SqlSupportedElementActions.SQL_ELEMENT_IN_INDEX_DEFINITIONS,
        SqlSupportedElementActions.SQL_ELEMENT_IN_PRIVILEGE_DEFINITIONS
      )
      .withSqlCatalogsSupportedActions(
        SqlSupportedElementActions.SQL_ELEMENT_IN_PROCEDURE_CALLS,
        SqlSupportedElementActions.SQL_ELEMENT_IN_INDEX_DEFINITIONS,
        SqlSupportedElementActions.SQL_ELEMENT_IN_PRIVILEGE_DEFINITIONS
      )
      .withSqlSupportedPositionedCommands(
        SqlSupportedPositionedCommands.SQL_POSITIONED_DELETE,
        SqlSupportedPositionedCommands.SQL_POSITIONED_UPDATE
      )
      .withSqlSelectForUpdateSupported(false)
      .withSqlStoredProceduresSupported(false)
      .withSqlSubQueriesSupported(
        SqlSupportedSubqueries.SQL_SUBQUERIES_IN_COMPARISONS,
        SqlSupportedSubqueries.SQL_SUBQUERIES_IN_EXISTS,
        SqlSupportedSubqueries.SQL_SUBQUERIES_IN_INS,
        SqlSupportedSubqueries.SQL_SUBQUERIES_IN_QUANTIFIEDS
      )
      .withSqlCorrelatedSubqueriesSupported(true)
      .withSqlSupportedUnions(
        SqlSupportedUnions.SQL_UNION,
        SqlSupportedUnions.SQL_UNION_ALL
      )
      // ---- Maximum-length limits (0 = unbounded per ODBC convention) ----
      .withSqlMaxBinaryLiteralLength(0L)
      .withSqlMaxCharLiteralLength(0L)
      .withSqlMaxColumnNameLength(0L)
      .withSqlMaxColumnsInGroupBy(0L)
      .withSqlMaxColumnsInIndex(0L)
      .withSqlMaxColumnsInOrderBy(0L)
      .withSqlMaxColumnsInSelect(0L)
      .withSqlMaxColumnsInTable(0L)
      .withSqlMaxConnections(0L)
      .withSqlMaxCursorNameLength(0L)
      .withSqlMaxIndexLength(0L)
      .withSqlDbSchemaNameLength(0L)
      .withSqlMaxProcedureNameLength(0L)
      .withSqlMaxCatalogNameLength(0L)
      .withSqlMaxRowSize(0L)
      .withSqlMaxRowSizeIncludesBlobs(true)
      .withSqlMaxStatementLength(0L)
      .withSqlMaxStatements(0L)
      .withSqlMaxTableNameLength(0L)
      .withSqlMaxTablesInSelect(0L)
      .withSqlMaxUsernameLength(0L)
      // ---- Transactions ----
      .withSqlDefaultTransactionIsolation(
        SqlTransactionIsolationLevel.SQL_TRANSACTION_SERIALIZABLE.getNumber.toLong
      )
      .withSqlTransactionsSupported(true)
      .withSqlSupportedTransactionsIsolationLevels(
        SqlTransactionIsolationLevel.SQL_TRANSACTION_SERIALIZABLE
      )
      .withSqlDataDefinitionCausesTransactionCommit(false)
      .withSqlDataDefinitionsInTransactionsIgnored(false)
      .withSqlSupportedResultSetTypes(
        SqlSupportedResultSetType.SQL_RESULT_SET_TYPE_FORWARD_ONLY
      )
      .withSqlBatchUpdatesSupported(true)
      .withSqlSavepointsSupported(false)
      .withSqlNamedParametersSupported(false)
      .withSqlLocatorsUpdateCopy(false)
      .withSqlStoredFunctionsUsingCallSyntaxSupported(false)

  override def getFlightInfoSqlInfo(
      request: FlightSql.CommandGetSqlInfo,
      context: FlightProducer.CallContext,
      descriptor: FlightDescriptor
  ): FlightInfo =
    singleEndpointInfo(request, Schemas.GET_SQL_INFO_SCHEMA, descriptor)

  override def getStreamSqlInfo(
      command: FlightSql.CommandGetSqlInfo,
      context: FlightProducer.CallContext,
      listener: FlightProducer.ServerStreamListener
  ): Unit =
    sqlInfoBuilder.send(command.getInfoList, listener)

  override def getFlightInfoStatement(
      command: FlightSql.CommandStatementQuery,
      context: FlightProducer.CallContext,
      descriptor: FlightDescriptor
  ): FlightInfo =
    // Ticket bytes must be an Any-wrapped TicketStatementQuery; raw SQL bytes
    // silently fail the NoOpFlightSqlProducer.getStream dispatch.
    val peer = Option(context.peerIdentity()).getOrElse("anonymous")
    val tsq  = FlightSql.TicketStatementQuery
      .newBuilder()
      .setStatementHandle(ByteString.copyFromUtf8(command.getQuery))
      .build()
    val ticket = new Ticket(ProtoAny.pack(tsq).toByteArray)
    // No locations: client follows up on the same connection.
    val endpoint = new FlightEndpoint(ticket)
    // The ODBC driver reads the result schema from FlightInfo.schema, so probe it
    // here. DML/DDL probes None -> advertise countSchema; an empty schema would trip
    // ADBC's FlightInfo.schema == DoGet stream guard. A claimed SQL admin mutation is DDL
    // too (probes None the same way) but actually delivers (status, detail), not Count - see
    // adminStatusSchema's doc for the incident this mirrors on the prepared path. An unprobed
    // statement advertises no schema (null): the DoGet stream carries it.
    val sql    = command.getQuery
    val schema = probeStatementSchema(peer, sql).getOrElse(
      if strategyOf(sql) == PrepareStrategy.Unprobed then null
      else if ai.starlake.quack.edge.admin.AdminSqlParser.claims(sql) then adminStatusSchema
      else countSchema
    )
    new FlightInfo(
      schema,
      descriptor,
      Collections.singletonList(endpoint),
      -1L,
      -1L
    )

  /** Probe the result schema for a literal statement (same PrepareStrategy split as
    * createPreparedStatement): Some for queries, None for DML/DDL and for an unprobed statement,
    * which never reaches a node. recordExecution = false keeps the probe out of history and load
    * metrics. Throws a Flight exception on auth / router failure.
    */
  private def probeStatementSchema(
      peer: String,
      sql: String
  ): Option[Schema] =
    liveSession(peer, noContext(peer)) match
      case Right((ConnectionContext.Entry(poolKey, connId, user, _, _), eff)) =>
        val probeSqlOpt: Option[String] = strategyOf(sql) match
          case PrepareStrategy.SkipExecute | PrepareStrategy.Unprobed => None
          case PrepareStrategy.ProbeWrap(probe)                       => Some(probe)
          case PrepareStrategy.FullExecute                            => Some(sql)
        probeSqlOpt.map { probeSql =>
          scala.util.Try(
            router
              .execute(connId, user, poolKey, probeSql, eff, recordExecution = false)
              .unsafeRunSync()
          ) match
            case scala.util.Success(Right(result)) =>
              try result.rows.getVectorSchemaRoot.getSchema
              finally result.close()
            case scala.util.Success(Left(f)) =>
              throw toFlightException(f)
            case scala.util.Failure(t) =>
              throw internalError("probeStatementSchema", t)
        }
      case Left(err) =>
        throw err

  /** Dedicated GetSchema RPC for CommandStatementQuery: same probed schema, empty for DML/DDL (the
    * documented contract for this RPC) and for an unprobed statement, whose schema only its
    * execution can tell.
    */
  override def getSchemaStatement(
      command: FlightSql.CommandStatementQuery,
      context: FlightProducer.CallContext,
      descriptor: FlightDescriptor
  ): SchemaResult =
    val peer = Option(context.peerIdentity()).getOrElse("anonymous")
    new SchemaResult(probeStatementSchema(peer, command.getQuery).getOrElse(emptySchema))

  override def getStreamStatement(
      ticket: FlightSql.TicketStatementQuery,
      context: FlightProducer.CallContext,
      listener: FlightProducer.ServerStreamListener
  ): Unit =
    // Mirrors the encoding in getFlightInfoStatement: handle bytes are UTF-8 SQL.
    val sql = ticket.getStatementHandle.toStringUtf8
    runStatement(sql, context, listener)

  /** Shared body of getStreamStatement / getStreamPreparedStatement. Resolves the per-peer context
    * (tenant/pool/connection), forwards the SQL through the router and streams the resulting Arrow
    * batches to the Flight client.
    */
  private def runStatement(
      sql: String,
      context: FlightProducer.CallContext,
      listener: FlightProducer.ServerStreamListener,
      announced: Option[Schema] = None
  ): Unit =
    val peer = Option(context.peerIdentity()).getOrElse("anonymous")
    liveSession(peer, noContext(peer)) match
      case Right((ConnectionContext.Entry(poolKey, connId, user, _, _), eff)) =>
        logger.debug(s"runStatement pool=$poolKey sql='${loggableSql(sql)}'")
        val outcome =
          scala.util.Try(router.execute(connId, user, poolKey, sql, eff).unsafeRunSync())
        outcome match
          case scala.util.Failure(t) =>
            listener.error(internalError("router.execute", t))
          case scala.util.Success(Right(result)) =>
            // Mirrors getFlightInfoStatement's own decision (probeStatementSchema falls back to
            // countSchema exactly when PrepareStrategy chose SkipExecute and no admin dialect
            // claimed the statement) WITHOUT re-running a probe: SkipExecute means nothing was
            // executed there either way, so recomputing the pure classification is free and safe.
            val advertisedWasCount =
              strategyOf(sql) == PrepareStrategy.SkipExecute
                && !ai.starlake.quack.edge.admin.AdminSqlParser.claims(sql)
            try streamArrow(result.rows, listener, advertisedWasCount, announced)
            catch
              case t: Throwable =>
                listener.error(commitConflictOrInternal("streaming Arrow batches", t))
            finally result.close()
          case scala.util.Success(Left(f)) =>
            logger.warn(s"router.execute Left: $f")
            listener.error(toFlightException(f))

      case Left(err) =>
        listener.error(err)

  /** Stream batches from `reader` to the Flight `listener`. Gated on !isCancelled(): clients
    * legitimately abandon streams mid-flight, and pumping batches into a cancelled gRPC stream
    * wastes node reads and spams netty warnings.
    *
    * `advertisedWasCount` is true when the caller advertised [[countSchema]] for this statement:
    * see [[streamAfterExhaustion]].
    *
    * `announced` is the schema a metadata call's FlightInfo gave: see
    * [[FlightProducerImpl.underAnnounced]].
    */
  private[edge] def streamArrow(
      reader: org.apache.arrow.vector.ipc.ArrowReader,
      listener: FlightProducer.ServerStreamListener,
      advertisedWasCount: Boolean = false,
      announced: Option[Schema] = None
  ): Unit =
    if advertisedWasCount then streamAfterExhaustion(reader, listener)
    else
      val root: VectorSchemaRoot = reader.getVectorSchemaRoot
      val sent                   = announced.fold(root)(FlightProducerImpl.underAnnounced(root, _))
      var hasMore                = reader.loadNextBatch()
      listener.start(sent)
      if !hasMore then
        // Empty result set: emit a zero-row batch so the client receives the
        // schema even if there's no data.
        sent.setRowCount(0)
        listener.putNext()
      while hasMore && !listener.isCancelled() do
        // A root of the same vectors keeps its own row count, which the reader does not set.
        if sent ne root then sent.setRowCount(root.getRowCount)
        listener.putNext()
        hasMore = reader.loadNextBatch()
      listener.completed()

  /** Answer a statement advertised as [[countSchema]] only once its stream is exhausted, which is
    * what fires a stamped write's deferred COMMIT (`adapter.StampedCommitArrowReader`). The Arrow
    * JDBC driver returns from `executeUpdate` as soon as it holds the first batch and never reads
    * further, so with the Count row sent first its next statement could run before the COMMIT
    * (`Table with name ... does not exist` right after the CTAS that created it), and a COMMIT that
    * failed reached nobody: a rolled-back write looked like a success (issue 170). A failure here
    * propagates to the caller before anything is sent.
    *
    * Holding the batches is cheap: such a statement answers one row. A node reply of the
    * `Success: BOOLEAN` shape is coerced ([[coerceSuccessToCount]]) and its rows dropped, with a
    * WARN if it carried real data.
    */
  private def streamAfterExhaustion(
      reader: org.apache.arrow.vector.ipc.ArrowReader,
      listener: FlightProducer.ServerStreamListener
  ): Unit =
    val root    = reader.getVectorSchemaRoot
    val batches = scala.collection.mutable.ListBuffer.empty[ArrowRecordBatch]
    try
      while reader.loadNextBatch() do batches += new VectorUnloader(root).getRecordBatch
      val coerced = coerceSuccessToCount(root)
      if coerced ne root then
        try
          val rows = batches.iterator.map(_.getLength.toLong).sum
          if rows > 1 then
            logger.warn(
              s"coerceSuccessToCount discarded a Success:BOOLEAN result carrying real row data " +
                s"($rows rows) - a statement legitimately returning rows under this exact shape " +
                "would silently lose them; see coerceSuccessToCount's doc"
            )
          listener.start(coerced)
          if !listener.isCancelled() then listener.putNext()
          listener.completed()
        finally coerced.close()
      else
        // The held batches share the reader's buffers, which Arrow only lets vectors of an allocator
        // with the same root take over.
        val owner = root.getFieldVectors.asScala.headOption.fold(allocator)(_.getAllocator)
        val out   = VectorSchemaRoot.create(root.getSchema, owner)
        try
          listener.start(out)
          if batches.isEmpty then
            out.setRowCount(0)
            listener.putNext()
          val loader = new VectorLoader(out)
          for batch <- batches if !listener.isCancelled() do
            loader.load(batch)
            listener.putNext()
          listener.completed()
        finally out.close()
    finally batches.foreach(_.close())

object FlightProducerImpl:

  /** `root`'s own vectors under `announced`. A metadata call's FlightInfo gives Arrow's standard
    * schema, some of whose columns are not null, and its rows come from SQL, whose columns are all
    * nullable, and ADBC refuses an answer whose stream differs from what its FlightInfo said
    * ("inconsistent schema"). Only when every column has the announced name and type: otherwise
    * `root` itself, as announcing a type a column does not have would break any client.
    */
  private[edge] def underAnnounced(root: VectorSchemaRoot, announced: Schema): VectorSchemaRoot =
    def columns(schema: Schema) = schema.getFields.asScala.map(f => (f.getName, f.getType)).toList
    if columns(root.getSchema) == columns(announced) then
      new VectorSchemaRoot(announced, root.getFieldVectors, 0)
    else root

  val DefaultMaxPutDrains: Int       = 128
  val DefaultMaxPutDrainBytes: Long  = 16777216L
  val DefaultPutDrainTimeoutMs: Long = 30000L

  /** The least a batch counts for, so that empty batches are bounded too. */
  private val MinBatchBytes: Long = 1024L

  /** Runs `body` on this thread and interrupts it if `body` is still running after `ms`. A failure
    * of `body` after the deadline fired is the deadline's: None. No interrupt outlives this method:
    * the lock orders the interrupt against the end of `body`, so one that fired is always cleared
    * here.
    */
  private def interruptAfter[A](ms: Long)(body: => A): Option[A] =
    val thread = Thread.currentThread()
    val lock   = new Object
    var open   = true
    var fired  = false
    val timer  = new java.util.concurrent.CompletableFuture[Unit]()
      .orTimeout(ms, java.util.concurrent.TimeUnit.MILLISECONDS)
    timer.whenComplete { (_, failure) =>
      if failure != null then
        lock.synchronized {
          if open then
            fired = true
            thread.interrupt()
        }
    }
    try
      try Some(body)
      catch case scala.util.control.NonFatal(_) if lock.synchronized(fired) => None
    finally
      lock.synchronized {
        open = false
        if fired then Thread.interrupted()
      }
      timer.complete(())

  /** The largest command an anonymous peer may send, which [[AnonymousCallGuard]] applies: several
    * times what every id GetSqlInfo defines takes. GetSqlInfo answers a row per id listed, repeats
    * included, in Arrow memory that no limit covers, and goes on after its client left: one
    * anonymous call listing 16 million ids, a 16 MiB message, took the JVM from 373 to 2295 MiB and
    * held its thread for 4 s after the client left. Its place stays held while `send` runs, as the
    * end of a call queues behind its handler, so what the limit on calls at once bounds is that
    * many such calls.
    */
  val MaxAnonymousCommandBytes: Int = 4096
