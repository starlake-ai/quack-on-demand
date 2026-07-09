package ai.starlake.quack.ondemand.api

import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.{IntVector, VarCharVector, VectorSchemaRoot}
import org.apache.arrow.vector.ipc.{ArrowStreamReader, ArrowStreamWriter}
import org.apache.arrow.vector.types.pojo.{ArrowType, Field, FieldType, Schema}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.charset.StandardCharsets
import java.util.Collections

/** Real in-memory Arrow round trip: build a `VectorSchemaRoot` with an `IntVector` + a
  * `VarCharVector` (one row null), serialize it via `ArrowStreamWriter`, and read it back through
  * `ArrowStreamReader` -- the same reader shape `ArrowRowsDecoder.decode` consumes in production
  * (an `ArrowReader` wrapping DuckDB result batches).
  */
class ArrowRowsDecoderSpec extends AnyFlatSpec with Matchers:

  private val idField =
    Field.nullable("id", new ArrowType.Int(32, true))
  private val nameField =
    new Field("name", FieldType.nullable(new ArrowType.Utf8), Collections.emptyList())
  private val schema = new Schema(java.util.List.of(idField, nameField))

  /** Serializes `rows` (id, name-or-null) into an Arrow IPC stream, one row per batch write, and
    * returns the bytes. A single batch would also work, but writing per-row exercises
    * `loadNextBatch()` being called more than once, which is closer to how a real DuckDB reader
    * hands back multiple record batches.
    */
  private def serialize(rows: List[(Int, Option[String])]): Array[Byte] =
    val allocator = new RootAllocator()
    val root      = VectorSchemaRoot.create(schema, allocator)
    val out       = new ByteArrayOutputStream()
    try
      val writer = new ArrowStreamWriter(root, null, out)
      try
        writer.start()
        val idVec   = root.getVector("id").asInstanceOf[IntVector]
        val nameVec = root.getVector("name").asInstanceOf[VarCharVector]
        rows.foreach { case (id, name) =>
          root.allocateNew()
          idVec.setSafe(0, id)
          name match
            case Some(s) => nameVec.setSafe(0, s.getBytes(StandardCharsets.UTF_8))
            case None    => nameVec.setNull(0)
          root.setRowCount(1)
          writer.writeBatch()
        }
        writer.end()
      finally writer.close()
      out.toByteArray
    finally
      root.close()
      allocator.close()

  private def readerOf(bytes: Array[Byte], allocator: RootAllocator): ArrowStreamReader =
    new ArrowStreamReader(new ByteArrayInputStream(bytes), allocator)

  "decode" should "render ints, strings, and a null cell as Json" in {
    val allocator = new RootAllocator()
    try
      val bytes  = serialize(List((1, Some("region-a")), (2, None)))
      val reader = readerOf(bytes, allocator)
      try
        val (columns, rows, truncated) = ArrowRowsDecoder.decode(reader, maxRows = 100)

        columns.map(_.name) shouldBe List("id", "name")
        rows.size shouldBe 2

        rows(0)(0).asNumber.flatMap(_.toInt) shouldBe Some(1)
        rows(0)(1).asString shouldBe Some("region-a")

        rows(1)(0).asNumber.flatMap(_.toInt) shouldBe Some(2)
        rows(1)(1).isNull shouldBe true

        truncated shouldBe false
      finally reader.close()
    finally allocator.close()
  }

  it should "stop at maxRows and report truncated" in {
    val allocator = new RootAllocator()
    try
      val bytes  = serialize(List((1, Some("a")), (2, Some("b")), (3, Some("c"))))
      val reader = readerOf(bytes, allocator)
      try
        val (_, rows, truncated) = ArrowRowsDecoder.decode(reader, maxRows = 2)
        rows.size shouldBe 2
        truncated shouldBe true
      finally reader.close()
    finally allocator.close()
  }

  it should "never buffer more rows than maxRows even when more batches remain" in {
    val allocator = new RootAllocator()
    try
      // 5 single-row batches; maxRows = 2 must stop pulling further batches once the cap is hit.
      val bytes  = serialize((1 to 5).map(i => (i, Some(s"v$i"))).toList)
      val reader = readerOf(bytes, allocator)
      try
        val (_, rows, truncated) = ArrowRowsDecoder.decode(reader, maxRows = 2)
        rows.size shouldBe 2
        rows.map(_.head.asNumber.flatMap(_.toInt).get) shouldBe List(1, 2)
        truncated shouldBe true
      finally reader.close()
    finally allocator.close()
  }

  it should "return empty rows and no truncation for an empty reader" in {
    val allocator = new RootAllocator()
    try
      val bytes  = serialize(Nil)
      val reader = readerOf(bytes, allocator)
      try
        val (columns, rows, truncated) = ArrowRowsDecoder.decode(reader, maxRows = 100)
        columns.map(_.name) shouldBe List("id", "name")
        rows shouldBe Nil
        truncated shouldBe false
      finally reader.close()
    finally allocator.close()
  }
