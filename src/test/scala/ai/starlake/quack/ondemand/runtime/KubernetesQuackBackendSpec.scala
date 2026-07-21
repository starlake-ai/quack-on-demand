package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.model.{NodeSpec, PoolKey, Role}
import cats.effect.unsafe.implicits.global
import io.fabric8.kubernetes.api.model.{Container, ObjectMeta, Pod, PodSpec}
import io.fabric8.kubernetes.client.server.mock.KubernetesServer
import org.scalatest.{BeforeAndAfterEach, OptionValues}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.jdk.CollectionConverters._

class KubernetesQuackBackendSpec
    extends AnyFlatSpec
    with Matchers
    with BeforeAndAfterEach
    with OptionValues:

  private val server = new KubernetesServer(false, true)

  override def beforeEach(): Unit = server.before()
  override def afterEach(): Unit  = server.after()

  "KubernetesQuackBackend" should "create a pod and service on start" in:
    val backend = new KubernetesQuackBackend(
      client = server.getClient,
      namespace = "default",
      image = "starlakeai/quack:test",
      quackPort = 8080,
      podLabel = "managed-by=quack-on-demand",
      startupTimeoutSec = 5,
      readPodReady = _ => true
    )
    val spec = NodeSpec(
      PoolKey("acme", "acme_default", "sales"),
      "quack-n1",
      Role.Dual,
      metastore = Map("pgHost" -> "pg"),
      s3 = Map.empty
    )
    val node = backend.start(spec).unsafeRunSync()
    node.podName.isDefined shouldBe true
    server.getClient.pods
      .inNamespace("default")
      .withName(node.podName.get)
      .get() should not be null

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
      client = server.getClient,
      namespace = "default",
      image = "starlakeai/quack:test",
      quackPort = 8080,
      podLabel = "managed-by=quack-on-demand",
      startupTimeoutSec = 5,
      readPodReady = _ => true,
      readEnv = name => fakeEnv.get(name)
    )
    val node = backend
      .start(
        NodeSpec(
          PoolKey("acme", "acme_default", "sales"),
          "quack-cred-fwd",
          Role.Dual,
          metastore = Map.empty,
          s3 = Map.empty
        )
      )
      .unsafeRunSync()

    val container = server.getClient.pods
      .inNamespace("default")
      .withName(node.podName.get)
      .get()
      .getSpec
      .getContainers
      .get(0)
    val env = container.getEnv.asScala.map(e => e.getName -> e.getValue).toMap

    env.get("QOD_S3_ENDPOINT") shouldBe Some("http://seaweedfs:8333")
    env.get("QOD_S3_ACCESS_KEY_ID") shouldBe Some("quack")
    env.get("QOD_S3_SECRET_ACCESS_KEY") shouldBe Some("quackquack")
    env.get("QOD_S3_REGION") shouldBe Some("us-east-1")
    env.get("QOD_S3_URL_STYLE") shouldBe Some("path")
    env.get("QOD_S3_USE_SSL") shouldBe Some("false")
    env.contains("PATH") shouldBe false

  it should "let per-node metastore override the forwarded env var" in:
    val fakeEnv = Map("QOD_S3_ENDPOINT" -> "http://from-manager:8333")
    val backend = new KubernetesQuackBackend(
      client = server.getClient,
      namespace = "default",
      image = "starlakeai/quack:test",
      quackPort = 8080,
      podLabel = "managed-by=quack-on-demand",
      startupTimeoutSec = 5,
      readPodReady = _ => true,
      readEnv = name => fakeEnv.get(name)
    )
    val node = backend
      .start(
        NodeSpec(
          PoolKey("acme", "acme_default", "sales"),
          "quack-cred-override",
          Role.Dual,
          metastore = Map("QOD_S3_ENDPOINT" -> "http://per-pool:8333"),
          s3 = Map.empty
        )
      )
      .unsafeRunSync()

    val container = server.getClient.pods
      .inNamespace("default")
      .withName(node.podName.get)
      .get()
      .getSpec
      .getContainers
      .get(0)
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
      client,
      "default",
      "img",
      8080,
      "managed-by=quack-on-demand",
      5,
      readPodReady = _ => true
    )
    val nodes = backend.discoverExisting().unsafeRunSync()
    nodes.map(_.nodeId) should contain("quack-acme-sales-1")
    nodes.find(_.nodeId == "quack-acme-sales-1").get.maxConcurrent shouldBe 3

  // ---------- federation: extraSetupSql via per-pool Secret ----------

  private def fedBackend = new KubernetesQuackBackend(
    client = server.getClient,
    namespace = "default",
    image = "starlakeai/quack:test",
    quackPort = 8080,
    podLabel = "managed-by=quack-on-demand",
    startupTimeoutSec = 5,
    readPodReady = _ => true
  )

  /** Same shape as the real federation blob -- a short ATTACH-flavoured string. */
  private val FedSql = "INSTALL postgres;\nLOAD postgres;\n-- federation here\n"

  /** PoolKey with a tenantDb that contains an underscore; the Secret name must hyphenize it. */
  private val FedKey  = PoolKey("acme", "acme_default", "sales")
  private val FedName = "qod-fedsql-acme-acme-default-sales"

  "federation Secret wiring" should "create a per-pool Secret and reference it from the pod" in:
    val backend = fedBackend
    val node    = backend
      .start(
        NodeSpec(
          FedKey,
          "quack-fed-1",
          Role.Dual,
          metastore = Map.empty,
          s3 = Map.empty,
          extraSetupSql = FedSql
        )
      )
      .unsafeRunSync()

    // The Secret exists with the resolved SQL on the expected key.
    val secret = server.getClient.secrets.inNamespace("default").withName(FedName).get()
    secret should not be null
    // K8s materialises stringData into base64 `data`; the test mock keeps both
    // depending on path. Read whichever is populated.
    val payload = Option(secret.getStringData)
      .map(_.asScala.toMap)
      .getOrElse(Map.empty)
      .getOrElse(
        KubernetesQuackBackend.FederationSecretKey, {
          val b64 = secret.getData.get(KubernetesQuackBackend.FederationSecretKey)
          new String(
            java.util.Base64.getDecoder.decode(b64),
            java.nio.charset.StandardCharsets.UTF_8
          )
        }
      )
    payload shouldBe FedSql

    // The pod has an `extraSetupSql` env entry whose value comes from a
    // SecretKeyRef pointing at the same Secret + same key.
    val container = server.getClient.pods
      .inNamespace("default")
      .withName(node.podName.get)
      .get()
      .getSpec
      .getContainers
      .get(0)
    val fedEnv = container.getEnv.asScala.find(_.getName == "extraSetupSql").value
    fedEnv.getValue shouldBe null // value-from, not value
    val ref = fedEnv.getValueFrom.getSecretKeyRef
    ref.getName shouldBe FedName
    ref.getKey shouldBe KubernetesQuackBackend.FederationSecretKey

  it should "skip the Secret + env entry entirely when extraSetupSql is empty" in:
    val backend = fedBackend
    val node    = backend
      .start(
        NodeSpec(FedKey, "quack-nofed-1", Role.Dual, metastore = Map.empty, s3 = Map.empty)
        // extraSetupSql defaulted to ""
      )
      .unsafeRunSync()

    server.getClient.secrets.inNamespace("default").withName(FedName).get() shouldBe null
    val container = server.getClient.pods
      .inNamespace("default")
      .withName(node.podName.get)
      .get()
      .getSpec
      .getContainers
      .get(0)
    container.getEnv.asScala.map(_.getName) should not contain "extraSetupSql"

  it should "delete the Secret when the last pod of the pool stops" in:
    val backend = fedBackend
    backend
      .start(
        NodeSpec(
          FedKey,
          "quack-fed-only-1",
          Role.Dual,
          metastore = Map.empty,
          s3 = Map.empty,
          extraSetupSql = FedSql
        )
      )
      .unsafeRunSync()

    server.getClient.secrets.inNamespace("default").withName(FedName).get() should not be null

    backend.stop("quack-fed-only-1").unsafeRunSync()

    server.getClient.secrets.inNamespace("default").withName(FedName).get() shouldBe null

  it should "keep the Secret alive when other pods of the pool remain" in:
    val backend = fedBackend
    backend
      .start(
        NodeSpec(
          FedKey,
          "quack-fed-a",
          Role.Dual,
          metastore = Map.empty,
          s3 = Map.empty,
          extraSetupSql = FedSql
        )
      )
      .unsafeRunSync()
    backend
      .start(
        NodeSpec(
          FedKey,
          "quack-fed-b",
          Role.Dual,
          metastore = Map.empty,
          s3 = Map.empty,
          extraSetupSql = FedSql
        )
      )
      .unsafeRunSync()

    backend.stop("quack-fed-a").unsafeRunSync()

    server.getClient.secrets.inNamespace("default").withName(FedName).get() should not be null

  // ---------- per-node object-store Secret ----------

  private def objectStoreSecretName(nodeId: String) = s"qod-store-$nodeId"

  /** Same shape as the real per-db object-store CREATE SECRET authored by ObjectStoreSecret.scala.
    */
  private val ObjectStoreSql =
    "CREATE OR REPLACE SECRET objstore_acme_default (TYPE s3, KEY_ID 'k', SECRET 's', SCOPE 's3://bucket/acme_default');\n"

  "object-store Secret wiring" should "create a per-node Secret and reference it from the pod" in:
    val backend = fedBackend
    val node    = backend
      .start(
        NodeSpec(
          FedKey,
          "quack-store-1",
          Role.Dual,
          metastore = Map.empty,
          s3 = Map.empty,
          objectStoreSql = ObjectStoreSql
        )
      )
      .unsafeRunSync()

    // The Secret exists with the resolved SQL on the expected key.
    val secret = server.getClient.secrets
      .inNamespace("default")
      .withName(objectStoreSecretName("quack-store-1"))
      .get()
    secret should not be null
    val payload = Option(secret.getStringData)
      .map(_.asScala.toMap)
      .getOrElse(Map.empty)
      .getOrElse(
        KubernetesQuackBackend.ObjectStoreSecretKey, {
          val b64 = secret.getData.get(KubernetesQuackBackend.ObjectStoreSecretKey)
          new String(
            java.util.Base64.getDecoder.decode(b64),
            java.nio.charset.StandardCharsets.UTF_8
          )
        }
      )
    payload shouldBe ObjectStoreSql

    // The pod has an `objectStoreSql` env entry whose value comes from a
    // SecretKeyRef pointing at the same Secret + same key (not inlined).
    val container = server.getClient.pods
      .inNamespace("default")
      .withName(node.podName.get)
      .get()
      .getSpec
      .getContainers
      .get(0)
    val storeEnv = container.getEnv.asScala.find(_.getName == "objectStoreSql").value
    storeEnv.getValue shouldBe null // value-from, not value
    val ref = storeEnv.getValueFrom.getSecretKeyRef
    ref.getName shouldBe objectStoreSecretName("quack-store-1")
    ref.getKey shouldBe KubernetesQuackBackend.ObjectStoreSecretKey

  it should "skip the Secret + env entry entirely when objectStoreSql is empty" in:
    val backend = fedBackend
    val node    = backend
      .start(
        NodeSpec(FedKey, "quack-nostore-1", Role.Dual, metastore = Map.empty, s3 = Map.empty)
        // objectStoreSql defaulted to ""
      )
      .unsafeRunSync()

    server.getClient.secrets
      .inNamespace("default")
      .withName(objectStoreSecretName("quack-nostore-1"))
      .get() shouldBe null
    val container = server.getClient.pods
      .inNamespace("default")
      .withName(node.podName.get)
      .get()
      .getSpec
      .getContainers
      .get(0)
    container.getEnv.asScala.map(_.getName) should not contain "objectStoreSql"

  it should "delete the object-store Secret on stop()" in:
    val backend = fedBackend
    backend
      .start(
        NodeSpec(
          FedKey,
          "quack-store-2",
          Role.Dual,
          metastore = Map.empty,
          s3 = Map.empty,
          objectStoreSql = ObjectStoreSql
        )
      )
      .unsafeRunSync()

    server.getClient.secrets
      .inNamespace("default")
      .withName(objectStoreSecretName("quack-store-2"))
      .get() should not be null

    backend.stop("quack-store-2").unsafeRunSync()

    server.getClient.secrets
      .inNamespace("default")
      .withName(objectStoreSecretName("quack-store-2"))
      .get() shouldBe null

  // ---------- per-pod token Secret + restart adoption ----------

  private def tokenSecretName(nodeId: String) = s"qod-token-$nodeId"

  /** Read whichever shape the mock server keeps populated. */
  private def readTokenPayload(s: io.fabric8.kubernetes.api.model.Secret): String =
    Option(s.getStringData)
      .map(_.asScala.toMap)
      .flatMap(_.get(KubernetesQuackBackend.TokenSecretKey))
      .getOrElse {
        val b64 = s.getData.get(KubernetesQuackBackend.TokenSecretKey)
        new String(java.util.Base64.getDecoder.decode(b64), java.nio.charset.StandardCharsets.UTF_8)
      }

  "token persistence" should "create a per-pod Secret and reference it from QOD_NODE_TOKEN" in:
    val backend = fedBackend
    val node    = backend
      .start(
        NodeSpec(FedKey, "quack-tok-1", Role.Dual, metastore = Map.empty, s3 = Map.empty)
      )
      .unsafeRunSync()

    // Secret exists with the token the manager handed back.
    val secret = server.getClient.secrets
      .inNamespace("default")
      .withName(tokenSecretName("quack-tok-1"))
      .get()
    secret should not be null
    readTokenPayload(secret) shouldBe node.token

    // The pod's QOD_NODE_TOKEN env is a SecretKeyRef pointing at the same
    // Secret + key. The plain `value` field stays null -- the bearer never
    // lands in the pod manifest.
    val container = server.getClient.pods
      .inNamespace("default")
      .withName(node.podName.get)
      .get()
      .getSpec
      .getContainers
      .get(0)
    val tokenEnv = container.getEnv.asScala.find(_.getName == "QOD_NODE_TOKEN").value
    tokenEnv.getValue shouldBe null
    val ref = tokenEnv.getValueFrom.getSecretKeyRef
    ref.getName shouldBe tokenSecretName("quack-tok-1")
    ref.getKey shouldBe KubernetesQuackBackend.TokenSecretKey

  it should "delete the token Secret on stop()" in:
    val backend = fedBackend
    backend
      .start(
        NodeSpec(FedKey, "quack-tok-2", Role.Dual, metastore = Map.empty, s3 = Map.empty)
      )
      .unsafeRunSync()

    server.getClient.secrets
      .inNamespace("default")
      .withName(tokenSecretName("quack-tok-2"))
      .get() should not be null

    backend.stop("quack-tok-2").unsafeRunSync()

    server.getClient.secrets
      .inNamespace("default")
      .withName(tokenSecretName("quack-tok-2"))
      .get() shouldBe null

  it should "recover the bearer token from the Secret on discoverExisting (manager restart)" in:
    // First backend instance: spawns a pod, persists its token to a Secret.
    val firstBackend = fedBackend
    val originalNode = firstBackend
      .start(
        NodeSpec(FedKey, "quack-tok-3", Role.Dual, metastore = Map.empty, s3 = Map.empty)
      )
      .unsafeRunSync()
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

  // ---------- spawn-failure rollback (no orphaned pod/secret) ----------

  "start() failure rollback" should "delete the pod and token Secret when waitReady times out" in:
    // Pod never becomes ready -> waitReady times out (short timeout keeps the
    // test fast) -> start() raises. The onError hook must then delete the pod
    // and the per-pod token Secret this call created so a later reconcile can
    // respawn on the same deterministic nodeId without an already-exists
    // conflict.
    val backend = new KubernetesQuackBackend(
      client = server.getClient,
      namespace = "default",
      image = "starlakeai/quack:test",
      quackPort = 8080,
      podLabel = "managed-by=quack-on-demand",
      startupTimeoutSec = 1,
      readPodReady = _ => false
    )
    val spec = NodeSpec(FedKey, "quack-fail-1", Role.Dual, metastore = Map.empty, s3 = Map.empty)

    an[Exception] should be thrownBy backend.start(spec).unsafeRunSync()

    // Both the pod and its token Secret must be gone -- no orphans left behind.
    server.getClient.pods.inNamespace("default").withName("quack-fail-1").get() shouldBe null
    server.getClient.secrets
      .inNamespace("default")
      .withName(tokenSecretName("quack-fail-1"))
      .get() shouldBe null

  it should "leave the shared per-pool federation Secret in place on a single node's failure" in:
    // A sibling pod of the same pool already exists and references the
    // federation Secret. A second node's spawn that times out must roll back
    // only its own pod/token Secret and must NOT delete the shared federation
    // Secret out from under the healthy sibling.
    val healthy = fedBackend
    healthy
      .start(
        NodeSpec(
          FedKey,
          "quack-fed-sibling",
          Role.Dual,
          metastore = Map.empty,
          s3 = Map.empty,
          extraSetupSql = FedSql
        )
      )
      .unsafeRunSync()
    server.getClient.secrets.inNamespace("default").withName(FedName).get() should not be null

    val failing = new KubernetesQuackBackend(
      client = server.getClient,
      namespace = "default",
      image = "starlakeai/quack:test",
      quackPort = 8080,
      podLabel = "managed-by=quack-on-demand",
      startupTimeoutSec = 1,
      readPodReady = _ => false
    )
    an[Exception] should be thrownBy failing
      .start(
        NodeSpec(
          FedKey,
          "quack-fed-failing",
          Role.Dual,
          metastore = Map.empty,
          s3 = Map.empty,
          extraSetupSql = FedSql
        )
      )
      .unsafeRunSync()

    // The failing node's own resources are cleaned up ...
    server.getClient.pods.inNamespace("default").withName("quack-fed-failing").get() shouldBe null
    server.getClient.secrets
      .inNamespace("default")
      .withName(tokenSecretName("quack-fed-failing"))
      .get() shouldBe null
    // ... but the shared federation Secret survives for the healthy sibling.
    server.getClient.secrets.inNamespace("default").withName(FedName).get() should not be null

  // ---------- per-pool resources + gated pod template ----------

  private val ns       = "default"
  private val image    = "starlakeai/quack:test"
  private val baseSpec = NodeSpec(
    PoolKey("acme", "acme_tpch", "bi"),
    "quack-tmpl-1",
    Role.Dual,
    metastore = Map.empty,
    s3 = Map.empty
  )

  private def makeBackend(podTemplateEnabled: Boolean): KubernetesQuackBackend =
    new KubernetesQuackBackend(
      client = server.getClient,
      namespace = ns,
      image = image,
      quackPort = 8080,
      podLabel = "managed-by=quack-on-demand",
      startupTimeoutSec = 5,
      readPodReady = _ => true,
      podTemplateEnabled = podTemplateEnabled
    )

  private def backend: KubernetesQuackBackend = makeBackend(podTemplateEnabled = true)

  "resources overlay" should "set requests and limits equal on the quack container when cpu/memory are present" in:
    val spec = baseSpec.copy(cpu = Some("500m"), memory = Some("2Gi"))
    backend.start(spec).unsafeRunSync()
    val c =
      server.getClient.pods.inNamespace(ns).withName(spec.nodeId).get.getSpec.getContainers.get(0)
    c.getResources.getRequests.get("cpu").toString shouldBe "500m"
    c.getResources.getLimits.get("cpu").toString shouldBe "500m"
    c.getResources.getRequests.get("memory").toString shouldBe "2Gi"
    c.getResources.getLimits.get("memory").toString shouldBe "2Gi"

  it should "set only the provided resource key" in:
    backend.start(baseSpec.copy(memory = Some("1Gi"))).unsafeRunSync()
    val c = server.getClient.pods
      .inNamespace(ns)
      .withName(baseSpec.nodeId)
      .get
      .getSpec
      .getContainers
      .get(0)
    c.getResources.getLimits.get("memory").toString shouldBe "1Gi"
    Option(c.getResources.getLimits.get("cpu")) shouldBe None

  it should "build from the pod template and overlay the quack env contract when the gate is on" in:
    val tmpl =
      """apiVersion: v1
        |kind: Pod
        |spec:
        |  containers:
        |    - name: quack
        |      image: placeholder
        |    - name: sidecar
        |      image: busybox""".stripMargin
    val backendOn = makeBackend(podTemplateEnabled = true)
    backendOn.start(baseSpec.copy(podTemplateYaml = Some(tmpl))).unsafeRunSync()
    val pod = server.getClient.pods.inNamespace(ns).withName(baseSpec.nodeId).get
    pod.getSpec.getContainers.asScala.map(_.getName) should contain allOf ("quack", "sidecar")
    val quack = pod.getSpec.getContainers.asScala.find(_.getName == "quack").get
    quack.getEnv.asScala.map(_.getName) should contain("QOD_NODE_TOKEN")
    quack.getImage shouldBe image

  it should "ignore a stored template and build from scratch when the gate is off" in:
    val tmpl =
      "apiVersion: v1\nkind: Pod\nspec:\n  containers:\n    - name: quack\n      image: x\n    - name: sidecar\n      image: y"
    val backendOff = makeBackend(podTemplateEnabled = false)
    backendOff.start(baseSpec.copy(podTemplateYaml = Some(tmpl))).unsafeRunSync()
    val pod = server.getClient.pods.inNamespace(ns).withName(baseSpec.nodeId).get
    pod.getSpec.getContainers.asScala.map(_.getName) should contain only "quack"

  it should "let structured resources overwrite the template's quack resources" in:
    val tmpl =
      "apiVersion: v1\nkind: Pod\nspec:\n  containers:\n    - name: quack\n      image: x\n      resources:\n        limits:\n          memory: 99Gi"
    val backendOn = makeBackend(podTemplateEnabled = true)
    backendOn
      .start(baseSpec.copy(podTemplateYaml = Some(tmpl), memory = Some("2Gi")))
      .unsafeRunSync()
    val quack = server.getClient.pods
      .inNamespace(ns)
      .withName(baseSpec.nodeId)
      .get
      .getSpec
      .getContainers
      .asScala
      .find(_.getName == "quack")
      .get
    quack.getResources.getLimits.get("memory").toString shouldBe "2Gi"

  it should "overwrite template metadata labels with the manager identity labels" in:
    val tmpl =
      """apiVersion: v1
        |kind: Pod
        |metadata:
        |  labels:
        |    app: wrong
        |spec:
        |  containers:
        |    - name: quack
        |      image: placeholder""".stripMargin
    val backendOn = makeBackend(podTemplateEnabled = true)
    backendOn.start(baseSpec.copy(podTemplateYaml = Some(tmpl))).unsafeRunSync()
    val pod    = server.getClient.pods.inNamespace(ns).withName(baseSpec.nodeId).get
    val labels = pod.getMetadata.getLabels.asScala.toMap
    labels should not contain key("app")
    labels("quack-tenant") shouldBe "acme"

  it should "replace template env on the quack container with the manager env contract" in:
    val tmpl =
      """apiVersion: v1
        |kind: Pod
        |spec:
        |  containers:
        |    - name: quack
        |      image: placeholder
        |      env:
        |        - name: MY_SECRET
        |          value: bad""".stripMargin
    val backendOn = makeBackend(podTemplateEnabled = true)
    backendOn.start(baseSpec.copy(podTemplateYaml = Some(tmpl))).unsafeRunSync()
    val quack = server.getClient.pods
      .inNamespace(ns)
      .withName(baseSpec.nodeId)
      .get
      .getSpec
      .getContainers
      .asScala
      .find(_.getName == "quack")
      .get
    val envNames = quack.getEnv.asScala.map(_.getName).toSet
    envNames should not contain "MY_SECRET"
    envNames should contain("QOD_NODE_TOKEN")

  it should "apply the configured serviceAccount and Service type to spawned resources" in:
    val backend = new KubernetesQuackBackend(
      client = server.getClient,
      namespace = "default",
      image = "starlakeai/quack:test",
      quackPort = 8080,
      podLabel = "managed-by=quack-on-demand",
      startupTimeoutSec = 5,
      readPodReady = _ => true,
      serviceAccount = Some("quack-nodes"),
      serviceType = "NodePort"
    )
    val spec = NodeSpec(
      PoolKey("acme", "acme_default", "sales"),
      "quack-sa1",
      Role.Dual,
      metastore = Map("pgHost" -> "pg"),
      s3 = Map.empty
    )
    val node = backend.start(spec).unsafeRunSync()
    val pod  = server.getClient.pods.inNamespace("default").withName(node.podName.get).get()
    pod.getSpec.getServiceAccountName shouldBe "quack-nodes"
    val svc = server.getClient.services.inNamespace("default").withName("quack-sa1").get()
    svc.getSpec.getType shouldBe "NodePort"

  it should "leave serviceAccountName unset and default the Service type to ClusterIP" in:
    val backend = new KubernetesQuackBackend(
      client = server.getClient,
      namespace = "default",
      image = "starlakeai/quack:test",
      quackPort = 8080,
      podLabel = "managed-by=quack-on-demand",
      startupTimeoutSec = 5,
      readPodReady = _ => true
    )
    val spec = NodeSpec(
      PoolKey("acme", "acme_default", "sales"),
      "quack-sa2",
      Role.Dual,
      metastore = Map("pgHost" -> "pg"),
      s3 = Map.empty
    )
    val node = backend.start(spec).unsafeRunSync()
    val pod  = server.getClient.pods.inNamespace("default").withName(node.podName.get).get()
    pod.getSpec.getServiceAccountName shouldBe null
    val svc = server.getClient.services.inNamespace("default").withName("quack-sa2").get()
    svc.getSpec.getType shouldBe "ClusterIP"
