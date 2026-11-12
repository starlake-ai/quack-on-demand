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
  * pin a slow path forever.
  *
  * `instruments` is optional so test wiring can construct it directly. When provided, cache
  * hits/misses/errors are recorded under `column_policy_catalog_lookups_total` with `tenant=""` /
  * `pool=""` placeholder tags.
  */
final class CachedColumnCatalog(
    fetch: (String, Option[PoolKey], String, String, String) => IO[List[String]],
    instruments: Option[StatementInstruments] = None
) extends ColumnCatalog:

  private val cache: Cache[(String, Option[PoolKey], String, String, String), List[String]] =
    Caffeine
      .newBuilder()
      .expireAfterWrite(Duration.ofSeconds(5))
      .maximumSize(4096)
      .build()

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
