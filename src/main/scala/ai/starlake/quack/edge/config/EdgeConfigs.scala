package ai.starlake.quack.edge.config

import pureconfig.*

case class SessionConfig(
    slProjectId: String,
    slDataPath: String,
    pgUsername: String,
    pgPassword: String,
    pgPort: Int,
    pgHost: String,
    jwtSecretKey: String,
    aclTenant: String
) derives ConfigReader

case class AclWatcherConfig(
    enabled: Boolean,
    debounceMs: Long,
    maxBackoffMs: Long,
    pollIntervalMs: Long
) derives ConfigReader

case class AclS3Config(
    region: Option[String],
    credentialsFile: Option[String]
) derives ConfigReader

case class AclGcsConfig(
    projectId: Option[String],
    serviceAccountKeyFile: Option[String]
) derives ConfigReader

case class AclAzureConfig(
    connectionString: Option[String]
) derives ConfigReader

case class AclConfig(
    enabled: Boolean,
    basePath: String,
    dialect: String,
    groupsClaim: String,
    maxTenants: Int,
    watcher: AclWatcherConfig,
    s3: AclS3Config,
    gcs: AclGcsConfig,
    azure: AclAzureConfig
)

object AclConfig:
  // Explicit reader: default kebab-case derivation mangles "s3" into "s-3"
  given ConfigReader[AclConfig] = ConfigReader.forProduct9(
    "enabled", "base-path", "dialect", "groups-claim", "max-tenants",
    "watcher", "s3", "gcs", "azure"
  )(AclConfig.apply)
