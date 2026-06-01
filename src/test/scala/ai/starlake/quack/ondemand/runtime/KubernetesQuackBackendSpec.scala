package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.model.{NodeSpec, PoolKey, Role}
import cats.effect.unsafe.implicits.global
import io.fabric8.kubernetes.api.model.{Container, ObjectMeta, Pod, PodSpec}
import io.fabric8.kubernetes.client.server.mock.KubernetesServer
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.jdk.CollectionConverters._

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

  it should "forward QOD_S3_* env vars from the manager process to the spawned pod" in:
    val fakeEnv = Map(
      "QOD_S3_ENDPOINT"          -> "http://seaweedfs:8333",
      "QOD_S3_ACCESS_KEY_ID"     -> "quack",
      "QOD_S3_SECRET_ACCESS_KEY" -> "quackquack",
      "QOD_S3_REGION"            -> "us-east-1",
      "QOD_S3_URL_STYLE"         -> "path",
      "QOD_S3_USE_SSL"           -> "false",
      // Unrelated env var that must NOT be copied through.
      "PATH" -> "/usr/bin"
    )
    val backend = new KubernetesQuackBackend(
      client    = server.getClient,
      namespace = "default",
      image     = "starlakeai/quack:test",
      quackPort = 8080,
      podLabel  = "managed-by=quack-on-demand",
      startupTimeoutSec = 5,
      readPodReady = _ => true,
      readEnv      = name => fakeEnv.get(name)
    )
    val node = backend.start(
      NodeSpec(PoolKey("acme", "sales"), "quack-cred-fwd", Role.Dual,
               metastore = Map.empty, s3 = Map.empty)
    ).unsafeRunSync()

    val container = server.getClient.pods.inNamespace("default")
      .withName(node.podName.get).get()
      .getSpec.getContainers.get(0)
    val env = container.getEnv.asScala.map(e => e.getName -> e.getValue).toMap

    env.get("QOD_S3_ENDPOINT")          shouldBe Some("http://seaweedfs:8333")
    env.get("QOD_S3_ACCESS_KEY_ID")     shouldBe Some("quack")
    env.get("QOD_S3_SECRET_ACCESS_KEY") shouldBe Some("quackquack")
    env.get("QOD_S3_REGION")            shouldBe Some("us-east-1")
    env.get("QOD_S3_URL_STYLE")         shouldBe Some("path")
    env.get("QOD_S3_USE_SSL")           shouldBe Some("false")
    env.contains("PATH") shouldBe false

  it should "let per-node metastore override the forwarded env var" in:
    val fakeEnv = Map("QOD_S3_ENDPOINT" -> "http://from-manager:8333")
    val backend = new KubernetesQuackBackend(
      client    = server.getClient,
      namespace = "default",
      image     = "starlakeai/quack:test",
      quackPort = 8080,
      podLabel  = "managed-by=quack-on-demand",
      startupTimeoutSec = 5,
      readPodReady = _ => true,
      readEnv      = name => fakeEnv.get(name)
    )
    val node = backend.start(
      NodeSpec(PoolKey("acme", "sales"), "quack-cred-override", Role.Dual,
               metastore = Map("QOD_S3_ENDPOINT" -> "http://per-pool:8333"),
               s3        = Map.empty)
    ).unsafeRunSync()

    val container = server.getClient.pods.inNamespace("default")
      .withName(node.podName.get).get()
      .getSpec.getContainers.get(0)
    val env = container.getEnv.asScala.map(e => e.getName -> e.getValue).toMap

    env("QOD_S3_ENDPOINT") shouldBe "http://per-pool:8333"

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