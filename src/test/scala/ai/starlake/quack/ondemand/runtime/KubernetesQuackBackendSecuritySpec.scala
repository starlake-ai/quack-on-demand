package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.model.{NodeSpec, PoolKey, Role}
import cats.effect.unsafe.implicits.global
import io.fabric8.kubernetes.client.server.mock.KubernetesServer
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._

/** Pod-level and container-level `securityContext` defaults, and the template-merge contract:
  * template-provided securityContext fields win field-by-field, everything the template leaves null
  * falls back to the default posture. See docs/superpowers/sdd/task-4-brief.md.
  */
class KubernetesQuackBackendSecuritySpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach:

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
