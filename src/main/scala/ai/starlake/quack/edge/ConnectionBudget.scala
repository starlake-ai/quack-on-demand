package ai.starlake.quack.edge

/** How many connections a listener takes. Before any credential a connection can make the manager
  * hold what it sends, and an out-of-memory error is fatal to the manager, so a listener takes no
  * more connections than fit its share of the heap, whatever its configuration says. Raising the
  * heap is how to take more.
  */
object ConnectionBudget:

  /** A listener's share of the heap is one part in this many: the three HTTP listeners flooded at
    * once leave two fifths, for what the manager holds anyway and for bodies being joined.
    */
  private val HeapParts: Long = 5L

  /** The FlightSQL edge's connections on probation get one part in this many: with the three HTTP
    * listeners flooded too, that leaves three tenths.
    */
  val FlightHeapParts: Long = 10L

  /** The read-only REST data edge (off by default) gets one part in this many: with every other
    * listener flooded too, that leaves two tenths.
    */
  val RestDataHeapParts: Long = 10L

  /** What an open connection holds whatever it sends: its read buffer and Ember's state (measured,
    * 69 KB).
    */
  val ConnectionBytes: Long = 70L * 1024

  /** What TLS adds to a connection (measured mid-body on the front door, 162 KB). */
  val TlsBytes: Long = 165L * 1024

  /** Heap per wire byte of a request line or a header block. Ember parses both into objects before
    * a route sees them and holds them for the whole request: 40 for a block of `:\r\n` lines, 39
    * for a path of `/a` segments (measured).
    */
  val HeapPerParsedByte: Long = 40L

  /** The most one connection can hold before any credential: a parsed request line and a parsed
    * header block, each up to `maxHeaderBytes` (Ember applies the limit to each), the rest of its
    * first read (Ember keeps it for the whole request) and a body.
    */
  def heldBytes(maxHeaderBytes: Int, maxBodyBytes: Long, tls: Boolean): Long =
    ConnectionBytes + 2L * maxHeaderBytes * HeapPerParsedByte + BoundedBody.ReadBufferBytes +
      maxBodyBytes + (if tls then TlsBytes else 0L)

  /** At most `configured`, and no more than fit one part in `heapParts` of `maxHeapBytes` at
    * `heldBytes` each.
    */
  def fit(
      configured: Int,
      heldBytes: Long,
      maxHeapBytes: Long,
      heapParts: Long = HeapParts
  ): Int =
    math.max(1L, math.min(configured.toLong, maxHeapBytes / heapParts / heldBytes)).toInt

  private def share(heapParts: Long): String =
    if heapParts == 5L then "a fifth"
    else if heapParts == 10L then "a tenth"
    else s"one part in $heapParts"

  /** What boot says when a listener takes fewer connections than it is configured to.
    * `limitsTunable` is false for a listener whose header and body limits are constants: lowering
    * them is no way to take more there, so the line says only to raise the heap.
    */
  private[edge] def notice(
      listener: String,
      envVar: String,
      configured: Int,
      taken: Int,
      heldBytes: Long,
      maxHeapBytes: Long,
      limitsTunable: Boolean = true,
      heapParts: Long = HeapParts
  ): String =
    s"$listener takes at most $taken of the $configured connections of $envVar: each can hold " +
      s"${heldBytes / 1024} KiB before any credential, against ${share(heapParts)} of this " +
      s"${maxHeapBytes / (1024 * 1024)} MiB heap. " +
      (if limitsTunable then "Raise the heap (-Xmx) or lower the limits to take more"
       else "Raise the heap (-Xmx) to take more")

  /** [[fit]] for this JVM's heap, saying so at boot when that is fewer than `configured` and
    * `announce` holds (println, as the logger's default level would swallow it).
    */
  def slots(
      listener: String,
      envVar: String,
      configured: Int,
      heldBytes: Long,
      limitsTunable: Boolean = true,
      heapParts: Long = HeapParts,
      announce: Boolean = true
  ): Int =
    val maxHeap = Runtime.getRuntime.maxMemory
    val taken   = fit(configured, heldBytes, maxHeap, heapParts)
    if announce && taken < configured then
      println(
        notice(listener, envVar, configured, taken, heldBytes, maxHeap, limitsTunable, heapParts)
      )
    taken
