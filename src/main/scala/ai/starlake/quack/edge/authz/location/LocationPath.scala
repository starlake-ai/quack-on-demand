package ai.starlake.quack.edge.authz.location

import ai.starlake.quack.model.S3BucketName

import java.util.Locale

/** Where a location lives: an object-store bucket (lowercase name), a POSIX filesystem, or a
  * Windows drive (uppercase letter).
  */
private[location] enum Space:
  case Bucket(name: String)
  case Posix
  case Drive(letter: Char)

/** A location split into its space and its path segments, in the checker's spelling. */
private[location] final case class LocationPath(space: Space, segments: Vector[String]):

  def isLocal: Boolean = space match
    case Space.Bucket(_) => false
    case _               => true

  /** Windows paths compare case-insensitively (NTFS); keys and POSIX paths do not. */
  def caseInsensitive: Boolean = space match
    case Space.Drive(_) => true
    case _              => false

  /** How many leading segments are literal: the index of the first glob segment, or all of them.
    */
  def literalLength: Int =
    val g = segments.indexWhere(LocationPath.isGlob)
    if g < 0 then segments.length else g

  /** The segments as denial compares them ([[LocationPath.foldForDenial]]), folded once per path: a
    * cached deny entry folds its data folder once for every statement it judges.
    */
  lazy val denialSegments: Vector[String] = segments.map(LocationPath.foldForDenial)

