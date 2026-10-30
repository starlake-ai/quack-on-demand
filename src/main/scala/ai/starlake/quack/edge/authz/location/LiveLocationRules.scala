package ai.starlake.quack.edge.authz.location

import ai.starlake.quack.model.TenantLocation
import com.typesafe.scalalogging.LazyLogging

import java.util.concurrent.atomic.AtomicReference
import scala.collection.concurrent.TrieMap

/** The location rules of one principal, as the decision pipeline needs them now. */
trait LocationRulesSource:

  /** The rules of user `userId` of tenant `tenantId`: the user's own S3 scopes, the tenant's local
    * folders, every data folder. Left when part of the deny set could not be read (the
    * control-plane store failed): the decision is then unavailable, never an allow.
    */
  def rulesFor(tenantId: String, userId: String): Either[String, LocationRules]

/** [[LocationRules]] read live: the tenant's authorized locations (`tenantLocations`, the
  * supervisor's), every data path the supervisor knows (`dataPaths`) and the managed prefixes the
  * store still holds (`unpurgedPrefixes`, cached [[LiveLocationRules.StoreTtlMs]]). A data path
  * seen once stays denied for [[LiveLocationRules.RecentMs]] after the supervisor drops it, so a
  * database dropped between two store reads is never missing from both. The deny set is built once
  * and handed to every statement until a data path or a managed prefix changes, a remembered path
  * expires or [[LiveLocationRules.EntryTtlMs]] passes (real paths are re-resolved then).
  *
  * `filesRoot` is `QOD_TENANT_FILES_ROOT` (`TenantFilesConfig.rootOpt`; a relative or blank one
  * authorizes nothing). A tenant folder counts only under `<root>/<tenantId>/`; an `s3://` URL
  * counts under the user's own credential scopes (`userScopes`, the supervisor's cache; Left while
  * they cannot be read, and the rules are then unavailable), never under a malformed one nor under
  * one that now lies at or under a data folder ([[LocationChecker.scopeError]]: a database created
  * after the scope was added). `sameHost` (the local runtime): local paths are also taken through
  * `realPath`, as the node resolves them; the root's real path and each tenant's folders are
  * resolved with the deny set and kept as long ([[LiveLocationRules.EntryTtlMs]] at most).
  *
  * A deny-set source the checker cannot place ([[LocationChecker.unplaceable]]) is named once in a
  * WARN while it stays listed, through `owners` (the tenant-dbs whose data path it is).
  */
