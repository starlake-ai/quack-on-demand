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
