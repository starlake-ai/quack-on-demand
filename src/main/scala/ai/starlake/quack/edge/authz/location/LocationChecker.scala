package ai.starlake.quack.edge.authz.location

import java.util.concurrent.atomic.AtomicReference

/** What one principal's statements may name as a file location (spec decisions 15, 18 and 23): the
  * principal's own S3 scopes and its tenant's local folders.
  *
  *   - `s3Scopes`: the S3 folders (`s3://<bucket>/` or a prefix in it) the principal holds
  *     credentials of its own for (decision 23), as UserS3CredentialRules stored them.
  *   - `tenantFolders`: the absolute local folders it authorized, each under `filesRoot/<tenant>/`.
  *   - `dataFolders`: every database data folder of the deployment, any tenant's, as
  *     [[DataFolders.of]] spells them: `s3://<bucket>/<key>/`, an absolute local folder, a relative
  *     data path whose place on the node is unknown, or [[DataFolders.Unplaced]] (either of the
  *     last two denies every local location).
  *   - `filesRoot`: the operator's root for tenant folders (`QOD_TENANT_FILES_ROOT`); None when no
  *     local folder can be authorized.
  */
final case class LocationRules(
    s3Scopes: List[String],
    tenantFolders: List[String],
    dataFolders: List[String],
    filesRoot: Option[String]
)

object LocationRules:

  /** Nothing authorized: every location is refused. */
  val Empty: LocationRules = LocationRules(Nil, Nil, Nil, None)

/** Judges one location a non-superuser statement names (a file reader's argument, a COPY's target),
  * as qod-node resolved it: an `s3://` URL unchanged, a local path made absolute with symlinks
  * resolved. Admitted only when it lies in one of the principal's S3 scopes or its tenant's folders
  * (literally, any glob past them) and reaches no database data folder: not the folder, nothing
  * below it, none of its derived siblings, no ancestor of it, no glob that could expand into it.
  * Segments compare whole, so `s3://b/lake-1/` never covers `s3://b/lake-10/`.
  *
  * The principal's own locations are checked first, so a location outside them always reads as
  * such, whatever lies there. A reason never carries a control, format or separator character.
  */
