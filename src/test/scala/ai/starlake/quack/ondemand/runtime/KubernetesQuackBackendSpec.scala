package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.model.{NodeSpec, PoolKey, Role}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.testkit.LogCapture
import cats.effect.unsafe.implicits.global
import ch.qos.logback.classic.Level
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
      readPodReady = _ => true,
      nodeKeys = ai.starlake.quack.edge.authz.NodeKeys.ephemeral(),
      nodeAuthzLimits = ai.starlake.quack.NodeAuthzLimitsConfig(),
      nodeFiles = ai.starlake.quack.NodeFilesConfig()
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

  it should "stop() waits out the pod delete and still succeeds when everything is already gone" in:
    val backend = new KubernetesQuackBackend(
      client = server.getClient,
      namespace = "default",
      image = "starlakeai/quack:test",
      quackPort = 8080,
      podLabel = "managed-by=quack-on-demand",
      startupTimeoutSec = 5,
      readPodReady = _ => true,
      stopTimeoutSec = 2,
      nodeKeys = ai.starlake.quack.edge.authz.NodeKeys.ephemeral(),
      nodeAuthzLimits = ai.starlake.quack.NodeAuthzLimitsConfig(),
      nodeFiles = ai.starlake.quack.NodeFilesConfig()
    )
    val key = PoolKey("acme", "acme_default", "sales")
    noException should be thrownBy backend.stop(key, "quack-never-existed").unsafeRunSync()

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
      readEnv = name => fakeEnv.get(name),
      nodeKeys = ai.starlake.quack.edge.authz.NodeKeys.ephemeral(),
      nodeAuthzLimits = ai.starlake.quack.NodeAuthzLimitsConfig(),
      nodeFiles = ai.starlake.quack.NodeFilesConfig()
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
    // The credentials are files of the secrets volume (via the node-env Secret), never env vars.
    env.contains("QOD_S3_ACCESS_KEY_ID") shouldBe false
    env.contains("QOD_S3_SECRET_ACCESS_KEY") shouldBe false
    container.getEnv.asScala.map(_.getValue) should not contain "quackquack"
    val pod = server.getClient.pods.inNamespace("default").withName(node.podName.get).get()
    SecretsVolumeView.files(pod).get("QOD_S3_SECRET_ACCESS_KEY") shouldBe
      Some(("qod-nodeenv--acme--acme-default--sales", "QOD_S3_SECRET_ACCESS_KEY"))
    env.get("QOD_S3_REGION") shouldBe Some("us-east-1")
    env.get("QOD_S3_URL_STYLE") shouldBe Some("path")
    env.get("QOD_S3_USE_SSL") shouldBe Some("false")
    env.contains("PATH") shouldBe false

  // SEC-1: the object-store settings are the manager's own. A tenant-db's metastore (tenant-admin
  // input) neither replaces a forwarded value nor suppresses it.
  it should "forward the manager's object-store settings whatever the metastore names" in:
    val fakeEnv = Map("QOD_S3_ENDPOINT" -> "http://from-manager:8333")
    val backend = new KubernetesQuackBackend(
      client = server.getClient,
      namespace = "default",
      image = "starlakeai/quack:test",
      quackPort = 8080,
      podLabel = "managed-by=quack-on-demand",
      startupTimeoutSec = 5,
      readPodReady = _ => true,
      readEnv = name => fakeEnv.get(name),
      nodeKeys = ai.starlake.quack.edge.authz.NodeKeys.ephemeral(),
      nodeAuthzLimits = ai.starlake.quack.NodeAuthzLimitsConfig(),
      nodeFiles = ai.starlake.quack.NodeFilesConfig()
    )
    val (node, logged) = LogCapture.logged(classOf[KubernetesQuackBackend].getName)(
      backend
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
    )
    // The metastore's key named once, at ERROR, never with its value.
    LogCapture.droppedKeyLines(logged) shouldBe
      List((Level.ERROR, "acme/acme_default/sales", "QOD_S3_ENDPOINT"))
    withClue("the metastore's value in the backend's log: ")(
      logged.exists(_.getFormattedMessage.contains("per-pool")) shouldBe false
    )

    val container = server.getClient.pods
      .inNamespace("default")
      .withName(node.podName.get)
      .get()
      .getSpec
      .getContainers
      .get(0)
    val endpoints =
      container.getEnv.asScala.filter(_.getName == "QOD_S3_ENDPOINT").map(_.getValue).toList

    endpoints shouldBe List("http://from-manager:8333")

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
      readPodReady = _ => true,
      nodeKeys = ai.starlake.quack.edge.authz.NodeKeys.ephemeral(),
      nodeAuthzLimits = ai.starlake.quack.NodeAuthzLimitsConfig(),
      nodeFiles = ai.starlake.quack.NodeFilesConfig()
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
    readPodReady = _ => true,
    nodeKeys = ai.starlake.quack.edge.authz.NodeKeys.ephemeral(),
    nodeAuthzLimits = ai.starlake.quack.NodeAuthzLimitsConfig(),
    nodeFiles = ai.starlake.quack.NodeFilesConfig()
  )

  /** Same shape as the real federation blob -- a short ATTACH-flavoured string. */
  private val FedSql = "INSTALL postgres;\nLOAD postgres;\n-- federation here\n"

  /** PoolKey with a tenantDb that contains an underscore; the Secret name must hyphenize it. */
  private val FedKey  = PoolKey("acme", "acme_default", "sales")
  private val FedName = "qod-fedsql--acme--acme-default--sales"

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

    // The pod reads it as the `extraSetupSql` file of the secrets volume, never as an env var.
    val pod       = server.getClient.pods.inNamespace("default").withName(node.podName.get).get()
    val container = pod.getSpec.getContainers.get(0)
    container.getEnv.asScala.map(_.getName) should not contain "extraSetupSql"
    SecretsVolumeView.files(pod).get("extraSetupSql") shouldBe
      Some((FedName, KubernetesQuackBackend.FederationSecretKey))

  it should "GC the federation Secret on stop even when the pod is already gone" in:
    val backend = fedBackend
    val key     = PoolKey("acme", "acme_default", "sales")
    val spec    = NodeSpec(
      key,
      "quack-fed-gone",
      Role.Dual,
      metastore = Map("pgHost" -> "pg"),
      s3 = Map.empty,
      extraSetupSql = "CREATE SECRET s (TYPE S3);"
    )
    backend.start(spec).unsafeRunSync()
    server.getClient.secrets
      .inNamespace("default")
      .withName(FedName)
      .get() should not be null
    // Pod vanishes out-of-band (node-group replacement).
    server.getClient.pods.inNamespace("default").withName("quack-fed-gone").delete()
    backend.stop(key, "quack-fed-gone").unsafeRunSync()
    server.getClient.secrets
      .inNamespace("default")
      .withName(FedName)
      .get() shouldBe null

  it should "GC the Secret even when an unmanaged pod wears the pool labels" in:
    val backend = fedBackend
    backend
      .start(
        NodeSpec(
          FedKey,
          "quack-fed-gc-2",
          Role.Dual,
          metastore = Map.empty,
          s3 = Map.empty,
          extraSetupSql = FedSql
        )
      )
      .unsafeRunSync()
    // Foreign pod: carries the three quack-* pool labels but NOT the managed-by
    // label. It must not count as a remaining pool member and keep the Secret alive.
    val foreignMeta = new ObjectMeta()
    foreignMeta.setName("foreign-observer")
    val foreignLabels = new java.util.HashMap[String, String]()
    foreignLabels.put("quack-tenant", FedKey.tenant)
    foreignLabels.put("quack-tenant-db", FedKey.tenantDb)
    foreignLabels.put("quack-pool", FedKey.pool)
    foreignMeta.setLabels(foreignLabels)
    val foreign = new Pod()
    foreign.setMetadata(foreignMeta)
    server.getClient.pods.inNamespace("default").resource(foreign).create()

    backend.stop(FedKey, "quack-fed-gc-2").unsafeRunSync()
    server.getClient.secrets.inNamespace("default").withName(FedName).get() shouldBe null

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

    backend.stop(FedKey, "quack-fed-only-1").unsafeRunSync()

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

    backend.stop(FedKey, "quack-fed-a").unsafeRunSync()

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

    // The pod reads it as the `objectStoreSql` file of the secrets volume, never as an env var.
    val pod       = server.getClient.pods.inNamespace("default").withName(node.podName.get).get()
    val container = pod.getSpec.getContainers.get(0)
    container.getEnv.asScala.map(_.getName) should not contain "objectStoreSql"
    SecretsVolumeView.files(pod).get("objectStoreSql") shouldBe
      Some((objectStoreSecretName("quack-store-1"), KubernetesQuackBackend.ObjectStoreSecretKey))

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

    backend.stop(FedKey, "quack-store-2").unsafeRunSync()

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

  "token persistence" should "create a per-pod Secret and mount it as the QOD_NODE_TOKEN file" in:
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

    // The token is the QOD_NODE_TOKEN file of the read-only secrets volume: neither in the pod
    // manifest nor in the node's process environment.
    val pod       = server.getClient.pods.inNamespace("default").withName(node.podName.get).get()
    val container = pod.getSpec.getContainers.get(0)
    container.getEnv.asScala.map(_.getName) should not contain "QOD_NODE_TOKEN"
    SecretsVolumeView.files(pod).get("QOD_NODE_TOKEN") shouldBe
      Some((tokenSecretName("quack-tok-1"), KubernetesQuackBackend.TokenSecretKey))
    val volume = pod.getSpec.getVolumes.asScala
      .find(_.getName == KubernetesQuackBackend.SecretsVolumeName)
      .value
    volume.getProjected.getDefaultMode shouldBe Integer.parseInt("400", 8)
    val mount = container.getVolumeMounts.asScala
      .find(_.getName == KubernetesQuackBackend.SecretsVolumeName)
      .value
    mount.getMountPath shouldBe KubernetesQuackBackend.SecretsMountPath
    mount.getReadOnly shouldBe true
    container.getEnv.asScala.find(_.getName == "QOD_SECRETS_DIR").value.getValue shouldBe
      KubernetesQuackBackend.SecretsMountPath

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

    backend.stop(FedKey, "quack-tok-2").unsafeRunSync()

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
      readPodReady = _ => false,
      nodeKeys = ai.starlake.quack.edge.authz.NodeKeys.ephemeral(),
      nodeAuthzLimits = ai.starlake.quack.NodeAuthzLimitsConfig(),
      nodeFiles = ai.starlake.quack.NodeFilesConfig()
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
      readPodReady = _ => false,
      nodeKeys = ai.starlake.quack.edge.authz.NodeKeys.ephemeral(),
      nodeAuthzLimits = ai.starlake.quack.NodeAuthzLimitsConfig(),
      nodeFiles = ai.starlake.quack.NodeFilesConfig()
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

  // ---------- 409-on-create retry + created-only rollback ----------

  it should "retry pod create while a same-name pod is terminating" in:
    val backend = new KubernetesQuackBackend(
      client = server.getClient,
      namespace = "default",
      image = "starlakeai/quack:test",
      quackPort = 8080,
      podLabel = "managed-by=quack-on-demand",
      startupTimeoutSec = 5,
      readPodReady = _ => true,
      stopTimeoutSec = 5,
      nodeKeys = ai.starlake.quack.edge.authz.NodeKeys.ephemeral(),
      nodeAuthzLimits = ai.starlake.quack.NodeAuthzLimitsConfig(),
      nodeFiles = ai.starlake.quack.NodeFilesConfig()
    )
    val key  = PoolKey("acme", "acme_default", "sales")
    val spec =
      NodeSpec(key, "quack-race-1", Role.Dual, metastore = Map("pgHost" -> "pg"), s3 = Map.empty)
    // Incumbent occupies the deterministic name.
    backend.start(spec).unsafeRunSync()
    // Simulate the apiserver clearing the Terminating twin ~1s into the retry poll.
    val reaper = new Thread(() => {
      Thread.sleep(1000)
      server.getClient.pods.inNamespace("default").withName("quack-race-1").delete()
      ()
    })
    reaper.start()
    noException should be thrownBy backend.start(spec).unsafeRunSync()
    reaper.join()

  it should "not delete a pod it did not create when start fails" in:
    val backend = new KubernetesQuackBackend(
      client = server.getClient,
      namespace = "default",
      image = "starlakeai/quack:test",
      quackPort = 8080,
      podLabel = "managed-by=quack-on-demand",
      startupTimeoutSec = 5,
      readPodReady = _ => true,
      stopTimeoutSec = 1,
      nodeKeys = ai.starlake.quack.edge.authz.NodeKeys.ephemeral(),
      nodeAuthzLimits = ai.starlake.quack.NodeAuthzLimitsConfig(),
      nodeFiles = ai.starlake.quack.NodeFilesConfig()
    )
    val key  = PoolKey("acme", "acme_default", "sales")
    val spec =
      NodeSpec(key, "quack-race-2", Role.Dual, metastore = Map("pgHost" -> "pg"), s3 = Map.empty)
    backend.start(spec).unsafeRunSync() // incumbent, stays put
    // Second start: create 409s, incumbent never goes away, retry re-409s -> start fails.
    an[Exception] should be thrownBy backend.start(spec).unsafeRunSync()
    // The incumbent pod must survive - cleanup only touches what THIS call created.
    server.getClient.pods.inNamespace("default").withName("quack-race-2").get() should not be null

  it should "not delete an incumbent's pre-existing token Secret when a racing start fails" in:
    val backend = new KubernetesQuackBackend(
      client = server.getClient,
      namespace = "default",
      image = "starlakeai/quack:test",
      quackPort = 8080,
      podLabel = "managed-by=quack-on-demand",
      startupTimeoutSec = 5,
      readPodReady = _ => true,
      stopTimeoutSec = 1,
      nodeKeys = ai.starlake.quack.edge.authz.NodeKeys.ephemeral(),
      nodeAuthzLimits = ai.starlake.quack.NodeAuthzLimitsConfig(),
      nodeFiles = ai.starlake.quack.NodeFilesConfig()
    )
    val key  = PoolKey("acme", "acme_default", "sales")
    val spec =
      NodeSpec(key, "quack-race-3", Role.Dual, metastore = Map("pgHost" -> "pg"), s3 = Map.empty)
    backend.start(spec).unsafeRunSync() // incumbent pod, service and token Secret, stays put

    // Overwrite the incumbent's token Secret with a known sentinel value so the final
    // assertion pins "this pre-existing Secret survived", not just "some Secret exists".
    val meta = new ObjectMeta()
    meta.setName(tokenSecretName("quack-race-3"))
    val sentinelData = new java.util.HashMap[String, String]()
    sentinelData.put(KubernetesQuackBackend.TokenSecretKey, "incumbent-sentinel-token")
    val sentinel = new io.fabric8.kubernetes.api.model.Secret()
    sentinel.setMetadata(meta)
    sentinel.setStringData(sentinelData)
    server.getClient.secrets.inNamespace("default").resource(sentinel).createOr(r => r.update())

    // Second start: create 409s, incumbent never goes away, retry re-409s -> start fails.
    an[Exception] should be thrownBy backend.start(spec).unsafeRunSync()

    // The incumbent's token Secret must survive - ensureTokenSecret's createOr overwrote it
    // in place during the failed attempt, but it pre-existed this call, so it must never be
    // in the "created" gate and cleanup must leave it alone. Its VALUE must also be restored
    // to the incumbent's own token: cleanup captures it before the overwrite and puts it back,
    // so a later discoverExisting (e.g. after a manager restart) keeps matching the incumbent
    // pod's still-live env instead of 401-ing on the failed attempt's rotated token.
    val survivingSecret = server.getClient.secrets
      .inNamespace("default")
      .withName(tokenSecretName("quack-race-3"))
      .get()
    survivingSecret should not be null
    readTokenPayload(survivingSecret) shouldBe "incumbent-sentinel-token"

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
      podTemplateEnabled = podTemplateEnabled,
      nodeKeys = ai.starlake.quack.edge.authz.NodeKeys.ephemeral(),
      nodeAuthzLimits = ai.starlake.quack.NodeAuthzLimitsConfig(),
      nodeFiles = ai.starlake.quack.NodeFilesConfig()
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
    quack.getEnv.asScala.map(_.getName) should contain("QOD_SECRETS_DIR")
    SecretsVolumeView.files(pod).keySet should contain("QOD_NODE_TOKEN")
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
    envNames should contain("QOD_SECRETS_DIR")

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
      serviceType = "NodePort",
      nodeKeys = ai.starlake.quack.edge.authz.NodeKeys.ephemeral(),
      nodeAuthzLimits = ai.starlake.quack.NodeAuthzLimitsConfig(),
      nodeFiles = ai.starlake.quack.NodeFilesConfig()
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

  it should "never mount the Kubernetes API token into a node pod, whatever a template says" in:
    val backend = new KubernetesQuackBackend(
      client = server.getClient,
      namespace = "default",
      image = "starlakeai/quack:test",
      quackPort = 8080,
      podLabel = "managed-by=quack-on-demand",
      startupTimeoutSec = 5,
      readPodReady = _ => true,
      podTemplateEnabled = true,
      serviceAccount = Some("quack-nodes"),
      nodeKeys = ai.starlake.quack.edge.authz.NodeKeys.ephemeral(),
      nodeAuthzLimits = ai.starlake.quack.NodeAuthzLimitsConfig(),
      nodeFiles = ai.starlake.quack.NodeFilesConfig()
    )
    val key      = PoolKey("acme", "acme_default", "sales")
    val template = "apiVersion: v1\nkind: Pod\nspec:\n  automountServiceAccountToken: true\n"
    for (id, yaml) <- List("quack-notoken1" -> None, "quack-notoken2" -> Some(template)) do
      val spec = NodeSpec(key, id, Role.Dual, metastore = Map.empty, s3 = Map.empty, podTemplateYaml = yaml)
      val node = backend.start(spec).unsafeRunSync()
      val pod  = server.getClient.pods.inNamespace("default").withName(node.podName.get).get()
      withClue(s"$id: ")(pod.getSpec.getAutomountServiceAccountToken shouldBe false)

  it should "liveNodeIds lists only the pool's pods" in:
    val backend = new KubernetesQuackBackend(
      client = server.getClient,
      namespace = "default",
      image = "starlakeai/quack:test",
      quackPort = 8080,
      podLabel = "managed-by=quack-on-demand",
      startupTimeoutSec = 5,
      readPodReady = _ => true,
      nodeKeys = ai.starlake.quack.edge.authz.NodeKeys.ephemeral(),
      nodeAuthzLimits = ai.starlake.quack.NodeAuthzLimitsConfig(),
      nodeFiles = ai.starlake.quack.NodeFilesConfig()
    )
    val keyA = PoolKey("acme", "acme_default", "sales")
    val keyB = PoolKey("acme", "acme_default", "bi")
    backend
      .start(NodeSpec(keyA, "quack-live-a1", Role.Dual, metastore = Map.empty, s3 = Map.empty))
      .unsafeRunSync()
    backend
      .start(NodeSpec(keyB, "quack-live-b1", Role.Dual, metastore = Map.empty, s3 = Map.empty))
      .unsafeRunSync()
    backend.liveNodeIds(keyA).unsafeRunSync() shouldBe Some(Set("quack-live-a1"))

  it should "leave serviceAccountName unset and default the Service type to ClusterIP" in:
    val backend = new KubernetesQuackBackend(
      client = server.getClient,
      namespace = "default",
      image = "starlakeai/quack:test",
      quackPort = 8080,
      podLabel = "managed-by=quack-on-demand",
      startupTimeoutSec = 5,
      readPodReady = _ => true,
      nodeKeys = ai.starlake.quack.edge.authz.NodeKeys.ephemeral(),
      nodeAuthzLimits = ai.starlake.quack.NodeAuthzLimitsConfig(),
      nodeFiles = ai.starlake.quack.NodeFilesConfig()
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

  // ---------- pool labels a real API server accepts ----------

  /** The rule a real API server names in its 422. The mock here does not validate labels, so this
    * spec reads what was written against it.
    */
  private val LabelRule = "(([A-Za-z0-9][-A-Za-z0-9_.]*)?[A-Za-z0-9])?".r

  private val MergeKey     = PoolKey("acme", "acme_default", "__merge")
  private val MergeFedName = "qod-fedsql--quack--acme--acme-default----merge-6537ded44a1a6782"
  private val MergeEnvName = "qod-nodeenv--quack--acme--acme-default----merge-6537ded44a1a6782"

  /** A node of `key` with both per-pool Secrets. */
  private def nodeOf(key: PoolKey, nodeId: String) = NodeSpec(
    key,
    nodeId,
    Role.Dual,
    metastore = Map("pgPassword" -> "p"),
    s3 = Map.empty,
    extraSetupSql = FedSql
  )

  private def podLabelsOf(nodeId: String): Map[String, String] =
    server.getClient.pods
      .inNamespace("default")
      .withName(nodeId)
      .get()
      .getMetadata
      .getLabels
      .asScala
      .toMap

  private def secretLabelsOf(name: String): Map[String, String] =
    server.getClient.secrets
      .inNamespace("default")
      .withName(name)
      .get()
      .getMetadata
      .getLabels
      .asScala
      .toMap

  "pool labels" should "be written as the rule accepts, on the pod and on each per-pool Secret" in:
    fedBackend.start(nodeOf(MergeKey, "merge-n1")).unsafeRunSync()
    val labels = podLabelsOf("merge-n1")
    labels("quack-tenant") shouldBe "acme"
    labels("quack-tenant-db") shouldBe "acme_default"
    labels("quack-pool") shouldBe "qod.__merge.qod"
    labels.values.foreach(v => withClue(v)(LabelRule.matches(v) shouldBe true))
    List(MergeFedName, MergeEnvName).foreach { name =>
      val onSecret = secretLabelsOf(name)
      onSecret("quack-pool") shouldBe "qod.__merge.qod"
      onSecret.values.foreach(v => withClue(v)(LabelRule.matches(v) shouldBe true))
    }

  it should "cover every name of the key, a user's too" in:
    val key = PoolKey("_t", "_t_db_", "x_")
    fedBackend.start(nodeOf(key, "odd-n1")).unsafeRunSync()
    val labels = podLabelsOf("odd-n1")
    (labels("quack-tenant"), labels("quack-tenant-db"), labels("quack-pool")) shouldBe
      (("qod._t.qod", "qod._t_db_.qod", "qod.x_.qod"))

  it should "leave a name the rule accepts as it is, so a running pod and its selector stay" in:
    fedBackend.start(nodeOf(FedKey, "sales-n1")).unsafeRunSync()
    val labels = podLabelsOf("sales-n1")
    (labels("quack-tenant"), labels("quack-tenant-db"), labels("quack-pool")) shouldBe
      (("acme", "acme_default", "sales"))
    val selector = server.getClient.services
      .inNamespace("default")
      .withName("sales-n1")
      .get()
      .getSpec
      .getSelector
      .asScala
      .toMap
    (selector("quack-tenant"), selector("quack-tenant-db"), selector("quack-pool")) shouldBe
      (("acme", "acme_default", "sales"))

  it should "select on what is written: the Service, liveNodeIds and the per-pool Secrets' GC" in:
    val backend = fedBackend
    backend.start(nodeOf(MergeKey, "merge-n1")).unsafeRunSync()
    backend.start(nodeOf(MergeKey, "merge-n2")).unsafeRunSync()
    val labels   = podLabelsOf("merge-n1")
    val selector = server.getClient.services
      .inNamespace("default")
      .withName("merge-n1")
      .get()
      .getSpec
      .getSelector
      .asScala
      .toMap
    selector.keySet should contain("quack-pool")
    selector.foreach((k, v) => labels.get(k) shouldBe Some(v))
    backend.liveNodeIds(MergeKey).unsafeRunSync() shouldBe Some(Set("merge-n1", "merge-n2"))
    // A sibling keeps the pool's Secrets: the GC finds it by what it wrote.
    backend.stop(MergeKey, "merge-n1").unsafeRunSync()
    server.getClient.secrets.inNamespace("default").withName(MergeFedName).get() should not be null
    server.getClient.secrets.inNamespace("default").withName(MergeEnvName).get() should not be null
    backend.liveNodeIds(MergeKey).unsafeRunSync() shouldBe Some(Set("merge-n2"))
    backend.stop(MergeKey, "merge-n2").unsafeRunSync()
    server.getClient.secrets.inNamespace("default").withName(MergeFedName).get() shouldBe null
    server.getClient.secrets.inNamespace("default").withName(MergeEnvName).get() shouldBe null

  it should "name each per-pool Secret as the API accepts, for a pool the rule refuses too" in:
    // A real API server's rule for a Secret's name. No two pools share one: `x_` is not `x_0`.
    val secretName = "[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*"
    val backend    = fedBackend
    val pools      = List("x_", "x_0", "x__", "_x_", "__merge", "sales", "a.b", "1a")
    pools.zipWithIndex.foreach { (pool, i) =>
      backend.start(nodeOf(PoolKey("acme", "acme_default", pool), s"named-n$i")).unsafeRunSync()
    }
    val names = server.getClient.secrets
      .inNamespace("default")
      .list()
      .getItems
      .asScala
      .toList
      .map(_.getMetadata.getName)
    names.foreach(name => withClue(name)(name should fullyMatch regex secretName))
    names.count(_.startsWith("qod-fedsql--")) shouldBe pools.size
    names.count(_.startsWith("qod-nodeenv--")) shouldBe pools.size
    // A pool whose name a label value cannot hold has Secret names the API accepts all the same.
    for
      pool   <- List("my pool", "a/b")
      prefix <- List(
        KubernetesQuackBackend.NodeEnvSecretPrefix,
        KubernetesQuackBackend.FederationSecretPrefix
      )
    do
      val name =
        KubernetesQuackBackend.perPoolSecretName(prefix, PoolKey("acme", "acme_default", pool))
      withClue(name)(name should fullyMatch regex secretName)
    // A name that holds to the rule reads as it is.
    names should contain("qod-fedsql--acme--acme-default--sales")
    names should contain("qod-fedsql--acme--acme-default--x-0")
    // One that does not takes a hash, since `--` could not split it.
    names should contain(MergeEnvName)
    // The pod draws from the Secret written, and the last pod's stop removes it.
    val pod   = server.getClient.pods.inNamespace("default").withName("named-n0").get()
    val trail = SecretsVolumeView.files(pod)("extraSetupSql")._1
    names should contain(trail)
    trail should fullyMatch regex "qod-fedsql--quack--acme--acme-default--x-*-[0-9a-f]{16}"
    backend.stop(PoolKey("acme", "acme_default", "x_"), "named-n0").unsafeRunSync()
    server.getClient.secrets.inNamespace("default").withName(trail).get() shouldBe null

  it should "read back from discovery the pool that was written" in:
    val keys = List(
      MergeKey,
      PoolKey("acme", "acme_default", "__maint"),
      PoolKey("acme", "acme_default__br_abcd1234", "__br_abcd1234"),
      PoolKey("_t", "_t_db_", "x_"),
      FedKey
    )
    val backend = fedBackend
    keys.zipWithIndex.foreach { (key, i) =>
      backend.start(nodeOf(key, s"disc-n$i")).unsafeRunSync()
    }
    backend.discoverExisting().unsafeRunSync().map(n => (n.nodeId, n.poolKey)).toSet shouldBe
      keys.zipWithIndex.map((key, i) => (s"disc-n$i", key)).toSet

  "a long node id" should "name all a node reaches as the API accepts, and read back" in:
    // A Service name and a label value hold 63 characters, a Secret's name 253. The mock checks
    // neither, so this reads what was written against the rules.
    val serviceName = "[a-z]([-a-z0-9]*[a-z0-9])?"
    val secretName  = "[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*"
    val tenant      = "t" * 61
    val nodes       = List(
      // The longest names there are, and the branch that used to answer 502.
      (PoolKey(tenant, s"${tenant}_d", "p" * 63), Int.MaxValue),
      (PoolKey("wayne_corp", "wayne_corp_sales_2026__br_ab12cd34", "__br_ab12cd34"), 1)
    )
    val backend = fedBackend
    val started = nodes.map { (key, index) =>
      val id = PoolSupervisor.nodeId(key, index)
      backend.start(nodeOf(key, id).copy(objectStoreSql = "CREATE SECRET s")).unsafeRunSync()
      (id, key)
    }
    started.foreach { (id, key) =>
      withClue(id) {
        val service = server.getClient.services.inNamespace("default").withName(id).get()
        service.getMetadata.getName should fullyMatch regex serviceName
        service.getMetadata.getName.length should be <= 63
        val labels = podLabelsOf(id)
        labels("quack-node-id") shouldBe id
        service.getSpec.getSelector.get("quack-node-id") shouldBe id
        labels.values.foreach { v =>
          LabelRule.matches(v) shouldBe true
          v.length should be <= 63
        }
        backend.liveNodeIds(key).unsafeRunSync() shouldBe Some(Set(id))
      }
    }
    val secrets = server.getClient.secrets
      .inNamespace("default")
      .list()
      .getItems
      .asScala
      .toList
      .map(_.getMetadata.getName)
    // Per node a token and an object-store Secret, per pool a federation and a node-env one.
    secrets.size shouldBe 4 * nodes.size
    secrets.foreach { name =>
      withClue(name) {
        name should fullyMatch regex secretName
        name.length should be <= 253
      }
    }
    // A restart finds the same nodes, under the same keys, with their tokens.
    val reborn = fedBackend.discoverExisting().unsafeRunSync()
    reborn.map(n => (n.nodeId, n.poolKey)).toSet shouldBe started.toSet
    reborn.foreach(n => withClue(n.nodeId)(n.token should not be empty))

  it should "give two keys that read alike their own per-pool Secrets" in:
    // Both pods start. Under one Secret's name each would read what the other's start wrote last,
    // which can hold the other pool's values.
    val left    = PoolKey("acme", "acme_" + "x" * 50, "y_z")
    val right   = PoolKey("acme", "acme_" + "x" * 50 + "_y", "z")
    val backend = fedBackend
    val ids     = List(left -> "left", right -> "right").map { (key, who) =>
      val id = PoolSupervisor.nodeId(key, 1)
      backend
        .start(
          nodeOf(key, id)
            .copy(metastore = Map("pgPassword" -> s"$who-pw"), extraSetupSql = s"-- $who-sql")
        )
        .unsafeRunSync()
      (id, who)
    }
    ids.map(_._1).distinct.size shouldBe 2
    val read = ids.map { (id, who) =>
      val files =
        SecretsVolumeView.files(server.getClient.pods.inNamespace("default").withName(id).get())
      val (env, sql)         = (files("pgPassword")._1, files("extraSetupSql")._1)
      def data(name: String) =
        server.getClient.secrets.inNamespace("default").withName(name).get().getStringData.asScala
      withClue(who) {
        data(env)("pgPassword") shouldBe s"$who-pw"
        data(sql)("extraSetupSql") shouldBe s"-- $who-sql"
      }
      (env, sql)
    }
    read.map(_._1).distinct.size shouldBe 2
    read.map(_._2).distinct.size shouldBe 2
    // The last pod of one goes with its own Secrets only.
    backend.stop(left, ids.head._1).unsafeRunSync()
    server.getClient.secrets.inNamespace("default").withName(read.head._1).get() shouldBe null
    server.getClient.secrets.inNamespace("default").withName(read(1)._1).get() should not be null

  it should "read a per-pool Secret's name plainly up to a first node id of 63, then take the hash" in:
    val at63                = PoolKey("acme", "acme_default", "p" * 33)
    val at64                = PoolKey("acme", "acme_default", "p" * 34)
    val backend             = fedBackend
    def names(key: PoolKey) =
      backend.start(nodeOf(key, PoolSupervisor.nodeId(key, 1))).unsafeRunSync()
      val pod =
        server.getClient.pods.inNamespace("default").withName(PoolSupervisor.nodeId(key, 1)).get()
      val files = SecretsVolumeView.files(pod)
      (files("pgPassword")._1, files("extraSetupSql")._1)
    names(at63) shouldBe
      (
        (
          s"qod-nodeenv--acme--acme-default--${"p" * 33}",
          s"qod-fedsql--acme--acme-default--${"p" * 33}"
        )
      )
    names(at64) shouldBe (
      (
        "qod-nodeenv--quack--acme--acme-default--ppppppppppppppppp-aeb52d900b8bc3e6",
        "qod-fedsql--quack--acme--acme-default--ppppppppppppppppp-aeb52d900b8bc3e6"
      )
    )

  "two pools that read alike under one `-`" should "get pods, Services and Secrets of their own" in:
    val pairs = List(
      PoolKey("acme", "acme_sales", "eu_west") -> PoolKey("acme", "acme_sales_eu", "west"),
      PoolKey("x", "x_x_x_a", "b")             -> PoolKey("x_x", "x_x_a", "b")
    )
    val backend = fedBackend
    pairs.foreach { (a, b) =>
      val started = List(a -> "a", b -> "b").map { (key, who) =>
        val id = PoolSupervisor.nodeId(key, 1)
        backend
          .start(nodeOf(key, id).copy(metastore = Map("pgPassword" -> s"$key-pw")))
          .unsafeRunSync()
        id
      }
      started.distinct.size shouldBe 2
      List(a, b).zip(started).foreach { (key, id) =>
        val files =
          SecretsVolumeView.files(server.getClient.pods.inNamespace("default").withName(id).get())
        val env =
          server.getClient.secrets.inNamespace("default").withName(files("pgPassword")._1).get()
        withClue(id) {
          env.getStringData.asScala("pgPassword") shouldBe s"$key-pw"
          server.getClient.services.inNamespace("default").withName(id).get() should not be null
        }
      }
    }

  // ---------- a pod an earlier release started ----------

  /** The per-pool Secrets and the pod of a release before `--`: its id and its Secrets' names in
    * the old form, each Secret holding `who`'s values, and its volume naming them literally, as a
    * pod's spec does for as long as the pod lives.
    */
  private def earlierPod(key: PoolKey, id: String, who: String): Pod =
    val env    = KubernetesQuackBackend.legacyPerPoolSecretName("qod-nodeenv", key)
    val fed    = KubernetesQuackBackend.legacyPerPoolSecretName("qod-fedsql", key)
    val labels = Map(
      "managed-by"      -> "quack-on-demand",
      "quack-tenant"    -> PoolLabelValue.encode(key.tenant),
      "quack-tenant-db" -> PoolLabelValue.encode(key.tenantDb),
      "quack-pool"      -> PoolLabelValue.encode(key.pool)
    )
    earlierSecret(env, labels, "pgPassword"    -> s"$who-pw")
    earlierSecret(fed, labels, "extraSetupSql" -> s"-- $who-sql")
    earlierSecret(
      s"qod-token-$id",
      Map("managed-by" -> "quack-on-demand", "quack-node-id" -> id),
      "QOD_NODE_TOKEN" -> s"$who-token"
    )
    val projected = new io.fabric8.kubernetes.api.model.ProjectedVolumeSource()
    projected.setSources(
      List(s"qod-token-$id" -> "QOD_NODE_TOKEN", env -> "pgPassword", fed -> "extraSetupSql").map {
        (secret, file) =>
          val item = new io.fabric8.kubernetes.api.model.KeyToPath()
          item.setKey(file)
          item.setPath(file)
          val source = new io.fabric8.kubernetes.api.model.SecretProjection()
          source.setName(secret)
          source.setItems(List(item).asJava)
          val projection = new io.fabric8.kubernetes.api.model.VolumeProjection()
          projection.setSecret(source)
          projection
      }.asJava
    )
    val volume = new io.fabric8.kubernetes.api.model.Volume()
    volume.setName(KubernetesQuackBackend.SecretsVolumeName)
    volume.setProjected(projected)
    val spec = new PodSpec()
    spec.setVolumes(List(volume).asJava)
    val container = new Container()
    container.setName("quack")
    spec.setContainers(List(container).asJava)
    val meta = new ObjectMeta()
    meta.setName(id)
    meta.setLabels((labels ++ Map("quack-role" -> "Dual", "quack-node-id" -> id)).asJava)
    val pod = new Pod()
    pod.setMetadata(meta)
    pod.setSpec(spec)
    server.getClient.pods.inNamespace("default").resource(pod).create()

  private def earlierSecret(
      name: String,
      labels: Map[String, String],
      data: (String, String)
  ): Unit =
    val meta = new ObjectMeta()
    meta.setName(name)
    meta.setLabels(labels.asJava)
    val secret = new io.fabric8.kubernetes.api.model.Secret()
    secret.setMetadata(meta)
    secret.setStringData(Map(data).asJava)
    server.getClient.secrets.inNamespace("default").resource(secret).create()
    ()

  private def secretExists(name: String): Boolean =
    server.getClient.secrets.inNamespace("default").withName(name).get() != null

  private val EarlierKey = PoolKey("acme", "acme_lake", "sales")
  private val OldEnv     = "qod-nodeenv-acme-acme-lake-sales"
  private val OldFed     = "qod-fedsql-acme-acme-lake-sales"
  private val NewEnv     = "qod-nodeenv--acme--acme-lake--sales"
  private val NewFed     = "qod-fedsql--acme--acme-lake--sales"
  private val OldId      = "quack-acme-acme-lake-sales-1"

  "an upgrade" should "read back both forms from discovery" in:
    earlierPod(EarlierKey, OldId, "old")
    val backend = fedBackend
    val fresh   = PoolSupervisor.nodeId(EarlierKey, 2)
    backend.start(nodeOf(EarlierKey, fresh)).unsafeRunSync()
    val found = fedBackend.discoverExisting().unsafeRunSync()
    found.map(n => (n.nodeId, n.poolKey)).toSet shouldBe
      Set((OldId, EarlierKey), (fresh, EarlierKey))
    found.find(_.nodeId == OldId).get.token shouldBe "old-token"
    fedBackend.liveNodeIds(EarlierKey).unsafeRunSync() shouldBe Some(Set(OldId, fresh))

  it should "leave a running pod its Secrets, while a new slot of its pool gets new ones" in:
    earlierPod(EarlierKey, OldId, "old")
    val backend = fedBackend
    val fresh   = PoolSupervisor.nodeId(EarlierKey, 2)
    backend
      .start(
        nodeOf(EarlierKey, fresh)
          .copy(metastore = Map("pgPassword" -> "new-pw"), extraSetupSql = "-- new-sql")
      )
      .unsafeRunSync()
    fresh shouldBe "quack--acme--acme-lake--sales--2"
    // The old pod's names still hold its values: its next container start reads them.
    def data(name: String) =
      server.getClient.secrets.inNamespace("default").withName(name).get().getStringData.asScala
    data(OldEnv)("pgPassword") shouldBe "old-pw"
    data(OldFed)("extraSetupSql") shouldBe "-- old-sql"
    // The new pod reads its own.
    val files =
      SecretsVolumeView.files(server.getClient.pods.inNamespace("default").withName(fresh).get())
    files("pgPassword")._1 shouldBe NewEnv
    files("extraSetupSql")._1 shouldBe NewFed
    data(NewEnv)("pgPassword") shouldBe "new-pw"
    // A stop of the new pod leaves the old one what it reads, and the pool's new Secrets too.
    backend.stop(EarlierKey, fresh).unsafeRunSync()
    List(OldEnv, OldFed, NewEnv, NewFed).foreach(n => withClue(n)(secretExists(n) shouldBe true))

  it should "start a stored id's next pod on Secrets it finds, and collect the old ones then" in:
    earlierPod(EarlierKey, OldId, "old")
    // The pod is gone out of band: the manager starts its stored id again.
    server.getClient.pods.inNamespace("default").withName(OldId).delete()
    val backend = fedBackend
    backend.start(nodeOf(EarlierKey, OldId)).unsafeRunSync()
    val files =
      SecretsVolumeView.files(server.getClient.pods.inNamespace("default").withName(OldId).get())
    files("pgPassword")._1 shouldBe NewEnv
    files("extraSetupSql")._1 shouldBe NewFed
    List(NewEnv, NewFed).foreach(n => withClue(n)(secretExists(n) shouldBe true))
    // No pod reads them any more.
    List(OldEnv, OldFed).foreach(n => withClue(n)(secretExists(n) shouldBe false))

  it should "collect the old names when the last pod reading them stops, the pool running on" in:
    earlierPod(EarlierKey, OldId, "old")
    val backend = fedBackend
    val fresh   = PoolSupervisor.nodeId(EarlierKey, 2)
    backend.start(nodeOf(EarlierKey, fresh)).unsafeRunSync()
    backend.stop(EarlierKey, OldId).unsafeRunSync()
    List(OldEnv, OldFed).foreach(n => withClue(n)(secretExists(n) shouldBe false))
    List(NewEnv, NewFed).foreach(n => withClue(n)(secretExists(n) shouldBe true))

  it should "collect both forms when the pool's last pod stops" in:
    earlierPod(EarlierKey, OldId, "old")
    val backend = fedBackend
    val fresh   = PoolSupervisor.nodeId(EarlierKey, 2)
    backend.start(nodeOf(EarlierKey, fresh)).unsafeRunSync()
    backend.stop(EarlierKey, fresh).unsafeRunSync()
    backend.stop(EarlierKey, OldId).unsafeRunSync()
    List(OldEnv, OldFed, NewEnv, NewFed).foreach(n => withClue(n)(secretExists(n) shouldBe false))

  it should "keep an old name another pool's pod still reads: two pools could share one" in:
    val a  = PoolKey("acme", "acme_sales", "eu_west")
    val b  = PoolKey("acme", "acme_sales_eu", "west")
    val id = "quack-acme-acme-sales-eu-west-1"
    earlierPod(a, id, "a")
    // Pool b's pod, a higher slot of the same old name, reading the same Secrets.
    val second = server.getClient.pods.inNamespace("default").withName(id).get()
    val copy   = new Pod()
    val meta   = new ObjectMeta()
    meta.setName("quack-acme-acme-sales-eu-west-2")
    meta.setLabels(
      Map(
        "managed-by"      -> "quack-on-demand",
        "quack-tenant"    -> "acme",
        "quack-tenant-db" -> "acme_sales_eu",
        "quack-pool"      -> "west",
        "quack-role"      -> "Dual"
      ).asJava
    )
    copy.setMetadata(meta)
    copy.setSpec(second.getSpec)
    server.getClient.pods.inNamespace("default").resource(copy).create()
    val shared = "qod-nodeenv-acme-acme-sales-eu-west"
    fedBackend.stop(a, id).unsafeRunSync()
    secretExists(shared) shouldBe true
    fedBackend.stop(b, "quack-acme-acme-sales-eu-west-2").unsafeRunSync()
    secretExists(shared) shouldBe false

  it should "collect at boot an old-named Secret no pod reads, and nothing else" in:
    val labels = Map(
      "managed-by"      -> "quack-on-demand",
      "quack-tenant"    -> "acme",
      "quack-tenant-db" -> "acme_gone",
      "quack-pool"      -> "bi"
    )
    earlierSecret("qod-nodeenv-acme-acme-gone-bi", labels, "pgPassword"   -> "left-behind")
    earlierSecret("qod-fedsql-acme-acme-gone-bi", labels, "extraSetupSql" -> "-- left behind")
    // Not of the old form, or not ours: left alone.
    earlierSecret("qod-nodeenv--acme--acme-gone--bi", labels, "pgPassword"        -> "new")
    earlierSecret("qod-token-quack-acme-acme-gone-bi-1", labels, "QOD_NODE_TOKEN" -> "t")
    earlierSecret("qod-nodeenv-acme-acme-gone-bi-backup", labels, "pgPassword" -> "an operator's")
    // An old-named Secret a pod reads, by the volume or by an older pod's secretKeyRef.
    earlierPod(EarlierKey, OldId, "old")
    val keyRefLabels = labels ++ Map("quack-tenant-db" -> "acme_ref")
    earlierSecret("qod-nodeenv-acme-acme-ref-bi", keyRefLabels, "pgPassword" -> "by-env")
    val selector     = new io.fabric8.kubernetes.api.model.SecretKeySelector()
    selector.setName("qod-nodeenv-acme-acme-ref-bi")
    selector.setKey("pgPassword")
    val source = new io.fabric8.kubernetes.api.model.EnvVarSource()
    source.setSecretKeyRef(selector)
    val envVar = new io.fabric8.kubernetes.api.model.EnvVar()
    envVar.setName("pgPassword")
    envVar.setValueFrom(source)
    val container = new Container()
    container.setName("quack")
    container.setEnv(List(envVar).asJava)
    val spec = new PodSpec()
    spec.setContainers(List(container).asJava)
    val meta = new ObjectMeta()
    meta.setName("quack-acme-acme-ref-bi-1")
    meta.setLabels(keyRefLabels.asJava)
    val pod = new Pod()
    pod.setMetadata(meta)
    pod.setSpec(spec)
    server.getClient.pods.inNamespace("default").resource(pod).create()

    fedBackend.discoverExisting().unsafeRunSync()

    List("qod-nodeenv-acme-acme-gone-bi", "qod-fedsql-acme-acme-gone-bi").foreach { n =>
      withClue(n)(secretExists(n) shouldBe false)
    }
    List(
      "qod-nodeenv--acme--acme-gone--bi",
      "qod-token-quack-acme-acme-gone-bi-1",
      "qod-nodeenv-acme-acme-gone-bi-backup",
      OldEnv,
      OldFed,
      "qod-nodeenv-acme-acme-ref-bi"
    ).foreach(n => withClue(n)(secretExists(n) shouldBe true))
