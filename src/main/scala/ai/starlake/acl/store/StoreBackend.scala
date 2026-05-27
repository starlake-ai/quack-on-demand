package ai.starlake.acl.store

import java.nio.file.Paths

/** Storage backend type, inferred from the base-path URI prefix.
  *
  * Pattern matching on this enum is exhaustive at compile time,
  * ensuring all backends are handled in the factory.
  */
enum StoreBackend:
  case Local(path: java.nio.file.Path)
  case S3(bucket: String, prefix: String)
  case Gcs(bucket: String, prefix: String)
  case Azure(container: String, prefix: String)

object StoreBackend:

  /** Parse a base-path URI into the appropriate backend.
    *
    * @param uri
    *   The base-path string from configuration
    * @return
    *   The inferred storage backend
    * @throws IllegalArgumentException
    *   if the URI scheme is recognized but malformed
    */
  def fromUri(uri: String): StoreBackend =
    if uri.startsWith("s3://") then
      parseCloudUri(uri, "s3://", S3.apply)
    else if uri.startsWith("gs://") then
      parseCloudUri(uri, "gs://", Gcs.apply)
    else if uri.startsWith("az://") then
      parseCloudUri(uri, "az://", Azure.apply)
    else
      Local(Paths.get(uri))

  private def parseCloudUri(
      uri: String,
      scheme: String,
      factory: (String, String) => StoreBackend
  ): StoreBackend =
    val withoutScheme = uri.stripPrefix(scheme)
    val slashIndex = withoutScheme.indexOf('/')
    val bucket = if slashIndex < 0 then withoutScheme else withoutScheme.substring(0, slashIndex)
    require(bucket.nonEmpty, s"Empty bucket/container name in URI: $uri")
    val prefix = if slashIndex < 0 then "" else withoutScheme.substring(slashIndex + 1).stripSuffix("/")
    factory(bucket, prefix)
