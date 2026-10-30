package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.model.{NodeSpec, PoolKey, Role, RunningNode}
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model._
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.utils.Serialization

import java.time.Instant
import scala.jdk.CollectionConverters._

/** Kubernetes-backed quack node runtime.
  *
  * Creates one Pod + one Service per node. Labels carry pool key, role and `maxConcurrent` so an
  * orphan-discovery pass can reconstruct [[ai.starlake.quack.model.RunningNode]] after a manager
  * restart.
  *
  * Four Secrets accompany each pod, all delivered as FILES of one read-only projected volume
  * (`qod-node-secrets`, mode 0400, mounted at [[KubernetesQuackBackend.SecretsMountPath]] and named
  * by `QOD_SECRETS_DIR`), never as env vars: a node user with SQL can read its own process
  * environment back.
  *   - per-pool `qod-fedsql-${tenant}-${tenantDb}-${pool}` holds the pool's init SQL: the resolved
  *     federation SQL (`spec.extraSetupSql`) and the tenant-db's and the pool's own initSql
  *     (`spec.tenantDbInitSql`, `spec.tenantPoolInitSql`), each key only when non-empty, the Secret
  *     only when one is; all pods of the pool share it. Each initSql sits under the key its trust
  *     names ([[NodeSecretFiles.tenantInitFiles]]), and a pod projects only the keys its own spec
  *     holds, so a pod started for a trusted text never reads a later untrusted one as trusted.
  *   - per-pool `qod-nodeenv-${tenant}-${tenantDb}-${pool}` holds the sensitive metastore values
  *     ([[ai.starlake.quack.model.TenantDb.NodeSecretEnvKeys]]: `pgPassword`, `encryptionKey`,
  *     taken from the metastore by [[NodeMetastore.secrets]]) plus the manager's own object-store
  *     credentials ([[NodeSecretFiles.CloudCredKeys]]); all pods of the pool share it, since they
  *     all attach the same tenant-db with the same credentials.
  *   - per-pod `qod-token-${nodeId}` holds the bearer token the manager uses to call this specific
  *     pod's `/quack` endpoint. The token survives manager restart -- [[discoverExisting]] reads it
  *     back from the Secret to repopulate the in-memory cache, so adopted pods stop 401-ing after a
  *     restart. It also holds the node's key ([[KubernetesQuackBackend.AuthzKeySecretKey]]), which
  *     the manager derives from its node-key secret and the node id, so every start writes the same
  *     one.
  *   - per-pod `qod-store-${nodeId}` holds the scoped per-database object-store `CREATE SECRET` SQL
  *     (authored by `ObjectStoreSecret.sql`, [[ai.starlake.quack.model.NodeSpec.objectStoreSql]])
  *     when non-empty; only that one node's pod references it.
  *
  * UPGRADE NOTE: pods created by an earlier manager still carry the old shape (`secretKeyRef` env
  * vars, or plain env before that) and are NOT healed in place: restart every node after upgrading.
  * The node image's entrypoint no longer passes a token argument, so old pod specs need the
  * matching old image.
  */
