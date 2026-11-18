package ai.starlake.quack.edge.cls

import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.observability.metrics.StatementInstruments
import cats.effect.IO
import com.github.benmanes.caffeine.cache.{Cache, Caffeine}

import java.time.Duration

/** A [[ColumnCatalog]] over an injected `fetch` (wired in `EdgeRewriters.columnCatalog`: a DuckLake
  * catalog read from Postgres, else the statement's pool asked). Caches at 5-second TTL keyed by
  * (tenant, pool, catalog, schema, table), the same TTL the catalog-browser endpoint uses. The pool
  * is part of the key because a catalog a node holds is the pool's: two pools may attach different
  * sources under one alias. Failures cache as `Nil` for a tick so a broken federated source doesn't
  * pin a slow path forever. [[historyStable]] keys its answers on the catalog's newest snapshot as
  * well.
  *
  * `instruments` is optional so test wiring can construct it directly. When provided, cache
  * hits/misses/errors are recorded under `column_policy_catalog_lookups_total` with `tenant=""` /
  * `pool=""` placeholder tags.
  */
final class CachedColumnCatalog(
    fetch: (String, Option[PoolKey], String, String, String) => IO[List[String]],
    instruments: Option[StatementInstruments] = None,
    /** [[ColumnCatalog.historyStable]]'s answer, DuckLake metadata only (never a node DESCRIBE);
      * false (fail closed) when not wired.
      */
    history: (String, Option[PoolKey], String, String, String) => IO[Boolean] = (_, _, _, _, _) =>
      IO.pure(false),
    /** The newest snapshot id of the catalog `history` reads for (tenant, pool, catalog), read on
      * every [[historyStable]] check; None (fail closed) when there is none or when not wired.
      */
    latestSnapshot: (String, Option[PoolKey], String) => IO[Option[Long]] = (_, _, _) =>
      IO.pure(None)
) extends ColumnCatalog:

  private val cache: Cache[(String, Option[PoolKey], String, String, String), List[String]] =
    Caffeine
      .newBuilder()
      .expireAfterWrite(Duration.ofSeconds(5))
      .maximumSize(4096)
      .build()

  /** [[historyStable]]'s answers, under the catalog's newest snapshot as well as the table. */
  private val stableCache
      : Cache[(String, Option[PoolKey], String, String, String, Long), java.lang.Boolean] =
    Caffeine
      .newBuilder()
      .expireAfterWrite(Duration.ofSeconds(5))
      .maximumSize(4096)
      .build()

  /** (tenant, pool, catalog) whose newest snapshot could not be read: refused for a tick. */
  private val unreadable: Cache[(String, Option[PoolKey], String), java.lang.Boolean] =
    Caffeine
      .newBuilder()
      .expireAfterWrite(Duration.ofSeconds(5))
      .maximumSize(4096)
      .build()

  /** Reads the catalog's newest snapshot on every check, and keeps an answer under it (for 5 s at
    * most): any commit since, a rename included, is a miss, so a true never outlives the catalog
    * state it was read on. The snapshot is read before the history, so what a kept true was read on
    * is that snapshot or newer. A snapshot that cannot be read answers false, kept for a tick so a
    * metadata Postgres that does not answer costs one bounded read per tick, not one per statement;
    * a failed history read answers false, kept under its snapshot.
    */
  override def historyStable(
      tenant: String,
      pool: Option[PoolKey],
      catalog: String,
      schemaName: String,
      tableName: String
  ): IO[Boolean] =
    IO.defer {
      if unreadable.getIfPresent((tenant, pool, catalog)) != null then IO.pure(false)
      else
        latestSnapshot(tenant, pool, catalog).attempt.flatMap {
          case Right(Some(snapshot)) =>
            val key = (tenant, pool, catalog, schemaName, tableName, snapshot)
            Option(stableCache.getIfPresent(key)) match
              case Some(cached) => IO.pure(cached.booleanValue)
              case None         =>
                history(tenant, pool, catalog, schemaName, tableName).attempt.map { r =>
                  val stable = r.getOrElse(false)
                  stableCache.put(key, stable)
                  stable
                }
          case Right(None) => IO.pure(false)
          case Left(_)     =>
            IO(unreadable.put((tenant, pool, catalog), java.lang.Boolean.TRUE)).as(false)
        }
    }

  def columnsOf(
      tenant: String,
      pool: Option[PoolKey],
      catalog: String,
      schemaName: String,
      tableName: String
  ): IO[List[String]] =
    val key = (tenant, pool, catalog, schemaName, tableName)
    Option(cache.getIfPresent(key)) match
      case Some(cached) =>
        instruments.foreach(_.recordColumnPolicyCatalogLookup("", "", "hit"))
        IO.pure(cached)
      case None =>
        fetch(tenant, pool, catalog, schemaName, tableName).attempt
          .map {
            case Right(cols) =>
              cache.put(key, cols)
              instruments.foreach(_.recordColumnPolicyCatalogLookup("", "", "miss"))
              cols
            case Left(_) =>
              cache.put(key, Nil)
              instruments.foreach(_.recordColumnPolicyCatalogLookup("", "", "error"))
              Nil
          }
