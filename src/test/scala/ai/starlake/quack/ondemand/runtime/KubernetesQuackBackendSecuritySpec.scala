package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.model.{NodeSpec, PoolKey, Role, TenantDb}
import cats.effect.unsafe.implicits.global
import io.fabric8.kubernetes.api.model.EnvVar
import io.fabric8.kubernetes.client.server.mock.KubernetesServer
import org.scalatest.{BeforeAndAfterEach, OptionValues}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._

/** Pod-level and container-level `securityContext` defaults, and the template-merge contract:
  * template-provided securityContext fields win field-by-field, everything the template leaves null
  * falls back to the default posture. See docs/superpowers/sdd/task-4-brief.md.
  */
class KubernetesQuackBackendSecuritySpec
    extends AnyFlatSpec
    with Matchers
    with BeforeAndAfterEach
    with OptionValues:

  private val server = new KubernetesServer(false, true)

  override def beforeEach(): Unit = server.before()
  override def afterEach(): Unit  = server.after()

  private val ns    = "default"
  private val image = "starlakeai/quack:test"

  private def makeBackend(podTemplateEnabled: Boolean = false): KubernetesQuackBackend =
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

  private val baseSpec = NodeSpec(
    PoolKey("acme", "acme_tpch", "bi"),
    "quack-sec-1",
    Role.Dual,
    metastore = Map.empty,
    s3 = Map.empty
  )

  "start" should "apply the default security posture to node pods" in:
    val backend = makeBackend()
    backend.start(baseSpec).unsafeRunSync()
    val pod = server.getClient.pods.inNamespace(ns).withName(baseSpec.nodeId).get()

    pod.getSpec.getSecurityContext.getRunAsNonRoot shouldBe true
    pod.getSpec.getSecurityContext.getRunAsUser shouldBe 1000L
    pod.getSpec.getSecurityContext.getFsGroup shouldBe 1000L
    pod.getSpec.getSecurityContext.getSeccompProfile.getType shouldBe "RuntimeDefault"

    val c = pod.getSpec.getContainers.asScala.find(_.getName == "quack").get
    c.getSecurityContext.getAllowPrivilegeEscalation shouldBe false
    c.getSecurityContext.getCapabilities.getDrop.asScala should contain("ALL")
    c.getSecurityContext.getReadOnlyRootFilesystem shouldBe true

    c.getVolumeMounts.asScala.map(_.getMountPath) should contain allOf ("/tmp", "/duckdb-tmp")
    pod.getSpec.getVolumes.asScala.map(_.getName) should contain allOf ("tmp", "duckdb-tmp")

  it should "honor a custom runAsUser on the backend" in:
    val backend = new KubernetesQuackBackend(
      client = server.getClient,
      namespace = ns,
      image = image,
      quackPort = 8080,
      podLabel = "managed-by=quack-on-demand",
      startupTimeoutSec = 5,
      readPodReady = _ => true,
      runAsUser = 5000L
    )
    backend.start(baseSpec.copy(nodeId = "quack-sec-runasuser")).unsafeRunSync()
    val pod = server.getClient.pods.inNamespace(ns).withName("quack-sec-runasuser").get()
    pod.getSpec.getSecurityContext.getRunAsUser shouldBe 5000L
    pod.getSpec.getSecurityContext.getFsGroup shouldBe 5000L

  "pod template overlay" should "let template securityContext fields win field-by-field" in:
    val tmpl =
      """apiVersion: v1
        |kind: Pod
        |spec:
        |  securityContext:
        |    runAsUser: 2000
        |  containers:
        |    - name: quack
        |      image: placeholder
        |      securityContext:
        |        readOnlyRootFilesystem: false""".stripMargin
    val backend = makeBackend(podTemplateEnabled = true)
    backend
      .start(baseSpec.copy(nodeId = "quack-sec-tmpl", podTemplateYaml = Some(tmpl)))
      .unsafeRunSync()
    val pod = server.getClient.pods.inNamespace(ns).withName("quack-sec-tmpl").get()

    // Template-set field wins ...
    pod.getSpec.getSecurityContext.getRunAsUser shouldBe 2000L
    // ... but fields the template left null still get the default posture.
    pod.getSpec.getSecurityContext.getRunAsNonRoot shouldBe true
    pod.getSpec.getSecurityContext.getSeccompProfile.getType shouldBe "RuntimeDefault"

    val c = pod.getSpec.getContainers.asScala.find(_.getName == "quack").get
    // Template-set field wins ...
    c.getSecurityContext.getReadOnlyRootFilesystem shouldBe false
    // ... but fields the template left null still get the default posture.
    c.getSecurityContext.getAllowPrivilegeEscalation shouldBe false
    c.getSecurityContext.getCapabilities.getDrop.asScala should contain("ALL")

    // emptyDir volumes are still added when the template doesn't declare them.
    c.getVolumeMounts.asScala.map(_.getMountPath) should contain allOf ("/tmp", "/duckdb-tmp")

  it should "not duplicate emptyDir volumes already declared by the template" in:
    val tmpl =
      """apiVersion: v1
        |kind: Pod
        |spec:
        |  volumes:
        |    - name: tmp
        |      emptyDir: {}
        |  containers:
        |    - name: quack
        |      image: placeholder
        |      volumeMounts:
        |        - name: tmp
        |          mountPath: /tmp""".stripMargin
    val backend = makeBackend(podTemplateEnabled = true)
    backend
      .start(baseSpec.copy(nodeId = "quack-sec-tmpl-vol", podTemplateYaml = Some(tmpl)))
      .unsafeRunSync()
    val pod = server.getClient.pods.inNamespace(ns).withName("quack-sec-tmpl-vol").get()

    pod.getSpec.getVolumes.asScala.map(_.getName).count(_ == "tmp") shouldBe 1
    pod.getSpec.getVolumes.asScala.map(_.getName) should contain("duckdb-tmp")
    val c = pod.getSpec.getContainers.asScala.find(_.getName == "quack").get
    c.getVolumeMounts.asScala.map(_.getMountPath).count(_ == "/tmp") shouldBe 1

  // --- per-pool node-env Secret (pgPassword / encryptionKey never in the plain pod env) ---

  private val nodeEnvKey = PoolKey("acme", "acme_lake", "bi")

  /** Non-sensitive metastore keys every ducklake node carries, as `effectiveMetastoreFor` would
    * hand them to the backend.
    */
  private val lakeMeta =
    Map("pgHost" -> "pg.internal", "pgPort" -> "5432", "pgUser" -> "qod", "dbName" -> "acme_lake")

  private def nodeEnvSpec(nodeId: String, metastore: Map[String, String]): NodeSpec =
    NodeSpec(nodeEnvKey, nodeId, Role.Dual, metastore = metastore, s3 = Map.empty)

  private def podEnv(nodeId: String): List[EnvVar] =
    server.getClient.pods
      .inNamespace(ns)
      .withName(nodeId)
      .get()
      .getSpec
      .getContainers
      .get(0)
      .getEnv
      .asScala
      .toList

  private def plainEnv(nodeId: String): List[EnvVar] = podEnv(nodeId).filter(_.getValue != null)

  private def secretRefs(nodeId: String): Map[String, (String, String)] =
    podEnv(nodeId)
      .filter(e => e.getValueFrom != null && e.getValueFrom.getSecretKeyRef != null)
      .map(e =>
        e.getName -> (e.getValueFrom.getSecretKeyRef.getName, e.getValueFrom.getSecretKeyRef.getKey)
      )
      .toMap

  /** Every key the named Secret holds, decoded. The mock server keeps `stringData` populated where
    * a real apiserver materialises base64 `data`; tolerate both, like the production reader.
    */
  private def secretPayload(name: String): Option[Map[String, String]] =
    Option(server.getClient.secrets.inNamespace(ns).withName(name).get()).map { s =>
      val fromString = Option(s.getStringData).map(_.asScala.toMap).getOrElse(Map.empty)
      val fromData   = Option(s.getData)
        .map(_.asScala.toMap)
        .getOrElse(Map.empty)
        .view
        .mapValues(b64 =>
          new String(
            java.util.Base64.getDecoder.decode(b64),
            java.nio.charset.StandardCharsets.UTF_8
          )
        )
        .toMap
      fromData ++ fromString
    }

  private val nodeEnvSecretName = "qod-nodeenv-acme-acme-lake-bi"

  "the pod spec" should "not carry pgPassword as a plain env var" in:
    val backend = makeBackend()
    backend
      .start(nodeEnvSpec("quack-nodeenv-1", lakeMeta + ("pgPassword" -> "hunter2")))
      .unsafeRunSync()
    val plain = plainEnv("quack-nodeenv-1")
    plain.map(_.getName) should not contain "pgPassword"
    plain.map(_.getValue) should not contain "hunter2"

  it should "not carry encryptionKey as a plain env var" in:
    val backend = makeBackend()
    backend
      .start(
        nodeEnvSpec("quack-nodeenv-2", Map("dbName" -> "acme_lake", "encryptionKey" -> "c2VjcmV0"))
      )
      .unsafeRunSync()
    val plain = plainEnv("quack-nodeenv-2")
    plain.map(_.getName) should not contain "encryptionKey"
    plain.map(_.getValue) should not contain "c2VjcmV0"

  it should "reference both secrets through secretKeyRef" in:
    val backend = makeBackend()
    backend
      .start(
        nodeEnvSpec(
          "quack-nodeenv-3",
          lakeMeta ++ Map("pgPassword" -> "p", "encryptionKey" -> "k")
        )
      )
      .unsafeRunSync()
    val refs = secretRefs("quack-nodeenv-3")
    refs.get("pgPassword") shouldBe Some((nodeEnvSecretName, "pgPassword"))
    refs.get("encryptionKey") shouldBe Some((nodeEnvSecretName, "encryptionKey"))

  it should "leave non-secret metastore keys as plain env vars" in:
    val backend = makeBackend()
    backend.start(nodeEnvSpec("quack-nodeenv-4", lakeMeta + ("pgPassword" -> "p"))).unsafeRunSync()
    val plain = plainEnv("quack-nodeenv-4").map(_.getName)
    plain should contain("pgHost")
    plain should contain("dbName")

  "the node-env Secret" should "hold exactly the keys the pod references, with their values" in:
    val backend = makeBackend()
    backend
      .start(
        nodeEnvSpec(
          "quack-nodeenv-5",
          lakeMeta ++ Map("pgPassword" -> "hunter2", "encryptionKey" -> "c2VjcmV0")
        )
      )
      .unsafeRunSync()
    val payload = secretPayload(nodeEnvSecretName).value
    payload shouldBe Map("pgPassword" -> "hunter2", "encryptionKey" -> "c2VjcmV0")
    // The pod can only reference keys the Secret actually holds, or kubelet rejects it.
    secretRefs("quack-nodeenv-5")
      .filter(_._2._1 == nodeEnvSecretName)
      .values
      .map(_._2)
      .toSet shouldBe payload.keySet

  it should "not be created at all when the metastore carries neither sensitive key" in:
    val backend = makeBackend()
    backend.start(nodeEnvSpec("quack-nodeenv-6", lakeMeta)).unsafeRunSync()
    secretPayload(nodeEnvSecretName) shouldBe None
    secretRefs("quack-nodeenv-6").values.map(_._1) should not contain nodeEnvSecretName

  it should "be deleted when the last pod of the pool stops" in:
    val backend = makeBackend()
    backend
      .start(nodeEnvSpec("quack-nodeenv-7", lakeMeta + ("pgPassword" -> "p")))
      .unsafeRunSync()
    secretPayload(nodeEnvSecretName) should not be empty
    backend.stop(nodeEnvKey, "quack-nodeenv-7").unsafeRunSync()
    secretPayload(nodeEnvSecretName) shouldBe None

  it should "survive a stop while another pod of the pool is still running" in:
    val backend = makeBackend()
    backend.start(nodeEnvSpec("quack-nodeenv-8a", lakeMeta + ("pgPassword" -> "p"))).unsafeRunSync()
    backend.start(nodeEnvSpec("quack-nodeenv-8b", lakeMeta + ("pgPassword" -> "p"))).unsafeRunSync()
    backend.stop(nodeEnvKey, "quack-nodeenv-8a").unsafeRunSync()
    secretPayload(nodeEnvSecretName) should not be empty

  // No back-compat shape is emitted (owner decision 2026-09-21: operators restart their nodes on
  // upgrade). These two pin that adoption of a pod from an EITHER shape is still lossless for
  // everything the manager actually tracks -- it never reads a credential back off a pod spec.

  "discoverExisting" should "adopt a pod carrying the pre-upgrade plain-env shape" in:
    val backend = makeBackend()
    // A pod as an earlier manager would have written it: pgPassword inlined, no node-env ref.
    backend.start(nodeEnvSpec("quack-nodeenv-old", lakeMeta)).unsafeRunSync()
    val pod    = server.getClient.pods.inNamespace(ns).withName("quack-nodeenv-old").get()
    val quack  = pod.getSpec.getContainers.get(0)
    val legacy = new EnvVar()
    legacy.setName("pgPassword")
    legacy.setValue("hunter2")
    quack.getEnv.add(legacy)
    server.getClient.pods.inNamespace(ns).resource(pod).update()

    val adopted = backend.discoverExisting().unsafeRunSync()
    val node    = adopted.find(_.nodeId == "quack-nodeenv-old").value
    node.poolKey shouldBe nodeEnvKey
    node.token should not be empty

  it should "still adopt a new-shape pod whose node-env Secret has gone missing" in:
    val backend = makeBackend()
    backend
      .start(nodeEnvSpec("quack-nodeenv-orphan", lakeMeta + ("pgPassword" -> "p")))
      .unsafeRunSync()
    server.getClient.secrets.inNamespace(ns).withName(nodeEnvSecretName).delete()

    val adopted = backend.discoverExisting().unsafeRunSync()
    adopted.map(_.nodeId) should contain("quack-nodeenv-orphan")

  "NodeEnvSecretKeys" should "be exactly the model's list, so the strip and the Secret agree" in:
    KubernetesQuackBackend.NodeEnvSecretKeys shouldBe TenantDb.NodeSecretEnvKeys