final class KubernetesQuackBackend(
    client: KubernetesClient,
    namespace: String,
    image: String,
    quackPort: Int,
    podLabel: String,
    startupTimeoutSec: Int,
    readPodReady: Pod => Boolean = pod => Option(pod.getStatus).map(_.getPhase).contains("Running"),
    readEnv: String => Option[String] = name => Option(System.getenv(name)),
    podTemplateEnabled: Boolean = false,
    // k8s.serviceAccount / k8s.serviceType (QOD_K8S_SERVICE_ACCOUNT / QOD_K8S_SERVICE_TYPE).
    serviceAccount: Option[String] = None,
    serviceType: String = "ClusterIP",
    // Pod-level runAsUser/fsGroup for the default security posture (see buildPod). A pod template's
    // own securityContext.runAsUser (if set) wins over this default.
    runAsUser: Long = 1000L,
    // Bound on stop()'s wait for the pod object to actually disappear (and on start()'s
    // poll for a Terminating twin to clear before retrying a 409'd create).
    stopTimeoutSec: Int = 60,
    // The keys every pod's QOD_NODE_AUTHZ_KEY file derives from (Main passes the manager's, so its
    // session handles verify).
    nodeKeys: ai.starlake.quack.edge.authz.NodeKeys =
      ai.starlake.quack.edge.authz.NodeKeys.ephemeral(),
    // The extension policy every pod's qod-node fixes before its database opens (spec decision 17).
    nodeExtensions: ai.starlake.quack.NodeExtensionsConfig =
      ai.starlake.quack.NodeExtensionsConfig()
) extends QuackBackend,
      LazyLogging:

  private val (labelKey, labelValue) =
    podLabel.split("=", 2) match
      case Array(k, v) => (k, v)
      case _           => sys.error(s"invalid podLabel: $podLabel")

  private val tokens = scala.collection.concurrent.TrieMap.empty[String, String]

  // Metastore keys that reach no pod, named once per pool and key.
  private val droppedKeys = new NodeMetastore.DroppedKeys(logger)

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
    // spec.metastore is ALREADY the fully-resolved, per-kind node metastore --
    // PoolSupervisor.effectiveMetastoreFor merged the manager defaults with the
    // tenant-db's own params and dropped whatever the kind doesn't want (e.g. no
    // `dataPath` for a pathless duckdb-file / in-memory tenant-db). Do NOT overlay
    // a separate defaults map here: re-merging manager defaults on top of this
    // would resurrect a key `effectiveMetastoreFor` deliberately removed (e.g. the
    // bootstrap tenant-db's DuckLake directory reappearing as `dataPath` on a
    // pathless duckdb-file node, which then fails ATTACH with "Is a directory").
    // Sensitive values never become EnvVars at all, neither plain (a pod spec is readable by anyone
    // with `kubectl get pod -o yaml`) nor `secretKeyRef` (a node user with SQL reads its own
    // process environment back through getenv() or /proc/self/environ). Every
    // NodeSecretFiles.Keys value (pgPassword, encryptionKey, federation SQL, object-store SQL, the
    // token, the forwarded QOD_S3_* / QOD_AZURE_* credentials) reaches the spawn script as a file of
    // the read-only `qod-node-secrets` projected volume instead (secretsVolume below), whose
    // Secrets start() creates before the pod.
    // Only TenantDb.NodeEnvKeys of the metastore become env (NodeMetastore): the metastore is
    // tenant-admin input, and any other name (BASH_ENV, PATH, LD_PRELOAD, QOD_NODE_BIN...) would run
    // commands in the pod before the node starts, or give the pod a second value of a variable set
    // below (its id, kind...).
    droppedKeys.report(spec.poolKey, spec.metastore)
    NodeMetastore.env(spec.metastore).foreach { case (k, v) =>
      val e = new EnvVar()
      e.setName(k)
      e.setValue(v)
      envs.add(e)
    }
    // Mirror the env-inheritance LocalQuackBackend gets for free (via ProcessBuilder): forward the
    // non-secret object-store settings present on the manager process so spawn-quack-node.sh can
    // build CREATE SECRET for s3:// / az:// / gs:// data paths (the credentials themselves ride
    // the node-env Secret, see nodeEnvSecretValues). The manager's own: no metastore key replaces
    // or suppresses one.
    KubernetesQuackBackend.cloudCredEnvVars.filterNot(NodeSecretFiles.isKey).foreach { name =>
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
    val secretsDirEnv = new EnvVar()
    secretsDirEnv.setName(NodeSecretFiles.DirEnv)
    secretsDirEnv.setValue(KubernetesQuackBackend.SecretsMountPath)
    envs.add(secretsDirEnv)
    val portEnv = new EnvVar()
    portEnv.setName("QOD_NODE_PORT")
    portEnv.setValue(quackPort.toString)
    envs.add(portEnv)
    val nodeIdEnv = new EnvVar()
    nodeIdEnv.setName(NodeSecretFiles.NodeIdEnv)
    nodeIdEnv.setValue(spec.nodeId)
    envs.add(nodeIdEnv)
    // Plain env like the node id: the policy is the manager's, not a secret.
    nodeExtensions.env.toList.sorted.foreach { case (k, v) =>
      val e = new EnvVar()
      e.setName(k)
      e.setValue(v)
      envs.add(e)
    }

    val containerPort = new ContainerPort()
    containerPort.setContainerPort(quackPort)

    // Base pod: gated template, else built from scratch.
    val templated =
      spec.podTemplateYaml
        .filter(_ => podTemplateEnabled)
        .filter(_.nonEmpty)
        .map(yaml => Serialization.unmarshal(yaml, classOf[Pod]))
    if spec.podTemplateYaml.exists(_.nonEmpty) && !podTemplateEnabled then
      logger.warn(
        s"pool ${spec.poolKey} has a pod template but QOD_POD_TEMPLATE_ENABLED is off; using the built-in pod shape"
      )

    val pod     = templated.getOrElse(new Pod())
    val podSpec = Option(pod.getSpec).getOrElse(new PodSpec())
    pod.setSpec(podSpec)
    serviceAccount.foreach(podSpec.setServiceAccountName)

    // Locate or create the quack container.
    val containers = Option(podSpec.getContainers).map(_.asScala.toList).getOrElse(Nil)
    val quack      = containers.find(_.getName == "quack").getOrElse {
      val c = new Container()
      c.setName("quack")
      c
    }
    quack.setImage(image)
    quack.setEnv(envs)
    quack.setPorts(java.util.List.of(containerPort))

    // Structured cpu/memory become request=limit on the quack container,
    // overwriting any template resources. Only-provided keys are set.
    val reqs = new java.util.HashMap[String, Quantity]()
    spec.cpu.filter(_.nonEmpty).foreach(v => reqs.put("cpu", new Quantity(v)))
    spec.memory.filter(_.nonEmpty).foreach(v => reqs.put("memory", new Quantity(v)))
    if !reqs.isEmpty then
      val rr = new ResourceRequirements()
      rr.setRequests(new java.util.HashMap(reqs))
      rr.setLimits(new java.util.HashMap(reqs))
      quack.setResources(rr)

    val others = containers.filterNot(_.getName == "quack")
    podSpec.setContainers((quack :: others).asJava)

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

    // Default security posture: runAsNonRoot pod identity + seccomp RuntimeDefault at the pod
    // level, no privilege escalation + all capabilities dropped + read-only rootfs on the quack
    // container. A pod template's own securityContext fields win field-by-field -- only the
    // fields the template left null are filled in with the default, so an operator's
    // stricter/looser override on a specific field survives untouched. The non-root uid with every
    // capability dropped is also what keeps spawn-quack-node.sh's 0500 secret directory read-only
    // (root with CAP_DAC_OVERRIDE could write it; its marker file still holds, and the script WARNs).
    podSpec.setSecurityContext(mergedPodSecurityContext(Option(podSpec.getSecurityContext)))
    quack.setSecurityContext(mergedContainerSecurityContext(Option(quack.getSecurityContext)))

    // /tmp and /duckdb-tmp as emptyDir volumes -- readOnlyRootFilesystem above means DuckDB's
    // scratch space and any incidental /tmp usage need a writable mount. Skipped when the
    // template already declares a volume/mount of the same name/path.
    ensureEmptyDirVolumes(podSpec)
    ensureEmptyDirMounts(quack)
    ensureSecretsVolume(podSpec, quack, secretsVolume(spec))

    // Identity metadata overlay (always ours).
    val meta = Option(pod.getMetadata).getOrElse(new ObjectMeta())
    meta.setName(spec.nodeId)
    meta.setLabels(buildLabels(spec))
    pod.setMetadata(meta)
    pod

  /** Fill in the default pod-level security posture, but let any field the (possibly
    * template-provided) `existing` context already set win. Mutates and returns `existing` when
    * present so unrelated fields the template set (e.g. `supplementalGroups`) survive untouched.
    */
  private def mergedPodSecurityContext(existing: Option[PodSecurityContext]): PodSecurityContext =
    val psc = existing.getOrElse(new PodSecurityContext())
    if psc.getRunAsNonRoot == null then psc.setRunAsNonRoot(true)
    if psc.getRunAsUser == null then psc.setRunAsUser(runAsUser)
    if psc.getFsGroup == null then psc.setFsGroup(runAsUser)
    if psc.getSeccompProfile == null then
      val seccomp = new SeccompProfile()
      seccomp.setType("RuntimeDefault")
      psc.setSeccompProfile(seccomp)
    psc

  /** Same field-by-field merge as [[mergedPodSecurityContext]], for the quack container. */
  private def mergedContainerSecurityContext(existing: Option[SecurityContext]): SecurityContext =
    val sc = existing.getOrElse(new SecurityContext())
    if sc.getAllowPrivilegeEscalation == null then sc.setAllowPrivilegeEscalation(false)
    if sc.getReadOnlyRootFilesystem == null then sc.setReadOnlyRootFilesystem(true)
    if sc.getCapabilities == null then
      val caps = new Capabilities()
      caps.setDrop(java.util.List.of("ALL"))
      sc.setCapabilities(caps)
    sc

  /** The read-only projected volume carrying every [[NodeSecretFiles.Keys]] value the node needs,
    * one file per key named after it: the per-pod token Secret, the per-pool node-env Secret
    * ([[nodeEnvSecretValues]]), the per-pool federation Secret and the per-pod object-store Secret,
    * each only when start() creates it. Mode 0400; `fsGroup` is always set
    * ([[mergedPodSecurityContext]] defaults it to `k8s.runAsUser` when the template sets none), so
    * kubelet makes them 0440 root:fsGroup, readable by the node's uid through that supplementary
    * group.
    */
  private def secretsVolume(spec: NodeSpec): Volume =
    def projection(secretName: String, items: Seq[(String, String)]): VolumeProjection =
      val sp = new SecretProjection()
      sp.setName(secretName)
      sp.setOptional(false)
      sp.setItems(items.map { case (key, path) =>
        val ktp = new KeyToPath()
        ktp.setKey(key)
        ktp.setPath(path)
        ktp
      }.asJava)
      val vp = new VolumeProjection()
      vp.setSecret(sp)
      vp
    val nodeEnvItems = nodeEnvSecretValues(spec).keys.toSeq
      .flatMap(k => NodeSecretFiles.canonical(k).map(c => (c, k)))
      .groupBy(_._1)
      .map { case (c, ks) => (ks.find(_._2 == c).getOrElse(ks.head)._2, c) }
      .toSeq
      .sortBy(_._2)
    val sources =
      Seq(
        projection(
          tokenSecretNameFor(spec.nodeId),
          Seq(
            TokenSecretKeyPath,
            KubernetesQuackBackend.AuthzKeySecretKey  -> NodeSecretFiles.AuthzKey,
            KubernetesQuackBackend.InitNonceSecretKey -> NodeSecretFiles.InitNonceKey
          )
        )
      ) ++
        Option.when(nodeEnvItems.nonEmpty)(
          projection(nodeEnvSecretNameFor(spec.poolKey), nodeEnvItems)
        ) ++
        Option.when(initSqlValues(spec).nonEmpty)(
          projection(
            secretNameFor(spec.poolKey),
            initSqlValues(spec).keys.toSeq.sorted.map(k => k -> k)
          )
        ) ++
        Option.when(spec.objectStoreSql.nonEmpty)(
          projection(
            objectStoreSecretNameFor(spec.nodeId),
            Seq(KubernetesQuackBackend.ObjectStoreSecretKey -> "objectStoreSql")
          )
        )
    val projected = new ProjectedVolumeSource()
    projected.setDefaultMode(Integer.valueOf(KubernetesQuackBackend.SecretsFileMode))
    projected.setSources(sources.asJava)
    val v = new Volume()
    v.setName(KubernetesQuackBackend.SecretsVolumeName)
    v.setProjected(projected)
    v

  private val TokenSecretKeyPath: (String, String) =
    KubernetesQuackBackend.TokenSecretKey -> NodeSecretFiles.TokenKey

  /** Install [[secretsVolume]] on the pod and mount it read-only on the quack container, replacing
    * any template volume / mount of the same name or path: the manager owns this one.
    */
  private def ensureSecretsVolume(podSpec: PodSpec, quack: Container, volume: Volume): Unit =
    val volumes = Option(podSpec.getVolumes).map(_.asScala.toList).getOrElse(Nil)
    podSpec.setVolumes(
      (volumes.filterNot(_.getName == volume.getName) :+ volume).asJava
    )
    val mounts = Option(quack.getVolumeMounts).map(_.asScala.toList).getOrElse(Nil)
    val vm     = new VolumeMount()
    vm.setName(volume.getName)
    vm.setMountPath(KubernetesQuackBackend.SecretsMountPath)
    vm.setReadOnly(true)
    quack.setVolumeMounts(
      (mounts.filterNot(m =>
        m.getName == volume.getName || m.getMountPath == KubernetesQuackBackend.SecretsMountPath
      ) :+ vm).asJava
    )

  /** Add the `tmp` / `duckdb-tmp` emptyDir volumes to the pod spec, skipping any name the template
    * already declares.
    */
  private def ensureEmptyDirVolumes(podSpec: PodSpec): Unit =
    val existingVolumes = Option(podSpec.getVolumes).map(_.asScala.toList).getOrElse(Nil)
    val existingNames   = existingVolumes.map(_.getName).toSet
    val toAdd           = KubernetesQuackBackend.emptyDirVolumes.filterNot { case (name, _) =>
      existingNames.contains(name)
    }
    if toAdd.nonEmpty then
      val vols = new java.util.ArrayList[Volume](existingVolumes.asJava)
      toAdd.foreach { case (name, _) =>
        val v = new Volume()
        v.setName(name)
        v.setEmptyDir(new EmptyDirVolumeSource())
        vols.add(v)
      }
      podSpec.setVolumes(vols)

  /** Mount the `tmp` / `duckdb-tmp` emptyDir volumes on the quack container, skipping any mount
    * path the template already declares.
    */
  private def ensureEmptyDirMounts(quack: Container): Unit =
    val existingMounts = Option(quack.getVolumeMounts).map(_.asScala.toList).getOrElse(Nil)
    val existingPaths  = existingMounts.map(_.getMountPath).toSet
    val toAdd          = KubernetesQuackBackend.emptyDirVolumes.filterNot { case (_, path) =>
      existingPaths.contains(path)
    }
    if toAdd.nonEmpty then
      val mounts = new java.util.ArrayList[VolumeMount](existingMounts.asJava)
      toAdd.foreach { case (name, path) =>
        val vm = new VolumeMount()
        vm.setName(name)
        vm.setMountPath(path)
        mounts.add(vm)
      }
      quack.setVolumeMounts(mounts)

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
    svcSpec.setType(serviceType)

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
    // Which resources THIS call created, so partial-start cleanup never tears down a
    // Terminating incumbent's resources after a 409'd create.
    val created = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
    // The incumbent's token value, captured before ensureTokenSecret's createOr overwrites
    // it, so a failed start can restore it (see cleanupPartialStart).
    val priorTokenSecretValue =
      new java.util.concurrent.atomic.AtomicReference[Option[String]](None)
    val run = IO.blocking {
      val token = LocalQuackBackend.randomToken()
      tokens.put(spec.nodeId, token)

      // All referenced Secrets must exist before pod create -- kubelet
      // rejects a pod whose env.valueFrom references a missing Secret.
      // ensureTokenSecret also makes the bearer string recoverable after
      // manager restart.
      val existingTokenSecret =
        Option(
          client.secrets
            .inNamespace(namespace)
            .withName(tokenSecretNameFor(spec.nodeId))
            .get()
        )
      val tokenSecretExisted = existingTokenSecret.isDefined
      // Same value discoverExisting would recover on a manager restart -- captured now so a
      // failed start can put it back rather than leaving the rotated value in place.
      existingTokenSecret.foreach(s => priorTokenSecretValue.set(readTokenFromSecret(s)))
      ensureTokenSecret(spec.nodeId, token)
      // An overwritten pre-existing Secret must survive cleanup (it may be an incumbent's
      // live credentials, e.g. a racing start against a still-running node).
      if !tokenSecretExisted then created.add("tokenSecret")
      val initSql = initSqlValues(spec)
      if initSql.nonEmpty then ensureFederationSecret(spec.poolKey, initSql)
      // Per-pool like the federation Secret above, and deliberately NOT tracked in `created`: it is
      // shared by every pod of the pool, so a partial-start rollback must not delete it out from
      // under a live sibling. stop() GCs it with the pool's last pod.
      val nodeEnvValues = nodeEnvSecretValues(spec)
      if nodeEnvValues.nonEmpty then ensureNodeEnvSecret(spec.poolKey, nodeEnvValues)
      if spec.objectStoreSql.nonEmpty then
        val objectStoreSecretExisted = client.secrets
          .inNamespace(namespace)
          .withName(objectStoreSecretNameFor(spec.nodeId))
          .get() != null
        ensureObjectStoreSecret(spec.nodeId, spec.objectStoreSql)
        // Same reasoning as the token Secret above: don't roll back an incumbent's Secret.
        if !objectStoreSecretExisted then created.add("objectStoreSecret")

      val pod        = buildPod(spec, token)
      val createdPod = createPodRetrying409(pod, spec.nodeId)
      created.add("pod")
      waitReady(createdPod)

      val svc        = buildService(spec)
      val svcExisted = client.services.inNamespace(namespace).withName(spec.nodeId).get() != null
      // Out-of-band pod death (e.g. an EC2 node-group replacement) leaves the Service
      // behind even though the Pod is gone; a respawn on the same deterministic nodeId
      // must not 409 on a Service that's still there, so this create is create-or-replace
      // just like the ensure*Secret helpers above. Same reasoning as the token Secret: an
      // overwritten pre-existing Service must survive cleanup (it may be an incumbent's).
      client.services.inNamespace(namespace).resource(svc).createOr(o => o.update())
      if !svcExisted then created.add("service")

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
      .onError { case e =>
        cleanupPartialStart(spec, e.toString, created, priorTokenSecretValue.get())
      }
      .onCancel(
        cleanupPartialStart(spec, "start cancelled", created, priorTokenSecretValue.get())
      )

  /** Create the pod, absorbing one 409 conflict against a Terminating twin: poll (bounded by
    * `stopTimeoutSec`) until the old pod object is gone, then create again. A second failure
    * propagates.
    */
  private def createPodRetrying409(pod: Pod, nodeId: String): Pod =
    try client.pods.inNamespace(namespace).resource(pod).create()
    catch
      case e: io.fabric8.kubernetes.client.KubernetesClientException if e.getCode == 409 =>
        logger.warn(
          s"pod $nodeId create conflicted (terminating twin?); waiting up to ${stopTimeoutSec}s"
        )
        val deadline = System.nanoTime() + stopTimeoutSec.toLong * 1000000000L
        while client.pods.inNamespace(namespace).withName(nodeId).get() != null
          && System.nanoTime() < deadline
        do Thread.sleep(500)
        client.pods.inNamespace(namespace).resource(pod).create()

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
    * Deletes the Service, the Pod, the per-pod token Secret and the per-pod object-store Secret by
    * the exact names this call created (all derived from `spec.nodeId`, matching [[buildService]] /
    * [[buildPod]] / [[tokenSecretNameFor]] / [[objectStoreSecretNameFor]]), gated by the `created`
    * set this call's [[start]] populated as each resource actually came into existence. That gate
    * is what keeps a 409'd pod create (Task 4: a Terminating incumbent still occupying the
    * deterministic name) from having its rollback delete the incumbent's own pod/service/secrets --
    * this call never created them, so `created` never marks them, so cleanup leaves them alone.
    * Every delete is swallowed and logged so cleanup can never mask the original spawn failure.
    *
    * The token Secret needs one more step than a plain "leave it alone": when it pre-existed (not
    * in `created`), `ensureTokenSecret` still overwrote its value in place before the pod create
    * failed, so the incumbent pod's still-live env now points at a Secret holding this failed
    * attempt's token instead of its own. `priorTokenSecretValue` carries the value `start` captured
    * before that overwrite; restoring it here is what keeps `discoverExisting` matching the
    * incumbent's token after a manager restart.
    *
    * The per-POOL Secrets (federation `qod-fedsql-...` and node-env `qod-nodeenv-...`) are
    * intentionally left out of the `created` gate and never deleted here: they are shared by every
    * pod of the pool, so deleting one on a single node's failure could break sibling pods that
    * reference it. Both are GC'd by [[stop]] when the pool's last pod goes away.
    */
  private def cleanupPartialStart(
      spec: NodeSpec,
      reason: String,
      created: java.util.Set[String],
      priorTokenSecretValue: Option[String]
  ): IO[Unit] = IO.blocking {
    val mine = created.asScala.toSet
    logger.warn(
      s"start() for pod ${spec.nodeId} failed ($reason); rolling back created resources: " +
        mine.mkString(", ")
    )
    if mine("service") then
      deleteQuietly("service", spec.nodeId) {
        client.services.inNamespace(namespace).withName(spec.nodeId).delete()
      }
    if mine("pod") then
      deleteQuietly("pod", spec.nodeId) {
        client.pods.inNamespace(namespace).withName(spec.nodeId).delete()
      }
    if mine("tokenSecret") then
      deleteQuietly("token secret", tokenSecretNameFor(spec.nodeId)) {
        client.secrets.inNamespace(namespace).withName(tokenSecretNameFor(spec.nodeId)).delete()
      }
    else
      // Pre-existing Secret this call rotated: the incumbent pod's env still carries the
      // pre-rotation token, so discoverExisting must keep matching it. Restore, don't delete.
      priorTokenSecretValue.foreach { prior =>
        deleteQuietly("token secret restore", tokenSecretNameFor(spec.nodeId)) {
          ensureTokenSecret(spec.nodeId, prior)
        }
      }
    if mine("objectStoreSecret") then
      deleteQuietly("object-store secret", objectStoreSecretNameFor(spec.nodeId)) {
        client.secrets
          .inNamespace(namespace)
          .withName(objectStoreSecretNameFor(spec.nodeId))
          .delete()
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

  def stop(key: PoolKey, nodeId: String): IO[Unit] = IO.blocking {
    client.services.inNamespace(namespace).withName(nodeId).delete()
    // withTimeout makes fabric8 wait until the pod object is actually gone, so a
    // delete-then-recreate roll (restartNode) can't 409 against its own Terminating
    // twin. On timeout we proceed: the caller's teardown is best-effort and start()
    // retries a 409'd create (Task 4).
    try
      client.pods
        .inNamespace(namespace)
        .withName(nodeId)
        .withTimeout(stopTimeoutSec.toLong, java.util.concurrent.TimeUnit.SECONDS)
        .delete()
    catch
      case _: io.fabric8.kubernetes.client.KubernetesClientTimeoutException =>
        logger.warn(s"pod $nodeId still terminating after ${stopTimeoutSec}s; proceeding")
    // Token and object-store Secrets both track the pod 1:1, so we can drop
    // them right away. Idempotent on missing (e.g. an externally-stopped pod
    // whose Secrets a previous reconcile already cleaned up, or a pool with
    // no per-db object-store credentials at all).
    client.secrets.inNamespace(namespace).withName(tokenSecretNameFor(nodeId)).delete()
    client.secrets.inNamespace(namespace).withName(objectStoreSecretNameFor(nodeId)).delete()
    tokens.remove(nodeId)

    // Per-pool Secret GC (federation + node-env) is pure bookkeeping: a failure here (apiserver
    // blip on the label-list) must not abort a stop whose pod delete already succeeded. The pool key
    // comes from the caller, never from pod labels, so the GC works when the pod is
    // already gone; the filterNots drop the just-deleted pod (apiserver propagation lag)
    // and Terminating siblings. Only manager-owned pods count (managed-by label, matching
    // liveNodeIds / discoverExisting): an unmanaged pod wearing the pool labels must not
    // keep the Secret alive.
    try
      val remaining = client.pods
        .inNamespace(namespace)
        .withLabel(labelKey, labelValue)
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
        // Same per-pool lifetime and the same idempotent-on-missing delete: a pool whose tenant-db
        // carried neither sensitive key never had this Secret, and the delete is a no-op then.
        client.secrets
          .inNamespace(namespace)
          .withName(nodeEnvSecretNameFor(key))
          .delete()
        ()
    catch
      case e: Throwable =>
        logger.warn(s"per-pool secret GC for $key failed (non-fatal): ${e.getMessage}")
    ()
  }

  /** The sensitive subset of a spec's metastore, i.e. exactly the entries delivered through the
    * per-pool node-env Secret. The ONE place this subset is computed: [[buildPod]] uses it to strip
    * those keys from the plain env and to emit the matching `secretKeyRef`s, and [[start]] uses it
    * to fill the Secret. Deriving both from here is what makes it impossible for a pod to reference
    * a Secret key the Secret does not hold (kubelet would reject such a pod), or for an empty
    * Secret to be created for a tenant-db that carries neither key.
    */
  private def nodeEnvSecretValues(spec: NodeSpec): Map[String, String] =
    // Case-insensitive, like `HandlerResolvers.redactPassword` and
    // `PoolSupervisor.mergeSecretKeys`: a metastore key spelled `PgPassword` is redacted from every
    // API response by those two, so it must not be the one spelling that lands in a pod spec as a
    // plain EnvVar. The key keeps its original spelling in the Secret; the volume maps it to the
    // canonical file name the script reads. Only the metastore's own secrets (NodeMetastore): a
    // metastore holds no cloud credential.
    val fromMetastore = NodeMetastore.secrets(spec.metastore)
    // The manager's own object-store credentials, forwarded as env before: now Secret-backed.
    val fromManager = NodeSecretFiles.CloudCredKeys
      .flatMap(k => readEnv(k).filter(_.nonEmpty).map(k -> _))
    fromMetastore ++ fromManager

  /** K8s Secret name for a pool's sensitive node env values. Per-pool, not per-pod: every pod of a
    * pool attaches the same tenant-db with the same credentials. Same RFC-1123 hyphenization as
    * [[secretNameFor]].
    */
  private def nodeEnvSecretNameFor(key: PoolKey): String =
    val safeTenant = key.tenant.replace('_', '-')
    val safeDb     = key.tenantDb.replace('_', '-')
    val safePool   = key.pool.replace('_', '-')
    s"qod-nodeenv-$safeTenant-$safeDb-$safePool"

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

  /** K8s Secret name for a node's per-database object-store `CREATE SECRET` SQL. Same RFC-1123
    * reasoning as [[tokenSecretNameFor]].
    */
  private def objectStoreSecretNameFor(nodeId: String): String = s"qod-store-$nodeId"

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
    data.put(KubernetesQuackBackend.AuthzKeySecretKey, nodeKeys.keyHex(nodeId))
    // A fresh init marker nonce on every write (a restore of an incumbent's token included): each
    // start of the pod's container reads the one the Secret holds then.
    data.put(KubernetesQuackBackend.InitNonceSecretKey, NodeSecretFiles.newInitNonce())
    secret.setStringData(data)
    client.secrets.inNamespace(namespace).resource(secret).createOr(r => r.update())
    ()

  /** Create-or-replace the per-pod object-store Secret. Same pattern as [[ensureTokenSecret]] --
    * must run before pod create so kubelet sees the Secret when validating the pod's
    * `env.valueFrom.secretKeyRef`. Idempotent across spawn retries. Called only when
    * `spec.objectStoreSql` is non-empty (a tenant-db with no per-db object-store credentials never
    * gets this Secret).
    */
  private def ensureObjectStoreSecret(nodeId: String, sql: String): Unit =
    val meta = new ObjectMeta()
    meta.setName(objectStoreSecretNameFor(nodeId))
    val labels = new java.util.HashMap[String, String]()
    labels.put(labelKey, labelValue)
    labels.put("quack-node-id", nodeId)
    meta.setLabels(labels)
    val secret = new Secret()
    secret.setMetadata(meta)
    val data = new java.util.HashMap[String, String]()
    data.put(KubernetesQuackBackend.ObjectStoreSecretKey, sql)
    secret.setStringData(data)
    client.secrets.inNamespace(namespace).resource(secret).createOr(r => r.update())
    ()

  /** Create-or-replace the per-pool node-env Secret holding the sensitive metastore values. Same
    * pattern and the same ordering constraint as [[ensureFederationSecret]]: kubelet rejects a pod
    * referencing a missing Secret, so this MUST run before pod create. Idempotent across spawn
    * retries. Called only when [[nodeEnvSecretValues]] is non-empty -- an in-memory tenant-db has
    * neither key, and an empty Secret referenced by a `secretKeyRef` would fail the pod.
    */
  private def ensureNodeEnvSecret(key: PoolKey, values: Map[String, String]): Unit =
    val meta = new ObjectMeta()
    meta.setName(nodeEnvSecretNameFor(key))
    val labels = new java.util.HashMap[String, String]()
    labels.put(labelKey, labelValue)
    labels.put("quack-tenant", key.tenant)
    labels.put("quack-tenant-db", key.tenantDb)
    labels.put("quack-pool", key.pool)
    meta.setLabels(labels)
    val secret = new Secret()
    secret.setMetadata(meta)
    val data = new java.util.HashMap[String, String]()
    values.foreach { case (k, v) => data.put(k, v) }
    secret.setStringData(data)
    client.secrets.inNamespace(namespace).resource(secret).createOr(r => r.update())
    ()

  /** Create-or-replace the per-pool federation Secret. Content is `stringData` (UTF-8, kubelet
    * base64-encodes); kubelet rejects pods that reference a missing Secret so this MUST run before
    * pod create. Idempotent -- fabric8's `resource(...).createOr(replace)` mirrors a kubectl-apply,
    * so concurrent ensure-calls for the same pool either both succeed (latest wins) or one races on
    * resourceVersion (auto-retried by fabric8).
    */
  /** The pool's init SQL Secret ([[secretNameFor]]): its non-empty values by key, which is also
    * each file's name in the secrets volume (the names the spawn script reads).
    */
  private def initSqlValues(spec: NodeSpec): Map[String, String] =
    (Map(KubernetesQuackBackend.FederationSecretKey -> spec.extraSetupSql) ++
      NodeSecretFiles.tenantInitFiles(spec)).filter(_._2.nonEmpty)

  private def ensureFederationSecret(key: PoolKey, values: Map[String, String]): Unit =
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
    values.foreach { case (k, v) => data.put(k, v) }
    secret.setStringData(data)
    val ops = client.secrets.inNamespace(namespace).resource(secret)
    // fabric8 6.x replaced createOrReplace with createOr(r => r.update())
    // for explicit conflict semantics. The first call creates; subsequent
    // calls update the existing object's stringData in place.
    ops.createOr(r => r.update())
    ()

  def isAlive(nodeId: String): Boolean =
    Option(client.pods.inNamespace(namespace).withName(nodeId).get()).exists(readPodReady)

  /** One labeled pod-list per pool per reconcile pass (never a per-node GET storm). Terminating
    * pods don't count as live. Fails OPEN (None) on any apiserver error: reconcile must not prune
    * or respawn a whole pool because of a transient list failure.
    */
  override def liveNodeIds(key: PoolKey): IO[Option[Set[String]]] = IO.blocking {
    try
      val items = client.pods
        .inNamespace(namespace)
        .withLabel(labelKey, labelValue)
        .withLabel("quack-tenant", key.tenant)
        .withLabel("quack-tenant-db", key.tenantDb)
        .withLabel("quack-pool", key.pool)
        .list()
        .getItems
        .asScala
        .toList
      Some(
        items
          .filterNot(p => Option(p.getMetadata.getDeletionTimestamp).exists(_.nonEmpty))
          .map(_.getMetadata.getName)
          .toSet
      )
    catch
      case e: Throwable =>
        logger.warn(s"liveNodeIds($key) failed; keeping current view: ${e.getMessage}")
        None
  }

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
      // Unlike the token there is no in-memory field to repopulate for the
      // object-store Secret -- its SQL only matters to the node's own DuckDB
      // boot sequence, not to calls the manager makes. Still, verify the ref
      // this pod's env points at is intact: a pod that references
      // `qod-store-$nodeId` but finds the Secret gone would fail to
      // reschedule (CreateContainerConfigError) on its next restart, so warn
      // the operator now instead of at that later, harder-to-diagnose crash.
      if podReferencesObjectStoreSecret(p) then
        val secretExists =
          Option(
            client.secrets.inNamespace(namespace).withName(objectStoreSecretNameFor(nodeId)).get()
          ).isDefined
        if !secretExists then
          logger.warn(
            s"pod $nodeId references object-store Secret ${objectStoreSecretNameFor(nodeId)}" +
              " which no longer exists; it will fail to reschedule until the node is respawned"
          )
      // Same reasoning for the per-pool node-env Secret: a pod whose env references
      // `qod-nodeenv-...` but finds the Secret gone fails to reschedule with
      // CreateContainerConfigError, so surface it now. Nothing is recovered into manager memory
      // here -- `pgPassword` / `encryptionKey` only matter to the node's own DuckDB boot, the
      // manager never reads them back from the pod -- so a pod from an earlier manager carrying
      // them as plain env is adopted exactly as before, just left un-healed (see the UPGRADE NOTE
      // on the class).
      nodeEnvSecretRefOf(p).foreach { secretName =>
        val secretExists =
          Option(client.secrets.inNamespace(namespace).withName(secretName).get()).isDefined
        if !secretExists then
          logger.warn(
            s"pod $nodeId references node-env Secret $secretName which no longer exists;" +
              " it will fail to reschedule until the node is respawned"
          )
      }
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

  /** True when the pod's `quack` container has an `objectStoreSql` env entry (always Secret-backed
    * -- [[buildPod]] never inlines it), i.e. this node was spawned with per-db object-store
    * credentials.
    */
  private def podReferencesObjectStoreSecret(p: Pod): Boolean =
    Option(p.getSpec)
      .flatMap(ps => Option(ps.getContainers))
      .exists(_.asScala.exists { c =>
        Option(c.getEnv).exists(_.asScala.exists(_.getName == "objectStoreSql"))
      }) || projectedSecretNames(p).exists(_.startsWith("qod-store-"))

  /** Names of the Secrets the pod's projected volumes draw from (the `qod-node-secrets` shape). */
  private def projectedSecretNames(p: Pod): List[String] =
    Option(p.getSpec)
      .flatMap(ps => Option(ps.getVolumes))
      .map(_.asScala.toList)
      .getOrElse(Nil)
      .flatMap(v => Option(v.getProjected).flatMap(pr => Option(pr.getSources)))
      .flatMap(_.asScala.toList)
      .flatMap(src => Option(src.getSecret).map(_.getName))

  /** The node-env Secret this pod's containers reference, if any. Read off the pod's own
    * `secretKeyRef`s rather than rebuilt from labels, so it reports what the running pod actually
    * points at. A pod spawned by an earlier manager (sensitive values inlined as plain env) has no
    * such ref and yields None.
    */
  private def nodeEnvSecretRefOf(p: Pod): Option[String] =
    Option(p.getSpec)
      .flatMap(ps => Option(ps.getContainers))
      .map(_.asScala.toList)
      .getOrElse(Nil)
      .flatMap(c => Option(c.getEnv).map(_.asScala.toList).getOrElse(Nil))
      .flatMap(e => Option(e.getValueFrom).flatMap(vf => Option(vf.getSecretKeyRef)))
      .map(_.getName)
      .concat(projectedSecretNames(p))
      .find(_.startsWith("qod-nodeenv-"))

  def cleanup(): IO[Unit] = IO.unit

end KubernetesQuackBackend

object KubernetesQuackBackend:

  /** Key inside the per-pool federation Secret holding the resolved SQL. Same string as the env var
    * name `buildPod` sets on the pod, so an operator who shells into the pod and runs
    * `env | grep extraSetupSql` sees a familiar value -- the kubelet-injected env mirrors the
    * Secret's keyed payload.
    */
  val FederationSecretKey: String = "extraSetupSql"

  /** Key inside the per-pod token Secret holding the bearer string. Matches the env var name
    * `spawn-quack-node.sh` expects.
    */
  val TokenSecretKey: String = "QOD_NODE_TOKEN"

  /** Key inside the per-pod token Secret holding the node's key (NodeSecretFiles.AuthzKey). */
  val AuthzKeySecretKey: String = "authzKey"

  /** Key inside the per-pod token Secret holding the init marker nonce
    * (NodeSecretFiles.InitNonceKey).
    */
  val InitNonceSecretKey: String = "initNonce"

  /** Key inside the per-pod object-store Secret holding the scoped `CREATE SECRET` SQL. Same string
    * as the env var name `buildPod` sets on the pod (matches the `objectStoreSql` env var
    * `scripts/spawn-quack-node.sh` reads), mirroring [[FederationSecretKey]] / [[TokenSecretKey]].
    */
  val ObjectStoreSecretKey: String = "objectStoreSql"

  /** Name of the projected Secret volume carrying the node's sensitive values as files. */
  val SecretsVolumeName: String = "qod-node-secrets"

  /** Where that volume is mounted; the pod's `QOD_SECRETS_DIR`. */
  val SecretsMountPath: String = "/var/run/qod-secrets"

  /** File mode of the projected files: 0400 (owner read-only), as a decimal Integer for the API. */
  val SecretsFileMode: Int = Integer.parseInt("400", 8)

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

  /** (volume name, mount path) pairs for the emptyDir scratch volumes every node pod carries.
    * `readOnlyRootFilesystem` on the quack container's default security posture means DuckDB's own
    * temp directory and any incidental `/tmp` usage need a writable volume mounted in.
    */
  val emptyDirVolumes: Seq[(String, String)] = Seq(
    "tmp"        -> "/tmp",
    "duckdb-tmp" -> "/duckdb-tmp"
  )
