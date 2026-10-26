package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.model.{NodeSpec, PoolKey, Role}
import ai.starlake.quack.ondemand.runtime.testkit.LogCapture
import cats.effect.unsafe.implicits.global
import ch.qos.logback.classic.Level
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LocalQuackBackendSpec extends AnyFlatSpec with Matchers with org.scalatest.OptionValues:
  import LocalQuackBackendSpec.{envDumpCmd, isWindows, sleepCmd}

  "LocalQuackBackend" should "start, lease a port, and register the node" in:
    val backend = new LocalQuackBackend(
      min = 23000,
      max = 23010,
      commandFor = (_, _, _) => sleepCmd(30)
    )
    val spec = NodeSpec(PoolKey("t", "t_default", "p"), "node-1", Role.Dual, Map.empty, Map.empty)
    val node = backend.start(spec).unsafeRunSync()
    try
      node.host shouldBe "127.0.0.1"
      node.port should (be >= 23000 and be <= 23010)
      node.pid.isDefined shouldBe true
      backend.isAlive("node-1") shouldBe true
    finally backend.stop(PoolKey("t", "t_default", "p"), "node-1").unsafeRunSync()

  it should "release the port on stop" in:
    val backend = new LocalQuackBackend(23020, 23021, commandFor = (_, _, _) => sleepCmd(30))
    val spec = NodeSpec(PoolKey("t", "t_default", "p"), "node-2", Role.Dual, Map.empty, Map.empty)
    backend.start(spec).unsafeRunSync()
    backend.stop(PoolKey("t", "t_default", "p"), "node-2").unsafeRunSync()
    backend.isAlive("node-2") shouldBe false

  // Regression for the pool-create spawn race: reconcile respawned a node it judged dead under the
  // same id, and start() overwrote the held Process handle, so the first process ran on untracked
  // (holding its port) and its port lease leaked.
  it should "stop the process it still holds under a node id before starting another one" in:
    assume(!isWindows)
    val backend = new LocalQuackBackend(23130, 23131, commandFor = (_, _, _) => sleepCmd(30))
    val key     = PoolKey("t", "t_default", "p")
    val spec    = NodeSpec(key, "node-again", Role.Dual, Map.empty, Map.empty)
    val first   = backend.start(spec).unsafeRunSync()
    try
      val second = backend.start(spec).unsafeRunSync()
      val handle = java.lang.ProcessHandle.of(first.pid.value)
      handle.isPresent && handle.get().isAlive shouldBe false
      backend.isAlive("node-again") shouldBe true
      second.pid should not be first.pid
      // Two ports: a lease leaked per replacement would refuse this third start.
      noException should be thrownBy backend.start(spec).unsafeRunSync()
    finally backend.stop(key, "node-again").unsafeRunSync()

  // A row's pid can name an unrelated process after a manager or container restart. adopt() must
  // not take it for the node unless its start instant matches the row's startedAt, so neither
  // stop() nor cleanup() ever signals it.
  it should "never signal an adopted pid whose process did not start when the node did" in:
    assume(!isWindows)
    val unrelated = new java.lang.ProcessBuilder(sleepCmd(30)*).start()
    val backend   = new LocalQuackBackend(23135, 23136, commandFor = (_, _, _) => sleepCmd(30))
    val key       = PoolKey("t", "t_default", "p")
    val row       = ai.starlake.quack.model.RunningNode(
      nodeId = "adopted-mismatch",
      poolKey = key,
      role = Role.Dual,
      host = "127.0.0.1",
      port = 23135,
      token = "tok",
      pid = Some(unrelated.pid()),
      podName = None,
      startedAt = java.time.Instant.now().minusSeconds(60),
      maxConcurrent = 4
    )
    try
      backend.adopt(row).unsafeRunSync()
      backend.stop(key, "adopted-mismatch").unsafeRunSync()
      backend.adopt(row).unsafeRunSync()
      backend.stopStray(row).unsafeRunSync()
      backend.cleanup().unsafeRunSync()
      unrelated.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)
      unrelated.isAlive shouldBe true
    finally
      unrelated.destroyForcibly()
      ()

  it should "carry NodeSpec.maxConcurrent through to RunningNode" in:
    val backend = new LocalQuackBackend(23030, 23031, commandFor = (_, _, _) => sleepCmd(30))
    val spec    = NodeSpec(
      PoolKey("t", "t_default", "p"),
      "node-3",
      Role.Dual,
      Map.empty,
      Map.empty,
      maxConcurrent = 5
    )
    val node = backend.start(spec).unsafeRunSync()
    try node.maxConcurrent shouldBe 5
    finally backend.stop(PoolKey("t", "t_default", "p"), "node-3").unsafeRunSync()

  it should "propagate NodeSpec.metastore as env vars with no backend-level defaults overlay" in:
    val capture = java.io.File.createTempFile("env-capture-", ".txt")
    capture.deleteOnExit()
    val backend = new LocalQuackBackend(
      min = 23040,
      max = 23041,
      commandFor = (_, _, _) => envDumpCmd(capture.getAbsolutePath, 5)
    )
    val spec = NodeSpec(
      PoolKey("t", "t_default", "p"),
      "node-env",
      Role.Dual,
      metastore = Map("pgHost" -> "override-host", "dbName" -> "specdb"),
      s3 = Map.empty
    )
    backend.start(spec).unsafeRunSync()
    try
      // Give the shell a moment to write the env dump
      val deadline = System.currentTimeMillis() + 3000
      while capture.length() == 0 && System.currentTimeMillis() < deadline do Thread.sleep(50)
      val out = java.nio.file.Files.readString(capture.toPath)
      out should include("pgHost=override-host") // spec.metastore is the sole source
      out should include("dbName=specdb")
      // Regression for the backend-overlay bug: LocalQuackBackend no longer
      // takes a `defaultMetastore` constructor param, so nothing can
      // resurrect a key `PoolSupervisor.effectiveMetastoreFor` deliberately
      // dropped (e.g. `dataPath` on a pathless duckdb-file / in-memory node).
      // A key absent from spec.metastore must stay absent from the node env.
      out should not include "pgUser="
    finally backend.stop(PoolKey("t", "t_default", "p"), "node-env").unsafeRunSync()

  it should "omit a key spec.metastore does not carry (e.g. dataPath dropped by effectiveMetastoreFor)" in:
    val capture = java.io.File.createTempFile("env-capture-nodatapath-", ".txt")
    capture.deleteOnExit()
    val backend = new LocalQuackBackend(
      min = 23042,
      max = 23043,
      commandFor = (_, _, _) => envDumpCmd(capture.getAbsolutePath, 5)
    )
    // Mirrors what effectiveMetastoreFor produces for a pathless duckdb-file
    // tenant-db: dataPath removed entirely, not merely blank.
    val spec = NodeSpec(
      PoolKey("t", "t_default", "p"),
      "node-nodatapath",
      Role.Dual,
      metastore = Map("pgHost" -> "h", "dbName" -> "specdb"),
      s3 = Map.empty
    )
    backend.start(spec).unsafeRunSync()
    try
      val deadline = System.currentTimeMillis() + 3000
      while capture.length() == 0 && System.currentTimeMillis() < deadline do Thread.sleep(50)
      val out = java.nio.file.Files.readString(capture.toPath)
      out should not include "dataPath="
    finally backend.stop(PoolKey("t", "t_default", "p"), "node-nodatapath").unsafeRunSync()

  it should "deliver every secret through a private secrets directory, never the env or argv" in:
    val capture = java.io.File.createTempFile("env-capture-secrets-", ".txt")
    capture.deleteOnExit()
    val argv    = new java.util.concurrent.atomic.AtomicReference[List[String]](Nil)
    val backend = new LocalQuackBackend(
      min = 23090,
      max = 23091,
      commandFor = (spec, port, token) =>
        argv.set(LocalQuackBackend.defaultCommand("spawn.sh")(spec, port, token))
        envDumpCmd(capture.getAbsolutePath, 5)
    )
    val storeSql = "CREATE OR REPLACE SECRET qod_db_store (TYPE s3, SECRET 'store-secret');"
    val spec     = NodeSpec(
      PoolKey("t", "t_default", "p"),
      "node-secrets",
      Role.Dual,
      metastore = Map(
        "pgHost"        -> "h",
        "pgPassword"    -> "hunter2",
        "encryptionKey" -> "c2VjcmV0",
        "PgPassword"    -> "odd-case"
      ),
      s3 = Map.empty,
      objectStoreSql = storeSql,
      extraSetupSql = "ATTACH 'fed-secret' AS f;"
    )
    val node = backend.start(spec).unsafeRunSync()
    try
      val deadline = System.currentTimeMillis() + 3000
      while capture.length() == 0 && System.currentTimeMillis() < deadline do Thread.sleep(50)
      val out = java.nio.file.Files.readString(capture.toPath)
      Seq("hunter2", "c2VjcmV0", "odd-case", "store-secret", "fed-secret", node.token).foreach { v =>
        withClue(v)(out should not include v)
      }
      argv.get() should not contain node.token
      out should include("pgHost=h")
      out should include(s"${NodeSecretFiles.EphemeralEnv}=1")
      val dir = java.nio.file.Paths.get(
        out.linesIterator
          .find(_.startsWith(s"${NodeSecretFiles.DirEnv}="))
          .value
          .stripPrefix(s"${NodeSecretFiles.DirEnv}=")
      )
      def read(k: String) = java.nio.file.Files.readString(dir.resolve(k))
      read("pgPassword") shouldBe "hunter2"
      read("encryptionKey") shouldBe "c2VjcmV0"
      read("objectStoreSql") shouldBe storeSql
      read("extraSetupSql") shouldBe "ATTACH 'fed-secret' AS f;"
      read(NodeSecretFiles.TokenKey) shouldBe node.token
      if !isWindows then
        java.nio.file.attribute.PosixFilePermissions
          .toString(java.nio.file.Files.getPosixFilePermissions(dir)) shouldBe "rwx------"
        java.nio.file.attribute.PosixFilePermissions
          .toString(java.nio.file.Files.getPosixFilePermissions(dir.resolve("pgPassword"))) shouldBe
          "rw-------"
      backend.stop(PoolKey("t", "t_default", "p"), "node-secrets").unsafeRunSync()
      // The launcher removes the directory once the process is gone (here the stand-in never
      // read it, like a script that died before getting that far).
      val gone = System.currentTimeMillis() + 5000
      while java.nio.file.Files.exists(dir) && System.currentTimeMillis() < gone do Thread.sleep(50)
      java.nio.file.Files.exists(dir) shouldBe false
    finally backend.stop(PoolKey("t", "t_default", "p"), "node-secrets").unsafeRunSync()

  // The node's identity reaches the spawn script as the manager derived it: the key as a file of
  // the secrets directory (never env or argv), the id as the plain env var the script passes on as
  // --node-id. A metastore entry spelled like either one replaces neither. The environment checks
  // are booleans: a failing `should not include` would print the whole environment dump.
  it should "hand the node its key as a file and its id as env, whatever its metastore carries" in:
    val capture = java.io.File.createTempFile("env-capture-identity-", ".txt")
    capture.deleteOnExit()
    val keys       = ai.starlake.quack.edge.authz.NodeKeys.ephemeral()
    val argv       = new java.util.concurrent.atomic.AtomicReference[List[String]](Nil)
    val production = LocalQuackBackend.defaultCommand(
      "spawn.sh",
      "spawn.ps1",
      LocalQuackBackend.managerMarker(20900)
    )
    val backend = new LocalQuackBackend(
      min = 23092,
      max = 23093,
      nodeKeys = keys,
      commandFor = (spec, port, token) =>
        argv.set(production(spec, port, token))
        envDumpCmd(capture.getAbsolutePath, 5)
    )
    val nodeId = "node-identity"
    val key    = keys.keyHex(nodeId)
    val spec   = NodeSpec(
      PoolKey("t", "t_default", "p"),
      nodeId,
      Role.Dual,
      // The id variable's own name, and the key file's name in another case (isKey compares names
      // case-insensitively).
      metastore = Map("pgHost" -> "h", "QOD_NODE_ID" -> "spoof", "qod_node_authz_key" -> "spoof"),
      s3 = Map.empty
    )
    val (_, logged) =
      LogCapture.logged(classOf[LocalQuackBackend].getName)(backend.start(spec).unsafeRunSync())
    try
      // Each spoofed key named once, at ERROR, never with its value.
      LogCapture.droppedKeyLines(logged) shouldBe
        List("QOD_NODE_ID", "qod_node_authz_key").map((Level.ERROR, spec.poolKey.toString, _))
      withClue("a spoofed value in the backend's log")(
        logged.exists(_.getFormattedMessage.contains("spoof")) shouldBe false
      )
      val deadline = System.currentTimeMillis() + 3000
      while capture.length() == 0 && System.currentTimeMillis() < deadline do Thread.sleep(50)
      val out = java.nio.file.Files.readString(capture.toPath)
      // The id is the node's own, set once; nothing the metastore spoofed reached the environment.
      out.linesIterator.filter(_.startsWith("QOD_NODE_ID=")).toList shouldBe
        List(s"QOD_NODE_ID=$nodeId")
      withClue("a spoofed value in the node's environment")(out.contains("spoof") shouldBe false)
      withClue("a variable named like the node key file")(
        out.linesIterator
          .map(_.takeWhile(_ != '='))
          .exists(_.equalsIgnoreCase(NodeSecretFiles.AuthzKey)) shouldBe false
      )
      // The key is in no environment and no argv, only in its file.
      withClue("the node's key in its environment")(out.contains(key) shouldBe false)
      withClue("the node's key in its argv")(argv.get().exists(_.contains(key)) shouldBe false)
      val dir = java.nio.file.Paths.get(
        out.linesIterator
          .find(_.startsWith(s"${NodeSecretFiles.DirEnv}="))
          .value
          .stripPrefix(s"${NodeSecretFiles.DirEnv}=")
      )
      // Exactly the node's token and key, nothing the metastore named. A cloud credential the
      // developer's shell exports is delivered too (the backend forwards the manager's own), so it
      // is set aside rather than counted.
      (dir.toFile.list().toSet -- NodeSecretFiles.CloudCredKeys) shouldBe
        Set(NodeSecretFiles.TokenKey, NodeSecretFiles.AuthzKey)
      val onDisk = java.nio.file.Files.readString(dir.resolve(NodeSecretFiles.AuthzKey))
      withClue("the key file does not hold the key derived for the node id")(
        (onDisk == key) shouldBe true
      )
    finally backend.stop(PoolKey("t", "t_default", "p"), nodeId).unsafeRunSync()

  // Decision 17: the extension policy reaches the spawn script as three plain variables, always all
  // three, from the manager's config: neither the manager's own environment nor a metastore entry
  // spelled like one decides them. The checks print only these three variables, never the dump.
  it should "hand the node the manager's extension policy, whatever its metastore carries" in:
    val capture = java.io.File.createTempFile("env-capture-extensions-", ".txt")
    capture.deleteOnExit()
    val backend = new LocalQuackBackend(
      min = 23098,
      max = 23099,
      nodeExtensions = ai.starlake.quack.NodeExtensionsConfig(allowUnsigned = true),
      commandFor = (_, _, _) => envDumpCmd(capture.getAbsolutePath, 5)
    )
    val key  = PoolKey("t", "t_default", "p")
    val spec = NodeSpec(
      key,
      "node-extensions",
      Role.Dual,
      metastore = Map("pgHost" -> "h", "QOD_NODE_ALLOW_COMMUNITY_EXTENSIONS" -> "true"),
      s3 = Map.empty
    )
    backend.start(spec).unsafeRunSync()
    try
      val deadline = System.currentTimeMillis() + 3000
      while capture.length() == 0 && System.currentTimeMillis() < deadline do Thread.sleep(50)
      val out = java.nio.file.Files.readString(capture.toPath)
      ai.starlake.quack.NodeExtensionsConfig.EnvNames.toList.sorted.map { name =>
        out.linesIterator.filter(_.startsWith(s"$name=")).toList
      } shouldBe List(
        List("QOD_NODE_ALLOW_COMMUNITY_EXTENSIONS=false"),
        List("QOD_NODE_ALLOW_UNSIGNED_EXTENSIONS=true"),
        List("QOD_NODE_AUTOINSTALL_KNOWN_EXTENSIONS=false")
      )
    finally backend.stop(key, "node-extensions").unsafeRunSync()

  // SEC-1: a tenant-db's metastore is tenant-admin input. Only TenantDb.NodeEnvKeys of it may reach
  // the spawn script's environment: BASH_ENV (bash expands it before running the script), PATH or
  // QOD_NODE_BIN would run commands as the manager's user, lockdownSql would replace the manager's
  // own. The metastore's other keys never become a secret file either. The values are inert
  // markers, and the checks are booleans: a failing `should not include` would print the dump.
  it should "pass only TenantDb.NodeEnvKeys of the metastore to the spawn environment" in:
    val captures = scala.collection.concurrent.TrieMap.empty[String, java.io.File]
    def capture(nodeId: String): java.io.File =
      captures.getOrElseUpdate(
        nodeId, {
          val f = java.io.File.createTempFile(s"env-capture-$nodeId-", ".txt")
          f.deleteOnExit()
          f
        }
      )
    val managerBin = java.nio.file.Paths.get("/opt/qod-sec1/manager/bin/qod-node")
    val backend    = new LocalQuackBackend(
      min = 23096,
      max = 23097,
      nodeBin = Some(managerBin),
      commandFor = (spec, _, _) => envDumpCmd(capture(spec.nodeId).getAbsolutePath, 5)
    )
    val allowed = Map(
      "pgHost"       -> "sec1-pg-host",
      "pgPort"       -> "15432",
      "pgUser"       -> "sec1_user",
      "dbName"       -> "sec1_db",
      "catalogAlias" -> "sec1_alias",
      "schemaName"   -> "sec1_schema",
      "dataPath"     -> "/data/sec1",
      "encrypted"    -> "true"
    )
    // A PATH that still resolves `env` and `sleep`, so the dump is written whether or not it leaks.
    val markedPath =
      "/nonexistent/sec1-marker-path" + java.io.File.pathSeparator +
        sys.env.getOrElse("PATH", "/usr/bin:/bin")
    val hostile = Map(
      "BASH_ENV"                 -> "/nonexistent/sec1-marker-bash-env",
      "PATH"                     -> markedPath,
      "QOD_NODE_BIN"             -> "/nonexistent/sec1-marker-node-bin",
      "lockdownSql"              -> "SELECT 'sec1-marker-lockdown';",
      "kind"                     -> "sec1-marker-kind",
      "extraSetupSql"            -> "SELECT 'sec1-marker-extra';",
      "QOD_S3_SECRET_ACCESS_KEY" -> "sec1-marker-s3-secret",
      "sec1Unknown"              -> "sec1-marker-unknown"
    )
    val key             = PoolKey("t", "t_default", "p")
    val managerLockdown = "SET enable_external_access = false;"

    def spec(nodeId: String, lockdownSql: String) = NodeSpec(
      key,
      nodeId,
      Role.Dual,
      metastore = allowed ++ hostile + ("pgPassword" -> "sec1-pg-password"),
      s3 = Map.empty,
      kindWire = "memory",
      lockdownSql = lockdownSql
    )
    def dumpOf(nodeId: String): String =
      val f        = capture(nodeId)
      val deadline = System.currentTimeMillis() + 3000
      while f.length() == 0 && System.currentTimeMillis() < deadline do Thread.sleep(50)
      java.nio.file.Files.readString(f.toPath)
    def lines(out: String, name: String): List[String] =
      out.linesIterator.filter(_.startsWith(s"$name=")).toList
    val (_, logged) = LogCapture.logged(classOf[LocalQuackBackend].getName) {
      backend.start(spec("node-sec1-open", lockdownSql = "")).unsafeRunSync()
      backend.start(spec("node-sec1-locked", lockdownSql = managerLockdown)).unsafeRunSync()
    }
    try
      // Each dropped key named once for the pool, not once per node, at ERROR, never with its value
      // (a boolean check: the PATH value holds this process's own PATH).
      LogCapture.droppedKeyLines(logged) shouldBe
        hostile.keys.toList.sorted.map((Level.ERROR, key.toString, _))
      withClue("a metastore marker in the backend's log: ")(
        logged.exists(_.getFormattedMessage.contains("sec1-marker")) shouldBe false
      )
      val open   = dumpOf("node-sec1-open")
      val locked = dumpOf("node-sec1-locked")
      Seq(open, locked).foreach { out =>
        withClue("a metastore marker in the spawn environment: ")(
          out.contains("sec1-marker") shouldBe false
        )
        allowed.foreach { case (k, v) =>
          withClue(s"$k as the metastore has it: ")((lines(out, k) == List(s"$k=$v")) shouldBe true)
        }
        withClue("the unknown key: ")(lines(out, "sec1Unknown").isEmpty shouldBe true)
        // What the manager sets stays the manager's, whatever the metastore names.
        withClue("the manager's qod-node: ")(
          (lines(out, "QOD_NODE_BIN") == List(s"QOD_NODE_BIN=$managerBin")) shouldBe true
        )
        withClue("the kind: ")((lines(out, "kind") == List("kind=memory")) shouldBe true)
        // The secrets directory holds the metastore's password and the node's own keys, and nothing
        // the metastore named otherwise (a cloud credential the developer's shell exports is the
        // manager's own, delivered too, so it is set aside rather than counted).
        val dir = java.nio.file.Paths.get(
          lines(out, NodeSecretFiles.DirEnv).headOption.value
            .stripPrefix(s"${NodeSecretFiles.DirEnv}=")
        )
        (dir.toFile.list().toSet -- NodeSecretFiles.CloudCredKeys) shouldBe
          Set("pgPassword", NodeSecretFiles.TokenKey, NodeSecretFiles.AuthzKey)
        val s3 = dir.resolve("QOD_S3_SECRET_ACCESS_KEY")
        withClue("the metastore's cloud credential in its file: ")(
          (java.nio.file.Files.exists(s3) &&
            java.nio.file.Files.readString(s3).contains("sec1-marker")) shouldBe false
        )
      }
      withClue("lockdownSql without the manager's: ")(
        lines(open, "lockdownSql").isEmpty shouldBe true
      )
      withClue("the manager's lockdownSql: ")(
        (lines(locked, "lockdownSql") == List(s"lockdownSql=$managerLockdown")) shouldBe true
      )
    finally
      backend.stop(key, "node-sec1-open").unsafeRunSync()
      backend.stop(key, "node-sec1-locked").unsafeRunSync()

  // Regression for issue #2 (Graceful JVM shutdown hook): cleanup()
  // must SIGTERM every tracked child, wait for them to exit (falling
  // back to SIGKILL on stragglers), then clear its registry maps so a
  // second cleanup() is a no-op.
  it should "cleanup(): graceful SIGTERM every tracked child then clear maps" in:
    val backend = new LocalQuackBackend(
      23060,
      23069,
      // sleep 30 reacts cleanly to SIGTERM (the shell exits 143
      // immediately), so the cleanup wait should never need the
      // 5 s SIGKILL fallback. (Windows console apps ignore the graceful
      // taskkill, so the timing assertion below is Unix-only.)
      commandFor = (_, _, _) => sleepCmd(30)
    )
    val a = NodeSpec(PoolKey("t", "t_default", "p"), "node-cu-a", Role.Dual, Map.empty, Map.empty)
    val b = NodeSpec(PoolKey("t", "t_default", "p"), "node-cu-b", Role.Dual, Map.empty, Map.empty)
    backend.start(a).unsafeRunSync()
    backend.start(b).unsafeRunSync()
    backend.isAlive("node-cu-a") shouldBe true
    backend.isAlive("node-cu-b") shouldBe true

    val t0 = System.currentTimeMillis()
    backend.cleanup().unsafeRunSync()
    val elapsedMs = System.currentTimeMillis() - t0

    // Children honour SIGTERM, so we should NOT need the 5 s fallback
    // per process. 8 s leaves headroom for slow CI. On Windows the graceful
    // `taskkill /T` is ignored by console processes, so cleanup always waits
    // out the grace period before force-killing - skip the promptness bound.
    if !isWindows then elapsedMs should be < 8000L
    backend.isAlive("node-cu-a") shouldBe false
    backend.isAlive("node-cu-b") shouldBe false

    // Idempotent: a second cleanup is a no-op against an empty map.
    backend.cleanup().unsafeRunSync()

  // Regression for the shutdown-orphan investigation (2026-07-18): when the
  // wrapper ignores SIGTERM (or is mid-trap) and cleanup escalates to
  // SIGKILL, the kill must take the wrapper's WHOLE tree. SIGKILL bypasses
  // the wrapper's trap, which is what normally forwards TERM to the duckdb
  // child, so without an explicit descendant sweep the grandchild orphans.
  it should "cleanup(): force-kill takes descendants when the wrapper ignores SIGTERM" in:
    assume(!isWindows)
    val backend = new LocalQuackBackend(
      23070,
      23073,
      commandFor = (_, _, _) => List("bash", "-c", "trap '' TERM; sleep 30 & wait")
    )
    val spec =
      NodeSpec(PoolKey("t", "t_default", "p"), "node-tree", Role.Dual, Map.empty, Map.empty)
    val node    = backend.start(spec).unsafeRunSync()
    val wrapper = java.lang.ProcessHandle.of(node.pid.get).get()
    // Wait for the sleep child to appear under the wrapper.
    val deadline = System.currentTimeMillis() + 3000
    while wrapper.children().count() == 0 && System.currentTimeMillis() < deadline do
      Thread.sleep(50)
    val kids = {
      import scala.jdk.CollectionConverters.*
      wrapper.descendants().iterator().asScala.toList
    }
    kids should not be empty

    backend.cleanup().unsafeRunSync()
    Thread.sleep(200)
    wrapper.isAlive shouldBe false
    withClue(s"descendants survived cleanup: ${kids.filter(_.isAlive).map(_.pid())}") {
      kids.exists(_.isAlive) shouldBe false
    }

  // Regression for the same investigation: cleanup() previously cleared
  // `adoptedPids` without terminating them, so nodes adopted from a previous
  // manager generation survived SIGTERM deterministically.
  it should "cleanup(): terminate adopt-only nodes tracked by pid" in:
    assume(!isWindows)
    val orphan  = new java.lang.ProcessBuilder("sh", "-c", "sleep 30").start()
    val backend = new LocalQuackBackend(23080, 23081, commandFor = (_, _, _) => sleepCmd(30))
    backend
      .adopt(
        ai.starlake.quack.model.RunningNode(
          nodeId = "adopted-1",
          poolKey = PoolKey("t", "t_default", "p"),
          role = Role.Dual,
          host = "127.0.0.1",
          port = 23080,
          token = "tok",
          pid = Some(orphan.pid()),
          podName = None,
          startedAt = java.time.Instant.now(),
          maxConcurrent = 4
        )
      )
      .unsafeRunSync()

    backend.cleanup().unsafeRunSync()
    orphan.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
    orphan.isAlive shouldBe false

