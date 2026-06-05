// src/test/scala/ai/starlake/quack/ondemand/manifest/BcryptUtilsSpec.scala
package ai.starlake.quack.ondemand.manifest

import at.favre.lib.crypto.bcrypt.BCrypt
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BcryptUtilsSpec extends AnyFlatSpec with Matchers:

  "BcryptUtils.toHash" should "return a bcrypt hash unchanged" in {
    val sample = BCrypt.withDefaults().hashToString(12, "pw".toCharArray)
    BcryptUtils.toHash(sample) shouldBe sample
  }

  it should "hash a plaintext password" in {
    val out = BcryptUtils.toHash("hunter2")
    out should startWith ("$2")
    BCrypt.verifyer().verify("hunter2".toCharArray, out).verified shouldBe true
  }

  it should "accept every bcrypt variant prefix" in {
    Seq("2a", "2b", "2x", "2y").foreach { v =>
      val candidate = s"$$$v$$12$$" + "a" * 53
      BcryptUtils.isBcrypt(candidate) shouldBe true
    }
  }

  it should "reject close-but-not bcrypt strings" in {
    BcryptUtils.isBcrypt("$2z$12$" + "a" * 53)            shouldBe false
    BcryptUtils.isBcrypt("$2a$12$short")                  shouldBe false
    BcryptUtils.isBcrypt("plaintext")                     shouldBe false
    BcryptUtils.isBcrypt("")                              shouldBe false
  }