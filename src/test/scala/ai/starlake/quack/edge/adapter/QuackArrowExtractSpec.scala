package ai.starlake.quack.edge.adapter

import org.apache.arrow.memory.RootAllocator
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

/** End-to-end round-trip for the Arrow C-data pipeline:
  *
  *   1. C++ fixture serializes a sample FETCH_RESPONSE (or
  *      PREPARE_RESPONSE) containing one `INTEGER` column with the
  *      value `42`.
  *   2. `QuackNativeBridge.extractArrowStream` decodes the message and
  *      hands us an `ArrowArrayStream*` cast to a `Long`.
  *   3. `QuackArrowImport.importStream` wraps the pointer as an
  *      `ArrowReader` via Arrow Java's C-data importer.
  *   4. We drain the reader and assert schema + row count + value.
  *
  * Successful close of the reader also exercises the native
  * `chunk_stream_release` callback path — leaks here would show up as
  * RootAllocator's outstanding-allocation assert when the reader closes.
  */
class QuackArrowExtractSpec extends AnyFunSpec with Matchers:

  describe("extractArrowStream"):
    it("extracts an Arrow stream from a FETCH_RESPONSE with one INTEGER row of 42") {
      val bytes = QuackTestFixtures.serializeSampleFetchResponse(withOneRowOneColChunk = true)
      val ptr   = QuackNativeBridge.extractArrowStream(bytes)
      ptr should not be 0L

      val allocator = new RootAllocator()
      try
        val reader = QuackArrowImport.importStream(allocator, ptr)
        try
          // Drain.
          reader.loadNextBatch() shouldBe true
          val root = reader.getVectorSchemaRoot
          root.getSchema.getFields.size() shouldBe 1
          // DuckDB INTEGER (int32) -> Arrow IntVector with field type Int(32,true).
          val field = root.getSchema.getFields.get(0)
          field.getType.toString.toLowerCase should include ("int")
          root.getRowCount shouldBe 1
          val vec = root.getVector(0)
          vec.getObject(0).toString shouldBe "42"
          // No more batches.
          reader.loadNextBatch() shouldBe false
        finally reader.close()
      finally allocator.close()
    }

    it("extracts an Arrow stream from a PREPARE_RESPONSE with one INTEGER row of 42") {
      val uuid  = new java.math.BigInteger("0123456789ABCDEF0123456789ABCDEF", 16)
      val bytes = QuackTestFixtures.serializeSamplePrepareResponse(
        resultUuid = uuid,
        needsMoreFetch = false,
        withOneRowOneColChunk = true
      )
      val ptr = QuackNativeBridge.extractArrowStream(bytes)
      ptr should not be 0L

      val allocator = new RootAllocator()
      try
        val reader = QuackArrowImport.importStream(allocator, ptr)
        try
          reader.loadNextBatch() shouldBe true
          reader.getVectorSchemaRoot.getRowCount shouldBe 1
          reader.getVectorSchemaRoot.getVector(0).getObject(0).toString shouldBe "42"
          reader.loadNextBatch() shouldBe false
        finally reader.close()
      finally allocator.close()
    }

    it("raises RuntimeException for a non-response message type") {
      val bytes = QuackNativeBridge.serializeConnectionRequest("any-token")
      val ex = intercept[RuntimeException] {
        QuackNativeBridge.extractArrowStream(bytes)
      }
      ex.getMessage should include ("PREPARE_RESPONSE or FETCH_RESPONSE")
    }

    it("raises RuntimeException on garbage bytes") {
      val ex = intercept[RuntimeException] {
        QuackNativeBridge.extractArrowStream(Array[Byte](0, 1, 2, 3))
      }
      ex.getMessage should not be empty
    }