package ai.starlake.quack.ondemand.demo

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DemoSeedSpec extends AnyFlatSpec with Matchers:

  "DemoSeed.buildInitSql" should "attach the DuckLake-over-Postgres catalog and dbgen at the SF" in {
    val pg  = PgCoords("localhost", 54321, "postgres", "")
    val sql = DemoSeed.buildInitSql(pg, "acme_tpch", "tpch1", "/demo/ducklake", 0.1)

    sql should include("INSTALL ducklake")
    sql should include("INSTALL postgres")
    sql should include("INSTALL tpch")
    sql should include("host=localhost port=54321 dbname=acme_tpch user=postgres")
    sql should include("DATA_PATH '/demo/ducklake'")
    sql should include("CREATE SCHEMA IF NOT EXISTS acme_tpch.tpch1")
    sql should include("CALL dbgen(sf = 0.1)")
  }

  it should "CTAS each of the 8 TPC-H tables from the in-memory dbgen output into the DuckLake schema" in {
    val pg  = PgCoords("localhost", 54321, "postgres", "")
    val sql = DemoSeed.buildInitSql(pg, "acme_tpch", "tpch1", "/demo/ducklake", 0.1)

    for table <-
        List("region", "nation", "customer", "supplier", "part", "partsupp", "orders", "lineitem")
    do
      sql should include(s"CREATE TABLE acme_tpch.tpch1.$table AS SELECT * FROM memory.main.$table")
  }
