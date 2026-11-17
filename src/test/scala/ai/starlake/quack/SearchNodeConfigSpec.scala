package ai.starlake.quack

import ai.starlake.quack.ondemand.api.ConfigRegistry
import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import java.nio.file.{Files, Paths}

/** Pins the search block (QoD Search Phase 1, Task 16): the node's search knobs, their defaults and
  * ranges, the variable each is read from (qod-node reads the same names), the refusal at config
  * load outside a range, the spawn environment that always names all nine, and the ranges qod-node
  * itself applies (native/qod-node/src/search_knobs.hpp), so both sides refuse the same values.
  */
class SearchNodeConfigSpec extends AnyFlatSpec with Matchers:
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

  private val MiB = 1L << 20
  private val GiB = 1L << 30
  private val TiB = 1L << 40

  /** (config key, variable, least value, greatest value, 0 also allowed below the least). */
  private val ranges = List(
    ("maxUnindexedRows", "QOD_SEARCH_MAX_UNINDEXED_ROWS", 0L, 100000000L, false),
    ("maxUnindexedSources", "QOD_SEARCH_MAX_UNINDEXED_SOURCES", 1L, 4096L, false),
    ("queryMemoryBytes", "QOD_SEARCH_QUERY_MEMORY", 64 * MiB, TiB, true),
    ("maxK", "QOD_SEARCH_MAX_K", 1L, 10000L, false),
    ("livenessCacheEntries", "QOD_SEARCH_LIVENESS_CACHE_ENTRIES", 0L, 100000L, false),
    ("livenessCacheBytes", "QOD_SEARCH_LIVENESS_CACHE_BYTES", 0L, TiB, false),
    ("segmentCacheBytes", "QOD_SEARCH_SEGMENT_CACHE_BYTES", 0L, TiB, false),
    ("missTtlSec", "QOD_SEARCH_MISS_TTL_SEC", 0L, 60L, false),
    ("maxBundleBytes", "QOD_SEARCH_MAX_BUNDLE_BYTES", MiB, 64 * GiB, false)
  )

  "SearchNodeConfig" should "default to qod-node's own limits" in {
    SearchNodeConfig() shouldBe SearchNodeConfig(
      maxUnindexedRows = 200000,
      maxUnindexedSources = 64,
      queryMemoryBytes = 0,
      maxK = 10000,
      livenessCacheEntries = 64,
      livenessCacheBytes = 256 * MiB,
      segmentCacheBytes = 4 * GiB,
      missTtlSec = 60,
      maxBundleBytes = GiB
    )
    load().search shouldBe SearchNodeConfig()
    SearchNodeConfig.refusal(SearchNodeConfig()) shouldBe None
  }

  it should "take each knob from the variable qod-node reads" in {
    val values = List(1234L, 7L, 128 * MiB, 50L, 3L, 5 * MiB, 6 * GiB, 30L, 2 * MiB)
    ranges.zip(values).foreach { case ((key, env, _, _, _), v) =>
      withClue(env) {
        SearchNodeConfig.nodeEnv(load(Map(env -> v.toString)).search)(env) shouldBe v.toString
      }
    }
    loadOverlay("quack-on-demand.search { maxK = 500 }").search shouldBe SearchNodeConfig(maxK =
      500
    )
  }

  it should "refuse, at config load, a value outside each range, naming the key and the variable" in {
    ranges.foreach { case (key, env, least, greatest, orZero) =>
      val below = if orZero then List(1L, least - 1) else if least > 0 then List(least - 1) else Nil
      (below ++ List(greatest + 1)).foreach { v =>
        withClue(s"$env=$v: ") {
          val e = intercept[Exception](load(Map(env -> v.toString)))
          e.getMessage should include(s"quack-on-demand.search.$key ($env)")
        }
      }
      // Both bounds are accepted.
      (List(least, greatest) ++ Option.when(orZero)(0L)).foreach { v =>
        withClue(s"$env=$v: ") {
          SearchNodeConfig.nodeEnv(load(Map(env -> v.toString)).search)(env) shouldBe v.toString
        }
      }
    }
    an[Exception] should be thrownBy load(Map("QOD_SEARCH_MAX_K" -> "lots"))
    an[Exception] should be thrownBy load(Map("QOD_SEARCH_MAX_UNINDEXED_ROWS" -> "-1"))
  }

  it should "name every refused knob in one refusal" in {
    val refusal = SearchNodeConfig.refusal(SearchNodeConfig(maxK = 0, missTtlSec = 61))
    refusal.map(
      _.contains("quack-on-demand.search.maxK (QOD_SEARCH_MAX_K) must be 1 to 10000, got 0")
    ) shouldBe
      Some(true)
    refusal.map(
      _.contains(
        "quack-on-demand.search.missTtlSec (QOD_SEARCH_MISS_TTL_SEC) must be 0 to 60, got 61"
      )
    ) shouldBe Some(true)
    SearchNodeConfig
      .refusal(SearchNodeConfig(queryMemoryBytes = 1))
      .map(_.contains("must be 0 or 67108864 to 1099511627776, got 1")) shouldBe Some(true)
  }

  it should "register each knob under its variable, none sensitive" in {
    val entries = ConfigRegistry.collect(List("quack-on-demand" -> classOf[ManagerConfig]))
    ranges.map { case (key, _, _, _, _) =>
      entries.find(_.path == s"quack-on-demand.search.$key").map(e => (e.envVar, e.sensitive))
    } shouldBe ranges.map { case (_, env, _, _, _) => Some((env, false)) }
  }

  "nodeEnv" should "always name all nine, as decimal text" in {
    SearchNodeConfig.nodeEnv(SearchNodeConfig()) shouldBe Map(
      "QOD_SEARCH_MAX_UNINDEXED_ROWS"     -> "200000",
      "QOD_SEARCH_MAX_UNINDEXED_SOURCES"  -> "64",
      "QOD_SEARCH_QUERY_MEMORY"           -> "0",
      "QOD_SEARCH_MAX_K"                  -> "10000",
      "QOD_SEARCH_LIVENESS_CACHE_ENTRIES" -> "64",
      "QOD_SEARCH_LIVENESS_CACHE_BYTES"   -> "268435456",
      "QOD_SEARCH_SEGMENT_CACHE_BYTES"    -> "4294967296",
      "QOD_SEARCH_MISS_TTL_SEC"           -> "60",
      "QOD_SEARCH_MAX_BUNDLE_BYTES"       -> "1073741824"
    )
    SearchNodeConfig.EnvNames shouldBe ranges.map(_._2).toSet
    SearchNodeConfig.EnvNames.forall(_.startsWith(SearchNodeConfig.EnvPrefix)) shouldBe true
  }

  "overlay" should "drop every QOD_SEARCH_ variable it inherited and set the nine from config" in {
    val env = new java.util.HashMap[String, String]()
    env.put("QOD_SEARCH_MAX_UNINDEXED_ROWS", "999")
    env.put("QOD_SEARCH_SOME_LATER_KNOB", "x")
    env.put("PATH", "/usr/bin")
    SearchNodeConfig.overlay(env, SearchNodeConfig(maxUnindexedRows = 1234))
    // Booleans first, naming only keys (never an environment map) in the clues.
    val rows   = env.get("QOD_SEARCH_MAX_UNINDEXED_ROWS") == "1234"
    val later  = env.containsKey("QOD_SEARCH_SOME_LATER_KNOB")
    val path   = env.get("PATH") == "/usr/bin"
    val search = SearchNodeConfig.EnvNames.forall(env.containsKey)
    withClue("QOD_SEARCH_MAX_UNINDEXED_ROWS is the config's: ")(rows shouldBe true)
    withClue("QOD_SEARCH_SOME_LATER_KNOB dropped: ")(later shouldBe false)
    withClue("PATH kept: ")(path shouldBe true)
    withClue("all nine set: ")(search shouldBe true)
  }

  // Fix round 1 (review): the fleet path is tied to the Knobs table too, so a knob added to Knobs alone
  // fails here: the assignment's searchLimits (FleetHandlers.toDto) and qod fleet join's own table.
  "the fleet assignment" should "carry every knob of the table under its key, with nodeEnv's value" in {
    import ai.starlake.quack.ondemand.api.{FleetHandlers, FleetAssignmentDto}
    import ai.starlake.quack.ondemand.api.Dtos.given
    import ai.starlake.quack.ondemand.state.FleetAssignment
    import ai.starlake.quack.model.PoolKey
    val c = SearchNodeConfig(
      maxUnindexedRows = 1234,
      maxUnindexedSources = 7,
      queryMemoryBytes = 128 * MiB,
      maxK = 50,
      livenessCacheEntries = 3,
      livenessCacheBytes = 5 * MiB,
      segmentCacheBytes = 6 * GiB,
      missTtlSec = 30,
      maxBundleBytes = 2 * MiB
    )
    val dto = FleetHandlers.toDto(
      FleetAssignment(
        epoch = 1,
        nodeId = "n1",
        poolKey = PoolKey("acme", "db", "bi"),
        port = 21900,
        token = "t",
        kind = "memory",
        env = Map.empty,
        objectStoreSql = "",
        extraSetupSql = ""
      ),
      "ab" * 32,
      NodeExtensionsConfig(),
      NodeFilesConfig(),
      NodeAuthzLimitsConfig(),
      c
    )
    val limits = io.circe
      .Encoder[FleetAssignmentDto]
      .apply(dto)
      .hcursor
      .downField("searchLimits")
      .focus
      .flatMap(_.asObject)
      .map(_.toMap)
      .getOrElse(Map.empty)
    val env = SearchNodeConfig.nodeEnv(c)
    limits.keySet shouldBe SearchNodeConfig.Knobs.map(_.key).toSet
    SearchNodeConfig.Knobs.foreach { k =>
      withClue(k.key)(
        limits.get(k.key).flatMap(_.asNumber).flatMap(_.toLong).map(_.toString) shouldBe
          Some(env(k.env))
      )
    }
  }

  "qod fleet join" should "map every knob of the table, key to variable (_NODE_SEARCH_ENV)" in {
    val source = Files.readString(Paths.get("cli/src/qod_cli/fleet_join.py"))
    val start  = source.indexOf("_NODE_SEARCH_ENV = (")
    start should be >= 0
    // The tuple of pairs ends at its first "))".
    val block = source.substring(start, source.indexOf("))", start) + 2)
    val pairs = """\("(\w+)", "(QOD_SEARCH_\w+)"\)""".r
      .findAllMatchIn(block)
      .map(m => m.group(1) -> m.group(2))
      .toList
    pairs.sorted shouldBe SearchNodeConfig.Knobs.map(k => k.key -> k.env).sorted
  }

  "qod-node" should "apply the same names, defaults and ranges (search_knobs.hpp)" in {
    val header = Files.readString(Paths.get("native/qod-node/src/search_knobs.hpp"))
    val knob   =
      """constexpr Knob k\w+ \{"(QOD_SEARCH_\w+)", (\d+), (\d+), (\d+), (true|false)\};""".r
    val native = knob
      .findAllMatchIn(header)
      .map(m => m.group(1) -> (m.group(2).toLong, m.group(3).toLong, m.group(4).toLong, m.group(5)))
      .toMap
    val defaults = SearchNodeConfig.nodeEnv(SearchNodeConfig())
    native.keySet shouldBe SearchNodeConfig.EnvNames
    ranges.foreach { case (_, env, least, greatest, orZero) =>
      withClue(env)(
        native(env) shouldBe (defaults(env).toLong, least, greatest, orZero.toString)
      )
    }
  }
