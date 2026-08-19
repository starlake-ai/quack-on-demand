package ai.starlake.quack.ondemand.api

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class StarlakeNotifierSpec extends AnyFlatSpec with Matchers:

  "sha256Hex" should "produce the expected digest" in {
    StarlakeNotifier.sha256Hex("abc") shouldBe
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
  }

  "HttpStarlakeNotifier" should "post the digest to the backchannel endpoint" in {
    var seen: (String, String) = null
    val notifier = new HttpStarlakeNotifier(
      "https://starlake.example.com",
      post = (url, body) => { seen = (url, body); Right(200) }
    )
    notifier.notifyLogout("deadbeef") shouldBe Right(())
    seen._1 shouldBe "https://starlake.example.com/api/v1/auth/qod/backchannel-logout"
    seen._2 shouldBe """{"tokenSha256":"deadbeef"}"""
  }

  it should "report transport failures without throwing" in {
    val notifier = new HttpStarlakeNotifier("https://x", post = (_, _) => Left("boom"))
    notifier.notifyLogout("d").isLeft shouldBe true
  }

  it should "treat non-2xx as failure" in {
    val notifier = new HttpStarlakeNotifier("https://x", post = (_, _) => Right(503))
    notifier.notifyLogout("d").isLeft shouldBe true
  }
