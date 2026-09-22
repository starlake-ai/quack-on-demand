package ai.starlake.quack.ondemand.federation.iceberg

import ai.starlake.quack.model.{FederatedSource, FederatedSourceType, PoolKey, RunningNode}
import ai.starlake.quack.ondemand.federation.ResolvedFederationBlock
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
  *   1. set aside every declared alias that collides with the tenant-db's OWN catalog alias
  *   2. list the node's attached catalogs
  *   3. diff against the remaining enabled `iceberg_rest` aliases declared for its pool
  *   4. re-issue THAT ONE source's rendered block for each missing alias
  *
  * Step 4 heals and diagnoses at once: a returned catalog attaches with no node restart, and a
  * still-broken one yields the real DuckDB error.
  *
  * Step 1 exists because the node's catalog listing CANNOT answer the question for a colliding
  * alias. `FederationBlobBuilder` refuses to render a source aliased the same as its own
  * tenant-db's catalog, so the node never ran that ATTACH -- yet the name is in
  * `duckdb_databases()` regardless, because it is the tenant-db's own DuckLake catalog. Matched
  * against the listing, such a source lands in `found`, `recordAttached` fires and the incarnation
  * latches: the node reports the Iceberg catalog as attached when it was never attached at all, and
  * queries against the alias silently hit DuckLake. Reachable through `ManifestImporter`, which
  * writes rows straight through `upsertSource` without the REST-time collision check in
  * `FederatedSourceHandlers`. So the collision is taken out of the match and recorded as a failure
  * naming the cause: it can never heal on its own, and the operator needs the name, not silence.
  *
  * `sourcesOf` returns an `IO`, not a plain value: the lookup makes a real Postgres round trip, and
  * everything in [[verify]] has to run inside the returned `IO` -- including calling `sourcesOf`
  * itself -- so a throw from it becomes a failed `IO` the caller's `handleErrorWith` can see,
  * instead of a synchronous exception that would kill the whole HealthProbe fiber (nobody joins
  * that fiber, so cats-effect would drop the failure silently and health probing would stop for
  * every node in the manager).
  *
  * `ownCatalogAliasOf` is REQUIRED, with no default. A default of `_ => IO.pure(None)` would
  * compile at every forgetful construction site and silently restore the false "attached" in step 1
  * -- the one outcome this component exists to prevent. A caller that genuinely cannot resolve the
  * alias has to say so in its own words.
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
    ownCatalogAliasOf: PoolKey => IO[Option[String]],
    renderOne: FederatedSource => IO[ResolvedFederationBlock],
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
                ownCatalogAliasOf(node.poolKey).flatMap { ownAlias =>
                  // Folded through the same helper as everything else it is compared with. `None`
                  // (the tenant-db could not be resolved) reserves nothing, which is parity with
                  // the deploy path, not a suppression: `FederationBlobBuilder` reserves the own
                  // alias from the same lookup, so a lookup that comes back empty there renders
                  // the ATTACH rather than refusing it, and there is then no collision to report.
                  val reserved                = ownAlias.map(foldAlias)
                  val (colliding, candidates) =
                    declared.partition(s => reserved.contains(foldAlias(s.alias)))
                  // Recorded BEFORE the listing: the collision is decided entirely by the
                  // control plane, so an unreachable node must not hide it.
                  colliding.traverse_(noteCollision(node, startedAtMs, _)) *>
                    listCatalogs(node).flatMap {
                      case Left(err) =>
                        // The node itself is unreachable or answered nonsense. Stay unlatched and
                        // say nothing: the health probe already owns node liveness.
                        IO.delay(
                          logger
                            .debug(s"attach verify ${node.nodeId}: catalog listing failed: $err")
                        )
                      case Right(present) =>
                        // Both sides folded through the same locale-independent helper: a default
                        // Turkish or Azeri locale folds `I` to the dotless `i`, and this match is
                        // a set `contains`, i.e. exact string equality after the fold, with no
                        // `equalsIgnoreCase` behind it to absorb the difference.
                        val lower            = present.map(foldAlias)
                        val (found, missing) =
                          candidates.partition(s => lower.contains(foldAlias(s.alias)))
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
    }

  /** Records a source whose alias IS the tenant-db's own catalog alias as a failure naming the
    * collision, rather than matching it against the node's catalog listing (where it would always
    * look attached, because the tenant-db itself is attached under that name).
    *
    * Deliberately does NOT re-issue the ATTACH: `FederationBlobBuilder.buildOne` reserves the same
    * alias set the deployed blob reserves and raises on this exact case, so the node would only be
    * handed a statement its own startup script already refused. Backoff-gated like [[reattach]], so
    * the attempt count grows on the same schedule and the whole pass can go quiet, while the stored
    * failure keeps the incarnation unlatched for as long as the collision stands.
    */
  private def noteCollision(
      node: RunningNode,
      startedAtMs: Long,
      src: FederatedSource
  ): IO[Unit] =
    if !registry.shouldRetry(node.nodeId, startedAtMs, src.alias) then IO.unit
    else
      note(
        node,
        startedAtMs,
        src,
        s"alias '${src.alias}' collides with the tenant-db's own catalog alias: the tenant-db " +
          "is already attached under that name, so this source can never attach -- rename the " +
          "source's alias",
        Set.empty
      )

  private def reattach(node: RunningNode, startedAtMs: Long, src: FederatedSource): IO[Unit] =
    if !registry.shouldRetry(node.nodeId, startedAtMs, src.alias) then IO.unit
    else
      renderOne(src).attempt.flatMap {
        case Left(t) =>
          // A config that will not render is a control-plane bug, not a catalog outage. Record
          // it the same way so it surfaces on the node and the source.
          //
          // No credential set is passed, and that is deliberate rather than inherited: rendering
          // failed, so nothing was resolved and there is nothing to name. Every raise on this path
          // (FederationBlobBuilder's config, alias-collision and unresolved-secret arms) names
          // aliases, secret NAMES and config errors, never a resolved value -- with one contrived
          // exception, the stray-placeholder arm, which quotes the already-substituted text and so
          // would quote a resolved secret that itself contained `{{...}}`. `scrub` is still called
          // for that reason: its blanket arm does not need a credential set to mask an
          // encoded-looking blob.
          note(node, startedAtMs, src, s"could not render attach SQL: ${t.getMessage}", Set.empty)
        case Right(block) =>
          runOnNode(node, block.sql).flatMap {
            case Right(_) =>
              IO.delay {
                registry.recordAttached(node.nodeId, startedAtMs, src.alias)
                logger.info(
                  s"attach verify ${node.nodeId}: catalog '${src.alias}' attached on retry"
                )
              }
            // The builder reports the values it substituted into this exact block, so `note` can
            // scrub the precise credentials this attempt handed DuckDB out of whatever the catalog
            // echoed back. `credentialsIn` is unioned in as defence in depth, for a credential that
            // somehow reached the SQL without passing through substitution.
            case Left(err) =>
              note(
                node,
                startedAtMs,
                src,
                err,
                block.secretValues ++ AttachErrorRedactor.credentialsIn(block.sql)
              )
          }
      }

  /** The one alias fold, [[ai.starlake.quack.model.FederatedAlias.fold]], used on BOTH sides of the
    * present-versus-declared match above. `AttachStatusRegistry` folds its keys through the same
    * helper, so a failure cannot be recorded under one spelling and looked up under another.
    */
  private def foldAlias(alias: String): String =
    ai.starlake.quack.model.FederatedAlias.fold(alias)

  /** The ONE funnel every STORED attach error goes through, which is why the redaction lives here
    * rather than at the REST rendering sites: `AttachStatusRegistry.recordFailure` has no other
    * non-test caller, so the registry entry -- and therefore `NodeInfo.catalogAttachFailures` --
    * and the WARN below both carry scrubbed text.
    *
    * The narrower half of that claim, stated because the broad version was wrong: this is NOT the
    * only place the raw error can reach the MANAGER LOG. `QuackHttpClient` logs the same string
    * verbatim at DEBUG before `note` ever sees it (`native query failed: ...` and `quack_query
    * failed: ...`), on both transports. That is generic adapter code outside this feature and off
    * at the default level, but a manager running at DEBUG can still have a credential in its log.
    *
    * See [[AttachErrorRedactor]] for the proven vector and for exactly what `scrub` guarantees.
    */
  private def note(
      node: RunningNode,
      startedAtMs: Long,
      src: FederatedSource,
      err: String,
      credentials: Set[String]
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

object IcebergAttachVerifier extends LazyLogging:

  /** Decodes `duckdb_databases()` batches into the set of attached catalog names, and always closes
    * the reader. The quack wire can emit a schema-only first batch, so batches are drained rather
    * than assuming the first carries rows (same reason `EngineStats.fromReader` loops).
    *
    * Total: this never throws. A decoding surprise becomes a `Left`. A `close()` that itself throws
    * is caught and logged, NOT turned into a `Left` -- a bare `finally close()` would have let it
    * propagate out and replace the value this method was about to return, which is the one way a
    * helper whose whole point is an `Either` still hands its caller an exception. It is not a
    * `Left` either, because by then the catalog names have already been decoded correctly and a
    * reader that failed to close is not a reason to discard them; only `Main.nodeCatalogs`'s
    * `.handleError` stood between that and a crash before, and a helper should not need one.
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
    finally
      try close()
      catch
        case t: Throwable =>
          logger.debug(s"closing the duckdb_databases() reader failed: ${t.getMessage}")
