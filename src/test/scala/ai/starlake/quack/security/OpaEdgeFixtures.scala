package ai.starlake.quack.security

import ai.starlake.quack.edge.adapter.{QuackResponse, TestArrow}
import ai.starlake.quack.edge.config.OpaConfig
import ai.starlake.quack.edge.opa.{OpaAuthorizer, OpaClient, OpaDecisionCache}
import ai.starlake.quack.model.{Pool, RoleDistribution, TenantAcl}
import ai.starlake.quack.observability.metrics.OpaInstruments
import ai.starlake.quack.ondemand.InProcessDuckDb
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.state.PoolPermission
import at.favre.lib.crypto.bcrypt.BCrypt
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import org.apache.arrow.flight.sql.FlightSqlClient
import org.apache.arrow.flight.sql.impl.FlightSql
import org.apache.arrow.flight.{FlightCallHeaders, HeaderCallOption, Ticket}

import java.sql.DriverManager
import java.util.Base64
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*

/** Shared setup for the OPA edge specs: a seeded control plane where tenant `acme` is in `opa` mode
  * against a WireMock OPA and tenant `globex` stays in `qod` mode, and small FlightSQL client
  * helpers.
  *
  * Principals: `bob` (acme, NO role, NO grant, NO pool permission: every admission is OPA's),
  * `root` (superuser), `carol` (globex, a pool permission on globex `sales` and no table grant).
  */
