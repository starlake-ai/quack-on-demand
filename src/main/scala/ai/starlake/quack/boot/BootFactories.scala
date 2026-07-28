package ai.starlake.quack.boot

import ai.starlake.quack.ManagerConfig
import ai.starlake.quack.edge.config.AclConfig
import ai.starlake.quack.edge.sql.{PostgresAclValidator, StatementValidator}
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
import ai.starlake.quack.ondemand.runtime.{KubernetesQuackBackend, LocalQuackBackend, QuackBackend}
import com.typesafe.scalalogging.LazyLogging

/** Config-driven component selection extracted from Main.bootManager: each factory turns one config
  * knob into a wired component and fails fast (sys.error) on unknown values.
  */
object BootFactories extends LazyLogging:

  def quackBackend(mgrCfg: ManagerConfig): QuackBackend = mgrCfg.runtimeType.toLowerCase match
    case "local" =>
      new LocalQuackBackend(
        mgrCfg.minPort,
        mgrCfg.maxPort,
        mgrCfg.defaultMetastore.asMap,
        commandFor = LocalQuackBackend.defaultCommand(mgrCfg.spawnScript, mgrCfg.spawnScriptWindows)
      )
    case "kubernetes" | "k8s" =>
      val k8s = new io.fabric8.kubernetes.client.KubernetesClientBuilder().build()
      new KubernetesQuackBackend(
        k8s,
        mgrCfg.k8s.namespace,
        mgrCfg.k8s.image,
        mgrCfg.k8s.quackPort,
        mgrCfg.k8s.podLabel,
        mgrCfg.k8s.startupTimeoutSec,
        mgrCfg.defaultMetastore.asMap,
        podTemplateEnabled = mgrCfg.k8s.podTemplateEnabled,
        serviceAccount = mgrCfg.k8s.serviceAccount,
        serviceType = mgrCfg.k8s.serviceType,
        runAsUser = mgrCfg.k8s.runAsUser
      )
    case other => sys.error(s"unknown runtime: $other")

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

  /** SQL ACL validator. The RBAC-backed PostgresAclValidator reads from the cached EffectiveSet
    * pinned on ConnectionContext at handshake time. acl.enabled=false falls back to allow-all for
    * local-dev workflows.
    */
  def aclValidator(
      aclCfg: AclConfig,
      mgrCfg: ManagerConfig,
      sup: PoolSupervisor
  ): StatementValidator =
    if !aclCfg.enabled then
      logger.warn("SQL ACL disabled (set quack-flightsql.acl.enabled=true to enforce).")
      StatementValidator.allowAll
    else
      val defaultDb     = mgrCfg.defaultMetastore.dbName
      val defaultSchema =
        if mgrCfg.defaultMetastore.schemaName.nonEmpty then mgrCfg.defaultMetastore.schemaName
        else "main"
      logger.info(
        s"SQL ACL enabled (RBAC effective-set, defaultDb=$defaultDb, defaultSchema=$defaultSchema)"
      )
      new PostgresAclValidator(
        defaultDatabase = defaultDb,
        defaultSchema = defaultSchema,
        dialect = aclCfg.dialect,
        // Scope wildcard catalog grants to the session's tenant. Maps
        // qodstate_tenant.id -> the set of tenant_db.name's the tenant
        // owns; the validator's `catalogMatch` consults this to decide
        // whether `*.*.*` admits a referenced catalog. Empty set = no
        // catalog matches via wildcard (fail-closed). Explicit named
        // grants bypass this and still cross tenants on purpose.
        tenantCatalogs = tenantId =>
          sup
            .getTenantById(tenantId)
            .map(t => sup.listTenantDbsByTenant(t.id).map(_.name).toSet)
            .getOrElse(Set.empty)
      )
