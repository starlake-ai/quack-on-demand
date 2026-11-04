package ai.starlake.quack.ondemand.runtime

import scala.collection.concurrent.TrieMap

final class PortAllocator(min: Int, max: Int):
  private val leased = TrieMap.empty[Int, Unit]

  def lease(): Option[Int] = synchronized {
    (min to max).find(p => !leased.contains(p) && free(p)).map { p =>
      leased.put(p, ())
      p
    }
  }

  /** Whether nothing listens on `port`. A node left by a manager that died keeps its port, and a
    * merge node has no row for the next manager to learn it from: a node started on that port
    * cannot bind, while the next manager's calls reach the leftover and fail "Authentication
    * failed". A connect, not a bind: on macOS a JVM's bind succeeds beside an IPv4 listener on the
    * same port. A leftover bound to one other address (`QOD_NODE_BIND`) goes unseen. The probe runs
    * under the allocator's monitor, at most 200 ms per unanswered port. A node killed a moment
    * earlier may still be seen as listening, and its replacement gets the next port, which is
    * benign.
    */
  private def free(port: Int): Boolean =
    val s = new java.net.Socket()
    try
      s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 200)
      false
    catch case _: java.io.IOException => true
    finally s.close()

  /** Hint to allocator that this port is in use (used on restart recovery). */
  def markLeased(port: Int): Unit =
    if port >= min && port <= max then leased.put(port, ())

  /** Lease `port` itself when it is in range and free; false when it is out of range or taken. */
  def tryLease(port: Int): Boolean = synchronized {
    if port < min || port > max || leased.contains(port) then false
    else
      leased.put(port, ())
      true
  }

  def release(port: Int): Unit = { leased.remove(port); () }
