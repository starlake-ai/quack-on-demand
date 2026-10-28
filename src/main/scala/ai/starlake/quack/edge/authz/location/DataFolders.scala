package ai.starlake.quack.edge.authz.location

import ai.starlake.quack.ondemand.runtime.NodePaths

import java.io.File
import java.nio.file.{Files, LinkOption, Paths}
import java.util.Locale

/** A database data path as [[LocationChecker]] deny-set entries. */
object DataFolders:

  /** Schemes whose URLs a tenant `s3://` secret with another endpoint can reach (S3-compatible
    * APIs, GCS interop, Azure containers named like buckets): all read as `s3://<bucket>/<key>/`,
    * the family-blind matching of `ai.starlake.quack.model.BucketKeys`.
    */
  private val ObjectSchemes =
    List("s3://", "s3a://", "s3n://", "r2://", "gs://", "gcs://", "az://", "azure://")

  private val SchemeRe = "^[a-zA-Z][a-zA-Z0-9+.-]*://.*$".r

  /** A `file:` URL's path naming a Windows drive (`/C:/...`). */
  private val FileDriveRe = "^/[A-Za-z]:[/\\\\].*$".r

  /** The data path of a database whose node gets none from the manager (a DuckLake or duckdb-file
    * row with no data path of its own while the default metastore has none either): the spawn
    * script then picks a folder of its own. Kept as written by [[of]], which the checker reads as
    * "may be anywhere on the node": every local location is refused.
    */
  val Unplaced: String = "(a data path the manager cannot place)"

  /** Why every local location is refused while a database is [[Unplaced]]: the checker's reason and
    * the supervisor's WARN share it.
    */
  val UnplacedReason: String = "a database whose data path this manager cannot place may lie there"

  /** Appended to a data path that names one database file (a `duckdb-file` database): its deny
    * entries then also cover every name that begins with the file's own (`x.duckdb2/...`), all of
    * which the database's own secret (scoped to the file's path) would sign.
    */
  val NamePrefixMarker: String = "*"

  /** The deny-set entries of data path `path`. `sameHost`: nodes run on this host and resolve a
    * relative data path against this process's directory (the local runtime), so a relative path is
    * resolved and every local entry is joined by its real path (`realPath`). Off the local runtime
    * a relative path stays as written, which the checker reads as "may be anywhere on the node". A
    * `file:` URL is the local path it names. A remote scheme no tenant can name yields nothing.
    */
  def of(path: String, sameHost: Boolean, realPath: String => String): List[String] =
    val p     = path.trim
    val lower = p.toLowerCase(Locale.ROOT)
    if p.isEmpty then Nil
    else if p == Unplaced then List(Unplaced)
    else if p.endsWith(NamePrefixMarker) then
      val named = of(p.dropRight(NamePrefixMarker.length), sameHost, realPath)
      named ++ named.collect {
        case e if e.endsWith("/") && LocationPath.folder(e).exists(_.segments.nonEmpty) =>
          e.stripSuffix("/") + NamePrefixMarker
      }
    else if lower.startsWith("file:") then
      fileUrlPath(p.substring("file:".length)) match
        case Some(local0) => local(local0, sameHost, realPath)
        case None         => List(Unplaced)
    else
      ObjectSchemes.find(lower.startsWith) match
        case Some(sch) => objectFolder(p.substring(sch.length)).toList
        case None      =>
          if lower.startsWith("abfss://") then
            val rest      = p.substring("abfss://".length)
            val container = rest.takeWhile(c => c != '@' && c != '/')
            val key       = rest.dropWhile(_ != '/')
            objectFolder(container + key).toList
          else if lower.startsWith("http://") || lower.startsWith("https://") then
            val rest      = p.substring(p.indexOf("://") + 3)
            val authority = rest.takeWhile(_ != '/')
            val hostLabel = authority.takeWhile(c => c != '.' && c != ':')
            val pathPart  = rest.drop(authority.length).stripPrefix("/")
            (objectFolder(s"$hostLabel/$pathPart").toList ++ objectFolder(pathPart).toList).distinct
          else if SchemeRe.matches(p) then Nil
          else local(p, sameHost, realPath)

  /** The local path a `file:` URL names (what follows `file:`): `///p` and `//localhost/p` are
    * `/p`, a Windows `/C:/p` drops its leading slash, `/p` and a relative `p` stand as written.
    * None for another host or nothing at all.
    */
  private def fileUrlPath(rest: String): Option[String] =
    val path =
      if !rest.startsWith("//") then Some(rest)
      else
        val authority = rest.drop(2).takeWhile(_ != '/')
        Option.when(authority.isEmpty || authority.equalsIgnoreCase("localhost"))(
          rest.drop(2 + authority.length)
        )
    path
      .map(q => if FileDriveRe.matches(q) then q.substring(1) else q)
      .filter(_.nonEmpty)

  /** `bucket/key...` as `s3://<lowercase bucket>/<key segments>/`; None without a bucket. */
  private def objectFolder(rest: String): Option[String] =
    val bucket = rest.takeWhile(_ != '/').toLowerCase(Locale.ROOT)
    val key    = rest.drop(bucket.length).split("/").filter(_.nonEmpty).map(_ + "/").mkString
    Option.when(bucket.nonEmpty)(s"s3://$bucket/$key")

  /** A path this host cannot parse (`Paths.get` refuses it) stays as written: the checker reads it
    * as "may be anywhere on the node" and refuses every local location.
    */
  private def local(p: String, sameHost: Boolean, realPath: String => String): List[String] =
    val absolute =
      try
        if NodePaths.isRelativeLocal(p) && !isWindowsAbsolute(p) then
          if sameHost then Some(NodePaths.resolve(p)) else None
        else Some(p)
      catch case _: java.nio.file.InvalidPathException => None
    absolute match
      case None      => List(p)
      case Some(abs) =>
        LocationPath.normalizeLocal(abs) match
          case None        => List(p)
          case Some(lexed) =>
            if !sameHost then List(lexed)
            else (lexed :: LocationPath.normalizeLocal(realPath(lexed)).toList).distinct

  private def isWindowsAbsolute(p: String): Boolean =
    p.length >= 3 && p.charAt(0).isLetter && p.charAt(1) == ':' &&
      (p.charAt(2) == '\\' || p.charAt(2) == '/')

