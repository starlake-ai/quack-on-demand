package ai.starlake.quack.edge.config

import ai.starlake.quack.config.ConfigField
import pureconfig.*

import scala.annotation.meta.field

/** SQL ACL knobs. The pre-Phase-C file/cloud store path is dead --
  * [[ai.starlake.quack.edge.sql.PostgresAclValidator]] reads the cached
  * [[ai.starlake.quack.ondemand.rbac.EffectiveSet]] instead.
  */
case class AclConfig(
    @field @ConfigField(
      envVar = "QOD_ACL_ENABLED",
      description = "Enable table-level RBAC (per-statement EffectiveSet check)."
    )
    enabled: Boolean,
    @field @ConfigField(
      envVar = "QOD_ACL_DIALECT",
      description = "Statement parser dialect for ACL extraction."
    )
    dialect: String
)

object AclConfig:
  // kebab-case reader (matches application.conf) to dodge the
  // default mangling of "s3" / "gcs" style keys.
  given ConfigReader[AclConfig] = ConfigReader.forProduct2(
    "enabled",
    "dialect"
  )(AclConfig.apply)

/** Pre-statement SQL validation knobs. Loaded reflectively by the config-page registry; not wired
  * to runtime today. Kept as a typed class so the configurable env-var contract stays visible in
  * the admin UI.
  */
case class ValidationConfig(
    @field @ConfigField(
      envVar = "QOD_VALIDATION_ENABLED",
      description = "Enable per-statement SQL validation."
    )
    enabled: Boolean,
    @field @ConfigField(
      envVar = "QOD_VALIDATION_ALLOW_BY_DEFAULT",
      description = "When true, statements pass when no explicit rule matches."
    )
    allowByDefault: Boolean,
    @field @ConfigField(
      envVar = "QOD_VALIDATION_BYPASS_USERS",
      description = "Comma-separated usernames that skip SQL validation entirely."
    )
    bypassUsers: String
)

object ValidationConfig:
  given ConfigReader[ValidationConfig] = ConfigReader.forProduct3(
    "enabled",
    "allowByDefault",
    "bypassUsers"
  )(ValidationConfig.apply)
