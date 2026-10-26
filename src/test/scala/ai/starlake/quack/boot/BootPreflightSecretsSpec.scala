package ai.starlake.quack.boot

import ai.starlake.quack.Main
import ai.starlake.quack.ManagerConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets

/** Pins the boot-secret generation contract: an unset session JWT secret is replaced with a fresh
  * random value outside HA and left untouched under HA (where HaPreconditions refuses the empty
  * secret instead). No API key is generated, and the banner prints only the session secret.
  *
  * Assertions compare as booleans: a failure must not print a generated secret or a config dump.
  */
class BootPreflightSecretsSpec extends AnyFlatSpec with Matchers:
  import Main.given

  // Overlays pin the secret regardless of QOD_* env vars in the invoking shell.
  private def load(overlay: String): ManagerConfig =
    ConfigSource
      .string(overlay)
      .withFallback(ConfigSource.default)
      .at("quack-on-demand")
      .loadOrThrow[ManagerConfig]

  /** `withGeneratedBootSecrets` with stdout captured: (result, banner). */
  private def generate(cfg: ManagerConfig): (ManagerConfig, String) =
    val out    = new ByteArrayOutputStream()
    val result = Console.withOut(new PrintStream(out, true, StandardCharsets.UTF_8)) {
      BootPreflight.withGeneratedBootSecrets(cfg)
    }
    (result, out.toString(StandardCharsets.UTF_8))

  private val unset = """quack-on-demand { auth.management.sessionJwtSecret = "" }"""

  "withGeneratedBootSecrets" should "generate the session secret when unset outside HA" in {
    val (out, _) = generate(load(unset))
    // 32 random bytes, base64.
    out.auth.management.sessionJwtSecret.length should be >= 43
  }

  it should "print only the session secret in its banner, never an API key" in {
    val (_, banner) = generate(load(unset))
    banner.contains("QOD_SESSION_JWT_SECRET=") shouldBe true
    banner.contains("QOD_API_KEY") shouldBe false
    banner.contains("API-key") shouldBe false
  }

  it should "generate a fresh secret on every boot" in {
    val cfg    = load(unset)
    val (a, _) = generate(cfg)
    val (b, _) = generate(cfg)
    (a.auth.management.sessionJwtSecret == b.auth.management.sessionJwtSecret) shouldBe false
  }

  it should "leave a configured secret untouched and print nothing" in {
    val cfg = load(
      """quack-on-demand { auth.management.sessionJwtSecret = "pinned-secret-0123456789abcdef0123456789" }"""
    )
    val (out, banner) = generate(cfg)
    (out == cfg) shouldBe true
    banner.isEmpty shouldBe true
  }

  it should "be a no-op under HA so HaPreconditions can refuse the empty secret" in {
    val cfg =
      load("""quack-on-demand { ha.enabled = true, auth.management.sessionJwtSecret = "" }""")
    val (out, banner) = generate(cfg)
    (out == cfg) shouldBe true
    banner.isEmpty shouldBe true
  }
