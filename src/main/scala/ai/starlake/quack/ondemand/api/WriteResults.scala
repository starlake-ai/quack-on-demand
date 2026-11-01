package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.QueryResult

import scala.util.control.NonFatal

/** The outcome of a write whose result nobody reads (a CTAS's row count). */
object WriteResults:

  /** Reads `result` to its end, then closes it. A stamped write commits when its stream is
    * exhausted and reports a failed COMMIT there, as a stream error; closing an undrained stream
    * also tries the COMMIT but can only log a failure (`StampedCommitArrowReader`). So a caller
    * that answers success after the write must drain first: `Left` carries the failure, the write
    * rolled back (or its outcome is unknown when the connection broke after the COMMIT went out,
    * which the caller settles from the catalog).
    */
  def drain(result: QueryResult): Either[String, Unit] =
    try
      try
        while result.rows.loadNextBatch() do ()
        Right(())
      catch
        case NonFatal(e) =>
          Left(Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
    finally result.close()
