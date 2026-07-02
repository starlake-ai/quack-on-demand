package ai.starlake.quack.ondemand.ha

import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

class HaCoordinatorSpec extends AnyFlatSpec with Matchers:

  import TestPostgres.{pgHost, pgPass, pgPort, pgUser}
  private val url = s"jdbc:postgresql://$pgHost:$pgPort/postgres"

  private def coordinator(handlers: Map[String, String => Unit] = Map.empty) =
    new HaCoordinator(url, pgUser, pgPass, 1.second, handlers)

  private def requirePg(): Unit =
    if !TestPostgres.reachable then
      cancel(s"local Postgres not reachable at $pgHost:$pgPort (SL_TEST_PG_* envs); skipping")

  "HaCoordinator" should "elect exactly one leader among two contenders" in {
    requirePg()
    val a = coordinator(); val b = coordinator()
    try
      a.tickNow(); b.tickNow()
      List(a.isLeader, b.isLeader).count(identity) shouldBe 1
    finally { a.close(); b.close() }
  }

  it should "fail over when the leader closes its connection" in {
    requirePg()
    val a = coordinator(); val b = coordinator()
    try
      a.tickNow(); b.tickNow()
      val (leader, follower) = if a.isLeader then (a, b) else (b, a)
      leader.close()
      // pg frees a session advisory lock as soon as the holding session ends
      eventuallyLeader(follower)
    finally { a.close(); b.close() }
  }

  private def eventuallyLeader(c: HaCoordinator): Unit =
    val deadline = System.nanoTime() + 10_000_000_000L
    while !c.isLeader && System.nanoTime() < deadline do
      c.tickNow(); Thread.sleep(100)
    c.isLeader shouldBe true

  it should "dispatch notifications to the registered handler once per channel per tick" in {
    requirePg()
    val seen = scala.collection.mutable.ListBuffer.empty[String]
    val c    = coordinator(Map("qod_test_chan" -> (p => seen += p)))
    try
      c.tickNow() // connects + LISTENs
      val sender = java.sql.DriverManager.getConnection(url, pgUser, pgPass)
      try
        sender.createStatement().execute("NOTIFY qod_test_chan, 'p1'")
        sender.createStatement().execute("NOTIFY qod_test_chan, 'p2'")
      finally sender.close()
      c.tickNow()
      // coalesced: one dispatch per channel per tick, latest payload wins
      seen.toList shouldBe List("p2")
    finally c.close()
  }

  it should "demote and recover after its connection is killed server-side" in {
    requirePg()
    val c = coordinator()
    try
      c.tickNow()
      c.isLeader shouldBe true
      // kill the coordinator's backend from a second connection; the
      // coordinator tags its connection with ApplicationName=qod-ha-coordinator
      val admin = java.sql.DriverManager.getConnection(url, pgUser, pgPass)
      try
        val st = admin.prepareStatement(
          "SELECT pg_terminate_backend(pid) FROM pg_stat_activity " +
            "WHERE pid <> pg_backend_pid() AND application_name = 'qod-ha-coordinator'"
        )
        st.executeQuery()
      finally admin.close()
      // next tick hits the dead socket: must demote, then re-acquire on the one after
      eventuallyLeader(c)
    finally c.close()
  }
