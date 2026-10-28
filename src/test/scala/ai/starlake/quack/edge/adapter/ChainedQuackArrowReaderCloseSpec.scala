package ai.starlake.quack.edge.adapter

import org.apache.arrow.vector.VectorUnloader
import org.apache.arrow.vector.ipc.ArrowReader
import org.apache.arrow.vector.types.pojo.Schema
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{CountDownLatch, TimeUnit}

/** A close from another thread while a batch is being read (how the REST data edge forces a stalled
  * read to return) must not release what that read still uses. No FETCH runs here, so no native.
  */
class ChainedQuackArrowReaderCloseSpec extends AnyFlatSpec with Matchers:

  /** A child whose read blocks on `gate`, counting its closes. */
  private final class GatedChild(inner: ArrowReader, reading: CountDownLatch, gate: CountDownLatch)
      extends ArrowReader(TestArrow.sharedAllocator):
    val closes                            = new AtomicInteger()
    override def loadNextBatch(): Boolean =
      reading.countDown()
      gate.await(10, TimeUnit.SECONDS)
      val more = inner.loadNextBatch()
      if more then
        val batch = new VectorUnloader(inner.getVectorSchemaRoot).getRecordBatch
        try loadRecordBatch(batch)
        finally batch.close()
      more
    override def close(closeReadSource: Boolean): Unit =
      closes.incrementAndGet()
      super.close(closeReadSource)
    override def bytesRead(): Long                 = inner.bytesRead()
    override protected def closeReadSource(): Unit = inner.close()
    override protected def readSchema(): Schema    = inner.getVectorSchemaRoot.getSchema

  "a chained Quack reader" should "leave its batches to a read in progress when closed from another thread" in {
    val reading = new CountDownLatch(1)
    val gate    = new CountDownLatch(1)
    val child   = new GatedChild(
      TestArrow.readerFor("SELECT range::INTEGER AS c_id FROM range(3)"),
      reading,
      gate
    )
    val disconnects = new AtomicInteger()
    val chained     = new ChainedQuackArrowReader(
      TestArrow.sharedAllocator,
      child,
      () => fail("no FETCH expected"),
      () => disconnects.incrementAndGet(): Unit,
      false
    )
    val outcome = new AtomicReference[Either[Throwable, Boolean]]()
    val reader  = new Thread(() =>
      outcome.set(
        try Right(chained.loadNextBatch())
        catch case t: Throwable => Left(t)
      )
    )
    reader.start()
    reading.await(5, TimeUnit.SECONDS) shouldBe true
    chained.close()
    // The disconnect, which is what unblocks a node read, is not held back...
    disconnects.get shouldBe 1
    // ...but nothing the read uses is released under it.
    child.closes.get shouldBe 0
    gate.countDown()
    reader.join(5000)
    // The read fails rather than hand out a batch whose root is gone, and releases on its way out.
    outcome.get.left.map(_.getClass) shouldBe Left(classOf[IllegalStateException])
    child.closes.get shouldBe 1
    chained.loadNextBatch() shouldBe false
    chained.close()
    child.closes.get shouldBe 1
    disconnects.get shouldBe 1
  }

  it should "release at once when closed with no read in progress" in {
    val gate    = new CountDownLatch(0)
    val child   = new GatedChild(TestArrow.readerFor("SELECT 1 AS x"), new CountDownLatch(1), gate)
    val chained =
      new ChainedQuackArrowReader(TestArrow.sharedAllocator, child, () => fail(), () => (), false)
    chained.loadNextBatch() shouldBe true
    chained.getVectorSchemaRoot.getRowCount shouldBe 1
    chained.close()
    child.closes.get shouldBe 1
  }
