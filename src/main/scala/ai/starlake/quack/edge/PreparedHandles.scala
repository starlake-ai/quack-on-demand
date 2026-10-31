package ai.starlake.quack.edge

import scala.collection.concurrent.TrieMap

/** The FlightSQL prepared-statement handles of this replica, keyed by handle.
  *
  * A handle leaves when its client closes it, when its session leaves [[ConnectionContext]]
  * ([[dropConnection]], from the edge's evict hook), or once unused for `idleTtlMillis` (dropped
  * lazily on access and by [[expireIdle]], run by the edge's session sweeper). A dropped handle
  * answers as unknown. Without the last two a client that prepares and never closes would keep
  * every handle it ever made.
  *
  * `connectionOf` names the session a value belongs to. A use racing an expiry may still get its
  * value; the next use of that handle finds it gone.
  */
final class PreparedHandles[A](idleTtlMillis: Long, clock: () => Long, connectionOf: A => String):
  require(idleTtlMillis > 0, s"prepared handle idle TTL must be positive, got $idleTtlMillis ms")

  private final class Slot(val value: A, @volatile var lastUsed: Long)

  private val slots = TrieMap.empty[String, Slot]

  private def idle(s: Slot, now: Long): Boolean = now - s.lastUsed >= idleTtlMillis

  def put(handle: String, value: A): Unit = { slots.put(handle, new Slot(value, clock())); () }

  /** The handle's value, marking it used now; None when unknown, closed, dropped or idle. */
  def get(handle: String): Option[A] =
    val now = clock()
    slots.get(handle) match
      case Some(s) if idle(s, now) =>
        slots.remove(handle, s)
        None
      case Some(s) =>
        s.lastUsed = now
        Some(s.value)
      case None => None

  def remove(handle: String): Unit = { slots.remove(handle); () }

  /** Drop every handle of the session `connectionId`. Returns how many were dropped. */
  def dropConnection(connectionId: String): Int =
    val gone = slots.iterator.collect {
      case (h, s) if connectionOf(s.value) == connectionId => (h, s)
    }.toList
    gone.count((h, s) => slots.remove(h, s))

  /** Drop every handle unused for the idle TTL. Returns how many were dropped. */
  def expireIdle(): Int =
    val now  = clock()
    val gone = slots.iterator.collect { case (h, s) if idle(s, now) => (h, s) }.toList
    gone.count((h, s) => slots.remove(h, s))

  def size: Int = slots.size
