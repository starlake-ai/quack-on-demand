package ai.starlake.quack.edge.rest

import ai.starlake.quack.RestEdgeConfig
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRateLimiter, AuditRecorder}
import com.github.benmanes.caffeine.cache.{Cache, Caffeine}
import com.typesafe.scalalogging.LazyLogging

import scala.collection.mutable

/** The failed-authentication throttle of the REST data edge. An HTTP-layer resource control, not
  * data policy: guessing a 32-byte PAT is infeasible, so what this bounds is the LOAD a credential
  * flood puts on the control plane (one indexed PAT lookup, or one JWT verification, per bad
  * token).
  *
  * Per client key ([[ClientAddress]]), a sliding window: more than `failuresPerWindow` failures
  * within `windowSec` blocks the key for `blockSec`. While blocked, the edge answers 429 before any
  * credential is parsed or looked up. A success does not reset the count (there is deliberately no
  * success call): a valid token held by the same client must not launder a guessing run.
  *
  * There is no edge-wide budget across keys. A run spread over many keys still costs one lookup per
  * attempt whatever the edge answers, so a shared bucket would shed no control-plane load; it would
  * only turn honest clients' 401s into 429s. Volumetric limits belong in front of the edge (a WAF).
  *
  * Memory is bounded. Counters live in a size-bounded Caffeine cache (`maxEntries`): evicting one
  * may forget partial progress, never a block. Blocks live in their own map, also capped at
  * `maxEntries`, and are never dropped before they expire unless that map is full, in which case
  * the block closest to its end goes first. Both are per replica: under HA the effective budget is
  * N times the configured one.
  *
  * `clock` (epoch millis) is the test seam. `onBlock` sees each new block exactly once (the audit
  * hook), however many concurrent failures cross the allowance, on the calling thread, outside
  * every lock.
  */
final class AuthThrottle(
    failuresPerWindow: Int,
    windowSec: Int,
    blockSec: Int,
    maxEntries: Int,
    clock: () => Long = () => System.currentTimeMillis(),
    onBlock: String => Unit = _ => ()
):

  private val windowMs = windowSec * 1000L
  private val blockMs  = blockSec * 1000L

  /** The last `failuresPerWindow + 1` failure instants of one key, oldest first: a ring, since only
    * that many can ever matter.
    */
  private final class Window:
    private val at    = new Array[Long](failuresPerWindow + 1)
    private var start = 0
    private var size  = 0

    /** Records a failure at `now`; true when the window now holds more than the allowance. */
    def add(now: Long): Boolean =
      while size > 0 && at(start) <= now - windowMs do
        start = (start + 1) % at.length
        size -= 1
      at((start + size) % at.length) = now
      if size < at.length then size += 1 else start = (start + 1) % at.length
      size > failuresPerWindow

  // Eviction runs on the calling thread (`Runnable::run`), so `cleanUp` is deterministic in tests
  // and no maintenance task outlives the edge.
  private val counters: Cache[String, Window] =
    Caffeine
      .newBuilder()
      .maximumSize(maxEntries.toLong)
      .executor((r: Runnable) => r.run())
      .build[String, Window]()

  /** key -> block end, and the same entries ordered by end, to drop the earliest when full. */
  private val blocked = mutable.HashMap.empty[String, Long]
  private val byEnd   = mutable.TreeSet.empty[(Long, String)]

  /** Seconds left on the key's block (rounded up, at least 1), or None when it is not blocked. */
  def blockedFor(key: String): Option[Int] =
    val now = clock()
    blocked.synchronized {
      expire(now)
      blocked.get(key).map(end => math.max(1L, (end - now + 999) / 1000).toInt)
    }

  /** Counts one authentication failure of `key`; true when this very failure blocked it. A key that
    * is already blocked is not counted again (its requests never reach authentication). When
    * concurrent failures cross the allowance together, the one whose block lands first is the one
    * that blocked the key: the others answer false and `onBlock` runs once.
    */
  def recordFailure(key: String): Boolean =
    val now = clock()
    if blockedFor(key).isDefined then false
    else
      val w       = counters.get(key, _ => new Window)
      val blocked = w.synchronized(w.add(now)) && block(key, now)
      if blocked then
        counters.invalidate(key)
        onBlock(key)
      blocked

  /** Keys blocked right now. */
  def blockedCount: Int = blocked.synchronized { expire(clock()); blocked.size }

  private[rest] def counterEntries: Long = counters.estimatedSize()

  /** Runs Caffeine's pending maintenance (evictions) now. */
  private[rest] def cleanUp(): Unit = counters.cleanUp()

  /** Blocks `key` from `now` unless it already is; true when this call placed the block. */
  private def block(key: String, now: Long): Boolean = blocked.synchronized {
    expire(now)
    !blocked.contains(key) && {
      if blocked.size >= maxEntries then
        byEnd.headOption.foreach { first =>
          byEnd.remove(first)
          blocked.remove(first._2)
        }
      val end = now + blockMs
      blocked.update(key, end)
      byEnd.add((end, key))
    }
  }

  /** Drops every block that has ended; the caller holds `blocked`'s lock. */
  private def expire(now: Long): Unit =
    while byEnd.headOption.exists(_._1 <= now) do
      val first = byEnd.head
      byEnd.remove(first)
      blocked.remove(first._2)

object AuthThrottle extends LazyLogging:

  /** The throttle a `quack-rest` block configures. */
  def apply(cfg: RestEdgeConfig, onBlock: String => Unit): AuthThrottle =
    new AuthThrottle(
      failuresPerWindow = cfg.authFailuresPerWindow,
      windowSec = cfg.authWindowSec,
      blockSec = cfg.authBlockSec,
      maxEntries = cfg.authThrottleMaxEntries,
      onBlock = onBlock
    )

  /** The audit hook of a new block, recorded the way the manager records its anonymous 401s
    * (`ManagerServer.apiKeyGuard`): actor `anonymous`, family `auth`, origin `rest-data`
    * ([[RestAuth.Origin]], the edge's origin everywhere), the client key as `source`, through an
    * [[AuditRateLimiter]].
    *
    * The limiter is keyed by ONE constant, not per client: a block already happens at most once per
    * key per `blockSec`, but a run rotating through fresh keys (an IPv6 /64 is cheap) can mint a
    * block every `failuresPerWindow + 1` requests, and the recorder writes synchronously. One row
    * per limiter interval is the signal; every block is still logged, at WARN without the client
    * address and at DEBUG with it. A per-key limiter would also keep one entry per key forever,
    * defeating the memory bound.
    */
  def auditingBlocks(audit: AuditRecorder, limiter: AuditRateLimiter): String => Unit = key =>
    logger.warn("REST data edge: a client was blocked after repeated failed authentication")
    logger.debug(s"REST data edge: client $key blocked after repeated failed authentication")
    if limiter.allow("rest-throttle") then
      audit.restAs(
        "anonymous",
        "system",
        "auth",
        AuditActions.AuthRestThrottled,
        "denied",
        detail = Map("source" -> key, "edge" -> RestAuth.Origin),
        origin = RestAuth.Origin
      )

  /** Which refusals are authentication failures: every 401, and the two 403s that judge the
    * credential rather than the data (the token's tenant is not the path's; its tools axis lacks
    * `rest`). An authorization denial, a 404 or a 400 comes from a principal that already
    * authenticated, whom the per-user cap bounds instead.
    */
  def counts(e: RestError): Boolean = e match
    case RestError.Unauthorized | RestError.Forbidden(_) => true
    case _                                               => false
