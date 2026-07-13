package ai.starlake.acl.parser

import ai.starlake.acl.model.{Config, DenyReason, TableRef}
import net.sf.jsqlparser.schema.Table

trait DialectMapper:
  def toTableRef(table: Table, config: Config): Either[DenyReason, TableRef]

object DialectMapper:
  val ansi: DialectMapper   = AnsiDialectMapper
  val duckdb: DialectMapper = DuckDBDialectMapper

  def forConfig(config: Config): DialectMapper =
    config.dialect.name.toLowerCase match
      case "duckdb" => duckdb
      case _        => ansi

private[parser] object AnsiDialectMapper extends DialectMapper:

  def toTableRef(table: Table, config: Config): Either[DenyReason, TableRef] =
    val tableName  = table.getUnquotedName
    val schemaName = Option(table.getUnquotedSchemaName)
    val dbName     = Option(table.getUnquotedDatabaseName)

    val rawDisplayName = reconstructName(dbName, schemaName, tableName)

    val resolvedSchema = schemaName.orElse(config.normalizedDefaultSchema)
    val resolvedDb     = dbName.orElse(config.normalizedDefaultDatabase)

    resolvedDb match
      case None =>
        Left(DenyReason.UnqualifiedTable(rawDisplayName, "database"))
      case Some(db) =>
        resolvedSchema match
          case None =>
            Left(DenyReason.UnqualifiedTable(rawDisplayName, "schema"))
          case Some(schema) =>
            Right(TableRef(db, schema, tableName))

  private def reconstructName(
      db: Option[String],
      schema: Option[String],
      table: String
  ): String =
    (db, schema) match
      case (Some(d), Some(s)) => s"$d.$s.$table"
      case (None, Some(s))    => s"$s.$table"
      case (Some(d), None)    => s"$d..$table"
      case (None, None)       => table

private[parser] object DuckDBDialectMapper extends DialectMapper:

  /** DuckDB partial-name resolution.
    *
    * A two-part name `X.Y` resolves as `schema=X, table=Y` under the session's default catalog
    * (ANSI semantics) -- the grant model, the demo manifests, and the RLS/CLS rewriters all
    * qualify two-part names this way. But DuckDB's runtime tries a catalog interpretation of `X`
    * first, and this gateway may have other catalogs attached on the session (the tenant-db ATTACH
    * alias, federation aliases, DuckDB built-ins like `memory`/`system`/`temp`). If `X` matches one
    * of those attached catalogs, the engine would bind `X.Y` catalog-first while the ACL check just
    * resolved it schema-first -- a potential grant mismatch. Such names are refused with
    * [[DenyReason.AmbiguousCatalogRef]] instead of guessed at; the caller must fully qualify
    * (`catalog.schema.table`).
    *
    * Three-part names (`X.Y.Z`) and single names (`X`) are handled identically to ANSI.
    *
    * String-literal file references (e.g., 'file.parquet') should be filtered by the caller before
    * calling toTableRef. If encountered here, they are treated as regular table names.
    */
  def toTableRef(table: Table, config: Config): Either[DenyReason, TableRef] =
    val tableName  = table.getUnquotedName
    val schemaName = Option(table.getUnquotedSchemaName)
    val dbName     = Option(table.getUnquotedDatabaseName)
    (dbName, schemaName) match
      case (None, Some(head))
          if config.normalizedAttachedCatalogs.contains(head.toLowerCase) =>
        Left(DenyReason.AmbiguousCatalogRef(s"$head.$tableName", head))
      case _ =>
        AnsiDialectMapper.toTableRef(table, config)

  /** Check whether a JSqlParser Table represents a string-literal file reference. The caller should
    * use this to filter before calling toTableRef.
    */
  def isFileReference(table: Table): Boolean =
    table.getName != null && table.getName.startsWith("'")
