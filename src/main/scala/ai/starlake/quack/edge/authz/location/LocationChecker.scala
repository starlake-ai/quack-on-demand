package ai.starlake.quack.edge.authz.location

/** What one tenant's statements may name as a file location (spec decisions 15 and 18).
  *
  *   - `tenantBuckets`: the buckets the tenant authorized with credentials of its own, lowercase.
  *   - `tenantFolders`: the absolute local folders it authorized, each under `filesRoot/<tenant>/`.
  *   - `dataFolders`: every database data folder of the deployment, any tenant's, as
  *     [[DataFolders.of]] spells them: `s3://<bucket>/<key>/`, an absolute local folder, or a
  *     relative data path whose place on the node is unknown (it denies every local location).
  *   - `filesRoot`: the operator's root for tenant folders (`QOD_TENANT_FILES_ROOT`); None when no
  *     local folder can be authorized.
  */
final case class LocationRules(
    tenantBuckets: Set[String],
    tenantFolders: List[String],
    dataFolders: List[String],
    filesRoot: Option[String]
)

object LocationRules:

  /** Nothing authorized: every location is refused. */
  val Empty: LocationRules = LocationRules(Set.empty, Nil, Nil, None)

/** Judges one location a non-superuser statement names (a file reader's argument, a COPY's target),
  * as qod-node resolved it: an `s3://` URL unchanged, a local path made absolute with symlinks
  * resolved. Admitted only when it lies in one of the tenant's authorized buckets or folders
  * (literally, any glob past them) and reaches no database data folder: not the folder, nothing
  * below it, none of its derived siblings, no ancestor of it, no glob that could expand into it.
  * Segments compare whole, so `s3://b/lake-1/` never covers `s3://b/lake-10/`.
  *
  * The tenant's own locations are checked first, so a location outside them always reads as such,
  * whatever lies there. A reason never carries a control character.
  */
object LocationChecker:

  val Outside: String    = "is outside the tenant's authorized locations"
  val DataFolder: String = "is a database data folder"

  def check(resolved: String, rules: LocationRules): Either[String, Unit] =
    val shown = resolved.filterNot(Character.isISOControl).take(512)
    for
      p <- LocationPath.parse(resolved).left.map(why => s"$shown $Outside ($why)")
      _ <- admit(p, rules).left.map(_.fold(s"$shown $Outside")(why => s"$shown $Outside ($why)"))
      _ <- deny(p, rules.dataFolders).map(detail => s"$shown $DataFolder$detail").toLeft(())
    yield ()

  /** Left(None): outside; Left(Some(why)): outside, and why. */
  private def admit(p: LocationPath, rules: LocationRules): Either[Option[String], Unit] =
    p.space match
      case Space.Bucket(b) =>
        if rules.tenantBuckets.exists(_.equalsIgnoreCase(b)) then Right(()) else Left(None)
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

  /** A deny-set entry: a folder; for an `s3://` entry the checker cannot parse, its whole bucket;
    * for anything else (a relative data path), every local location.
    */
  private def entry(s: String): Entry =
    LocationPath.folder(s) match
      case Some(p)                       => Entry.Folder(p)
      case None if s.startsWith("s3://") =>
        Entry.WholeBucket(s.substring("s3://".length).takeWhile(_ != '/').toLowerCase)
      case None => Entry.UnknownLocal

  /** Some(detail) when `p` reaches a data folder (detail empty, or why it may). */
  private def deny(p: LocationPath, dataFolders: List[String]): Option[String] =
    val entries = dataFolders.map(entry)
    val hit     = entries.exists {
      case Entry.Folder(d)      => LocationPath.reaches(p, d)
      case Entry.WholeBucket(b) => p.space == Space.Bucket(b)
      case Entry.UnknownLocal   => false
    }
    if hit then Some("")
    else if p.isLocal && entries.contains(Entry.UnknownLocal) then
      Some(" (a relative data path of this deployment may lie there)")
    else None
