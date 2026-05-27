package ai.starlake.acl.store

import ai.starlake.acl.watcher.WatcherConfig
import ai.starlake.gizmo.proxy.config.AclConfig
import com.typesafe.scalalogging.LazyLogging

/** Factory that creates the appropriate AclStore and AclChangeDetector
  * based on the `base-path` URI prefix in configuration.
  */
object AclStoreFactory extends LazyLogging:

  /** Create an AclStore and AclChangeDetector from configuration.
    *
    * The storage backend is inferred from the `basePath` prefix:
    * - `s3://...` → S3 via fs2-blobstore
    * - `gs://...` → GCS via fs2-blobstore
    * - `az://...` → Azure via fs2-blobstore
    * - anything else → local filesystem
    *
    * @return
    *   (AclStore, AclChangeDetector) - caller must close both when done
    */
  def create(config: AclConfig): (AclStore, AclChangeDetector) =
    StoreBackend.fromUri(config.basePath) match
      case StoreBackend.Local(path) =>
        val store = new LocalAclStore(path)
        val watcherConfig = WatcherConfig(
          debounceMs = config.watcher.debounceMs,
          maxBackoffMs = config.watcher.maxBackoffMs
        )
        val detector = new LocalChangeDetector(path, watcherConfig)
        logger.info(s"ACL store: local filesystem at $path")
        (store, detector)

      case StoreBackend.S3(bucket, prefix) =>
        createCloudStore(
          CloudAclStore.forS3(bucket, prefix, config.s3),
          s"S3 s3://$bucket/$prefix", s"S3 bucket=$bucket prefix=$prefix",
          config.watcher.pollIntervalMs
        )

      case StoreBackend.Gcs(bucket, prefix) =>
        createCloudStore(
          CloudAclStore.forGcs(bucket, prefix, config.gcs),
          s"GCS gs://$bucket/$prefix", s"GCS bucket=$bucket prefix=$prefix",
          config.watcher.pollIntervalMs
        )

      case StoreBackend.Azure(container, prefix) =>
        createCloudStore(
          CloudAclStore.forAzure(container, prefix, config.azure),
          s"Azure az://$container/$prefix", s"Azure container=$container prefix=$prefix",
          config.watcher.pollIntervalMs
        )

  /** Create cloud store with health check and polling detector.
    * Closes the store on health check failure to prevent resource leaks.
    */
  private def createCloudStore(
      store: AclStore,
      healthDesc: String,
      logDesc: String,
      pollIntervalMs: Long
  ): (AclStore, AclChangeDetector) =
    try
      store.listTenants() match
        case Right(_) =>
          logger.info(s"ACL store health check passed: $healthDesc")
        case Left(err) =>
          throw new IllegalStateException(
            s"ACL store health check failed for $healthDesc: ${err.message}. " +
              "Check credentials and permissions."
          )
      val detector = new PollingChangeDetector(store, pollIntervalMs)
      logger.info(s"ACL store: $logDesc")
      (store, detector)
    catch case e: Exception =>
      store.close()
      throw e
