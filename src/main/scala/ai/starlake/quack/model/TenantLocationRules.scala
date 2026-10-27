package ai.starlake.quack.model

import java.util.UUID

/** What a tenant may authorize for its users' file access, checked when a location is added (REST,
  * MCP, CLI, manifest import). Whether a statement may then use a URL or path is decided at use
  * time against the stored locations and the data-folder deny set; this object only decides what
  * may be stored.
  *
  *   - `s3://<bucket>/`: a bucket only, never a key path inside it, with the bucket's own
  *     credentials. Bucket names follow S3's rules; an uppercase bucket or scheme is refused rather
  *     than folded, since DuckDB matches secret scopes case-sensitively.
  *   - an absolute local folder under `<filesRoot>/<tenant id>/`, with no empty, `.` or `..`
  *     segment, stored with a trailing `/`; refused while the operator set no root
  *     (QOD_TENANT_FILES_ROOT).
  *
  * Refusals are `(code, message)`; no message carries an objectStore value.
  */
object TenantLocationRules:

  /** A location as it is stored. */
  final case class Accepted(kind: String, location: String)

  private val S3Url          = "^s3://([a-z0-9][a-z0-9.-]{1,61}[a-z0-9])/?$".r
  private val Region         = "^[a-z0-9-]{1,64}$".r
  private val Endpoint       = "^(https?://)?[A-Za-z0-9.-]+(:[0-9]{1,5})?/?$".r
  private val UrlStyles      = Set("path", "vhost")
  private val MaxValueLength = 4096

  /** `m` with each value trimmed: what a location stores. A pasted `" AKIA... "` would otherwise
    * pass the checks and be rendered with its spaces into the node secret, failing only at use.
    */
  def trimValues(m: Map[String, String]): Map[String, String] = m.map((k, v) => k -> v.trim)

  /** `<root>/<tenantId>/`. */
  def tenantRoot(root: String, tenantId: String): String =
    root.stripSuffix("/") + "/" + tenantId + "/"

  def validate(
      tenantId: String,
      raw: String,
      objectStore: Map[String, String],
      filesRoot: Option[String]
  ): Either[(String, String), Accepted] =
    val loc = raw.trim
    if loc.exists(_.isControl) then
      Left("invalid_location" -> "a location holds no control character")
    else if loc.startsWith("s3://") then s3(loc, objectStore)
    else if loc.startsWith("/") then local(tenantId, loc, objectStore, filesRoot)
    else
      Left(
        "invalid_location" -> ("a location is s3://<bucket>/ or an absolute folder under the " +
          "tenant files root; other schemes and relative paths are refused")
      )

  private def s3(
      loc: String,
      objectStore: Map[String, String]
  ): Either[(String, String), Accepted] =
    loc match
      case S3Url(bucket) if !bucket.contains("..") =>
        objectStoreError(objectStore).toLeft(Accepted(TenantLocation.S3, s"s3://$bucket/"))
      case _ =>
        Left(
          "invalid_location" -> ("an s3 location is a bucket only, s3://<bucket>/ (3 to 63 " +
            "lowercase letters, digits, '.' and '-'); a key path inside the bucket cannot be " +
            "authorized")
        )

  private def objectStoreError(m: Map[String, String]): Option[(String, String)] =
    val unknown = m.keySet -- TenantLocation.ObjectStoreKeys
    if unknown.nonEmpty then
      Some(
        "invalid_object_store" -> (s"unknown objectStore key(s) " +
          s"${unknown.toList.sorted.map(TenantDb.printableKey).mkString(", ")}; an s3 location " +
          s"takes ${TenantLocation.ObjectStoreKeys.toList.sorted.mkString(", ")}")
      )
    else
      TenantLocation.RequiredObjectStoreKeys.find(k => m.get(k).forall(_.trim.isEmpty)) match
        case Some(k) =>
          Some(
            "object_store_required" -> s"an s3 location needs the bucket's own credentials: $k is missing"
          )
        case None =>
          if m.values.exists(v => v.exists(_.isControl) || v.length > MaxValueLength) then
            Some(
              "invalid_object_store" ->
                "an objectStore value holds no control character and at most 4096 characters"
            )
          else if m.get("s3_url_style").exists(s => !UrlStyles.contains(s)) then
            Some("invalid_object_store" -> "s3_url_style is path or vhost")
          else if m.get("s3_region").exists(r => !Region.matches(r)) then
            Some("invalid_object_store" -> "s3_region is lowercase letters, digits and '-'")
          else if m.get("s3_endpoint").exists(e => !Endpoint.matches(e)) then
            Some(
              "invalid_object_store" ->
                "s3_endpoint is host[:port], optionally with http:// or https://, and no path"
            )
          else None

  private def local(
      tenantId: String,
      loc: String,
      objectStore: Map[String, String],
      filesRoot: Option[String]
  ): Either[(String, String), Accepted] =
    filesRoot match
      case None =>
        Left(
          "tenant_files_root_unset" ->
            "local folders can be authorized only once the operator sets QOD_TENANT_FILES_ROOT"
        )
      case Some(root) =>
        val body     = loc.stripPrefix("/")
        val segments =
          (if body.endsWith("/") then body.dropRight(1) else body).split("/", -1).toList
        if objectStore.nonEmpty then
          Left("invalid_object_store" -> "a local folder takes no objectStore")
        else if body.isEmpty || segments.exists(s => s.isEmpty || s == "." || s == "..") then
          Left(
            "invalid_location" -> "a local folder is an absolute path with no empty, '.' or '..' segment"
          )
        else
          val folder = segments.mkString("/", "/", "/")
          val under  = tenantRoot(root, tenantId)
          if folder.startsWith(under) then Right(Accepted(TenantLocation.Local, folder))
          else
            Left(
              "outside_tenant_files_root" -> s"a local folder of tenant '$tenantId' lies under $under"
            )

  /** A fresh location id (32 lowercase hex digits) whose first 8 differ from those of every id in
    * `taken`: a node names a location's secret `qod_tl_<first 8>`, and two locations of one tenant
    * must never share a secret name.
    */
  @annotation.tailrec
  def newId(
      taken: Iterable[String],
      fresh: () => String = () => UUID.randomUUID().toString.replace("-", "")
  ): String =
    val id = fresh()
    if taken.exists(_.take(8) == id.take(8)) then newId(taken, fresh) else id
