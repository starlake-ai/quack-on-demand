package ai.starlake.acl.policy

import ai.starlake.acl.AclError
import ai.starlake.acl.model.{AclPolicy, TenantId}
import ai.starlake.acl.store.{AclStore, LocalAclStore}
import cats.data.ValidatedNel
import cats.syntax.all.*
import com.typesafe.scalalogging.LazyLogging

import java.nio.file.Path

/** Folder-based ACL loader for multi-tenant deployments.
  *
  * Each tenant has its own folder containing YAML ACL files. The loader
  * validates folder existence, reads all YAML files, and delegates to
  * AclLoader for parsing and merging. Empty folders represent valid tenants
  * with deny-all policy.
  */
object TenantLoader extends LazyLogging:

  /** Load ACL policy for a tenant using an AclStore and system environment variables. */
  def load(store: AclStore, tenantId: TenantId): ValidatedNel[AclError, AclPolicy] =
    loadWithEnv(store, tenantId, name => Option(System.getenv(name)))

  /** Load ACL policy for a tenant using an AclStore with a custom environment resolver. */
  def loadWithEnv(
      store: AclStore,
      tenantId: TenantId,
      env: String => Option[String]
  ): ValidatedNel[AclError, AclPolicy] =
    val exists = store.tenantExists(tenantId)
    logger.debug(s"Tenant '${tenantId.canonical}': exists=$exists")
    if !exists then AclError.TenantNotFound(tenantId.canonical).invalidNel
    else
      store.listYamlFiles(tenantId) match
        case Left(err) =>
          logger.warn(s"Tenant '${tenantId.canonical}': listYamlFiles failed: ${err.message}")
          err.invalidNel
        case Right(yamlFiles) =>
          logger.info(s"Tenant '${tenantId.canonical}': found ${yamlFiles.size} YAML file(s): ${yamlFiles.mkString(", ")}")
          if yamlFiles.isEmpty then
            // Empty folder is valid - tenant exists with no grants (deny all)
            logger.warn(s"Tenant '${tenantId.canonical}': no YAML files found, using deny-all policy")
            AclPolicy(List.empty, ResolutionMode.Strict).validNel
          else
            val readResults = yamlFiles.map(f => store.readFile(tenantId, f))
            val firstError = readResults.collectFirst { case Left(err) => err }
            firstError match
              case Some(err) =>
                logger.error(s"Tenant '${tenantId.canonical}': failed to read file: ${err.message}")
                err.invalidNel
              case None =>
                val yamlContents = readResults.collect { case Right(content) => content }
                val result = AclLoader.loadAllWithEnv(yamlContents, env)
                result.fold(
                  errors => logger.error(s"Tenant '${tenantId.canonical}': YAML validation failed: ${errors.toList.map(_.message).mkString("; ")}"),
                  policy => logger.info(s"Tenant '${tenantId.canonical}': loaded ${policy.grants.size} grant(s), mode=${policy.mode}")
                )
                result

  /** Load ACL policy for a tenant using system environment variables.
    *
    * @deprecated Use load(store, tenantId) instead
    */
  @deprecated("Use load(store, tenantId) instead", "2.0")
  def load(basePath: Path, tenantId: TenantId): ValidatedNel[AclError, AclPolicy] =
    // Creates a new LocalAclStore per call. LocalAclStore.close() is a no-op,
    // so the unclosed instance is benign — no resources to leak.
    load(new LocalAclStore(basePath), tenantId)

  /** Load ACL policy for a tenant with a custom environment resolver.
    *
    * @deprecated Use loadWithEnv(store, tenantId, env) instead
    */
  @deprecated("Use loadWithEnv(store, tenantId, env) instead", "2.0")
  def loadWithEnv(
      basePath: Path,
      tenantId: TenantId,
      env: String => Option[String]
  ): ValidatedNel[AclError, AclPolicy] =
    loadWithEnv(new LocalAclStore(basePath), tenantId, env)