private[location] object LocationPath:

  private val DriveRe   = "(?s)^[A-Za-z]:[\\\\/].*$".r
  private val ShortName = "(?s)^.*~[0-9].*$".r
  private val GlobChars = "*?[]{}"
  private val Devices   =
    Set("con", "prn", "aux", "nul") ++ (1 to 9).flatMap(i => List(s"com$i", s"lpt$i"))

  /** Whether `segment` holds a glob character (DuckDB's `*`, `?`, `[...]`, `**`, and braces). */
  def isGlob(segment: String): Boolean = segment.exists(c => GlobChars.indexOf(c) >= 0)

  /** A location as a statement names it, after qod-node resolved it: an `s3://` URL or an absolute
    * local path, strictly. Left names the first thing refused.
    */
  def parse(s: String): Either[String, LocationPath] =
    if s.isEmpty then Left("an empty location")
    else if s.exists(Character.isISOControl) then Left("a control character")
    else if s.contains('%') then Left("a percent-escape")
    else if s.startsWith("s3://") then parseS3(s.substring("s3://".length))
    else if DriveRe.matches(s) then
      segments(s.substring(3), windows = true)
        .map(LocationPath(Space.Drive(s.charAt(0).toUpper), _))
    else if s.startsWith("/") then
      if s.contains('\\') then Left("a '\\' in a POSIX path")
      else segments(s.substring(1), windows = false).map(LocationPath(Space.Posix, _))
    else Left("not an s3:// URL or an absolute local path")

  /** A folder of the rules (an authorized bucket or folder, the files root, a data folder): parsed
    * like a location, and never a glob.
    */
  def folder(s: String): Option[LocationPath] =
    parse(s).toOption.filter(p => p.literalLength == p.segments.length)

  /** Whether `p` lies in `folder` (or is it): same space, and every segment of the folder matched
    * literally, before any glob of `p`.
    */
  def within(p: LocationPath, folder: LocationPath): Boolean =
    p.space == folder.space && folder.segments.length <= p.literalLength &&
      folder.segments.indices.forall(i =>
        if p.caseInsensitive then p.segments(i).equalsIgnoreCase(folder.segments(i))
        else p.segments(i) == folder.segments(i)
      )

  /** A segment as denial compares it: NFC (APFS opens a name by any normalization of it, and the
    * node cannot canonicalize a tail that does not exist yet), then lowercase. ASCII is its own
    * NFC.
    */
  def foldForDenial(segment: String): String =
    if segment.forall(_ < 0x80) then segment.toLowerCase(Locale.ROOT)
    else
      java.text.Normalizer
        .normalize(segment, java.text.Normalizer.Form.NFC)
        .toLowerCase(Locale.ROOT)

  /** Whether `p` reaches data folder `d`: names it or something below it, one of its derived
    * siblings (`<last>.<suffix>`, `<last>__br_<suffix>`), an ancestor of it, or a glob whose
    * literal prefix runs along it (the glob could expand into it). Case- and
    * normalization-insensitive ([[foldForDenial]]).
    */
  def reaches(p: LocationPath, d: LocationPath): Boolean =
    p.space == d.space && {
      val n = d.segments.length
      val k = math.min(p.literalLength, n)
      (0 until k).forall { i =>
        val a = p.denialSegments(i)
        val b = d.denialSegments(i)
        a == b || (k == n && i == n - 1 && (a.startsWith(b + ".") || a.startsWith(b + "__br_")))
      }
    }

  /** Whether `p` reaches database file `d` by name: [[reaches]], `d`'s last segment matched as a
    * name prefix (`x.duckdb` reaches `x.duckdb2/...`, `x.duckdb.wal` and `x.duckdbx`). DuckDB signs
    * a path with the secret whose scope is its longest string prefix, and a file's own scope (the
    * file's path, ObjectStoreSecret.dataScopes) is a string prefix of every name that begins with
    * it.
    */
  def reachesByName(p: LocationPath, d: LocationPath): Boolean =
    p.space == d.space && d.segments.nonEmpty && {
      val n = d.segments.length
      val k = math.min(p.literalLength, n)
      (0 until k).forall { i =>
        val a = p.denialSegments(i)
        val b = d.denialSegments(i)
        if i == n - 1 then a.startsWith(b) else a == b
      }
    }

  /** An absolute local path an operator configured (a data path, the files root, an authorized
    * folder), in the checker's spelling: forward slashes, an uppercase drive letter, `.` and empty
    * segments dropped, `..` applied, a trailing `/`. None when it is not absolute, is a POSIX path
    * holding a `\`, or climbs above its root.
    */
  def normalizeLocal(abs: String): Option[String] =
    val split: Option[(String, String, Boolean)] =
      if DriveRe.matches(abs) then Some((s"${abs.charAt(0).toUpper}:/", abs.substring(3), true))
      else if abs.startsWith("/") && !abs.contains('\\') then Some(("/", abs.substring(1), false))
      else None
    split.flatMap { (root, rest, windows) =>
      rest
        .split(if windows then "[/\\\\]" else "/")
        .toList
        .filter(seg => seg.nonEmpty && seg != ".")
        .foldLeft(Option(List.empty[String])) {
          case (Some(acc), "..") => acc.headOption.map(_ => acc.tail)
          case (Some(acc), seg)  => Some(seg :: acc)
          case (None, _)         => None
        }
        .map(reversed => root + reversed.reverse.map(_ + "/").mkString)
    }

  private def parseS3(rest: String): Either[String, LocationPath] =
    val slash = rest.indexOf('/')
    if slash < 0 then Left("no '/' after the bucket")
    else
      val bucket = rest.substring(0, slash)
      val key    = rest.substring(slash + 1)
      if S3BucketName.isAlias(bucket) then Left("an access point alias, not a bucket")
      else if !S3BucketName.valid(bucket) then Left("not a lowercase S3 bucket name")
      else if key.exists(c => c == '?' || c == '#' || c == '\\') then
        Left("a '?', '#' or '\\' in the key")
      else segments(key, windows = false).map(LocationPath(Space.Bucket(bucket), _))

  /** `rest` (what follows the bucket or the root) as segments: one trailing separator allowed, any
    * other empty segment refused, as are `.`, `..` and, on Windows, the names Windows rewrites or
    * reserves.
    */
  private def segments(rest: String, windows: Boolean): Either[String, Vector[String]] =
    if rest.isEmpty then Right(Vector.empty)
    else
      val parts = rest.split(if windows then "[/\\\\]" else "/", -1).toVector
      val body  = if parts.last.isEmpty then parts.init else parts
      body.iterator.flatMap(segmentError(_, windows)).nextOption() match
        case Some(why) => Left(why)
        case None      => Right(body)

  private def segmentError(seg: String, windows: Boolean): Option[String] =
    if seg.isEmpty then Some("an empty path segment")
    else if seg == "." || seg == ".." then Some("a '.' or '..' segment")
    else if !windows then None
    else if seg.contains(':') then Some("a ':' inside a Windows path segment")
    else if seg.endsWith(".") || seg.endsWith(" ") then
      Some("a Windows path segment ending in '.' or a space")
    else if ShortName.matches(seg) then Some("a Windows short (8.3) name")
    else if Devices.contains(seg.takeWhile(_ != '.').toLowerCase(Locale.ROOT)) then
      Some("a Windows device name")
    else None
