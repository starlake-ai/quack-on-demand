package ai.starlake.quack.model

/** One location a tenant authorized for its non-superuser users' file access (in-engine
  * authorization, decisions 15 and 18; `qodstate_tenant_location`, Liquibase 0051): an S3 bucket
  * with its own credentials, or a local folder under the operator's root. `location` is stored as
  * [[TenantLocationRules]] normalized it: `s3://<bucket>/`, or an absolute folder ending in `/`.
  * `objectStore` (s3 only) holds a tenant-db objectStore's s3 keys and becomes the node secret
  * `qod_tl_<first 8 of id>` (TenantLocationSecrets). `createdAt` is epoch milliseconds.
  */
final case class TenantLocation(
    id: String,
    tenantId: String,
    kind: String,
    location: String,
    objectStore: Map[String, String],
    createdAt: Long,
    createdBy: Option[String]
):
  /** Prints the secret as [[FederatedSecret.RedactedMarker]], so no log line built from a location
    * carries it. Equality and `copy` are untouched.
    */
  override def toString: String =
    s"TenantLocation(id=$id,tenantId=$tenantId,kind=$kind,location=$location," +
      s"objectStore=${TenantLocation.redacted(objectStore)},createdAt=$createdAt," +
      s"createdBy=$createdBy)"

object TenantLocation:
  val S3: String    = "s3"
  val Local: String = "local"

  /** The objectStore keys no API response carries and a manifest export redacts. */
  val SecretKeys: Set[String] = Set("s3_secret_access_key")

  /** Every key an s3 location's objectStore may hold: the s3 keys ObjectStoreSecret renders. */
  val ObjectStoreKeys: Set[String] = Set(
    "s3_access_key_id",
    "s3_secret_access_key",
    "s3_region",
    "s3_endpoint",
    "s3_url_style"
  )

  /** A bucket is reached only with credentials of its own: an entry without them is refused. */
  val RequiredObjectStoreKeys: List[String] = List("s3_access_key_id", "s3_secret_access_key")

  /** `m` with each secret value replaced by [[FederatedSecret.RedactedMarker]]. */
  def redacted(m: Map[String, String]): Map[String, String] =
    m.map((k, v) => if SecretKeys.contains(k) then k -> FederatedSecret.RedactedMarker else k -> v)

  /** `m` without its secret keys: what an API response carries. */
  def withoutSecrets(m: Map[String, String]): Map[String, String] =
    m.filterNot((k, _) => SecretKeys.contains(k))
