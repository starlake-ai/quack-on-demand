package ai.starlake.quack.edge

import cats.effect.unsafe.implicits.global
import com.google.protobuf.{Any => ProtoAny, ByteString}
import com.typesafe.scalalogging.LazyLogging
import org.apache.arrow.flight.*
import org.apache.arrow.flight.sql.NoOpFlightSqlProducer
import org.apache.arrow.flight.sql.impl.FlightSql
import org.apache.arrow.memory.{BufferAllocator, RootAllocator}
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.types.pojo.{Field, Schema}

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

  /** Per-handle execution context, captured at Prepare time and replayed on every
    * `getStreamPreparedStatement` so the same handle can drive many Executes (as the Arrow Flight
    * SQL spec requires). Arrow JDBC / DBeaver / ADBC all reuse a Prepare handle across multiple
    * ExecuteQuery calls until they explicitly call ClosePreparedStatement.
    *
    * We don't cache the materialized Arrow batches: a `VectorSchemaRoot` is consumed by the first
    * stream and the underlying [[QueryResult.rows]] cannot be replayed. So we re-execute the SQL
    * through the router on every Execute. One Prepare = one extra round-trip vs. caching, but the
    * client gets correct multi-Execute semantics.
    *
    * `preferredNode` is the Quack node that served Prepare (when there was one). The Execute path
    * forwards it to the router as a soft pin so DuckDB-side per-process caches stay warm across the
    * two halves of the FlightSQL Prepare + Execute dance. `None` when Prepare made no node call
    * (DML / DDL / transaction control via [[PrepareStrategy.SkipExecute]]).
    *
    * `prepareDurationMs` is the wall-clock time the LIMIT-0 probe spent on the node. Forwarded to
    * the router on the Execute call so the resulting [[StatementRecord]] carries it -- the UI uses
    * it to render "57 ms / prep 28 ms" on the single visible row. `None` for SkipExecute.
    */
  private final case class PreparedExec(
      sql: String,
      connId: String,
      user: String,
      poolKey: ai.starlake.quack.model.PoolKey,
      effectiveSet: Option[ai.starlake.quack.ondemand.rbac.EffectiveSet],
      preferredNode: Option[String],
      prepareDurationMs: Option[Long]
  )

  /** Cached empty Arrow schema for the [[PrepareStrategy.SkipExecute]] arm. FlightSQL clients
    * interpret a zero-field `dataset_schema` as "this statement is an update, not a query" and
    * dispatch through `executeUpdate` rather than `executeQuery`.
    */
  private val emptySchemaBytes: ByteString =
    serializeSchema(new Schema(java.util.Collections.emptyList[Field]()))

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

  /** Map a typed [[RouterFailure]] to the Arrow Flight `CallStatus` the wire surfaces to clients.
    * Keeps the status code authoritative so connectors (Power BI, DBeaver, ADBC) can branch on
    * UNAUTHORIZED / NOT_FOUND / INVALID_ARGUMENT / UNAVAILABLE / INTERNAL without parsing the
    * description string. R12.
    */
  private def toFlightException(f: RouterFailure): Throwable =
    val status = f match
      case RouterFailure.AccessDenied(_) => CallStatus.UNAUTHORIZED
      case RouterFailure.NotFound(_)     => CallStatus.NOT_FOUND
      case RouterFailure.BadRequest(_)   => CallStatus.INVALID_ARGUMENT
      case RouterFailure.Unavailable(_)  => CallStatus.UNAVAILABLE
      case RouterFailure.Internal(_)     => CallStatus.INTERNAL
    status.withDescription(f.reason).toRuntimeException()

  override def createPreparedStatement(
      request: FlightSql.ActionCreatePreparedStatementRequest,
      context: FlightProducer.CallContext,
      listener: FlightProducer.StreamListener[Result]
  ): Unit =
    val sql  = request.getQuery
    val peer = Option(context.peerIdentity()).getOrElse("anonymous")

    (
      ConnectionContext.poolFor(peer),
      ConnectionContext.connectionIdFor(peer),
      ConnectionContext.userFor(peer)
    ) match
      case (Some(poolKey), Some(connId), Some(user)) =>
        val eff      = ConnectionContext.effectiveSetFor(peer)
        val kind     = router.classifier.classify(sql)
        val strategy = PrepareStrategy.choose(sql, kind)

        // Schema-probe SQL the node sees at Prepare time. For DML/DDL we don't ask anything;
        // for a wrappable SELECT we send a LIMIT-0 subquery so the planner returns the result
        // schema without running the body; for everything else we fall back to the original SQL.
        val probeSqlOpt: Option[String] = strategy match
          case PrepareStrategy.SkipExecute      => None
          case PrepareStrategy.ProbeWrap(probe) => Some(probe)
          case PrepareStrategy.FullExecute      => Some(sql)

        probeSqlOpt match
          case None =>
            // SkipExecute: empty Arrow schema, no node call, no soft pin, no prep duration.
            val handle = java.util.UUID.randomUUID().toString
            preparedStatements.put(
              handle,
              PreparedExec(
                sql,
                connId,
                user,
                poolKey,
                eff,
                preferredNode = None,
                prepareDurationMs = None
              )
            )
            val resp = FlightSql.ActionCreatePreparedStatementResult
              .newBuilder()
              .setPreparedStatementHandle(ByteString.copyFromUtf8(handle))
              .setDatasetSchema(emptySchemaBytes)
              // Apache Arrow Flight SQL ODBC reads parameter_schema to learn
              // the parameter count. An absent field throws "Tried reading
              // schema message, was null or length 0". Quack has no parameter
              // binding so we advertise zero parameters via the empty schema.
              .setParameterSchema(emptySchemaBytes)
              .build()
            listener.onNext(new Result(ProtoAny.pack(resp).toByteArray))
            listener.onCompleted()

          case Some(probeSql) =>
            // `recordExecution = false`: the probe is internal plumbing -- the operator-visible
            // history row is the matching Execute, which will carry the probe's duration via
            // PreparedExec.prepareDurationMs.
            scala.util.Try(
              router
                .execute(connId, user, poolKey, probeSql, eff, recordExecution = false)
                .unsafeRunSync()
            ) match
              case scala.util.Success(Right(result)) =>
                val handle      = java.util.UUID.randomUUID().toString
                val schemaBytes =
                  try serializeSchema(result.rows.getVectorSchemaRoot.getSchema)
                  finally result.close()
                preparedStatements.put(
                  handle,
                  PreparedExec(
                    sql,
                    connId,
                    user,
                    poolKey,
                    eff,
                    preferredNode = Some(result.nodeId),
                    prepareDurationMs = Some(result.durationMs)
                  )
                )
                val resp = FlightSql.ActionCreatePreparedStatementResult
                  .newBuilder()
                  .setPreparedStatementHandle(ByteString.copyFromUtf8(handle))
                  .setDatasetSchema(schemaBytes)
                  // Empty parameter_schema = zero bound parameters. See the
                  // SkipExecute arm above for the why.
                  .setParameterSchema(emptySchemaBytes)
                  .build()
                listener.onNext(new Result(ProtoAny.pack(resp).toByteArray))
                listener.onCompleted()
              case scala.util.Success(Left(f)) =>
                listener.onError(toFlightException(f))
              case scala.util.Failure(t) =>
                logger.error(s"createPreparedStatement threw: ${t.getMessage}", t)
                listener.onError(
                  CallStatus.INTERNAL.withDescription(t.getMessage).toRuntimeException()
                )
      case _ =>
        listener.onError(
          CallStatus.UNAUTHENTICATED
            .withDescription(s"no connection context for peer $peer")
            .toRuntimeException()
        )

  override def closePreparedStatement(
      request: FlightSql.ActionClosePreparedStatementRequest,
      context: FlightProducer.CallContext,
      listener: FlightProducer.StreamListener[Result]
  ): Unit =
    val handle = request.getPreparedStatementHandle.toStringUtf8
    preparedStatements.remove(handle)
    listener.onCompleted()

  override def getFlightInfoPreparedStatement(
      command: FlightSql.CommandPreparedStatementQuery,
      context: FlightProducer.CallContext,
      descriptor: FlightDescriptor
  ): FlightInfo =
    val handle = command.getPreparedStatementHandle.toStringUtf8
    if !preparedStatements.contains(handle) then
      throw CallStatus.INVALID_ARGUMENT
        .withDescription(s"no such prepared statement: $handle")
        .toRuntimeException()
    val ticket   = new Ticket(ProtoAny.pack(command).toByteArray)
    val endpoint = new FlightEndpoint(ticket)
    new FlightInfo(null, descriptor, Collections.singletonList(endpoint), -1L, -1L)

  override def getStreamPreparedStatement(
      command: FlightSql.CommandPreparedStatementQuery,
      context: FlightProducer.CallContext,
      listener: FlightProducer.ServerStreamListener
  ): Unit =
    val handle = command.getPreparedStatementHandle.toStringUtf8
    // Re-execute on every Execute so the handle stays valid until the client
    // sends ClosePreparedStatement -- the spec contract Arrow JDBC / DBeaver /
    // ADBC rely on. We re-use the (connId, user, poolKey, EffectiveSet)
    // captured at Prepare time so an ACL change mid-session does not let a
    // stale handle bypass the new policy; it still binds to the pool the
    // client was authorized for at Prepare.
    preparedStatements.get(handle) match
      case None =>
        listener.error(
          CallStatus.INVALID_ARGUMENT
            .withDescription(s"no such prepared statement: $handle")
            .toRuntimeException()
        )
      case Some(p) =>
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
        ) match
          case scala.util.Success(Right(result)) =>
            try streamArrow(result.rows, listener)
            catch
              case t: Throwable =>
                logger.error(s"failed streaming Arrow batches: ${t.getMessage}", t)
                listener.error(
                  CallStatus.INTERNAL
                    .withDescription(s"stream failure: ${t.getMessage}")
                    .toRuntimeException()
                )
            finally result.close()
          case scala.util.Success(Left(f)) =>
            listener.error(toFlightException(f))
          case scala.util.Failure(t) =>
            logger.error(s"getStreamPreparedStatement re-execute threw: ${t.getMessage}", t)
            listener.error(
              CallStatus.INTERNAL.withDescription(t.getMessage).toRuntimeException()
            )

  // -----------------------------------------------------------------
  //  Metadata endpoints - DBeaver / JDBC clients walk these to populate
  //  the catalog browser. Each handler translates the Flight SQL request
  //  into a SQL query against the Quack node's information_schema and
  //  forwards through the router, so column types and schemas use the
  //  same Arrow-IPC path as regular queries.
  // -----------------------------------------------------------------

  private def quote(s: String): String = s.replace("'", "''")

  override def getFlightInfoCatalogs(
      command: FlightSql.CommandGetCatalogs,
      context: FlightProducer.CallContext,
      descriptor: FlightDescriptor
  ): FlightInfo =
    val ticket = new Ticket(ProtoAny.pack(command).toByteArray)
    new FlightInfo(
      null,
      descriptor,
      Collections.singletonList(new FlightEndpoint(ticket)),
      -1L,
      -1L
    )

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
    val ticket = new Ticket(ProtoAny.pack(command).toByteArray)
    new FlightInfo(
      null,
      descriptor,
      Collections.singletonList(new FlightEndpoint(ticket)),
      -1L,
      -1L
    )

  override def getStreamSchemas(
      command: FlightSql.CommandGetDbSchemas,
      context: FlightProducer.CallContext,
      listener: FlightProducer.ServerStreamListener
  ): Unit =
    val catalog       = if command.hasCatalog then Some(command.getCatalog) else None
    val schemaPattern =
      if command.hasDbSchemaFilterPattern then Some(command.getDbSchemaFilterPattern) else None
    val filters = (
      catalog.map(c => s"catalog_name = '${quote(c)}'") ::
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
    val ticket = new Ticket(ProtoAny.pack(command).toByteArray)
    new FlightInfo(
      null,
      descriptor,
      Collections.singletonList(new FlightEndpoint(ticket)),
      -1L,
      -1L
    )

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
      catalog.map(c => s"table_catalog = '${quote(c)}'") ::
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

  /** include_schema=true variant of getStreamTables. DBeaver and the Arrow Flight SQL JDBC driver
    * call this to populate column metadata: each row carries the table's Arrow schema as
    * IPC-serialized bytes in a fifth `table_schema` column. We fetch the table list through the
    * router, then probe each table with `SELECT * ... LIMIT 0` to capture its schema, and emit a
    * single VectorSchemaRoot built locally (not streamed from Quack - the per-table-schema shape
    * isn't expressible as one SQL).
    */
  private def streamTablesWithSchema(
      listSql: String,
      context: FlightProducer.CallContext,
      listener: FlightProducer.ServerStreamListener
  ): Unit =
    val peer = Option(context.peerIdentity()).getOrElse("anonymous")
    (
      ConnectionContext.poolFor(peer),
      ConnectionContext.connectionIdFor(peer),
      ConnectionContext.userFor(peer)
    ) match
      case (Some(poolKey), Some(connId), Some(user)) =>
        val eff           = ConnectionContext.effectiveSetFor(peer)
        val tablesAttempt = scala.util.Try(
          router.execute(connId, user, poolKey, listSql, eff).unsafeRunSync()
        )
        tablesAttempt match
          case scala.util.Failure(t) =>
            logger.error(s"streamTablesWithSchema list failed: ${t.getMessage}", t)
            listener.error(
              CallStatus.INTERNAL
                .withDescription(s"router threw: ${t.getMessage}")
                .toRuntimeException()
            )
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
        listener.error(
          CallStatus.UNAUTHENTICATED
            .withDescription(s"no connection context for peer $peer")
            .toRuntimeException()
        )

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

  /** Probe a single table's Arrow schema via `SELECT * FROM cat.schema.name LIMIT 0`. Returns the
    * IPC-serialized Schema bytes, or empty bytes on failure (so one bad table doesn't break the
    * whole metadata response).
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
          val schema = qr.rows.getVectorSchemaRoot.getSchema
          val baos   = new java.io.ByteArrayOutputStream()
          org.apache.arrow.vector.ipc.message.MessageSerializer.serialize(
            new org.apache.arrow.vector.ipc.WriteChannel(
              java.nio.channels.Channels.newChannel(baos)
            ),
            schema
          )
          baos.toByteArray
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
    val ticket = new Ticket(ProtoAny.pack(command).toByteArray)
    new FlightInfo(
      null,
      descriptor,
      Collections.singletonList(new FlightEndpoint(ticket)),
      -1L,
      -1L
    )

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
  //  Key-metadata endpoints - DBeaver's "Properties" tab polls these.
  //  DuckLake/DuckDB doesn't enforce primary/foreign keys, so we return
  //  empty result sets conforming to the Flight SQL canonical schemas
  //  rather than UNIMPLEMENTED. Empty + correct schema is what JDBC
  //  callers expect when the engine has no key constraints.
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
    val ticket = new Ticket(ProtoAny.pack(command).toByteArray)
    new FlightInfo(
      null,
      descriptor,
      Collections.singletonList(new FlightEndpoint(ticket)),
      -1L,
      -1L
    )

  override def getStreamPrimaryKeys(
      command: FlightSql.CommandGetPrimaryKeys,
      context: FlightProducer.CallContext,
      listener: FlightProducer.ServerStreamListener
  ): Unit =
    emitEmpty(
      org.apache.arrow.flight.sql.FlightSqlProducer.Schemas.GET_PRIMARY_KEYS_SCHEMA,
      listener
    )

  override def getFlightInfoImportedKeys(
      command: FlightSql.CommandGetImportedKeys,
      context: FlightProducer.CallContext,
      descriptor: FlightDescriptor
  ): FlightInfo =
    val ticket = new Ticket(ProtoAny.pack(command).toByteArray)
    new FlightInfo(
      null,
      descriptor,
      Collections.singletonList(new FlightEndpoint(ticket)),
      -1L,
      -1L
    )

  override def getStreamImportedKeys(
      command: FlightSql.CommandGetImportedKeys,
      context: FlightProducer.CallContext,
      listener: FlightProducer.ServerStreamListener
  ): Unit =
    emitEmpty(
      org.apache.arrow.flight.sql.FlightSqlProducer.Schemas.GET_IMPORTED_KEYS_SCHEMA,
      listener
    )

  override def getFlightInfoExportedKeys(
      command: FlightSql.CommandGetExportedKeys,
      context: FlightProducer.CallContext,
      descriptor: FlightDescriptor
  ): FlightInfo =
    val ticket = new Ticket(ProtoAny.pack(command).toByteArray)
    new FlightInfo(
      null,
      descriptor,
      Collections.singletonList(new FlightEndpoint(ticket)),
      -1L,
      -1L
    )

  override def getStreamExportedKeys(
      command: FlightSql.CommandGetExportedKeys,
      context: FlightProducer.CallContext,
      listener: FlightProducer.ServerStreamListener
  ): Unit =
    emitEmpty(
      org.apache.arrow.flight.sql.FlightSqlProducer.Schemas.GET_EXPORTED_KEYS_SCHEMA,
      listener
    )

  override def getFlightInfoCrossReference(
      command: FlightSql.CommandGetCrossReference,
      context: FlightProducer.CallContext,
      descriptor: FlightDescriptor
  ): FlightInfo =
    val ticket = new Ticket(ProtoAny.pack(command).toByteArray)
    new FlightInfo(
      null,
      descriptor,
      Collections.singletonList(new FlightEndpoint(ticket)),
      -1L,
      -1L
    )

  override def getStreamCrossReference(
      command: FlightSql.CommandGetCrossReference,
      context: FlightProducer.CallContext,
      listener: FlightProducer.ServerStreamListener
  ): Unit =
    emitEmpty(
      org.apache.arrow.flight.sql.FlightSqlProducer.Schemas.GET_CROSS_REFERENCE_SCHEMA,
      listener
    )

  /** FlightSQL "execute an update statement" entrypoint. A client's `executeUpdate` on a literal
    * INSERT / UPDATE / DELETE / DDL arrives here as a `CommandStatementUpdate` -- the read path
    * `getStreamStatement` only ever sees a SELECT (DoGet). We resolve the per-peer pool context
    * exactly as [[runStatement]] does, run the SQL through the router (ACL gate + routing + node
    * call), and return the affected-row count to the client as a `DoPutUpdateResult`.
    *
    * A plain statement update carries no bound parameters, so we never read `flightStream` --
    * mirroring the Arrow reference producer. Parameterized DML (`INSERT ... VALUES (?)`) is a
    * separate, larger feature: it additionally needs a `parameter_schema` advertised at Prepare and
    * an `acceptPutPreparedStatementUpdate` override.
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
        (
          ConnectionContext.poolFor(peer),
          ConnectionContext.connectionIdFor(peer),
          ConnectionContext.userFor(peer)
        ) match
          case (Some(poolKey), Some(connId), Some(user)) =>
            logger.debug(s"acceptPutStatement pool=$poolKey sql='$sql'")
            val eff = ConnectionContext.effectiveSetFor(peer)
            ackUpdateResult(
              scala.util.Try(router.execute(connId, user, poolKey, sql, eff).unsafeRunSync()),
              ackStream,
              "acceptPutStatement"
            )
          case _ =>
            ackStream.onError(
              CallStatus.UNAUTHENTICATED
                .withDescription("no pool bound to session; authenticate first")
                .toRuntimeException()
            )

  /** FlightSQL "execute a prepared update" entrypoint. Arrow JDBC / ADBC / DBeaver prepare *every*
    * statement, so a literal INSERT / UPDATE / DELETE / DDL the user runs arrives here (not via
    * [[acceptPutStatement]]): first `createPreparedStatement` stores the handle, then
    * `executeUpdate` lands as a `CommandPreparedStatementUpdate`. We replay the SQL captured at
    * Prepare time -- same (connId, user, poolKey, EffectiveSet) as [[getStreamPreparedStatement]]
    * -- and ack the count.
    *
    * Bound parameters (`?`) would arrive as rows on `flightStream`; we don't read them, so this
    * covers literal (non-parameterized) prepared updates only. Parameterized DML still needs the
    * `parameter_schema` work at Prepare.
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
        preparedStatements.get(handle) match
          case None =>
            ackStream.onError(
              CallStatus.INVALID_ARGUMENT
                .withDescription(s"no such prepared statement: $handle")
                .toRuntimeException()
            )
          case Some(p) =>
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

  /** Consume and discard the DoPut request stream. A FlightSQL update client streams its bound
    * parameter batches (often a single empty batch for a literal statement) on the DoPut channel;
    * if the server writes its `PutResult` and half-closes WITHOUT reading them, the client's
    * in-flight write breaks and surfaces as `UNAVAILABLE: io exception`. We don't support bound
    * parameters, so drain the batches and throw them away. `null` only in unit tests, which drive
    * `run()` directly without a wire stream.
    */
  private def drainPutStream(flightStream: FlightStream): Unit =
    if flightStream != null then while flightStream.next() do ()

  /** Shared tail of the two update entrypoints: turn a router execution outcome into a
    * `DoPutUpdateResult` ack (or an error on the ack stream). Closes the result either way. R12:
    * router failures are mapped to their kind-appropriate Flight status (AccessDenied ->
    * UNAUTHORIZED, NotFound -> NOT_FOUND, etc.) via [[toFlightException]], not folded to
    * INVALID_ARGUMENT.
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
        logger.error(s"$label threw: ${t.getMessage}", t)
        ackStream.onError(CallStatus.INTERNAL.withDescription(t.getMessage).toRuntimeException())

  /** Best-effort affected-row count from a DML result. DuckDB returns a single-row, single-column
    * "Count" (BigInt) for INSERT / UPDATE / DELETE; we read that cell. Anything else (DDL, or a
    * node that returns no count) yields -1, which FlightSQL clients accept as an unknown update
    * count.
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

  /** Serialize a `DoPutUpdateResult{record_count}` into an ArrowBuf and hand it to the ack stream.
    * The buffer is released only after `onNext` + `onCompleted` have consumed it (mirrors the Arrow
    * reference producer's try-with-resources ordering).
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
  //  Type info + SQL info - R7. Power BI and other ODBC clients read
  //  these to learn the server's type system and SQL capabilities. The
  //  NoOp base throws UNIMPLEMENTED; we emit the canonical Flight SQL
  //  schemas populated from [[TypeInfoCatalog]] (types) and a
  //  [[SqlInfoBuilder]] (server identification + dialect knobs).
  // -----------------------------------------------------------------

  override def getFlightInfoTypeInfo(
      request: FlightSql.CommandGetXdbcTypeInfo,
      context: FlightProducer.CallContext,
      descriptor: FlightDescriptor
  ): FlightInfo =
    val ticket = new Ticket(ProtoAny.pack(request).toByteArray)
    new FlightInfo(
      null,
      descriptor,
      Collections.singletonList(new FlightEndpoint(ticket)),
      -1L,
      -1L
    )

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
    val schema = org.apache.arrow.flight.sql.FlightSqlProducer.Schemas.GET_TYPE_INFO_SCHEMA
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
        // R7: emit create_params as a null list. ODBC consumers (Power BI,
        // DBeaver) use it only when issuing DDL like CREATE TABLE; they don't
        // need it to render result sets. Skipping the dense ListVector writer
        // dance keeps this method readable.
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

  /** SqlInfoBuilder pre-loaded with the full standard SqlInfo set. R7. The Arrow Flight SQL ODBC
    * driver builds its SQLGetInfo cache from this response; any code we omit surfaces to the client
    * as `Unknown GetInfo type: N`. Values reflect DuckDB's actual behavior where it differs from
    * generic defaults (case-insensitive unquoted / case-sensitive quoted identifiers, full ANSI-92
    * grammar, serializable isolation, no stored procedures).
    *
    * Maximum-length codes use `0` per ODBC convention for "no fixed limit". Function-name lists use
    * JDBC escape names (the standard JDBC `getNumericFunctions` / `getStringFunctions` /
    * `getSystemFunctions` / `getTimeDateFunctions` surface) so ODBC translation tables resolve.
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
      // R7 fix-up: report NONE for the FlightSql server-managed transaction
      // action surface. Quack handles inline BEGIN/COMMIT/ROLLBACK statements
      // through the regular query path (see `SqlTransactionsSupported = true`
      // below), but it does NOT implement the FlightSql BeginTransaction /
      // EndTransaction / BeginSavepoint actions - those still inherit
      // UNIMPLEMENTED from NoOpFlightSqlProducer. Advertising TRANSACTION made
      // ADBC autocommit-off clients call BeginTransaction and hard-fail.
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
    val ticket = new Ticket(ProtoAny.pack(request).toByteArray)
    new FlightInfo(
      null,
      descriptor,
      Collections.singletonList(new FlightEndpoint(ticket)),
      -1L,
      -1L
    )

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
    // Flight SQL ticket discipline: the ticket bytes must be a protobuf Any
    // wrapping a TicketStatementQuery (or another well-known message). The
    // NoOpFlightSqlProducer.getStream parses ticket.getBytes() as Any and
    // dispatches to getStreamStatement only when the unwrapped message is
    // TicketStatementQuery. Raw SQL bytes will silently fail dispatch.
    val tsq = FlightSql.TicketStatementQuery
      .newBuilder()
      .setStatementHandle(ByteString.copyFromUtf8(command.getQuery))
      .build()
    val ticket = new Ticket(ProtoAny.pack(tsq).toByteArray)
    // No locations → client follows up on the same connection.
    val endpoint = new FlightEndpoint(ticket)
    // null schema = "I don't know yet, trust the schema in the DoGet stream".
    // If we returned an empty Schema here, ADBC compares it with the actual
    // stream schema and 400s on mismatch.
    new FlightInfo(
      null,
      descriptor,
      Collections.singletonList(endpoint),
      -1L,
      -1L
    )

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
    (
      ConnectionContext.poolFor(peer),
      ConnectionContext.connectionIdFor(peer),
      ConnectionContext.userFor(peer)
    ) match
      case (Some(poolKey), Some(connId), Some(user)) =>
        logger.debug(s"runStatement pool=$poolKey sql='$sql'")
        val eff     = ConnectionContext.effectiveSetFor(peer)
        val outcome =
          scala.util.Try(router.execute(connId, user, poolKey, sql, eff).unsafeRunSync())
        outcome match
          case scala.util.Failure(t) =>
            logger.error(s"router.execute threw: ${t.getMessage}", t)
            listener.error(
              CallStatus.INTERNAL
                .withDescription(s"router threw: ${t.getMessage}")
                .toRuntimeException()
            )
          case scala.util.Success(Right(result)) =>
            try streamArrow(result.rows, listener)
            catch
              case t: Throwable =>
                logger.error(s"failed streaming Arrow batches: ${t.getMessage}", t)
                listener.error(
                  CallStatus.INTERNAL
                    .withDescription(s"stream failure: ${t.getMessage}")
                    .toRuntimeException()
                )
            finally result.close()
          case scala.util.Success(Left(f)) =>
            logger.warn(s"router.execute Left: $f")
            listener.error(toFlightException(f))

      case _ =>
        listener.error(
          CallStatus.UNAUTHENTICATED
            .withDescription(s"no connection context for peer $peer")
            .toRuntimeException()
        )

  /** Read batches from `reader` and push them to the Flight `listener`. Reuses
    * `reader.getVectorSchemaRoot()` directly - the listener flushes the current state of the root
    * on each `putNext()`.
    */
  private def streamArrow(
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
    while hasMore do
      listener.putNext()
      hasMore = reader.loadNextBatch()
    listener.completed()
