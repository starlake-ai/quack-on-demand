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

  /** DuckDB name resolution for two-part names differs from ANSI SQL.
    *
    * Per DuckDB docs: "When providing partial qualifications, DuckDB attempts to resolve
    * the reference as either a catalog or a schema."
    *
    * For a two-part name `X.Y`:
    *   - DuckDB tries `catalog=X, schema=<default>, table=Y` first (catalog interpretation)
    *   - Then `catalog=<default>, schema=X, table=Y` (schema interpretation)
    *   - If both match, it returns a binder error
    *
    * Since the ACL parser does not have access to the live catalog to check which
    * interpretation is valid, we adopt the catalog-first convention: `X.Y` is resolved
    * as `catalog=X, schema=main, table=Y`. This matches DuckDB's default behavior where
    * the default schema is always `main`.
    *
    * Three-part names (`X.Y.Z`) and single names (`X`) are handled identically to ANSI.
    *
    * String-literal file references (e.g., 'file.parquet') should be filtered by the caller
    * before calling toTableRef. If encountered here, they are treated as regular table names.
    */
  def toTableRef(table: Table, config: Config): Either[DenyReason, TableRef] =
    val tableName  = table.getUnquotedName
    val schemaName = Option(table.getUnquotedSchemaName)
    val dbName     = Option(table.getUnquotedDatabaseName)

    (dbName, schemaName) match
      // Three-part: fully qualified — use as-is
      case (Some(_), Some(_)) =>
        AnsiDialectMapper.toTableRef(table, config)

      // Two-part: DuckDB interprets the first part as catalog (database), not schema
      case (None, Some(firstPart)) =>
        val defaultSchema = config.normalizedDefaultSchema.getOrElse("main")
        Right(TableRef(firstPart, defaultSchema, tableName))

      // One-part or zero-part: apply defaults (same as ANSI)
      case (None, None) =>
        AnsiDialectMapper.toTableRef(table, config)

      // dbName set without schema — unlikely from JSqlParser but handle gracefully
      case (Some(_), None) =>
        AnsiDialectMapper.toTableRef(table, config)

  /** Check whether a JSqlParser Table represents a string-literal file reference.
    * The caller should use this to filter before calling toTableRef.
    */
  def isFileReference(table: Table): Boolean =
    table.getName != null && table.getName.startsWith("'")
