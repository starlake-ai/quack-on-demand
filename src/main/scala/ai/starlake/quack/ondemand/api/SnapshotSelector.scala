package ai.starlake.quack.ondemand.api

import sttp.model.StatusCode

import java.time.Instant

/** Pure snapshot-selector resolution shared by the catalog table endpoint and
  * [[TagHandlers.resolveAsOf]]. At most one of asOf / asOfTag / asOfTs may be supplied; the reader
  * and tag lookups are injected so the resolver stays testable without a live DuckLake catalog.
  */
object SnapshotSelector:

  enum Resolution:
    case Current
    case At(snapshotId: Long, committedAt: Option[Instant])

  enum SelectorError:
    case MultipleSelectors        // 400 invalid_selector
    case TagNotFound(tag: String) // 404 not_found
    case BeforeFirstSnapshot      // 422 invalid_snapshot (ts predates the first snapshot)
    case EmptyCatalog             // 422 invalid_snapshot (catalog has no snapshots at all)
    case Expired(id: Long)        // 410 snapshot_expired
    case BeyondLatest(id: Long)   // 422 invalid_snapshot

  /** Classify a resolved-but-absent snapshot id: above the newest id is BeyondLatest, below it is
    * Expired (vacuumed), and an id selector against a catalog with no snapshots is EmptyCatalog.
    */
  private def classifyAbsent(id: Long, maxId: () => Option[Long]): SelectorError =
    maxId() match
      case None                  => SelectorError.EmptyCatalog
      case Some(max) if id > max => SelectorError.BeyondLatest(id)
      case Some(_)               => SelectorError.Expired(id)

  /** Uniform HTTP mapping for selector failures, shared by every time-travel surface (catalog
    * detail, preview / data-diff, tag resolution): (status, wire error code, message).
    */
  def httpError(e: SelectorError): (StatusCode, String, String) = e match
    case SelectorError.MultipleSelectors =>
      (StatusCode.BadRequest, "invalid_selector", "supply only one of asOf, asOfTag, or asOfTs")
    case SelectorError.TagNotFound(tag) =>
      (StatusCode.NotFound, "not_found", s"tag '$tag' not found")
    case SelectorError.BeforeFirstSnapshot =>
      (StatusCode.UnprocessableEntity, "invalid_snapshot", "timestamp is before the first snapshot")
    case SelectorError.EmptyCatalog =>
      (StatusCode.UnprocessableEntity, "invalid_snapshot", "catalog has no snapshots")
    case SelectorError.BeyondLatest(id) =>
      (
        StatusCode.UnprocessableEntity,
        "invalid_snapshot",
        s"snapshot $id is beyond the latest snapshot"
      )
    case SelectorError.Expired(id) =>
      (StatusCode.Gone, "snapshot_expired", s"snapshot $id has been vacuumed")

  def resolve(
      asOf: Option[Long],
      asOfTag: Option[String],
      asOfTs: Option[Instant],
      maxId: () => Option[Long],
      atOrBefore: Instant => Option[Long],
      tagSnapshot: String => Option[Long],
      exists: Long => Boolean
  ): Either[SelectorError, Resolution] =
    val selectors = List(asOf.isDefined, asOfTag.isDefined, asOfTs.isDefined).count(identity)
    if selectors > 1 then Left(SelectorError.MultipleSelectors)
    else if asOf.isDefined then
      val id = asOf.get
      if exists(id) then Right(Resolution.At(id, None))
      else Left(classifyAbsent(id, maxId))
    else if asOfTag.isDefined then
      val tag = asOfTag.get
      tagSnapshot(tag) match
        case None     => Left(SelectorError.TagNotFound(tag))
        case Some(id) =>
          if exists(id) then Right(Resolution.At(id, None))
          else Left(classifyAbsent(id, maxId))
    else if asOfTs.isDefined then
      val ts = asOfTs.get
      atOrBefore(ts) match
        case None     => Left(SelectorError.BeforeFirstSnapshot)
        case Some(id) =>
          if exists(id) then Right(Resolution.At(id, Some(ts)))
          else Left(classifyAbsent(id, maxId))
    else Right(Resolution.Current)
