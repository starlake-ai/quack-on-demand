package ai.starlake.acl.model

enum CaseSensitivity {
  case CaseInsensitive
}

enum IdentifierMapping {
  case ThreePart
}

final case class Dialect(
    name: String,
    identifierMapping: IdentifierMapping,
    caseSensitivity: CaseSensitivity
)

final case class Config(
    defaultDatabase: Option[String],
    defaultSchema: Option[String],
    dialect: Dialect,
    // Catalog names attached on the executing session (tenant-db ATTACH alias,
    // federation aliases, DuckDB built-ins). Empty = standalone parser use;
    // two-part heads then always take the schema interpretation.
    attachedCatalogs: Set[String] = Set.empty
) {
  val normalizedDefaultDatabase: Option[String] = defaultDatabase.map(_.toLowerCase)
  val normalizedDefaultSchema: Option[String]   = defaultSchema.map(_.toLowerCase)
  val normalizedAttachedCatalogs: Set[String]   = attachedCatalogs.map(_.toLowerCase)
}

object Config {

  private val genericDialect: Dialect = Dialect(
    name = "generic",
    identifierMapping = IdentifierMapping.ThreePart,
    caseSensitivity = CaseSensitivity.CaseInsensitive
  )

  private val duckdbDialect: Dialect = Dialect(
    name = "duckdb",
    identifierMapping = IdentifierMapping.ThreePart,
    caseSensitivity = CaseSensitivity.CaseInsensitive
  )

  def forGeneric(
      defaultDatabase: Option[String] = None,
      defaultSchema: Option[String] = None
  ): Config =
    Config(
      defaultDatabase = defaultDatabase,
      defaultSchema = defaultSchema,
      dialect = genericDialect
    )

  def forGeneric(defaultDatabase: String, defaultSchema: String): Config =
    forGeneric(Some(defaultDatabase), Some(defaultSchema))

  def forDuckDB(
      defaultDatabase: Option[String] = None,
      defaultSchema: Option[String] = None,
      attachedCatalogs: Set[String] = Set.empty
  ): Config =
    Config(
      defaultDatabase = defaultDatabase,
      defaultSchema = defaultSchema,
      dialect = duckdbDialect,
      attachedCatalogs = attachedCatalogs
    )

  def forDuckDB(defaultDatabase: String, defaultSchema: String): Config =
    forDuckDB(Some(defaultDatabase), Some(defaultSchema))
}
