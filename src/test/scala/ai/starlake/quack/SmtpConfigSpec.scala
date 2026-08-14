package ai.starlake.quack

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

/** Pins defaults and the camelCase ProductHint for the smtp block. */
class SmtpConfigSpec extends AnyFlatSpec with Matchers:
  import Main.given

  "SmtpConfig" should "load defaults from application.conf" in:
    val cfg = ConfigSource.default.at("quack-on-demand").loadOrThrow[ManagerConfig]
    cfg.smtp.host shouldBe None
    cfg.smtp.port shouldBe 587
    cfg.smtp.user shouldBe None
    cfg.smtp.password shouldBe None
    cfg.smtp.from shouldBe "no-reply@quack-on-demand.local"
    cfg.smtp.starttls shouldBe true

  it should "honor camelCase overlays" in:
    val cfg = ConfigSource
      .string(
        """quack-on-demand { smtp { host = "smtp.example.com", port = 2525, starttls = false } }"""
      )
      .withFallback(ConfigSource.default)
      .at("quack-on-demand")
      .loadOrThrow[ManagerConfig]
    cfg.smtp.host shouldBe Some("smtp.example.com")
    cfg.smtp.port shouldBe 2525
    cfg.smtp.starttls shouldBe false
