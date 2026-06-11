package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{NodeSpec, RunningNode}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.manifest.ConfigManifest
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class ManifestHandlersSpec extends AnyFlatSpec with Matchers:

  private def stubBackend: QuackBackend = new QuackBackend:
    def start(s: NodeSpec): IO[RunningNode] =
      IO.pure(RunningNode(s.nodeId, s.poolKey, s.role, "127.0.0.1", 21000, "tok",
                          Some(1L), None, Instant.EPOCH, maxConcurrent = s.maxConcurrent))
    def stop(id: String)    = IO.unit
    def isAlive(id: String) = true
    def discoverExisting()  = IO.pure(Nil)
    def cleanup()           = IO.unit

  private def newHandlers(store: InMemoryControlPlaneStore = new InMemoryControlPlaneStore()): ManifestHandlers =
    val sup = new PoolSupervisor(stubBackend, new NodeLoadTracker, store)
    new ManifestHandlers(store, sup, managerVersion = "test", hostname = "host")

  "ManifestHandlers.export" should "return a v1 YAML manifest" in {
    val handlers = newHandlers()
    val yaml     = handlers.exportYaml(None)((_: String) => None).unsafeRunSync().toOption.get
    yaml should include ("apiVersion: quack-on-demand/v1")
    yaml should include ("kind: ConfigManifest")
  }

  "ManifestHandlers.import" should "reject invalid apiVersion with 400" in {
    val handlers = newHandlers()
    val bad =
      """apiVersion: quack-on-demand/v99
        |kind: ConfigManifest
        |exportedAt: '2026-06-05T12:00:00Z'
        |exportedFrom: { managerVersion: test, hostname: host }
        |""".stripMargin
    val res = handlers.importYaml(bad, None)((_: String) => None).unsafeRunSync()
    res.isLeft shouldBe true
    res.left.toOption.get._1.code shouldBe 400
  }

  it should "reload the supervisor cache after a successful import" in {
    val store    = new InMemoryControlPlaneStore()
    val sup      = new PoolSupervisor(stubBackend, new NodeLoadTracker, store)
    val handlers = new ManifestHandlers(store, sup, managerVersion = "test", hostname = "host")
    val yaml =
      """apiVersion: quack-on-demand/v1
        |kind: ConfigManifest
        |exportedAt: '2026-06-05T12:00:00Z'
        |exportedFrom: { managerVersion: test, hostname: host }
        |tenants:
        |  - name: demo
        |""".stripMargin
    sup.listTenants() shouldBe empty
    handlers.importYaml(yaml, None)((_: String) => None).unsafeRunSync().isRight shouldBe true
    sup.listTenants().map(_.displayName) should contain ("demo")
  }