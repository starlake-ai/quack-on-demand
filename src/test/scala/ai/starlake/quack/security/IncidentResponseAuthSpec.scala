package ai.starlake.quack.security

import ai.starlake.quack.model.{PoolKey, Role, RunningNode}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Instant

/** Auth gate on the incident-response surface: quarantine / unquarantine must be superuser-only. A
  * tenant-admin (alice) should get 403 superuser_required; the root superuser should succeed and
  * the change should be visible in pool/list.
  *
  * Fixture adaptation notes:
  *   - The brief's `TenantDbId` placeholder maps to [[SecurityFixtures.TenantDbName]]
  *     ("acme_main"), not the store id ("td-main0001"), because [[NodeHandlers.withNode]] resolves
  *     pools via [[PoolKey]] which carries the TenantDb *name*.
  *   - [[SecurityFixtures]] has no `RootPassword`; it uses `RootPassword = "rootpw"`.
  *   - The harness's `mintToken` takes `tenant = None` for the superuser login.
  */
class IncidentResponseAuthSpec extends AnyFlatSpec with Matchers:

  private val RequestTimeout: java.time.Duration = java.time.Duration.ofSeconds(10)

  private def post(
      client: HttpClient,
      url: String,
      body: String,
      apiKey: Option[String] = None
  ): HttpResponse[String] =
    val b = HttpRequest
      .newBuilder(URI.create(url))
      .header("Content-Type", "application/json")
      .timeout(RequestTimeout)
      .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
    apiKey.foreach(k => b.header("X-API-Key", k))
    client.send(b.build(), HttpResponse.BodyHandlers.ofString())

  private def get(
      client: HttpClient,
      url: String,
      apiKey: Option[String] = None
  ): HttpResponse[String] =
    val b = HttpRequest.newBuilder(URI.create(url)).GET().timeout(RequestTimeout)
    apiKey.foreach(k => b.header("X-API-Key", k))
    client.send(b.build(), HttpResponse.BodyHandlers.ofString())

  private val TestNodeId = "n-test0001"

  // The brief uses TenantDbId as the tenantDb field, but PoolKey resolution uses
  // the TenantDb *name* (acme_main), not its store id (td-main0001).
  private def nodeOpBody(nodeId: String): String =
    s"""{"tenant":"${SecurityFixtures.TenantId}","tenantDb":"${SecurityFixtures.TenantDbName}","pool":"${SecurityFixtures.PoolName}","nodeId":"$nodeId"}"""

  private def bootWithNode(): (ManagerServerHarness.Harness, String) =
    val fix  = SecurityFixtures.freshStore()
    val node = RunningNode(
      nodeId = TestNodeId,
      poolKey = PoolKey(
        SecurityFixtures.TenantId,
        SecurityFixtures.TenantDbName,
        SecurityFixtures.PoolName
      ),
      role = Role.Dual,
      host = "127.0.0.1",
      port = 21000,
      token = "tok-test",
      pid = Some(1L),
      podName = None,
      startedAt = Instant.EPOCH,
      maxConcurrent = 0
    )
    fix.store.upsertNode(node, SecurityFixtures.PoolId)
    val h = ManagerServerHarness.boot(fix.store, staticApiKey = None)
    (h, TestNodeId)

  "node/quarantine" should "reject a tenant admin with 403 superuser_required" in {
    val (h, nodeId) = bootWithNode()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val resp = post(
        h.httpClient,
        s"${h.baseUrl}/api/node/quarantine",
        nodeOpBody(nodeId),
        apiKey = Some(token)
      )
      withClue(s"tenant admin -> /node/quarantine body: ${resp.body()}") {
        resp.statusCode() shouldBe 403
      }
    finally h.shutdown()
  }

  "node/restart" should "reject a tenant admin with 403" in {
    val (h, nodeId) = bootWithNode()
    try
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      withClue(s"tenant admin -> /node/restart should be 403") {
        post(
          h.httpClient,
          s"${h.baseUrl}/api/node/restart",
          nodeOpBody(nodeId),
          apiKey = Some(token)
        ).statusCode() shouldBe 403
      }
    finally h.shutdown()
  }

  it should "let a superuser quarantine and unquarantine, reflected in pool/list" in {
    val (h, nodeId) = bootWithNode()
    try
      val root  = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword, None)
      val qResp = post(
        h.httpClient,
        s"${h.baseUrl}/api/node/quarantine",
        nodeOpBody(nodeId),
        apiKey = Some(root)
      )
      withClue(s"superuser -> /node/quarantine body: ${qResp.body()}") {
        qResp.statusCode() shouldBe 200
      }
      val listAfterQ = get(h.httpClient, s"${h.baseUrl}/api/pool/list", apiKey = Some(root))
      withClue(s"pool/list after quarantine body: ${listAfterQ.body()}") {
        listAfterQ.body() should include(""""quarantined":true""")
      }
      val uqResp = post(
        h.httpClient,
        s"${h.baseUrl}/api/node/unquarantine",
        nodeOpBody(nodeId),
        apiKey = Some(root)
      )
      withClue(s"superuser -> /node/unquarantine body: ${uqResp.body()}") {
        uqResp.statusCode() shouldBe 200
      }
      val listAfterUq = get(h.httpClient, s"${h.baseUrl}/api/pool/list", apiKey = Some(root))
      withClue(s"pool/list after unquarantine body: ${listAfterUq.body()}") {
        listAfterUq.body() should include(""""quarantined":false""")
      }
    finally h.shutdown()
  }

  // Tenant-B fixture constant, mirroring the value in RbacTenantScopeSpec.
  private val GlobexTenantId = "t-globex01"

  "node/active-statements" should "show a tenant admin only their tenant's statements" in {
    val (h, _) = bootWithNode()
    try
      h.activeRegistry.register("alice", SecurityFixtures.TenantId, "bi", "n1", "SELECT 1")
      h.activeRegistry.register("bob", GlobexTenantId, "bi", "n2", "SELECT 2")
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      val body =
        get(h.httpClient, s"${h.baseUrl}/api/node/active-statements", apiKey = Some(token)).body()
      body should include("alice")
      body should not include "bob"
    finally h.shutdown()
  }

  "statement/kill" should "404 on a cross-tenant statement and accept an owned one" in {
    val (h, _) = bootWithNode()
    try
      val own =
        h.activeRegistry.register("alice", SecurityFixtures.TenantId, "bi", "n1", "SELECT 1")
      val other = h.activeRegistry.register("bob", GlobexTenantId, "bi", "n2", "SELECT 2")
      val token = h.mintToken(
        SecurityFixtures.AliceUsername,
        SecurityFixtures.AlicePassword,
        Some(SecurityFixtures.TenantId)
      )
      post(
        h.httpClient,
        s"${h.baseUrl}/api/statement/kill",
        s"""{"id":"$other"}""",
        apiKey = Some(token)
      ).statusCode() shouldBe 404
      val ok = post(
        h.httpClient,
        s"${h.baseUrl}/api/statement/kill",
        s"""{"id":"$own"}""",
        apiKey = Some(token)
      )
      ok.statusCode() shouldBe 200
      ok.body() should include("accepted")
    finally h.shutdown()
  }

  it should "answer already-completed for an unknown id outside HA" in {
    val (h, _) = bootWithNode()
    try
      val root = h.mintToken(SecurityFixtures.RootUsername, SecurityFixtures.RootPassword, None)
      val resp = post(
        h.httpClient,
        s"${h.baseUrl}/api/statement/kill",
        """{"id":"nope"}""",
        apiKey = Some(root)
      )
      resp.statusCode() shouldBe 200
      resp.body() should include("already-completed")
    finally h.shutdown()
  }
