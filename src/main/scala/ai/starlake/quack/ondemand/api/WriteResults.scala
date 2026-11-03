package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.QueryResult
import ai.starlake.quack.edge.adapter.{QuackHttpClient, QuackWireError}

import scala.util.control.NonFatal

/** The outcome of a write whose result nobody reads (a CTAS's row count). */
object WriteResults:

  /** Why a drain failed. `answered`: the node answered the step that failed with an error of its
    * own (an ERROR_RESPONSE, [[QuackWireError.Permanent]]), or refused the COMMIT's session handle
    * ([[QuackHttpClient.HandleRefused]], the COMMIT never went out): either way nothing committed.
    * Anything else (a broken connection, a 5xx, a stream that broke) says nothing of whether the
    * COMMIT landed. Decided by what failed, never by its message. It rests on the result being a
    * CTAS's, one batch with no further fetch, so the COMMIT is the only round trip of the read.
    */
  final case class Failed(message: String, answered: Boolean)

  /** Reads `result` to its end, then closes it. A stamped write commits when its stream is
    * exhausted and reports a failed COMMIT there, as a stream error; closing an undrained stream
    * also tries the COMMIT but can only log a failure (`StampedCommitArrowReader`). So a caller
    * that answers success after the write must drain first: `Left` carries the failure, the write
    * rolled back when the node `answered`, else its outcome is unknown (the connection broke after
    * the COMMIT went out), which the caller settles from the catalog.
    */
  def drain(result: QueryResult): Either[Failed, Unit] =
    try
      try
        while result.rows.loadNextBatch() do ()
        Right(())
      catch
        case NonFatal(e) =>
          Left(Failed(Option(e.getMessage).getOrElse(e.getClass.getSimpleName), answered(e)))
    finally result.close()

  private def answered(e: Throwable): Boolean = e match
    case _: QuackWireError.Permanent | _: QuackHttpClient.HandleRefused => true
    case _                                                              => false
