package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.{ActiveStatementRegistry, StatementHistoryStore}
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class KillBroadcastSpec extends AnyFlatSpec with Matchers:

  private def freshHandlers(registry: ActiveStatementRegistry, history: StatementHistoryStore) =
    new ActiveStatementHandlers(
      registry,
      history,
      new InMemoryControlPlaneStore(),
      haEnabled = false
    )

  "onKillBroadcast" should "kill an owned statement within the tenant scope and record killed" in:
    val registry = new ActiveStatementRegistry()
    val history  = new StatementHistoryStore()
    val h        = freshHandlers(registry, history)
    val id       = registry.register("alice", "acme", "bi", "n1", "SELECT 1")
    h.onKillBroadcast(KillBroadcast.encode(id, Some(List("acme"))))
    registry.list() shouldBe Nil
    history.snapshot(10).map(_.status) shouldBe List("killed")

  it should "ignore a broadcast whose tenant scope does not cover the statement" in:
    val registry = new ActiveStatementRegistry()
    val h        = freshHandlers(registry, new StatementHistoryStore())
    val id       = registry.register("bob", "globex", "bi", "n1", "SELECT 1")
    h.onKillBroadcast(KillBroadcast.encode(id, Some(List("acme"))))
    registry.list().map(_.id) shouldBe List(id)

  it should "ignore malformed payloads and unknown ids" in:
    val registry = new ActiveStatementRegistry()
    val h        = freshHandlers(registry, new StatementHistoryStore())
    h.onKillBroadcast("not json")
    h.onKillBroadcast(KillBroadcast.encode("unknown-id", None))
    registry.list() shouldBe Nil

  "killByPats" should "kill matching statements, record killed, and count them" in:
    val registry = new ActiveStatementRegistry()
    val history  = new StatementHistoryStore()
    val h        = freshHandlers(registry, history)
    registry.register("agent", "acme", "bi", "n1", "SELECT 1", patId = Some("pat-a"))
    registry.register("agent", "acme", "bi", "n1", "SELECT 2", patId = Some("pat-b"))
    val idSession = registry.register("alice", "acme", "bi", "n1", "SELECT 3")
    h.killByPats(Set("pat-a", "pat-b")) shouldBe 2
    registry.list().map(_.id) shouldBe List(idSession)
    history.snapshot(10).map(_.status) shouldBe List("killed", "killed")

  "onPatKillBroadcast" should "kill by pat set from the wire payload" in:
    val registry = new ActiveStatementRegistry()
    val history  = new StatementHistoryStore()
    val h        = freshHandlers(registry, history)
    registry.register("agent", "acme", "bi", "n1", "SELECT 1", patId = Some("pat-a"))
    h.onPatKillBroadcast(PatKillBroadcast.encode(Set("pat-a")))
    registry.list() shouldBe Nil
    history.snapshot(10).map(_.status) shouldBe List("killed")

  "PatKillBroadcast.encodeBatches" should "split large pat sets into decodable NOTIFY-sized payloads" in:
    val ids      = (1 to 101).map(i => f"pat-$i%032d").toSet
    val payloads = PatKillBroadcast.encodeBatches(ids)
    payloads should have size 2
    payloads.foreach(p => p.getBytes("UTF-8").length should be < 8000)
    val decoded  = payloads.flatMap { p =>
      io.circe.parser.decode[PatKillBroadcast](p).toOption.map(_.patIds).getOrElse(Nil)
    }
    decoded.toSet shouldBe ids

  it should "ignore malformed payloads and unknown pat ids" in:
    val registry = new ActiveStatementRegistry()
    val h        = freshHandlers(registry, new StatementHistoryStore())
    val id       = registry.register("agent", "acme", "bi", "n1", "SELECT 1", patId = Some("pat-a"))
    h.onPatKillBroadcast("not json")
    h.onPatKillBroadcast(PatKillBroadcast.encode(Set("pat-unknown")))
    registry.list().map(_.id) shouldBe List(id)
