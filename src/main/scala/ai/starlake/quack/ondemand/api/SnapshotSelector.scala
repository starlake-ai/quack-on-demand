package ai.starlake.quack.ondemand.api

import java.time.Instant

object SnapshotSelector:

  enum Resolution:
    case Current
    case At(snapshotId: Long, committedAt: Option[Instant])

  enum SelectorError:
    case MultipleSelectors
    case TagNotFound(tag: String)
    case BeforeFirstSnapshot
    case Expired(id: Long)
    case BeyondLatest(id: Long)

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
      else
        maxId() match
          case None      => Left(SelectorError.BeforeFirstSnapshot)
          case Some(max) =>
            if id > max then Left(SelectorError.BeyondLatest(id))
            else Left(SelectorError.Expired(id))
    else if asOfTag.isDefined then
      val tag = asOfTag.get
      tagSnapshot(tag) match
        case None     => Left(SelectorError.TagNotFound(tag))
        case Some(id) =>
          if exists(id) then Right(Resolution.At(id, None))
          else
            maxId() match
              case None      => Left(SelectorError.BeforeFirstSnapshot)
              case Some(max) =>
                if id > max then Left(SelectorError.BeyondLatest(id))
                else Left(SelectorError.Expired(id))
    else if asOfTs.isDefined then
      val ts = asOfTs.get
      atOrBefore(ts) match
        case None     => Left(SelectorError.BeforeFirstSnapshot)
        case Some(id) =>
          if exists(id) then Right(Resolution.At(id, Some(ts)))
          else
            maxId() match
              case None      => Left(SelectorError.BeforeFirstSnapshot)
              case Some(max) =>
                if id > max then Left(SelectorError.BeyondLatest(id))
                else Left(SelectorError.Expired(id))
    else Right(Resolution.Current)
