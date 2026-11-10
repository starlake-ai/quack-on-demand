package ai.starlake.quack.ondemand.api

import sttp.model.StatusCode

/** The `after` cursor of the audit and statement history routes: rows with a greater id, oldest
  * first. Unlike `before`, a bad value is refused rather than ignored, because a reader moving
  * forward would otherwise restart from the newest rows and skip everything between.
  */
private[api] object HistoryCursor:

  def after(
      before: Option[String],
      after: Option[String]
  ): Either[(StatusCode, ErrorResponse), Option[Long]] =
    (before, after) match
      case (Some(_), Some(_)) =>
        Left(
          StatusCode.BadRequest -> ErrorResponse("invalid_cursor", "send before or after, not both")
        )
      case (_, None)    => Right(None)
      case (_, Some(a)) =>
        a.toLongOption.filter(_ >= 0L) match
          case Some(id) => Right(Some(id))
          case None     =>
            Left(StatusCode.BadRequest -> ErrorResponse("invalid_cursor", "after must be a row id"))
