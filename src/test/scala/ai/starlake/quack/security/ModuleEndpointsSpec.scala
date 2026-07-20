// src/test/scala/ai/starlake/quack/security/ModuleEndpointsSpec.scala
package ai.starlake.quack.security

import ai.starlake.quack.module.TestModule
import ai.starlake.quack.spi.*
import cats.effect.IO
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint

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

  "a public prefix" should "match on segment boundaries, not raw string prefixes" in {
    // Declared public prefix is "/api/hosted-test/ping"; the endpoint below is
    // "/api/hosted-test/pingx", which shares the raw string prefix but is NOT a
    // sub-path of it. Segment-safe matching must keep pingx gated behind the key.
    val localModule = new ManagerModule:
      def name: String                             = "prefix-boundary-module"
      def changelogPath: Option[String]            = None
      def start(ctx: ManagerContext): IO[Unit]     = IO.unit
      def endpoints: List[ServerEndpoint[Any, IO]] = List(
        endpoint.get
          .in("api" / "hosted-test" / "pingx")
          .out(stringBody)
          .serverLogicSuccess[IO](_ => IO.pure("pongx"))
      )
      def publicPathPrefixes: Set[String]        = Set("/api/hosted-test/ping")
      def onEvent(event: ManagerEvent): IO[Unit] = IO.unit
      def stop: IO[Unit]                         = IO.unit

    val fix   = SecurityFixtures.freshStore()
    val local = ManagerServerHarness.boot(
      fix.store,
      staticApiKey = Some(ApiKey),
      moduleEndpoints = localModule.endpoints,
      modulePublicPrefixes = localModule.publicPathPrefixes
    )
    try
      val denied = get(local.httpClient, s"${local.baseUrl}/api/hosted-test/pingx")
      withClue(s"GET /api/hosted-test/pingx (no key) body: ${denied.body()}") {
        denied.statusCode() shouldBe 401
      }
    finally local.shutdown()
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
