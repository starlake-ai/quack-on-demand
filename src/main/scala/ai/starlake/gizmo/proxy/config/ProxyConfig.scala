package ai.starlake.gizmo.proxy.config

import pureconfig.*
import pureconfig.generic.ProductHint

case class ProxyTlsConfig(
    enabled: Boolean,
    certChain: String,
    privateKey: String
) derives ConfigReader

case class ProxyServerConfig(
    host: String,
    port: Int,
    tls: ProxyTlsConfig
) derives ConfigReader

case class BackendTlsConfig(
    enabled: Boolean,
    trustedCertificates: String
) derives ConfigReader

case class BackendConfig(
    host: String,
    port: Int,
    tls: BackendTlsConfig,
    defaultUsername: String,
    defaultPassword: String
) derives ConfigReader

case class ValidationRulesConfig(
    allowByDefault: Boolean,
    // Comma-separated list of usernames that bypass SQL validation. Stored
    // as a single string so it can be overridden via env var (HOCON env-var
    // substitution can't inject a List). Use `bypassUsersList` to consume.
    bypassUsers: String,
    rulesFile: String
) derives ConfigReader:
  def bypassUsersList: List[String] =
    bypassUsers.split(",").iterator.map(_.trim).filter(_.nonEmpty).toList

case class ValidationConfig(
    enabled: Boolean,
    rules: ValidationRulesConfig
) derives ConfigReader

case class LoggingConfig(
    level: String,
    logStatements: Boolean,
    logValidation: Boolean
) derives ConfigReader

case class SessionConfig(
    gizmosqlUsername: String,
    gizmosqlPassword: String,
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

case class GizmoSqlProxyConfig(
    proxy: ProxyServerConfig,
    backend: BackendConfig,
    validation: ValidationConfig,
    acl: AclConfig,
    logging: LoggingConfig,
    session: SessionConfig,
    authentication: AuthenticationConfig
) derives ConfigReader

object ProxyConfig:
  def load(): GizmoSqlProxyConfig =
    ConfigSource.default
      .at("gizmosql-proxy")
      .loadOrThrow[GizmoSqlProxyConfig]
