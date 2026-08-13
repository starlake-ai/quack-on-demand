package ai.starlake.quack.ondemand.storage

import ai.starlake.quack.ManagedObjectStoreConfig
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  CreateBucketRequest,
  Delete,
  DeleteObjectsRequest,
  HeadBucketRequest,
  ListObjectsV2Request,
  NoSuchBucketException,
  ObjectIdentifier,
  S3Error
}

import java.net.URI
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** AWS SDK v2 [[S3Client]] impl of [[ManagedStoreClient]] for the operator root bucket. The client
  * is built lazily so wiring this up with `enabled=false` never touches the SDK.
  */
final class S3ManagedStoreClient(cfg: ManagedObjectStoreConfig) extends ManagedStoreClient:

  private lazy val client: S3Client =
    val builder = S3Client
      .builder()
      .region(Region.of(cfg.region))
      .credentialsProvider(
        StaticCredentialsProvider.create(
          AwsBasicCredentials.create(cfg.accessKeyId, cfg.secretAccessKey)
        )
      )
      .forcePathStyle(cfg.urlStyle == "path")
    if cfg.endpoint.nonEmpty then builder.endpointOverride(URI.create(cfg.endpoint)).build()
    else builder.build()

  def ensureBucket(): Either[String, Unit] =
    try
      client.headBucket(HeadBucketRequest.builder().bucket(cfg.bucket).build())
      Right(())
    catch
      case _: NoSuchBucketException =>
        try
          client.createBucket(CreateBucketRequest.builder().bucket(cfg.bucket).build())
          Right(())
        catch case NonFatal(e) => Left(s"ensureBucket: createBucket failed: ${e.getMessage}")
      case NonFatal(e) => Left(s"ensureBucket: headBucket failed: ${e.getMessage}")

  def listPrefix(prefix: String, max: Int): Either[String, List[String]] =
    try
      val req = ListObjectsV2Request
        .builder()
        .bucket(cfg.bucket)
        .prefix(prefix)
        .maxKeys(max)
        .build()
      val resp = client.listObjectsV2(req)
      Right(resp.contents().asScala.map(_.key()).toList)
    catch case NonFatal(e) => Left(s"listPrefix: ${e.getMessage}")

  def deleteBatch(keys: List[String]): Either[String, Unit] =
    if keys.isEmpty then Right(())
    else
      try
        val objectIds = keys.map(k => ObjectIdentifier.builder().key(k).build()).asJava
        val req       = DeleteObjectsRequest
          .builder()
          .bucket(cfg.bucket)
          .delete(Delete.builder().objects(objectIds).build())
          .build()
        val resp = client.deleteObjects(req)
        S3ManagedStoreClient.deleteOutcome(resp.errors().asScala.toList)
      catch case NonFatal(e) => Left(s"deleteBatch: ${e.getMessage}")

object S3ManagedStoreClient:

  /** DeleteObjects answers 200 with a per-key error list when some keys could not be removed (ACL,
    * object lock, transient server error). Reporting that as success would make the purge worker a
    * silent non-progress loop: the listing never empties, so the prefix is re-listed forever and no
    * `Left` ever reaches the sweep's warn path. Named errors are surfaced instead.
    */
  private[storage] def deleteOutcome(errors: List[S3Error]): Either[String, Unit] =
    errors match
      case Nil        => Right(())
      case first :: _ =>
        Left(
          s"deleteBatch: ${errors.size} key(s) failed, " +
            s"first: ${first.key()} (${first.code()})"
        )
