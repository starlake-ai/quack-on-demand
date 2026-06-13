package ai.starlake.quack.edge.cls

import cats.effect.IO
import com.github.benmanes.caffeine.cache.{Cache, Caffeine}

import java.time.Duration

/** Backed by an injected `fetch` function so we don't bind to a concrete `DuckLakeCatalogReader`
  * in this file (Task 15 wires the production fetcher in `Main.scala`). Caches at 5-second TTL
  * keyed by (catalog, schema, table) -- same TTL the catalog-browser endpoint uses (consistent
  * operator mental model). Failures cache as `Nil` for a tick so a broken federated source doesn't
  * pin a slow path forever.
  */
final class DuckLakeColumnCatalog(
    fetch: (String, String, String) => IO[List[String]]
) extends ColumnCatalog:

  private val cache: Cache[(String, String, String), List[String]] =
    Caffeine.newBuilder()
      .expireAfterWrite(Duration.ofSeconds(5))
      .maximumSize(4096)
      .build()

  def columnsOf(catalog: String, schemaName: String, tableName: String): IO[List[String]] =
    val key = (catalog, schemaName, tableName)
    Option(cache.getIfPresent(key)) match
      case Some(cached) => IO.pure(cached)
      case None =>
        fetch(catalog, schemaName, tableName)
          .attempt
          .map {
            case Right(cols) => cache.put(key, cols); cols
            case Left(_)     => cache.put(key, Nil);  Nil
          }