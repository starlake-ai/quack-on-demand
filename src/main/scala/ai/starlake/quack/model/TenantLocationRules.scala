package ai.starlake.quack.model

import java.util.{Locale, UUID}

/** What a tenant may authorize for its users' file access, checked when a location is added (REST,
  * MCP, CLI, manifest import): an absolute local folder under `<filesRoot>/<tenant id>/`, with no
  * empty, `.` or `..` segment, stored with a trailing `/`; refused while the operator set no root
  * (QOD_TENANT_FILES_ROOT). Whether a statement may then use a path is decided at use time against
  * the stored folders and the data-folder deny set; this object only decides what may be stored. An
  * `s3://` location is refused: S3 access is per user (decision 23); so is a folder holding a '%',
  * a glob character or a backslash, which the location checker could never admit a path under.
  *
  * Refusals are `(code, message)`.
  */
object TenantLocationRules:

  /** A location as it is stored. */
  final case class Accepted(kind: String, location: String)

  /** Why an `s3://` location is refused. */
  val S3IsPerUser: String =
    "S3 access is per user: each user registers keys of their own for what they reach " +
      "(qod profile s3 add); a tenant authorizes local folders only"

  /** What a stored folder never holds: the location checker refuses a path with a percent-escape or
    * a backslash, and reads a glob character as a glob, never as a literal folder name.
    */
  private val Unmatchable = "%*?[]{}\\"

  /** `<root>/<tenantId>/`. */
  def tenantRoot(root: String, tenantId: String): String =
    root.stripSuffix("/") + "/" + tenantId + "/"

  def validate(
      tenantId: String,
      raw: String,
      filesRoot: Option[String]
  ): Either[(String, String), Accepted] =
    val loc = raw.trim
    if loc.exists(_.isControl) then
      Left("invalid_location" -> "a location holds no control character")
    else if loc.toLowerCase(Locale.ROOT).startsWith("s3://") then
      Left("invalid_location" -> S3IsPerUser)
    else if loc.startsWith("/") then local(tenantId, loc, filesRoot)
    else
      Left(
        "invalid_location" -> ("a location is an absolute folder under the tenant files root; " +
          "other schemes and relative paths are refused")
      )

  private def local(
      tenantId: String,
      loc: String,
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
        if body.isEmpty || segments.exists(s => s.isEmpty || s == "." || s == "..") then
          Left(
            "invalid_location" -> "a local folder is an absolute path with no empty, '.' or '..' segment"
          )
        else if body.exists(c => Unmatchable.indexOf(c) >= 0) then
          Left(
            "invalid_location" -> ("a local folder holds no '%', glob character ('*', '?', '[', " +
              "']', '{', '}') or backslash: the location checker could never admit a path under it")
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
    * `taken`, so a short id names one location of a tenant.
    */
  @annotation.tailrec
  def newId(
      taken: Iterable[String],
      fresh: () => String = () => UUID.randomUUID().toString.replace("-", "")
  ): String =
    val id = fresh()
    if taken.exists(_.take(8) == id.take(8)) then newId(taken, fresh) else id
