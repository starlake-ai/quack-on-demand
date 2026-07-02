package ai.starlake.quack.ondemand.ha

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{
  NodeSpec,
  PoolKey,
  RoleDistribution,
  RunningNode,
  Tenant,
  TenantDbKind
}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PostgresControlPlaneStore}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.sql.DriverManager
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*

class CrossReplicaPropagationSpec extends AnyFlatSpec with Matchers:

  import TestPostgres.{pgHost, pgPass, pgPort, pgUser}

  private final class StubBackend extends QuackBackend:
    private val nodes                          = TrieMap.empty[String, RunningNode]
    def start(spec: NodeSpec): IO[RunningNode] = IO {
      val n = RunningNode(
        spec.nodeId,
        spec.poolKey,
        spec.role,
        "127.0.0.1",
        21000 + nodes.size,
        "tok-" + spec.nodeId,
        Some(1L),
        None,
        Instant.EPOCH,
        maxConcurrent = spec.maxConcurrent
      )
      nodes.put(spec.nodeId, n); n
    }
    def stop(id: String): IO[Unit]                = IO { nodes.remove(id); () }
    def isAlive(id: String): Boolean              = nodes.contains(id)
    def discoverExisting(): IO[List[RunningNode]] = IO.pure(nodes.values.toList)
    def cleanup(): IO[Unit]                       = IO(nodes.clear())

  "a mutation on replica A" should "reach replica B via NOTIFY, not the TTL" in {
    if !TestPostgres.reachable then cancel("local Postgres not reachable; skipping")
    val dbName   = s"qodxr_test_${System.nanoTime()}"
    val adminUrl = s"jdbc:postgresql://$pgHost:$pgPort/postgres"
    val dbUrl    = s"jdbc:postgresql://$pgHost:$pgPort/$dbName"
    val admin    = DriverManager.getConnection(adminUrl, pgUser, pgPass)
    try admin.createStatement().execute(s"""CREATE DATABASE "$dbName"""")
    finally admin.close()
    try
      new LiquibaseRunner(dbUrl, pgUser, pgPass).run()
      val storeA = new PostgresControlPlaneStore(dbUrl, pgUser, pgPass)
      val storeB = new PostgresControlPlaneStore(dbUrl, pgUser, pgPass)
      val supA   = new PoolSupervisor(
        new StubBackend,
        new NodeLoadTracker,
        storeA,
        publish = new PgStateChangePublisher(storeA)
      )
      val supB = new PoolSupervisor(
        new StubBackend,
        new NodeLoadTracker,
        storeB
      )
      supB.restore()
      val coordB = new HaCoordinator(
        dbUrl,
        pgUser,
        pgPass,
        1.second,
        handlers = Map(
          "qod_topology" -> (_ => supB.restore()),
          "qod_rbac"     -> (_ => supB.restore())
        )
      )
      try
        coordB.tickNow() // connect + LISTEN before A mutates
        supA.createTenant(Tenant("acme")).unsafeRunSync()
        supA
          .createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, dataPath = "")
          .unsafeRunSync()
        // createTenantDb("acme", "default", ...) composes to "acme_default"
        val key = PoolKey("acme", "acme_default", "bi")
        supA.createPool(key, RoleDistribution(1, 0, 0)).unsafeRunSync()
        supB.get(key) shouldBe None // B has not refreshed yet: proves the assert below is real
        coordB.tickNow()            // drain + dispatch: handler re-runs restore()
        // B's in-memory mirror converged after one tick, no TTL wait.
        // restore() also replaced B's rbacResolver and invalidated its EffectiveSet cache,
        // so RBAC convergence rides the same assertion.
        supB.get(key).isDefined shouldBe true

        // Deletion must also converge: A deletes the pool, one tick on B, and
        // B's diff-aware restore() drops it (a plain put()-only restore would leave
        // the stale entry behind).
        supA.deletePool(key, force = true).unsafeRunSync()
        coordB.tickNow()
        supB.get(key) shouldBe None
      finally
        coordB.close()
        storeA.close()
        storeB.close()
    finally
      val admin2 = DriverManager.getConnection(adminUrl, pgUser, pgPass)
      try admin2.createStatement().execute(s"""DROP DATABASE IF EXISTS "$dbName" WITH (FORCE)""")
      finally admin2.close()
  }
