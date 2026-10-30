package ai.starlake.quack.boot

import java.nio.file.Path
import ai.starlake.quack.{ManagerConfig, RuntimeType}
import ai.starlake.quack.edge.authz.AuthzDecider
import ai.starlake.quack.edge.authz.location.LocationRulesSource
import ai.starlake.quack.edge.config.OpaConfig
import ai.starlake.quack.edge.opa.OpaAuthorizer
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.federation.{
  AwsSecretsManagerResolver,
  AzureSecretsManagerResolver,
  DispatchingSecretResolver,
  EnvSecretResolver,
  GcpSecretsManagerResolver,
  PostgresSecretResolver,
  SecretResolver,
  VaultSecretResolver
}
import ai.starlake.quack.ondemand.runtime.{
  FleetQuackBackend,
  KubernetesQuackBackend,
  LocalQuackBackend,
  QuackBackend
}
import ai.starlake.quack.ondemand.state.FleetServerStore
import com.typesafe.scalalogging.LazyLogging

/** Config-driven component selection extracted from Main.bootManager: each factory turns one config
  * knob into a wired component and fails fast (sys.error) on unknown values.
  */
object BootFactories extends LazyLogging:

  /** True when this manager starts nodes on its own host, which then run qod-node
    * ([[ai.starlake.quack.ondemand.runtime.QodNodeBinary]]): the local runtime, or fleet mode with
    * QOD_FLEET_EPHEMERAL=local for its maintenance and branch-merge nodes.
    */
  def runsLocalNodes(mgrCfg: ManagerConfig): Boolean =
    RuntimeType.isLocal(mgrCfg.runtimeType) ||
      (RuntimeType.isFleet(mgrCfg.runtimeType) && mgrCfg.fleet.ephemeralLocal)

  /** @param nodeBin
    *   the qod-node local nodes run; Main resolves it whenever [[runsLocalNodes]].
    * @param nodeKeys
    *   the manager's node keys: every node the local and Kubernetes backends start gets the key
    *   derived for its id. The fleet backend does not use them: a fleet node's key is derived per
    *   heartbeat by `FleetHandlers`, which Main builds with the same keys. The local and Kubernetes
    *   backends hand every node `mgrCfg.nodeExtensions` (spec decision 17); fleet nodes get it from
    *   `FleetHandlers`, per heartbeat.
    */
  def quackBackend(
      mgrCfg: ManagerConfig,
      fleetStore: => FleetServerStore,
      nodeBin: Option[Path],
      nodeKeys: ai.starlake.quack.edge.authz.NodeKeys
  ): QuackBackend =
    mgrCfg.runtimeType match
      case rt if RuntimeType.isLocal(rt) =>
        new LocalQuackBackend(
          mgrCfg.minPort,
          mgrCfg.maxPort,
          commandFor = LocalQuackBackend.defaultCommand(
            mgrCfg.spawnScript,
            mgrCfg.spawnScriptWindows,
            LocalQuackBackend.managerMarker(mgrCfg.port)
          ),
          nodeBin = nodeBin,
          nodeKeys = nodeKeys,
          nodeExtensions = mgrCfg.nodeExtensions,
          nodeAuthzLimits = mgrCfg.nodeAuthzLimits
        )
      case rt if RuntimeType.isKubernetes(rt) =>
        val k8s = new io.fabric8.kubernetes.client.KubernetesClientBuilder().build()
        new KubernetesQuackBackend(
          k8s,
          mgrCfg.k8s.namespace,
          mgrCfg.k8s.image,
          mgrCfg.k8s.quackPort,
          mgrCfg.k8s.podLabel,
          mgrCfg.k8s.startupTimeoutSec,
          podTemplateEnabled = mgrCfg.k8s.podTemplateEnabled,
          serviceAccount = mgrCfg.k8s.serviceAccount,
          serviceType = mgrCfg.k8s.serviceType,
          runAsUser = mgrCfg.k8s.runAsUser,
          stopTimeoutSec = mgrCfg.k8s.stopTimeoutSec,
          nodeKeys = nodeKeys,
          nodeExtensions = mgrCfg.nodeExtensions,
          nodeAuthzLimits = mgrCfg.nodeAuthzLimits
        )
      case rt if RuntimeType.isFleet(rt) =>
        val proxies =
          val p = mgrCfg.fleet.trustedProxyCidrs
          if p.isEmpty then "none" else p.mkString(",")
        mgrCfg.fleet.openAutoApproveWarning match
          case Some(w) => logger.warn(s"$w Trusted proxies: $proxies.")
          case None    =>
            val list = mgrCfg.fleet.autoApproveCidrs
            logger.info(
              "fleet: auto-approve " +
                (if list.isEmpty then "none (every new server needs `qod fleet approve`)"
                 else list.mkString(",")) +
                s"; trusted proxies: $proxies"
            )
        new FleetQuackBackend(
          fleetStore,
          mgrCfg.fleet,
          defaultPgHost = mgrCfg.defaultMetastore.pgHost,
          defaultPgPort = mgrCfg.defaultMetastore.pgPort
        )
      case other => sys.error(s"unknown runtime: $other")

  /** Backend for maintenance and branch-merge nodes. Same as `main` unless fleet mode asks for them
    * to run on the manager host (QOD_FLEET_EPHEMERAL=local).
    */
  def ephemeralBackend(
      mgrCfg: ManagerConfig,
      main: QuackBackend,
      nodeBin: Option[Path],
      nodeKeys: ai.starlake.quack.edge.authz.NodeKeys
  ): QuackBackend =
    if RuntimeType.isFleet(mgrCfg.runtimeType) && mgrCfg.fleet.ephemeralLocal then
      logger.info(
        "fleet: ephemeral maintenance/merge nodes run on the manager host (local backend)"
      )
      new LocalQuackBackend(
        mgrCfg.minPort,
        mgrCfg.maxPort,
        commandFor = LocalQuackBackend.defaultCommand(
          mgrCfg.spawnScript,
          mgrCfg.spawnScriptWindows,
          LocalQuackBackend.managerMarker(mgrCfg.port)
        ),
        nodeBin = nodeBin,
        nodeKeys = nodeKeys,
        nodeExtensions = mgrCfg.nodeExtensions,
        nodeAuthzLimits = mgrCfg.nodeAuthzLimits
      )
    else main

  /** `dispatch` routes per-secret based on the row's shape (value -> Postgres, externalRef prefix
    * -> matching cloud / env / vault resolver). Other values force a single backend; useful only
    * when an operator wants to hard-restrict the manager to one secret store. `dispatch` keeps the
    * stub resolvers wired so a deployment that only uses postgres/env secrets still comes up;
    * runtime errors surface only for secrets that actually carry a stub-backed externalRef prefix
    * (aws-sm: / gcp-sm: / azure-kv: / vault:). Selecting a stub directly is refused here because
    * every secret resolved through it would crash at handshake time -- caller's mistake should fail
    * at boot, not in production.
    */
  def secretResolver(secretStore: String): SecretResolver =
    val UnimplementedSingleBackends = Set("aws-sm", "gcp-sm", "azure-kv", "vault")
    secretStore match
      case "dispatch" | "auto" =>
        new DispatchingSecretResolver(
          postgres = new PostgresSecretResolver,
          env = new EnvSecretResolver(),
          awsSm = new AwsSecretsManagerResolver,
          gcpSm = new GcpSecretsManagerResolver,
          azureKv = new AzureSecretsManagerResolver,
          vault = new VaultSecretResolver
        )
      case "postgres"                                   => new PostgresSecretResolver
      case "env"                                        => new EnvSecretResolver()
      case s if UnimplementedSingleBackends.contains(s) =>
        sys.error(
          s"federation.secretStore = '$s' is not implemented (the resolver is a stub). " +
            "Set QOD_FEDERATION_SECRET_STORE to 'postgres' (inline secret values), " +
            "'env' (resolve \\$VARS), or 'dispatch' (route per-secret by externalRef " +
            s"prefix -- the dispatch mode keeps the $s stub wired; only sources whose " +
            s"secrets actually carry an '$s:' externalRef will fail at resolve time)."
        )
      case other => sys.error(s"unknown federation.secretStore: '$other'")

  /** The live location rules of the decision pipeline (spec decisions 15, 18 and 23): the
    * supervisor's data paths, tenant locations and users' S3 credential scopes, the store's
    * unpurged managed prefixes, the operator's files root. Real-path resolution only on the local
    * runtime, the one whose nodes share this host's filesystem and working directory.
    */
  def locationRules(
      mgrCfg: ManagerConfig,
      sup: PoolSupervisor,
      store: ai.starlake.quack.ondemand.state.ControlPlaneStore
  ): ai.starlake.quack.edge.authz.location.LiveLocationRules =
    new ai.starlake.quack.edge.authz.location.LiveLocationRules(
      dataPaths = () => sup.dataPaths(),
      unpurgedPrefixes = () => store.unpurgedManagedPrefixes().map(_.prefix),
      tenantLocations = sup.tenantLocations,
      userScopes = userId => sup.userS3.current(userId).map(_.map(_.scope)),
      filesRoot = mgrCfg.tenantFiles.rootOpt,
      sameHost = RuntimeType.isLocal(mgrCfg.runtimeType)
    )

  /** qodstate_tenant.id -> the tenant_db names the tenant owns: the catalogs a wildcard catalog
    * grant admits for its sessions (AuthzDecider). Unknown tenant: none.
    */
  private def tenantCatalogsOf(sup: PoolSupervisor): String => Set[String] =
    tenantId =>
      sup
        .getTenantById(tenantId)
        .map(t => sup.listTenantDbsByTenant(t.id).map(_.name).toSet)
        .getOrElse(Set.empty)

  /** The node decision pipeline (spec Section 6). Always enforced (decision 5); an opa tenant is
    * judged by its OPA policy.
    */
  def authzDecider(
      sup: PoolSupervisor,
      opaCfg: OpaConfig,
      opa: OpaAuthorizer,
      locationRules: LocationRulesSource
  ): AuthzDecider =
    new AuthzDecider(
      tenantOf = sup.getTenantById,
      tenantCatalogs = tenantCatalogsOf(sup),
      // The catalog the pool's node attaches (memory, a catalogAlias or dbName): a wildcard
      // catalog grant covers it on that pool only.
      poolCatalog = key =>
        sup.get(key).flatMap(st => AuthzDecider.attachedCatalog(st.kindWire, st.metastore)),
      parentPoolsOf = sup.parentPoolNamesOf,
      locations = locationRules,
      opa = opa,
      opaCfg = opaCfg
    )
