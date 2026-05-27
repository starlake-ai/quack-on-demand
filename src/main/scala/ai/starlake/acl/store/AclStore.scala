package ai.starlake.acl.store

import ai.starlake.acl.AclError
import ai.starlake.acl.model.TenantId

/** Abstraction for ACL file storage.
  *
  * Implementations may use local filesystem, S3, GCS, or Azure blob storage.
  * All methods are synchronous because the ACL subsystem is synchronous.
  * Errors are captured in `Either[AclError, A]` rather than thrown as exceptions.
  */
trait AclStore extends AutoCloseable:

  /** Check if a tenant directory exists. */
  def tenantExists(tenantId: TenantId): Boolean

  /** List YAML file names under a tenant directory, sorted alphabetically. */
  def listYamlFiles(tenantId: TenantId): Either[AclError, List[String]]

  /** Read a file's content as a UTF-8 string. */
  def readFile(tenantId: TenantId, fileKey: String): Either[AclError, String]

  /** List all tenant directories (direct children of the base path). */
  def listTenants(): Either[AclError, List[TenantId]]

  /** List YAML files with metadata for change detection.
    *
    * Default implementation delegates to `listYamlFiles` without `lastModified`.
    * Implementations should override to provide `lastModified` when available.
    */
  def listYamlFilesWithMetadata(tenantId: TenantId): Either[AclError, List[FileEntry]] =
    listYamlFiles(tenantId).map(_.map(name => FileEntry(name, lastModified = None)))

object AclStore:
  /** Check if a filename has a YAML extension (.yaml or .yml). */
  def isYamlFile(name: String): Boolean =
    val lower = name.toLowerCase
    lower.endsWith(".yaml") || lower.endsWith(".yml")
