package ai.starlake.quack.ondemand.demo

import ai.starlake.quack.ManagedObjectStoreConfig
import ai.starlake.quack.ondemand.storage.ManagedStoreClient

import scala.annotation.tailrec

/** The demo's S3 settings, read from what the CLI hands the jar (`QOD_DEMO_S3_PATH`,
  * `QOD_DEMO_S3_LOCAL`, `QOD_S3_*`), and everything the demo does to that store.
  *
  * `runFolder` always ends in `qod-demo-<8 hex digits>`, which [[DemoS3.fromEnv]] enforces: that
  * folder is the only thing [[deleteRun]] can delete, even when someone sets the variable by hand.
  * Nothing here reaches `ManagerConfig`, so managed storage stays off.
  */
final case class DemoS3(
    runFolder: String,
    bucket: String,
    runKeyPrefix: String,
    local: Boolean,
    endpoint: String,
    region: String,
    accessKeyId: String,
    secretAccessKey: String,
    urlStyle: String,
    useSsl: Boolean
):

  override def toString: String = s"DemoS3($runFolder, local=$local)"

  /** One level inside the run folder: a row with no path derives its folder by replacing the last
    * segment of this (`PoolSupervisor.replaceLastSegment`), so the run folder itself would put the
    * data beside it, out of reach of [[deleteRun]].
    */
  def defaultDataPath: String = s"$runFolder/data"

  def storeConfig: ManagedObjectStoreConfig =
    ManagedObjectStoreConfig(
      endpoint =
        if endpoint.isEmpty then "" else s"${if useSsl then "https" else "http"}://$endpoint",
      region = region,
      bucket = bucket,
      accessKeyId = accessKeyId,
      secretAccessKey = secretAccessKey,
      urlStyle = urlStyle
    )

  /** Mask both keys. An empty key is skipped: replacing "" would insert the mask everywhere. */
  def redact(text: String): String =
    List(secretAccessKey, accessKeyId)
      .filter(_.nonEmpty)
      .foldLeft(text)((acc, k) => acc.replace(k, "***"))

  /** What `DemoSeed` runs before the ATTACH. Not `DuckLakeInitializer.storageSqlFor`, whose
    * `loadSql` puts two statements on one line: the CLI skips the rest of a line after a failure.
    * The secret has no ENDPOINT clause when [[endpoint]] is empty (AWS): an empty `ENDPOINT ''`
    * breaks seeding there, and without the clause DuckDB uses AWS's default endpoint.
    */
  def seedStorageSql: List[String] =
    def q(s: String) = "'" + s.replace("'", "''") + "'"
    val endpointLine = if endpoint.isEmpty then "" else s"  ENDPOINT ${q(endpoint)},\n"
    List(
      "INSTALL httpfs",
      "LOAD httpfs",
      s"""CREATE OR REPLACE TEMPORARY SECRET qod_demo_s3 (
         |  TYPE s3,
         |  KEY_ID ${q(accessKeyId)},
         |  SECRET ${q(secretAccessKey)},
         |  SESSION_TOKEN '',
         |  REGION ${q(region)},
         |$endpointLine  URL_STYLE ${q(urlStyle)},
         |  USE_SSL $useSsl,
         |  SCOPE ${q(s"$runFolder/")}
         |)""".stripMargin
    )

  def bannerNotes: List[String] =
    if local then List("S3: a throwaway local store on 127.0.0.1, deleted on exit.")
    else
      List(
        "S3: your bucket. Objects under this path are deleted on exit; a killed demo leaves them.",
        "Listening on 127.0.0.1 only, as the demo's logins are public.",
        "Use a dedicated bucket and key for this demo."
      )

  /** Ready the store for a run. Local mode creates the bucket, retrying as the store may answer
    * before its filer is ready. Own-bucket mode never creates one: it writes and deletes one
    * marker, so a missing bucket or bad keys fail here, before seeding, with a readable cause.
    */
  def prepare(
      client: ManagedStoreClient,
      put: String => Either[String, Unit],
      nowMs: () => Long = () => System.currentTimeMillis(),
      sleep: Long => Unit = Thread.sleep
  ): Either[String, Unit] =
    if local then
      val deadline = nowMs() + DemoS3.BucketBudgetMs
      @tailrec
      def attempt(): Either[String, Unit] =
        client.ensureBucket() match
          case Right(())                       => Right(())
          case Left(err) if nowMs() > deadline =>
            Left(redact(s"could not create bucket $bucket on the local store: $err"))
          case Left(_) =>
            sleep(DemoS3.BucketRetryMs)
            attempt()
      attempt()
    else
      val marker = s"$runKeyPrefix.qod-demo-write-check"
      put(marker)
        .flatMap(_ => client.deleteBatch(List(marker)))
        .left
        .map(err =>
          redact(
            s"cannot use s3://$bucket: $err. Check that the bucket exists, and the keys and region."
          )
        )

  /** Delete the run folder's objects: list, check, delete, until a listing comes back empty,
    * bounded by a time budget. A listed key outside the run folder aborts before anything is
    * deleted. Returns how many objects went.
    */
  def deleteRun(
      client: ManagedStoreClient,
      nowMs: () => Long = () => System.currentTimeMillis()
  ): Either[String, Int] =
    val deadline = nowMs() + DemoS3.DeleteBudgetMs
    @tailrec
    def loop(deleted: Int): Either[String, Int] =
      client.listPrefix(runKeyPrefix, 1000) match
        case Left(err)   => Left(err)
        case Right(Nil)  => Right(deleted)
        case Right(keys) =>
          keys.find(!_.startsWith(runKeyPrefix)) match
            case Some(stray) =>
              Left(s"refusing to delete: the listing holds $stray, outside $runKeyPrefix")
            case None if nowMs() > deadline =>
              Left(s"ran out of time after deleting $deleted objects")
            case None =>
              client.deleteBatch(keys) match
                case Left(err) => Left(err)
                case Right(()) => loop(deleted + keys.size)
    loop(0)

