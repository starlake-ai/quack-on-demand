package ai.starlake.quack.model

/** An S3 bucket name where a user names one (a credential's scope, a statement's `s3://` location):
  * S3's naming rules, lowercase (DuckDB matches secret scopes case-sensitively), and never an
  * access point alias. S3 serves a bucket's objects under the name of an access point alias too, so
  * a location spelled with an alias would reach a bucket, a data folder's included, under a name no
  * rule compares with the bucket's own.
  */
object S3BucketName:

  private val BucketRe = "^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$".r

  /** The suffixes S3 reserves for aliases: access points (`-s3alias`), Object Lambda access points
    * (`--ol-s3`) and Multi-Region Access Points (`.mrap`). No bucket can be created with one.
    */
  val AliasSuffixes: List[String] = List("-s3alias", "--ol-s3", ".mrap")

  /** Whether `bucket` follows S3's rules, lowercase, with no `..`, and is no alias. */
  def valid(bucket: String): Boolean =
    BucketRe.matches(bucket) && !bucket.contains("..") && !isAlias(bucket)

  /** Whether `bucket` ends in a suffix S3 reserves for an alias ([[AliasSuffixes]]). */
  def isAlias(bucket: String): Boolean = AliasSuffixes.exists(bucket.endsWith)
