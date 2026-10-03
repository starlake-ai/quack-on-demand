package ai.starlake.quack.edge.rest

import ai.starlake.quack.edge.adapter.TestArrow
import cats.effect.unsafe.implicits.global
import org.apache.arrow.vector.ipc.ArrowReader
import org.apache.arrow.vector.types.pojo.Schema
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files
import java.sql.DriverManager
import scala.concurrent.duration.*

/** Parquet streaming through the in-process DuckDB and a named pipe: what a client reads back is a
  * valid Parquet file holding the source's rows up to the cap, in order and with their types; early
  * failures surface at open; an abandoned write is torn down without leaving anything behind.
  */
class RestParquetWriterSpec extends AnyFlatSpec with Matchers:

  private def assumeAvailable(): Unit =
    assume(RestParquetWriter.available, "no POSIX named pipes on this host")

  /** The whole file: the first bytes, the rest, then the writer torn down. */
  private def written(w: RestParquetWriter): Array[Byte] =
    try (fs2.Stream.chunk(fs2.Chunk.array(w.first)) ++ w.rest).compile.to(Array).unsafeRunSync()
    finally w.close().unsafeRunSync()

  private def write(sql: String, maxRows: Int): (Array[Byte], Long, Boolean) =
    val source = TestArrow.readerFor(sql)
    try
      val w     = RestParquetWriter.open(source, maxRows).unsafeRunSync()
      val bytes = written(w)
      (bytes, w.rows, w.more)
    finally source.close()

  /** Runs `query` (with `$f` for the file) over the bytes, read back as a Parquet file. */
  private def readBack(bytes: Array[Byte], query: String): List[List[String]] =
    val f = Files.createTempFile("rest-parquet-spec", ".parquet")
    try
      Files.write(f, bytes)
      val c = DriverManager.getConnection("jdbc:duckdb:")
      try
        val rs   = c.createStatement().executeQuery(query.replace("$f", s"'$f'"))
        val cols = rs.getMetaData.getColumnCount
        Iterator
          .continually(rs.next())
          .takeWhile(identity)
          .map(_ => (1 to cols).map(i => String.valueOf(rs.getObject(i))).toList)
          .toList
      finally c.close()
    finally Files.deleteIfExists(f)

  "the writer" should "stream a valid Parquet file of every row under the cap" in {
    assumeAvailable()
    val (bytes, rows, more) = write("SELECT range AS id, 'r' || range AS s FROM range(5000)", 10000)
    new String(bytes.take(4), "US-ASCII") shouldBe "PAR1"
    new String(bytes.takeRight(4), "US-ASCII") shouldBe "PAR1"
    readBack(bytes, "SELECT count(*), min(id), max(id), max(s) FROM read_parquet($f)") shouldBe
      List(List("5000", "0", "4999", "r999"))
    (rows, more) shouldBe (5000L, false)
  }

  it should "stop at the cap, keep the order and report the cut" in {
    assumeAvailable()
    val (bytes, rows, more) = write("SELECT range AS id FROM range(50000)", 30000)
    readBack(
      bytes,
      "SELECT count(*), bool_and(id = rn - 1) FROM " +
        "(SELECT id, row_number() OVER () AS rn FROM read_parquet($f))"
    ) shouldBe List(List("30000", "true"))
    (rows, more) shouldBe (30000L, true)
  }

  it should "fail at open when the byte cap is reached before the first byte" in {
    assumeAvailable()
    val source = TestArrow.readerFor("SELECT range AS id FROM range(5000)")
    try
      a[CappedArrowReader.ByteCapReached] should be thrownBy
        RestParquetWriter.open(source, 10000, maxBytes = 1).unsafeRunSync()
    finally source.close()
  }

  it should "fail, never writing the footer, past the byte cap after the first row group" in {
    assumeAvailable()
    // 8 KiB of BIGINT per 1024-row batch: a 300 KiB cap falls past the first 16384-row group.
    val source = TestArrow.readerFor("SELECT range AS id FROM range(100000)")
    try
      val w    = RestParquetWriter.open(source, 1000000, maxBytes = 300L * 1024).unsafeRunSync()
      val sent = scala.collection.mutable.ArrayBuffer.empty[Byte]
      val out  =
        try
          (fs2.Stream.chunk(fs2.Chunk.array(w.first)) ++ w.rest).chunks
            .evalMap(c => cats.effect.IO(sent ++= c.toList))
            .compile
            .drain
            .attempt
            .unsafeRunSync()
        finally w.close().unsafeRunSync()
      out.isLeft shouldBe true
      new String(sent.takeRight(4).toArray, "US-ASCII") should not be "PAR1"
      w.cut shouldBe true
    finally source.close()
  }

  it should "write a whole file when the byte cap is crossed by the last batch" in {
    assumeAvailable()
    val source = TestArrow.readerFor("SELECT range AS id FROM range(1024)")
    try
      val w = RestParquetWriter.open(source, 10000, maxBytes = 1).unsafeRunSync()
      readBack(written(w), "SELECT count(*) FROM read_parquet($f)") shouldBe List(List("1024"))
      w.cut shouldBe false
    finally source.close()
  }

  it should "write rows too wide for full-size row groups within its memory limit" in {
    assumeAvailable()
    // 20000 rows of ~10 KB: one 16384-row group would be ~160 MB, past the 64 MB limit.
    val (bytes, rows, _) =
      write(
        "SELECT range AS id, repeat(chr(65 + (range % 26)::INTEGER), 10000) AS s FROM range(20000)",
        100000
      )
    rows shouldBe 20000L
    readBack(bytes, "SELECT count(*), max(length(s)) FROM read_parquet($f)") shouldBe
      List(List("20000", "10000"))
  }

  "rowGroupRows" should "size a row group to the target bytes, between one row and the row cap" in {
    RestParquetWriter.rowGroupRows(
      bytes = 8L * 1024,
      rows = 1024
    ) shouldBe RestParquetWriter.RowGroupRows
    RestParquetWriter.rowGroupRows(bytes = 10L * 1024 * 1024, rows = 1024) shouldBe 819
    RestParquetWriter.rowGroupRows(bytes = Long.MaxValue / 2, rows = 1) shouldBe 1
    RestParquetWriter.rowGroupRows(bytes = 0, rows = 0) shouldBe RestParquetWriter.RowGroupRows
  }

  it should "keep the column types, NULLs and enums" in {
    assumeAvailable()
    val sql =
      "SELECT range::INTEGER AS n, CASE WHEN range % 2 = 0 THEN NULL ELSE range END AS maybe, " +
        "(range / 3)::DECIMAL(10,2) AS d, DATE '2026-01-01' + range::INTEGER AS day, " +
        "(['a', 'b'])[range % 2 + 1]::ENUM('a', 'b') AS e FROM range(10)"
    val (bytes, _, _) = write(sql, 100)
    readBack(
      bytes,
      "SELECT typeof(n), typeof(d), typeof(day), count(maybe), max(d)::VARCHAR, " +
        "max(day)::VARCHAR, string_agg(e::VARCHAR, '' ORDER BY n) FROM read_parquet($f) " +
        "GROUP BY ALL"
    ) shouldBe List(
      List("INTEGER", "DECIMAL(10,2)", "DATE", "5", "3.00", "2026-01-10", "abababab" + "ab")
    )
  }

  it should "write a valid empty file for an empty result" in {
    assumeAvailable()
    val (bytes, rows, more) = write("SELECT range AS id FROM range(0)", 10)
    readBack(bytes, "SELECT count(*) FROM read_parquet($f)") shouldBe List(List("0"))
    (rows, more) shouldBe (0L, false)
  }

  it should "fail at open when the first batch fails" in {
    assumeAvailable()
    val source = new FailingReader(TestArrow.readerFor("SELECT range AS id FROM range(10)"))
    try
      RestParquetWriter.open(source, 10).attempt.unsafeRunSync().isLeft shouldBe true
    finally source.close()
  }

  it should "tear an abandoned write down promptly, leaving no pipe behind" in {
    assumeAvailable()
    val source =
      TestArrow.readerFor("SELECT range AS id, repeat('x', 200) AS pad FROM range(200000)")
    try
      val w = RestParquetWriter.open(source, 200000).unsafeRunSync()
      Files.exists(w.pipeDir) shouldBe true
      // The client goes away after the first bytes: close must not hang on the writer.
      w.close().timeout(20.seconds).unsafeRunSync()
      Files.exists(w.pipeDir) shouldBe false
      w.close().unsafeRunSync() // idempotent
    finally source.close()
  }

  it should "tear the write down when its open fails after the COPY started" in {
    assumeAvailable()
    val source =
      TestArrow.readerFor("SELECT range AS id, repeat('x', 200) AS pad FROM range(200000)")
    try
      val dir    = new java.util.concurrent.atomic.AtomicReference[java.nio.file.Path]()
      val failed = cats.effect.IO.uncancelable { poll =>
        RestParquetWriter.opening(
          source,
          200000,
          Long.MaxValue,
          cats.effect.IO.unit,
          poll,
          copyStarted = d =>
            cats.effect.IO(dir.set(d)) *>
              cats.effect.IO.raiseError(new IllegalStateException("injected"))
        )
      }
      val out = failed.attempt.timeout(20.seconds).unsafeRunSync()
      out.left.toOption.map(_.getMessage) shouldBe Some("injected")
      // The COPY was stopped and joined, the instance and the pipe released.
      Files.exists(dir.get) shouldBe false
    finally source.close()
  }

  /** A source whose every batch fails, as a node that drops the connection. */
  private final class FailingReader(inner: ArrowReader)
      extends ArrowReader(TestArrow.sharedAllocator):
    override def loadNextBatch(): Boolean =
      throw new java.io.IOException("node connection reset")
    override def getVectorSchemaRoot(): org.apache.arrow.vector.VectorSchemaRoot =
      inner.getVectorSchemaRoot
    override def bytesRead(): Long                 = 0L
    override protected def closeReadSource(): Unit = inner.close()
    override protected def readSchema(): Schema    = inner.getVectorSchemaRoot.getSchema
