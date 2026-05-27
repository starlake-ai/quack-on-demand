package ai.starlake.acl.store

import ai.starlake.acl.AclError
import ai.starlake.acl.model.TenantId

import java.time.Instant

/** In-memory AclStore for testing.
  *
  * @param files
  *   Map of tenantId.canonical -> (fileName -> content)
  * @param metadata
  *   Optional lastModified metadata per file
  */
class InMemoryAclStore(
    private var files: Map[String, Map[String, String]] = Map.empty,
    private var metadata: Map[String, Map[String, Instant]] = Map.empty
) extends AclStore:

  override def tenantExists(tenantId: TenantId): Boolean =
    files.contains(tenantId.canonical)

  override def listYamlFiles(tenantId: TenantId): Either[AclError, List[String]] =
    files.get(tenantId.canonical) match
      case Some(tenantFiles) =>
        Right(
          tenantFiles.keys.toList
            .filter(name => name.endsWith(".yaml") || name.endsWith(".yml"))
            .sorted
        )
      case None =>
        Left(AclError.TenantNotFound(tenantId.canonical))

  override def readFile(tenantId: TenantId, fileKey: String): Either[AclError, String] =
    files.get(tenantId.canonical).flatMap(_.get(fileKey)) match
      case Some(content) => Right(content)
      case None =>
        Left(AclError.StoreError(s"File not found: ${tenantId.canonical}/$fileKey"))

  override def listTenants(): Either[AclError, List[TenantId]] =
    Right(
      files.keys.toList.sorted.flatMap(name => TenantId.parse(name).toOption)
    )

  override def listYamlFilesWithMetadata(tenantId: TenantId): Either[AclError, List[FileEntry]] =
    listYamlFiles(tenantId).map { names =>
      val tenantMeta = metadata.getOrElse(tenantId.canonical, Map.empty)
      names.map(name => FileEntry(name, tenantMeta.get(name)))
    }

  override def close(): Unit = ()

  // Mutation methods for testing
  def addFile(tenantId: String, fileName: String, content: String): Unit =
    val tenantFiles = files.getOrElse(tenantId, Map.empty)
    files = files.updated(tenantId, tenantFiles.updated(fileName, content))

  def removeFile(tenantId: String, fileName: String): Unit =
    files.get(tenantId).foreach { tenantFiles =>
      files = files.updated(tenantId, tenantFiles.removed(fileName))
    }

  def addTenant(tenantId: String): Unit =
    if !files.contains(tenantId) then files = files.updated(tenantId, Map.empty)

  def removeTenant(tenantId: String): Unit =
    files = files.removed(tenantId)
    metadata = metadata.removed(tenantId)

  def setLastModified(tenantId: String, fileName: String, instant: Instant): Unit =
    val tenantMeta = metadata.getOrElse(tenantId, Map.empty)
    metadata = metadata.updated(tenantId, tenantMeta.updated(fileName, instant))
