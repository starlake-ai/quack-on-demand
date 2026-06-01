package ai.starlake.quack.edge.adapter

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

/** Decode-side tests for `QuackNativeBridge`.
  *
  * Task 3 only added encode entries for request-side types
  * (CONNECTION_REQUEST, PREPARE_REQUEST, FETCH_REQUEST, DISCONNECT). We
  * therefore cannot construct response bytes (CONNECTION_RESPONSE,
  * PREPARE_RESPONSE, FETCH_RESPONSE, ERROR_RESPONSE) from Scala without a
  * C++ test fixture helper. That helper is Task 5/6 territory, so this
  * spec covers:
  *   - `parseMessageType` round-trips for each of the four request types
  *   - structural failure modes (garbage bytes; wrong type to a typed
  *     extractor) — verifying that callers get a clean `RuntimeException`
  *     rather than UB
  *
  * Happy-path tests for `extractConnectionId`, `extractErrorMessage`,
  * `needsMoreFetch`, and `extractResultUuid` are deferred until Task 5
  * lands a C++ fixture helper that constructs and serializes the
  * response-side messages.
  */
class QuackDecodeSpec extends AnyFunSpec with Matchers:

  describe("parseMessageType"):
    it("returns CONNECTION_REQUEST's wire ordinal for serialized ConnectionRequest bytes") {
      val bytes = QuackNativeBridge.serializeConnectionRequest("any-token")
      QuackNativeBridge.parseMessageType(bytes) shouldBe MessageType.ConnectionRequest.wireOrdinal
    }

    it("returns PREPARE_REQUEST's wire ordinal for serialized PrepareRequest bytes") {
      val bytes = QuackNativeBridge.serializePrepareRequest("conn-1", "SELECT 1")
      QuackNativeBridge.parseMessageType(bytes) shouldBe MessageType.PrepareRequest.wireOrdinal
    }

    it("returns FETCH_REQUEST's wire ordinal for serialized FetchRequest bytes") {
      val uuid  = new java.math.BigInteger("0123456789ABCDEF0123456789ABCDEF", 16)
      val bytes = QuackNativeBridge.serializeFetchRequest("conn-1", uuid)
      QuackNativeBridge.parseMessageType(bytes) shouldBe MessageType.FetchRequest.wireOrdinal
    }

    it("returns DISCONNECT_MESSAGE's wire ordinal for serialized Disconnect bytes") {
      val bytes = QuackNativeBridge.serializeDisconnect("conn-1")
      QuackNativeBridge.parseMessageType(bytes) shouldBe MessageType.DisconnectMessage.wireOrdinal
    }

    it("raises RuntimeException on garbage bytes") {
      val garbage = Array[Byte](0, 1, 2, 3)
      val ex = intercept[RuntimeException] {
        QuackNativeBridge.parseMessageType(garbage)
      }
      ex.getMessage should not be empty
    }

  describe("extractConnectionId"):
    it("raises RuntimeException when called with non-CONNECTION_RESPONSE bytes") {
      val bytes = QuackNativeBridge.serializeConnectionRequest("any-token")
      val ex = intercept[RuntimeException] {
        QuackNativeBridge.extractConnectionId(bytes)
      }
      ex.getMessage should include ("CONNECTION_RESPONSE")
    }
    it("returns the connection id round-tripped through a CONNECTION_RESPONSE fixture") {
      val bytes = QuackTestFixtures.serializeSampleConnectionResponse("conn-XYZ")
      QuackNativeBridge.extractConnectionId(bytes) shouldBe "conn-XYZ"
    }

  describe("extractErrorMessage"):
    it("raises RuntimeException when called with non-ERROR_RESPONSE bytes") {
      val bytes = QuackNativeBridge.serializeConnectionRequest("any-token")
      val ex = intercept[RuntimeException] {
        QuackNativeBridge.extractErrorMessage(bytes)
      }
      ex.getMessage should include ("ERROR_RESPONSE")
    }
    it("returns the error string round-tripped through an ERROR_RESPONSE fixture") {
      val bytes = QuackTestFixtures.serializeSampleErrorResponse("boom")
      // ErrorResponse(const string&) constructs an ErrorData with type
      // INVALID_INPUT, which DuckDB's ErrorData::Message() prefixes with
      // "Invalid Input Error: ". Asserting `endsWith` rather than full
      // equality keeps this resilient to upstream tweaks to the prefix
      // format while still proving the payload survived the round-trip.
      val msg = QuackNativeBridge.extractErrorMessage(bytes)
      msg should endWith ("boom")
      msg should include ("Invalid Input")
    }

  describe("needsMoreFetch"):
    it("raises RuntimeException when called with non-PREPARE_RESPONSE bytes") {
      val bytes = QuackNativeBridge.serializeConnectionRequest("any-token")
      val ex = intercept[RuntimeException] {
        QuackNativeBridge.needsMoreFetch(bytes)
      }
      ex.getMessage should include ("PREPARE_RESPONSE")
    }
    it("returns true when the PREPARE_RESPONSE fixture sets needsMoreFetch=true") {
      val uuid = java.math.BigInteger.ZERO
      val bytes = QuackTestFixtures.serializeSamplePrepareResponse(
        resultUuid = uuid,
        needsMoreFetch = true,
        withOneRowOneColChunk = false
      )
      QuackNativeBridge.needsMoreFetch(bytes) shouldBe true
    }
    it("returns false when the PREPARE_RESPONSE fixture sets needsMoreFetch=false") {
      val uuid = java.math.BigInteger.ZERO
      val bytes = QuackTestFixtures.serializeSamplePrepareResponse(
        resultUuid = uuid,
        needsMoreFetch = false,
        withOneRowOneColChunk = false
      )
      QuackNativeBridge.needsMoreFetch(bytes) shouldBe false
    }

  describe("extractResultUuid"):
    it("raises RuntimeException when called with non-PREPARE_RESPONSE bytes") {
      val bytes = QuackNativeBridge.serializeConnectionRequest("any-token")
      val ex = intercept[RuntimeException] {
        QuackNativeBridge.extractResultUuid(bytes)
      }
      ex.getMessage should include ("PREPARE_RESPONSE")
    }
    it("returns the same BigInteger that was supplied to the PREPARE_RESPONSE fixture") {
      val uuid = new java.math.BigInteger("0123456789ABCDEF0123456789ABCDEF", 16)
      val bytes = QuackTestFixtures.serializeSamplePrepareResponse(
        resultUuid = uuid,
        needsMoreFetch = false,
        withOneRowOneColChunk = false
      )
      QuackNativeBridge.extractResultUuid(bytes) shouldBe uuid
    }

  describe("fetchResponseChunkCount"):
    it("returns > 0 for a FETCH_RESPONSE carrying a chunk") {
      val bytes = QuackTestFixtures.serializeSampleFetchResponse(withOneRowOneColChunk = true)
      QuackNativeBridge.fetchResponseChunkCount(bytes) should be > 0
    }
    it("returns 0 for a FETCH_RESPONSE with no chunks (end-of-stream signal)") {
      val bytes = QuackTestFixtures.serializeSampleFetchResponse(withOneRowOneColChunk = false)
      QuackNativeBridge.fetchResponseChunkCount(bytes) shouldBe 0
    }
    it("raises RuntimeException when called with non-FETCH_RESPONSE bytes") {
      val bytes = QuackNativeBridge.serializeConnectionRequest("any-token")
      val ex = intercept[RuntimeException] {
        QuackNativeBridge.fetchResponseChunkCount(bytes)
      }
      ex.getMessage should include ("FETCH_RESPONSE")
    }

  describe("extractColumnNames"):
    it("returns the column names from a PREPARE_RESPONSE fixture") {
      val uuid  = java.math.BigInteger.ZERO
      val bytes = QuackTestFixtures.serializeSamplePrepareResponse(
        resultUuid = uuid,
        needsMoreFetch = false,
        withOneRowOneColChunk = false,
        columnName = "answer"
      )
      val names = QuackNativeBridge.extractColumnNames(bytes)
      names.toList shouldBe List("answer")
    }
    it("raises RuntimeException when called with non-PREPARE_RESPONSE bytes") {
      val bytes = QuackNativeBridge.serializeConnectionRequest("any-token")
      val ex = intercept[RuntimeException] {
        QuackNativeBridge.extractColumnNames(bytes)
      }
      ex.getMessage should include ("PREPARE_RESPONSE")
    }

  describe("MessageType wire ordinals"):
    it("matches the duckdb-quack MessageType enum exactly") {
      MessageType.Invalid.wireOrdinal            shouldBe 0
      MessageType.ConnectionRequest.wireOrdinal  shouldBe 1
      MessageType.ConnectionResponse.wireOrdinal shouldBe 2
      MessageType.PrepareRequest.wireOrdinal     shouldBe 3
      MessageType.PrepareResponse.wireOrdinal    shouldBe 4
      MessageType.FetchRequest.wireOrdinal       shouldBe 7
      MessageType.FetchResponse.wireOrdinal      shouldBe 8
      MessageType.AppendRequest.wireOrdinal      shouldBe 9
      MessageType.SuccessResponse.wireOrdinal    shouldBe 10
      MessageType.DisconnectMessage.wireOrdinal  shouldBe 11
      MessageType.ErrorResponse.wireOrdinal      shouldBe 100
    }