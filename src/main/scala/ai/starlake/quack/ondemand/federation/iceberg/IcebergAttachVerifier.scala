package ai.starlake.quack.ondemand.federation.iceberg

import ai.starlake.quack.model.{FederatedSource, FederatedSourceType, PoolKey, RunningNode}
import cats.effect.IO
import cats.syntax.all.*
import com.typesafe.scalalogging.LazyLogging

/** Makes a silently-failed Iceberg ATTACH visible, and heals it when it was transient.
  *
  * The problem: all four auth modes authenticate ONCE, at ATTACH, during node spawn. The DuckDB CLI
  * reads the node's init SQL from a pipe with bail off, so a failed ATTACH prints its error to the
  * node's stderr and execution CONTINUES to `quack_serve`. The node then passes the `SELECT 1`
  * health probe and is routed to like any other, with the catalog simply absent, and users get
  * `Catalog 'x' does not exist` instead of "expired credentials" or "catalog unreachable".
  *
  * Per health tick, until the node latches:
  *   1. list the node's attached catalogs
  *   2. diff against the enabled `iceberg_rest` aliases declared for its pool
  *   3. re-issue THAT ONE source's rendered block for each missing alias
  *
  * Step 3 heals and diagnoses at once: a returned catalog attaches with no node restart, and a
  * still-broken one yields the real DuckDB error.
  */
final class IcebergAttachVerifier(
    sourcesOf: PoolKey => List[FederatedSource],
    renderOne: FederatedSource => IO[String],
    runOnNode: (RunningNode, String) => IO[Either[String, Unit]],
    listCatalogs: RunningNode => IO[Either[String, Set[String]]],
    registry: AttachStatusRegistry
) extends LazyLogging:

  def verify(node: RunningNode): IO[Unit] =
    if registry.latched(node.nodeId) then IO.unit
    else
      val declared = sourcesOf(node.poolKey).filter { s =>
        s.sourceType == FederatedSourceType.IcebergRest && !s.disabled
      }
      // Nothing declared: latch without ever touching the node, so the overwhelmingly common
      // pool (no Iceberg catalog at all) costs exactly zero round-trips.
      if declared.isEmpty then IO.delay(registry.markLatched(node.nodeId))
      else
        listCatalogs(node).flatMap {
          case Left(err) =>
            // The node itself is unreachable or answered nonsense. Stay unlatched and say
            // nothing: the health probe already owns node liveness.
            IO.delay(logger.debug(s"attach verify ${node.nodeId}: catalog listing failed: $err"))
          case Right(present) =>
            val lower   = present.map(_.toLowerCase)
            val missing = declared.filterNot(s => lower.contains(s.alias.toLowerCase))
            missing.traverse_(reattach(node, _)) *> IO.delay {
              if registry.failuresFor(node.nodeId).isEmpty then registry.markLatched(node.nodeId)
            }
        }

  private def reattach(node: RunningNode, src: FederatedSource): IO[Unit] =
    if !registry.shouldRetry(node.nodeId, src.alias) then IO.unit
    else
      renderOne(src).attempt.flatMap {
        case Left(t) =>
          // A config that will not render is a control-plane bug, not a catalog outage. Record
          // it the same way so it surfaces on the node and the source.
          note(node, src, s"could not render attach SQL: ${t.getMessage}")
        case Right(sql) =>
          runOnNode(node, sql).flatMap {
            case Right(_) =>
              IO.delay {
                registry.recordAttached(node.nodeId, src.alias)
                logger.info(
                  s"attach verify ${node.nodeId}: catalog '${src.alias}' attached on retry"
                )
              }
            case Left(err) => note(node, src, err)
          }
      }

  private def note(node: RunningNode, src: FederatedSource, err: String): IO[Unit] =
    IO.delay {
      val noteworthy = registry.recordFailure(node.nodeId, src.alias, err)
      if noteworthy then
        logger.warn(
          s"iceberg catalog '${src.alias}' is NOT attached on node ${node.nodeId} " +
            s"(tenant=${node.poolKey.tenant} db=${node.poolKey.tenantDb} " +
            s"pool=${node.poolKey.pool}): $err"
        )
    }
