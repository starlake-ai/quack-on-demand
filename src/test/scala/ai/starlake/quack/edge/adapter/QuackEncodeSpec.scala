package ai.starlake.quack.edge.adapter

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class QuackEncodeSpec extends AnyFunSpec with Matchers:

  describe("serializeConnectionRequest"):
    it("returns non-empty, deterministic bytes for the same token") {
      val a = QuackNativeBridge.serializeConnectionRequest("token-1")
      val b = QuackNativeBridge.serializeConnectionRequest("token-1")
      a should not be empty
      a shouldBe b
    }
    it("produces distinct bytes for distinct tokens") {
      val a = QuackNativeBridge.serializeConnectionRequest("token-1")
      val c = QuackNativeBridge.serializeConnectionRequest("token-2")
      a should not equal c
    }

  describe("serializePrepareRequest"):
    it("returns non-empty, deterministic bytes for the same args") {
      val a = QuackNativeBridge.serializePrepareRequest("conn-1", "SELECT 1")
      val b = QuackNativeBridge.serializePrepareRequest("conn-1", "SELECT 1")
      a should not be empty
      a shouldBe b
    }
    it("produces distinct bytes for distinct sql") {
      val a = QuackNativeBridge.serializePrepareRequest("conn-1", "SELECT 1")
      val c = QuackNativeBridge.serializePrepareRequest("conn-1", "SELECT 2")
      a should not equal c
    }
    it("produces distinct bytes for distinct connection ids") {
      val a = QuackNativeBridge.serializePrepareRequest("conn-1", "SELECT 1")
      val c = QuackNativeBridge.serializePrepareRequest("conn-2", "SELECT 1")
      a should not equal c
    }

  describe("serializeFetchRequest"):
    val uuid1 = new java.math.BigInteger("0123456789ABCDEF0123456789ABCDEF", 16)
    val uuid2 = new java.math.BigInteger("FEDCBA9876543210FEDCBA9876543210", 16)

    it("returns non-empty, deterministic bytes for the same args") {
      val a = QuackNativeBridge.serializeFetchRequest("conn-1", uuid1)
      val b = QuackNativeBridge.serializeFetchRequest("conn-1", uuid1)
      a should not be empty
      a shouldBe b
    }
    it("produces distinct bytes for distinct uuids") {
      val a = QuackNativeBridge.serializeFetchRequest("conn-1", uuid1)
      val c = QuackNativeBridge.serializeFetchRequest("conn-1", uuid2)
      a should not equal c
    }
    it("produces distinct bytes for distinct connection ids") {
      val a = QuackNativeBridge.serializeFetchRequest("conn-1", uuid1)
      val c = QuackNativeBridge.serializeFetchRequest("conn-2", uuid1)
      a should not equal c
    }

  describe("serializeDisconnect"):
    it("returns non-empty, deterministic bytes for the same connection id") {
      val a = QuackNativeBridge.serializeDisconnect("conn-1")
      val b = QuackNativeBridge.serializeDisconnect("conn-1")
      a should not be empty
      a shouldBe b
    }
    it("produces distinct bytes for distinct connection ids") {
      val a = QuackNativeBridge.serializeDisconnect("conn-1")
      val c = QuackNativeBridge.serializeDisconnect("conn-2")
      a should not equal c
    }

  describe("hugeint conversion edge cases (FetchRequest)"):
    val uuid1 = new java.math.BigInteger("0123456789ABCDEF0123456789ABCDEF", 16)

    it("accepts an exactly-16-byte BigInteger (no leading-zero suppression)") {
      // 0x80...00 has the high bit set so BigInteger uses all 16 bytes
      // with no sign-byte prefix.
      val exact16 = new java.math.BigInteger(1, Array.fill[Byte](16)(0.toByte).updated(0, 0x80.toByte))
      exact16.toByteArray.length shouldBe 17 // Java prepends a 0x00 sign byte
      val a = QuackNativeBridge.serializeFetchRequest("conn-1", exact16)
      val b = QuackNativeBridge.serializeFetchRequest("conn-1", exact16)
      a should not be empty
      a shouldBe b
    }

    it("accepts a negative BigInteger uuid (sign-extended bytes)") {
      val neg = uuid1.negate()
      val a = QuackNativeBridge.serializeFetchRequest("conn-1", neg)
      val b = QuackNativeBridge.serializeFetchRequest("conn-1", neg)
      a should not be empty
      a shouldBe b
      a should not equal QuackNativeBridge.serializeFetchRequest("conn-1", uuid1)
    }

    it("rejects a BigInteger that does not fit in 128 bits") {
      // 17 magnitude bytes + 1 sign byte = 18 bytes total -- over the limit.
      val tooBig = java.math.BigInteger.ONE.shiftLeft(128) // exactly 129 bits set
      val ex = intercept[RuntimeException] {
        QuackNativeBridge.serializeFetchRequest("conn-1", tooBig)
      }
      ex.getMessage should include ("does not fit")
    }

    it("rejects a null resultUuid") {
      val ex = intercept[RuntimeException] {
        QuackNativeBridge.serializeFetchRequest("conn-1", null)
      }
      ex.getMessage should include ("null")
    }

  describe("empty-string arguments"):
    it("serializeConnectionRequest accepts an empty token") {
      QuackNativeBridge.serializeConnectionRequest("") should not be empty
    }
    it("serializePrepareRequest accepts an empty connection id and empty sql") {
      QuackNativeBridge.serializePrepareRequest("", "") should not be empty
    }
    it("serializeDisconnect accepts an empty connection id") {
      QuackNativeBridge.serializeDisconnect("") should not be empty
    }