package ai.starlake.quack

import ai.starlake.quack.ondemand.api.ConfigRegistry
import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

/** Pins the quack-native block's request limits: the defaults the bundled application.conf gives,
  * the variables, the values boot refuses, and the registry rows the admin UI and the docs page are
  * built from.
  */
class QuackNativeConfigSpec extends AnyFlatSpec with Matchers:
  import Main.given

  /** The bundled application.conf under the HOCON `overlay`, the real environment left out. */
  private def loadOverlay(overlay: String): QuackNativeConfig =
    val conf = ConfigFactory
      .parseString(overlay)
      .withFallback(ConfigFactory.parseResources("application.conf"))
      .resolve(ConfigResolveOptions.noSystem())
    ConfigSource.fromConfig(conf).at("quack-native").loadOrThrow[QuackNativeConfig]

  private def load(env: Map[String, String] = Map.empty): QuackNativeConfig =
    loadOverlay(env.map((k, v) => s"$k = \"$v\"").mkString("\n"))

  "QuackNativeConfig" should "default to 64 KiB connect bodies, 1024 connections and 4 KiB headers" in {
    val cfg = load()
    cfg.maxConnectBodyBytes shouldBe 65536L
    cfg.maxConnections shouldBe 1024
    cfg.maxHeaderBytes shouldBe 4096
  }

  it should "take the header limit from QOD_QUACK_MAX_HEADER_BYTES" in {
    load(Map("QOD_QUACK_MAX_HEADER_BYTES" -> "16384")).maxHeaderBytes shouldBe 16384
    // The camelCase key of application.conf reaches the reader.
    loadOverlay("quack-native { maxHeaderBytes = 2048 }").maxHeaderBytes shouldBe 2048
  }

  it should "refuse a header limit outside 1 KiB to 1 MiB, naming its variable" in {
    List("1023", "1048577", "0", "-4096").foreach { v =>
      val e = intercept[Exception](load(Map("QOD_QUACK_MAX_HEADER_BYTES" -> v)))
      withClue(v)(e.getMessage should include("QOD_QUACK_MAX_HEADER_BYTES"))
    }
    an[Exception] should be thrownBy load(Map("QOD_QUACK_MAX_HEADER_BYTES" -> "lots"))
    load(Map("QOD_QUACK_MAX_HEADER_BYTES" -> "1024")).maxHeaderBytes shouldBe 1024
    load(Map("QOD_QUACK_MAX_HEADER_BYTES" -> "1048576")).maxHeaderBytes shouldBe 1048576
  }

  it should "register the header limit under its variable, not sensitive" in {
    val entries = ConfigRegistry.collect(List("quack-native" -> classOf[QuackNativeConfig]))
    entries
      .find(_.path == "quack-native.maxHeaderBytes")
      .map(e => (e.envVar, e.sensitive)) shouldBe Some(("QOD_QUACK_MAX_HEADER_BYTES", false))
  }

  it should "tell an operator what the header limit and the connection count cost" in {
    val entries = ConfigRegistry.collect(List("quack-native" -> classOf[QuackNativeConfig]))
    def description(key: String) = entries.find(_.path == s"quack-native.$key").map(_.description)
    description("maxHeaderBytes").get should include(
      "Largest request line, and largest header block, the Quack front door reads, in bytes each."
    )
    description("maxHeaderBytes").get should include(
      "Raising it lowers how many connections fit the heap."
    )
    description("maxConnections").get should include(
      "Fewer when a fifth of the heap cannot hold that many: boot says so."
    )
  }
