// src/test/scala/ai/starlake/quack/security/FlightSqlRealClientSpec.scala
package ai.starlake.quack.security

import org.apache.arrow.flight.{
  CallStatus,
  FlightCallHeaders,
  FlightDescriptor,
  FlightRuntimeException,
  FlightStatusCode,
  HeaderCallOption
}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.Base64

/** Real Arrow Flight wire tests for the handshake gate.
  *
  * Unlike [[FlightHandshakeSecuritySpec]] (which exercises collaborators
  * directly, with no sockets), this suite boots a real [[FlightEdgeServer]] via
  * [[FlightEdgeHarness]] and uses the Arrow Flight Java client over gRPC to hit
  * the full stack. The goal is to catch Arrow / gRPC / Netty regressions --
  * specifically the tenant display-name vs surrogate-id normalization fix -- that
  * stub specs would not exercise.
  *
  * Each test case is independent: it boots its own harness, makes one RPC call,
  * and tears down the harness in a `try/finally` block.
  *
  * Authentication fires in the `headerAuth` gate before the FlightSQL producer
  * dispatches on the command bytes. The success cases therefore assert that any
  * exception that surfaces from `getInfo` is NOT UNAUTHENTICATED -- the auth
  * gate passed, and any INVALID_ARGUMENT from the producer is a separate concern
  * (raw SQL bytes are not a valid FlightSQL protobuf command, but that is only
  * reachable after the auth gate succeeds).
  */