/** The real path a node gives a local path, by qod-node's own algorithm (`ResolveLocalPath`,
  * native/qod-node/src/locations.cpp): the path split by the node's lexical rules (absolute, no
  * NUL, no empty, `.` or `..` segment; on Windows a drive letter, no `:` in a segment, no segment
  * ending in `.` or a space, no device name), then the deepest ancestor that exists AS ITSELF (a
  * link that leads nowhere included, so it is not joined past) through `toRealPath`, the rest
  * appended as written, a trailing separator kept. A link that leads nowhere, or a folder this host
  * cannot read, refuses the path rather than leaving it unresolved. The manager applies it on the
  * local runtime, where it sees the nodes' filesystem.
  */
object LocalRealPath:

  /** The node's resolution of `path`, or why the node refuses it. */
  def resolve(path: String): Either[String, String] =
    split(path).flatMap { (root, segments, trailing) =>
      try
        var existing = Paths.get(root)
        var kept     = 0
        var walking  = true
        while walking && kept < segments.length do
          val next = existing.resolve(segments(kept))
          if Files.exists(next, LinkOption.NOFOLLOW_LINKS) then
            existing = next
            kept += 1
          else walking = false
        val real     = existing.toRealPath().toString
        val resolved = new StringBuilder(
          if Windows && real.startsWith("\\\\?\\") then real.drop(4) else real
        )
        if Windows && (resolved.length < 3 || resolved.charAt(1) != ':') then
          Left("resolves outside a drive")
        else
          segments.drop(kept).foreach { seg =>
            if resolved.isEmpty || resolved.last != Separator then resolved.append(Separator)
            resolved.append(seg)
          }
          if trailing && (resolved.isEmpty || resolved.last != Separator) then
            resolved.append(Separator)
          Right(resolved.toString)
      catch
        case _: Exception =>
          Left(
            "cannot be resolved: it passes through a symbolic link that leads nowhere or a " +
              "folder this node cannot read"
          )
    }

  /** [[resolve]], or `path` as written when the node would refuse it: for the deny set and the
    * tenant folders, which keep their written form beside the real one. A statement's location is
    * judged through [[resolve]], so a path the node refuses is refused there too.
    */
  def of(path: String): String = resolve(path).getOrElse(path)

  private val Windows   = File.separatorChar == '\\'
  private val Separator = File.separatorChar

  private def isSeparator(c: Char): Boolean = c == '/' || (Windows && c == '\\')

  /** The node's `Split`: the root, the segments, whether a separator ends the path. */
  private def split(path: String): Either[String, (String, Vector[String], Boolean)] =
    if path.contains('\u0000') then Left("holds a NUL character")
    else
      val rooted =
        if Windows then
          Option
            .when(
              path.length >= 3 && isAsciiLetter(path.charAt(0)) && path.charAt(1) == ':' &&
                isSeparator(path.charAt(2))
            ) {
              (s"${path.charAt(0).toUpper}:\\", 3)
            }
            .toRight("is not an absolute path with a drive letter")
        else if path.isEmpty || path.charAt(0) != '/' then Left("is a relative path")
        else Right(("/", 1))
      rooted.flatMap { (root, start) =>
        val trailing = path.length > start && isSeparator(path.last)
        val end      = if trailing then path.length - 1 else path.length
        if end <= start then Right((root, Vector.empty, trailing))
        else
          val segments = splitOn(path.substring(start, end))
          segments.collectFirst(Function.unlift(segmentError)).toLeft((root, segments, trailing))
      }

  private def splitOn(s: String): Vector[String] =
    val out  = Vector.newBuilder[String]
    var from = 0
    var i    = 0
    while i <= s.length do
      if i == s.length || isSeparator(s.charAt(i)) then
        out += s.substring(from, i)
        from = i + 1
      i += 1
    out.result()

  private def segmentError(segment: String): Option[String] =
    if segment.isEmpty || segment == "." || segment == ".." then
      Some("has an empty, '.' or '..' segment")
    else if !Windows then None
    else if segment.contains(':') then Some("names an alternate data stream")
    else if segment.endsWith(".") || segment.endsWith(" ") then
      Some("has a segment ending in '.' or ' ', which Windows strips")
    else if isDeviceName(segment) then Some("names a Windows device")
    else None

  private def isDeviceName(segment: String): Boolean =
    val stem = segment.takeWhile(_ != '.').toLowerCase(Locale.ROOT)
    Set("con", "prn", "aux", "nul")(stem) ||
    (stem.length == 4 && (stem.startsWith("com") || stem.startsWith("lpt")) &&
      stem.charAt(3) >= '1' && stem.charAt(3) <= '9')

  private def isAsciiLetter(c: Char): Boolean = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
