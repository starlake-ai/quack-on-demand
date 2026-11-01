package ai.starlake.quack.ondemand

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.Tenant
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{
  ControlPlaneStore,
  InMemoryControlPlaneStore,
  LiquibaseRunner,
  PostgresControlPlaneStore
}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/** `PoolSupervisor.setGroupState` (SCIM group PUT and PATCH) writes a group's members and
  * externalId as one: a member that disappears after the handler checked it refuses or fails the
  * whole write, and nothing of it stays applied.
  */
class GroupStateAtomicitySpec extends AnyFlatSpec with Matchers:
  TestPostgres.dropStrayTestDatabases("qodgrp")

  private val hash = "$2a$10$seedseedseedseedseedse"

  /** acme with users bob (a member of `analysts`, externalId `ext-0`), alice and carl. */
  private def seed(sup: PoolSupervisor, store: ControlPlaneStore): (String, Map[String, String]) =
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    val ids = List("bob", "alice", "carl").map { n =>
      n -> store.upsertUserWithHash(Some("acme"), n, hash, "user")
    }.toMap
    sup.restore()
    val g = sup.createGroup("acme", "analysts").unsafeRunSync().toOption.get
    sup.addUserGroup(ids("bob"), g.id).unsafeRunSync() shouldBe Right(())
    sup.setGroupExternalId(g.id, Some("ext-0"))
    (g.id, ids)

  private def assertUnchanged(store: ControlPlaneStore, gid: String, ids: Map[String, String]) =
    store.listUsersInGroup(gid) shouldBe List(ids("bob"))
    store.findGroup("acme", "analysts").flatMap(_.externalId) shouldBe Some("ext-0")

  "setGroupState" should "refuse the whole write when a joining member is gone by write time" in {
    val inner = new InMemoryControlPlaneStore()
    var gone  = Option.empty[String]
    // Carl is deleted after the handler checked him: the supervisor's own read no longer finds him.
    val store = new ControlPlaneStore:
      export inner.{getUserById as _, *}
      override def getUserById(id: String) =
        if gone.contains(id) then None else inner.getUserById(id)
    val sup        = new PoolSupervisor(StubQuackBackend.noop(), new NodeLoadTracker, store)
    val (gid, ids) = seed(sup, store)
    gone = Some(ids("carl"))
    val out = sup
      .setGroupState(gid, Set(ids("alice"), ids("carl")), Some(Some("ext-1")))
      .unsafeRunSync()
    out.isLeft shouldBe true
    assertUnchanged(inner, gid, ids)
  }

  it should "roll every write back when a member is deleted between the check and its edge" in {
    TestPostgres.ensureReachable()
    val dbName = s"qodgrp_${java.util.UUID.randomUUID().toString.take(8)}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val pg = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try
        var doomed = Option.empty[String]
        // Carl passes the check, then is deleted (on another connection, committed) before his
        // edge is written: the edge's foreign key fails, after alice's edge was written.
        val store = new ControlPlaneStore:
          export pg.{addUserGroup as _, *}
          override def addUserGroup(userId: String, groupId: String): Unit =
            if doomed.contains(userId) then
              val other =
                new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
              try other.deleteUser(userId)
              finally other.close()
            pg.addUserGroup(userId, groupId)
        val sup        = new PoolSupervisor(StubQuackBackend.noop(), new NodeLoadTracker, store)
        val (gid, ids) = seed(sup, store)
        doomed = Some(ids("carl"))
        val out = sup
          .setGroupState(gid, Set(ids("alice"), ids("carl")), Some(Some("ext-1")))
          .attempt
          .unsafeRunSync()
        out.forall(_.isLeft) shouldBe true
        assertUnchanged(pg, gid, ids)
      finally pg.close()
    finally Try(TestPostgres.dropDatabase(dbName))
  }

  it should "write members and externalId together when every member is there" in {
    val store      = new InMemoryControlPlaneStore()
    val sup        = new PoolSupervisor(StubQuackBackend.noop(), new NodeLoadTracker, store)
    val (gid, ids) = seed(sup, store)
    sup.setGroupState(gid, Set(ids("alice")), Some(Some("ext-1"))).unsafeRunSync() shouldBe
      Right(())
    store.listUsersInGroup(gid) shouldBe List(ids("alice"))
    store.findGroup("acme", "analysts").flatMap(_.externalId) shouldBe Some("ext-1")
  }
