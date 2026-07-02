package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.model.{NodeSpec, PoolKey, Role, RunningNode}
import cats.effect.IO
import io.fabric8.kubernetes.api.model._
import io.fabric8.kubernetes.client.KubernetesClient
import org.slf4j.LoggerFactory

import java.time.Instant
import scala.jdk.CollectionConverters._

/** Kubernetes-backed quack node runtime.
  *
  * Creates one Pod + one Service per node. Labels carry pool key, role and `maxConcurrent` so an
  * orphan-discovery pass can reconstruct [[ai.starlake.quack.model.RunningNode]] after a manager
  * restart.
  *
  * Two Secrets accompany each pod:
  *   - per-pool `qod-fedsql-${tenant}-${tenantDb}-${pool}` holds the resolved federation SQL when
  *     `spec.extraSetupSql` is non-empty; all pods of the pool share it.
  *   - per-pod `qod-token-${nodeId}` holds the bearer token the manager uses to call this specific
  *     pod's `/quack` endpoint. The token survives manager restart -- [[discoverExisting]] reads it
  *     back from the Secret to repopulate the in-memory cache, so adopted pods stop 401-ing after a
  *     restart.
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

  private val logger = LoggerFactory.getLogger(getClass)

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
    // Federation: when the pool has any extraSetupSql, point the pod's
    // `extraSetupSql` env var at the per-pool Secret instead of inlining
    // the value. Same env var name as LocalQuackBackend's
    // env.put("extraSetupSql", ...) so spawn-quack-node.sh:200 reads it
    // identically. Putting it in a Secret keeps the (potentially long,
    // potentially credential-bearing) SQL out of `kubectl describe pod`
    // output and out of the pod object's etcd record. The Secret itself
    // is created in start() by ensureFederationSecret.
    if spec.extraSetupSql.nonEmpty then
      val fedEnv = new EnvVar()
      fedEnv.setName("extraSetupSql")
      val source = new EnvVarSource()
      val ref    = new SecretKeySelector()
      ref.setName(secretNameFor(spec.poolKey))
      ref.setKey(KubernetesQuackBackend.FederationSecretKey)
      source.setSecretKeyRef(ref)
      fedEnv.setValueFrom(source)
      envs.add(fedEnv)
    // Token is materialised from the per-pod Secret instead of inlined, so
    // (a) kubectl describe pod doesn't expose the bearer string and
    // (b) the value is recoverable after a manager restart via
    // discoverExisting. ensureTokenSecret() must have created the Secret
    // BEFORE pod create or kubelet will reject the pod with
    // CreateContainerConfigError.
    val tokenEnv = new EnvVar()
    tokenEnv.setName("QOD_NODE_TOKEN")
    val tokenSource = new EnvVarSource()
    val tokenRef    = new SecretKeySelector()
    tokenRef.setName(tokenSecretNameFor(spec.nodeId))
    tokenRef.setKey(KubernetesQuackBackend.TokenSecretKey)
    tokenSource.setSecretKeyRef(tokenRef)
    tokenEnv.setValueFrom(tokenSource)
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

  def start(spec: NodeSpec): IO[RunningNode] =
    val run = IO.blocking {
      val token = LocalQuackBackend.randomToken()
      tokens.put(spec.nodeId, token)

      // Both Secrets must exist before pod create -- kubelet rejects a pod
      // whose env.valueFrom references a missing Secret. ensureTokenSecret
      // also makes the bearer string recoverable after manager restart.
      ensureTokenSecret(spec.nodeId, token)
      if spec.extraSetupSql.nonEmpty then ensureFederationSecret(spec.poolKey, spec.extraSetupSql)

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
    // If anything after the first resource is created fails (waitReady
    // timeout, API error) or the spawn is cancelled, roll back the pod,
    // Service and per-pod token Secret this call created before the error
    // propagates. Without this the orphaned pod keeps the deterministic
    // nodeId, and the next reconcile respawn hits an already-exists
    // conflict on pod create -- the pool never recovers.
    run
      .onError { case e => cleanupPartialStart(spec, e.toString) }
      .onCancel(cleanupPartialStart(spec, "start cancelled"))

  private def waitReady(p: Pod): Unit =
    // fabric8's waitUntilCondition sits on a watch + readiness predicate
    // and returns as soon as the predicate flips. Shares `readPodReady`
    // with `isAlive` so the readiness contract is consistent.
    val name = p.getMetadata.getName
    try
      client.pods
        .inNamespace(namespace)
        .withName(name)
        .waitUntilCondition(
          pod => pod != null && readPodReady(pod),
          startupTimeoutSec.toLong,
          java.util.concurrent.TimeUnit.SECONDS
        )
    catch
      case _: io.fabric8.kubernetes.client.KubernetesClientTimeoutException =>
        sys.error(s"pod $name not ready in ${startupTimeoutSec}s")

  /** Best-effort rollback of the per-node resources a failed [[start]] created for `spec`.
    *
    * Deletes the Service, the Pod and the per-pod token Secret by the exact names this call created
    * (all derived from `spec.nodeId`, matching [[buildService]] / [[buildPod]] /
    * [[tokenSecretNameFor]]), so a subsequent reconcile respawns cleanly instead of colliding with
    * an orphaned pod on the deterministic nodeId. Every delete is swallowed and logged so cleanup
    * can never mask the original spawn failure.
    *
    * The per-POOL federation Secret (`qod-fedsql-...`) is intentionally left in place: it is shared
    * by every pod of the pool, so deleting it on one node's failure could break sibling pods that
    * reference it. It is GC'd by [[stop]] when the pool's last pod goes away.
    */
  private def cleanupPartialStart(spec: NodeSpec, reason: String): IO[Unit] = IO.blocking {
    logger.warn(
      s"start() for pod ${spec.nodeId} failed ($reason); rolling back pod/service/token secret"
    )
    deleteQuietly("service", spec.nodeId) {
      client.services.inNamespace(namespace).withName(spec.nodeId).delete()
    }
    deleteQuietly("pod", spec.nodeId) {
      client.pods.inNamespace(namespace).withName(spec.nodeId).delete()
    }
    deleteQuietly("token secret", tokenSecretNameFor(spec.nodeId)) {
      client.secrets.inNamespace(namespace).withName(tokenSecretNameFor(spec.nodeId)).delete()
    }
    tokens.remove(spec.nodeId)
    ()
  }

  /** Run a delete, swallowing and logging any failure so partial-start cleanup never throws. */
  private def deleteQuietly(kind: String, name: String)(op: => Any): Unit =
    try
      op
      ()
    catch
      case e: Throwable =>
        logger.warn(s"partial-start cleanup: failed to delete $kind $name: ${e.getMessage}")

  def stop(nodeId: String): IO[Unit] = IO.blocking {
    // Capture the pod's pool labels before delete -- after the API call
    // the pod object may be gone or in a Terminating state.
    val poolKeyOpt = readPoolKey(nodeId)

    client.services.inNamespace(namespace).withName(nodeId).delete()
    client.pods.inNamespace(namespace).withName(nodeId).delete()
    // Token Secret tracks the pod 1:1, so we can drop it right away.
    // Idempotent on missing (e.g. an externally-stopped pod whose
    // token Secret a previous reconcile already cleaned up).
    client.secrets.inNamespace(namespace).withName(tokenSecretNameFor(nodeId)).delete()
    tokens.remove(nodeId)

    // If this was the last live pod for the pool, GC the federation
    // Secret. Filter out the just-deleted pod (still in the list if the
    // apiserver hasn't propagated yet) and anything in Terminating
    // state. The Secret is created lazily on start so its absence here
    // is normal for pools with no federation -- the delete() call is
    // idempotent and returns false for missing.
    poolKeyOpt.foreach { key =>
      val remaining = client.pods
        .inNamespace(namespace)
        .withLabel("quack-tenant", key.tenant)
        .withLabel("quack-tenant-db", key.tenantDb)
        .withLabel("quack-pool", key.pool)
        .list()
        .getItems
        .asScala
        .toList
        .filterNot(p => p.getMetadata.getName == nodeId)
        .filterNot(p => Option(p.getMetadata.getDeletionTimestamp).exists(_.nonEmpty))
      if remaining.isEmpty then
        client.secrets
          .inNamespace(namespace)
          .withName(secretNameFor(key))
          .delete()
    }
    ()
  }

  /** Reconstruct a [[PoolKey]] from a pod's labels. Returns `None` if the pod is gone or its label
    * set is incomplete (older labelling scheme or hand-edited pod).
    */
  private def readPoolKey(nodeId: String): Option[PoolKey] =
    Option(client.pods.inNamespace(namespace).withName(nodeId).get()).flatMap { p =>
      val labels = Option(p.getMetadata.getLabels)
        .map(_.asScala)
        .getOrElse(scala.collection.mutable.Map.empty)
      for
        tenant   <- labels.get("quack-tenant")
        tenantDb <- labels.get("quack-tenant-db")
        pool     <- labels.get("quack-pool")
      yield PoolKey(tenant, tenantDb, pool)
    }

  /** K8s Secret name for a pool's federation SQL. Hyphenizes the underscore on every segment
    * (tenant / tenantDb / pool) the same way [[ai.starlake.quack.ondemand.PoolSupervisor.nodeId]]
    * does, so the result is RFC-1123 compatible. Slugs never contain '-', so '_' -> '-' is
    * collision-free.
    */
  private def secretNameFor(key: PoolKey): String =
    val safeTenant = key.tenant.replace('_', '-')
    val safeDb     = key.tenantDb.replace('_', '-')
    val safePool   = key.pool.replace('_', '-')
    s"qod-fedsql-$safeTenant-$safeDb-$safePool"

  /** K8s Secret name for a node's bearer token. `nodeId` is already RFC-1123 safe (see
    * [[ai.starlake.quack.ondemand.PoolSupervisor.nodeId]]), so the prefix is the only addition.
    */
  private def tokenSecretNameFor(nodeId: String): String = s"qod-token-$nodeId"

  /** Create-or-replace the per-pod token Secret. Same pattern as [[ensureFederationSecret]] -- must
    * run before pod create so kubelet sees the Secret when validating the pod's
    * `env.valueFrom.secretKeyRef`. Idempotent across spawn retries.
    */
  private def ensureTokenSecret(nodeId: String, token: String): Unit =
    val meta = new ObjectMeta()
    meta.setName(tokenSecretNameFor(nodeId))
    val labels = new java.util.HashMap[String, String]()
    labels.put(labelKey, labelValue)
    labels.put("quack-node-id", nodeId)
    meta.setLabels(labels)
    val secret = new Secret()
    secret.setMetadata(meta)
    val data = new java.util.HashMap[String, String]()
    data.put(KubernetesQuackBackend.TokenSecretKey, token)
    secret.setStringData(data)
    client.secrets.inNamespace(namespace).resource(secret).createOr(r => r.update())
    ()

  /** Create-or-replace the per-pool federation Secret. Content is `stringData` (UTF-8, kubelet
    * base64-encodes); kubelet rejects pods that reference a missing Secret so this MUST run before
    * pod create. Idempotent -- fabric8's `resource(...).createOr(replace)` mirrors a kubectl-apply,
    * so concurrent ensure-calls for the same pool either both succeed (latest wins) or one races on
    * resourceVersion (auto-retried by fabric8).
    */
  private def ensureFederationSecret(key: PoolKey, sql: String): Unit =
    val name = secretNameFor(key)
    val meta = new ObjectMeta()
    meta.setName(name)
    val labels = new java.util.HashMap[String, String]()
    labels.put(labelKey, labelValue)
    labels.put("quack-tenant", key.tenant)
    labels.put("quack-tenant-db", key.tenantDb)
    labels.put("quack-pool", key.pool)
    meta.setLabels(labels)
    val secret = new Secret()
    secret.setMetadata(meta)
    val data = new java.util.HashMap[String, String]()
    data.put(KubernetesQuackBackend.FederationSecretKey, sql)
    secret.setStringData(data)
    val ops = client.secrets.inNamespace(namespace).resource(secret)
    // fabric8 6.x replaced createOrReplace with createOr(r => r.update())
    // for explicit conflict semantics. The first call creates; subsequent
    // calls update the existing object's stringData in place.
    ops.createOr(r => r.update())
    ()

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
      val nodeId = p.getMetadata.getName
      // Recover the bearer token from the per-pod Secret so a manager
      // restart doesn't wedge every Flight call to this pod on 401. Pods
      // without a matching Secret keep the empty token they had before;
      // a warn nudges the operator to scale/replace those pods.
      val recoveredToken =
        Option(client.secrets.inNamespace(namespace).withName(tokenSecretNameFor(nodeId)).get())
          .flatMap(s => readTokenFromSecret(s))
      recoveredToken match
        case Some(t) => tokens.put(nodeId, t)
        case None    => () // keep the existing in-memory entry if any, leave empty otherwise
      for
        tenant   <- labels.get("quack-tenant")
        tenantDb <- labels.get("quack-tenant-db")
        pool     <- labels.get("quack-pool")
        roleS    <- labels.get("quack-role")
        role     <- Role.parse(roleS).toOption
      yield RunningNode(
        nodeId = nodeId,
        poolKey = PoolKey(tenant, tenantDb, pool),
        role = role,
        host = s"$nodeId.$namespace.svc.cluster.local",
        port = quackPort,
        token = tokens.getOrElse(nodeId, ""),
        pid = None,
        podName = Some(nodeId),
        startedAt = Instant.now(),
        maxConcurrent = labels.get("quack-max-concurrent").flatMap(_.toIntOption).getOrElse(0)
      )
    }
  }

  /** Decode the bearer token from the per-pod Secret. The Secret stores `stringData` on write but
    * the K8s API materialises it back as base64-encoded `data` on read; tolerate both shapes
    * because fabric8's mock server keeps `stringData` populated.
    */
  private def readTokenFromSecret(s: Secret): Option[String] =
    Option(s.getStringData)
      .map(_.asScala.toMap)
      .flatMap(_.get(KubernetesQuackBackend.TokenSecretKey))
      .orElse {
        Option(s.getData)
          .map(_.asScala.toMap)
          .flatMap(_.get(KubernetesQuackBackend.TokenSecretKey))
          .map { b64 =>
            new String(
              java.util.Base64.getDecoder.decode(b64),
              java.nio.charset.StandardCharsets.UTF_8
            )
          }
      }
      .filter(_.nonEmpty)

  def cleanup(): IO[Unit] = IO.unit

end KubernetesQuackBackend

object KubernetesQuackBackend:

  /** Key inside the per-pool federation Secret holding the resolved SQL. Same string as the env var
    * name [[buildPod]] sets on the pod, so an operator who shells into the pod and runs
    * `env | grep extraSetupSql` sees a familiar value -- the kubelet-injected env mirrors the
    * Secret's keyed payload.
    */
  val FederationSecretKey: String = "extraSetupSql"

  /** Key inside the per-pod token Secret holding the bearer string. Matches the env var name
    * `spawn-quack-node.sh` expects.
    */
  val TokenSecretKey: String = "QOD_NODE_TOKEN"

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
