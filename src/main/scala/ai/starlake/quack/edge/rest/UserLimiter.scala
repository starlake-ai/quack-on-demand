package ai.starlake.quack.edge.rest

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.collection.mutable

/** The per-user in-flight cap of the REST data edge: an HTTP-layer resource control bounding what
  * one authenticated principal can make the nodes do at once, since the edge's time cap is a
  * bounded wait, not a cancellation.
  *
  * A slot is keyed by `(tenant, userId)` of the request's principal (the PAT's OWNER, never the
  * PAT), so minting more tokens buys no extra slots. At most `perUser` slots per user and `total`
  * for the whole edge; the handler answers 429 `too_many_requests` past either.
  *
  * One request takes one slot, shared by all its statements (probe, data, listing). The slot is
  * reference-counted: the request holds it, every node call it starts [[UserLimiter.Hold.retain]]s
  * it, and it returns to the pool only when the LAST holder lets go. That is what keeps a slot
  * taken after the HTTP request gave up on a statement (504), and while a streamed body is still
  * being read from the node: the node call's hold is released only when its result is closed or the
  * call itself completes or fails. Every hold releases at most once, so a double release can never
  * raise the cap. State is per replica: under HA the effective budget is N times the configured
  * one.
  */
final class UserLimiter(val perUser: Int, val total: Int):

  private val perKey = mutable.HashMap.empty[(String, String), Int]
  private var all    = 0

  /** A slot for `user` (`(tenant, userId)`), or None when a cap is reached. */
  def tryAcquire(user: (String, String)): Option[UserLimiter.Hold] = synchronized {
    val mine = perKey.getOrElse(user, 0)
    Option.when(mine < perUser && all < total) {
      perKey.update(user, mine + 1)
      all += 1
      new Slot(user).firstHold
    }
  }

  /** Slots `user` holds right now. */
  def inFlight(user: (String, String)): Int = synchronized(perKey.getOrElse(user, 0))

  /** Slots held across the edge right now. */
  def inFlightTotal: Int = synchronized(all)

  private def free(user: (String, String)): Unit = synchronized {
    perKey
      .get(user)
      .foreach(n => if n <= 1 then perKey.remove(user) else perKey.update(user, n - 1))
    all -= 1
  }

  /** One acquired slot and the count of holders still using it. */
  private final class Slot(user: (String, String)):
    private val holders = new AtomicInteger(1)

    val firstHold: UserLimiter.Hold = new SlotHold

    private final class SlotHold extends UserLimiter.Hold:
      private val released = new AtomicBoolean(false)

      def retain(): UserLimiter.Hold =
        // Only while someone still holds the slot: a slot already freed is never revived.
        val live = holders.getAndUpdate(n => if n > 0 then n + 1 else n) > 0
        if live then new SlotHold else UserLimiter.Hold.Released

      def release(): Unit =
        if released.compareAndSet(false, true) && holders.decrementAndGet() == 0 then free(user)

object UserLimiter:

  /** A share of one slot. [[release]] is idempotent; [[retain]] hands out another share that the
    * slot waits for too.
    */
  trait Hold:
    def retain(): Hold
    def release(): Unit

  object Hold:
    /** A share of a slot that is already free: holds nothing, frees nothing. */
    val Released: Hold = new Hold:
      def retain(): Hold  = this
      def release(): Unit = ()

  /** The limiter a `quack-rest` block configures. */
  def apply(cfg: ai.starlake.quack.RestEdgeConfig): UserLimiter =
    new UserLimiter(cfg.maxConcurrentPerUser, cfg.maxConcurrentTotal)
