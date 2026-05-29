package ai.starlake.quack.ondemand.state

import ai.starlake.quack.model.{PoolKey, Role, RoleDistribution, RunningNode}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.{Files, Path}
import java.time.Instant

class StateStoreSpec extends AnyFlatSpec with Matchers:

  private def tmpFile: Path = Files.createTempFile("quack-state-", ".json")

  private val key: PoolKey = PoolKey("acme", "sales")
  private val node = RunningNode("n1", key, Role.Dual, "127.0.0.1", 21900, "tok",
                                 Some(12345L), None, Instant.parse("2026-01-01T00:00:00Z"))
  private val pool = StoredPool(
    key = key,
    size = 1,
    distribution = RoleDistribution(0, 0, 1),
    metastore = Map("pgHost" -> "localhost"),
    s3 = Map.empty,
    nodes = List(node)
  )

  "StateStore" should "write-then-read round-trip" in:
    val path = tmpFile
    val store = StateStore(path)
    store.save(StoredState(Map(key.toString -> pool)))
    store.load() shouldBe StoredState(Map(key.toString -> pool))

  it should "return empty state when file doesn't exist" in:
    val path = Path.of("/tmp/does-not-exist-" + System.nanoTime())
    StateStore(path).load() shouldBe StoredState(Map.empty)

  it should "write atomically (no partial file on rename)" in:
    val path = tmpFile
    val store = StateStore(path)
    store.save(StoredState(Map(key.toString -> pool)))
    Files.exists(path) shouldBe true
    Files.size(path) should be > 0L

  it should "fail loudly on malformed JSON" in:
    val path = tmpFile
    Files.writeString(path, "{ this is not json")
    intercept[RuntimeException](StateStore(path).load())

  it should "read legacy state files that lack the `tenants` field" in:
    // Pre-tenants format - only `pools`. Decoder must default `tenants` to empty.
    val path = tmpFile
    Files.writeString(path,
      """{"pools":{"acme/sales":{"key":"acme/sales","size":1,
        |"distribution":{"writeonly":0,"readonly":0,"dual":1},
        |"metastore":{},"s3":{},"nodes":[],"maxConcurrentPerNode":0}}}""".stripMargin)
    val s = StateStore(path).load()
    s.pools.keySet shouldBe Set("acme/sales")
    s.tenants shouldBe Map.empty

  it should "round-trip pools and tenants together" in:
    val path  = tmpFile
    val store = StateStore(path)
    val state = StoredState(
      pools   = Map(key.toString -> pool),
      tenants = Map(
        "acme" -> StoredTenant("acme", Map("pgHost" -> "h", "pgDb" -> "d")),
        "beta" -> StoredTenant("beta", Map.empty)
      )
    )
    store.save(state)
    store.load() shouldBe state