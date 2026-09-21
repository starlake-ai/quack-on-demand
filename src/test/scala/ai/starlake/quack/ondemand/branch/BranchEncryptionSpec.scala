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
    "pgPassword" -> "p",
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
        encrypted = true
      )
      .unsafeRunSync()
    sup
      .createTenantDb(
        "acme",
        "plain",
        TenantDbKind.DuckLake,
        lakeMeta,
        dataPath = "/var/plain",
        encrypted = false
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

  it should "attach the branch catalog without ENCRYPTED when the parent is not encrypted" in {
    val spec = fresh()
      .mergeNodeSpec("acme", "acme_plain", "acme_plain__br_ab12cd34", "/var/plain__br_ab12cd34/")
      .get
    spec.extraSetupSql should not include "ENCRYPTED"
  }
