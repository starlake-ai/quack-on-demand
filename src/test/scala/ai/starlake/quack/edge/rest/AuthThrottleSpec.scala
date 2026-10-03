package ai.starlake.quack.edge.rest

import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRateLimiter, AuditRecorder}
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import ch.qos.logback.classic.{Level, Logger as LogbackLogger}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*

/** The failed-authentication throttle of the REST data edge, driven by an injected clock: a sliding
  * window per client key, and a block that outlives counter churn and is reported once.
  */
class AuthThrottleSpec extends AnyFlatSpec with Matchers:

  private final class Clock(start: Long = 1_000_000L):
    val now                     = new AtomicLong(start)
    def apply(): Long           = now.get
    def advance(ms: Long): Unit = now.addAndGet(ms): Unit

  private def throttle(
      clock: Clock,
      perWindow: Int = 20,
      windowSec: Int = 60,
      blockSec: Int = 300,
      maxEntries: Int = 100000,
      blocks: ListBuffer[String] = ListBuffer.empty
  ) =
    new AuthThrottle(
      failuresPerWindow = perWindow,
      windowSec = windowSec,
      blockSec = blockSec,
      maxEntries = maxEntries,
      clock = () => clock(),
      onBlock = k => blocks.synchronized(blocks += k): Unit
    )

  "the per-key window" should "block at failure N+1, not at N" in {
    val c = new Clock
    val t = throttle(c)
    (1 to 20).foreach(_ => t.recordFailure("a") shouldBe false)
    t.blockedFor("a") shouldBe None
    t.recordFailure("a") shouldBe true
    t.blockedFor("a") shouldBe Some(300)
    t.blockedFor("b") shouldBe None
  }

  it should "report the seconds left, rounded up, and lift the block when it expires" in {
    val c = new Clock
    val t = throttle(c)
    (1 to 21).foreach(_ => t.recordFailure("a"))
    c.advance(299_500)
    t.blockedFor("a") shouldBe Some(1)
    c.advance(500)
    t.blockedFor("a") shouldBe None
    // The block started a fresh count: one more failure does not re-block.
    t.recordFailure("a") shouldBe false
  }

  it should "not reset on a success, since there is no success call at all" in {
    // A success is simply not recorded; failures around it keep accumulating.
    val c = new Clock
    val t = throttle(c, perWindow = 3)
    (1 to 3).foreach(_ => t.recordFailure("a"))
    t.blockedFor("a") shouldBe None // a request that then succeeds sees no block
    t.recordFailure("a") shouldBe true
  }

  it should "slide: failures older than the window stop counting" in {
    val c = new Clock
    val t = throttle(c, perWindow = 3, windowSec = 60)
    t.recordFailure("a")
    c.advance(30_000)
    t.recordFailure("a")
    t.recordFailure("a")
    c.advance(30_001) // the first failure is now 60.001 s old
    t.recordFailure("a") shouldBe false
    t.blockedFor("a") shouldBe None
    c.advance(1)
    t.recordFailure("a") shouldBe true
  }

  it should "report each block exactly once to the audit hook" in {
    val c      = new Clock
    val blocks = ListBuffer.empty[String]
    val t      = throttle(c, perWindow = 2, blocks = blocks)
    (1 to 3).foreach(_ => t.recordFailure("a"))
    (1 to 3).foreach(_ => t.recordFailure("b"))
    blocks.toList shouldBe List("a", "b")
  }

  "the memory bound" should "never evict an active block under churn of 200000 other keys" in {
    val c = new Clock
    val t = throttle(c, perWindow = 1, maxEntries = 1000)
    t.recordFailure("victim")
    t.recordFailure("victim") shouldBe true
    (1 to 200000).foreach(i => t.recordFailure(s"k$i"))
    t.cleanUp()
    t.counterEntries should be <= 1000L
    t.blockedFor("victim") shouldBe Some(300)
  }

  it should "bound the blocked set, dropping the block that expires first when full" in {
    val c = new Clock
    val t = throttle(c, perWindow = 1, maxEntries = 3)
    List("a", "b", "c").foreach { k =>
      t.recordFailure(k); t.recordFailure(k); c.advance(1000)
    }
    t.blockedCount shouldBe 3
    t.recordFailure("d")
    t.recordFailure("d") shouldBe true
    t.blockedCount shouldBe 3
    t.blockedFor("a") shouldBe None
    List("b", "c", "d").foreach(k => t.blockedFor(k) should not be empty)
  }

  "a block" should "be reported exactly once when concurrent failures cross the allowance" in
    (1 to 50).foreach { round =>
      val c      = new Clock
      val blocks = ListBuffer.empty[String]
      val t      = throttle(c, perWindow = 1, blocks = blocks)
      t.recordFailure("a") shouldBe false
      // Every thread's failure is past the allowance; exactly one of them blocks the key.
      val start   = new java.util.concurrent.CountDownLatch(1)
      val pool    = java.util.concurrent.Executors.newFixedThreadPool(16)
      val results =
        try
          val fs = (1 to 16).map(_ => pool.submit { () => start.await(); t.recordFailure("a") })
          start.countDown()
          fs.map(_.get(5, java.util.concurrent.TimeUnit.SECONDS))
        finally pool.shutdownNow(): Unit
      withClue(s"round $round: ") {
        results.count(identity) shouldBe 1
        blocks.toList shouldBe List("a")
      }
    }

  "counts" should "hold every authentication failure and nothing else" in {
    AuthThrottle.counts(RestError.Unauthorized) shouldBe true
    AuthThrottle.counts(RestError.Forbidden("your token is scoped to tenant 'x'")) shouldBe true
    List(
      RestError.AclDenied("access denied"),
      RestError.NotFound,
      RestError.InvalidFilter("x"),
      RestError.UnknownColumn("x"),
      RestError.OrderRequired,
      RestError.InvalidKind,
      RestError.UnsupportedFormat,
      RestError.PoolUnavailable,
      RestError.StatementTimeout,
      RestError.UpstreamError,
      RestError.TooManyRequests,
      RestError.TooManyAuthFailures(1)
    ).foreach(e => withClue(e.code)(AuthThrottle.counts(e) shouldBe false))
  }

  "auditingBlocks" should "record a block as an anonymous auth denial with its source, rate-limited" in {
    val store   = new RecordingTelemetryStore
    val audit   = new AuditRecorder(store, _ => None)
    val c       = new Clock
    val limiter = new AuditRateLimiter(intervalMillis = 1000, clock = () => c())
    val t       =
      new AuthThrottle(1, 60, 300, 1000, () => c(), AuthThrottle.auditingBlocks(audit, limiter))
    List("198.51.100.1", "198.51.100.2").foreach(k => (1 to 2).foreach(_ => t.recordFailure(k)))
    c.advance(1000)
    (1 to 2).foreach(_ => t.recordFailure("198.51.100.3"))
    store.events.map(_.action).toList shouldBe List.fill(2)(AuditActions.AuthRestThrottled)
    val e = store.events.head
    (e.actor, e.family, e.outcome, e.origin) shouldBe ("anonymous", "auth", "denied", "rest-data")
    e.detail.get("source") shouldBe Some("198.51.100.1")
    store.events(1).detail.get("source") shouldBe Some("198.51.100.3")
  }

  it should "log a block at WARN without the client address, which only DEBUG carries" in {
    val logger   = LoggerFactory.getLogger(AuthThrottle.getClass).asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]
    appender.start()
    val before = logger.getLevel
    logger.setLevel(Level.ALL)
    logger.addAppender(appender)
    try
      val c = new Clock
      val t = new AuthThrottle(
        1,
        60,
        300,
        1000,
        () => c(),
        AuthThrottle.auditingBlocks(AuditRecorder.noop, new AuditRateLimiter())
      )
      (1 to 2).foreach(_ => t.recordFailure("198.51.100.7"))
    finally
      logger.detachAppender(appender)
      logger.setLevel(before)
    val lines = appender.list.asScala.toList.map(e => (e.getLevel, e.getFormattedMessage))
    lines.filter(_._1 == Level.WARN).map(_._2) shouldBe
      List("REST data edge: a client was blocked after repeated failed authentication")
    lines.filter(_._1 == Level.DEBUG).exists(_._2.contains("198.51.100.7")) shouldBe true
  }
