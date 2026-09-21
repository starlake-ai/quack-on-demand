package ai.starlake.quack.ondemand.federation.iceberg

import ai.starlake.quack.model.{FederatedSource, FederatedSourceType, PoolKey, RunningNode}
import cats.effect.IO
import cats.syntax.all.*
import com.typesafe.scalalogging.LazyLogging
import org.apache.arrow.vector.ipc.ArrowReader

/** Makes a silently-failed Iceberg ATTACH visible, and heals it when it was transient.
  *
  * The problem: all four auth modes authenticate ONCE, at ATTACH, during node spawn. The DuckDB CLI
  * reads the node's init SQL from a pipe with bail off, so a failed ATTACH prints its error to the
  * node's stderr and execution CONTINUES to `quack_serve`. The node then passes the `SELECT 1`
  * health probe and is routed to like any other, with the catalog simply absent, and users get
  * `Catalog 'x' does not exist` instead of "expired credentials" or "catalog unreachable".
  *
  * Per health tick, until the node's incarnation latches:
  *   1. list the node's attached catalogs
  *   2. diff against the enabled `iceberg_rest` aliases declared for its pool
  *   3. re-issue THAT ONE source's rendered block for each missing alias
  *
  * Step 3 heals and diagnoses at once: a returned catalog attaches with no node restart, and a
  * still-broken one yields the real DuckDB error.
  *
  * `sourcesOf` returns an `IO`, not a plain value: the lookup makes a real Postgres round trip, and
  * everything in [[verify]] has to run inside the returned `IO` -- including calling `sourcesOf`
  * itself -- so a throw from it becomes a failed `IO` the caller's `handleErrorWith` can see,
  * instead of a synchronous exception that would kill the whole HealthProbe fiber (nobody joins
  * that fiber, so cats-effect would drop the failure silently and health probing would stop for
  * every node in the manager).
  *
  * Once every alias this incarnation has ever tried has failed, and none of them is due for a retry
  * per [[AttachStatusRegistry.shouldRetry]], the WHOLE pass is skipped -- `sourcesOf` and
  * `listCatalogs` are not called at all, not just the re-attach. Without this, a catalog that is
  * genuinely and permanently broken (an expired credential on a pool nobody is going to fix) would
  * cost one non-pooled Postgres connection plus one node round trip on every health tick, forever
  * (`healthCheckIntervalSec = 5` by default). The one behaviour this trades away: a source ADDED to
  * a pool while it is in backoff is not picked up until the current backoff window ends, bounded by
  * `maxBackoffMs` (5 minutes by default) -- never longer than that ceiling.
  */