object LocationChecker:

  val Outside: String    = "is outside the tenant's authorized locations"
  val DataFolder: String = "is a database data folder"

  /** Why an `s3://` URL outside the principal's scopes is refused. */
  val NoCredential: String = "no S3 credential of yours covers it"

  def check(resolved: String, rules: LocationRules): Either[String, Unit] =
    val shown = shownOf(resolved)
    for
      p <- LocationPath.parse(resolved).left.map(why => s"$shown $Outside ($why)")
      _ <- admit(p, rules).left.map(_.fold(s"$shown $Outside")(why => s"$shown $Outside ($why)"))
      _ <- deny(p, rules.dataFolders).map(detail => s"$shown $DataFolder$detail").toLeft(())
    yield ()

  /** Why a user may not hold an S3 credential for `scope` (as UserS3CredentialRules normalized it):
    * the scope lies at or under a database data folder of any tenant, or one of its derived
    * siblings (`.wal`, `__br_<id8>`), or in a bucket whose data path the checker cannot parse. An
    * ancestor of a data folder is admitted: the folder itself stays refused at use (decision 18).
    * None when the scope may be held.
    */
  def scopeError(scope: String, dataFolders: List[String]): Option[String] =
    val shown = shownOf(scope)
    LocationPath.folder(scope).filterNot(_.isLocal) match
      case None    => Some(s"$shown is not an s3:// folder")
      case Some(p) =>
        val hit = entriesOf(dataFolders).exists {
          case Entry.Folder(d) =>
            p.segments.length >= d.segments.length && LocationPath.reaches(p, d)
          case Entry.WholeBucket(b)                     => p.space == Space.Bucket(b)
          case Entry.UnknownLocal | Entry.UnplacedLocal => false
        }
        Option.when(hit)(s"$shown $DataFolder")

  /** The S3 scope or tenant folder of `rules` that admits `resolved`, the longest; None when none
    * does. What a node decision's OPA input names beside each location (Task 6).
    */
  def admittedBy(resolved: String, rules: LocationRules): Option[String] =
    LocationPath.parse(resolved).toOption.flatMap { p =>
      val candidates = if p.isLocal then rules.tenantFolders else rules.s3Scopes
      candidates
        .flatMap(c => LocationPath.folder(c).map(c -> _))
        .filter((_, f) => LocationPath.within(p, f))
        .maxByOption(_._2.segments.length)
        .map(_._1)
    }

  /** `s` as a reason shows it: every [[unshown]] code point dropped, at most 512 code points. */
  private def shownOf(s: String): String =
    val b  = new java.lang.StringBuilder
    val it = s.codePoints.filter(cp => !unshown(cp)).limit(512).iterator
    while it.hasNext do b.appendCodePoint(it.next())
    b.toString

  /** A code point a reason never shows: a control character, a format character (bidi overrides and
    * isolates, zero-width marks, the supplementary plane's tag characters U+E0001 and
    * U+E0020-U+E007F), a line or paragraph separator, or a lone surrogate, any of which could make
    * the text a client or a log displays differ from the path it names. Judged per code point, so a
    * supplementary character is judged whole, never as its two surrogate halves.
    */
  private def unshown(cp: Int): Boolean =
    Character.isISOControl(cp) || {
      val t = Character.getType(cp)
      t == Character.FORMAT || t == Character.LINE_SEPARATOR ||
      t == Character.PARAGRAPH_SEPARATOR || t == Character.SURROGATE
    }

  /** Left(None): outside; Left(Some(why)): outside, and why. */
  private def admit(p: LocationPath, rules: LocationRules): Either[Option[String], Unit] =
    p.space match
      case Space.Bucket(_) =>
        if rules.s3Scopes.flatMap(LocationPath.folder).exists(LocationPath.within(p, _)) then
          Right(())
        else Left(Some(NoCredential))
      case local =>
        rules.filesRoot match
          case None =>
            Left(Some("no local folder can be authorized: QOD_TENANT_FILES_ROOT is unset"))
          case Some(r) =>
            LocationPath.folder(r).filter(_.space == local) match
              case None       => Left(None)
              case Some(root) =>
                val folders = rules.tenantFolders
                  .flatMap(LocationPath.folder)
                  .filter(t => LocationPath.within(t, root))
                if LocationPath.within(p, root) && folders.exists(LocationPath.within(p, _)) then
                  Right(())
                else Left(None)

  private enum Entry:
    case Folder(path: LocationPath)
    case WholeBucket(name: String)
    case UnknownLocal
    case UnplacedLocal

  /** A deny-set entry: a folder; for an `s3://` entry the checker cannot parse, its whole bucket;
    * for [[DataFolders.Unplaced]] or anything else (a relative data path), every local location.
    */
  private def entry(s: String): Entry =
    LocationPath.folder(s) match
      case Some(p)                           => Entry.Folder(p)
      case None if s == DataFolders.Unplaced => Entry.UnplacedLocal
      case None if s.startsWith("s3://")     =>
        Entry.WholeBucket(
          s.substring("s3://".length).takeWhile(_ != '/').toLowerCase(java.util.Locale.ROOT)
        )
      case None => Entry.UnknownLocal

  /** The deny set as parsed entries, kept for the last list judged: the live rules hand every
    * statement the same list until a data path changes, so each data folder is parsed and folded
    * once, not once per statement.
    */
  private val lastEntries = new AtomicReference[(List[String], List[Entry])]((Nil, Nil))

  private def entriesOf(dataFolders: List[String]): List[Entry] =
    val (folders, parsed) = lastEntries.get()
    if folders eq dataFolders then parsed
    else
      val fresh = dataFolders.map(entry)
      lastEntries.set((dataFolders, fresh))
      fresh

  /** Some(detail) when `p` reaches a data folder (detail empty, or why it may). */
  private def deny(p: LocationPath, dataFolders: List[String]): Option[String] =
    val entries = entriesOf(dataFolders)
    val hit     = entries.exists {
      case Entry.Folder(d)                          => LocationPath.reaches(p, d)
      case Entry.WholeBucket(b)                     => p.space == Space.Bucket(b)
      case Entry.UnknownLocal | Entry.UnplacedLocal => false
    }
    if hit then Some("")
    else if p.isLocal && entries.contains(Entry.UnknownLocal) then
      Some(" (a relative data path of this deployment may lie there)")
    else if p.isLocal && entries.contains(Entry.UnplacedLocal) then
      Some(" (a database whose data path this manager cannot place may lie there)")
    else None