final class LiveLocationRules(
    dataPaths: () => List[String],
    unpurgedPrefixes: () => List[String],
    tenantLocations: String => List[TenantLocation],
    userScopes: String => Either[String, List[String]],
    filesRoot: Option[String],
    sameHost: Boolean,
    clock: () => Long = () => System.currentTimeMillis(),
    realPath: String => String = LocalRealPath.of,
    owners: String => List[String] = _ => Nil
) extends LocationRulesSource,
      LazyLogging:
  import LiveLocationRules.*

  private val root: Option[String] =
    filesRoot.map(_.trim).filter(_.nonEmpty).flatMap(LocationPath.normalizeLocal)

  private val storeCache = new AtomicReference[Option[(Long, List[String])]](None)
  private val seen       = TrieMap.empty[String, Long]
  private val entries    = TrieMap.empty[String, (Long, List[String])]

  /** The deny set last built, and what it was built from. */
  private final case class Built(
      paths: List[String],
      prefixes: List[String],
      validUntil: Long,
      folders: List[String],
      rootReal: Option[String]
  )

  private val built = new AtomicReference[Option[Built]](None)

  /** tenant id -> the locations its folders were resolved from, and the folders; emptied with every
    * rebuild of the deny set, so a real path is resolved again at least every EntryTtlMs.
    */
  private val folderCache = TrieMap.empty[String, (List[TenantLocation], List[String])]

  /** The deny-set sources already named as unplaceable: once per source while it stays listed. */
  private val unplaceableWarned = TrieMap.empty[String, Unit]

  /** Every database data folder of the deployment, as the deny set holds it now: the same list
    * instance while nothing it is built from changes (LocationChecker parses each list once).
    */
  def dataFolders(): Either[String, List[String]] = current().map(_.folders)

  private def current(): Either[String, Built] =
    storePrefixes().map { prefixes =>
      val now   = clock()
      val paths = dataPaths()
      built.get() match
        case Some(b) if b.paths == paths && b.prefixes == prefixes && now < b.validUntil => b
        case previous => synchronized(rebuild(paths, prefixes, previous, now))
    }

  /** A fresh deny set. The paths the previous one was built from count as seen now: they were
    * listed until this change was noticed (over-deny, never under).
    */
  private def rebuild(
      paths: List[String],
      prefixes: List[String],
      previous: Option[Built],
      now: Long
  ): Built =
    previous.foreach(_.paths.foreach(p => seen.put(p, now)))
    paths.foreach(p => seen.put(p, now))
    seen.filterInPlace((_, at) => now - at <= RecentMs)
    val sources = (seen.keys.toList ++ prefixes).distinct
    val live    = sources.toSet
    entries.filterInPlace((path, _) => live.contains(path))
    unplaceableWarned.filterInPlace((path, _) => live.contains(path))
    val folders = sources.flatMap(entriesOf(_, now)).distinct
    // Rebuilt no later than the first remembered path's expiry and the entries' TTL.
    val livePaths  = paths.toSet
    val firstGone  = seen.collect { case (p, at) if !livePaths.contains(p) => at + RecentMs + 1 }
    val validUntil = (now + EntryTtlMs) +: firstGone.toList
    folderCache.clear()
    val b = Built(paths, prefixes, validUntil.min, folders, root.map(real))
    built.set(Some(b))
    b

  def rulesFor(tenantId: String, userId: String): Either[String, LocationRules] =
    for
      b      <- current()
      scopes <- userScopes(userId)
    yield LocationRules(
      s3Scopes = scopes
        .filter(s =>
          LocationPath.folder(s).exists(!_.isLocal) &&
            LocationChecker.scopeError(s, b.folders).isEmpty
        )
        .distinct,
      tenantFolders = cachedTenantFolders(tenantId),
      dataFolders = b.folders,
      filesRoot = b.rootReal
    )

  /** [[tenantFolders]] of `tenantId`, kept while its locations are unchanged and until the next
    * rebuild of the deny set.
    */
  private def cachedTenantFolders(tenantId: String): List[String] =
    val locations = tenantLocations(tenantId)
    folderCache.get(tenantId) match
      case Some((from, folders)) if from == locations => folders
      case _                                          =>
        val folders = tenantFolders(tenantId, locations)
        folderCache.put(tenantId, (locations, folders))
        folders

  /** Why a user may not hold an S3 credential for `scope` ([[LocationChecker.scopeError]] against
    * the deny set as it is now); Left when the deny set could not be read.
    */
  def dataFolderOverlap(scope: String): Either[String, Option[String]] =
    dataFolders().map(LocationChecker.scopeError(scope, _))

  /** The tenant's local folders under `<root>/<tenantId>/`, as written; on the local runtime each
    * replaced by its real path, dropped when that leaves the real `<root>/<tenantId>/` (an operator
    * symlink to another tenant's folder authorizes nothing).
    */
  private def tenantFolders(tenantId: String, locations: List[TenantLocation]): List[String] =
    val tenantRoot = root.map(r => s"$r$tenantId/")
    (
      tenantRoot.flatMap(LocationPath.folder),
      tenantRoot.map(real).flatMap(LocationPath.folder)
    ) match
      case (Some(asWritten), Some(asReal)) =>
        def under(f: String, r: LocationPath) =
          LocationPath.folder(f).exists(LocationPath.within(_, r))
        locations
          .filter(_.kind == TenantLocation.Local)
          .flatMap(l => LocationPath.normalizeLocal(l.location))
          .filter(under(_, asWritten))
          .map(real)
          .filter(under(_, asReal))
          .distinct
      case _ => Nil

  /** On the local runtime, the real path of normalized local folder `f`; else `f`. */
  private def real(f: String): String =
    if sameHost then LocationPath.normalizeLocal(realPath(f)).getOrElse(f) else f

  private def entriesOf(path: String, now: Long): List[String] =
    entries.get(path) match
      case Some((at, cached)) if now - at < EntryTtlMs => cached
      case _                                           =>
        val fresh = DataFolders.of(path, sameHost, realPath)
        entries.put(path, (now, fresh))
        if fresh.exists(LocationChecker.unplaceable) &&
          unplaceableWarned.putIfAbsent(path, ()).isEmpty
        then
          // A failing lookup costs the names, never the deny set being built.
          val named =
            try owners(path)
            catch case scala.util.control.NonFatal(_) => Nil
          val whose = named match
            case Nil   => "a data path of this deployment"
            case named => named.mkString(", ")
          logger.warn(
            s"location rules: $whose: its data path cannot be placed on the nodes (a relative " +
              "path off the local runtime, or one this host cannot parse); every tenant's local " +
              "file locations are refused until it is fixed"
          )
        fresh

  private def storePrefixes(): Either[String, List[String]] =
    val now = clock()
    storeCache.get() match
      case Some((at, prefixes)) if now - at < StoreTtlMs => Right(prefixes)
      case _                                             =>
        try
          val prefixes = unpurgedPrefixes()
          storeCache.set(Some((now, prefixes)))
          Right(prefixes)
        catch
          case e: Exception =>
            logger.warn(
              s"location rules: managed prefixes unreadable (${e.getClass.getSimpleName}: " +
                s"${e.getMessage})"
            )
            Left("the database data folders could not be read")

object LiveLocationRules:

  /** How long a read of the store's unpurged managed prefixes is reused. */
  val StoreTtlMs: Long = 2_000L

  /** How long a data path stays denied after the supervisor stops listing it. */
  val RecentMs: Long = 10 * 60_000L

  /** How long one data path's entries (normalization and real paths) are reused. */
  val EntryTtlMs: Long = 10_000L
