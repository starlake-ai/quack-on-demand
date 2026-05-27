package ai.starlake.acl.store

import ai.starlake.acl.AclError
import ai.starlake.acl.model.TenantId

import java.nio.file.{Files, Path}
import java.time.Instant
import scala.io.Source
import scala.jdk.StreamConverters.*
import scala.util.Using

/** AclStore implementation backed by the local filesystem.
  *
  * Each tenant has a folder under `basePath` containing YAML ACL files.
  * This implementation extracts the file I/O logic previously in TenantLoader.
  *
  * @param basePath
  *   Base directory containing tenant folders
  */
class LocalAclStore(basePath: Path) extends AclStore:

  override def tenantExists(tenantId: TenantId): Boolean =
    val tenantDir = basePath.resolve(tenantId.canonical)
    Files.isDirectory(tenantDir)

  override def listYamlFiles(tenantId: TenantId): Either[AclError, List[String]] =
    val tenantDir = basePath.resolve(tenantId.canonical)
    if !Files.isDirectory(tenantDir) then Left(AclError.TenantNotFound(tenantId.canonical))
    else
      try
        Right(
          Using.resource(Files.list(tenantDir)) { stream =>
            stream
              .toScala(List)
              .filter(Files.isRegularFile(_))
              .map(_.getFileName.toString)
              .filter(AclStore.isYamlFile)
              .sorted
          }
        )
      catch case e: Exception => Left(AclError.StoreError(s"Failed to list files: ${e.getMessage}", Some(e)))

  override def readFile(tenantId: TenantId, fileKey: String): Either[AclError, String] =
    val filePath = basePath.resolve(tenantId.canonical).resolve(fileKey)
    if !Files.isRegularFile(filePath) then
      Left(AclError.StoreError(s"File not found: ${tenantId.canonical}/$fileKey"))
    else
      try Right(Using.resource(Source.fromFile(filePath.toFile))(_.mkString))
      catch case e: Exception => Left(AclError.StoreError(s"Failed to read ${tenantId.canonical}/$fileKey: ${e.getMessage}", Some(e)))

  override def listTenants(): Either[AclError, List[TenantId]] =
    if !Files.isDirectory(basePath) then Right(List.empty)
    else
      try
        Right(
          Using.resource(Files.list(basePath)) { stream =>
            stream
              .toScala(List)
              .filter(Files.isDirectory(_))
              .map(_.getFileName.toString)
              .sorted
              .flatMap(name => TenantId.parse(name).toOption)
          }
        )
      catch case e: Exception => Left(AclError.StoreError(s"Failed to list tenants: ${e.getMessage}", Some(e)))

  override def listYamlFilesWithMetadata(tenantId: TenantId): Either[AclError, List[FileEntry]] =
    val tenantDir = basePath.resolve(tenantId.canonical)
    if !Files.isDirectory(tenantDir) then Left(AclError.TenantNotFound(tenantId.canonical))
    else
      try
        Right(
          Using.resource(Files.list(tenantDir)) { stream =>
            stream
              .toScala(List)
              .filter(Files.isRegularFile(_))
              .filter(p => AclStore.isYamlFile(p.getFileName.toString))
              .map { p =>
                val lastModified = try Some(Files.getLastModifiedTime(p).toInstant)
                catch case _: Exception => None
                FileEntry(p.getFileName.toString, lastModified)
              }
              .sortBy(_.name)
          }
        )
      catch case e: Exception => Left(AclError.StoreError(s"Failed to list files with metadata: ${e.getMessage}", Some(e)))

  override def close(): Unit = ()
