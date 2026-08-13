package ai.starlake.quack.ondemand.storage

/** Thin seam over the operator object store. No SDK types leak past here. */
trait ManagedStoreClient:
  def ensureBucket(): Either[String, Unit] // idempotent create-if-missing
  def listPrefix(
      prefix: String,
      max: Int
  ): Either[String, List[String]]                           // object KEYS under prefix, up to max
  def deleteBatch(keys: List[String]): Either[String, Unit] // <= 1000 keys per call

object ManagedPrefix:
  def id8(tenantDbId: String): String = tenantDbId.stripPrefix("td-").take(8)
  def dataPath(bucket: String, tenant: String, dbName: String, tenantDbId: String): String =
    s"s3://$bucket/${tenant}_$dbName-${id8(tenantDbId)}/"

  /** The object-key prefix inside the bucket (dataPath minus scheme+bucket). */
  def keyPrefix(tenant: String, dbName: String, tenantDbId: String): String =
    s"${tenant}_$dbName-${id8(tenantDbId)}/"
  def objectStoreFor(cfg: ai.starlake.quack.ManagedObjectStoreConfig): Map[String, String] =
    val base = Map(
      "s3_region"            -> cfg.region,
      "s3_url_style"         -> cfg.urlStyle,
      "s3_access_key_id"     -> cfg.accessKeyId,
      "s3_secret_access_key" -> cfg.secretAccessKey
    )
    if cfg.endpoint.nonEmpty then base.updated("s3_endpoint", cfg.endpoint) else base
