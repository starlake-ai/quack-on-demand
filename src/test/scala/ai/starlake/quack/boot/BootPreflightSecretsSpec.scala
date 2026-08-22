package ai.starlake.quack.boot

import ai.starlake.quack.Main
import ai.starlake.quack.ManagerConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

/** Pins the boot-secret generation contract: unset session JWT secret / API key are replaced with
  * fresh random values outside HA, and left untouched under HA (where HaPreconditions refuses the
  * empty secret instead).
  */
class BootPreflightSecretsSpec extends AnyFlatSpec with Matchers with org.scalatest.OptionValues:
  import Main.given

  // Overlays pin the two fields regardless of QOD_* env vars in the invoking shell.
  private def load(overlay: String): ManagerConfig =
    ConfigSource
      .string(overlay)
      .withFallback(ConfigSource.default)
      .at("quack-on-demand")
      .loadOrThrow[ManagerConfig]

  "withGeneratedBootSecrets" should "generate both secrets when unset outside HA" in {
    val cfg = load("""quack-on-demand { apiKey = "", auth.management.sessionJwtSecret = "" }""")
    val out = BootPreflight.withGeneratedBootSecrets(cfg)
    out.auth.management.sessionJwtSecret should not be empty
    out.apiKey.value should startWith("qod_")
    // 32 random bytes: base64 secret, url-safe base64 key past the prefix.
    out.auth.management.sessionJwtSecret.length should be >= 43
    out.apiKey.value.length should be >= 43
  }

  it should "generate fresh values on every boot" in {
    val cfg = load("""quack-on-demand { apiKey = "", auth.management.sessionJwtSecret = "" }""")
    val a   = BootPreflight.withGeneratedBootSecrets(cfg)
    val b   = BootPreflight.withGeneratedBootSecrets(cfg)
    a.auth.management.sessionJwtSecret should not be b.auth.management.sessionJwtSecret
    a.apiKey should not be b.apiKey
  }

  it should "leave explicitly configured values untouched" in {
    val cfg = load(
      """quack-on-demand { apiKey = "pinned-key", auth.management.sessionJwtSecret = "pinned-secret-0123456789abcdef0123456789" }"""
    )
    BootPreflight.withGeneratedBootSecrets(cfg) shouldBe cfg
  }

  it should "generate only the missing one" in {
    val cfg = load(
      """quack-on-demand { apiKey = "pinned-key", auth.management.sessionJwtSecret = "" }"""
    )
    val out = BootPreflight.withGeneratedBootSecrets(cfg)
    out.apiKey shouldBe Some("pinned-key")
    out.auth.management.sessionJwtSecret should not be empty
  }

  it should "be a no-op under HA so HaPreconditions can refuse the empty secret" in {
    val cfg = load(
      """quack-on-demand { ha.enabled = true, apiKey = "", auth.management.sessionJwtSecret = "" }"""
    )
    BootPreflight.withGeneratedBootSecrets(cfg) shouldBe cfg
  }