final class IcebergAttachVerifier(
    sourcesOf: PoolKey => IO[List[FederatedSource]],
    renderOne: FederatedSource => IO[String],
    runOnNode: (RunningNode, String) => IO[Either[String, Unit]],
    listCatalogs: RunningNode => IO[Either[String, Set[String]]],
    registry: AttachStatusRegistry
) extends LazyLogging:

  def verify(node: RunningNode): IO[Unit] =
    IO.defer {
      val startedAtMs = node.startedAt.toEpochMilli
      registry.pruneOtherIncarnations(node.nodeId, startedAtMs)
      if registry.latched(node.nodeId, startedAtMs) then IO.unit
      else
        val pastFailures  = registry.failuresFor(node.nodeId, startedAtMs)
        val allBackingOff = pastFailures.nonEmpty &&
          pastFailures.forall(f => !registry.shouldRetry(node.nodeId, startedAtMs, f.alias))
        if allBackingOff then IO.unit
        else
          sourcesOf(node.poolKey).attempt.flatMap {
            case Left(t) =>
              // The lookup itself failed -- a missing tenant-db in the supervisor's cache, or a
              // Postgres blip. That is NOT the same as "this pool declares no Iceberg source": stay
              // unlatched and say nothing at info/warn, so the next tick retries.
              IO.delay(
                logger.debug(s"attach verify ${node.nodeId}: source lookup failed: ${t.getMessage}")
              )
            case Right(sources) =>
              val declared = sources.filter { s =>
                s.sourceType == FederatedSourceType.IcebergRest && !s.disabled
              }
              // Nothing declared: latch without ever touching the node, so the overwhelmingly
              // common pool (no Iceberg catalog at all) costs exactly zero round-trips.
              if declared.isEmpty then IO.delay(registry.markLatched(node.nodeId, startedAtMs))
              else
                listCatalogs(node).flatMap {
                  case Left(err) =>
                    // The node itself is unreachable or answered nonsense. Stay unlatched and say
                    // nothing: the health probe already owns node liveness.
                    IO.delay(
                      logger.debug(s"attach verify ${node.nodeId}: catalog listing failed: $err")
                    )
                  case Right(present) =>
                    val lower            = present.map(_.toLowerCase)
                    val (found, missing) =
                      declared.partition(s => lower.contains(s.alias.toLowerCase))
                    found.traverse_(s =>
                      IO.delay(registry.recordAttached(node.nodeId, startedAtMs, s.alias))
                    ) *>
                      missing.traverse_(reattach(node, startedAtMs, _)) *> IO.delay {
                        if registry.failuresFor(node.nodeId, startedAtMs).isEmpty then
                          registry.markLatched(node.nodeId, startedAtMs)
                      }
                }
          }
    }

  private def reattach(node: RunningNode, startedAtMs: Long, src: FederatedSource): IO[Unit] =
    if !registry.shouldRetry(node.nodeId, startedAtMs, src.alias) then IO.unit
    else
      renderOne(src).attempt.flatMap {
        case Left(t) =>
          // A config that will not render is a control-plane bug, not a catalog outage. Record
          // it the same way so it surfaces on the node and the source.
          note(node, startedAtMs, src, s"could not render attach SQL: ${t.getMessage}")
        case Right(sql) =>
          runOnNode(node, sql).flatMap {
            case Right(_) =>
              IO.delay {
                registry.recordAttached(node.nodeId, startedAtMs, src.alias)
                logger.info(
                  s"attach verify ${node.nodeId}: catalog '${src.alias}' attached on retry"
                )
              }
            // The credentials come from the SQL we just rendered, so `note` can scrub the exact
            // values this attempt handed DuckDB out of whatever the catalog echoed back.
            case Left(err) =>
              note(node, startedAtMs, src, err, AttachErrorRedactor.credentialsIn(sql))
          }
      }

  /** The ONE funnel every stored attach error goes through, which is why the redaction lives here
    * rather than at the REST rendering sites: the registry entry AND the WARN below both get the
    * scrubbed text, so neither the API response nor the manager log can carry a credential a
    * hostile or merely verbose catalog echoed back. See [[AttachErrorRedactor]] for the proven
    * vector.
    */
  private def note(
      node: RunningNode,
      startedAtMs: Long,
      src: FederatedSource,
      err: String,
      credentials: Set[String] = Set.empty
  ): IO[Unit] =
    IO.delay {
      val safe       = AttachErrorRedactor.scrub(err, credentials)
      val noteworthy = registry.recordFailure(node.nodeId, startedAtMs, src.alias, safe)
      if noteworthy then
        logger.warn(
          s"iceberg catalog '${src.alias}' is NOT attached on node ${node.nodeId} " +
            s"(tenant=${node.poolKey.tenant} db=${node.poolKey.tenantDb} " +
            s"pool=${node.poolKey.pool}): $safe"
        )
    }

object IcebergAttachVerifier:

  /** Decodes `duckdb_databases()` batches into the set of attached catalog names, and always closes
    * the reader. The quack wire can emit a schema-only first batch, so batches are drained rather
    * than assuming the first carries rows (same reason `EngineStats.fromReader` loops); any
    * surprise, including a `close()` that itself throws, becomes a `Left`, never an exception.
    */
  def decodeCatalogNames(rows: ArrowReader, close: () => Unit): Either[String, Set[String]] =
    try
      val acc = scala.collection.mutable.Set.empty[String]
      while rows.loadNextBatch() do
        val root = rows.getVectorSchemaRoot
        val vec  = root.getFieldVectors.get(0)
        var i    = 0
        while i < root.getRowCount do
          Option(vec.getObject(i)).foreach(v => acc += v.toString)
          i += 1
      Right(acc.toSet)
    catch case t: Throwable => Left(s"could not decode duckdb_databases(): ${t.getMessage}")
    finally close()
