package ai.starlake.quack.edge

import cats.effect.unsafe.implicits.global
import com.google.protobuf.{Any => ProtoAny, ByteString}
import com.typesafe.scalalogging.LazyLogging
import org.apache.arrow.flight.*
import org.apache.arrow.flight.sql.NoOpFlightSqlProducer
import org.apache.arrow.flight.sql.FlightSqlProducer.Schemas
import org.apache.arrow.flight.sql.impl.FlightSql
import org.apache.arrow.memory.{BufferAllocator, RootAllocator}
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.types.pojo.{ArrowType, Field, FieldType, Schema}

import java.nio.charset.StandardCharsets
import java.util.Collections

/** Minimal FlightSqlProducer that forwards SQL to the [[FlightSqlRouter]]. The router talks to the
  * chosen Quack node through DuckDB's `quack_query` extension and hands us back an
  * [[org.apache.arrow.vector.ipc.ArrowReader]] that we stream batch-by-batch to the Flight client.
  */
final class FlightProducerImpl(
    router: FlightSqlRouter
) extends NoOpFlightSqlProducer
    with LazyLogging:

  private val allocator: BufferAllocator = new RootAllocator()

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
    * SkipExecute. `datasetSchema` answers getSchemaPreparedStatement without a second probe.
    */
  private final case class PreparedExec(
      sql: String,
      ownerPeer: String,
      connId: String,
      user: String,
      poolKey: ai.starlake.quack.model.PoolKey,
      preferredNode: Option[String],
      prepareDurationMs: Option[Long],
      datasetSchema: Schema
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
      prepareDurationMs: Option[Long]
  )

  /** Zero-field schema: the "dispatch through executeUpdate" marker, and the `parameter_schema`
    * value since Quack has no parameter binding.
    */
  private val emptySchema: Schema =
    new Schema(java.util.Collections.emptyList[Field]())

  private val emptySchemaBytes: ByteString =
    serializeSchema(emptySchema)

  /** DuckDB streams every non-result statement (DML / DDL / txn control) as a single-row
    * `Count BIGINT`. Both FlightInfo paths must advertise THIS schema, not an empty one: ADBC
    * enforces FlightInfo.schema == DoGet stream schema.
    */
  private val countSchema: Schema =
    new Schema(
      Collections.singletonList(
        new Field("Count", FieldType.nullable(new ArrowType.Int(64, true)), null)
      )
    )

  private val countSchemaBytes: ByteString =
    serializeSchema(countSchema)

  private val preparedStatements =
    scala.collection.concurrent.TrieMap.empty[String, PreparedExec]

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

  /** Diagnostic-only override: logs the DoGet peer + ticket shape before forwarding to the
    * FlightSqlProducer dispatcher.
    */
  override def getStream(
      context: FlightProducer.CallContext,
      ticket: Ticket,
      listener: FlightProducer.ServerStreamListener
  ): Unit =
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
      case RouterFailure.AccessDenied(_) => CallStatus.UNAUTHORIZED
      case RouterFailure.NotFound(_)     => CallStatus.NOT_FOUND
      case RouterFailure.BadRequest(_)   => CallStatus.INVALID_ARGUMENT
      case RouterFailure.Unavailable(_)  => CallStatus.UNAVAILABLE
      case RouterFailure.Internal(_)     => CallStatus.INTERNAL
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

  /** Resolve a prepared handle into an executable plan, enforcing two invariants: the calling peer
    * must be the handle's creator (a leaked handle cannot be replayed), and the [[EffectiveSet]] is
    * read live from [[ConnectionContext]] (mid-session revocation is honored; an expired session
    * denies instead of running).
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
            Right(
              PreparedCall(
                sql = p.sql,
                connId = p.connId,
                user = p.user,
                poolKey = p.poolKey,
                effectiveSet = e.effectiveSet,
                preferredNode = p.preferredNode,
                prepareDurationMs = p.prepareDurationMs
              )
            )
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

  override def createPreparedStatement(
      request: FlightSql.ActionCreatePreparedStatementRequest,
      context: FlightProducer.CallContext,
      listener: FlightProducer.StreamListener[Result]
  ): Unit =
    val sql  = request.getQuery
    val peer = Option(context.peerIdentity()).getOrElse("anonymous")

    ConnectionContext.entry(peer) match
      case Some(ConnectionContext.Entry(poolKey, connId, user, eff, _)) =>
        val kind     = router.classifier.classify(sql)
        val strategy = PrepareStrategy.choose(sql, kind)

        val probeSqlOpt: Option[String] = strategy match
          case PrepareStrategy.SkipExecute      => None
          case PrepareStrategy.ProbeWrap(probe) => Some(probe)
          case PrepareStrategy.FullExecute      => Some(sql)

        probeSqlOpt match
          case None =>
            // SkipExecute: no node call; advertise countSchema (see its doc for the ADBC why).
            val handle = java.util.UUID.randomUUID().toString
            preparedStatements.put(
              handle,
              PreparedExec(
                sql,
                ownerPeer = peer,
                connId,
                user,
                poolKey,
                preferredNode = None,
                prepareDurationMs = None,
                datasetSchema = countSchema
              )
            )
            val resp = FlightSql.ActionCreatePreparedStatementResult
              .newBuilder()
              .setPreparedStatementHandle(ByteString.copyFromUtf8(handle))
              .setDatasetSchema(countSchemaBytes)
              // The ODBC driver throws on an ABSENT parameter_schema, so advertise
              // zero parameters explicitly via the empty schema.
              .setParameterSchema(emptySchemaBytes)
              .build()
            listener.onNext(new Result(ProtoAny.pack(resp).toByteArray))
            listener.onCompleted()

          case Some(probeSql) =>
            // recordExecution = false: the operator-visible history row is the matching
            // Execute, which carries the probe's duration via prepareDurationMs.
            scala.util.Try(
              router
                .execute(connId, user, poolKey, probeSql, eff, recordExecution = false)
                .unsafeRunSync()
            ) match
              case scala.util.Success(Right(result)) =>
                val handle                = java.util.UUID.randomUUID().toString
                val (schema, schemaBytes) =
                  try
                    val s = result.rows.getVectorSchemaRoot.getSchema
                    (s, serializeSchema(s))
                  finally result.close()
                preparedStatements.put(
                  handle,
                  PreparedExec(
                    sql,
                    ownerPeer = peer,
                    connId,
                    user,
                    poolKey,
                    preferredNode = Some(result.nodeId),
                    prepareDurationMs = Some(result.durationMs),
                    datasetSchema = schema
                  )
                )
                val resp = FlightSql.ActionCreatePreparedStatementResult
                  .newBuilder()
                  .setPreparedStatementHandle(ByteString.copyFromUtf8(handle))
                  .setDatasetSchema(schemaBytes)
                  .setParameterSchema(emptySchemaBytes)
                  .build()
                listener.onNext(new Result(ProtoAny.pack(resp).toByteArray))
                listener.onCompleted()
              case scala.util.Success(Left(f)) =>
                listener.onError(toFlightException(f))
              case scala.util.Failure(t) =>
                listener.onError(internalError("createPreparedStatement", t))
      case _ =>
        listener.onError(noContext(peer))

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
      case Some(p) => new SchemaResult(p.datasetSchema)
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
        // schema matches the DoGet stream so ADBC's mismatch guard stays happy.
        new FlightInfo(p.datasetSchema, descriptor, Collections.singletonList(endpoint), -1L, -1L)

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
              try streamArrow(result.rows, listener)
              catch
                case t: Throwable =>
                  listener.error(internalError("streaming Arrow batches", t))
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
      listener
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
      listener
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
    if !command.getIncludeSchema then runStatement(listSql, context, listener)
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
    ConnectionContext.entry(peer) match
      case Some(ConnectionContext.Entry(poolKey, connId, user, eff, _)) =>
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
      case _ =>
        listener.error(noContext(peer))

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
      listener
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

  /** Literal executeUpdate entrypoint: run the SQL through the router and ack the affected-row
    * count as a DoPutUpdateResult. No bound parameters are supported, so `flightStream` is drained,
    * never read.
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
        drainPutStream(flightStream)
        ConnectionContext.entry(peer) match
          case Some(ConnectionContext.Entry(poolKey, connId, user, eff, _)) =>
            logger.debug(s"acceptPutStatement pool=$poolKey sql='$sql'")
            ackUpdateResult(
              scala.util.Try(router.execute(connId, user, poolKey, sql, eff).unsafeRunSync()),
              ackStream,
              "acceptPutStatement"
            )
          case _ =>
            ackStream.onError(noContext(peer, _ => "no pool bound to session; authenticate first"))

  /** Prepared-update entrypoint: Arrow JDBC / ADBC / DBeaver prepare every statement, so literal
    * DML lands here rather than [[acceptPutStatement]]. Replays the Prepare-time SQL and acks the
    * count. Literal updates only: bound parameters are never read.
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
        drainPutStream(flightStream)
        resolvePreparedCall(handle, context) match
          case Left(err) => ackStream.onError(err)
          case Right(p)  =>
            logger.debug(s"acceptPutPreparedStatementUpdate pool=${p.poolKey} sql='${p.sql}'")
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

  /** Drain and discard the DoPut request stream: half-closing without reading the client's
    * parameter batches breaks its in-flight write ("UNAVAILABLE: io exception"). `null` only in
    * unit tests, which drive run() directly.
    */
  private def drainPutStream(flightStream: FlightStream): Unit =
    if flightStream != null then while flightStream.next() do ()

  /** Shared tail of the two update entrypoints: outcome -> DoPutUpdateResult ack or typed error on
    * the ack stream. Closes the result either way.
    */
  private def ackUpdateResult(
      attempt: scala.util.Try[Either[RouterFailure, QueryResult]],
      ackStream: FlightProducer.StreamListener[PutResult],
      label: String
  ): Unit =
    attempt match
      case scala.util.Success(Right(result)) =>
        val count =
          try updateCountOf(result.rows)
          finally result.close()
        emitUpdateCount(count, ackStream)
      case scala.util.Success(Left(f)) =>
        ackStream.onError(toFlightException(f))
      case scala.util.Failure(t) =>
        ackStream.onError(internalError(label, t))

  /** Best-effort affected-row count: DuckDB's single-row Count cell, or -1 (accepted by FlightSQL
    * clients as "unknown").
    */
  private def updateCountOf(reader: org.apache.arrow.vector.ipc.ArrowReader): Long =
    try
      if reader.loadNextBatch() then
        val root = reader.getVectorSchemaRoot
        if root.getRowCount >= 1 && !root.getFieldVectors.isEmpty then
          root.getFieldVectors.get(0).getObject(0) match
            case n: java.lang.Number => n.longValue()
            case _                   => -1L
        else -1L
      else -1L
    catch case _: Throwable => -1L

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
    // ADBC's FlightInfo.schema == DoGet stream guard.
    val schema = probeStatementSchema(peer, command.getQuery).getOrElse(countSchema)
    new FlightInfo(
      schema,
      descriptor,
      Collections.singletonList(endpoint),
      -1L,
      -1L
    )

  /** Probe the result schema for a literal statement (same PrepareStrategy split as
    * createPreparedStatement): Some for queries, None for DML/DDL. recordExecution = false keeps
    * the probe out of history and load metrics. Throws a Flight exception on auth / router failure.
    */
  private def probeStatementSchema(
      peer: String,
      sql: String
  ): Option[Schema] =
    ConnectionContext.entry(peer) match
      case Some(ConnectionContext.Entry(poolKey, connId, user, eff, _)) =>
        val kind                        = router.classifier.classify(sql)
        val strategy                    = PrepareStrategy.choose(sql, kind)
        val probeSqlOpt: Option[String] = strategy match
          case PrepareStrategy.SkipExecute      => None
          case PrepareStrategy.ProbeWrap(probe) => Some(probe)
          case PrepareStrategy.FullExecute      => Some(sql)
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
      case _ =>
        throw noContext(peer)

  /** Dedicated GetSchema RPC for CommandStatementQuery: same probed schema, empty for DML/DDL (the
    * documented contract for this RPC).
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
      listener: FlightProducer.ServerStreamListener
  ): Unit =
    val peer = Option(context.peerIdentity()).getOrElse("anonymous")
    ConnectionContext.entry(peer) match
      case Some(ConnectionContext.Entry(poolKey, connId, user, eff, _)) =>
        logger.debug(s"runStatement pool=$poolKey sql='$sql'")
        val outcome =
          scala.util.Try(router.execute(connId, user, poolKey, sql, eff).unsafeRunSync())
        outcome match
          case scala.util.Failure(t) =>
            listener.error(internalError("router.execute", t))
          case scala.util.Success(Right(result)) =>
            try streamArrow(result.rows, listener)
            catch
              case t: Throwable =>
                listener.error(internalError("streaming Arrow batches", t))
            finally result.close()
          case scala.util.Success(Left(f)) =>
            logger.warn(s"router.execute Left: $f")
            listener.error(toFlightException(f))

      case _ =>
        listener.error(noContext(peer))

  /** Stream batches from `reader` to the Flight `listener`. Gated on !isCancelled(): clients
    * legitimately abandon streams mid-flight, and pumping batches into a cancelled gRPC stream
    * wastes node reads and spams netty warnings.
    */
  private[edge] def streamArrow(
      reader: org.apache.arrow.vector.ipc.ArrowReader,
      listener: FlightProducer.ServerStreamListener
  ): Unit =
    val root: VectorSchemaRoot = reader.getVectorSchemaRoot
    listener.start(root)
    var hasMore = reader.loadNextBatch()
    if !hasMore then
      // Empty result set: emit a zero-row batch so the client receives the
      // schema even if there's no data.
      root.setRowCount(0)
      listener.putNext()
    while hasMore && !listener.isCancelled() do
      listener.putNext()
      hasMore = reader.loadNextBatch()
    listener.completed()
