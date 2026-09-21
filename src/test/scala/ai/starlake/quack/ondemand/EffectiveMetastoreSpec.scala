package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EffectiveMetastoreSpec extends AnyFlatSpec with Matchers:

  private val sup =
    new PoolSupervisor(new StubQuackBackend, new NodeLoadTracker, new InMemoryControlPlaneStore())

  private val lakeDb = TenantDb(
    id = "td_1",
    tenantId = "t_1",
    name = "acme_lake",
    kind = TenantDbKind.DuckLake,
    metastore = Map(
      "pgHost"     -> "h",
      "pgPort"     -> "5432",
      "pgUser"     -> "u",
      "pgPassword" -> "p",
      "dbName"     -> "acme_lake",
      "schemaName" -> "main"
    ),
    dataPath = "/var/lake"
  )

  private val fileDb = lakeDb.copy(
    id = "td_2",
    name = "acme_sales",
    kind = TenantDbKind.DuckDbFile,
    metastore = Map("dbName" -> "acme_sales", "schemaName" -> "main"),
    dataPath = "/var/sales.duckdb"
  )

  private val memDb = lakeDb.copy(
    id = "td_3",
    name = "acme_scratch",
    kind = TenantDbKind.InMemory,
    metastore = Map.empty,
    dataPath = ""
  )

  "effectiveMetastoreFor" should "emit encrypted=true for an encrypted ducklake row" in {
    val m = sup.effectiveMetastoreForTest(lakeDb.copy(encrypted = true))
    m.get("encrypted") shouldBe Some("true")
  }

  it should "omit the key entirely when the row is not encrypted" in {
    val m = sup.effectiveMetastoreForTest(lakeDb.copy(encrypted = false))
    m.get("encrypted") shouldBe None
  }

  it should "emit encrypted=true for an encrypted duckdb-file row" in {
    val m = sup.effectiveMetastoreForTest(fileDb.copy(encrypted = true))
    m.get("encrypted") shouldBe Some("true")
  }

  it should "never emit encrypted for an in-memory row" in {
    val m = sup.effectiveMetastoreForTest(memDb.copy(encrypted = true))
    m.get("encrypted") shouldBe None
  }
