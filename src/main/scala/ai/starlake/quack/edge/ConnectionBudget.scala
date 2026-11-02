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

  /** The most one connection can hold before any credential. Waiting for the end of its headers, a
    * parsed request line and a parsed header block, each up to `maxHeaderBytes`: Ember applies the
    * limit to each. Reading a body, the header block, the rest of its first read (Ember keeps it
    * for the whole request) and the body: its request line is then the short path of a route that
    * reads one.
    */
  def heldBytes(maxHeaderBytes: Int, maxBodyBytes: Long, tls: Boolean): Long =
    val parsing = 2L * maxHeaderBytes * HeapPerParsedByte
    val reading = maxHeaderBytes * HeapPerParsedByte + BoundedBody.ReadBufferBytes + maxBodyBytes
    ConnectionBytes + math.max(parsing, reading) + (if tls then TlsBytes else 0L)

  /** At most `configured`, and no more than fit a fifth of `maxHeapBytes` at `heldBytes` each. */
  def fit(configured: Int, heldBytes: Long, maxHeapBytes: Long): Int =
    math.max(1L, math.min(configured.toLong, maxHeapBytes / HeapParts / heldBytes)).toInt

  /** [[fit]] for this JVM's heap, saying so at boot when that is fewer than `configured` (println,
    * as the logger's default level would swallow it).
    */
  def slots(listener: String, envVar: String, configured: Int, heldBytes: Long): Int =
    val maxHeap = Runtime.getRuntime.maxMemory
    val taken   = fit(configured, heldBytes, maxHeap)
    if taken < configured then
      println(
        s"$listener takes $taken connections, not the $configured of $envVar: each can hold " +
          s"${heldBytes / 1024} KiB before any credential, and a fifth of this " +
          s"${maxHeap / (1024 * 1024)} MiB heap holds no more. Raise the heap (-Xmx) to take more"
      )
    taken