object OpaEdgeFixtures:

  val ConnectPath: String   = "/v1/data/qod/authz/connect"
  val StatementPath: String = "/v1/data/qod/authz/statement"

  val GlobexPoolId: String  = "p-globex-sales"
  val CarolUsername: String = "carol"
  val CarolPassword: String = "carolpw"

  def allow: ResponseDefinitionBuilder = okJson("""{"result":{"allow":true}}""")

  def deny(reason: String): ResponseDefinitionBuilder =
    okJson(s"""{"result":{"allow":false,"reason":"$reason"},"decision_id":"d-1"}""")

  def stubConnect(wm: WireMockServer, resp: ResponseDefinitionBuilder): Unit =
    wm.stubFor(post(urlEqualTo(ConnectPath)).willReturn(resp))

  def stubStatement(wm: WireMockServer, resp: ResponseDefinitionBuilder): Unit =
    wm.stubFor(post(urlEqualTo(StatementPath)).willReturn(resp))

  def connectCalls: RequestPatternBuilder   = postRequestedFor(urlEqualTo(ConnectPath))
  def statementCalls: RequestPatternBuilder = postRequestedFor(urlEqualTo(StatementPath))

  /** The canonical security fixture, with `acme` switched to opa mode against `opaUrl` and a
    * `globex` tenant (qod mode) that owns a `sales` pool and the user `carol`.
    */
  def seed(opaUrl: String): SecurityFixtures.Fixture =
    val fix = SecurityFixtures.freshStore()
    val s   = fix.store
    // The tenant row already exists: its acl changes only through updateTenantAcl.
    s.updateTenantAcl(SecurityFixtures.TenantId, TenantAcl(Some("opa"), Some(opaUrl)))
    SecurityFixtures.addTenantB(fix)
    s.upsertPool(
      Pool(
        id = GlobexPoolId,
        tenantId = SecurityFixtures.GlobexTenantId,
        tenantDbId = SecurityFixtures.GlobexTenantDbId,
        name = SecurityFixtures.PoolName,
        size = 1,
        distribution = RoleDistribution(writeonly = 0, readonly = 0, dual = 1),
        maxConcurrentPerNode = 0,
        disabled = false
      )
    )
    val carolId = s.upsertUserWithHash(
      tenant = Some(SecurityFixtures.GlobexTenantId),
      username = CarolUsername,
      passwordHash = BCrypt.withDefaults().hashToString(10, CarolPassword.toCharArray),
      kind = "user"
    )
    // Connect permission only (gate 4 of a qod tenant): no role, so no table grant at all.
    s.insertPoolPermission(
      PoolPermission(
        id = "pp-globex-carol",
        tenantId = SecurityFixtures.GlobexTenantId,
        poolId = Some(GlobexPoolId),
        userId = Some(carolId)
      )
    )
    fix

  /** Default cache TTL is the production one (5s): a FlightSQL statement is validated twice (the
    * schema probe in GetFlightInfo, then DoGet), and the second, identical question is a cache hit.
    */
  def authorizer(
      ttlSec: Int = OpaConfig.default.cacheTtlSec,
      timeoutMs: Int = 2000
  ): OpaAuthorizer =
    new OpaAuthorizer(
      OpaConfig("qod", "", timeoutMs, ttlSec),
      new OpaClient(timeoutMs, OpaClient.jdkPost(timeoutMs)),
      new OpaDecisionCache(ttlSec),
      OpaInstruments.noop
    )

  // ---------------------------------------------------------------------------------------------
  // FlightSQL client helpers
  // ---------------------------------------------------------------------------------------------

  def headers(
      user: String,
      password: String,
      tenant: String = SecurityFixtures.TenantId,
      pool: String = SecurityFixtures.PoolName,
      superuser: Boolean = false
  ): HeaderCallOption =
    val hdrs  = new FlightCallHeaders()
    val basic = Base64.getEncoder.encodeToString(s"$user:$password".getBytes("UTF-8"))
    hdrs.insert("tenant", tenant)
    hdrs.insert("pool", pool)
    hdrs.insert("authorization", s"Basic $basic")
    if superuser then hdrs.insert("superuser", "true")
    new HeaderCallOption(hdrs)

  /** Execute + DoGet, decoding every row into column -> stringified value. */
  def query(
      h: FlightEdgeHarness.Harness,
      opt: HeaderCallOption,
      sql: String
  ): List[Map[String, String]] =
    val raw = h.newClient()
    try
      val fsql   = new FlightSqlClient(raw)
      val info   = fsql.execute(sql, opt)
      val stream = fsql.getStream(info.getEndpoints.get(0).getTicket, opt)
      try readAll(stream.getSchema.getFields.asScala.map(_.getName).toList, stream)
      finally stream.close()
    finally raw.close()

  /** DoGet with a hand-built statement ticket, skipping GetFlightInfo's schema probe (which runs
    * with recordExecution = false and so never writes an audit row).
    */
  def doGet(
      h: FlightEdgeHarness.Harness,
      opt: HeaderCallOption,
      sql: String
  ): List[Map[String, String]] =
    val raw = h.newClient()
    try
      val tsq = FlightSql.TicketStatementQuery
        .newBuilder()
        .setStatementHandle(com.google.protobuf.ByteString.copyFromUtf8(sql))
        .build()
      val ticket = new Ticket(com.google.protobuf.Any.pack(tsq).toByteArray)
      val stream = raw.getStream(ticket, opt)
      try readAll(stream.getSchema.getFields.asScala.map(_.getName).toList, stream)
      finally stream.close()
    finally raw.close()

  private def readAll(
      fields: List[String],
      stream: org.apache.arrow.flight.FlightStream
  ): List[Map[String, String]] =
    val rows = ListBuffer.empty[Map[String, String]]
    while stream.next() do
      val root = stream.getRoot
      for i <- 0 until root.getRowCount do
        rows += fields
          .map(n => n -> Option(root.getVector(n).getObject(i)).map(_.toString).getOrElse(""))
          .toMap
    rows.toList

  // ---------------------------------------------------------------------------------------------
  // Stub node backed by an in-process DuckDB
  // ---------------------------------------------------------------------------------------------

  /** A stub node that really runs the SQL the router forwards, against a fresh in-memory DuckDB
    * prepared with `setup`. Statements are split on `;` (the router prefixes a `USE` prelude); the
    * last one's result is returned. Lets a spec observe the effect of an edge rewrite (CLS masking)
    * in the result rows the client receives.
    */
  def duckNode(setup: String): String => QuackResponse = sql =>
    val conn = DriverManager.getConnection("jdbc:duckdb:", InProcessDuckDb.properties)
    // The reader owns `conn` once built; until then any failure (a statement that throws, an
    // empty script) closes it here, so a failing statement never leaks an instance.
    try
      val stmt  = conn.createStatement()
      val parts = (setup.split(";") ++ sql.split(";")).map(_.trim).filter(_.nonEmpty).toList
      parts.init.foreach(stmt.execute)
      val rs = stmt.executeQuery(parts.last)
      QuackResponse.Ok(TestArrow.fromResultSet(conn, rs), 1L, () => ())
    catch
      case e: Throwable =>
        conn.close()
        throw e

  /** `orders(id, email)` with two rows, shared by the Flight specs. */
  val OrdersSetup: String =
    "CREATE TABLE orders(id INTEGER, email VARCHAR); " +
      "INSERT INTO orders VALUES (1, 'a@x.io'), (2, 'b@x.io')"
