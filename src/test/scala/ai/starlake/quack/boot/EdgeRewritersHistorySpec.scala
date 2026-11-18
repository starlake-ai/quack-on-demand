package ai.starlake.quack.boot

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{PoolKey, Tenant, TenantDb, TenantDbKind}
import ai.starlake.quack.observability.metrics.StatementInstruments
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.DurationInt

/** The DuckLake reads [[EdgeRewriters.columnCatalog]] wires: a branch pool attaches its own catalog
  * under its parent's alias, so its columns, its newest snapshot and its history are all its own.
  * No Postgres: the readers are stubs.
  */
class EdgeRewritersHistorySpec extends AnyFlatSpec with Matchers:

  /** A reader whose columns, newest snapshot and history answer the test sets. */
  private final class StubReader(
      var snapshot: Long,
      var stable: Boolean,
      var columns: List[String] = List("id", "name")
  ) extends DuckLakeCatalogReader(null):
    override def maxSnapshotIdBounded(): Option[Long]                     = Some(snapshot)
    override def nameHistoryStable(schema: String, name: String): Boolean = stable
    override def columnNames(schema: String, table: String): List[String] = columns

  private val parentRow = TenantDb(
    id = "td-lake",
    tenantId = "acme",
    name = "acme_lake",
    kind = TenantDbKind.DuckLake,
    metastore = Map("dbName" -> "acme_lake", TenantDb.CatalogAliasKey -> "sales"),
    dataPath = "/data/lake"
  )
  private val branchRow = TenantDb(
    id = "td-br",
    tenantId = "acme",
    name = "acme_lake__br_ab12cd34",
    kind = TenantDbKind.DuckLake,
    metastore = Map("dbName" -> "acme_lake__br_ab12cd34", TenantDb.CatalogAliasKey -> "sales"),
    dataPath = "/data/lake__br_ab12cd34",
    branchOf = Some("td-lake")
  )

  private val ParentPool = Some(PoolKey("acme", parentRow.name, "p"))
  private val BranchPool = Some(PoolKey("acme", branchRow.name, "p"))

  private final class Fixture:
    val parent        = new StubReader(snapshot = 5L, stable = true)
    val branch        = new StubReader(snapshot = 9L, stable = true)
    private val store = new InMemoryControlPlaneStore()
    store.upsertTenant(Tenant(id = "acme", displayName = "acme", authProvider = "db"))
    store.upsertTenantDb(parentRow)
    store.upsertTenantDb(branchRow)
    private val sup = new PoolSupervisor(new StubQuackBackend(), new NodeLoadTracker, store)
    sup.restore()
    private val readers = Map(parentRow.name -> parent, branchRow.name -> branch)
    private val catalog = EdgeRewriters.columnCatalog(
      sup,
      (_, td) => readers(td),
      StatementInstruments.noop,
      nodeQuery = (_, _) => IO.pure(Right(Nil)),
      nodeTimeout = 1.second
    )
    def stable(pool: Option[PoolKey], cat: String = "sales"): Boolean =
      catalog.historyStable("acme", pool, cat, "tpch1", "customer").unsafeRunSync()
    def columns(pool: Option[PoolKey], cat: String = "sales"): List[String] =
      catalog.columnsOf("acme", pool, cat, "tpch1", "customer").unsafeRunSync()

  "The name-history check" should "read a branch pool's snapshot and history from the branch" in {
    val f = new Fixture
    f.stable(BranchPool) shouldBe true
    f.stable(ParentPool) shouldBe true
    // A column renamed on the branch only: the branch's catalog moves, its parent's does not.
    f.branch.snapshot = 10L
    f.branch.stable = false
    f.stable(BranchPool) shouldBe false
    f.stable(ParentPool) shouldBe true
    f.stable(None) shouldBe true
  }

  it should "read a statement with no pool, or another tenant's pool, from the alias's database" in {
    val f = new Fixture
    f.stable(None) shouldBe true
    f.stable(Some(PoolKey("globex", branchRow.name, "p"))) shouldBe true
    f.parent.snapshot = 6L
    f.parent.stable = false
    f.stable(None) shouldBe false
    f.stable(Some(PoolKey("globex", branchRow.name, "p"))) shouldBe false
    f.stable(BranchPool) shouldBe true
  }

  it should "answer false for a catalog that is no DuckLake database of the tenant" in {
    val f = new Fixture
    f.stable(ParentPool, cat = "pg_federated") shouldBe false
  }

  "The column lookup" should "read a branch pool's columns from the branch (#241)" in {
    val f = new Fixture
    // A column added on the branch only.
    f.branch.columns = List("id", "name", "ssn")
    f.columns(BranchPool) shouldBe List("id", "name", "ssn")
    f.columns(ParentPool) shouldBe List("id", "name")
  }

  it should "read a statement with no pool, or another tenant's pool, from the alias's database" in {
    val f = new Fixture
    f.branch.columns = List("id", "name", "ssn")
    f.columns(None) shouldBe List("id", "name")
    f.columns(Some(PoolKey("globex", branchRow.name, "p"))) shouldBe List("id", "name")
  }

  it should "list nothing from DuckLake for a catalog that is no DuckLake database of the tenant" in {
    val f = new Fixture
    // The fixture's node answers no rows, so nothing lists the table.
    f.columns(ParentPool, cat = "pg_federated") shouldBe Nil
  }
