package ai.starlake.quack.edge.rest

import ai.starlake.quack.edge.adapter.TestArrow
import org.apache.arrow.vector.ipc.ArrowStreamReader
import org.apache.arrow.vector.{BigIntVector, IntVector, VarCharVector}
import org.apache.arrow.vector.dictionary.DictionaryEncoder
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import scala.util.Random

/** Arrow IPC re-framing of a query result: what a client reads back is the source's rows up to the
  * cap, in order, with their types and dictionaries, and the writer reports what it cut. The source
  * readers are in-process DuckDB results, 1024 rows per batch.
  */
class RestArrowWriterSpec extends AnyFlatSpec with Matchers:

  /** Everything the writer produces, start to end. */
  private def written(w: RestArrowWriter): Array[Byte] =
    val out = new ByteArrayOutputStream()
    out.write(w.start())
    var next = w.next()
    while next.isDefined do
      out.write(next.get)
      next = w.next()
    out.toByteArray

  private def write(sql: String, maxRows: Int, maxBytes: Long = Long.MaxValue) =
    val source = TestArrow.readerFor(sql)
    val w      = new RestArrowWriter(source, maxRows, maxBytes)
    try (written(w), w.rows, w.more)
    finally
      w.close()
      source.close()

  /** The `id` column of a stream, and its batch count. */
  private def ids(bytes: Array[Byte]): (Vector[Long], Int) =
    val r = new ArrowStreamReader(new ByteArrayInputStream(bytes), TestArrow.sharedAllocator)
    try
      val out     = Vector.newBuilder[Long]
      var batches = 0
      while r.loadNextBatch() do
        batches += 1
        val v = r.getVectorSchemaRoot.getVector("id").asInstanceOf[BigIntVector]
        (0 until r.getVectorSchemaRoot.getRowCount).foreach(i => out += v.get(i))
      (out.result(), batches)
    finally r.close()

  private def range(n: Int) = s"SELECT range AS id FROM range($n)"

  "the writer" should "stream every row of a result under the cap, batch by batch" in {
    val (bytes, rows, more) = write(range(2500), maxRows = 10000)
    val (got, batches)      = ids(bytes)
    got shouldBe (0L until 2500L).toVector
    batches shouldBe 3
    (rows, more) shouldBe (2500L, false)
  }

  it should "slice the batch that crosses the cap and report the cut" in {
    val (bytes, rows, more) = write(range(2500), maxRows = 1500)
    ids(bytes)._1 shouldBe (0L until 1500L).toVector
    (rows, more) shouldBe (1500L, true)
  }

  it should "report the cut when the cap falls exactly on a batch boundary" in {
    write(range(2049), maxRows = 2048) match
      case (bytes, rows, more) =>
        ids(bytes)._1.size shouldBe 2048
        (rows, more) shouldBe (2048L, true)
    write(range(2048), maxRows = 2048) match
      case (_, rows, more) => (rows, more) shouldBe (2048L, false)
  }

  it should "write a valid stream with the schema and no batch for an empty result" in {
    val (bytes, rows, more) = write("SELECT range AS id FROM range(0)", maxRows = 10)
    ids(bytes) shouldBe (Vector.empty, 0)
    (rows, more) shouldBe (0L, false)
  }

  it should "raise past maxBytes with rows left, never writing the end-of-stream marker" in {
    val source = TestArrow.readerFor(range(5000))
    val w      = new RestArrowWriter(source, 10000, 1L)
    try
      // The first batch is always written; the cap stops what follows it, by failing.
      w.start().length should be > 0
      a[CappedArrowReader.ByteCapReached] should be thrownBy w.next()
      (w.rows, w.more, w.cut) shouldBe (1024L, true, true)
    finally
      w.close()
      source.close()
  }

  it should "end cleanly when the byte cap is crossed by the last batch" in {
    val source = TestArrow.readerFor(range(1024))
    val w      = new RestArrowWriter(source, 10000, 1L)
    try
      ids(written(w))._1 shouldBe (0L until 1024L).toVector
      (w.rows, w.more, w.cut) shouldBe (1024L, false, false)
    finally
      w.close()
      source.close()
  }

  it should "keep a dictionary-encoded column readable, its dictionary written" in {
    val sql =
      "SELECT range::INTEGER AS n, (['red', 'green', 'blue'])[range % 3 + 1]::" +
        "ENUM('red', 'green', 'blue') AS colour FROM range(2100)"
    val (bytes, rows, _) = write(sql, maxRows = 3000)
    rows shouldBe 2100L
    val r = new ArrowStreamReader(new ByteArrayInputStream(bytes), TestArrow.sharedAllocator)
    try
      val seen = Vector.newBuilder[(Int, String)]
      while r.loadNextBatch() do
        val root    = r.getVectorSchemaRoot
        val encoded = root.getVector("colour")
        val dict    = r.lookup(encoded.getField.getDictionary.getId)
        val decoded = DictionaryEncoder.decode(encoded, dict).asInstanceOf[VarCharVector]
        try
          val n = root.getVector("n").asInstanceOf[IntVector]
          (0 until root.getRowCount).foreach(i =>
            seen += ((n.get(i), decoded.getObject(i).toString))
          )
        finally decoded.close()
      val all = seen.result()
      all.size shouldBe 2100
      all.foreach((n, c) => c shouldBe List("red", "green", "blue")(n % 3))
    finally r.close()
  }

  "random sizes and caps" should "always yield min(size, cap) rows in order and a correct cut" in {
    val seed = System.nanoTime()
    info(s"seed=$seed")
    val rnd = new Random(seed)
    (1 to 25).foreach { _ =>
      val size                = rnd.nextInt(4000)
      val cap                 = 1 + rnd.nextInt(4000)
      val (bytes, rows, more) = write(range(size), cap)
      withClue(s"seed=$seed size=$size cap=$cap: ") {
        ids(bytes)._1 shouldBe (0L until math.min(size, cap).toLong).toVector
        rows shouldBe math.min(size, cap).toLong
        more shouldBe (size > cap)
      }
    }
  }
