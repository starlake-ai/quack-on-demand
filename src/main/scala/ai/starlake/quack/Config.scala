package ai.starlake.quack

final case class K8sConfig(
    namespace: String,
    image: String,
    serviceAccount: Option[String],
    serviceType: String,
    quackPort: Int,
    startupTimeoutSec: Int,
    podLabel: String
)

final case class AdminConfig(
    // Comma-separated list of admin usernames. All get the same password +
    // role on seed. Stored as a single string so a single env var can
    // override (HOCON env-var substitution can't inject a list).
    username: String,
    password: String,
    role: String
):
  def usernameList: List[String] =
    username.split(",").iterator.map(_.trim).filter(_.nonEmpty).toList

final case class RoleDistributionConfig(writeonly: Int, readonly: Int, dual: Int)

final case class BootstrapConfig(
    enabled: Boolean,
    tenant: String,
    pool: String,
    roleDistribution: RoleDistributionConfig
)

final case class ManagerConfig(
    host: String,
    port: Int,
    apiKey: Option[String],
    runtimeType: String,
    minPort: Int,
    maxPort: Int,
    maxNodesTotal: Int,
    statePath: String,
    stateStorage: String,
    drainTimeoutSec: Int,
    healthCheckIntervalSec: Int,
    defaultMetastore: Map[String, String],
    admin: AdminConfig,
    k8s: K8sConfig,
    bootstrap: BootstrapConfig
)

final case class FlightConfig(
    host: String,
    port: Int,
    tlsEnabled: Boolean,
    tlsCertChain: String,
    tlsPrivateKey: String,
    tenantClaim: String,
    defaultTenant: String,
    defaultPool: String,
    sessionTtlSec: Long
)