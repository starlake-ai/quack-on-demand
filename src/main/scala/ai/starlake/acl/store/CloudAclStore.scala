package ai.starlake.acl.store

import ai.starlake.acl.AclError
import ai.starlake.acl.model.TenantId
import ai.starlake.gizmo.proxy.config.{AclAzureConfig, AclGcsConfig, AclS3Config}
import blobstore.Store
import blobstore.url.{FsObject, Url}
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import cats.syntax.all.*
import com.typesafe.scalalogging.LazyLogging

/** AclStore implementation for cloud object stores (S3, GCS, Azure).
  *
  * Uses fs2-blobstore `Store[IO, B]` with type parameter `B <: FsObject` to
  * preserve access to blob metadata (lastModified, isDir) from list results.
  * All operations go through the blobstore Store trait — no NIO.
  *
  * Bridges effectful fs2-blobstore operations to the synchronous AclStore
  * interface via `IO.unsafeRunSync()` with a dedicated IORuntime, following
  * the same pattern as BlobAclStore.
  */
class CloudAclStore[B <: FsObject](
    store: Store[IO, B],
    baseUrl: Url.Plain,
    runtime: IORuntime,
    onClose: () => Unit
) extends AclStore
    with LazyLogging:

  // Same bridge pattern as BlobAclStore: dedicated IORuntime avoids deadlocks
  // in non-IOApp contexts (ScalaTest, gRPC handlers).
  private def run[A](io: IO[A]): A = io.unsafeRunSync()(using runtime)

  private val closed = new java.util.concurrent.atomic.AtomicBoolean(false)

  override def tenantExists(tenantId: TenantId): Boolean =
    try
      val url = tenantUrl(tenantId)
      run(
        store
          .list(url, recursive = false)
          .take(1)
          .compile
          .last
      ).isDefined
    catch case e: Exception =>
      logger.debug(s"tenantExists check failed for ${tenantId.canonical}: ${e.getMessage}")
      false

  override def listYamlFiles(tenantId: TenantId): Either[AclError, List[String]] =
    try
      val url = tenantUrl(tenantId)
      logger.debug(s"listYamlFiles: listing url=$url for tenant=${tenantId.canonical}")
      // Single API call: list first, check tenant existence only if empty
      val allItems = run(
        store
          .list(url, recursive = false)
          .compile
          .toList
      )
      logger.debug(s"listYamlFiles: ${allItems.size} item(s) found for tenant=${tenantId.canonical}")
      allItems.foreach { item =>
        logger.debug(s"  item: fileName=${item.path.fileName}, isDir=${item.path.isDir}, path=${item.path}")
      }
      if allItems.isEmpty then
        return Left(AclError.TenantNotFound(tenantId.canonical))

      val files = allItems
        .filter(!_.path.isDir)
        .map(_.path.fileName.getOrElse(""))
        .filter(AclStore.isYamlFile)
      logger.debug(s"listYamlFiles: ${files.size} YAML file(s) after filtering: ${files.mkString(", ")}")
      Right(files.sorted)
    catch case e: Exception =>
      Left(AclError.StoreError(s"Failed to list files for ${tenantId.canonical}: ${e.getMessage}", Some(e)))

  override def readFile(tenantId: TenantId, fileKey: String): Either[AclError, String] =
    try
      val url = fileUrl(tenantId, fileKey)
      val content = run(
        store
          .get(url, chunkSize = 8192)
          .through(fs2.text.utf8.decode)
          .compile
          .string
      )
      Right(content)
    catch case e: Exception =>
      Left(AclError.StoreError(s"Failed to read ${tenantId.canonical}/$fileKey: ${e.getMessage}", Some(e)))

  override def listTenants(): Either[AclError, List[TenantId]] =
    try
      // For cloud object stores, "directories" are virtual common prefixes.
      // dirName returns the last segment for dir-type paths.
      val tenants = run(
        store
          .list(baseUrl, recursive = false)
          .filter(_.path.isDir)
          .map(_.path.dirName.getOrElse("").stripSuffix("/"))
          .filter(_.nonEmpty)
          .compile
          .toList
      )
      Right(
        tenants.sorted.flatMap(name => TenantId.parse(name).toOption)
      )
    catch case e: Exception =>
      Left(AclError.StoreError(s"Failed to list tenants: ${e.getMessage}", Some(e)))

  override def listYamlFilesWithMetadata(tenantId: TenantId): Either[AclError, List[FileEntry]] =
    try
      val url = tenantUrl(tenantId)
      // Single API call: list all items, check tenant existence from result
      // Cloud list() returns Url[B] with FsObject metadata populated —
      // no separate stat() call needed (unlike BlobAclStore for local filesystem).
      val allItems = run(
        store
          .list(url, recursive = false)
          .compile
          .toList
      )
      if allItems.isEmpty then
        return Left(AclError.TenantNotFound(tenantId.canonical))

      val entries = allItems
        .filter(!_.path.isDir)
        .filter(u => AclStore.isYamlFile(u.path.fileName.getOrElse("")))
        .map { u =>
          val name = u.path.fileName.getOrElse("")
          val lastMod = u.path.lastModified
          FileEntry(name, lastMod)
        }
      Right(entries.sortBy(_.name))
    catch case e: Exception =>
      Left(AclError.StoreError(s"Failed to list files with metadata: ${e.getMessage}", Some(e)))

  override def close(): Unit =
    if closed.compareAndSet(false, true) then onClose()

  // URL construction: append tenant/file segments to the base URL path.
  // IMPORTANT: use `//` (not `/`) to ensure a trailing slash in the URL path.
  // Cloud APIs (S3, GCS, Azure) do prefix string matching, so listing with
  // prefix "tenant1" would also match "tenant10". The trailing slash ensures
  // we only list objects under "tenant1/".
  private def tenantUrl(tenantId: TenantId): Url.Plain =
    baseUrl `//` tenantId.canonical

  private def fileUrl(tenantId: TenantId, fileKey: String): Url.Plain =
    baseUrl / tenantId.canonical / fileKey

