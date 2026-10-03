package ai.starlake.quack.edge.rest

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.dictionary.Dictionary
import org.apache.arrow.vector.ipc.ArrowReader
import org.apache.arrow.vector.types.pojo.Schema
import org.apache.arrow.vector.{VectorLoader, VectorSchemaRoot, VectorUnloader}

import scala.jdk.CollectionConverters.*

/** At most `maxRows` rows of `inner`, as an [[ArrowReader]] whose batches are `inner`'s, the one
  * that crosses the cap sliced at the cap. This is how the streamed formats enforce the row cap:
  * the data statement fetches one row more than the cap, and the reader both stops at the cap and
  * records whether that extra row existed ([[more]]), without ever holding more than one batch.
  *
  * It also enforces the response byte cap, between batches: once the Arrow data it has handed out
  * (the body length of each batch, what an IPC stream carries apart from its framing) reaches
  * `maxBytes`, it hands out nothing more. When rows were left behind ([[cut]]) the next
  * `loadNextBatch` RAISES [[CappedArrowReader.ByteCapReached]] instead of ending the batches: a
  * streamed body that ended cleanly there would be indistinguishable from a complete page (its
  * paging trailers are easily lost), so the stream fails and the connection is aborted. The cap is
  * crossed by at most the batch that reached it; a page that merely ends there is not a cut.
  *
  * Shaped like the chained Quack reader: one STABLE root re-populated by every `loadNextBatch`
  * (zero-copy, `VectorLoader` shares the inner batch's buffers, so the root lives in the inner
  * vectors' allocator), and every public surface that would initialise the base class is
  * overridden, dictionary lookup included, so dictionary-encoded columns resolve against `inner`'s
  * current dictionaries.
  *
  * `close` releases this reader's root only. `inner` belongs to the query result, whose single
  * finalizer closes it.
  */
final class CappedArrowReader private (
    inner: ArrowReader,
    maxRows: Int,
    maxBytes: Long,
    alloc: BufferAllocator
) extends ArrowReader(alloc):

  private val out: VectorSchemaRoot =
    VectorSchemaRoot.create(inner.getVectorSchemaRoot.getSchema, alloc)
  private val loader = new VectorLoader(out)
  private var served = 0L
  private var sent   = 0L
  private var extra  = false
  private var budget = false
  private var closed = false

  /** Rows handed out so far. */
  def rows: Long = served

  /** Arrow data handed out so far, in bytes (the batches' body lengths). */
  def bytes: Long = sent

  /** Whether `inner` held a row past the cap. Final once [[loadNextBatch]] returned false. */
  def more: Boolean = extra

  /** Whether the byte cap ended the batches while `inner` still held a row below the row cap. Final
    * once [[loadNextBatch]] returned false or raised.
    */
  def cut: Boolean = budget

  private var pending = false

  /** Loads the first batch NOW, so a source that fails on it fails here, and hands that same batch
    * out on the next [[loadNextBatch]] (for a consumer, such as a native scan, that pulls on its
    * own schedule). True when there was a batch.
    */
  def prefetch(): Boolean =
    pending = loadNextBatch()
    pending

  override def loadNextBatch(): Boolean =
    if pending then
      pending = false
      true
    else if closed then false
    else if served >= maxRows then
      // At the cap: one look at what is left, then the stream is over.
      if !extra then extra = hasAnotherRow
      false
    else if sent >= maxBytes then
      // At the byte cap: the same one look, so a page that merely ends here is not a cut.
      if !budget then budget = hasAnotherRow
      if budget then throw new CappedArrowReader.ByteCapReached
      false
    else
      var loaded = false
      while !loaded && inner.loadNextBatch() do
        val src   = inner.getVectorSchemaRoot
        val count = src.getRowCount
        if count > 0 then
          val take = math.min(count.toLong, maxRows - served).toInt
          if take < count then
            extra = true
            val slice = src.slice(0, take)
            try load(slice)
            finally slice.close()
          else load(src)
          served += take
          loaded = true
      loaded

  private def load(src: VectorSchemaRoot): Unit =
    val batch = new VectorUnloader(src).getRecordBatch
    try
      loader.load(batch)
      sent += batch.computeBodyLength()
    finally batch.close()

  private def hasAnotherRow: Boolean =
    var found = false
    while !found && inner.loadNextBatch() do found = inner.getVectorSchemaRoot.getRowCount > 0
    found

  override def getVectorSchemaRoot(): VectorSchemaRoot = out

  override def getDictionaryVectors(): java.util.Map[java.lang.Long, Dictionary] =
    inner.getDictionaryVectors

  override def lookup(id: Long): Dictionary = inner.getDictionaryVectors.get(id)

  override def getDictionaryIds(): java.util.Set[java.lang.Long] =
    inner.getDictionaryVectors.keySet

  override def bytesRead(): Long = inner.bytesRead()

  override def close(closeReadSource: Boolean): Unit =
    if !closed then
      closed = true
      out.close()

  /** Never called: `getVectorSchemaRoot` is overridden, so the base class never initialises. */
  override protected def readSchema(): Schema = out.getSchema

  /** `inner` is closed by its owner, never here. */
  override protected def closeReadSource(): Unit = ()

object CappedArrowReader:

  /** The response byte cap was reached with rows left: the stream must fail, not end. */
  final class ByteCapReached
      extends RuntimeException("the response byte cap was reached with rows left; lower limit")

  /** A capped view of `inner`. Its root is allocated where `inner`'s vectors live, the one place
    * the shared buffers can be accounted to; a result without a column cannot be streamed.
    */
  def apply(inner: ArrowReader, maxRows: Int, maxBytes: Long = Long.MaxValue): CappedArrowReader =
    val alloc = inner.getVectorSchemaRoot.getFieldVectors.asScala.headOption
      .map(_.getAllocator)
      .getOrElse(throw new IllegalStateException("a result without columns cannot be streamed"))
    new CappedArrowReader(inner, maxRows, maxBytes, alloc)
