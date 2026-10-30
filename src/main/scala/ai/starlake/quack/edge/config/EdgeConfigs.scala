package ai.starlake.quack.edge.config

import ai.starlake.quack.config.ConfigField
import pureconfig.*

import scala.annotation.meta.field

/** Node-lockdown knob (QOD_NODE_LOCKDOWN). When enabled, every statement from a non-superuser
  * caller is screened by [[ai.starlake.quack.edge.sql.LockdownScreen]] before the ACL validation
  * gate, denying ATTACH/DETACH/INSTALL/LOAD, protected settings, and local-path file functions.
  */
case class NodeLockdownConfig(
    @field @ConfigField(
      envVar = "QOD_NODE_LOCKDOWN",
      description =
        "Deny ATTACH/DETACH/INSTALL/LOAD and other node-escape statements for non-superuser callers."
    )
    enabled: Boolean
)

object NodeLockdownConfig:
  given ConfigReader[NodeLockdownConfig] = ConfigReader.forProduct1(
    "enabled"
  )(NodeLockdownConfig.apply)

/** Manager-wide OPA defaults for tenants that don't set their own
  * [[ai.starlake.quack.model.TenantAcl]]. `defaultMode` picks the authorization engine (QoD RBAC
  * grants vs. the tenant's OPA) when a tenant leaves its own mode unset; `url` is the fallback OPA
  * base address for opa-mode tenants that set none of their own.
  */
case class OpaConfig(
    @field @ConfigField(
      envVar = "QOD_ACL_MODE",
      description = "Default data-access authorization mode for tenants without their own: " +
        "qod (QoD RBAC grants) or opa (the tenant's Open Policy Agent is authoritative)."
    )
    defaultMode: String,
    @field @ConfigField(
      envVar = "QOD_OPA_URL",
      description = "Manager-wide OPA base URL used by opa-mode tenants that set none."
    )
    url: String,
    @field @ConfigField(
      envVar = "QOD_OPA_TIMEOUT_MS",
      description = "Per-request OPA timeout; on timeout the request is denied as unavailable."
    )
    timeoutMs: Int,
    @field @ConfigField(
      envVar = "QOD_OPA_CACHE_TTL_SEC",
      description = "Seconds an OPA allow/deny decision is cached (0 disables the cache)."
    )
    cacheTtlSec: Int
)
object OpaConfig:
  given ConfigReader[OpaConfig] =
    ConfigReader.forProduct4("defaultMode", "url", "timeoutMs", "cacheTtlSec")(OpaConfig.apply)

  val default: OpaConfig = OpaConfig("qod", "", 2000, 5)

  def validate(c: OpaConfig): Either[String, OpaConfig] =
    if !ai.starlake.quack.model.TenantAcl.ValidModes.contains(c.defaultMode) then
      Left(s"QOD_ACL_MODE must be one of qod, opa (got '${c.defaultMode}')")
    else if c.url.trim.nonEmpty && !ai.starlake.quack.model.TenantAcl.validUrl(c.url.trim) then
      // Never echo the value: a rejected URL may carry credentials.
      Left("QOD_OPA_URL must be an absolute http(s) URL without credentials")
    else if c.timeoutMs <= 0 then Left("QOD_OPA_TIMEOUT_MS must be > 0")
    else if c.cacheTtlSec < 0 then Left("QOD_OPA_CACHE_TTL_SEC must be >= 0")
    else Right(c.copy(url = c.url.trim))

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
