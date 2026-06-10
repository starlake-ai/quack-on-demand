package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.model.{NodeSpec, PoolKey, Role, RunningNode}
import cats.effect.IO
import io.fabric8.kubernetes.api.model._
import io.fabric8.kubernetes.client.KubernetesClient

import java.time.Instant
import scala.jdk.CollectionConverters._

/** Kubernetes-backed quack node runtime.
  *
  * Creates one Pod + one Service per node. Labels carry pool key, role and `maxConcurrent` so an
  * orphan-discovery pass can reconstruct [[ai.starlake.quack.model.RunningNode]] after a manager
  * restart.
  *
  * v1 limitation: the per-node token is held only in-memory. After a manager restart, discovered
  * pods come back with an empty token; a follow-up task could persist it in a K8s Secret named
  * after the pod.
  */
final class KubernetesQuackBackend(
    client: KubernetesClient,
    namespace: String,
    image: String,
    quackPort: Int,
    podLabel: String,
    startupTimeoutSec: Int,
    defaultMetastore: Map[String, String] = Map.empty,
    readPodReady: Pod => Boolean = pod => Option(pod.getStatus).map(_.getPhase).contains("Running"),
    readEnv: String => Option[String] = name => Option(System.getenv(name))
) extends QuackBackend:

  private val (labelKey, labelValue) =
    podLabel.split("=", 2) match
      case Array(k, v) => (k, v)
      case _           => sys.error(s"invalid podLabel: $podLabel")

  private val tokens = scala.collection.concurrent.TrieMap.empty[String, String]

  override val supportsPlacement: Boolean = true

  private def buildLabels(spec: NodeSpec): java.util.Map[String, String] =
    val m = new java.util.HashMap[String, String]()
    m.put(labelKey, labelValue)
    m.put("quack-tenant", spec.poolKey.tenant)
    m.put("quack-tenant-db", spec.poolKey.tenantDb)
    m.put("quack-pool", spec.poolKey.pool)
    m.put("quack-role", spec.role.toString)
    m.put("quack-max-concurrent", spec.maxConcurrent.toString)
    // Per-node identity. Without this, the per-node Service's selector
    // (managed-by + tenant + pool) matches every pod in the pool, so the
    // K8s endpoints for `quack-<pool>-1` end up pointing at all N pods.
    // Manager calls then round-robin across pods with mismatching auth
    // tokens, producing intermittent "Authentication failed".
    m.put("quack-node-id", spec.nodeId)
    m

  private def buildPod(spec: NodeSpec, token: String): Pod =
    val envs = new java.util.ArrayList[EnvVar]()
    // Defaults first, per-pool overrides next, then well-known QUACK_* keys.
    val merged = defaultMetastore ++ spec.metastore
    merged.foreach { case (k, v) =>
      val e = new EnvVar()
      e.setName(k)
      e.setValue(v)
      envs.add(e)
    }
    // Mirror the env-inheritance LocalQuackBackend gets for free (via
    // ProcessBuilder): forward object-store credentials present on the
    // manager process so spawn-quack-node.sh can build CREATE SECRET for
    // s3:// / az:// / gs:// data paths. `defaultMetastore` wins if the
    // operator has explicitly pinned a value there.
    KubernetesQuackBackend.cloudCredEnvVars.foreach { name =>
      if !merged.contains(name) then
        readEnv(name).foreach { v =>
          val e = new EnvVar()
          e.setName(name)
          e.setValue(v)
          envs.add(e)
        }
    }
    // Pass the tenant-db kind so spawn-quack-node.sh selects the right
    // catalog attach path (ducklake / duckdb-file / in-memory).
    val kindEnv = new EnvVar()
    kindEnv.setName("kind")
    kindEnv.setValue(spec.kindWire)
    envs.add(kindEnv)
    // TODO(federation): propagate spec.extraSetupSql as an env var or K8s
    // Secret mount for federation E2E on the K8s backend. Currently only
    // the Local backend receives extraSetupSql; K8s federation is a
    // follow-up task.
    val tokenEnv = new EnvVar()
    tokenEnv.setName("QOD_NODE_TOKEN")
    tokenEnv.setValue(token)
    envs.add(tokenEnv)
    val portEnv = new EnvVar()
    portEnv.setName("QOD_NODE_PORT")
    portEnv.setValue(quackPort.toString)
    envs.add(portEnv)

    val containerPort = new ContainerPort()
    containerPort.setContainerPort(quackPort)

    val container = new Container()
    container.setName("quack")
    container.setImage(image)
    container.setEnv(envs)
    container.setPorts(java.util.List.of(containerPort))

    val podSpec = new PodSpec()
    podSpec.setContainers(java.util.List.of(container))

    // Apply the cohort's placement, if any. Empty selector + empty
    // tolerations leaves the default scheduler in charge -- same as
    // a pool created without explicit cohorts.
    if spec.placement.nodeSelector.nonEmpty then
      podSpec.setNodeSelector(spec.placement.nodeSelector.asJava)
    if spec.placement.tolerations.nonEmpty then
      val tols = new java.util.ArrayList[Toleration]()
      spec.placement.tolerations.foreach { t =>
        val tol = new Toleration()
        tol.setKey(t.key)
        tol.setOperator(t.operator)
        t.value.foreach(tol.setValue)
        t.effect.foreach(tol.setEffect)
        tols.add(tol)
      }
      podSpec.setTolerations(tols)

    val meta = new ObjectMeta()
    meta.setName(spec.nodeId)
    meta.setLabels(buildLabels(spec))

    val pod = new Pod()
    pod.setMetadata(meta)
    pod.setSpec(podSpec)
    pod

  private def buildService(spec: NodeSpec): Service =
    val svcPort = new ServicePort()
    svcPort.setPort(quackPort)
    svcPort.setTargetPort(new IntOrString(quackPort))

    val selector = new java.util.HashMap[String, String]()
    selector.put(labelKey, labelValue)
    selector.put("quack-tenant", spec.poolKey.tenant)
    selector.put("quack-tenant-db", spec.poolKey.tenantDb)
    selector.put("quack-pool", spec.poolKey.pool)
    // Pin the Service to exactly one pod by its unique node id. Without
    // this the selector matches every pod in the pool, producing fan-out
    // routing across pods and intermittent auth-token mismatches at the
    // manager call site.
    selector.put("quack-node-id", spec.nodeId)

    val svcSpec = new ServiceSpec()
    svcSpec.setSelector(selector)
    svcSpec.setPorts(java.util.List.of(svcPort))

    val svcLabels = new java.util.HashMap[String, String]()
    svcLabels.put(labelKey, labelValue)

    val meta = new ObjectMeta()
    meta.setName(spec.nodeId)
    meta.setLabels(svcLabels)

    val svc = new Service()
    svc.setMetadata(meta)
    svc.setSpec(svcSpec)
    svc

  def start(spec: NodeSpec): IO[RunningNode] = IO.blocking {
    val token = LocalQuackBackend.randomToken()
    tokens.put(spec.nodeId, token)

    val pod     = buildPod(spec, token)
    val created = client.pods.inNamespace(namespace).resource(pod).create()
    waitReady(created)

    val svc = buildService(spec)
    client.services.inNamespace(namespace).resource(svc).create()

    RunningNode(
      nodeId = spec.nodeId,
      poolKey = spec.poolKey,
      role = spec.role,
      host = s"${spec.nodeId}.$namespace.svc.cluster.local",
      port = quackPort,
      token = token,
      pid = None,
      podName = Some(spec.nodeId),
      startedAt = Instant.now(),
      maxConcurrent = spec.maxConcurrent
    )
  }

  private def waitReady(p: Pod): Unit =
    val deadline = System.currentTimeMillis() + startupTimeoutSec * 1000L
    var ready    = false
    while !ready && System.currentTimeMillis() < deadline do
      val latest = client.pods.inNamespace(namespace).withName(p.getMetadata.getName).get()
      if latest != null && readPodReady(latest) then ready = true
      else Thread.sleep(500)
    if !ready then sys.error(s"pod ${p.getMetadata.getName} not ready in ${startupTimeoutSec}s")

  def stop(nodeId: String): IO[Unit] = IO.blocking {
    client.services.inNamespace(namespace).withName(nodeId).delete()
    client.pods.inNamespace(namespace).withName(nodeId).delete()
    tokens.remove(nodeId)
    ()
  }

  def isAlive(nodeId: String): Boolean =
    Option(client.pods.inNamespace(namespace).withName(nodeId).get()).exists(readPodReady)

  def discoverExisting(): IO[List[RunningNode]] = IO.blocking {
    val pods = client.pods
      .inNamespace(namespace)
      .withLabel(labelKey, labelValue)
      .list()
      .getItems
      .asScala
      .toList
    pods.flatMap { p =>
      val labels = Option(p.getMetadata.getLabels)
        .map(_.asScala)
        .getOrElse(scala.collection.mutable.Map.empty)
      for
        tenant   <- labels.get("quack-tenant")
        tenantDb <- labels.get("quack-tenant-db")
        pool     <- labels.get("quack-pool")
        roleS    <- labels.get("quack-role")
        role     <- Role.parse(roleS).toOption
      yield RunningNode(
        nodeId = p.getMetadata.getName,
        poolKey = PoolKey(tenant, tenantDb, pool),
        role = role,
        host = s"${p.getMetadata.getName}.$namespace.svc.cluster.local",
        port = quackPort,
        token = tokens.getOrElse(p.getMetadata.getName, ""),
        pid = None,
        podName = Some(p.getMetadata.getName),
        startedAt = Instant.now(),
        maxConcurrent = labels.get("quack-max-concurrent").flatMap(_.toIntOption).getOrElse(0)
      )
    }
  }

  def cleanup(): IO[Unit] = IO.unit

end KubernetesQuackBackend

object KubernetesQuackBackend:
  /** Object-store credential env vars the manager's pod env is allowed to forward into spawned node
    * pods. Mirrors what `LocalQuackBackend` gets for free through `ProcessBuilder` env inheritance.
    * Keep in sync with the `QOD_*` keys read by `scripts/spawn-quack-node.sh`.
    */
  val cloudCredEnvVars: Seq[String] = Seq(
    "QOD_S3_ENDPOINT",
    "QOD_S3_ACCESS_KEY_ID",
    "QOD_S3_SECRET_ACCESS_KEY",
    "QOD_S3_REGION",
    "QOD_S3_URL_STYLE",
    "QOD_S3_USE_SSL",
    "QOD_AZURE_CONNECTION_STRING",
    "QOD_GCS_KEY_ID",
    "QOD_GCS_SECRET"
  )
