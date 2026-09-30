package ai.starlake.quack.ondemand.catalog.iceberg

import ai.starlake.quack.edge.adapter.QuackResponse
import ai.starlake.quack.model.{PoolKey, RunningNode}
import ai.starlake.quack.ondemand.api.ArrowRowsDecoder
import ai.starlake.quack.ondemand.federation.iceberg.AttachErrorRedactor
import cats.effect.IO
import io.circe.Json

import scala.concurrent.duration.DurationInt

enum MetadataFailure:
  case NoPool
  case NoRunningNode
  case NotAttached(detail: Option[String])
  case Remote(message: String)
  case TimedOut

/** Internal signal for a `QuackResponse.Failed` raised INSIDE the `send`+decode region so a single
  * `.attempt` after `.timeout` can tell it apart from a genuine timeout: both surface as `Left` of
  * that `.attempt`, and only this one carries a message to scrub and report as `Remote`.
  */
private final case class RemoteFailure(message: String) extends RuntimeException(message)

/** Runs one privileged metadata statement (session = None, the path `IcebergAttachVerifier` uses)
  * on a read node of the tenant-db where the Iceberg alias attached. Admin-only callers; never used
  * for preview or diff, which go through the routed executor as the caller.
  */
final class NodeMetadataQuery(
    readPool: (String, String) => Option[PoolKey],
    readNodes: PoolKey => List[RunningNode],
    isAttached: (RunningNode, String) => Boolean,
    attachSummary: (String, Set[String]) => Option[String],
    send: (RunningNode, String) => IO[QuackResponse],
    timeoutSec: Int,
    maxRows: Int
):

  def run(
      tenant: String,
      tenantDb: String,
      alias: String,
      sql: String
  ): IO[Either[MetadataFailure, List[List[Json]]]] =
    readPool(tenant, tenantDb) match
      case None      => IO.pure(Left(MetadataFailure.NoPool))
      case Some(key) =>
        val nodes = readNodes(key)
        if nodes.isEmpty then IO.pure(Left(MetadataFailure.NoRunningNode))
        else
          nodes.find(isAttached(_, alias)) match
            case None =>
              IO.pure(
                Left(MetadataFailure.NotAttached(attachSummary(alias, nodes.map(_.nodeId).toSet)))
              )
            case Some(node) =>
              // The decode has to live INSIDE the region `.timeout` and `.attempt` cover, not
              // chained after them: draining the reader is itself blocking I/O against the node
              // (the native client's `Ok` reader is a chained network reader), so a stall or a
              // parse error mid-drain is exactly as much a remote-node failure as one raised by
              // `send` itself, and must come back `Left(Remote(scrub(..)))` rather than escape as
              // a raw failed/cancelled IO. `close()` runs via `guarantee` so it fires exactly once
              // on every path: normal completion, a decode throw, or a cancellation from the
              // timeout firing after the `Ok` already arrived.
              //
              // `IO.uncancelable` + `poll` around both `send` and the decode: without it, a
              // cancellation observed at the flatMap boundary right after `send` returns `Ok` --
              // before the decode's own `.guarantee` has even been installed -- would cancel the
              // whole `run` with NO finalizer registered yet, leaking the reader and its node
              // connection (the native client's DISCONNECT chain never fires). Masking the gap
              // between "resource arrived" and "finalizer installed" is the same acquisition
              // discipline `bracket`/`Resource` use; `poll` re-admits cancellation only where it is
              // actually safe (waiting on `send`, and inside the finalizer-guarded decode).
              //
              // `IO.interruptible`, not `IO.blocking`, for the decode: empirically (this project's
              // cats-effect 3.7.0), `.timeout` racing a plain `IO.blocking` body does NOT preempt
              // it -- it waits for the blocking call to finish on its own and then reports ITS
              // outcome, discarding the deadline entirely (the pre-existing, deliberately accepted
              // "bounded wait, not cancellation" caveat on the routed-executor preview path in
              // `Main.scala`). `IO.interruptible` only buys real preemption for a reader that
              // actually responds to `Thread.interrupt()`: the native client's chained network
              // reader does. The JDBC/embedded fallback reader drains batches through native JNI
              // calls, which ignore interrupts, so on that path this too is only a bounded wait: on
              // a non-interruptible reader, or whenever `send` itself cannot be cancelled,
              // `timeoutSec` is only observed once that step returns on its own; `close()` still
              // fires exactly once once the call (eventually) returns.
              IO.uncancelable { poll =>
                poll(send(node, sql)).flatMap {
                  case QuackResponse.Failed(err, _) =>
                    IO.raiseError(RemoteFailure(err.toString))
                  case QuackResponse.Ok(reader, _, close) =>
                    poll(IO.interruptible(ArrowRowsDecoder.decode(reader, maxRows)._2))
                      .guarantee(IO(close()))
                }
              }.timeout(timeoutSec.seconds)
                .attempt
                .map {
                  case Right(rows)                                    => Right(rows)
                  case Left(_: java.util.concurrent.TimeoutException) =>
                    Left(MetadataFailure.TimedOut)
                  case Left(RemoteFailure(message)) => Left(MetadataFailure.Remote(scrub(message)))
                  case Left(t) => Left(MetadataFailure.Remote(scrub(String.valueOf(t.getMessage))))
                }

  // Remote catalogs echo request bodies into their errors; the redactor's blanket arm masks
  // encoded material even without a known credential set.
  private def scrub(s: String): String = AttachErrorRedactor.scrub(s, Set.empty)
