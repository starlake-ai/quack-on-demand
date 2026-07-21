package ai.starlake.quack.ondemand.catalog

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.{Duration, Instant}
import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable.ListBuffer

class ReaderCacheSweeperSpec extends AnyFlatSpec with Matchers:

  "sweep" should "evict only entries idle past the threshold and close them" in {
    val cache    = new ConcurrentHashMap[String, ReaderCacheSweeper.Entry[String]]()
    val closed   = ListBuffer.empty[String]
    val now      = Instant.parse("2026-07-20T12:00:00Z")
    val oldEntry = new ReaderCacheSweeper.Entry[String]("old-reader")
    oldEntry.lastAccess = now.minus(Duration.ofMinutes(31))
    val freshEntry = new ReaderCacheSweeper.Entry[String]("fresh-reader")
    freshEntry.lastAccess = now.minus(Duration.ofMinutes(1))
    cache.put("old-key", oldEntry)
    cache.put("fresh-key", freshEntry)

    val sweeper = new ReaderCacheSweeper[String, String](
      cache,
      closeReader = r => closed += r,
      idleEvict = Duration.ofMinutes(30),
      now = () => now
    )

    sweeper.sweep() shouldBe 1
    closed.toList shouldBe List("old-reader")
    cache.keySet().size() shouldBe 1
    cache.containsKey("fresh-key") shouldBe true
  }

  it should "leave a re-added entry alone after eviction (rebuild race)" in {
    val cache    = new ConcurrentHashMap[String, ReaderCacheSweeper.Entry[String]]()
    val closed   = ListBuffer.empty[String]
    val t0       = Instant.parse("2026-07-20T12:00:00Z")
    val oldEntry = new ReaderCacheSweeper.Entry[String]("old-reader")
    oldEntry.lastAccess = t0.minus(Duration.ofMinutes(31))
    cache.put("key", oldEntry)

    var clock   = t0
    val sweeper = new ReaderCacheSweeper[String, String](
      cache,
      closeReader = r => closed += r,
      idleEvict = Duration.ofMinutes(30),
      now = () => clock
    )

    sweeper.sweep() shouldBe 1
    closed.toList shouldBe List("old-reader")

    // Racing rebuild: a fresh entry lands under the same key right after eviction,
    // stamped by a fresh access (a concurrent computeIfAbsent winning the race).
    val rebuilt = new ReaderCacheSweeper.Entry[String]("rebuilt-reader")
    rebuilt.lastAccess = clock
    cache.put("key", rebuilt)

    // A short time later (well under the idle threshold relative to the
    // rebuilt entry's own stamp), a second sweep must find nothing stale --
    // the rebuilt entry is judged on its own lastAccess, not evicted just
    // because the key was stale a moment ago.
    clock = clock.plus(Duration.ofMinutes(1))
    sweeper.sweep() shouldBe 0
    closed.toList shouldBe List("old-reader")
    cache.containsKey("key") shouldBe true
    cache.get("key") shouldBe rebuilt
  }
