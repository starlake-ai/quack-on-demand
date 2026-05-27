package ai.starlake.acl.store

import ai.starlake.acl.AclError
import ai.starlake.acl.model.TenantId
import blobstore.fs.FileStore
import blobstore.url.Path as BlobPath
import cats.effect.IO
import cats.effect.unsafe.IORuntime

import java.nio.file.{Path as NioPath}

/** AclStore implementation backed by fs2-blobstore.
  *
  * Uses `IO.unsafeRunSync()` with a dedicated IORuntime to bridge effectful
  * fs2-blobstore operations to the synchronous AclStore interface.
  *
  * @param fileStore
  *   fs2-blobstore FileStore instance
  * @param basePath
  *   Base path under which tenant folders are located
  * @param runtime
  *   Dedicated IORuntime for running IO effects synchronously
  */
class BlobAclStore(
    fileStore: FileStore[IO],
    basePath: NioPath,
    runtime: IORuntime
) extends AclStore:

  // Uses IO.unsafeRunSync() with a dedicated IORuntime instead of Dispatcher[IO].
  // Dispatcher.sequential[IO].allocated.unsafeRunSync() deadlocks in non-IOApp contexts
  // (ScalaTest, gRPC handlers) because the cats-effect work-stealing pool blocks.
  // A dedicated IORuntime with CachedThreadPool avoids this issue.
  private def run[A](io: IO[A]): A = io.unsafeRunSync()(using runtime)

  override def tenantExists(tenantId: TenantId): Boolean =
    try
      val tenantDir = basePath.resolve(tenantId.canonical)
      java.nio.file.Files.isDirectory(tenantDir)
    catch case _: Exception => false

  override def listYamlFiles(tenantId: TenantId): Either[AclError, List[String]] =
    try
      val tenantDir = basePath.resolve(tenantId.canonical)
      if !java.nio.file.Files.isDirectory(tenantDir) then
        return Left(AclError.TenantNotFound(tenantId.canonical))

      val path = BlobPath(tenantDir.toString)
      val files = run(
        fileStore
          .list(path, recursive = false)
          .filter(!_.isDir)
          .map(_.fileName.getOrElse(""))
          .filter(AclStore.isYamlFile)
          .compile
          .toList
      )
      Right(files.sorted)
    catch case e: Exception =>
      Left(AclError.StoreError(s"Failed to list files for ${tenantId.canonical}: ${e.getMessage}", Some(e)))

  override def readFile(tenantId: TenantId, fileKey: String): Either[AclError, String] =
    try
      val filePath = basePath.resolve(tenantId.canonical).resolve(fileKey)
      val path = BlobPath(filePath.toString)
      val content = run(
        fileStore
          .get(path, chunkSize = 8192)
          .through(fs2.text.utf8.decode)
          .compile
          .string
      )
      Right(content)
    catch case e: Exception =>
      Left(AclError.StoreError(s"Failed to read ${tenantId.canonical}/$fileKey: ${e.getMessage}", Some(e)))

  override def listTenants(): Either[AclError, List[TenantId]] =
    // Use NIO for directory listing since FileStore.list may not return directories.
    // For cloud backends (S3/GCS/Azure), this would use the blob store's list instead.
    try
      import scala.jdk.StreamConverters.*
      import scala.util.Using
      Right(
        Using.resource(java.nio.file.Files.list(basePath)) { stream =>
          stream
            .toScala(List)
            .filter(java.nio.file.Files.isDirectory(_))
            .map(_.getFileName.toString)
            .sorted
            .flatMap(name => TenantId.parse(name).toOption)
        }
      )
    catch case e: Exception =>
      Left(AclError.StoreError(s"Failed to list tenants: ${e.getMessage}", Some(e)))

  override def listYamlFilesWithMetadata(tenantId: TenantId): Either[AclError, List[FileEntry]] =
    try
      val tenantDir = basePath.resolve(tenantId.canonical)
      if !java.nio.file.Files.isDirectory(tenantDir) then
        return Left(AclError.TenantNotFound(tenantId.canonical))

      val path = BlobPath(tenantDir.toString)
      val entries = run(
        fileStore
          .list(path, recursive = false)
          .filter(!_.isDir)
          .filter(p => AclStore.isYamlFile(p.fileName.getOrElse("")))
          .evalMap { p =>
            fileStore.stat(p).map { blobOpt =>
              val name = p.fileName.getOrElse("")
              val lastMod = blobOpt.flatMap(_.lastModified)
              FileEntry(name, lastMod)
            }
          }
          .compile
          .toList
      )
      Right(entries.sortBy(_.name))
    catch case e: Exception =>
      Left(AclError.StoreError(s"Failed to list files with metadata: ${e.getMessage}", Some(e)))

  override def close(): Unit =
    runtime.shutdown()

object BlobAclStore:

  /** Create a BlobAclStore backed by the local filesystem via fs2-blobstore FileStore.
    *
    * Creates a dedicated IORuntime with a cached thread pool to avoid
    * deadlocks in non-IOApp contexts (ScalaTest, gRPC handlers).
    */
  def forLocalFs(basePath: NioPath): BlobAclStore =
    val exec = java.util.concurrent.Executors.newCachedThreadPool { r =>
      val t = new Thread(r, "BlobAclStore-worker")
      t.setDaemon(true)
      t
    }
    val ec = scala.concurrent.ExecutionContext.fromExecutorService(exec)
    val schedExec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r =>
      val t = new Thread(r, "BlobAclStore-scheduler")
      t.setDaemon(true)
      t
    }
    val scheduler = cats.effect.unsafe.Scheduler.fromScheduledExecutor(schedExec)
    val rt = cats.effect.unsafe.IORuntime(
      ec, ec, scheduler,
      () => { exec.shutdown(); schedExec.shutdown() },
      cats.effect.unsafe.IORuntimeConfig()
    )

    import fs2.io.file.Files.implicitForAsync
    val store = FileStore[IO]
    new BlobAclStore(store, basePath, rt)