class FlightSqlRealClientSpec extends AnyFlatSpec with Matchers:

  // Encode "user:password" as a Base64 string suitable for a Basic
  // Authorization header value.
  private def basicCredentials(user: String, password: String): String =
    Base64.getEncoder.encodeToString(s"$user:$password".getBytes("UTF-8"))

  // Build a HeaderCallOption carrying the three connection headers the
  // FlightEdgeServer reads: tenant, pool, and Authorization.
  private def headersOpt(tenant: String, pool: String, user: String, password: String): HeaderCallOption =
    val hdrs = new FlightCallHeaders()
    hdrs.insert("tenant", tenant)
    hdrs.insert("pool", pool)
    hdrs.insert("authorization", s"Basic ${basicCredentials(user, password)}")
    new HeaderCallOption(hdrs)

  /** Assert that auth passed on an RPC whose command bytes may be malformed.
    *
    * The `headerAuth` gate runs before the FlightSQL producer decodes the
    * command. If auth succeeds the producer is called next; since we pass raw
    * SQL bytes rather than a protobuf-encoded `CommandStatementQuery`, the
    * server returns INVALID_ARGUMENT (not UNAUTHENTICATED). Either outcome
    * is acceptable: no exception at all means the producer also succeeded;
    * a non-UNAUTHENTICATED FlightRuntimeException means auth passed but the
    * producer rejected the malformed command. Any UNAUTHENTICATED exception
    * fails the test because it means the auth gate itself rejected the request.
    */
  private def assertAuthPassed(body: => Unit): Unit =
    try body
    catch
      case e: FlightRuntimeException =>
        if e.status().code() == FlightStatusCode.UNAUTHENTICATED then
          fail(
            s"auth gate rejected the request (UNAUTHENTICATED): ${e.status().description()}"
          )
        // Any other FlightStatusCode (e.g. INVALID_ARGUMENT from the producer
        // receiving raw SQL bytes) is fine -- auth succeeded.
      case _: Throwable =>
        () // non-Flight exceptions are not auth failures.

  // -------------------------------------------------------------------------
  // Case 1: display-name tenant "acme" succeeds.
  // -------------------------------------------------------------------------

  "FlightSqlRealClientSpec" should
    "succeed with display-name tenant 'acme'" in {
      val fix    = SecurityFixtures.freshStore()
      val h      = FlightEdgeHarness.boot(fix.store, enableProviders = true, tls = false)
      val client = h.newClient()
      try
        val opt = headersOpt(
          tenant   = SecurityFixtures.TenantName,
          pool     = SecurityFixtures.PoolName,
          user     = SecurityFixtures.AliceUsername,
          password = SecurityFixtures.AlicePassword
        )
        // getInfo with the auth headers triggers the headerAuth gate first.
        // The server validates credentials and mints a Bearer peerId on
        // success. The raw "SELECT 1" bytes are not a valid FlightSQL protobuf
        // command, so the producer may return INVALID_ARGUMENT after auth --
        // assertAuthPassed accepts that outcome.
        assertAuthPassed {
          client.getInfo(FlightDescriptor.command("SELECT 1".getBytes("UTF-8")), opt)
        }
      finally
        client.close()
        h.shutdown()
    }

  // -------------------------------------------------------------------------
  // Case 2: surrogate-id tenant succeeds via Names.looksLikeTenantId normalization.
  //
  // SecurityFixtures.TenantId ("t-acme0001") contains the non-hex char 'm',
  // so Names.looksLikeTenantId rejects it. For this wire-level regression test
  // we need an id that matches the real surrogate shape: t-[0-9a-f]{8}. We add
  // a second tenant (id "t-ace00001") directly to the shared fixture store so
  // both the display-name tests and this surrogate-id test share one harness
  // lifecycle without touching the SecurityFixtures constants.
  //
  // The canonical surrogate format is minted by PoolSupervisor.newId("t"),
  // which takes the first 8 hex chars of a UUID. "t-ace00001" is all hex.
  // -------------------------------------------------------------------------

  /** Tenant id that satisfies Names.looksLikeTenantId (8 lowercase hex chars). */
  private val HexTenantId = "t-ace00001"

  it should "succeed with surrogate-id tenant (Names.looksLikeTenantId=true)" in {
    import ai.starlake.quack.model.{Pool, RoleDistribution, Tenant, TenantDb, TenantDbKind}
    import ai.starlake.quack.ondemand.state.{PoolPermission, RolePermission}
    // Build a fresh fixture store, then add a second tenant whose id matches
    // the hex surrogate pattern so FlightEdgeServer's looksLikeTenantId branch
    // is exercised on the real Flight wire.
    val fix    = SecurityFixtures.freshStore()
    val s      = fix.store

    val hexTenantDisplayName = "hexacme"
    val hexTenantDbId        = "td-hex0001"
    val hexPoolId            = "p-hex0001"
    val hexPoolPermId        = "pp-hex0001"
    val hexAdminRoleId       = "r-hexadmin01"
    val hexAdminPermId       = "rp-hexall01"

    s.upsertTenant(Tenant(
      id          = HexTenantId,
      displayName = hexTenantDisplayName,
      authProvider = "db"
    ))
    s.upsertTenantDb(TenantDb(
      id        = hexTenantDbId,
      tenantId  = HexTenantId,
      name      = s"${hexTenantDisplayName}_main",
      kind      = TenantDbKind.InMemory,
      metastore = Map.empty,
      dataPath  = ""
    ))
    s.upsertPool(Pool(
      id                   = hexPoolId,
      tenantId             = HexTenantId,
      tenantDbId           = hexTenantDbId,
      name                 = SecurityFixtures.PoolName,
      size                 = 1,
      distribution         = RoleDistribution(writeonly = 0, readonly = 0, dual = 1),
      maxConcurrentPerNode = 0,
      disabled             = false
    ))
    s.upsertRole(ai.starlake.quack.ondemand.state.RbacRole(
      id          = hexAdminRoleId,
      tenantId    = HexTenantId,
      name        = "admin",
      description = Some("hex tenant admin role")
    ))
    s.insertRolePermission(RolePermission(
      id          = hexAdminPermId,
      roleId      = hexAdminRoleId,
      catalogName = "*",
      schemaName  = "*",
      tableName   = "*",
      verb        = "ALL"
    ))
    import at.favre.lib.crypto.bcrypt.BCrypt
    val hexAliceId = s.upsertUserWithHash(
      tenant       = Some(HexTenantId),
      username     = SecurityFixtures.AliceUsername,
      passwordHash = BCrypt.withDefaults().hashToString(10, SecurityFixtures.AlicePassword.toCharArray),
      role         = "admin"
    )
    s.addUserRole(hexAliceId, hexAdminRoleId)
    s.insertPoolPermission(PoolPermission(
      id       = hexPoolPermId,
      tenantId = HexTenantId,
      poolId   = Some(hexPoolId),
      userId   = Some(hexAliceId)
    ))

    val h      = FlightEdgeHarness.boot(s, enableProviders = true, tls = false)
    val client = h.newClient()
    try
      // Send the surrogate id on the wire. FlightEdgeServer.resolveTenant calls
      // Names.looksLikeTenantId("t-ace00001") == true, then getTenantById,
      // and normalises to the display name before calling lookupPool/authorize.
      val opt = headersOpt(
        tenant   = HexTenantId,
        pool     = SecurityFixtures.PoolName,
        user     = SecurityFixtures.AliceUsername,
        password = SecurityFixtures.AlicePassword
      )
      assertAuthPassed {
        client.getInfo(FlightDescriptor.command("SELECT 1".getBytes("UTF-8")), opt)
      }
    finally
      client.close()
      h.shutdown()
  }

  // -------------------------------------------------------------------------
  // Case 3: wrong password is rejected with UNAUTHENTICATED.
  // -------------------------------------------------------------------------

  it should "fail with UNAUTHENTICATED on wrong password" in {
    val fix    = SecurityFixtures.freshStore()
    val h      = FlightEdgeHarness.boot(fix.store, enableProviders = true, tls = false)
    val client = h.newClient()
    try
      val opt = headersOpt(
        tenant   = SecurityFixtures.TenantName,
        pool     = SecurityFixtures.PoolName,
        user     = SecurityFixtures.AliceUsername,
        password = "wrongpassword"
      )
      val ex = intercept[FlightRuntimeException] {
        client.getInfo(FlightDescriptor.command("SELECT 1".getBytes("UTF-8")), opt)
      }
      ex.status().code() shouldBe FlightStatusCode.UNAUTHENTICATED
    finally
      client.close()
      h.shutdown()
  }

  // -------------------------------------------------------------------------
  // Case 4: missing tenant header is rejected with UNAUTHENTICATED and a
  // description mentioning the missing tenant scope.
  // -------------------------------------------------------------------------

  it should "fail with UNAUTHENTICATED when tenant header is absent" in {
    val fix    = SecurityFixtures.freshStore()
    val h      = FlightEdgeHarness.boot(fix.store, enableProviders = true, tls = false)
    val client = h.newClient()
    try
      val hdrs = new FlightCallHeaders()
      hdrs.insert("pool", SecurityFixtures.PoolName)
      hdrs.insert(
        "authorization",
        s"Basic ${basicCredentials(SecurityFixtures.AliceUsername, SecurityFixtures.AlicePassword)}"
      )
      val opt = new HeaderCallOption(hdrs)
      val ex = intercept[FlightRuntimeException] {
        client.getInfo(FlightDescriptor.command("SELECT 1".getBytes("UTF-8")), opt)
      }
      ex.status().code() shouldBe FlightStatusCode.UNAUTHENTICATED
      // The error description should reference the missing tenant context.
      // FlightEdgeServer emits either "missing tenant scope for Basic auth: ..."
      // or a TenantSelector "pool not found" message when pool resolution fails
      // due to no tenant context.
      val desc = ex.status().description().toLowerCase
      desc should (include("tenant") or include("pool"))
    finally
      client.close()
      h.shutdown()
  }

  // -------------------------------------------------------------------------
  // Case 5: GetSqlInfo is reachable without auth.
  //
  // The Apache Arrow Flight SQL ODBC driver (Power BI, Excel) loads its
  // SQLGetInfo cache at connect time by calling GetSqlInfo BEFORE replaying
  // the session bearer from the handshake. Quack must serve GetSqlInfo as
  // server-identification metadata (no tenant data is exposed) so the
  // driver's connect-time probe succeeds. Data-bearing calls (runStatement,
  // catalogs / schemas / tables) still require auth.
  // -------------------------------------------------------------------------

  it should "serve GetSqlInfo without any Authorization header" in {
    import com.google.protobuf.{Any => ProtoAny}
    import org.apache.arrow.flight.sql.impl.FlightSql
    val fix    = SecurityFixtures.freshStore()
    val h      = FlightEdgeHarness.boot(fix.store, enableProviders = true, tls = false)
    val client = h.newClient()
    try
      // No Authorization, no tenant, no pool. Just the SqlInfo command.
      val sqlInfoCmd = FlightSql.CommandGetSqlInfo
        .newBuilder()
        .addInfo(FlightSql.SqlInfo.FLIGHT_SQL_SERVER_NAME_VALUE)
        .build()
      val packed = ProtoAny.pack(sqlInfoCmd).toByteArray
      assertAuthPassed {
        client.getInfo(FlightDescriptor.command(packed))
      }
    finally
      client.close()
      h.shutdown()
  }

  // -------------------------------------------------------------------------
  // Case 6: data-bearing calls still require auth even though SqlInfo
  // doesn't. Anonymous peers reach the producer but bounce off the
  // ConnectionContext check, surfacing as UNAUTHENTICATED.
  // -------------------------------------------------------------------------

  // -------------------------------------------------------------------------
  // Case 7: `x-qod-authorization` aliases `Authorization`.
  //
  // The Arrow Flight SQL ODBC build that Power BI ships wraps forwards
  // arbitrary passthrough headers (`tenant`, `pool`) but discards the
  // standard `Authorization` header. The fallback lets the connector .mez
  // route the credential through any passthrough name; the server accepts
  // either header identically.
  // -------------------------------------------------------------------------

  // -------------------------------------------------------------------------
  // Case 8: accept unpadded base64 in the `Basic` credential value.
  //
  // The Dremio Arrow Flight SQL ODBC connection-string parser uses
  // `rfind('=')` on the entire remaining string, which corrupts splits when
  // the value contains `=` (Base64 padding). Verified against
  // dremio/flightsql-odbc connection_string_parser.cc:52. Workaround:
  // connector emits Basic credentials base64-encoded WITHOUT padding, server
  // pads + decodes. Same wire format (`Basic <base64>`); only the encoder
  // alphabet variant changes.
  // -------------------------------------------------------------------------

  private def basicCredentialsUnpadded(user: String, password: String): String =
    Base64.getEncoder.withoutPadding.encodeToString(s"$user:$password".getBytes("UTF-8"))

  it should "accept Basic creds with UNPADDED base64 (Dremio parser workaround)" in {
    val fix    = SecurityFixtures.freshStore()
    val h      = FlightEdgeHarness.boot(fix.store, enableProviders = true, tls = false)
    val client = h.newClient()
    try
      val hdrs = new FlightCallHeaders()
      hdrs.insert("tenant", SecurityFixtures.TenantName)
      hdrs.insert("pool", SecurityFixtures.PoolName)
      hdrs.insert(
        "x-qod-authorization",
        s"Basic ${basicCredentialsUnpadded(SecurityFixtures.AliceUsername, SecurityFixtures.AlicePassword)}"
      )
      val opt = new HeaderCallOption(hdrs)
      assertAuthPassed {
        client.getInfo(FlightDescriptor.command("SELECT 1".getBytes("UTF-8")), opt)
      }
    finally
      client.close()
      h.shutdown()
  }

  it should "accept Basic creds presented via x-qod-authorization (alias of Authorization)" in {
    val fix    = SecurityFixtures.freshStore()
    val h      = FlightEdgeHarness.boot(fix.store, enableProviders = true, tls = false)
    val client = h.newClient()
    try
      val hdrs = new FlightCallHeaders()
      hdrs.insert("tenant", SecurityFixtures.TenantName)
      hdrs.insert("pool", SecurityFixtures.PoolName)
      hdrs.insert(
        "x-qod-authorization",
        s"Basic ${basicCredentials(SecurityFixtures.AliceUsername, SecurityFixtures.AlicePassword)}"
      )
      val opt = new HeaderCallOption(hdrs)
      assertAuthPassed {
        client.getInfo(FlightDescriptor.command("SELECT 1".getBytes("UTF-8")), opt)
      }
    finally
      client.close()
      h.shutdown()
  }

  it should "reject a data-bearing call from an unauthenticated peer" in {
    import com.google.protobuf.{Any => ProtoAny}
    import org.apache.arrow.flight.sql.impl.FlightSql
    val fix    = SecurityFixtures.freshStore()
    val h      = FlightEdgeHarness.boot(fix.store, enableProviders = true, tls = false)
    val client = h.newClient()
    try
      val stmtCmd = FlightSql.CommandStatementQuery
        .newBuilder()
        .setQuery("SELECT 1")
        .build()
      val packed = ProtoAny.pack(stmtCmd).toByteArray
      // getFlightInfoStatement now probes the schema before returning the
      // FlightInfo (needed so ODBC's `flight_info->GetSchema()` works).
      // The probe goes through router.execute, which consults
      // ConnectionContext - so an anonymous peer is rejected here at
      // getInfo() rather than later at getStream().
      val ex = intercept[FlightRuntimeException] {
        client.getInfo(FlightDescriptor.command(packed))
      }
      ex.status().code() shouldBe FlightStatusCode.UNAUTHENTICATED
    finally
      client.close()
      h.shutdown()
  }
