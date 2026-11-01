package ai.starlake.quack.ondemand.state

import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.util.Try

/** The snapshot-pin lock (ControlPlaneStore.lockSnapshotPins) holds across replicas: two stores on
  * one control plane stand for two managers.
  */
class SnapshotPinLockSpec extends AnyFlatSpec with Matchers:
  TestPostgres.dropStrayTestDatabases("qodpin")

  private def withStores(test: (PostgresControlPlaneStore, PostgresControlPlaneStore) => Unit) =
    TestPostgres.ensureReachable()
    val dbName = s"qodpin_${java.util.UUID.randomUUID().toString.take(8)}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val a = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      val b = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try test(a, b)
      finally
        a.close()
        b.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  "lockSnapshotPins" should "make a second replica wait until the first lets go" in withStores {
    (a, b) =>
      val held     = a.lockSnapshotPins("acme", "lake")
      val acquired = new CountDownLatch(1)
      val waiter   = new Thread(() =>
        val h = b.lockSnapshotPins("acme", "lake")
        acquired.countDown()
        h.close()
      )
      waiter.setDaemon(true)
      waiter.start()
      acquired.await(500, TimeUnit.MILLISECONDS) shouldBe false
      held.close()
      acquired.await(10, TimeUnit.SECONDS) shouldBe true
      waiter.join()
  }

  it should "not make another database wait" in withStores { (a, b) =>
    val held = a.lockSnapshotPins("acme", "lake")
    try
      val other = b.lockSnapshotPins("acme", "other_lake")
      other.close()
    finally held.close()
  }

  it should "be taken again once released" in withStores { (a, _) =>
    a.lockSnapshotPins("acme", "lake").close()
    a.lockSnapshotPins("acme", "lake").close()
  }

  it should "keep the pool usable for the holder however many requests wait for the lock" in
    withStores { (a, _) =>
      // A pool of 3 and 6 waiters: waiters must hold no pooled connection, or the holder's own
      // reads (the pins, the catalog) would starve.
      val small = new PostgresControlPlaneStore(
        a.jdbcDataSource.asInstanceOf[com.zaxxer.hikari.HikariDataSource].getJdbcUrl,
        TestPostgres.pgUser,
        TestPostgres.pgPass,
        poolSize = 3
      )
      try
        val held    = small.lockSnapshotPins("acme", "lake")
        val done    = new CountDownLatch(6)
        val waiters = (1 to 6).map { _ =>
          val t = new Thread(() =>
            small.lockSnapshotPins("acme", "lake").close()
            done.countDown()
          )
          t.setDaemon(true)
          t.start()
          t
        }
        Thread.sleep(300) // the waiters are now blocked in pg_advisory_lock
        val read = new java.util.concurrent.FutureTask[List[ai.starlake.quack.model.Tenant]](() =>
          small.listTenants()
        )
        new Thread(read).start()
        read.get(5, TimeUnit.SECONDS) shouldBe Nil
        held.close()
        done.await(30, TimeUnit.SECONDS) shouldBe true
        waiters.foreach(_.join())
      finally small.close()
    }
