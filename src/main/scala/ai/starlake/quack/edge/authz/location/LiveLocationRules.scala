package ai.starlake.quack.edge.authz.location

import ai.starlake.quack.model.TenantLocation
import com.typesafe.scalalogging.LazyLogging

import java.util.concurrent.atomic.AtomicReference
import scala.collection.concurrent.TrieMap

/** The location rules of one tenant, as the decision pipeline needs them now. */
trait LocationRulesSource:

  /** Left when part of the deny set could not be read (the control-plane store failed): the
    * decision is then unavailable, never an allow.
    */
  def rulesFor(tenantId: String): Either[String, LocationRules]

/** [[LocationRules]] read live: the tenant's authorized locations (`tenantLocations`, the
  * supervisor's), every data path the supervisor knows (`dataPaths`) and the managed prefixes the
  * store still holds (`unpurgedPrefixes`, cached [[LiveLocationRules.StoreTtlMs]]). A data path
  * seen once stays denied for [[LiveLocationRules.RecentMs]] after the supervisor drops it, so a
  * database dropped between two store reads is never missing from both.
  *
  * `filesRoot` is `QOD_TENANT_FILES_ROOT` (`TenantFilesConfig.rootOpt`; a relative or blank one
  * authorizes nothing). A tenant folder counts only under `<root>/<tenantId>/`, an S3 location only
  * with both of `TenantLocation.RequiredObjectStoreKeys`. `sameHost` (the local runtime): local
  * paths are also taken through `realPath`, as the node resolves them.
  */
final class LiveLocationRules(
    dataPaths: () => List[String],
    unpurgedPrefixes: () => List[String],
    tenantLocations: String => List[TenantLocation],
    filesRoot: Option[String],
    sameHost: Boolean,
    clock: () => Long = () => System.currentTimeMillis(),
    realPath: String => String = LocalRealPath.of
) extends LocationRulesSource,
      LazyLogging:
  import LiveLocationRules.*

  private val root: Option[String] =
    filesRoot.map(_.trim).filter(_.nonEmpty).flatMap(LocationPath.normalizeLocal)

  private val storeCache = new AtomicReference[Option[(Long, List[String])]](None)
  private val seen       = TrieMap.empty[String, Long]
  private val entries    = TrieMap.empty[String, (Long, List[String])]

  def rulesFor(tenantId: String): Either[String, LocationRules] =
    storePrefixes().map { prefixes =>
      val now = clock()
      dataPaths().foreach(p => seen.put(p, now))
      seen.filterInPlace((_, at) => now - at <= RecentMs)
      val sources = (seen.keys.toList ++ prefixes).distinct
      val live    = sources.toSet
      entries.filterInPlace((path, _) => live.contains(path))
      val folders   = sources.flatMap(entriesOf(_, now)).distinct
      val locations = tenantLocations(tenantId)
      LocationRules(
        tenantBuckets = locations
          .filter(l =>
            l.kind == TenantLocation.S3 &&
              TenantLocation.RequiredObjectStoreKeys.forall(k =>
                l.objectStore.get(k).exists(_.nonEmpty)
              )
          )
          .flatMap(l => LocationPath.folder(l.location))
          .collect { case LocationPath(Space.Bucket(b), _) => b }
          .toSet,
        tenantFolders = tenantFolders(tenantId, locations),
        dataFolders = folders,
        filesRoot = root.map(real)
      )
    }

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
