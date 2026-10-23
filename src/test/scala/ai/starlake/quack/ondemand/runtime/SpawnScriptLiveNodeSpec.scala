package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.BuildVersions
import ai.starlake.quack.ondemand.DuckDbExtensionSource
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.{InetSocketAddress, Socket}
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import scala.sys.process.*
import scala.util.Try

/** A node spawned by the real script with qod-node, queried over its own quack endpoint from
  * another process (the pinned DuckDB CLI as a quack client), the way the manager reaches it.
  * Cancelled without qod-node or the pinned CLI.
  */
class SpawnScriptLiveNodeSpec extends AnyFlatSpec with Matchers with SpawnScriptTempDir:

  private val script = Paths.get("scripts/spawn-quack-node.sh").toAbsolutePath.toString
  private val Token  = "tok-live-node-7c41"

  /** The DuckDB CLI this build pins, the quack client of these tests. */
  private lazy val pinnedDuckdb: Option[Path] =
    val abi = BuildVersions.duckdb.split('.').take(3).mkString(".")
    sys.env
      .get("DUCKDB_BIN")
      .filter(_.nonEmpty)
      .map(Paths.get(_))
      .orElse(sys.env.get("DUCKDB_CACHE_DIR").map(d => Paths.get(d, abi, "bin", "duckdb")))
      .filter(Files.isExecutable(_))

  private def freePort(): Int =
    val s = new java.net.ServerSocket(0)
    try s.getLocalPort
    finally s.close()

  private def listening(port: Int): Boolean =
    Try {
      val s = new Socket()
      try s.connect(new InetSocketAddress("127.0.0.1", port), 200)
      finally s.close()
    }.isSuccess

  /** The pid of the qod-node process listening on `port` (the lsof -t of that port), if any. */
  private def nodePidOn(port: Int): Option[Long] =
    Try(Process(Seq("lsof", "-nP", "-tiTCP:" + port, "-sTCP:LISTEN")).!!.trim).toOption
      .filter(_.nonEmpty)
      .flatMap(_.linesIterator.find(_.nonEmpty))
      .flatMap(s => Try(s.toLong).toOption)

  private def alive(pid: Long): Boolean =
    java.lang.ProcessHandle.of(pid).map[Boolean](_.isAlive).orElse(false)

  /** SIGKILL the wrapper and every qod-node it started, so a test can never leave an orphan. */
  private def hardStop(p: java.lang.Process): Unit =
    Try(p.descendants().forEach(d => d.destroyForcibly()))
    p.destroyForcibly()
    p.waitFor(20, TimeUnit.SECONDS)

  /** Start a node (default kind `memory`) and wait until it listens. Returns the wrapper process
    * and its port; the caller stops it. Credentials ride the secrets dir, as in production.
    */
  private def startNode(
      extraSetupSql: String = "",
      env: Seq[(String, String)] = Nil
  ): (java.lang.Process, Int) =
    assume(pinnedDuckdb.nonEmpty, "the pinned DuckDB CLI is not available")
    assume(QodNodeTestBinary.path.nonEmpty, "no qod-node binary (QOD_NODE_BIN or a local build)")
    val port    = freePort()
    val secrets = NodeSecretFiles.writePrivateDir(
      Map(NodeSecretFiles.TokenKey -> Token, "extraSetupSql" -> extraSetupSql),
      Some(tempDir)
    )
    val pb = new java.lang.ProcessBuilder("bash", script, port.toString)
      .directory(Files.createDirectories(tempDir.resolve("start")).toFile)
      .redirectErrorStream(true)
      .redirectOutput(tempDir.resolve("node.log").toFile)
    pb.environment()
      .putAll(
        (Seq(
          "kind"          -> "memory",
          "schemaName"    -> "main",
          "dbName"        -> "acme_mem",
          "QOD_NODE_BIN"  -> QodNodeTestBinary.path.get.toString,
          "QOD_NODE_BIND" -> "127.0.0.1",
          // A throwaway HOME, so the node never reads the developer's own ~/.duckdb (extensions
          // resolve the same; before the persistent-secrets fix a real HOME also leaked stored
          // secrets into the node).
          "HOME" -> Files.createDirectories(tempDir.resolve("node-home")).toString,
          // The node directory (and the secret directory in it) lands where the test can see it.
          "TMPDIR"                     -> nodeTmp.toString,
          NodeSecretFiles.DirEnv       -> secrets.toString,
          NodeSecretFiles.EphemeralEnv -> "1"
        ) ++ env).toMap.asJava
      )
    val p        = pb.start()
    val deadline = System.currentTimeMillis() + 60000
    while !listening(port) && p.isAlive && System.currentTimeMillis() < deadline do
      Thread.sleep(100)
    withClue(Files.readString(tempDir.resolve("node.log")))(listening(port) shouldBe true)
    (p, port)

  private def entries(dir: Path): List[String] =
    val s = Files.list(dir)
    try s.iterator().asScala.map(_.getFileName.toString).toList.sorted
    finally s.close()

  /** Where the script creates its per-node directory (its TMPDIR). */
  private def nodeTmp: Path = Files.createDirectories(tempDir.resolve("node-tmp"))

  /** Start a `memory` node with `extraSetupSql`, run `body(port)` once it listens, then stop it. */
  private def withNode[A](extraSetupSql: String, env: (String, String)*)(body: Int => A): A =
    val (p, port) = startNode(extraSetupSql, env)
    try body(port)
    finally hardStop(p)

  /** `sql` run on the node through `quack_query` from a separate DuckDB process; CSV rows. */
  private def onNode(port: Int, sql: String): List[String] =
    val (rc, out, err) = onNodeResult(port, sql)
    withClue(err)(rc shouldBe 0)
    out.linesIterator.toList

  /** [[onNode]] without the success check: (exit code, stdout, stderr). */
  private def onNodeResult(port: Int, sql: String): (Int, String, String) =
    val inner = sql.replace("'", "''")
    val out   = new StringBuilder
    val err   = new StringBuilder
    val rc    = Process(
      Seq(
        pinnedDuckdb.get.toString,
        "-csv",
        "-noheader",
        "-c",
        s"SELECT * FROM quack_query('quack:127.0.0.1:$port', '$inner', token := '$Token');"
      ),
      tempDir.toFile,
      "DUCKDB_AGENT_MODE" -> "0",
      "HOME"              -> Files.createDirectories(tempDir.resolve("client-home")).toString
    ) ! ProcessLogger(l => out.append(l).append('\n'), l => err.append(l).append('\n'))
    (rc, out.toString, err.toString)

  // The DuckDB 2.0 CLI recorded every statement it read from stdin in shell_history(), which any
  // session of the node could query, and the init SQL carries the catalog password and the
  // federation / object-store secrets. qod-node is no CLI: there is no shell history at all.
  "a node" should "carry on past a failing init statement and keep no shell history" in {
    val marker = "AKIAINITMARKER7731"
    // The failing statement first: a node carries on past an init error, as it always has.
    withNode(
      "SELECT * FROM no_such_table;\n" +
        s"CREATE OR REPLACE TEMPORARY SECRET fed_marker (TYPE s3, KEY_ID '$marker', SECRET 'x');"
    ) { port =>
      onNode(port, "SELECT count(*) FROM duckdb_secrets() WHERE name = 'fed_marker'") shouldBe
        List("1")
      onNode(port, "SELECT 42") shouldBe List("42")
      val (rc, _, err) = onNodeResult(port, "SELECT sql FROM shell_history()")
      rc should not be 0
      err should include("shell_history")
    }
  }

  // DuckDB loads every persistent secret of its secret directory into a node on first secret use,
  // and the default is the OS user's ~/.duckdb/stored_secrets, so an operator's own cloud keys would
  // serve every tenant on the node. The node's secret directory is an empty read-only one of its
  // own, and the secret manager is already in use when it serves, so DuckDB refuses to move it.
  it should "not load the OS user's persistent secrets, nor store one of its own" in {
    assume(pinnedDuckdb.nonEmpty, "the pinned DuckDB CLI is not available")
    val home = Files.createDirectories(tempDir.resolve("home"))
    Process(
      Seq(
        pinnedDuckdb.get.toString,
        "-c",
        "CREATE PERSISTENT SECRET operator_keys (TYPE s3, KEY_ID 'AKIAOPERATOR', SECRET 's')"
      ),
      tempDir.toFile,
      "HOME"              -> home.toString,
      "DUCKDB_AGENT_MODE" -> "0"
    ).!! should include("true")
    Files.list(home.resolve(".duckdb/stored_secrets")).count() shouldBe 1
    val (p, port) = startNode("SELECT 1;", Seq("HOME" -> home.toString))
    try
      onNode(port, "SELECT count(*) FROM duckdb_secrets()") shouldBe List("0")
      // A path below the regular file `none` of the node's read-only `secrets` directory.
      val configured =
        Paths.get(onNode(port, "SELECT current_setting('secret_directory')").head)
      configured.startsWith(nodeTmp) shouldBe true
      val marker = configured.getParent
      val dir    = marker.getParent
      marker.getFileName.toString shouldBe "none"
      Files.isRegularFile(marker) shouldBe true
      dir.getFileName.toString shouldBe "secrets"
      PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)) shouldBe "r-x------"
      PosixFilePermissions.toString(Files.getPosixFilePermissions(marker)) shouldBe "r--------"
      def persistentCreateRefused(): Unit =
        val (rc, _, err) = onNodeResult(
          port,
          "CREATE PERSISTENT SECRET tenant_keys (TYPE s3, KEY_ID 'AKIATENANT', SECRET 's')"
        )
        rc should not be 0
        err should include("File exists")
        entries(dir) shouldBe List("none")
        Files.size(marker) shouldBe 0
      persistentCreateRefused()
      // What a root node with CAP_DAC_OVERRIDE sees: a writable directory. The marker still holds.
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
      persistentCreateRefused()
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"))
      val moved          = home.resolve(".duckdb/stored_secrets").toString.replace("'", "''")
      val (rc2, _, err2) = onNodeResult(port, s"SET secret_directory = '$moved'")
      rc2 should not be 0
      err2 should include("after the secret manager is used")
      onNode(port, "SELECT count(*) FROM duckdb_secrets()") shouldBe List("0")
      // A normal stop (SIGTERM) removes the node directory, read-only secret directory included.
      p.destroy()
      p.waitFor(20, TimeUnit.SECONDS) shouldBe true
      Files.exists(dir) shouldBe false
    finally hardStop(p)
  }

  // On DuckDB 2.0, `allow_persistent_secrets = false` refused every postgres ATTACH that names no
  // SECRET ("Unknown secret storage found: local_file"), so operator federation SQL carrying inline
  // credentials broke. A read-only secret directory keeps that working.
  it should "accept an inline-password postgres ATTACH in the operator's init SQL" in {
    TestPostgres.ensureReachable()
    val attach =
      s"ATTACH 'host=${TestPostgres.pgHost} port=${TestPostgres.pgPort} dbname=postgres " +
        s"user=${TestPostgres.pgUser} password=${TestPostgres.pgPass}' AS fedpg (TYPE postgres, READ_ONLY);"
    withNode(DuckDbExtensionSource.fromEnv.loadSql("postgres_scanner") + "\n" + attach) { port =>
      onNode(port, "SELECT * FROM postgres_query('fedpg', 'SELECT 41 + 1')") shouldBe List("42")
    }
  }

  // qod-node moves the secret directory itself, strictly, before it runs any of the init SQL (the
  // node's own process test pins that it refuses to start when it cannot); the SQL carries no step
  // of it, and nothing the DuckDB CLI would have run as a dot-command.
  "the node init SQL" should "leave the secret directory to qod-node and hold no dot-command" in {
    val dir = NodeSecretFiles.writePrivateDir(
      Map(NodeSecretFiles.TokenKey -> Token, "pgPassword" -> "p"),
      Some(tempDir)
    )
    val lines = Process(
      Seq("bash", script, "21999"),
      None,
      "QOD_SPAWN_DRY_RUN"          -> "1",
      "TMPDIR"                     -> nodeTmp.toString,
      "kind"                       -> "ducklake",
      "schemaName"                 -> "main",
      "dbName"                     -> "acme_lake",
      "dataPath"                   -> tempDir.resolve("lake").toString,
      "dbInitSql"                  -> "SELECT 'db-init';",
      NodeSecretFiles.DirEnv       -> dir.toString,
      NodeSecretFiles.EphemeralEnv -> "1"
    ).!!.linesIterator.filter(_.trim.nonEmpty).toVector
    lines.exists(_.contains("secret_directory")) shouldBe false
    lines.exists(_.contains("quack_serve")) shouldBe false
    lines.exists(_.trim.startsWith(".")) shouldBe false
    lines should contain("SELECT 'db-init';")
  }

  // A SIGKILLed wrapper skips its cleanup; the next spawn's sweep removes its directory, read-only
  // secret directory and marker file included (rm -rf alone cannot unlink a file in a 0500 dir).
  "a later spawn" should "sweep a dead node's read-only secret directory" in {
    val dead = new java.lang.ProcessBuilder("true").start()
    dead.waitFor(10, TimeUnit.SECONDS) shouldBe true
    val stale   = Files.createDirectories(nodeTmp.resolve("quack-node.STALE1"))
    val secrets = Files.createDirectories(stale.resolve("secrets"))
    Files.writeString(stale.resolve("owner.pid"), dead.pid().toString)
    Files.createFile(secrets.resolve("none"))
    Files.setPosixFilePermissions(
      secrets.resolve("none"),
      PosixFilePermissions.fromString("r--------")
    )
    Files.setPosixFilePermissions(secrets, PosixFilePermissions.fromString("r-x------"))
    val dir = NodeSecretFiles.writePrivateDir(Map(NodeSecretFiles.TokenKey -> Token), Some(tempDir))
    Process(
      Seq("bash", script, "21999"),
      None,
      "QOD_SPAWN_DRY_RUN"          -> "1",
      "TMPDIR"                     -> nodeTmp.toString,
      "kind"                       -> "memory",
      "schemaName"                 -> "main",
      "dbName"                     -> "m",
      NodeSecretFiles.DirEnv       -> dir.toString,
      NodeSecretFiles.EphemeralEnv -> "1"
    ).!!
    Files.exists(stale) shouldBe false
  }

  // qod-node waits for its wrapper to end (or a stop signal) without a query, which would pin a
  // transaction and could spin a core, and takes its init SQL from a pipe on stdin, never a file.
  // These pin that on a live node.
  "a running node" should "have read its init SQL from a pipe on stdin" in {
    val (p, port) = startNode()
    try
      val pid = nodePidOn(port).getOrElse(fail("no qod-node on the node port"))
      // lsof names a pipe PIPE on macOS and FIFO on Linux.
      val fd0 = Process(Seq("lsof", "-nP", "-p", pid.toString, "-a", "-d", "0", "-Ft")).!!
      withClue(fd0)(fd0.linesIterator.exists(l => l == "tPIPE" || l == "tFIFO") shouldBe true)
    finally hardStop(p)
  }

  it should "die within a few seconds of a SIGKILL of its wrapper (no orphan)" in {
    val (p, port) = startNode()
    try
      val pid = nodePidOn(port).getOrElse(fail("no qod-node on the node port"))
      // SIGKILL the wrapper ALONE (not its children), which skips its trap: qod-node sees its parent
      // gone and exits.
      java.lang.ProcessHandle.of(p.pid()).ifPresent(_.destroyForcibly())
      val deadline = System.currentTimeMillis() + 15000
      while alive(pid) && System.currentTimeMillis() < deadline do Thread.sleep(200)
      withClue(s"qod-node pid $pid still alive after the wrapper was killed")(
        alive(pid) shouldBe false
      )
    finally hardStop(p)
  }

  it should "sit near-idle on the CPU" in {
    val (p, port) = startNode()
    try
      val pid = nodePidOn(port).getOrElse(fail("no qod-node on the node port"))
      Thread.sleep(4000) // let any boot work settle
      val pcpu = Process(Seq("ps", "-o", "%cpu=", "-p", pid.toString)).!!.trim.toDouble
      // A busy-spin wait would pin a core (~100%); waiting on the parent costs ~0. Generous bound.
      withClue(s"idle qod-node used $pcpu% CPU")(pcpu should be < 50.0)
    finally hardStop(p)
  }

  "a duckdb-file node" should "CHECKPOINT (an idle node holds no write transaction)" in {
    val dbFile    = tempDir.resolve("node.duckdb").toString
    val (p, port) = startNode(
      env = Seq(
        "kind"       -> "duckdb-file",
        "dbName"     -> "acme_f",
        "schemaName" -> "main",
        "dataPath"   -> dbFile
      )
    )
    try
      onNode(port, "CREATE TABLE acme_f.main.t AS SELECT range i FROM range(200000)")
      onNode(port, "INSERT INTO acme_f.main.t SELECT range FROM range(200000)")
      // Would fail "there are other write transactions active" if the idle node held one.
      onNode(port, "CHECKPOINT acme_f")
      onNode(port, "SELECT count(*) FROM acme_f.main.t") shouldBe List("400000")
    finally hardStop(p)
  }
