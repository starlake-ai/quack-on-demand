package ai.starlake.quack.edge.rest

import ai.starlake.quack.edge.adapter.TestArrow
import org.apache.arrow.vector.BigIntVector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The row and byte caps of the streamed formats: one stable root, the source's batches up to the
  * row cap (the crossing batch sliced) and up to the first batch boundary at or past the byte cap,
  * and the source left to its owner.
  */
class CappedArrowReaderSpec extends AnyFlatSpec with Matchers:

  private def batches(r: CappedArrowReader): List[List[Long]] =
    Iterator
      .continually(r.loadNextBatch())
      .takeWhile(identity)
      .map { _ =>
        val v = r.getVectorSchemaRoot.getVector("id").asInstanceOf[BigIntVector]
        (0 until r.getVectorSchemaRoot.getRowCount).map(v.get(_)).toList
      }
      .toList

  "a capped reader" should "hand out the source's batches up to the cap, slicing the last one" in {
    val source = TestArrow.readerFor("SELECT range AS id FROM range(3000)")
    val capped = CappedArrowReader(source, 1030)
    try
      val got = batches(capped)
      got.map(_.size) shouldBe List(1024, 6)
      got.flatten shouldBe (0L until 1030L).toList
      (capped.rows, capped.more) shouldBe (1030L, true)
      // The same root object every time, as the ArrowReader contract says.
      capped.getVectorSchemaRoot shouldBe theSameInstanceAs(capped.getVectorSchemaRoot)
    finally
      capped.close()
      source.close()
  }

  it should "not report a cut when the source ends at or under the cap" in {
    val source = TestArrow.readerFor("SELECT range AS id FROM range(10)")
    val capped = CappedArrowReader(source, 10)
    try
      batches(capped).flatten.size shouldBe 10
      capped.loadNextBatch() shouldBe false
      (capped.rows, capped.more) shouldBe (10L, false)
    finally
      capped.close()
      source.close()
  }

  it should "raise at the first batch boundary at or past the byte cap with rows left" in {
    val source = TestArrow.readerFor("SELECT range AS id FROM range(3000)")
    // One 1024-row BIGINT batch is 8 KiB of data: the cap is crossed by the first one.
    val capped = CappedArrowReader(source, 10000, maxBytes = 4096)
    try
      capped.loadNextBatch() shouldBe true
      capped.getVectorSchemaRoot.getRowCount shouldBe 1024
      // Never a clean end: a stream that stopped here would pass for a complete page.
      a[CappedArrowReader.ByteCapReached] should be thrownBy capped.loadNextBatch()
      a[CappedArrowReader.ByteCapReached] should be thrownBy capped.loadNextBatch()
      (capped.rows, capped.more, capped.cut) shouldBe (1024L, false, true)
      capped.bytes should be >= 4096L
    finally
      capped.close()
      source.close()
  }

  it should "not report a byte cut when nothing was left behind the cap" in {
    val source = TestArrow.readerFor("SELECT range AS id FROM range(1024)")
    val capped = CappedArrowReader(source, 10000, maxBytes = 1)
    try
      batches(capped).flatten.size shouldBe 1024
      capped.loadNextBatch() shouldBe false
      (capped.rows, capped.more, capped.cut) shouldBe (1024L, false, false)
    finally
      capped.close()
      source.close()
  }

  it should "leave a page cut by the row cap to `more`, not to the byte cap" in {
    val source = TestArrow.readerFor("SELECT range AS id FROM range(3000)")
    val capped = CappedArrowReader(source, 1024, maxBytes = 1)
    try
      batches(capped).flatten.size shouldBe 1024
      (capped.rows, capped.more, capped.cut) shouldBe (1024L, true, false)
    finally
      capped.close()
      source.close()
  }

  it should "leave the source open when closed" in {
    val source = TestArrow.readerFor("SELECT range AS id FROM range(2000)")
    val capped = CappedArrowReader(source, 5)
    batches(capped)
    capped.close()
    // The source still reads: the cap's close released only its own root.
    source.loadNextBatch() shouldBe true
    source.close()
  }