object LocalQuackBackendSpec:
  val isWindows: Boolean = sys.props.getOrElse("os.name", "").toLowerCase.contains("win")

  /** A long-running child that the backend can start / observe / terminate, spelled for the host
    * OS: `sh -c "sleep N"` on Unix, a PowerShell `Start-Sleep` on Windows.
    */
  def sleepCmd(seconds: Int): List[String] =
    if isWindows then
      List(
        "powershell.exe",
        "-NoProfile",
        "-NonInteractive",
        "-Command",
        s"Start-Sleep -Seconds $seconds"
      )
    else List("sh", "-c", s"sleep $seconds")

  /** Dump the child's environment as `KEY=VALUE` lines to `file`, then linger `seconds` so the
    * parent can read it before stop().
    */
  def envDumpCmd(file: String, seconds: Int): List[String] =
    if isWindows then
      List(
        "powershell.exe",
        "-NoProfile",
        "-NonInteractive",
        "-Command",
        s"""Get-ChildItem Env: | ForEach-Object { "$$($$_.Name)=$$($$_.Value)" } | """ +
          s"""Set-Content -LiteralPath '$file' -Encoding utf8; Start-Sleep -Seconds $seconds"""
      )
    else List("sh", "-c", s"env > '$file'; sleep $seconds")
