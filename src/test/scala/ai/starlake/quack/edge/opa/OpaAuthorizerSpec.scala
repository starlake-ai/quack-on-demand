package ai.starlake.quack.edge.opa

import ai.starlake.acl.model.TableRef
import ai.starlake.acl.parser.{TableAccess, Verb}
import ai.starlake.quack.edge.config.OpaConfig
import ai.starlake.quack.model.{PoolKey, Tenant, TenantAcl}
import ai.starlake.quack.observability.metrics.OpaInstruments
import ai.starlake.quack.ondemand.rbac.{EffectiveSet, HandshakeDenial}
import ai.starlake.quack.ondemand.runtime.testkit.LogCapture
import ai.starlake.quack.ondemand.state.{RbacRole, RbacUser}
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class OpaAuthorizerSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val wm                 = new WireMockServer(options().dynamicPort())
  override def beforeAll(): Unit = wm.start()
  override def afterAll(): Unit  = wm.stop()

  private def base = s"http://localhost:${wm.port()}"
  private val acc  = Set(TableAccess(TableRef("tpch", "main", "orders"), Verb.Read))
  private val tgt  = OpaTarget("acme", "tpch", "bi", Nil)
  private val usr  = OpaUser("alice", List("analyst"), Nil, Map.empty)

  private def tenant(
      token: Option[String] = None,
      text: Boolean = false,
      policyPath: Option[String] = None
  ) =
    Tenant("acme", acl = TenantAcl(Some("opa"), Some(base), policyPath, token, text))

  private def authorizer(ttl: Int = 0, timeoutMs: Int = 500) =
    val cfg = OpaConfig("qod", "", timeoutMs, ttl)
    new OpaAuthorizer(
      cfg,
      new OpaClient(timeoutMs, OpaClient.jdkPost(timeoutMs)),
      new OpaDecisionCache(ttl),
      OpaInstruments.noop
    )

  private def stubbedAuthorizer(reply: Either[String, (Int, String)]) =
    new OpaAuthorizer(
      OpaConfig("qod", "", 500, 0),
      new OpaClient(500, (_, _, _) => reply),
      new OpaDecisionCache(0),
      OpaInstruments.noop
    )

  "OpaAuthorizer.statement" should "POST the statement input with the bearer and allow" in:
    wm.resetAll()
    wm.stubFor(
      post(urlEqualTo("/v1/data/qod/authz/statement"))
        .withHeader("Authorization", equalTo("Bearer tok"))
        .willReturn(okJson("""{"result":{"allow":true}}"""))
    )
    authorizer().statement(
      tenant(Some("tok")),
      tgt,
      usr,
      "u1",
      "flightsql",
      "READ",
      acc,
      "SELECT 1"
    ) shouldBe
      Decision.Allow(None)
    wm.verify(
      postRequestedFor(urlEqualTo("/v1/data/qod/authz/statement"))
        .withRequestBody(matchingJsonPath("$.input.accesses[0].table", equalTo("orders")))
        .withRequestBody(notContaining("SELECT 1"))
    )

  it should "send the text only when the tenant opted in" in:
    wm.resetAll()
    wm.stubFor(post(anyUrl()).willReturn(okJson("""{"result":{"allow":true}}""")))
    authorizer().statement(
      tenant(text = true),
      tgt,
      usr,
      "u1",
      "flightsql",
      "READ",
      acc,
      "SELECT 1"
    )
    wm.verify(postRequestedFor(anyUrl()).withRequestBody(containing("SELECT 1")))

  it should "return Error on 5xx, connection refused and timeout" in:
    wm.resetAll()
    wm.stubFor(post(anyUrl()).willReturn(serverError()))
    authorizer()
      .statement(tenant(), tgt, usr, "u1", "flightsql", "READ", acc, "") shouldBe a[Decision.Error]
    wm.resetAll()
    wm.stubFor(
      post(anyUrl()).willReturn(okJson("""{"result":{"allow":true}}""").withFixedDelay(1500))
    )
    authorizer(timeoutMs = 200).statement(
      tenant(),
      tgt,
      usr,
      "u1",
      "flightsql",
      "READ",
      acc,
      ""
    ) shouldBe
      a[Decision.Error]
    val refused =
      Tenant("acme", acl = TenantAcl(Some("opa"), Some("http://127.0.0.1:1"), None, None))
    authorizer()
      .statement(refused, tgt, usr, "u1", "flightsql", "READ", acc, "") shouldBe a[Decision.Error]

  it should "return Error quickly when the response body dribbles past the timeout" in:
    wm.resetAll()
    wm.stubFor(
      post(anyUrl()).willReturn(
        okJson("""{"result":{"allow":true}}""").withChunkedDribbleDelay(5, 3000)
      )
    )
    val started  = System.nanoTime()
    val decision =
      authorizer(timeoutMs = 300).statement(tenant(), tgt, usr, "u1", "flightsql", "READ", acc, "")
    val elapsedMs = (System.nanoTime() - started) / 1000000L
    decision shouldBe a[Decision.Error]
    elapsedMs should be < 2000L

  it should "return Error when no URL is configured anywhere" in:
    authorizer().statement(
      Tenant("acme", acl = TenantAcl(Some("opa"))),
      tgt,
      usr,
      "u1",
      "f",
      "READ",
      acc,
      ""
    ) shouldBe
      a[Decision.Error]

  it should "make one call for repeated identical decisions within the TTL" in:
    wm.resetAll()
    wm.stubFor(post(anyUrl()).willReturn(okJson("""{"result":{"allow":true}}""")))
    val a = authorizer(ttl = 60)
    (1 to 3).foreach(_ => a.statement(tenant(), tgt, usr, "u1", "flightsql", "READ", acc, ""))
    wm.verify(1, postRequestedFor(anyUrl()))

  it should "make two calls when only the statement class differs" in:
    wm.resetAll()
    wm.stubFor(post(anyUrl()).willReturn(okJson("""{"result":{"allow":true}}""")))
    val a = authorizer(ttl = 60)
    a.statement(tenant(), tgt, usr, "u1", "flightsql", "READ", acc, "")
    a.statement(tenant(), tgt, usr, "u1", "flightsql", "WRITE", acc, "")
    wm.verify(2, postRequestedFor(anyUrl()))

  it should "make two calls when only the edge differs" in:
    wm.resetAll()
    wm.stubFor(post(anyUrl()).willReturn(okJson("""{"result":{"allow":true}}""")))
    val a = authorizer(ttl = 60)
    a.statement(tenant(), tgt, usr, "u1", "flightsql", "READ", acc, "")
    a.statement(tenant(), tgt, usr, "u1", "native-quack", "READ", acc, "")
    wm.verify(2, postRequestedFor(anyUrl()))

  it should "make two calls when only the text differs and the tenant sends statement text" in:
    wm.resetAll()
    wm.stubFor(post(anyUrl()).willReturn(okJson("""{"result":{"allow":true}}""")))
    val a = authorizer(ttl = 60)
    a.statement(tenant(text = true), tgt, usr, "u1", "flightsql", "READ", acc, "SELECT 1")
    a.statement(tenant(text = true), tgt, usr, "u1", "flightsql", "READ", acc, "SELECT 2")
    wm.verify(2, postRequestedFor(anyUrl()))

  it should "make one call when only the text differs and the tenant does not send statement text" in:
    wm.resetAll()
    wm.stubFor(post(anyUrl()).willReturn(okJson("""{"result":{"allow":true}}""")))
    val a = authorizer(ttl = 60)
    a.statement(tenant(), tgt, usr, "u1", "flightsql", "READ", acc, "SELECT 1")
    a.statement(tenant(), tgt, usr, "u1", "flightsql", "READ", acc, "SELECT 2")
    wm.verify(1, postRequestedFor(anyUrl()))

  it should "miss the cache when the tenant's policy path changes" in:
    wm.resetAll()
    wm.stubFor(post(anyUrl()).willReturn(okJson("""{"result":{"allow":true}}""")))
    val a = authorizer(ttl = 60)
    a.statement(tenant(), tgt, usr, "u1", "flightsql", "READ", acc, "")
    a.statement(
      tenant(policyPath = Some("other/path")),
      tgt,
      usr,
      "u1",
      "flightsql",
      "READ",
      acc,
      ""
    )
    wm.verify(2, postRequestedFor(anyUrl()))

  it should "return Error when the response body exceeds 1 MiB" in:
    wm.resetAll()
    val oneMibPlusTen = "x" * ((1024 * 1024) + 10)
    wm.stubFor(
      post(anyUrl()).willReturn(okJson(s"""{"result":{"allow":true},"pad":"$oneMibPlusTen"}"""))
    )
    authorizer()
      .statement(tenant(), tgt, usr, "u1", "flightsql", "READ", acc, "") shouldBe a[Decision.Error]

  "OpaAuthorizer.connect" should "POST to the connect rule" in:
    wm.resetAll()
    wm.stubFor(
      post(urlEqualTo("/v1/data/qod/authz/connect"))
        .willReturn(okJson("""{"result":{"allow":false,"reason":"nope"}}"""))
    )
    authorizer().connect(tenant(), tgt, usr, "u1", "flightsql") shouldBe
      Decision.Deny("nope", Set.empty, None)

  // ---------- OpaPoolAccess.connect: the 6-arg RBAC overload the handshake calls ----------

  private def rbacUser(name: String) =
    RbacUser(id = s"u-$name", tenant = Some("acme"), username = name, kind = "user")

  "OpaAuthorizer.connect (RBAC overload)" should "map Allow to Right(())" in:
    wm.resetAll()
    wm.stubFor(post(anyUrl()).willReturn(okJson("""{"result":{"allow":true}}""")))
    val user = rbacUser("alice")
    val eff  = EffectiveSet(user, Nil, Nil, Nil, Nil)
    authorizer().connect(
      tenant(),
      PoolKey("acme", "tpch", "bi"),
      Nil,
      user,
      eff,
      "flightsql"
    ) shouldBe
      Right(())

  it should "map Deny to Left(HandshakeDenial.Denied) prefixed with 'pool access denied by policy'" in:
    wm.resetAll()
    wm.stubFor(post(anyUrl()).willReturn(okJson("""{"result":{"allow":false,"reason":"nope"}}""")))
    val user = rbacUser("alice")
    val eff  = EffectiveSet(user, Nil, Nil, Nil, Nil)
    authorizer().connect(tenant(), PoolKey("acme", "tpch", "bi"), Nil, user, eff, "flightsql") match
      case Left(HandshakeDenial.Denied(msg)) => msg should startWith("pool access denied by policy")
      case other                             => fail(other.toString)

  it should "map Error to Left(HandshakeDenial.Unavailable)" in:
    wm.resetAll()
    wm.stubFor(post(anyUrl()).willReturn(serverError()))
    val user = rbacUser("alice")
    val eff  = EffectiveSet(user, Nil, Nil, Nil, Nil)
    authorizer().connect(tenant(), PoolKey("acme", "tpch", "bi"), Nil, user, eff, "flightsql") match
      case Left(HandshakeDenial.Unavailable(_)) => succeed
      case other                                => fail(other.toString)

  it should "answer the shared message, never the transport cause, and log the cause at WARN" in:
    val cause          = "ConnectException: opa.internal:8181"
    val user           = rbacUser("alice")
    val eff            = EffectiveSet(user, Nil, Nil, Nil, Nil)
    val (answer, logs) = LogCapture.logged(classOf[OpaAuthorizer].getName)(
      stubbedAuthorizer(Left(cause))
        .connect(tenant(), PoolKey("acme", "tpch", "bi"), Nil, user, eff, "flightsql")
    )
    answer shouldBe Left(HandshakeDenial.Unavailable(OpaAuthorizer.UnavailableMessage))
    val leaked = answer.left.toOption.exists(_.toString.contains("opa.internal"))
    leaked shouldBe false
    val warned = logs.exists(e =>
      e.getLevel == ch.qos.logback.classic.Level.WARN &&
        e.getFormattedMessage.contains(cause) &&
        e.getFormattedMessage.contains("tenant=acme") &&
        e.getFormattedMessage.contains("connect")
    )
    warned shouldBe true

  it should "answer the shared message when no OPA URL is configured" in:
    val user = rbacUser("alice")
    val eff  = EffectiveSet(user, Nil, Nil, Nil, Nil)
    authorizer().connect(
      Tenant("acme", acl = TenantAcl(Some("opa"))),
      PoolKey("acme", "tpch", "bi"),
      Nil,
      user,
      eff,
      "flightsql"
    ) shouldBe Left(HandshakeDenial.Unavailable(OpaAuthorizer.UnavailableMessage))

  "OpaClient.jdkPost" should "never carry the bearer token in a transport error" in:
    val token  = "s3cr3t-opa-token\ninjected"
    val result = OpaClient.jdkPost(500)(s"$base/v1/data/qod/authz/connect", "{}", Some(token))
    result.isLeft shouldBe true
    val leaked = result.left.toOption.exists(_.contains("s3cr3t-opa-token"))
    leaked shouldBe false

  "OpaAuthorizer.connect (RBAC overload)" should "carry parentPool, roles and claims from the EffectiveSet into the request body" in:
    wm.resetAll()
    wm.stubFor(post(anyUrl()).willReturn(okJson("""{"result":{"allow":true}}""")))
    val user = rbacUser("alice")
    val eff  = EffectiveSet(
      user,
      roles = List(RbacRole(id = "r1", tenantId = "acme", name = "analyst")),
      groups = Nil,
      permissions = Nil,
      poolPerms = Nil,
      claims = Map("dept" -> "fin")
    )
    authorizer().connect(
      tenant(),
      PoolKey("acme", "tpch", "bi"),
      List("writer", "reader"),
      user,
      eff,
      "flightsql"
    )
    wm.verify(
      postRequestedFor(anyUrl())
        .withRequestBody(matchingJsonPath("$.input.parentPool[0]", equalTo("reader")))
        .withRequestBody(matchingJsonPath("$.input.parentPool[1]", equalTo("writer")))
        .withRequestBody(matchingJsonPath("$.input.user.roles[0]", equalTo("analyst")))
        .withRequestBody(matchingJsonPath("$.input.user.claims.dept", equalTo("fin")))
    )
