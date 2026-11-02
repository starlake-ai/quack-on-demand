package ai.starlake.quack

import ai.starlake.quack.ondemand.api.ConfigRegistry
import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

/** Pins the restLimits block: the defaults the bundled application.conf gives, the five variables,
  * the values boot refuses, and the registry rows the admin UI and the docs page are built from.
  */
class RestLimitsConfigSpec extends AnyFlatSpec with Matchers:
  import Main.given

  /** The bundled application.conf under the HOCON `overlay`, the real environment left out. */
  private def loadOverlay(overlay: String): ManagerConfig =
    val conf = ConfigFactory
      .parseString(overlay)
      .withFallback(ConfigFactory.parseResources("application.conf"))
      .resolve(ConfigResolveOptions.noSystem())
    ConfigSource.fromConfig(conf).at("quack-on-demand").loadOrThrow[ManagerConfig]

  private def load(env: Map[String, String] = Map.empty): ManagerConfig =
    loadOverlay(env.map((k, v) => s"$k = \"$v\"").mkString("\n"))

  "RestLimitsConfig" should "default to 16 MiB, 64 KiB, 30 s, 1024 connections and 8 KiB headers" in {
    RestLimitsConfig() shouldBe RestLimitsConfig(16777216L, 65536L, 30L, 1024, 8192)
    load().restLimits shouldBe RestLimitsConfig()
    load().restLimits.maxHeaderBytes shouldBe 8192
  }

  it should "take each limit from its variable" in {
    load(Map("QOD_REST_MAX_BODY_BYTES" -> "1048576")).restLimits shouldBe
      RestLimitsConfig(maxBodyBytes = 1048576L)
    load(Map("QOD_REST_MAX_PUBLIC_BODY_BYTES" -> "4096")).restLimits shouldBe
      RestLimitsConfig(maxPublicBodyBytes = 4096L)
    load(Map("QOD_REST_BODY_TIMEOUT_SEC" -> "7")).restLimits shouldBe
      RestLimitsConfig(bodyTimeoutSec = 7L)
    load(Map("QOD_REST_MAX_CONNECTIONS" -> "64")).restLimits shouldBe
      RestLimitsConfig(maxConnections = 64)
    load(Map("QOD_REST_MAX_HEADER_BYTES" -> "16384")).restLimits shouldBe
      RestLimitsConfig(maxHeaderBytes = 16384)
    // The camelCase keys of application.conf reach the reader (a missing ProductHint would leave
    // every value at its default and ignore the block).
    loadOverlay("quack-on-demand.restLimits { bodyTimeoutSec = 9 }").restLimits shouldBe
      RestLimitsConfig(bodyTimeoutSec = 9L)
  }

  it should "refuse a value the listener cannot honour" in {
    List(
      Map("QOD_REST_MAX_BODY_BYTES"        -> "0"),
      Map("QOD_REST_MAX_BODY_BYTES"        -> (Int.MaxValue - 7L).toString),
      Map("QOD_REST_MAX_PUBLIC_BODY_BYTES" -> "-1"),
      Map("QOD_REST_MAX_PUBLIC_BODY_BYTES" -> (Int.MaxValue - 7L).toString),
      Map("QOD_REST_BODY_TIMEOUT_SEC"      -> "0"),
      Map("QOD_REST_BODY_TIMEOUT_SEC"      -> "86401"),
      Map("QOD_REST_MAX_CONNECTIONS"       -> "0"),
      Map("QOD_REST_MAX_CONNECTIONS"       -> "lots"),
      Map("QOD_REST_MAX_HEADER_BYTES"      -> "1023"),
      Map("QOD_REST_MAX_HEADER_BYTES"      -> "1048577"),
      Map("QOD_REST_MAX_HEADER_BYTES"      -> "0"),
      Map("QOD_REST_MAX_HEADER_BYTES"      -> "-8192")
    ).foreach(env => withClue(env)(an[Exception] should be thrownBy load(env)))
    load(
      Map("QOD_REST_MAX_BODY_BYTES" -> (Int.MaxValue - 8L).toString)
    ).restLimits.maxBodyBytes shouldBe
      Int.MaxValue - 8L
    load(Map("QOD_REST_MAX_PUBLIC_BODY_BYTES" -> "1")).restLimits.maxPublicBodyBytes shouldBe 1L
    load(Map("QOD_REST_BODY_TIMEOUT_SEC" -> "86400")).restLimits.bodyTimeoutSec shouldBe 86400L
  }

  it should "name QOD_REST_MAX_HEADER_BYTES when it refuses a header limit, and keep 1 KiB to 1 MiB" in {
    List("1023", "1048577").foreach { v =>
      val e = intercept[Exception](load(Map("QOD_REST_MAX_HEADER_BYTES" -> v)))
      withClue(v)(e.getMessage should include("QOD_REST_MAX_HEADER_BYTES"))
    }
    load(Map("QOD_REST_MAX_HEADER_BYTES" -> "1024")).restLimits.maxHeaderBytes shouldBe 1024
    load(Map("QOD_REST_MAX_HEADER_BYTES" -> "1048576")).restLimits.maxHeaderBytes shouldBe 1048576
  }

  it should "register each limit under its variable, none sensitive" in {
    val entries = ConfigRegistry.collect(List("quack-on-demand" -> classOf[ManagerConfig]))
    List(
      "maxBodyBytes"       -> "QOD_REST_MAX_BODY_BYTES",
      "maxPublicBodyBytes" -> "QOD_REST_MAX_PUBLIC_BODY_BYTES",
      "bodyTimeoutSec"     -> "QOD_REST_BODY_TIMEOUT_SEC",
      "maxConnections"     -> "QOD_REST_MAX_CONNECTIONS",
      "maxHeaderBytes"     -> "QOD_REST_MAX_HEADER_BYTES"
    ).foreach { case (key, envVar) =>
      entries
        .find(_.path == s"quack-on-demand.restLimits.$key")
        .map(e => (e.envVar, e.sensitive)) shouldBe Some((envVar, false))
    }
  }

  it should "tell an operator what the header limit and the connection count cost" in {
    val entries = ConfigRegistry.collect(List("quack-on-demand" -> classOf[ManagerConfig]))
    def description(key: String) =
      entries.find(_.path == s"quack-on-demand.restLimits.$key").map(_.description)
    description("maxHeaderBytes").get should include(
      "Largest request line, and largest header block, the REST port reads, in bytes each."
    )
    description("maxHeaderBytes").get should include(
      "Raising it lowers how many connections fit the heap."
    )
    description("maxConnections").get should include(
      "Fewer when a fifth of the heap cannot hold that many: boot says so."
    )
  }
