package ai.starlake.quack.ondemand.branch

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{Tenant, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** [[PoolSupervisor.mergeNodeSpec]]'s branch-catalog ATTACH must carry ENCRYPTED when the parent
  * tenant-db is encrypted, and must not when it is not.
  */
class BranchEncryptionSpec extends AnyFlatSpec with Matchers:

  private val lakeMeta = Map(
    "pgHost"     -> "h",
    "pgPort"     -> "5432",
    "pgUser"     -> "u",
    "pgPassword" -> "Catalog-Pw-7731",
    "schemaName" -> "main"
  )

  /** Supervisor with tenant `acme` and two DuckLake tenant-dbs: one encrypted, one not. */
  private def fresh(): PoolSupervisor =
    val sup =
      new PoolSupervisor(new StubQuackBackend, new NodeLoadTracker, new InMemoryControlPlaneStore())
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup
      .createTenantDb(
        "acme",
        "lake",
        TenantDbKind.DuckLake,
        lakeMeta,
        dataPath = "/var/lake",
        encrypted = true,
        adoptExistingDatabase = false
      )
      .unsafeRunSync()
    sup
      .createTenantDb(
        "acme",
        "plain",
        TenantDbKind.DuckLake,
        lakeMeta,
        dataPath = "/var/plain",
        encrypted = false,
        adoptExistingDatabase = false
      )
      .unsafeRunSync()
    sup

  "mergeNodeSpec" should "attach the branch catalog with ENCRYPTED when the parent is encrypted" in {
    val spec = fresh()
      .mergeNodeSpec("acme", "acme_lake", "acme_lake__br_ab12cd34", "/var/lake__br_ab12cd34/")
      .get
    spec.extraSetupSql should include("ENCRYPTED")
    spec.extraSetupSql should include("READ_ONLY")
  }

  it should "carry the catalog password in a temporary secret, never in the ATTACH string" in {
    val sql = fresh()
      .mergeNodeSpec("acme", "acme_lake", "acme_lake__br_ab12cd34", "/var/lake__br_ab12cd34/")
      .get
      .extraSetupSql
    val lines = sql.linesIterator.toVector
    lines.filter(_.contains("Catalog-Pw-7731")) shouldBe Vector(
      "CREATE OR REPLACE TEMPORARY SECRET qod_branch_pg " +
        "(TYPE postgres, PASSWORD 'Catalog-Pw-7731');"
    )
    sql.toLowerCase should not include "password="
    val create = lines.indexWhere(_.contains("CREATE OR REPLACE TEMPORARY SECRET qod_branch_pg"))
    val attach = lines.indexWhere(_.startsWith("ATTACH 'ducklake:postgres:"))
    val drop   = lines.indexOf("DROP SECRET IF EXISTS qod_branch_pg;")
    lines(attach) should include("METADATA_PARAMETERS MAP {'SECRET': 'qod_branch_pg'}")
    create should be < attach
    attach should be < drop
  }

  it should "attach the branch catalog without ENCRYPTED when the parent is not encrypted" in {
    val spec = fresh()
      .mergeNodeSpec("acme", "acme_plain", "acme_plain__br_ab12cd34", "/var/plain__br_ab12cd34/")
      .get
    spec.extraSetupSql should not include "ENCRYPTED"
  }

  /** [[ai.starlake.quack.model.Names.normalizeTenantDbName]] composes `"${tenant}_${suffix}"` only
    * when the suffix does not already start with `"${tenant}_"`, so two different tenants can land
    * on the SAME composed tenant-db name: tenant `a` with suffix `b_c`, and tenant `a_b` with
    * suffix `c`, both compose to `a_b_c`. A parent-encryption lookup that scans `tenantDbs` by name
    * alone (not scoped by tenant) would match whichever row happens to come first, so this pins the
    * fix against that specific collision instead of matching by name only.
    */
  it should "resolve the parent's encryption by tenant, not by a name that collides across tenants" in {
    val store = new InMemoryControlPlaneStore()
    // A tenant stored before a new tenant's name lost its underscores keeps its name.
    store.upsertTenant(Tenant("a_b"))
    val sup = new PoolSupervisor(new StubQuackBackend, new NodeLoadTracker, store)
    sup.restore()
    sup.createTenant(Tenant("a")).unsafeRunSync()
    // tenant "a", tenant-db "b_c" -> composed name "a_b_c", NOT encrypted.
    sup
      .createTenantDb(
        "a",
        "b_c",
        TenantDbKind.DuckLake,
        lakeMeta,
        dataPath = "/var/a_b_c",
        encrypted = false,
        adoptExistingDatabase = false
      )
      .unsafeRunSync()
    // tenant "a_b", tenant-db "c" -> composed name ALSO "a_b_c", but for an unrelated tenant, and
    // it IS encrypted.
    sup
      .createTenantDb(
        "a_b",
        "c",
        TenantDbKind.DuckLake,
        lakeMeta,
        dataPath = "/var/other_a_b_c",
        encrypted = true,
        adoptExistingDatabase = false
      )
      .unsafeRunSync()

    val spec = sup
      .mergeNodeSpec("a", "a_b_c", "a_b_c__br_ab12cd34", "/var/a_b_c__br_ab12cd34/")
      .get
    spec.extraSetupSql should not include "ENCRYPTED"
  }
