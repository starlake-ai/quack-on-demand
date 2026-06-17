package ai.starlake.quack.edge

import ai.starlake.quack.edge.adapter._
import ai.starlake.quack.model.{NodeSpec, PoolKey, RoleDistribution, RunningNode, Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.google.protobuf.ByteString
import org.apache.arrow.flight.FlightProducer.{CallContext, ServerStreamListener}
import org.apache.arrow.flight.sql.impl.FlightSql
import org.apache.arrow.vector.VectorSchemaRoot
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.time.Instant
import scala.collection.concurrent.TrieMap

/** R7 coverage: GetXdbcTypeInfo and GetSqlInfo round-trip through the producer's overrides. */
class FlightProducerImplTypeInfoSpec extends AnyFlatSpec with Matchers:

  private def setupProducer(): FlightProducerImpl =
    val backend = new QuackBackend:
      private val n = TrieMap.empty[String, RunningNode]
      def start(s: NodeSpec) = IO {
        val r = RunningNode(s.nodeId, s.poolKey, s.role, "127.0.0.1",
                            25000 + n.size, "tok", Some(1L), None, Instant.EPOCH,
                            maxConcurrent = s.maxConcurrent)
        n.put(s.nodeId, r); r
      }
      def stop(id: String)    = IO { n.remove(id); () }
      def isAlive(id: String) = n.contains(id)
      def discoverExisting()  = IO.pure(n.values.toList)
      def cleanup()           = IO { n.clear() }
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(backend, tracker, new InMemoryControlPlaneStore())
    val client = new QuackHttpClient(
      TestArrow.sharedAllocator,
      nativeClient   = true,
      nodeDisableSsl = true
    ):
      override def query(endpoint: String, token: String, sql: String, session: Option[String]) =
        IO.pure(TestArrow.okResponse())
    val adapter = new QuackHttpAdapter(client, tracker)
    val router  = new FlightSqlRouter(sup, new SessionRegistry, tracker, adapter)
    new FlightProducerImpl(router)

  private val emptyHeaders = new org.apache.arrow.flight.CallHeaders:
    def get(k: String)             = null
    def getByte(k: String)         = null
    def getAll(k: String)          = java.util.Collections.emptyList[String]()
    def getAllByte(k: String)      = java.util.Collections.emptyList[Array[Byte]]()
    def insert(k: String, v: String): Unit      = ()
    def insert(k: String, v: Array[Byte]): Unit = ()
    def keys()                     = java.util.Collections.emptySet[String]()
    def containsKey(k: String)     = false

  private val fakeContext: CallContext = new CallContext:
    def peerIdentity()         = "test-peer"
    def isCancelled()          = false
    def getHeaders()           = emptyHeaders
    def getMiddleware[T <: org.apache.arrow.flight.FlightServerMiddleware](
        key: org.apache.arrow.flight.FlightServerMiddleware.Key[T]
    ): T = null.asInstanceOf[T]
    def getMiddleware() = java.util.Collections.emptyMap[
      org.apache.arrow.flight.FlightServerMiddleware.Key[?],
      org.apache.arrow.flight.FlightServerMiddleware
    ]()

  /** Captures the VectorSchemaRoot snapshot that the producer emits via `putNext`. We snapshot
    * the row count and type_name column (when present) before the root is freed - the producer
    * closes the root after `completed()` so we cannot defer reads.
    */
  private final class CapturingListener extends ServerStreamListener:
    @volatile var schema: org.apache.arrow.vector.types.pojo.Schema = null
    @volatile var rowCount: Int                                    = -1
    val typeNames   = scala.collection.mutable.ListBuffer.empty[String]
    val dataTypes   = scala.collection.mutable.ListBuffer.empty[Int]
    val infoNames   = scala.collection.mutable.ListBuffer.empty[Long]
    val int32Values = scala.collection.mutable.ListBuffer.empty[Int]
    @volatile var done: Boolean             = false
    @volatile var errored: Throwable        = null
    private var root: VectorSchemaRoot      = null
    override def start(root: VectorSchemaRoot): Unit =
      this.root = root
      this.schema = root.getSchema
    override def start(
        root: VectorSchemaRoot,
        dict: org.apache.arrow.vector.dictionary.DictionaryProvider
    ): Unit = start(root)
    override def start(
        root: VectorSchemaRoot,
        dict: org.apache.arrow.vector.dictionary.DictionaryProvider,
        opts: org.apache.arrow.vector.ipc.message.IpcOption
    ): Unit = start(root)
    override def putNext(): Unit =
      rowCount = root.getRowCount
      Option(root.getVector("type_name")).foreach { v =>
        val tv = v.asInstanceOf[org.apache.arrow.vector.VarCharVector]
        var i  = 0
        while i < rowCount do
          if !tv.isNull(i) then typeNames += new String(tv.get(i), StandardCharsets.UTF_8)
          i += 1
      }
      Option(root.getVector("data_type")).foreach { v =>
        val dv = v.asInstanceOf[org.apache.arrow.vector.IntVector]
        var i  = 0
        while i < rowCount do
          if !dv.isNull(i) then dataTypes += dv.get(i)
          i += 1
      }
      Option(root.getVector("info_name")).foreach { v =>
        val iv = v.asInstanceOf[org.apache.arrow.vector.UInt4Vector]
        var i  = 0
        while i < rowCount do
          if !iv.isNull(i) then infoNames += (iv.get(i).toLong & 0xffffffffL)
          i += 1
      }
      // SqlInfo response shape: value is a DenseUnion with 6 children indexed
      // 0..5 -> string_value, bool_value, bigint_value, int32_bitmask,
      // string_list, int32_to_int32_list_map. For the FLIGHT_SQL_SERVER_*
      // codes that hold a SqlSupportedTransaction enum, the union picks the
      // int32_bitmask child (typeId 3). Read it back so tests can pin the
      // exact reported value.
      Option(root.getVector("value")).foreach { v =>
        val union   = v.asInstanceOf[org.apache.arrow.vector.complex.DenseUnionVector]
        var i       = 0
        while i < rowCount do
          val tid = union.getTypeId(i)
          if tid == 3 then
            val int32Vec = union.getIntVector(tid)
            val offset   = union.getOffset(i)
            int32Values += int32Vec.get(offset)
          i += 1
      }
    override def putMetadata(buf: org.apache.arrow.memory.ArrowBuf): Unit = ()
    override def putNext(buf: org.apache.arrow.memory.ArrowBuf): Unit     = ()
    override def error(t: Throwable): Unit                                = errored = t
    override def completed(): Unit                                        = done = true
    override def isReady(): Boolean                                       = true
    override def isCancelled(): Boolean                                   = false
    override def setOnReadyHandler(handler: Runnable): Unit               = ()
    override def setUseZeroCopy(zeroCopy: Boolean): Unit                  = ()
    override def setOnCancelHandler(handler: Runnable): Unit              = ()

  "FlightProducerImpl.getStreamTypeInfo" should
    "emit a row per DuckDB type matching TypeInfoCatalog" in:
    val producer = setupProducer()
    val cmd      = FlightSql.CommandGetXdbcTypeInfo.newBuilder().build()
    val listener = new CapturingListener
    producer.getStreamTypeInfo(cmd, fakeContext, listener)
    listener.errored shouldBe null
    listener.done shouldBe true
    listener.rowCount shouldBe TypeInfoCatalog.rows.size
    listener.typeNames.toList should contain allOf (
      "BOOLEAN", "BIGINT", "DECIMAL", "VARCHAR", "DATE", "TIMESTAMP"
    )

  it should "filter by data_type when the request specifies one" in:
    val producer = setupProducer()
    val cmd      = FlightSql.CommandGetXdbcTypeInfo
      .newBuilder()
      .setDataType(TypeInfoCatalog.SQL_BIGINT)
      .build()
    val listener = new CapturingListener
    producer.getStreamTypeInfo(cmd, fakeContext, listener)
    listener.done shouldBe true
    listener.typeNames.toSet shouldBe Set("BIGINT", "UBIGINT")
    listener.dataTypes.toSet shouldBe Set(TypeInfoCatalog.SQL_BIGINT)

  it should "emit a non-null schema even when filter matches nothing" in:
    val producer = setupProducer()
    val cmd      = FlightSql.CommandGetXdbcTypeInfo
      .newBuilder()
      .setDataType(9999) // unknown code
      .build()
    val listener = new CapturingListener
    producer.getStreamTypeInfo(cmd, fakeContext, listener)
    listener.done shouldBe true
    listener.rowCount  shouldBe 0
    listener.schema      should not be null
    listener.schema.findField("type_name") should not be null

  import org.apache.arrow.flight.sql.impl.FlightSql.SqlInfo

  private val FLIGHT_SQL_SERVER_NAME    = SqlInfo.FLIGHT_SQL_SERVER_NAME_VALUE
  private val FLIGHT_SQL_SERVER_VERSION = SqlInfo.FLIGHT_SQL_SERVER_VERSION_VALUE

  // Codes the Arrow Flight SQL ODBC driver probes during SQLGetInfo
  // initialization. Anything Quack omits surfaces to the client as
  // `Unknown GetInfo type: N` and breaks the driver's capability cache.
  // Sourced from the proto-generated SqlInfo enum so the test tracks any
  // future additions automatically.
  private val sqlInfoStandardSet: Seq[Int] = Seq(
    // Server identification + capabilities
    SqlInfo.FLIGHT_SQL_SERVER_NAME_VALUE,
    SqlInfo.FLIGHT_SQL_SERVER_VERSION_VALUE,
    SqlInfo.FLIGHT_SQL_SERVER_ARROW_VERSION_VALUE,
    SqlInfo.FLIGHT_SQL_SERVER_READ_ONLY_VALUE,
    SqlInfo.FLIGHT_SQL_SERVER_SQL_VALUE,
    SqlInfo.FLIGHT_SQL_SERVER_SUBSTRAIT_VALUE,
    SqlInfo.FLIGHT_SQL_SERVER_TRANSACTION_VALUE,
    SqlInfo.FLIGHT_SQL_SERVER_CANCEL_VALUE,
    // DDL
    SqlInfo.SQL_DDL_CATALOG_VALUE,
    SqlInfo.SQL_DDL_SCHEMA_VALUE,
    SqlInfo.SQL_DDL_TABLE_VALUE,
    // Identifier handling
    SqlInfo.SQL_IDENTIFIER_CASE_VALUE,
    SqlInfo.SQL_IDENTIFIER_QUOTE_CHAR_VALUE,
    SqlInfo.SQL_QUOTED_IDENTIFIER_CASE_VALUE,
    SqlInfo.SQL_ALL_TABLES_ARE_SELECTABLE_VALUE,
    SqlInfo.SQL_NULL_ORDERING_VALUE,
    SqlInfo.SQL_KEYWORDS_VALUE,
    // Function lists + escapes
    SqlInfo.SQL_NUMERIC_FUNCTIONS_VALUE,
    SqlInfo.SQL_STRING_FUNCTIONS_VALUE,
    SqlInfo.SQL_SYSTEM_FUNCTIONS_VALUE,
    SqlInfo.SQL_DATETIME_FUNCTIONS_VALUE,
    SqlInfo.SQL_SEARCH_STRING_ESCAPE_VALUE,
    SqlInfo.SQL_EXTRA_NAME_CHARACTERS_VALUE,
    // Grammar / dialect
    SqlInfo.SQL_SUPPORTS_COLUMN_ALIASING_VALUE,
    SqlInfo.SQL_NULL_PLUS_NULL_IS_NULL_VALUE,
    SqlInfo.SQL_SUPPORTS_CONVERT_VALUE,
    SqlInfo.SQL_SUPPORTS_TABLE_CORRELATION_NAMES_VALUE,
    SqlInfo.SQL_SUPPORTS_DIFFERENT_TABLE_CORRELATION_NAMES_VALUE,
    SqlInfo.SQL_SUPPORTS_EXPRESSIONS_IN_ORDER_BY_VALUE,
    SqlInfo.SQL_SUPPORTS_ORDER_BY_UNRELATED_VALUE,
    SqlInfo.SQL_SUPPORTED_GROUP_BY_VALUE,
    SqlInfo.SQL_SUPPORTS_LIKE_ESCAPE_CLAUSE_VALUE,
    SqlInfo.SQL_SUPPORTS_NON_NULLABLE_COLUMNS_VALUE,
    SqlInfo.SQL_SUPPORTED_GRAMMAR_VALUE,
    SqlInfo.SQL_ANSI92_SUPPORTED_LEVEL_VALUE,
    SqlInfo.SQL_SUPPORTS_INTEGRITY_ENHANCEMENT_FACILITY_VALUE,
    SqlInfo.SQL_OUTER_JOINS_SUPPORT_LEVEL_VALUE,
    // Vocabulary
    SqlInfo.SQL_SCHEMA_TERM_VALUE,
    SqlInfo.SQL_PROCEDURE_TERM_VALUE,
    SqlInfo.SQL_CATALOG_TERM_VALUE,
    SqlInfo.SQL_CATALOG_AT_START_VALUE,
    SqlInfo.SQL_SCHEMAS_SUPPORTED_ACTIONS_VALUE,
    SqlInfo.SQL_CATALOGS_SUPPORTED_ACTIONS_VALUE,
    SqlInfo.SQL_SUPPORTED_POSITIONED_COMMANDS_VALUE,
    SqlInfo.SQL_SELECT_FOR_UPDATE_SUPPORTED_VALUE,
    SqlInfo.SQL_STORED_PROCEDURES_SUPPORTED_VALUE,
    SqlInfo.SQL_SUPPORTED_SUBQUERIES_VALUE,
    SqlInfo.SQL_CORRELATED_SUBQUERIES_SUPPORTED_VALUE,
    SqlInfo.SQL_SUPPORTED_UNIONS_VALUE,
    // Maximum-length limits
    SqlInfo.SQL_MAX_BINARY_LITERAL_LENGTH_VALUE,
    SqlInfo.SQL_MAX_CHAR_LITERAL_LENGTH_VALUE,
    SqlInfo.SQL_MAX_COLUMN_NAME_LENGTH_VALUE,
    SqlInfo.SQL_MAX_COLUMNS_IN_GROUP_BY_VALUE,
    SqlInfo.SQL_MAX_COLUMNS_IN_INDEX_VALUE,
    SqlInfo.SQL_MAX_COLUMNS_IN_ORDER_BY_VALUE,
    SqlInfo.SQL_MAX_COLUMNS_IN_SELECT_VALUE,
    SqlInfo.SQL_MAX_COLUMNS_IN_TABLE_VALUE,
    SqlInfo.SQL_MAX_CONNECTIONS_VALUE,
    SqlInfo.SQL_MAX_CURSOR_NAME_LENGTH_VALUE,
    SqlInfo.SQL_MAX_INDEX_LENGTH_VALUE,
    SqlInfo.SQL_DB_SCHEMA_NAME_LENGTH_VALUE,
    SqlInfo.SQL_MAX_PROCEDURE_NAME_LENGTH_VALUE,
    SqlInfo.SQL_MAX_CATALOG_NAME_LENGTH_VALUE,
    SqlInfo.SQL_MAX_ROW_SIZE_VALUE,
    SqlInfo.SQL_MAX_ROW_SIZE_INCLUDES_BLOBS_VALUE,
    SqlInfo.SQL_MAX_STATEMENT_LENGTH_VALUE,
    SqlInfo.SQL_MAX_STATEMENTS_VALUE,
    SqlInfo.SQL_MAX_TABLE_NAME_LENGTH_VALUE,
    SqlInfo.SQL_MAX_TABLES_IN_SELECT_VALUE,
    SqlInfo.SQL_MAX_USERNAME_LENGTH_VALUE,
    // Transactions
    SqlInfo.SQL_DEFAULT_TRANSACTION_ISOLATION_VALUE,
    SqlInfo.SQL_TRANSACTIONS_SUPPORTED_VALUE,
    SqlInfo.SQL_SUPPORTED_TRANSACTIONS_ISOLATION_LEVELS_VALUE,
    SqlInfo.SQL_DATA_DEFINITION_CAUSES_TRANSACTION_COMMIT_VALUE,
    SqlInfo.SQL_DATA_DEFINITIONS_IN_TRANSACTIONS_IGNORED_VALUE,
    SqlInfo.SQL_SUPPORTED_RESULT_SET_TYPES_VALUE,
    SqlInfo.SQL_BATCH_UPDATES_SUPPORTED_VALUE,
    SqlInfo.SQL_SAVEPOINTS_SUPPORTED_VALUE,
    SqlInfo.SQL_NAMED_PARAMETERS_SUPPORTED_VALUE,
    SqlInfo.SQL_LOCATORS_UPDATE_COPY_VALUE,
    SqlInfo.SQL_STORED_FUNCTIONS_USING_CALL_SYNTAX_SUPPORTED_VALUE
  )

  "FlightProducerImpl.getStreamSqlInfo" should
    "echo back the info_names the client requested" in:
    val producer = setupProducer()
    val cmd      = FlightSql.CommandGetSqlInfo
      .newBuilder()
      .addInfo(FLIGHT_SQL_SERVER_NAME)
      .addInfo(FLIGHT_SQL_SERVER_VERSION)
      .build()
    val listener = new CapturingListener
    producer.getStreamSqlInfo(cmd, fakeContext, listener)
    listener.errored shouldBe null
    listener.done shouldBe true
    listener.infoNames.toSet should contain allOf (
      FLIGHT_SQL_SERVER_NAME.toLong, FLIGHT_SQL_SERVER_VERSION.toLong
    )

  // R7 fix-up after R15 redeploy: advertising
  // SQL_SUPPORTED_TRANSACTION_TRANSACTION made clients call BeginTransaction
  // (the FlightSql server-managed transaction action), which we don't
  // implement and which throws UNIMPLEMENTED. Honest answer: NONE.
  it should "report SQL_SUPPORTED_TRANSACTION_NONE for FlightSqlServerTransaction" in:
    val producer = setupProducer()
    val cmd = FlightSql.CommandGetSqlInfo
      .newBuilder()
      .addInfo(SqlInfo.FLIGHT_SQL_SERVER_TRANSACTION_VALUE)
      .build()
    val listener = new CapturingListener
    producer.getStreamSqlInfo(cmd, fakeContext, listener)
    listener.errored shouldBe null
    listener.done shouldBe true
    listener.int32Values.headOption shouldBe Some(
      FlightSql.SqlSupportedTransaction.SQL_SUPPORTED_TRANSACTION_NONE.getNumber
    )

  // R7 follow-up: the ODBC driver's SQLGetInfo cache fails with
  // "Unknown GetInfo type: N" when the server doesn't populate the code.
  // Verify every code in the standard ODBC probe set returns a row.
  // Probe one code at a time so a missing entry names itself in the failure.
  it should "populate every info code in the standard ODBC probe set" in:
    val producer = setupProducer()
    val missing  = sqlInfoStandardSet.filter { code =>
      val cmd = FlightSql.CommandGetSqlInfo
        .newBuilder()
        .addInfo(code)
        .build()
      val listener = new CapturingListener
      producer.getStreamSqlInfo(cmd, fakeContext, listener)
      listener.errored != null || !listener.infoNames.map(_.toInt).contains(code)
    }
    withClue(s"missing SqlInfo codes: ${missing.mkString(", ")}") {
      missing shouldBe empty
    }