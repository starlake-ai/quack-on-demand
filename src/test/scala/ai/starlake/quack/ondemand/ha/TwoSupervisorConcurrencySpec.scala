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
import scala.collection.concurrent.TrieMap

class TwoSupervisorConcurrencySpec extends AnyFlatSpec with Matchers:

  import TestPostgres.{pgHost, pgPass, pgPort, pgUser}

  // Mirror the CapturingBackend in PoolSupervisorSpec.
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

  "two supervisors" should "not duplicate nodes when scale races reconcile" in {
    if !TestPostgres.reachable then cancel("local Postgres not reachable; skipping")
    val dbName   = s"qodha_test_${System.nanoTime()}"
    val adminUrl = s"jdbc:postgresql://$pgHost:$pgPort/postgres"
    val dbUrl    = s"jdbc:postgresql://$pgHost:$pgPort/$dbName"
    val admin    = java.sql.DriverManager.getConnection(adminUrl, pgUser, pgPass)
    try admin.createStatement().execute(s"""CREATE DATABASE "$dbName"""")
    finally admin.close()
    try
      new LiquibaseRunner(dbUrl, pgUser, pgPass).run()
      val locker  = new PgPoolLocker(dbUrl, pgUser, pgPass)
      def mkSup() =
        new PoolSupervisor(
          new StubBackend,
          new NodeLoadTracker,
          new PostgresControlPlaneStore(dbUrl, pgUser, pgPass),
          locks = locker
        )
      val supA = mkSup()
      supA.createTenant(Tenant("acme")).unsafeRunSync()
      supA
        .createTenantDb("acme", "default", TenantDbKind.InMemory, Map.empty, dataPath = "")
        .unsafeRunSync()
      // createTenantDb("acme", "default", ...) composes the tenant-db name as
      // `acme_default` (Names.normalizeTenantDbName prefixes the tenant), so the
      // routing PoolKey's tenantDb segment must be the composed name.
      val key = PoolKey("acme", "acme_default", "bi")
      supA.createPool(key, RoleDistribution(2, 0, 0)).unsafeRunSync()
      val supB = mkSup()
      supB.restore()
      // Race: A scales to 4 while B reconciles, 10 rounds.
      (1 to 10).foreach { _ =>
        IO.both(
          supA.scale(key, 4, RoleDistribution(4, 0, 0), force = false),
          supB.reconcile()
        ).unsafeRunSync()
      }
      val store = new PostgresControlPlaneStore(dbUrl, pgUser, pgPass)
      try
        val poolId = supA.poolId(key).getOrElse(fail("pool id missing"))
        val rows   = store.listNodes(poolId)
        rows.map(_.nodeId).distinct.size shouldBe rows.size
        rows.size shouldBe 4
      finally store.close()
    finally
      val admin2 = java.sql.DriverManager.getConnection(adminUrl, pgUser, pgPass)
      try admin2.createStatement().execute(s"""DROP DATABASE IF EXISTS "$dbName" WITH (FORCE)""")
      finally admin2.close()
  }
