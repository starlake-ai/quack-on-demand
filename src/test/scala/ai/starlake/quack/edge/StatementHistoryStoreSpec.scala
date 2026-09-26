package ai.starlake.quack.edge

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class StatementHistoryStoreSpec extends AnyFlatSpec with Matchers:

  private def rec(nodeId: String, serverName: Option[String] = None) =
    StatementRecord(
      ts = Instant.EPOCH,
      user = "alice",
      tenant = "acme",
      pool = "bi",
      nodeId = nodeId,
      sql = "SELECT 1",
      durationMs = 1L,
      status = "ok",
      error = None,
      serverName = serverName
    )

  "record" should "stamp the server hosting the node at record time" in:
    val store = new StatementHistoryStore(serverOf = Map("quack-acme-db-bi-1" -> "s1").get)
    store.record(rec("quack-acme-db-bi-1"))
    store.record(rec("manager"))
    store.snapshot(10).map(_.serverName) shouldBe List(None, Some("s1"))

  it should "keep a server name the caller already set" in:
    val store = new StatementHistoryStore(serverOf = _ => Some("s2"))
    store.record(rec("quack-acme-db-bi-1", Some("s1")))
    store.snapshot(1).head.serverName shouldBe Some("s1")
