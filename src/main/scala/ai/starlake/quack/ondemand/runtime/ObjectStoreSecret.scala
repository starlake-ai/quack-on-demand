package ai.starlake.quack.ondemand.runtime

import java.util.Locale

/** Authors the per-database object-store CREATE SECRET, single source of truth. Reads the tenant-db
  * `objectStore` map (keys per the UI's DataPathEditor: s3_*, azure_*, gcs_*) and emits
  * `qod_db_store`, scoped to the data path ([[dataScopes]]). No unscoped s3 secret exists on a node
  * (spec decision 23): a user's s3:// URL signs with that user's own connection secret, and the
  * data path with this one or the spawn scripts' `qod_data_s3`, which follows the same scopes.
  * Every s3 key is named, so DuckDB fills none from a session's settings. Empty map or a
  * non-object-store dataPath yields "" (`qod_data_s3` then signs the data path).
  */
object ObjectStoreSecret:

  /** The per-database object-store secret's name. */
  val SecretName = "qod_db_store"

  private def lit(v: String): String = "'" + v.replace("'", "''") + "'"

  private def scheme(dataPath: String): String =
    val i = dataPath.indexOf("://")
    if i < 0 then "" else dataPath.substring(0, i).toLowerCase(Locale.ROOT)

  /** The scopes of a database's own credentials for `dataPath`, the rule `qod_db_store` here and
    * `qod_data_s3` in the spawn scripts share:
    *   - a `duckdb-file` path names one file: itself (its `.wal` and `.tmp` begin with it). DuckDB
    *     matches a scope as a string prefix, so no scope can name the file alone: this one also
    *     reaches every name that begins with the file's (`x.duckdb2/...`). The deny set covers
    *     those names instead (DataFolders.NamePrefixMarker), so no user URL there is ever admitted;
    *   - a branch prefix (`<parent>__br_<id8>/`): the parent's prefix and its own, since the branch
    *     reads the parent's Parquet in place;
    *   - any other path is a directory: itself with one trailing `/`, and its branches' prefix
    *     `<path>__br_`, which a merge node (the parent's node shape) attaches beside it.
    *
    * No user's scope lies at or under any of these (UserS3CredentialRules at add, the location
    * checker at use), so for every data file one of them is the longest match. A branch of a
    * bucket-root database is the one shape left odd: its prefix `s3://<bucket>__br_<id8>/` names
    * another bucket (not a valid S3 bucket name, so the branch's writes fail).
    */
  def dataScopes(dataPath: String, kind: String): List[String] =
    if kind == ai.starlake.quack.model.TenantDbKind.DuckDbFile.wireValue then List(dataPath)
    else
      ai.starlake.quack.ondemand.branch.BranchNames.parentPrefix(dataPath) match
        case Some(parent) => List(parent + "/", dataPath.stripSuffix("/") + "/")
        case None         =>
          val base = dataPath.stripSuffix("/")
          List(base + "/", base + ai.starlake.quack.ondemand.branch.BranchNames.DbSuffixMarker)

  /** `kind` is the tenant-db's wire kind (`ducklake`, `duckdb-file`, `memory`): it decides whether
    * the data path is a file or a directory ([[dataScopes]]).
    */
  def sql(objectStore: Map[String, String], dataPath: String, kind: String): String =
    if objectStore.isEmpty then ""
    else
      val scopes = dataScopes(dataPath, kind)
      scheme(dataPath) match
        case "s3" | "s3a" | "r2"      => s3Secret(objectStore, scopes)
        case "gs"                     => gcsSecret(objectStore, scopes)
        case "az" | "azure" | "abfss" => azureSecret(objectStore, scopes)
        case _                        => ""

  private def scopeList(scopes: List[String]): String = scopes.map(lit).mkString("[", ", ", "]")

  /** Every key an s3 secret takes, named: DuckDB fills a missing one from the settings, a session's
    * included.
    */
  private def s3Options(m: Map[String, String], scopes: List[String]): List[String] =
    val region      = m.getOrElse("s3_region", "us-east-1")
    val urlStyle    = m.getOrElse("s3_url_style", "path")
    val rawEndpoint = m.get("s3_endpoint").map(_.trim).filter(_.nonEmpty)
    // http:// (e.g. a local MinIO) means TLS is off; https:// or no endpoint at all keeps the
    // secure default. No separate s3_use_ssl key: the endpoint scheme is the one signal.
    val useSsl   = !rawEndpoint.exists(_.startsWith("http://"))
    val endpoint =
      rawEndpoint.fold("")(_.stripPrefix("https://").stripPrefix("http://").stripSuffix("/"))
    List(
      "TYPE s3",
      s"KEY_ID ${lit(m.getOrElse("s3_access_key_id", ""))}",
      s"SECRET ${lit(m.getOrElse("s3_secret_access_key", ""))}",
      "SESSION_TOKEN ''",
      s"REGION ${lit(region)}",
      s"ENDPOINT ${lit(endpoint)}",
      s"URL_STYLE ${lit(urlStyle)}",
      s"USE_SSL $useSsl",
      "KMS_KEY_ID ''",
      "URL_COMPATIBILITY_MODE false",
      "REQUESTER_PAYS false",
      s"SCOPE ${scopeList(scopes)}"
    )

  private def s3Secret(m: Map[String, String], scopes: List[String]): String =
    "INSTALL httpfs;\nLOAD httpfs;\n" +
      s"CREATE OR REPLACE SECRET $SecretName (\n" +
      s3Options(m, scopes).map("  " + _).mkString(",\n") +
      "\n);"

  private def gcsSecret(m: Map[String, String], scopes: List[String]): String =
    "INSTALL httpfs;\nLOAD httpfs;\n" +
      s"CREATE OR REPLACE SECRET $SecretName (\n" +
      "  TYPE gcs,\n" +
      s"  KEY_ID ${lit(m.getOrElse("gcs_hmac_key_id", ""))},\n" +
      s"  SECRET ${lit(m.getOrElse("gcs_hmac_secret", ""))},\n" +
      s"  SCOPE ${scopeList(scopes)}\n" +
      ");"

  private def azureSecret(m: Map[String, String], scopes: List[String]): String =
    val account = m.getOrElse("azure_account", "")
    val key     = m.getOrElse("azure_account_key", "")
    val conn    =
      s"DefaultEndpointsProtocol=https;AccountName=$account;AccountKey=$key;EndpointSuffix=core.windows.net"
    "INSTALL azure;\nLOAD azure;\n" +
      s"CREATE OR REPLACE SECRET $SecretName (\n" +
      "  TYPE azure,\n" +
      s"  CONNECTION_STRING ${lit(conn)},\n" +
      s"  SCOPE ${scopeList(scopes)}\n" +
      ");"
