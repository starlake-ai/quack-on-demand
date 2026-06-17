package ai.starlake.quack.edge

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TypeInfoCatalogSpec extends AnyFlatSpec with Matchers:

  private lazy val byName = TypeInfoCatalog.rows.map(r => r.typeName -> r).toMap

  // R7 wishlist from the Power BI connector team: DECIMAL, DATE, TIMESTAMP,
  // BOOLEAN, large strings, 64-bit integers must round-trip with correct
  // types. GetXdbcTypeInfo is what the ODBC driver reads to learn the
  // server's type system.
  "TypeInfoCatalog" should "include every DuckDB type Power BI needs" in:
    byName.keySet should contain allOf (
      "BOOLEAN", "TINYINT", "SMALLINT", "INTEGER", "BIGINT",
      "HUGEINT", "REAL", "DOUBLE", "DECIMAL", "VARCHAR",
      "BLOB", "DATE", "TIME", "TIMESTAMP", "UUID"
    )

  it should "map BOOLEAN to SQL_BIT" in:
    byName("BOOLEAN").dataType    shouldBe TypeInfoCatalog.SQL_BIT
    byName("BOOLEAN").sqlDataType shouldBe TypeInfoCatalog.SQL_BIT

  it should "map BIGINT to SQL_BIGINT with 19-digit precision" in:
    val r = byName("BIGINT")
    r.dataType          shouldBe TypeInfoCatalog.SQL_BIGINT
    r.columnSize        shouldBe Some(19)
    r.unsignedAttribute shouldBe Some(false)

  it should "flag UBIGINT as unsigned" in:
    byName("UBIGINT").unsignedAttribute shouldBe Some(true)

  it should "map DATE/TIME/TIMESTAMP with quoted-literal prefixes" in:
    Seq("DATE", "TIME", "TIMESTAMP").foreach: t =>
      val r = byName(t)
      r.literalPrefix shouldBe Some("'")
      r.literalSuffix shouldBe Some("'")

  it should "map TIMESTAMP to SQL_TYPE_TIMESTAMP" in:
    byName("TIMESTAMP").dataType shouldBe TypeInfoCatalog.SQL_TYPE_TIMESTAMP

  it should "map DECIMAL to SQL_DECIMAL with precision/scale create params" in:
    val r = byName("DECIMAL")
    r.dataType       shouldBe TypeInfoCatalog.SQL_DECIMAL
    r.createParams   shouldBe Some(List("precision", "scale"))
    r.fixedPrecScale shouldBe true

  it should "map HUGEINT to SQL_DECIMAL with 38-digit precision" in:
    val r = byName("HUGEINT")
    r.dataType   shouldBe TypeInfoCatalog.SQL_DECIMAL
    r.columnSize shouldBe Some(38)

  it should "mark VARCHAR as case-sensitive with quoted-literal prefixes" in:
    val r = byName("VARCHAR")
    r.dataType      shouldBe TypeInfoCatalog.SQL_VARCHAR
    r.caseSensitive shouldBe true
    r.literalPrefix shouldBe Some("'")
    r.literalSuffix shouldBe Some("'")

  it should "report every row as searchable=FULL and nullable=YES" in:
    TypeInfoCatalog.rows.foreach: r =>
      withClue(s"${r.typeName} searchable: ") {
        r.searchable shouldBe TypeInfoCatalog.SEARCHABLE_FULL
      }
      withClue(s"${r.typeName} nullable: ") {
        r.nullable shouldBe TypeInfoCatalog.NULLABLE_YES
      }