object DemoS3:

  val PathVar  = "QOD_DEMO_S3_PATH"
  val LocalVar = "QOD_DEMO_S3_LOCAL"

  private val BucketBudgetMs = 30000L
  private val BucketRetryMs  = 500L
  private val DeleteBudgetMs = 60000L

  // Segments are limited to characters every reader of the path (DuckDB's quoted DATA_PATH, the
  // spawn scripts, S3 keys) takes as they are.
  private val RunFolder =
    """^s3://([a-z0-9][a-z0-9.\-]{1,61}[a-z0-9])/((?:[A-Za-z0-9._\-]+/)*)(qod-demo-[0-9a-f]{8})/?$""".r

  /** The spawn scripts' reading of `QOD_S3_ENDPOINT`: a leading `http://` or `https://` and one
    * trailing `/` dropped, leaving `host[:port]`.
    */
  private def bareEndpoint(raw: String): String =
    raw.stripPrefix("http://").stripPrefix("https://").stripSuffix("/")

  def fromEnv(env: String => Option[String]): Either[String, Option[DemoS3]] =
    def value(k: String) = env(k).map(_.trim).filter(_.nonEmpty)
    value(PathVar) match
      case None       => Right(None)
      case Some(path) =>
        for
          parts <- path match
            case RunFolder(bucket, prefix, leaf)
                if !prefix.split('/').exists(s => s == "." || s == "..") =>
              Right((bucket, prefix, leaf))
            case _ =>
              Left(s"$PathVar must be s3://<bucket>/[<prefix>/]qod-demo-<8 hex digits>: $path")
          key <- value("QOD_S3_ACCESS_KEY_ID").toRight(
            s"$PathVar needs QOD_S3_ACCESS_KEY_ID and QOD_S3_SECRET_ACCESS_KEY: QOD_S3_ACCESS_KEY_ID is empty"
          )
          secret <- value("QOD_S3_SECRET_ACCESS_KEY").toRight(
            s"$PathVar needs QOD_S3_ACCESS_KEY_ID and QOD_S3_SECRET_ACCESS_KEY: QOD_S3_SECRET_ACCESS_KEY is empty"
          )
          useSsl <- value("QOD_S3_USE_SSL").getOrElse("true").toLowerCase match
            case "true"  => Right(true)
            case "false" => Right(false)
            case _       => Left("QOD_S3_USE_SSL must be true or false")
          urlStyle <- value("QOD_S3_URL_STYLE").getOrElse("path") match
            case s @ ("path" | "vhost") => Right(s)
            case _                      => Left("QOD_S3_URL_STYLE must be path or vhost")
        yield
          val (bucket, prefix, leaf) = parts
          Some(
            DemoS3(
              runFolder = s"s3://$bucket/$prefix$leaf",
              bucket = bucket,
              runKeyPrefix = s"$prefix$leaf/",
              local = env(LocalVar).contains("1"),
              endpoint = value("QOD_S3_ENDPOINT").map(bareEndpoint).getOrElse(""),
              region = value("QOD_S3_REGION").getOrElse("us-east-1"),
              accessKeyId = key,
              secretAccessKey = secret,
              urlStyle = urlStyle,
              useSsl = useSsl
            )
          )
