package ai.starlake.quack.ondemand

import ai.starlake.quack.model.TenantDb
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EncryptionKeyGenSpec extends AnyFlatSpec with Matchers:

  "mint" should "produce a 44-character base64 encoding of 32 bytes" in {
    val key = EncryptionKeyGen.mint()
    key.length shouldBe 44
    java.util.Base64.getDecoder.decode(key).length shouldBe 32
  }

  it should "produce a different key every call" in {
    val keys = (1 to 50).map(_ => EncryptionKeyGen.mint()).toSet
    keys.size shouldBe 50
  }

  it should "never emit a character the spawn scripts would choke on" in {
    val forbidden = Set('\'', ';', '\\', '\n', '\r')
    (1 to 200).foreach { _ =>
      EncryptionKeyGen.mint().find(forbidden.contains) shouldBe None
    }
  }
