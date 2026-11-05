package ai.starlake.quack.edge.authz.location

import ai.starlake.quack.model.TenantLocation
import ai.starlake.quack.ondemand.branch.BranchNames
import com.github.benmanes.caffeine.cache.Caffeine
import com.typesafe.scalalogging.LazyLogging

import java.util.concurrent.atomic.AtomicReference
import scala.collection.concurrent.TrieMap

/** One database's hold on data path `path`. `tenant`: the tenant whose users alone it counts
  * against, when the row holds complete keys of its own there; None when it counts against every
  * non-superuser, which is what a row must not be able to show otherwise reads as.
  */
final case class DataPathHolder(path: String, tenant: Option[String])

object DataPathHolder:

  /** A path that counts against every non-superuser. */
  def ofEveryone(path: String): DataPathHolder = DataPathHolder(path, None)

/** The location rules of one principal, as the decision pipeline needs them now. */
trait LocationRulesSource:

  /** The rules of user `userId` of tenant `tenantId`: the user's own S3 scopes, the tenant's local
    * folders, the data folders that count against the tenant's users. Left when part of the deny
    * set could not be read (the control-plane store failed): the decision is then unavailable,
    * never an allow.
    */
  def rulesFor(tenantId: String, userId: String): Either[String, LocationRules]

/** [[LocationRules]] read live: the tenant's authorized locations (`tenantLocations`, the
  * supervisor's), every data path the supervisor knows with who holds it (`holders`) and the
  * managed prefixes the store still holds (`unpurgedPrefixes`, cached
  * [[LiveLocationRules.StoreTtlMs]], and read again when a holder changes). A data path seen once
  * stays denied for [[LiveLocationRules.RecentMs]] after the supervisor drops it, so a database
  * dropped between two store reads is never missing from both. The deny set is built once and
  * handed to every statement until a holder or a managed prefix changes, a remembered path expires
  * or [[LiveLocationRules.EntryTtlMs]] passes (real paths are re-resolved then).
  *
  * Whom a folder counts against ([[foldersAgainst]], the one rule): the users of its tenant when
  * each row at its path holds complete keys of its own, and every non-superuser otherwise: a row
  * that does not hold them at that path, a path a holder left within [[LiveLocationRules.RecentMs]]
  * (a drop, or keys cleared), and a path at or under a managed prefix or a branch of one. A path
  * several tenants' rows hold counts against each of those tenants. A location several folders
  * cover is refused if any of them counts against the user.
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
  *
  * `tenantOfUser`: a user's tenant, as the supervisor reads it: None for no such user, Some(None)
  * for a superuser, each of which reads the whole set. May throw.
  */
