package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.QueryResult
import ai.starlake.quack.edge.adapter.{QuackHttpClient, QuackWireError}

import scala.util.control.NonFatal

/** The outcome of a write whose result nobody reads (a CTAS's row count). */
object WriteResults:

  /** Why a drain failed. `nodeRefused`: the node itself answered the step that failed with an error
    * (an ERROR_RESPONSE, [[QuackWireError.Permanent]]), or refused the COMMIT's session handle
    * ([[QuackHttpClient.HandleRefused]]: the COMMIT never went out). It says what the node saw, not
    * what the catalog's Postgres did: every statement error is an ERROR_RESPONSE, a connection to
    * the catalog lost during the metadata COMMIT included, after which that commit can be durable.
    * So a caller still asks the catalog, and `nodeRefused` only decides what an absent commit
    * means: a refusal, where after a lost answer (a broken connection, a 5xx, a stream that broke:
    * never `nodeRefused`) the COMMIT may still be in flight. Decided by what failed, never by its
    * message. That refusal rests on the result being a CTAS's, one batch with no further fetch, so
    * the COMMIT is the only round trip of the read and no close-time COMMIT follows a failure.
    */
  final case class Failed(message: String, nodeRefused: Boolean)

  /** Reads `result` to its end, then closes it. A stamped write commits when its stream is
    * exhausted and reports a failed COMMIT there, as a stream error; closing an undrained stream
    * also tries the COMMIT (inside this call, so a caller that reads the catalog afterwards sees
    * it) but can only log a failure (`StampedCommitArrowReader`). So a caller that answers success
    * after the write must drain first: `Left` carries the failure, and whether the write rolled
    * back is for the catalog to say, a node's refusal included.
    */
  def drain(result: QueryResult): Either[Failed, Unit] =
    try
      try
        while result.rows.loadNextBatch() do ()
        Right(())
      catch
        case NonFatal(e) =>
          Left(Failed(Option(e.getMessage).getOrElse(e.getClass.getSimpleName), nodeRefused(e)))
    finally result.close()

  private def nodeRefused(e: Throwable): Boolean = e match
    case _: QuackWireError.Permanent | _: QuackHttpClient.HandleRefused => true
    case _                                                              => false
