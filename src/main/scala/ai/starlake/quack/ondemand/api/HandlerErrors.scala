package ai.starlake.quack.ondemand.api

import cats.effect.IO
import sttp.model.StatusCode

/** Error mappings shared by REST handlers. */
object HandlerErrors:
  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  /** Runs `io`, turning a raised error into `502 backend_error` (with `describe` as the message
    * prefix) instead of a bodyless 500. `onRaised` runs first, e.g. to write an "error" audit row.
    */
  def raisedToBadGateway[A](describe: String)(onRaised: => Unit)(io: => Out[A]): Out[A] =
    IO.defer(io).attempt.map {
      case Right(r) => r
      case Left(t)  =>
        onRaised
        Left(
          (
            StatusCode.BadGateway,
            ErrorResponse(
              "backend_error",
              s"$describe: ${Option(t.getMessage).getOrElse(t.toString)}"
            )
          )
        )
    }
