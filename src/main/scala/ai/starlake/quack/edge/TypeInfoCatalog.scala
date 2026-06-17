package ai.starlake.quack.edge

/** One row of the FlightSQL `GetXdbcTypeInfo` response. Field names match the canonical Arrow
  * schema (`FlightSqlProducer.Schemas.GET_TYPE_INFO_SCHEMA`); semantics follow the ODBC spec.
  */
final case class TypeInfoRow(
    typeName: String,
    dataType: Int, // SQL_TYPE_* per ODBC
    columnSize: Option[Int],
    literalPrefix: Option[String],
    literalSuffix: Option[String],
    createParams: Option[List[String]],
    nullable: Int, // 0=no, 1=yes, 2=unknown
    caseSensitive: Boolean,
    searchable: Int, // 0=none, 1=char, 2=basic, 3=full
    unsignedAttribute: Option[Boolean],
    fixedPrecScale: Boolean,
    autoIncrement: Option[Boolean],
    localTypeName: Option[String],
    minimumScale: Option[Int],
    maximumScale: Option[Int],
    sqlDataType: Int,
    datetimeSubcode: Option[Int],
    numPrecRadix: Option[Int],
    intervalPrecision: Option[Int]
)

/** Static table of DuckDB types exposed to FlightSQL clients via `GetXdbcTypeInfo`. The Arrow
  * Flight SQL ODBC driver (which Power BI, Excel, and other ODBC consumers ride on) reads this to
  * decide how to map the wire types into client-side types: precision, sign-ness, quoted-literal
  * syntax, etc. R7.
  *
  * Types listed here cover the connector team's R7 wishlist (DECIMAL, DATE, TIMESTAMP, BOOLEAN,
  * large strings, 64-bit integers) plus the rest of DuckDB's primitive surface. Composite types
  * (LIST, STRUCT, MAP, UNION) are intentionally absent; they aren't representable in the
  * `data_type` column's SQL_TYPE_* enumeration and the ODBC driver flattens them into strings.
  */