final class LiveLocationRules(
    holders: () => List[DataPathHolder],
    unpurgedPrefixes: () => List[String],
    tenantLocations: String => List[TenantLocation],
    userScopes: String => Either[String, List[String]],
    filesRoot: Option[String],
    sameHost: Boolean,
    clock: () => Long = () => System.currentTimeMillis(),
    realPath: String => String = LocalRealPath.of,
    owners: String => List[String] = _ => Nil,
    tenantOfUser: String => Option[Option[String]] = _ => None
) extends LocationRulesSource,
      LazyLogging:
  import LiveLocationRules.*

  private val root: Option[String] =
    filesRoot.map(_.trim).filter(_.nonEmpty).flatMap(LocationPath.normalizeLocal)

  private val storeCache = new AtomicReference[Option[(Long, List[String])]](None)
  private val seen       = TrieMap.empty[String, Long]
  private val left       = TrieMap.empty[String, Long]
  private val entries    = TrieMap.empty[String, (Long, List[String])]

  /** The deny set last built, and what it was built from. */
  private final case class Built(
      holders: List[DataPathHolder],
      prefixes: List[String],
      validUntil: Long,
      folders: List[String],
      everyone: List[String],
      byTenant: Map[String, List[String]],
      rootReal: Option[String]
  )

  private val built = new AtomicReference[Option[Built]](None)

  /** tenant id -> the locations its folders were resolved from, and the folders; emptied with every
    * rebuild of the deny set, so a real path is resolved again at least every EntryTtlMs.
    */
  private val folderCache = TrieMap.empty[String, (List[TenantLocation], List[String])]

  /** The deny-set sources already named as unplaceable: once per source while it stays listed. */
  private val unplaceableWarned = TrieMap.empty[String, Unit]

  /** Every database data folder of the deployment, whoever it counts against, as the deny set holds
    * it now: the same list instance while nothing it is built from changes (LocationChecker parses
    * each list once). What no new database may overlap.
    */
  def dataFolders(): Either[String, List[String]] = current().map(_.folders)

  /** The data folders that count against a user of `tenant`, the one rule for adding a scope, using
    * one, and serving a credential: the same list instance while nothing changes. None, a
    * superuser's scope when one is added, reads the whole set.
    */
  def foldersAgainst(tenant: Option[String]): Either[String, List[String]] =
    current().map(against(_, tenant))

  private def against(b: Built, tenant: Option[String]): List[String] =
    tenant.fold(b.folders)(t => b.byTenant.getOrElse(t, b.everyone))

  /** [[foldersAgainst]] the tenant of user `userId`; the whole set for no such user. Left when the
    * user cannot be read.
    */
  def foldersAgainstUser(userId: String): Either[String, List[String]] =
    tenantOf(userId).flatMap(foldersAgainst)

  /** A user's tenant never changes, so a read is kept. No such user is read again each time. */
  private def tenantOf(userId: String): Either[String, Option[String]] =
    Option(userTenants.getIfPresent(userId)) match
      case Some(tenant) => Right(tenant)
      case None         =>
        try
          Right(tenantOfUser(userId) match
            case Some(tenant) =>
              userTenants.put(userId, tenant)
              tenant
            case None => None)
        catch
          case e: Exception =>
            logger.warn(s"location rules: a user's tenant unreadable (${e.getClass.getSimpleName})")
            Left(Unreadable)

  private val userTenants =
    Caffeine.newBuilder().maximumSize(UserTenantEntries).build[String, Option[String]]()

  private def current(): Either[String, Built] =
    val listed = holders()
    val moved  = built.get().forall(_.holders != listed)
    // A row is listed after its managed prefix is written: read them again when a holder changes.
    storePrefixes(fresh = moved).map { prefixes =>
      val now = clock()
      built.get() match
        case Some(b) if b.holders == listed && b.prefixes == prefixes && now < b.validUntil => b
        case previous => synchronized(rebuild(listed, prefixes, previous, now))
    }

  /** A fresh deny set. The paths the previous one was built from count as seen now: they were
    * listed until this change was noticed (over-deny, never under).
    */
  private def rebuild(
      listed: List[DataPathHolder],
      prefixes: List[String],
      previous: Option[Built],
      now: Long
  ): Built =
    val paths = listed.map(_.path).distinct
    previous.foreach(_.holders.foreach(h => seen.put(h.path, now)))
    paths.foreach(p => seen.put(p, now))
    seen.filterInPlace((_, at) => now - at <= RecentMs)
    // A holder that left a path leaves it counting against everyone for as long as a dropped one.
    previous.foreach(_.holders.diff(listed).foreach(h => left.put(h.path, now)))
    left.filterInPlace((_, at) => now - at <= RecentMs)
    val sources = (seen.keys.toList ++ prefixes).distinct
    val live    = sources.toSet
    entries.filterInPlace((path, _) => live.contains(path))
    unplaceableWarned.filterInPlace((path, _) => live.contains(path))
    val folders = sources.flatMap(entriesOf(_, now)).distinct
    // Who each listed path counts against, its tenants, when nothing makes it everyone's.
    val alone: Map[String, Set[String]] = listed
      .groupBy(_.path)
      .collect {
        case (path, rows)
            if rows.forall(_.tenant.isDefined) && !left.contains(path) &&
              !managedPath(path, prefixes) =>
          path -> rows.flatMap(_.tenant).toSet
      }
    val everyone = sources.filterNot(alone.contains).flatMap(entriesOf(_, now)).distinct
    val byTenant = alone.toList
      .flatMap((path, tenants) => tenants.toList.map(_ -> path))
      .groupMap(_._1)(_._2)
      .map((tenant, own) => tenant -> (everyone ++ own.flatMap(entriesOf(_, now))).distinct)
    // Rebuilt no later than the first remembered path's expiry and the entries' TTL.
    val livePaths  = paths.toSet
    val firstGone  = seen.collect { case (p, at) if !livePaths.contains(p) => at + RecentMs + 1 }
    val firstLeft  = left.values.map(_ + RecentMs + 1)
    val validUntil = (now + EntryTtlMs) +: (firstGone.toList ++ firstLeft)
    folderCache.clear()
    val b = Built(listed, prefixes, validUntil.min, folders, everyone, byTenant, root.map(real))
    built.set(Some(b))
    b

  /** Whether `path` lies at, under or above a managed prefix, or is a branch of one that does. */
  private def managedPath(path: String, prefixes: List[String]): Boolean =
    val named = path :: BranchNames.parentPrefix(path).toList
    prefixes.exists(prefix => named.exists(DataFolders.overlap(_, prefix)))

  def rulesFor(tenantId: String, userId: String): Either[String, LocationRules] =
    for
      b      <- current()
      scopes <- userScopes(userId)
      folders = against(b, Some(tenantId))
    yield LocationRules(
      s3Scopes = scopes
        .filter(s =>
          LocationPath.folder(s).exists(!_.isLocal) &&
            LocationChecker.scopeError(s, folders).isEmpty
        )
        .distinct,
      tenantFolders = cachedTenantFolders(tenantId),
      dataFolders = folders,
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

  /** Why a user of `tenant` may not hold an S3 credential for `scope`
    * ([[LocationChecker.scopeError]] against [[foldersAgainst]] as it is now); Left when the deny
    * set could not be read.
    */
  def dataFolderOverlap(tenant: Option[String], scope: String): Either[String, Option[String]] =
    foldersAgainst(tenant).map(LocationChecker.scopeError(scope, _))

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
            case Nil  => "a data path of this deployment"
            case list => list.mkString(", ")
          logger.warn(
            s"location rules: $whose: its data path cannot be placed on the nodes (a relative " +
              "path off the local runtime, or one this host cannot parse); every tenant's local " +
              "file locations are refused until it is fixed"
          )
        fresh

  private def storePrefixes(fresh: Boolean): Either[String, List[String]] =
    val now = clock()
    storeCache.get() match
      case Some((at, prefixes)) if !fresh && now - at < StoreTtlMs => Right(prefixes)
      case _                                                       =>
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
            Left(Unreadable)

object LiveLocationRules:

  /** Why the deny set cannot be given. */
  val Unreadable: String = "the database data folders could not be read"

  /** How many users' tenants a replica keeps. */
  val UserTenantEntries: Long = 100_000L

  /** How long a read of the store's unpurged managed prefixes is reused. */
  val StoreTtlMs: Long = 2_000L

  /** How long a data path stays denied after the supervisor stops listing it. */
  val RecentMs: Long = 10 * 60_000L

  /** How long one data path's entries (normalization and real paths) are reused. */
  val EntryTtlMs: Long = 10_000L
