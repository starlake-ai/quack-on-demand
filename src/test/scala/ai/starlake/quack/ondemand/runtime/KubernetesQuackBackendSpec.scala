package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.model.{NodeSpec, PoolKey, Role}
import cats.effect.unsafe.implicits.global
import io.fabric8.kubernetes.api.model.{Container, ObjectMeta, Pod, PodSpec}
import io.fabric8.kubernetes.client.server.mock.KubernetesServer
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class KubernetesQuackBackendSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach:

  private val server = new KubernetesServer(false, true)

  override def beforeEach(): Unit = server.before()
  override def afterEach():  Unit = server.after()

  "KubernetesQuackBackend" should "create a pod and service on start" in:
    val backend = new KubernetesQuackBackend(
      client    = server.getClient,
      namespace = "default",
      image     = "starlakeai/quack:test",
      quackPort = 8080,
      podLabel  = "managed-by=quack-on-demand",
      startupTimeoutSec = 5,
      readPodReady = _ => true
    )
    val spec = NodeSpec(PoolKey("acme", "sales"), "quack-n1", Role.Dual,
                        metastore = Map("pgHost" -> "pg"), s3 = Map.empty)
    val node = backend.start(spec).unsafeRunSync()
    node.podName.isDefined shouldBe true
    server.getClient.pods.inNamespace("default")
      .withName(node.podName.get).get() should not be null

  it should "discover existing pods by label on restart" in:
    val client = server.getClient

    val container = new Container()
    container.setName("quack")
    container.setImage("x")
    val podSpec = new PodSpec()
    podSpec.setContainers(java.util.List.of(container))

    val labels = new java.util.HashMap[String, String]()
    labels.put("managed-by", "quack-on-demand")
    labels.put("quack-tenant", "acme")
    labels.put("quack-pool", "sales")
    labels.put("quack-role", "Dual")
    labels.put("quack-max-concurrent", "3")

    val meta = new ObjectMeta()
    meta.setName("quack-acme-sales-1")
    meta.setLabels(labels)

    val seedPod = new Pod()
    seedPod.setMetadata(meta)
    seedPod.setSpec(podSpec)

    client.pods.inNamespace("default").resource(seedPod).create()

    val backend = new KubernetesQuackBackend(
      client, "default", "img", 8080, "managed-by=quack-on-demand", 5,
      readPodReady = _ => true)
    val nodes = backend.discoverExisting().unsafeRunSync()
    nodes.map(_.nodeId) should contain ("quack-acme-sales-1")
    nodes.find(_.nodeId == "quack-acme-sales-1").get.maxConcurrent shouldBe 3