object TypeInfoCatalog:
  // ODBC SQL_TYPE_* codes (https://docs.microsoft.com/sql/odbc/reference/appendixes/sql-data-types).
  val SQL_BIT: Int            = -7
  val SQL_TINYINT: Int        = -6
  val SQL_BIGINT: Int         = -5
  val SQL_VARBINARY: Int      = -3
  val SQL_VARCHAR: Int        = 12
  val SQL_SMALLINT: Int       = 5
  val SQL_INTEGER: Int        = 4
  val SQL_DECIMAL: Int        = 3
  val SQL_REAL: Int           = 7
  val SQL_DOUBLE: Int         = 8
  val SQL_TYPE_DATE: Int      = 91
  val SQL_TYPE_TIME: Int      = 92
  val SQL_TYPE_TIMESTAMP: Int = 93

  val NULLABLE_NO: Int      = 0
  val NULLABLE_YES: Int     = 1
  val NULLABLE_UNKNOWN: Int = 2

  val SEARCHABLE_NONE: Int  = 0
  val SEARCHABLE_CHAR: Int  = 1
  val SEARCHABLE_BASIC: Int = 2
  val SEARCHABLE_FULL: Int  = 3

  private val NumericRadix = Some(10)
  private val BinaryRadix  = Some(2)

  // Default row template: most fields are nullable/none. Variants overwrite what differs.
  private def baseRow(
      typeName: String,
      dataType: Int,
      columnSize: Option[Int] = None,
      literalPrefix: Option[String] = None,
      literalSuffix: Option[String] = None,
      createParams: Option[List[String]] = None,
      caseSensitive: Boolean = false,
      unsignedAttribute: Option[Boolean] = None,
      fixedPrecScale: Boolean = false,
      autoIncrement: Option[Boolean] = Some(false),
      minimumScale: Option[Int] = None,
      maximumScale: Option[Int] = None,
      sqlDataType: Option[Int] = None,
      numPrecRadix: Option[Int] = None
  ): TypeInfoRow = TypeInfoRow(
    typeName = typeName,
    dataType = dataType,
    columnSize = columnSize,
    literalPrefix = literalPrefix,
    literalSuffix = literalSuffix,
    createParams = createParams,
    nullable = NULLABLE_YES,
    caseSensitive = caseSensitive,
    searchable = SEARCHABLE_FULL,
    unsignedAttribute = unsignedAttribute,
    fixedPrecScale = fixedPrecScale,
    autoIncrement = autoIncrement,
    localTypeName = Some(typeName),
    minimumScale = minimumScale,
    maximumScale = maximumScale,
    sqlDataType = sqlDataType.getOrElse(dataType),
    datetimeSubcode = None,
    numPrecRadix = numPrecRadix,
    intervalPrecision = None
  )

  val rows: List[TypeInfoRow] = List(
    baseRow("BOOLEAN", SQL_BIT, columnSize = Some(1)),
    baseRow(
      "TINYINT",
      SQL_TINYINT,
      columnSize = Some(3),
      unsignedAttribute = Some(false),
      numPrecRadix = NumericRadix
    ),
    baseRow(
      "UTINYINT",
      SQL_TINYINT,
      columnSize = Some(3),
      unsignedAttribute = Some(true),
      numPrecRadix = NumericRadix
    ),
    baseRow(
      "SMALLINT",
      SQL_SMALLINT,
      columnSize = Some(5),
      unsignedAttribute = Some(false),
      numPrecRadix = NumericRadix
    ),
    baseRow(
      "USMALLINT",
      SQL_SMALLINT,
      columnSize = Some(5),
      unsignedAttribute = Some(true),
      numPrecRadix = NumericRadix
    ),
    baseRow(
      "INTEGER",
      SQL_INTEGER,
      columnSize = Some(10),
      unsignedAttribute = Some(false),
      numPrecRadix = NumericRadix
    ),
    baseRow(
      "UINTEGER",
      SQL_INTEGER,
      columnSize = Some(10),
      unsignedAttribute = Some(true),
      numPrecRadix = NumericRadix
    ),
    baseRow(
      "BIGINT",
      SQL_BIGINT,
      columnSize = Some(19),
      unsignedAttribute = Some(false),
      numPrecRadix = NumericRadix
    ),
    baseRow(
      "UBIGINT",
      SQL_BIGINT,
      columnSize = Some(20),
      unsignedAttribute = Some(true),
      numPrecRadix = NumericRadix
    ),
    baseRow(
      "HUGEINT",
      SQL_DECIMAL,
      columnSize = Some(38),
      unsignedAttribute = Some(false),
      fixedPrecScale = true,
      minimumScale = Some(0),
      maximumScale = Some(0),
      numPrecRadix = NumericRadix
    ),
    baseRow("REAL", SQL_REAL, columnSize = Some(24), numPrecRadix = BinaryRadix),
    baseRow("DOUBLE", SQL_DOUBLE, columnSize = Some(53), numPrecRadix = BinaryRadix),
    baseRow(
      "DECIMAL",
      SQL_DECIMAL,
      columnSize = Some(38),
      createParams = Some(List("precision", "scale")),
      fixedPrecScale = true,
      minimumScale = Some(0),
      maximumScale = Some(38),
      numPrecRadix = NumericRadix
    ),
    baseRow(
      "VARCHAR",
      SQL_VARCHAR,
      literalPrefix = Some("'"),
      literalSuffix = Some("'"),
      createParams = Some(List("length")),
      caseSensitive = true
    ),
    baseRow("BLOB", SQL_VARBINARY, literalPrefix = Some("'"), literalSuffix = Some("'")),
    baseRow(
      "DATE",
      SQL_TYPE_DATE,
      columnSize = Some(10),
      literalPrefix = Some("'"),
      literalSuffix = Some("'")
    ),
    baseRow(
      "TIME",
      SQL_TYPE_TIME,
      columnSize = Some(8),
      literalPrefix = Some("'"),
      literalSuffix = Some("'")
    ),
    baseRow(
      "TIMESTAMP",
      SQL_TYPE_TIMESTAMP,
      columnSize = Some(26),
      literalPrefix = Some("'"),
      literalSuffix = Some("'")
    ),
    baseRow(
      "UUID",
      SQL_VARCHAR,
      columnSize = Some(36),
      literalPrefix = Some("'"),
      literalSuffix = Some("'")
    )
  )

  /** Lookup by ODBC SQL_TYPE_* code, mirroring the `data_type` filter on the
    * `CommandGetXdbcTypeInfo` request: when the client passes `data_type`, the response is filtered
    * to just rows matching that code.
    */
  def filterByDataType(code: Option[Int]): List[TypeInfoRow] =
    code match
      case None    => rows
      case Some(c) => rows.filter(_.dataType == c)
