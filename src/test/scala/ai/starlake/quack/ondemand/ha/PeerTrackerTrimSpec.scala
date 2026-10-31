package ai.starlake.quack.ondemand.ha

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{PoolKey, RoleDistribution, Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PostgresControlPlaneStore}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicReference

/** A peer replica's [[NodeLoadTracker]] shrinks with the node rows: its `restore()` (what a
  * `qod_topology` NOTIFY or the periodic refresh runs) forgets the load and latency ring of every
  * node another replica removed, and never a node written after the snapshot it rebuilt from, such
  * as a respawned node whose quarantine was re-applied meanwhile.
  */
class PeerTrackerTrimSpec extends AnyFlatSpec with Matchers:

  import TestPostgres.{pgHost, pgPass, pgPort, pgUser}

  private def newStubBackend() = new StubQuackBackend(tokenFor = StubQuackBackend.PerNodeToken)

  /** A fresh control-plane database shared by two replicas' stores, dropped afterwards. */
  private def withTwoStores(
      body: (PostgresControlPlaneStore, PostgresControlPlaneStore) => Unit
  ): Unit =
    if !TestPostgres.reachable then cancel("local Postgres not reachable; skipping")
    val dbName   = s"qodtrim_test_${System.nanoTime()}"
    val adminUrl = s"jdbc:postgresql://$pgHost:$pgPort/postgres"
    val dbUrl    = s"jdbc:postgresql://$pgHost:$pgPort/$dbName"
    val admin    = DriverManager.getConnection(adminUrl, pgUser, pgPass)
    try admin.createStatement().execute(s"""CREATE DATABASE "$dbName"""")
    finally admin.close()
    try
      new LiquibaseRunner(dbUrl, pgUser, pgPass).run()
      val storeA = new PostgresControlPlaneStore(dbUrl, pgUser, pgPass)
      val storeB = new PostgresControlPlaneStore(dbUrl, pgUser, pgPass)
      try body(storeA, storeB)
      finally
        storeA.close()
        storeB.close()
    finally
      val admin2 = DriverManager.getConnection(adminUrl, pgUser, pgPass)
      try admin2.createStatement().execute(s"""DROP DATABASE IF EXISTS "$dbName" WITH (FORCE)""")
      finally admin2.close()

  private def seedTenant(sup: PoolSupervisor): Unit =
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    sup
      .createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, dataPath = "")
      .unsafeRunSync()
    ()

  "a peer replica's restore()" should "forget the tracker entries of a pool another replica deleted" in
    withTwoStores { (storeA, storeB) =>
      val supA     = new PoolSupervisor(newStubBackend(), new NodeLoadTracker, storeA)
      val trackerB = new NodeLoadTracker
      val supB     = new PoolSupervisor(newStubBackend(), trackerB, storeB)
      seedTenant(supA)
      val gone = PoolKey("acme", "acme_default", "bi")
      val kept = PoolKey("acme", "acme_default", "etl")
      supA.createPool(gone, RoleDistribution(1, 0, 0)).unsafeRunSync()
      supA.createPool(kept, RoleDistribution(1, 0, 0)).unsafeRunSync()

      supB.restore()
      val goneIds = supB.get(gone).get.nodes.map(_.nodeId).toSet
      val keptIds = supB.get(kept).get.nodes.map(_.nodeId).toSet
      goneIds should not be empty
      keptIds should not be empty
      // Replica B routed statements to every node: load and latency ring on B.
      (goneIds ++ keptIds).foreach { id => trackerB.onStart(id); trackerB.onFinish(id, 25) }
      trackerB.snapshotAll.keySet should contain allElementsOf (goneIds ++ keptIds)

      // Replica A deletes the pool; B never runs a removal of its own, only its restore().
      supA.deletePool(gone, force = true).unsafeRunSync()
      supB.restore()

      supB.get(gone) shouldBe None
      trackerB.snapshotAll.keySet.intersect(goneIds) shouldBe empty
      goneIds.foreach(id => trackerB.latencyPercentiles(id) shouldBe ((0.0, 0.0, 0.0)))
      trackerB.snapshotAll.keySet should contain allElementsOf keptIds
      keptIds.foreach(id => trackerB.latencyPercentiles(id)._1 shouldBe 25.0)
    }

  it should "keep the quarantine of a node respawned after the snapshot it rebuilt from was read" in
    withTwoStores { (storeA, storeB) =>
      val supA     = new PoolSupervisor(newStubBackend(), new NodeLoadTracker, storeA)
      val trackerB = new NodeLoadTracker
      // Runs inside B's restore(), after its snapshot was read: the moment a concurrent respawn
      // on B (PoolSupervisor's reconcile, which re-applies an operator quarantine to the fresh
      // node) writes a node that snapshot does not list.
      val duringRestore = new AtomicReference[() => Unit](() => ())
      val supB          = new PoolSupervisor(
        newStubBackend(),
        trackerB,
        storeB,
        federatedTenantDbIds = () => {
          duringRestore.get()()
          None
        }
      )
      seedTenant(supA)
      supA
        .createPool(PoolKey("acme", "acme_default", "bi"), RoleDistribution(1, 0, 0))
        .unsafeRunSync()
      supB.restore()

      val fresh = "acme-acme_default-bi-respawned"
      duringRestore.set(() => trackerB.setQuarantined(fresh, true))
      supB.restore()
      duringRestore.set(() => ())

      trackerB.snapshot(fresh).quarantined shouldBe true
      trackerB.snapshot(fresh).routable shouldBe false
      // A node that never gets a row is forgotten by the next restore, which began after it.
      supB.restore()
      trackerB.snapshotAll.keySet should not contain fresh
    }
