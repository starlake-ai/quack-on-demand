package ai.starlake.quack.route

import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.spi.{ManagerEvent, ManagerEventSink}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PoolLoadStatsSpec extends AnyFlatSpec with Matchers:
  private val key: PoolKey = PoolKey("t", "db", "p")

  "PoolLoadStats" should "accumulate statements and duration per minute bucket" in:
    var nowMs = 120_000L
    val stats = new PoolLoadStats(clock = () => nowMs)
    stats.record(key, 500L)
    stats.record(key, 1500L)
    nowMs = 200_000L // next minute: bucket 120000 is now closed
    val drained = stats.drainClosed()
    drained shouldBe Map((key, 120_000L) -> PoolLoadStats.Sample(2L, 2000L))
    stats.drainClosed() shouldBe Map.empty // drain removes

  it should "keep the open bucket" in:
    val nowMs = 120_000L
    val stats = new PoolLoadStats(clock = () => nowMs)
    stats.record(key, 500L)
    stats.drainClosed() shouldBe Map.empty // current minute still open

  it should "record StatementExecuted through its sink and ignore other events" in:
    var nowMs = 120_000L
    val stats = new PoolLoadStats(clock = () => nowMs)
    stats.sink.emit(
      ManagerEvent.StatementExecuted("t", "db", "p", "READ", "u", durationMs = 250L, ok = true)
    )
    stats.sink.emit(ManagerEvent.TenantCreated("t"))
    nowMs = 200_000L
    stats.drainClosed() shouldBe Map((key, 120_000L) -> PoolLoadStats.Sample(1L, 250L))

  "ManagerEventSink.fanout" should "deliver to every sink" in:
    var a                     = 0
    var b                     = 0
    val fan: ManagerEventSink =
      ManagerEventSink.fanout((_: ManagerEvent) => a += 1, (_: ManagerEvent) => b += 1)
    fan.emit(ManagerEvent.TenantCreated("t"))
    a shouldBe 1
    b shouldBe 1
