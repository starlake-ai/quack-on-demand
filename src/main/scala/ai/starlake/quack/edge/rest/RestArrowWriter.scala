package ai.starlake.quack.edge.rest

import org.apache.arrow.vector.ipc.{ArrowReader, ArrowStreamWriter}

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel

/** Re-frames a query result as an Arrow IPC stream (`application/vnd.apache.arrow.stream`), one
  * batch at a time: the node's batches are copied by reference into a [[CappedArrowReader]], which
  * enforces the row cap by slicing, and each one is serialised into a buffer that holds only that
  * batch's bytes. Memory is bounded by one batch whatever the result's size.
  *
  * Dictionary-encoded columns (DuckDB ENUMs) keep their encoding: the writer reads the dictionaries
  * of the source reader and writes a replacement whenever one changes, as the stream format allows.
  *
  * `maxBytes` is the response byte cap, enforced between batches by the capped reader: once the
  * batches written reach it with rows left, [[next]] raises [[CappedArrowReader.ByteCapReached]]
  * instead of writing the end-of-stream marker, so the stream never ends as if it were complete.
  * The body can exceed the cap by at most the batch that crossed it plus the stream's framing.
  *
  * Not thread-safe: one consumer pulls [[start]] once, then [[next]] until it answers None.
  */
final class RestArrowWriter(reader: ArrowReader, maxRows: Int, maxBytes: Long = Long.MaxValue):

  private val capped = CappedArrowReader(reader, maxRows, maxBytes)
  private val buffer = new ByteArrayOutputStream()
  private val sink   = new WritableByteChannel:
    @volatile private var open      = true
    def write(src: ByteBuffer): Int =
      val n = src.remaining()
      val a = new Array[Byte](n)
      src.get(a)
      buffer.write(a)
      n
    def isOpen: Boolean = open
    def close(): Unit   = open = false

  private var writer: ArrowStreamWriter = null
  private var ended                     = false

  /** Rows written so far. */
  def rows: Long = capped.rows

  /** Whether rows existed past what was written: past the row cap, or cut by `maxBytes`. Final once
    * [[next]] answered None.
    */
  def more: Boolean = capped.more || capped.cut

  /** Whether `maxBytes` ended the stream while rows were left. Final once [[next]] answered None.
    */
  def cut: Boolean = capped.cut

  /** The schema message and the first batch, if any. Pulled before the answer is committed, so a
    * node failure on the first batch is still an ordinary error response.
    */
  def start(): Array[Byte] =
    val first = capped.loadNextBatch()
    // Built after the first batch: the writer reads the dictionaries the batch brought.
    writer = new ArrowStreamWriter(capped.getVectorSchemaRoot, capped, sink)
    writer.start()
    if first then writer.writeBatch()
    else finish()
    drain()

  /** The next batch's bytes, then the end-of-stream marker, then None; raises at the byte cap with
    * rows left.
    */
  def next(): Option[Array[Byte]] =
    if ended then None
    else
      if capped.loadNextBatch() then writer.writeBatch() else finish()
      Some(drain())

  private def finish(): Unit =
    if !ended then
      ended = true
      // Settles `more` and `cut` when a cap was reached exactly at a batch boundary.
      capped.loadNextBatch(): Unit
      writer.end()

  private def drain(): Array[Byte] =
    val bytes = buffer.toByteArray
    buffer.reset()
    bytes

  /** Releases the writer and the capped root; the source reader stays with its owner. */
  def close(): Unit =
    try if writer != null then writer.close()
    finally capped.close()
