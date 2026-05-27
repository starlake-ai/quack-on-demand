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
    dialect: Dialect
) {
  val normalizedDefaultDatabase: Option[String] = defaultDatabase.map(_.toLowerCase)
  val normalizedDefaultSchema: Option[String]   = defaultSchema.map(_.toLowerCase)
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
      defaultSchema: Option[String] = None
  ): Config =
    Config(
      defaultDatabase = defaultDatabase,
      defaultSchema = defaultSchema,
      dialect = duckdbDialect
    )

  def forDuckDB(defaultDatabase: String, defaultSchema: String): Config =
    forDuckDB(Some(defaultDatabase), Some(defaultSchema))
}
