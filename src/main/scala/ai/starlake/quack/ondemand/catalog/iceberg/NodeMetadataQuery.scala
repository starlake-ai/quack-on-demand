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
              send(node, sql)
                .timeout(timeoutSec.seconds)
                .attempt
                .map {
                  case Left(_: java.util.concurrent.TimeoutException) =>
                    Left(MetadataFailure.TimedOut)
                  case Left(t) => Left(MetadataFailure.Remote(scrub(String.valueOf(t.getMessage))))
                  case Right(QuackResponse.Failed(err, _)) =>
                    Left(MetadataFailure.Remote(scrub(err.toString)))
                  case Right(QuackResponse.Ok(reader, _, close)) =>
                    try
                      val (_, rows, _) = ArrowRowsDecoder.decode(reader, maxRows)
                      Right(rows)
                    finally close()
                }

  // Remote catalogs echo request bodies into their errors; the redactor's blanket arm masks
  // encoded material even without a known credential set.
  private def scrub(s: String): String = AttachErrorRedactor.scrub(s, Set.empty)