object CloudAclStore extends LazyLogging:

  /** Create a CloudAclStore backed by AWS S3. */
  def forS3(bucket: String, prefix: String, config: AclS3Config): CloudAclStore[blobstore.s3.S3Blob] =
    import blobstore.s3.S3Store
    import software.amazon.awssdk.services.s3.S3AsyncClient
    import software.amazon.awssdk.regions.Region

    val runtime = createRuntime("s3")
    val clientBuilder = S3AsyncClient.builder()
    config.region.foreach(r => clientBuilder.region(Region.of(r)))
    config.credentialsFile.foreach { file =>
      import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
      import software.amazon.awssdk.profiles.ProfileFile
      val profileFile = ProfileFile.builder()
        .content(java.nio.file.Paths.get(file))
        .`type`(ProfileFile.Type.CREDENTIALS)
        .build()
      clientBuilder.credentialsProvider(
        ProfileCredentialsProvider.builder()
          .profileFile(profileFile)
          .build()
      )
    }
    val s3Client = clientBuilder.build()
    try
      val s3Store = S3Store.builder[IO](s3Client).build.fold(
        errs => throw new RuntimeException(
          s"Failed to create S3 store: ${errs.toList.map(_.getMessage).mkString("; ")}"
        ),
        identity
      )
      val baseUrl = makeBaseUrl("s3", bucket, prefix)
      new CloudAclStore(s3Store, baseUrl, runtime, () => { s3Client.close(); runtime.shutdown() })
    catch case e: Exception =>
      s3Client.close()
      runtime.shutdown()
      throw e

  /** Create a CloudAclStore backed by Google Cloud Storage. */
  def forGcs(bucket: String, prefix: String, config: AclGcsConfig): CloudAclStore[blobstore.gcs.GcsBlob] =
    import blobstore.gcs.GcsStore
    import com.google.cloud.storage.StorageOptions
    import com.google.auth.oauth2.ServiceAccountCredentials

    val runtime = createRuntime("gcs")
    val optionsBuilder = StorageOptions.newBuilder()
    config.projectId.foreach(optionsBuilder.setProjectId)
    config.serviceAccountKeyFile.foreach { file =>
      optionsBuilder.setCredentials(
        ServiceAccountCredentials.fromStream(new java.io.FileInputStream(file))
      )
    }
    val storage = optionsBuilder.build().getService
    try
      val gcsStore = GcsStore.builder[IO](storage).build.fold(
        errs => throw new RuntimeException(
          s"Failed to create GCS store: ${errs.toList.map(_.getMessage).mkString("; ")}"
        ),
        identity
      )
      val baseUrl = makeBaseUrl("gs", bucket, prefix)
      new CloudAclStore(gcsStore, baseUrl, runtime, () => {
        try storage.close() catch case _: Exception => ()
        runtime.shutdown()
      })
    catch case e: Exception =>
      try storage.close() catch case _: Exception => ()
      runtime.shutdown()
      throw e

  /** Create a CloudAclStore backed by Azure Blob Storage. */
  def forAzure(container: String, prefix: String, config: AclAzureConfig): CloudAclStore[blobstore.azure.AzureBlob] =
    import blobstore.azure.AzureStore
    import com.azure.storage.blob.BlobServiceClientBuilder

    val runtime = createRuntime("azure")
    val connectionString = config.connectionString.getOrElse(
      throw new IllegalArgumentException(
        "Azure ACL storage requires ACL_AZURE_CONNECTION_STRING. " +
          "Set the connection string in configuration or environment."
      )
    )
    val asyncClient = new BlobServiceClientBuilder()
      .connectionString(connectionString)
      .buildAsyncClient()
    try
      val azureStore = AzureStore.builder[IO](asyncClient).build.fold(
        errs => throw new RuntimeException(
          s"Failed to create Azure store: ${errs.toList.map(_.getMessage).mkString("; ")}"
        ),
        identity
      )
      val baseUrl = makeBaseUrl("az", container, prefix)
      new CloudAclStore(azureStore, baseUrl, runtime, () => { runtime.shutdown() })
    catch case e: Exception =>
      runtime.shutdown()
      throw e

  // --- Private helpers ---

  /** Construct the base URL from scheme, bucket/container, and prefix. */
  private def makeBaseUrl(scheme: String, bucket: String, prefix: String): Url.Plain =
    if prefix.isEmpty then Url.unsafe(s"$scheme://$bucket")
    else Url.unsafe(s"$scheme://$bucket/$prefix")

  /** Create a dedicated IORuntime with CachedThreadPool (same pattern as BlobAclStore). */
  private def createRuntime(name: String): IORuntime =
    val exec = java.util.concurrent.Executors.newCachedThreadPool { r =>
      val t = new Thread(r, s"CloudAclStore-$name-worker")
      t.setDaemon(true)
      t
    }
    val ec = scala.concurrent.ExecutionContext.fromExecutorService(exec)
    val schedExec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r =>
      val t = new Thread(r, s"CloudAclStore-$name-scheduler")
      t.setDaemon(true)
      t
    }
    val scheduler = cats.effect.unsafe.Scheduler.fromScheduledExecutor(schedExec)
    cats.effect.unsafe.IORuntime(
      ec, ec, scheduler,
      () => { exec.shutdown(); schedExec.shutdown() },
      cats.effect.unsafe.IORuntimeConfig()
    )
