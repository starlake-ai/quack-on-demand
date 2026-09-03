package ai.starlake.quack.route

import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.spi.ManagerEvent
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PoolActivitySpec extends AnyFlatSpec with Matchers:
  private val poolKey = PoolKey("t", "db", "p")

  "sink" should "record StatementExecuted and PoolResumed, ignoring other events" in:
    var clock = 1000L
    val a     = new PoolActivity(() => clock)
    a.sink.emit(ManagerEvent.StatementExecuted("t", "db", "p", "read", "u", 5L, true))
    clock = 2000L
    a.sink.emit(ManagerEvent.PoolResumed("t", "db", "p", "rest"))
    a.sink.emit(ManagerEvent.PoolCreated("t", "db", "q")) // no-op
    a.drain() shouldBe Map(poolKey -> 2000L)

  "touch" should "keep the max timestamp" in:
    var clock = 5000L
    val a     = new PoolActivity(() => clock)
    a.touch(poolKey)
    clock = 3000L
    a.touch(poolKey)
    a.drain() shouldBe Map(poolKey -> 5000L)

  "drain" should "empty the map so a silent pool stops producing rows" in:
    val a = new PoolActivity(() => 1L)
    a.touch(poolKey)
    a.drain() should have size 1
    a.drain() shouldBe empty
