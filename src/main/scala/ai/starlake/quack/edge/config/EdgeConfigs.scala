package ai.starlake.quack.edge.config

import pureconfig.*

/** SQL ACL knobs. The pre-Phase-C file/cloud store path is dead --
  * [[ai.starlake.quack.edge.sql.PostgresAclValidator]] reads the cached
  * [[ai.starlake.quack.ondemand.rbac.EffectiveSet]] instead. */
case class AclConfig(
    enabled: Boolean,
    dialect: String
)

object AclConfig:
  // kebab-case reader (matches application.conf) to dodge the
  // default mangling of "s3" / "gcs" style keys.
  given ConfigReader[AclConfig] = ConfigReader.forProduct2(
    "enabled", "dialect"
  )(AclConfig.apply)
