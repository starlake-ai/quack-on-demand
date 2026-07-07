package ai.starlake.quack.ondemand.api

import sttp.model.StatusCode

import java.time.Instant
import scala.util.Try

/** Shared query-parameter parsing for the telemetry read endpoints (audit, history, usage). */
object QueryParams:

  /** Parse an optional ISO-8601 instant query param. `label` names the parameter in the 400
    * `invalid_time` error message.
    */
  def instant(
      s: Option[String],
      label: String
  ): Either[(StatusCode, ErrorResponse), Option[Instant]] =
    s match
      case None    => Right(None)
      case Some(v) =>
        Try(Instant.parse(v)).toOption
          .map(i => Right(Some(i)))
          .getOrElse(
            Left(
              (
                StatusCode.BadRequest,
                ErrorResponse("invalid_time", s"$label must be ISO-8601 instant")
              )
            )
          )
