package ai.starlake.quack.ondemand.runtime

/** Authors the per-database object-store CREATE SECRET, single source of truth (like NodeLockdown).
  * Reads the tenant-db `objectStore` map (keys per the UI's DataPathEditor: s3_*, azure_*, gcs_*)
  * and emits a SCOPE'd DuckDB secret named `qod_db_store`, distinct from the global
  * `quack_s3`/`quack_azure` so both coexist (DuckDB picks the longest matching scope, so the scoped
  * per-db secret wins for its bucket while the global stays the default). Empty map or a
  * non-object-store dataPath yields "" (the global-env path is used, unchanged).
  */
object ObjectStoreSecret:
  private val SecretName = "qod_db_store"

  private def lit(v: String): String = "'" + v.replace("'", "''") + "'"

  private def scheme(dataPath: String): String =
    val i = dataPath.indexOf("://")
    if i < 0 then "" else dataPath.substring(0, i).toLowerCase

  def sql(objectStore: Map[String, String], dataPath: String): String =
    if objectStore.isEmpty then ""
    else
      scheme(dataPath) match
        case "s3" | "s3a" | "r2"      => s3Secret(objectStore, dataPath)
        case "gs"                     => gcsSecret(objectStore, dataPath)
        case "az" | "azure" | "abfss" => azureSecret(objectStore, dataPath)
        case _                        => ""

  private def s3Secret(m: Map[String, String], scope: String): String =
    val region      = m.getOrElse("s3_region", "us-east-1")
    val urlStyle    = m.getOrElse("s3_url_style", "path")
    val rawEndpoint = m.get("s3_endpoint").map(_.trim).filter(_.nonEmpty)
    // http:// (e.g. a local MinIO) means TLS is off; https:// or no endpoint at all keeps the
    // secure default. No separate s3_use_ssl key: the endpoint scheme is the one signal.
    val useSsl   = !rawEndpoint.exists(_.startsWith("http://"))
    val endpoint = rawEndpoint
      .map { e =>
        val stripped = e.stripPrefix("https://").stripPrefix("http://").stripSuffix("/")
        s"  ENDPOINT ${lit(stripped)},\n"
      }
      .getOrElse("")
    "INSTALL httpfs; LOAD httpfs;\n" +
      s"CREATE OR REPLACE SECRET $SecretName (\n" +
      "  TYPE s3,\n" +
      s"  KEY_ID ${lit(m.getOrElse("s3_access_key_id", ""))},\n" +
      s"  SECRET ${lit(m.getOrElse("s3_secret_access_key", ""))},\n" +
      s"  REGION ${lit(region)},\n" +
      endpoint +
      s"  URL_STYLE ${lit(urlStyle)},\n" +
      s"  USE_SSL $useSsl,\n" +
      s"  SCOPE ${lit(scope)}\n" +
      ");"

  private def gcsSecret(m: Map[String, String], scope: String): String =
    "INSTALL httpfs; LOAD httpfs;\n" +
      s"CREATE OR REPLACE SECRET $SecretName (\n" +
      "  TYPE gcs,\n" +
      s"  KEY_ID ${lit(m.getOrElse("gcs_hmac_key_id", ""))},\n" +
      s"  SECRET ${lit(m.getOrElse("gcs_hmac_secret", ""))},\n" +
      s"  SCOPE ${lit(scope)}\n" +
      ");"

  private def azureSecret(m: Map[String, String], scope: String): String =
    val account = m.getOrElse("azure_account", "")
    val key     = m.getOrElse("azure_account_key", "")
    val conn    =
      s"DefaultEndpointsProtocol=https;AccountName=$account;AccountKey=$key;EndpointSuffix=core.windows.net"
    "INSTALL azure; LOAD azure;\n" +
      s"CREATE OR REPLACE SECRET $SecretName (\n" +
      "  TYPE azure,\n" +
      s"  CONNECTION_STRING ${lit(conn)},\n" +
      s"  SCOPE ${lit(scope)}\n" +
      ");"
