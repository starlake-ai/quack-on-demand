package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.model.{NodeSpec, PoolKey, Role}
import cats.effect.unsafe.implicits.global
import io.fabric8.kubernetes.api.model.{Container, ObjectMeta, Pod, PodSpec}
import io.fabric8.kubernetes.client.server.mock.KubernetesServer
import org.scalatest.{BeforeAndAfterEach, OptionValues}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.jdk.CollectionConverters._

class KubernetesQuackBackendSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach with OptionValues:

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
    val spec = NodeSpec(PoolKey("acme", "acme_default", "sales"), "quack-n1", Role.Dual,
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
      NodeSpec(PoolKey("acme", "acme_default", "sales"), "quack-cred-fwd", Role.Dual,
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
      NodeSpec(PoolKey("acme", "acme_default", "sales"), "quack-cred-override", Role.Dual,
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
    labels.put("quack-tenant-db", "acme_default")
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

  // ---------- federation: extraSetupSql via per-pool Secret ----------

  private def fedBackend = new KubernetesQuackBackend(
    client            = server.getClient,
    namespace         = "default",
    image             = "starlakeai/quack:test",
    quackPort         = 8080,
    podLabel          = "managed-by=quack-on-demand",
    startupTimeoutSec = 5,
    readPodReady      = _ => true
  )

  /** Same shape as the real federation blob -- a short ATTACH-flavoured string. */
  private val FedSql = "INSTALL postgres;\nLOAD postgres;\n-- federation here\n"

  /** PoolKey with a tenantDb that contains an underscore; the Secret name must hyphenize it. */
  private val FedKey  = PoolKey("acme", "acme_default", "sales")
  private val FedName = "qod-fedsql-acme-acme-default-sales"

  "federation Secret wiring" should "create a per-pool Secret and reference it from the pod" in:
    val backend = fedBackend
    val node    = backend.start(
      NodeSpec(FedKey, "quack-fed-1", Role.Dual,
               metastore = Map.empty, s3 = Map.empty,
               extraSetupSql = FedSql)
    ).unsafeRunSync()

    // The Secret exists with the resolved SQL on the expected key.
    val secret = server.getClient.secrets.inNamespace("default").withName(FedName).get()
    secret should not be null
    // K8s materialises stringData into base64 `data`; the test mock keeps both
    // depending on path. Read whichever is populated.
    val payload = Option(secret.getStringData).map(_.asScala.toMap).getOrElse(Map.empty)
      .getOrElse(KubernetesQuackBackend.FederationSecretKey, {
        val b64 = secret.getData.get(KubernetesQuackBackend.FederationSecretKey)
        new String(java.util.Base64.getDecoder.decode(b64), java.nio.charset.StandardCharsets.UTF_8)
      })
    payload shouldBe FedSql

    // The pod has an `extraSetupSql` env entry whose value comes from a
    // SecretKeyRef pointing at the same Secret + same key.
    val container = server.getClient.pods.inNamespace("default")
      .withName(node.podName.get).get()
      .getSpec.getContainers.get(0)
    val fedEnv = container.getEnv.asScala.find(_.getName == "extraSetupSql").value
    fedEnv.getValue shouldBe null  // value-from, not value
    val ref = fedEnv.getValueFrom.getSecretKeyRef
    ref.getName shouldBe FedName
    ref.getKey  shouldBe KubernetesQuackBackend.FederationSecretKey

  it should "skip the Secret + env entry entirely when extraSetupSql is empty" in:
    val backend = fedBackend
    val node    = backend.start(
      NodeSpec(FedKey, "quack-nofed-1", Role.Dual,
               metastore = Map.empty, s3 = Map.empty)
      // extraSetupSql defaulted to ""
    ).unsafeRunSync()

    server.getClient.secrets.inNamespace("default").withName(FedName).get() shouldBe null
    val container = server.getClient.pods.inNamespace("default")
      .withName(node.podName.get).get()
      .getSpec.getContainers.get(0)
    container.getEnv.asScala.map(_.getName) should not contain "extraSetupSql"

  it should "delete the Secret when the last pod of the pool stops" in:
    val backend = fedBackend
    backend.start(
      NodeSpec(FedKey, "quack-fed-only-1", Role.Dual,
               metastore = Map.empty, s3 = Map.empty,
               extraSetupSql = FedSql)
    ).unsafeRunSync()

    server.getClient.secrets.inNamespace("default").withName(FedName).get() should not be null

    backend.stop("quack-fed-only-1").unsafeRunSync()

    server.getClient.secrets.inNamespace("default").withName(FedName).get() shouldBe null

  it should "keep the Secret alive when other pods of the pool remain" in:
    val backend = fedBackend
    backend.start(
      NodeSpec(FedKey, "quack-fed-a", Role.Dual,
               metastore = Map.empty, s3 = Map.empty,
               extraSetupSql = FedSql)
    ).unsafeRunSync()
    backend.start(
      NodeSpec(FedKey, "quack-fed-b", Role.Dual,
               metastore = Map.empty, s3 = Map.empty,
               extraSetupSql = FedSql)
    ).unsafeRunSync()

    backend.stop("quack-fed-a").unsafeRunSync()

    server.getClient.secrets.inNamespace("default").withName(FedName).get() should not be null

  // ---------- per-pod token Secret + restart adoption ----------

  private def tokenSecretName(nodeId: String) = s"qod-token-$nodeId"

  /** Read whichever shape the mock server keeps populated. */
  private def readTokenPayload(s: io.fabric8.kubernetes.api.model.Secret): String =
    Option(s.getStringData).map(_.asScala.toMap).flatMap(_.get(KubernetesQuackBackend.TokenSecretKey))
      .getOrElse {
        val b64 = s.getData.get(KubernetesQuackBackend.TokenSecretKey)
        new String(java.util.Base64.getDecoder.decode(b64), java.nio.charset.StandardCharsets.UTF_8)
      }

  "token persistence" should "create a per-pod Secret and reference it from QOD_NODE_TOKEN" in:
    val backend = fedBackend
    val node    = backend.start(
      NodeSpec(FedKey, "quack-tok-1", Role.Dual, metastore = Map.empty, s3 = Map.empty)
    ).unsafeRunSync()

    // Secret exists with the token the manager handed back.
    val secret = server.getClient.secrets.inNamespace("default")
      .withName(tokenSecretName("quack-tok-1")).get()
    secret should not be null
    readTokenPayload(secret) shouldBe node.token

    // The pod's QOD_NODE_TOKEN env is a SecretKeyRef pointing at the same
    // Secret + key. The plain `value` field stays null -- the bearer never
    // lands in the pod manifest.
    val container = server.getClient.pods.inNamespace("default")
      .withName(node.podName.get).get()
      .getSpec.getContainers.get(0)
    val tokenEnv = container.getEnv.asScala.find(_.getName == "QOD_NODE_TOKEN").value
    tokenEnv.getValue shouldBe null
    val ref = tokenEnv.getValueFrom.getSecretKeyRef
    ref.getName shouldBe tokenSecretName("quack-tok-1")
    ref.getKey  shouldBe KubernetesQuackBackend.TokenSecretKey

  it should "delete the token Secret on stop()" in:
    val backend = fedBackend
    backend.start(
      NodeSpec(FedKey, "quack-tok-2", Role.Dual, metastore = Map.empty, s3 = Map.empty)
    ).unsafeRunSync()

    server.getClient.secrets.inNamespace("default")
      .withName(tokenSecretName("quack-tok-2")).get() should not be null

    backend.stop("quack-tok-2").unsafeRunSync()

    server.getClient.secrets.inNamespace("default")
      .withName(tokenSecretName("quack-tok-2")).get() shouldBe null

  it should "recover the bearer token from the Secret on discoverExisting (manager restart)" in:
    // First backend instance: spawns a pod, persists its token to a Secret.
    val firstBackend = fedBackend
    val originalNode = firstBackend.start(
      NodeSpec(FedKey, "quack-tok-3", Role.Dual, metastore = Map.empty, s3 = Map.empty)
    ).unsafeRunSync()
    val originalToken = originalNode.token
    originalToken should not be empty

    // Second backend instance against the SAME mock server (simulating a
    // manager restart). Its in-memory `tokens` map is empty; without the
    // Secret-read code path, discoverExisting would surface "" and every
    // Flight call would 401.
    val rebornBackend = fedBackend
    val rebornNodes   = rebornBackend.discoverExisting().unsafeRunSync()
    val recovered     = rebornNodes.find(_.nodeId == "quack-tok-3").value
    recovered.token shouldBe originalToken