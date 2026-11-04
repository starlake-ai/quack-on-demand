package ai.starlake.quack

import ai.starlake.quack.boot.BootFactories
import ai.starlake.quack.edge.authz.NodeKeys
import ai.starlake.quack.ondemand.api.ConfigRegistry
import ai.starlake.quack.ondemand.state.InMemoryFleetServerStore
import com.typesafe.config.{ConfigFactory, ConfigResolveOptions}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource
import pureconfig.error.ConfigReaderException

/** Pins the nodeFiles block: on by default, off from one variable, refused at config load when it
  * is no boolean, and the spawn environment that always names it. What a row and a node do with it
  * is in LocalFilesBlockSpec, NodeFilesScriptsSpec and LocalFilesOffE2ESpec.
  */
class NodeFilesConfigSpec extends AnyFlatSpec with Matchers:
  import Main.given

  /** The bundled application.conf, with `env` standing in for the process environment and the real
    * environment left out.
    */
  private def load(env: Map[String, String] = Map.empty): ManagerConfig =
    loadOverlay(env.map((k, v) => s"$k = \"$v\"").mkString("\n"))

  private def loadOverlay(overlay: String): ManagerConfig =
    val conf = ConfigFactory
      .parseString(overlay)
      .withFallback(ConfigFactory.parseResources("application.conf"))
      .resolve(ConfigResolveOptions.noSystem())
    ConfigSource.fromConfig(conf).at("quack-on-demand").loadOrThrow[ManagerConfig]

  "NodeFilesConfig" should "leave local files on by default, for anyone who does not set it" in {
    NodeFilesConfig().allowLocal shouldBe true
    load().nodeFiles shouldBe NodeFilesConfig(allowLocal = true)
  }

  it should "turn them off from its variable and from the camelCase key" in {
    load(Map("QOD_NODE_ALLOW_LOCAL_FILES" -> "false")).nodeFiles.allowLocal shouldBe false
    load(Map("QOD_NODE_ALLOW_LOCAL_FILES" -> "true")).nodeFiles.allowLocal shouldBe true
    loadOverlay("quack-on-demand.nodeFiles.allowLocal = false").nodeFiles.allowLocal shouldBe false
  }

  it should "be refused at config load when it is no boolean" in {
    for value <- List("maybe", "0", "") do
      withClue(s"QOD_NODE_ALLOW_LOCAL_FILES=$value: ") {
        a[ConfigReaderException[?]] should be thrownBy
          load(Map("QOD_NODE_ALLOW_LOCAL_FILES" -> value))
      }
  }

  it should "register its one setting under its variable, not sensitive" in {
    ConfigRegistry
      .collect(List("quack-on-demand" -> classOf[ManagerConfig]))
      .find(_.path == "quack-on-demand.nodeFiles.allowLocal")
      .map(e => (e.envVar, e.sensitive)) shouldBe
      Some(("QOD_NODE_ALLOW_LOCAL_FILES", false))
  }

  "env" should "always name the variable, whatever the setting" in {
    NodeFilesConfig().env shouldBe Map("QOD_NODE_ALLOW_LOCAL_FILES" -> "true")
    NodeFilesConfig(allowLocal = false).env shouldBe Map("QOD_NODE_ALLOW_LOCAL_FILES" -> "false")
    NodeFilesConfig.EnvNames shouldBe NodeFilesConfig().env.keySet
  }

  "isRemote" should "read a path as DuckDB compares allowed_directories: exact prefixes" in {
    for path <- List(
        "s3://b/x/",
        "s3a://b/x/",
        "s3n://b/x/",
        "r2://b/x/",
        "gs://b/x/",
        "gcs://b/x/",
        "hf://datasets/x/",
        "az://c/x/",
        "azure://c/x/",
        "abfs://c@a/x/",
        "abfss://c@a/x/",
        "http://h/x",
        "https://h/x"
      )
    do withClue(path)(NodeFilesConfig.isRemote(path) shouldBe true)
    for path <- List(
        "/data/x/",
        "./ducklake/x",
        "ducklake/x",
        "file:///data/x",
        "S3://b/x/",
        "memory://x",
        "C:\\data\\x"
      )
    do withClue(path)(NodeFilesConfig.isRemote(path) shouldBe false)
  }

  "localDataPath" should "name a local path, and an empty one only for a kind that needs a path" in {
    NodeFilesConfig.localDataPath(pathOptional = false, "/data/x/") shouldBe Some("/data/x/")
    NodeFilesConfig.localDataPath(pathOptional = true, "/data/x/") shouldBe Some("/data/x/")
    NodeFilesConfig.localDataPath(pathOptional = false, "") shouldBe Some("")
    NodeFilesConfig.localDataPath(pathOptional = true, "") shouldBe None
    NodeFilesConfig.localDataPath(pathOptional = false, "s3://b/x/") shouldBe None
  }

  "InitStatements" should "end by turning access off, with no local path in what it allows" in {
    val statements = NodeFilesConfig.InitStatements
    statements.last shouldBe "SET enable_external_access = false;"
    statements.head shouldBe "LOAD httpfs;"
    (NodeFilesConfig.HttpfsSchemes ++ NodeFilesConfig.AzureSchemes).foreach(_ should endWith("://"))
    // The one exception to no local path: the files httpfs reads for TLS, each named.
    NodeFilesConfig.CaBundles.foreach(_ should startWith("/etc/"))
    NodeFilesConfig.CaBundles.foreach(p => p should not endWith "/")
  }

  // A launcher that forgot the setting would not compile (NodeKeysRequiredSpec). These pin that what
  // the boot factories hand it is the manager's, and the supervisor reads the same answer.
  "BootFactories" should "build every runtime's backend with the manager's setting" in {
    def backend(runtimeType: String, allowLocal: Boolean) =
      BootFactories.quackBackend(
        load().copy(
          runtimeType = runtimeType,
          minPort = 23900,
          maxPort = 23901,
          nodeFiles = NodeFilesConfig(allowLocal),
          fleet = FleetConfig(joinToken = "x")
        ),
        new InMemoryFleetServerStore(),
        None,
        NodeKeys.ephemeral()
      )
    for runtimeType <- List("local", "fleet"); allowLocal <- List(true, false) do
      withClue(s"$runtimeType, allowLocal=$allowLocal: ") {
        backend(runtimeType, allowLocal).allowsLocalFiles shouldBe allowLocal
      }
  }

  // The fleet assignment's setting comes from Main's FleetHandlers, and no spec boots that wiring:
  // read from the source, as a spec does when only a boot could show it.
  "Main" should "hand the fleet handlers the manager's setting" in {
    val main = java.nio.file.Files.readString(
      java.nio.file.Path.of("src/main/scala/ai/starlake/quack/Main.scala")
    )
    main should include regex
      "new ai\\.starlake\\.quack\\.ondemand\\.api\\.FleetHandlers\\([^)]*nodeFiles = mgrCfg\\.nodeFiles"
  }

  // The health loop's lambda lives in Main too: what it asks for, by the same reading.
  it should "have the health loop read a node's access only as the backend's setting says" in {
    val main = java.nio.file.Files.readString(
      java.nio.file.Path.of("src/main/scala/ai/starlake/quack/Main.scala")
    )
    main should include("adapter.healthProbe(n, probeSql, backend.allowsLocalFiles)")
  }

  "BootFactories" should "build the ephemeral backend of a fleet with it too" in {
    val cfg = load().copy(
      runtimeType = "fleet",
      minPort = 23900,
      maxPort = 23901,
      nodeFiles = NodeFilesConfig(allowLocal = false),
      fleet = FleetConfig(joinToken = "x", ephemeral = "local")
    )
    val main = BootFactories.quackBackend(
      cfg,
      new InMemoryFleetServerStore(),
      None,
      NodeKeys.ephemeral()
    )
    val ephemeral = BootFactories.ephemeralBackend(cfg, main, None, NodeKeys.ephemeral())
    (ephemeral eq main) shouldBe false
    ephemeral.allowsLocalFiles shouldBe false
  }
