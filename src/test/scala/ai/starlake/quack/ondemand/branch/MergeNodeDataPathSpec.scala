package ai.starlake.quack.ondemand.branch

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The merge node runs in an empty directory of its own (spawn-quack-node.sh), so the branch
  * catalog it attaches must name an absolute data path: a relative one would resolve inside that
  * directory. DuckLake recorded the relative spelling, hence the override.
  */
class MergeNodeDataPathSpec extends AnyFlatSpec with Matchers:

  private def fresh(dataPath: String): PoolSupervisor =
    val sup =
      new PoolSupervisor(new StubQuackBackend, new NodeLoadTracker, new InMemoryControlPlaneStore())
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    val created = sup
      .createTenantDb(
        "acme",
        "lake",
        TenantDbKind.DuckLake,
        Map(
          "pgHost"     -> "h",
          "pgPort"     -> "5432",
          "pgUser"     -> "u",
          "pgPassword" -> "p",
          "schemaName" -> "main"
        ),
        dataPath = dataPath,
        adoptExistingDatabase = false
      )
      .unsafeRunSync()
    withClue(created)(created.isRight shouldBe true)
    sup

  "mergeNodeSpec" should "attach a relative branch prefix by its absolute path, overriding" in {
    val sql = fresh("./ducklake/lake")
      .mergeNodeSpec("acme", "acme_lake", "acme_lake__br_ab12cd34", "./ducklake/lake__br_ab12cd34/")
      .get
      .extraSetupSql
    val abs = java.nio.file.Paths.get("ducklake/lake__br_ab12cd34").toAbsolutePath.toString
    sql should include(s"DATA_PATH '$abs/', READ_ONLY, OVERRIDE_DATA_PATH true,")
  }

  it should "leave an absolute branch prefix as is, with no override" in {
    val sql = fresh("/var/lake")
      .mergeNodeSpec("acme", "acme_lake", "acme_lake__br_ab12cd34", "/var/lake__br_ab12cd34/")
      .get
      .extraSetupSql
    sql should include("DATA_PATH '/var/lake__br_ab12cd34/', READ_ONLY,")
    sql should not include "OVERRIDE_DATA_PATH"
  }
