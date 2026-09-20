package ai.starlake.quack.edge.sql

import ai.starlake.acl.model.Config
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CatalogWriteScreenSpec extends AnyFlatSpec with Matchers with OptionValues:

  private val attached = Set("acme_db", "sales_lake", "memory", "system", "temp")
  private val cfg      = Config.forDuckDB(Some("acme_db"), Some("main"), attached)
  private val readOnly = Set("sales_lake")

  private def screen(sql: String, ro: Set[String] = readOnly): Option[String] =
    CatalogWriteScreen.screen(sql, ro, cfg)

  "screen" should "admit a read against a read-only catalog" in {
    screen("SELECT * FROM sales_lake.main.orders") shouldBe None
  }

  it should "admit any statement when no catalog is read-only" in {
    screen("INSERT INTO sales_lake.main.orders VALUES (1)", Set.empty) shouldBe None
  }

  it should "deny an INSERT into a read-only catalog" in {
    val r = screen("INSERT INTO sales_lake.main.orders VALUES (1)")
    r.value should include("sales_lake")
    r.value should include("read-only")
  }

  it should "deny UPDATE, DELETE and MERGE into a read-only catalog" in {
    screen("UPDATE sales_lake.main.orders SET a = 1") should not be None
    screen("DELETE FROM sales_lake.main.orders") should not be None
    screen(
      "MERGE INTO sales_lake.main.orders t USING acme_db.main.stg s ON t.id = s.id " +
        "WHEN MATCHED THEN UPDATE SET t.a = s.a"
    ) should not be None
  }

  it should "deny DDL against a read-only catalog" in {
    screen("CREATE TABLE sales_lake.main.t (a INT)") should not be None
    screen("DROP TABLE sales_lake.main.orders") should not be None
    screen("ALTER TABLE sales_lake.main.orders ADD COLUMN b INT") should not be None
  }

  it should "admit a write to a writable catalog while a read-only one is attached" in {
    screen("INSERT INTO acme_db.main.orders VALUES (1)") shouldBe None
  }

  it should "deny a CTAS whose target is read-only even though its source is not" in {
    screen("CREATE TABLE sales_lake.main.t AS SELECT * FROM acme_db.main.orders") should not be None
  }

  it should "admit a CTAS that only READS the read-only catalog" in {
    screen("CREATE TABLE acme_db.main.t AS SELECT * FROM sales_lake.main.orders") shouldBe None
  }

  it should "admit control-flow statements that touch no table" in {
    screen("COMMIT") shouldBe None
    screen("SET memory_limit = '1GB'") shouldBe None
  }

  it should "fail closed on an unparseable statement while a read-only catalog is attached" in {
    screen("INSRT INTO sales_lake.main.orders VALUES (1)") should not be None
  }

  it should "stay open on an unparseable statement when nothing is read-only" in {
    screen("INSRT INTO whatever VALUES (1)", Set.empty) shouldBe None
  }

  it should "match the read-only alias case-insensitively" in {
    screen("INSERT INTO SALES_LAKE.main.orders VALUES (1)") should not be None
  }
