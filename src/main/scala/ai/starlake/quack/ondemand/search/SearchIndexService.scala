package ai.starlake.quack.ondemand.search

import ai.starlake.quack.model.{SearchIndex, SearchIndexField, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.catalog.{DuckLakeCatalogReader, LiveTable}
import ai.starlake.quack.ondemand.state.SearchIndexStore
import cats.effect.IO
import sttp.model.StatusCode

import java.time.Instant
import java.util.{Locale, UUID}

/** A refusal of the registry, with the REST status and code it answers. */
final case class SearchError(status: StatusCode, code: String, message: String)

/** What the registry reads of a tenant-db's DuckLake catalog. Each read may fail (the catalog's
  * Postgres down): the service answers `503 catalog_unavailable` and names nothing of the cause.
  */
trait SearchCatalog:

  /** `schema.table` at the current snapshot ([[LiveTable]]: its table id, the catalog's spelling of
    * its names, its live top-level columns), None when no live table has that name.
    */
  def liveTableColumns(td: TenantDb, schema: String, table: String): IO[Option[LiveTable]]

  /** The current `(schema, table)` of a live table id, None when it is no longer live. */
  def liveTableName(td: TenantDb, tableId: Long): IO[Option[(String, String)]]

object SearchCatalog:

  /** Over the manager's cached per-tenant-db readers (`CatalogReaders.get(tenant, tenantDb)`). */
  def over(readerOf: (String, String) => DuckLakeCatalogReader): SearchCatalog = new SearchCatalog:
    def liveTableColumns(td: TenantDb, schema: String, table: String) =
      IO.blocking(readerOf(td.tenantId, td.name).liveTableColumns(schema, table))
    def liveTableName(td: TenantDb, tableId: Long) =
      IO.blocking(readerOf(td.tenantId, td.name).liveTableName(tableId))

/** An index as `list` shows it: its tenant-db and the table's current name (None when the table is
  * gone, or the catalog could not be read).
  */
final case class ListedIndex(
    index: SearchIndex,
    tenantDb: TenantDb,
    current: Option[(String, String)]
)

/** An index resolved for a build: its tenant-db, the table's current schema and name, and the
  * current name of each indexed field, in index order.
  */
final case class BuildTarget(
    tenantDb: TenantDb,
    index: SearchIndex,
    schema: String,
    table: String,
    columns: List[String]
)

/** The QoD Search index registry (spec 6.1): create, list and drop an index of a DuckLake table.
  * The callers gate first (`SearchHandlers`: a session or a PAT, then the tenant scope); this
  * decides on the request and the catalog only. The table id and the field ids are read from the
  * catalog, never taken from a request.
  *
  * `tenantDbOf(tenant, database)` and `tenantDbsOf(tenant)` look rows up inside one tenant, so a
  * database of another tenant is as absent as one that does not exist. `metastoreOf` is the row's
  * effective metastore (its schema default and data path, `PoolSupervisor.effectiveMetastoreFor`).
  */
final class SearchIndexService(
    tenantDbOf: (String, String) => Option[TenantDb],
    tenantDbsOf: String => List[TenantDb],
    metastoreOf: TenantDb => Map[String, String],
    catalog: SearchCatalog,
    store: SearchIndexStore,
    newId: () => UUID = () => UUID.randomUUID(),
    now: () => Instant = () => Instant.now()
):
  import SearchIndexService.*

  private type Out[A] = IO[Either[SearchError, A]]

  /** Creates an index on `table` (`table` or `schema.table`) over `columns`, refusing in this
    * order: no such database (`404 not_found`), a branch (`400 search_branch_unsupported`), not
    * DuckLake (`400 search_ducklake_only`), encrypted at rest (`400 search_encrypted_unsupported`),
    * a table name that is not plain identifiers (`400 invalid_name`), a request naming `options`,
    * `k1` or `b` (`400 invalid_search_options`), not 1 to 32 distinct columns (`400
    * invalid_search_columns`), an unreadable catalog (`503 catalog_unavailable`), no such live
    * table (`404 table_not_found`), a column it does not have or that is not VARCHAR (`400
    * search_column_not_found`, `400 search_column_type`), a row with no data path to hold bundles
    * (`409 search_no_data_path`), and an index on the table already (`409 search_index_exists`).
    */
  def create(
      createdBy: String,
      tenant: String,
      database: String,
      table: String,
      columns: List[String],
      namesOptions: Boolean
  ): Out[SearchIndex] =
    val checked: Either[SearchError, (TenantDb, String, String, List[String])] =
      for
        td              <- indexable(tenant, database)
        (schema, tname) <- tableName(td, table)
        _               <- Either.cond(!namesOptions, (), FixedOptions)
        cols            <- columnList(columns)
      yield (td, schema, tname, cols)
    checked match
      case Left(e)                          => IO.pure(Left(e))
      case Right((td, schema, tname, cols)) =>
        read(catalog.liveTableColumns(td, schema, tname)).flatMap {
          case Left(e)           => IO.pure(Left(e))
          case Right(None)       => IO.pure(Left(tableNotFound(schema, tname)))
          case Right(Some(live)) =>
            // The catalog's spelling of the names, never the request's (review M1).
            val tableId = live.tableId
            val made    = for
              fields <- fieldsOf(live.schema, live.table, cols, live.columns)
              root   <- bundleRootOf(td)
            yield SearchIndex(
              indexId = newId(),
              tenantDbId = td.id,
              schemaName = live.schema,
              tableName = live.table,
              tableId = tableId,
              fields = fields,
              bundleRoot = root,
              state = SearchIndex.Ready,
              segmentsVersion = 0L,
              createdBy = createdBy,
              createdAt = now()
            )
            made match
              case Left(e)   => IO.pure(Left(e))
              case Right(ix) =>
                store.find(td.id, tableId).flatMap {
                  case Some(_) => IO.pure(Left(exists(live.schema, live.table)))
                  case None    =>
                    store.create(ix).map(_.left.map(_ => exists(live.schema, live.table)))
                }
        }

  /** The indexes of `database`, or of every database of the tenant `visible` admits when None (`404
    * not_found` for a named database it does not hold; the caller refuses a named one `visible`
    * does not admit before asking). Each index's table is read live: an index whose table id is no
    * longer live answers `disabled` (spec 6.1: a table created again is another table id), and a
    * renamed table shows its current name. A list writes nothing: the row keeps its stored state
    * (the node finds no record for a dropped table anyway, as it looks one up by table id). A
    * catalog that cannot be read answers its indexes as stored, with no current name.
    */
  def list(
      tenant: String,
      database: Option[String],
      visible: TenantDb => Boolean
  ): Out[List[ListedIndex]] =
    val rows: Either[SearchError, List[TenantDb]] = database match
      case Some(d) => tenantDbOf(tenant, d).map(List(_)).toRight(notFound(tenant, d))
      case None    => Right(tenantDbsOf(tenant).filter(visible))
    rows match
      case Left(e)    => IO.pure(Left(e))
      case Right(tds) =>
        val byId = tds.map(td => td.id -> td).toMap
        store.list(byId.keySet).flatMap { ixs =>
          ixs
            .foldLeft(IO.pure(List.empty[ListedIndex])) { (acc, ix) =>
              acc.flatMap(done => listed(byId(ix.tenantDbId), ix).map(done :+ _))
            }
            .map(Right(_))
        }

  private def listed(td: TenantDb, ix: SearchIndex): IO[ListedIndex] =
    catalog.liveTableName(td, ix.tableId).attempt.map {
      case Right(Some(name)) => ListedIndex(ix, td, Some(name))
      case Right(None)       => ListedIndex(ix.copy(state = SearchIndex.Disabled), td, None)
      case Left(_)           => ListedIndex(ix, td, None)
    }

  /** Drops an index, named by `target`: its table's current name, resolved to its table id through
    * the catalog (an index whose table is gone, by its name at create), or its index id, which also
    * reaches a disabled index whose name a recreated table now holds (review M2). Its bundles stay,
    * under a tombstone for Phase 2's GC (D3). `404 not_found` when there is none, the same answer
    * for an id another database holds.
    */
  def delete(tenant: String, database: String, target: DeleteTarget): Out[SearchIndex] =
    tenantDbOf(tenant, database) match
      case None     => IO.pure(Left(notFound(tenant, database)))
      case Some(td) =>
        val found: Out[SearchIndex] = target match
          case DeleteTarget.ByIndexId(id) =>
            val noSuch = SearchError(StatusCode.NotFound, "not_found", s"no search index $id")
            scala.util.Try(UUID.fromString(id)).toOption.filter(_.toString == id) match
              case None       => IO.pure(Left(noSuch))
              case Some(uuid) =>
                store.findByIndexId(uuid).map(_.filter(_.tenantDbId == td.id).toRight(noSuch))
          case DeleteTarget.ByTable(table) =>
            tableName(td, table) match
              case Left(e)                => IO.pure(Left(e))
              case Right((schema, tname)) => resolveIndex(td, schema, tname)
        found.flatMap {
          case Left(e)   => IO.pure(Left(e))
          case Right(ix) =>
            store.delete(ix.indexId).map { deleted =>
              Either.cond(deleted, ix, noIndex(ix.schemaName, ix.tableName))
            }
        }

  /** The index of `table` by its live table id, else (no live table of that name) the one index of
    * the row whose name at create is that name and whose table is no longer live.
    */
  private def resolveIndex(td: TenantDb, schema: String, tname: String): Out[SearchIndex] =
    read(catalog.liveTableColumns(td, schema, tname)).flatMap {
      case Left(e)           => IO.pure(Left(e))
      case Right(Some(live)) =>
        store.find(td.id, live.tableId).map(_.toRight(noIndex(live.schema, live.table)))
      case Right(None) =>
        store.list(Set(td.id)).flatMap { ixs =>
          val named = ixs.filter(ix =>
            ix.schemaName.equalsIgnoreCase(schema) && ix.tableName.equalsIgnoreCase(tname)
          )
          named
            .foldLeft(IO.pure[Either[SearchError, List[SearchIndex]]](Right(Nil))) { (acc, ix) =>
              acc.flatMap {
                case Left(e)     => IO.pure(Left(e))
                case Right(gone) =>
                  read(catalog.liveTableName(td, ix.tableId)).map(_.map {
                    case None    => gone :+ ix
                    case Some(_) => gone
                  })
              }
            }
            .map(_.flatMap {
              case ix :: Nil => Right(ix)
              case _         => Left(noIndex(schema, tname))
            })
        }
    }

  /** The index of `table` as a build needs it (D7): the refusals of a create for the row, then the
    * index by the table's live id (`404 not_found` with none), `ready` (`409
    * search_index_disabled`), and every field still a live column (`409 search_column_dropped`).
    * The schema, table and column names are the catalog's, now (review M1).
    */
  def buildTarget(tenant: String, database: String, table: String): Out[BuildTarget] =
    (for
      td              <- indexable(tenant, database)
      (schema, tname) <- tableName(td, table)
    yield (td, schema, tname)) match
      case Left(e)                    => IO.pure(Left(e))
      case Right((td, schema, tname)) =>
        read(catalog.liveTableColumns(td, schema, tname)).flatMap {
          case Left(e)           => IO.pure(Left(e))
          case Right(None)       => IO.pure(Left(tableNotFound(schema, tname)))
          case Right(Some(live)) =>
            val (lschema, ltable) = (live.schema, live.table)
            store.find(td.id, live.tableId).map {
              case None                                      => Left(noIndex(lschema, ltable))
              case Some(ix) if ix.state != SearchIndex.Ready =>
                Left(
                  SearchError(
                    StatusCode.Conflict,
                    "search_index_disabled",
                    s"the index on $lschema.$ltable is disabled"
                  )
                )
              case Some(ix) =>
                val byField = live.columns.map((n, id, _) => id -> n).toMap
                val names   = ix.fields.map(f => byField.get(f.fieldId))
                if names.exists(_.isEmpty) then
                  Left(
                    SearchError(
                      StatusCode.Conflict,
                      "search_column_dropped",
                      s"an indexed column of $lschema.$ltable is no longer a column of the table"
                    )
                  )
                else Right(BuildTarget(td, ix, lschema, ltable, names.flatten))
            }
        }

  // ---- the row and request checks ----

  private def indexable(tenant: String, database: String): Either[SearchError, TenantDb] =
    tenantDbOf(tenant, database) match
      case None                             => Left(notFound(tenant, database))
      case Some(td) if td.branchOf.nonEmpty =>
        Left(
          SearchError(
            StatusCode.BadRequest,
            "search_branch_unsupported",
            s"'$database' is a branch; search indexes a branch's parent database only"
          )
        )
      case Some(td) if td.kind != TenantDbKind.DuckLake =>
        Left(
          SearchError(
            StatusCode.BadRequest,
            "search_ducklake_only",
            s"'$database' is not a DuckLake database; only DuckLake tables can be indexed"
          )
        )
      case Some(td) if td.encrypted =>
        // A bundle holds the indexed text in the clear until Phase 2 seals bundles (spec 6.2;
        // OWNER RULING 2026-11-16, D3).
        Left(
          SearchError(
            StatusCode.BadRequest,
            "search_encrypted_unsupported",
            s"'$database' is encrypted at rest; search indexes no encrypted database in this release"
          )
        )
      case Some(td) => Right(td)

  /** `table` or `schema.table`, plain identifiers; the schema defaults to the row's session path's
    * (its `defaultSchema`, else the metastore's `schemaName`, else `main`).
    */
  private def tableName(td: TenantDb, table: String): Either[SearchError, (String, String)] =
    table.split("\\.", -1).toList match
      case t :: Nil if validName(t)                      => Right(defaultSchema(td) -> t)
      case s :: t :: Nil if validName(s) && validName(t) => Right(s -> t)
      case _                                             =>
        Left(
          SearchError(
            StatusCode.BadRequest,
            "invalid_name",
            "table must be <table> or <schema>.<table>, each a plain identifier " +
              "([A-Za-z_][A-Za-z0-9_]*)"
          )
        )

  private def defaultSchema(td: TenantDb): String =
    td.defaultSchema
      .map(_.trim)
      .filter(_.nonEmpty)
      .orElse(metastoreOf(td).get("schemaName").map(_.trim).filter(_.nonEmpty))
      .getOrElse("main")

  private def columnList(columns: List[String]): Either[SearchError, List[String]] =
    val cols = columns.map(_.trim)
    if cols.isEmpty || cols.size > SearchIndex.MaxFields || cols.exists(_.isEmpty) then
      Left(
        SearchError(
          StatusCode.BadRequest,
          "invalid_search_columns",
          s"an index takes 1 to ${SearchIndex.MaxFields} columns"
        )
      )
    else if cols.map(fold).distinct.size != cols.size then
      Left(
        SearchError(
          StatusCode.BadRequest,
          "invalid_search_columns",
          "a column is named more than once"
        )
      )
    else Right(cols)

  /** Each requested column as the catalog spells it, with its field id, in request order. A name
    * matches ignoring case, the exact spelling first, as DuckDB resolves a column.
    */
  private def fieldsOf(
      schema: String,
      table: String,
      cols: List[String],
      live: List[(String, Long, String)]
  ): Either[SearchError, List[SearchIndexField]] =
    cols.foldLeft[Either[SearchError, List[SearchIndexField]]](Right(Nil)) { (acc, c) =>
      acc.flatMap { done =>
        val hit = live.find(_._1 == c).orElse {
          live.filter(l => fold(l._1) == fold(c)) match
            case one :: Nil => Some(one)
            case _          => None
        }
        hit match
          case None =>
            Left(
              SearchError(
                StatusCode.BadRequest,
                "search_column_not_found",
                s"$schema.$table has no column $c"
              )
            )
          case Some((name, _, tpe)) if fold(tpe) != "varchar" =>
            Left(
              SearchError(
                StatusCode.BadRequest,
                "search_column_type",
                s"column $name of $schema.$table is not VARCHAR; only VARCHAR columns are indexed"
              )
            )
          case Some((name, fieldId, _)) => Right(done :+ SearchIndexField(name, fieldId))
      }
    }

  private def bundleRootOf(td: TenantDb): Either[SearchError, String] =
    metastoreOf(td)
      .get("dataPath")
      .map(_.trim)
      .filter(_.nonEmpty)
      .toRight(
        SearchError(
          StatusCode.Conflict,
          "search_no_data_path",
          s"'${td.name}' has no data path to hold the index's bundles"
        )
      )

  /** A catalog read; its failure is `503 catalog_unavailable`, naming nothing of the cause (the
    * catalog's host and login are QoD's).
    */
  private def read[A](io: IO[A]): IO[Either[SearchError, A]] =
    io.attempt.map(_.left.map(_ => CatalogUnavailable))

/** How a delete names its index: its table's name, or its index id. */
enum DeleteTarget:
  case ByTable(table: String)
  case ByIndexId(indexId: String)

object SearchIndexService:

  val NameRe = "[A-Za-z_][A-Za-z0-9_]*".r

  def validName(s: String): Boolean = NameRe.matches(s)

  private def fold(s: String): String = s.toLowerCase(Locale.ROOT)

  /** OWNER RULING 2026-11-16, D10 (spec ruling 24): no option exists through Phase 1. */
  val FixedOptions: SearchError =
    SearchError(
      StatusCode.BadRequest,
      "invalid_search_options",
      "k1 and b are fixed at 1.2 and 0.75 in this release"
    )

  val CatalogUnavailable: SearchError =
    SearchError(
      StatusCode.ServiceUnavailable,
      "catalog_unavailable",
      "the database's catalog could not be read; retry"
    )

  /** The same answer whether the tenant does not exist, holds no such database, or another tenant
    * does: no existence leaks.
    */
  def notFound(tenant: String, database: String): SearchError =
    SearchError(StatusCode.NotFound, "not_found", s"no database '$database' in tenant '$tenant'")

  def tableNotFound(schema: String, table: String): SearchError =
    SearchError(StatusCode.NotFound, "table_not_found", s"no live table $schema.$table")

  def noIndex(schema: String, table: String): SearchError =
    SearchError(StatusCode.NotFound, "not_found", s"no search index on $schema.$table")

  def exists(schema: String, table: String): SearchError =
    SearchError(
      StatusCode.Conflict,
      "search_index_exists",
      s"$schema.$table already has a search index"
    )
