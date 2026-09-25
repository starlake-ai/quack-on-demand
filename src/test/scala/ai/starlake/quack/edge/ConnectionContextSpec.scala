package ai.starlake.quack.edge

import ai.starlake.quack.model.PoolKey
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ArrayBuffer

/** Session eviction on the Flight edge: every dropped entry reports its connection id to the
  * installed hook, so the router's own session table can be closed alongside.
  */
class ConnectionContextSpec extends AnyFlatSpec with Matchers:

  private val poolKey = PoolKey("acme", "acme_default", "sales")

  "ConnectionContext" should "report expired entries to the eviction hook when swept" in:
    val evicted = ArrayBuffer.empty[String]
    ConnectionContext.onEvict(id => evicted.synchronized { evicted += id; () })
    ConnectionContext.bind("peer-sweep-a", poolKey, "conn-sweep-a", "alice", None, ttlSec = 0)
    ConnectionContext.bind("peer-sweep-b", poolKey, "conn-sweep-b", "bob", None, ttlSec = 3600)
    ConnectionContext.evictExpired() should be >= 1
    evicted.synchronized(evicted.toList) should contain("conn-sweep-a")
    evicted.synchronized(evicted.toList) should not contain "conn-sweep-b"
    ConnectionContext.entry("peer-sweep-b").isDefined shouldBe true
    ConnectionContext.unbind("peer-sweep-b")

  it should "report an entry evicted lazily on access, and one unbound explicitly" in:
    val evicted = ArrayBuffer.empty[String]
    ConnectionContext.onEvict(id => evicted.synchronized { evicted += id; () })
    ConnectionContext.bind("peer-lazy", poolKey, "conn-lazy", "alice", None, ttlSec = 0)
    ConnectionContext.entry("peer-lazy") shouldBe None
    ConnectionContext.bind("peer-unbind", poolKey, "conn-unbind", "alice", None, ttlSec = 3600)
    ConnectionContext.unbind("peer-unbind")
    evicted.synchronized(evicted.toList) should contain allOf ("conn-lazy", "conn-unbind")
