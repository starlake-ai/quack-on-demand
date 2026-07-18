// src/test/scala/ai/starlake/quack/security/ModuleEndpointsSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.module.TestModule
import ai.starlake.quack.spi.StaticMount
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.compiletime.uninitialized

/** Pins the ManagerServer plumbing added in Task 7: module REST endpoints flow through the same
  * apiKeyGuard as core endpoints (gated unless whitelisted via `modulePublicPrefixes`), module
  * static mounts get an SPA-fallback route exactly like the core `/ui` mount, and duplicate routes
  * between core and module endpoints abort server construction.
  */
class ModuleEndpointsSpec
    extends AnyFlatSpec
    with Matchers
    with SecurityHttpHelpers
    with BeforeAndAfterAll:

  private val module = new TestModule
  private val ApiKey = "test-key"

  private var harness: ManagerServerHarness.Harness = uninitialized

  override def beforeAll(): Unit =
    val fix = SecurityFixtures.freshStore()
    harness = ManagerServerHarness.boot(
      fix.store,
      staticApiKey = Some(ApiKey),
      moduleEndpoints = module.endpoints,
      modulePublicPrefixes = module.publicPathPrefixes,
      moduleStaticMounts = List(StaticMount("/portal", "/portal-test"))
    )

  override def afterAll(): Unit =
    if harness != null then harness.shutdown()

  "module endpoints" should "serve a public-prefix route without any credential" in {
    val resp = get(harness.httpClient, s"${harness.baseUrl}/api/hosted-test/ping")
    withClue(s"GET /api/hosted-test/ping body: ${resp.body()}") {
      resp.statusCode() shouldBe 200
    }
    resp.body() shouldBe "pong"
  }

  it should "gate non-public module routes behind the api key" in {
    val denied = get(harness.httpClient, s"${harness.baseUrl}/api/hosted-test/secret")
    withClue(s"GET /api/hosted-test/secret (no key) body: ${denied.body()}") {
      denied.statusCode() shouldBe 401
    }

    val admitted =
      get(harness.httpClient, s"${harness.baseUrl}/api/hosted-test/secret", apiKey = Some(ApiKey))
    withClue(s"GET /api/hosted-test/secret (with key) body: ${admitted.body()}") {
      admitted.statusCode() shouldBe 200
    }
    admitted.body() shouldBe "classified"
  }

  "module static mounts" should "serve assets and SPA-fallback to index.html" in {
    val root = get(harness.httpClient, s"${harness.baseUrl}/portal/")
    withClue(s"GET /portal/ body: ${root.body()}") {
      root.statusCode() shouldBe 200
    }
    root.body() should include("portal-index")

    val deep = get(harness.httpClient, s"${harness.baseUrl}/portal/deep/route")
    withClue(s"GET /portal/deep/route body: ${deep.body()}") {
      deep.statusCode() shouldBe 200
    }
    deep.body() should include("portal-index")
  }

  "route collisions" should "fail server construction" in {
    val fix = SecurityFixtures.freshStore()
    val ex  = intercept[IllegalStateException] {
      ManagerServerHarness.boot(
        fix.store,
        staticApiKey = Some(ApiKey),
        moduleEndpoints = module.endpoints ++ module.endpoints
      )
    }
    ex.getMessage should include("duplicate REST routes")
  }
