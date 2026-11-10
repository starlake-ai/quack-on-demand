package ai.starlake.quack.edge.cls

import ai.starlake.quack.observability.metrics.StatementInstruments
import cats.effect.IO
import com.github.benmanes.caffeine.cache.{Cache, Caffeine}

import java.time.Duration

/** Backed by an injected `fetch` function so we don't bind to a concrete `DuckLakeCatalogReader` in
  * this file (Task 15 wires the production fetcher in `Main.scala`). Caches at 5-second TTL keyed
  * by (tenant, catalog, schema, table) -- same TTL the catalog-browser endpoint uses (consistent
  * operator mental model). Failures cache as `Nil` for a tick so a broken federated source doesn't
  * pin a slow path forever.
  *
  * `instruments` is optional so existing test wiring that constructs `DuckLakeColumnCatalog`
  * directly keeps compiling without changes. When provided, cache hits/misses/errors are recorded
  * under `column_policy_catalog_lookups_total` with `tenant=""` / `pool=""` placeholder tags; a
  * follow-up task will thread per-statement (tenant, pool) context once it is available here.
  */
final class DuckLakeColumnCatalog(
    fetch: (String, String, String, String) => IO[List[String]],
    instruments: Option[StatementInstruments] = None,
    /** [[ColumnCatalog.historyStable]]'s answer; false (fail closed) when not wired. */
    history: (String, String, String, String) => IO[Boolean] = (_, _, _, _) => IO.pure(false)
) extends ColumnCatalog:

  private val cache: Cache[(String, String, String, String), List[String]] =
    Caffeine
      .newBuilder()
      .expireAfterWrite(Duration.ofSeconds(5))
      .maximumSize(4096)
      .build()

  private val stableCache: Cache[(String, String, String, String), java.lang.Boolean] =
    Caffeine
      .newBuilder()
      .expireAfterWrite(Duration.ofSeconds(5))
      .maximumSize(4096)
      .build()

  /** Cached like [[columnsOf]]; a failed lookup answers false, cached for a tick. */
  override def historyStable(
      tenant: String,
      catalog: String,
      schemaName: String,
      tableName: String
  ): IO[Boolean] =
    val key = (tenant, catalog, schemaName, tableName)
    Option(stableCache.getIfPresent(key)) match
      case Some(cached) => IO.pure(cached.booleanValue)
      case None         =>
        history(tenant, catalog, schemaName, tableName).attempt.map { r =>
          val stable = r.getOrElse(false)
          stableCache.put(key, stable)
          stable
        }

  def columnsOf(
      tenant: String,
      catalog: String,
      schemaName: String,
      tableName: String
  ): IO[List[String]] =
    val key = (tenant, catalog, schemaName, tableName)
    Option(cache.getIfPresent(key)) match
      case Some(cached) =>
        instruments.foreach(_.recordColumnPolicyCatalogLookup("", "", "hit"))
        IO.pure(cached)
      case None =>
        fetch(tenant, catalog, schemaName, tableName).attempt
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
