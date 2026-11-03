package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.BuildVersions
import ai.starlake.quack.ondemand.DuckDbExtensionSource
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.{InetSocketAddress, Socket}
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import scala.sys.process.*
import scala.util.Try

/** A node spawned by the real script with qod-node, queried over its own quack endpoint through the
  * manager's native client after a session handle, the way the manager reaches it. The pinned
  * DuckDB CLI only creates the operator's persistent secret of one case. Cancelled without
  * qod-node.
  */
class SpawnScriptLiveNodeSpec
    extends AnyFlatSpec
    with Matchers
    with SpawnScriptTempDir
    with BeforeAndAfterAll:

  private val script = Paths.get("scripts/spawn-quack-node.sh").toAbsolutePath.toString
  private val Token  = "tok-live-node-7c41"
  private val NodeId = "quack-live-node-1"

  private val keys      = ai.starlake.quack.edge.authz.NodeKeys.ephemeral()
  private val allocator = new org.apache.arrow.memory.RootAllocator()
  private val client    = new ai.starlake.quack.edge.adapter.QuackHttpClient(allocator)

  // A DuckLake case's catalog database a killed run left behind (withLakeNode drops its own).
  override def beforeAll(): Unit = TestPostgres.dropStrayTestDatabases("qod_initsql")

  override def afterAll(): Unit = allocator.close()

  /** The DuckDB CLI this build pins, which the persistent-secret case runs. */
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
      env: Seq[(String, String)] = Nil,
      moreSecrets: Map[String, String] = Map.empty
  ): (java.lang.Process, Int) =
    assume(QodNodeTestBinary.path.nonEmpty, "no qod-node binary (QOD_NODE_BIN or a local build)")
    val port    = freePort()
    val secrets = NodeSecretFiles.writePrivateDir(
      Map(
        NodeSecretFiles.TokenKey     -> Token,
        NodeSecretFiles.AuthzKey     -> keys.keyHex(NodeId),
        NodeSecretFiles.InitNonceKey -> NodeSecretFiles.newInitNonce(),
        "extraSetupSql"              -> extraSetupSql
      ) ++ moreSecrets,
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
          // resolve the same, from the run's shared cache; before the persistent-secrets fix a
          // real HOME also leaked stored secrets into the node).
          "HOME" -> LiveExtensionCache
            .seed(Files.createDirectories(tempDir.resolve("node-home")))
            .toString,
          // The node directory (and the secret directory in it) lands where the test can see it.
          "TMPDIR"                     -> nodeTmp.toString,
          NodeSecretFiles.DirEnv       -> secrets.toString,
          NodeSecretFiles.EphemeralEnv -> "1",
          NodeSecretFiles.NodeIdEnv    -> NodeId
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

  /** `sql` run on the node through the manager's native client, after a superuser handle; one line
    * per row.
    */
  private def onNode(port: Int, sql: String): List[String] =
    val (rc, out, err) = onNodeResult(port, sql)
    withClue(err)(rc shouldBe 0)
    out.linesIterator.toList

  /** `sql` on the node as the manager runs it: the native client, after a superuser handle. (0,
    * rows as lines, "") or (1, "", the node's error).
    */
  private def onNodeResult(port: Int, sql: String): (Int, String, String) =
    new LiveQodNode.At(NodeId, port, Token, keys).query(client, sql) match
      case Right(lines) => (0, lines.mkString("\n"), "")
      case Left(err)    => (1, "", err)

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

  // Task 20 Part C, live: a tenant admin's pool initSql reaches the node inside a marked section,
  // where qod-node runs its SET and skips its CREATE SECURE VIEW over a host file; the node serves.
  // The same SQL written by a superuser runs as written.
  private val TenantInit =
    "SET memory_limit = '2GB';\n" +
      "CREATE SECURE VIEW tenant_hosts AS SELECT * FROM read_text('/etc/hosts');\n" +
      "SET threads = 3;"

  "a tenant admin's initSql" should "run only its allowlisted statements on a real node" in {
    val (p, port) = startNode(
      moreSecrets = Map(NodeSecretFiles.TenantPoolInitKey -> TenantInit)
    )
    try
      onNode(port, "SELECT current_setting('memory_limit')") shouldBe List("1.8 GiB")
      onNode(port, "SELECT current_setting('threads')") shouldBe List("3")
      onNode(port, "SELECT count(*) FROM duckdb_views() WHERE view_name = 'tenant_hosts'") shouldBe
        List("0")
      val log = Files.readString(tempDir.resolve("node.log"))
      log should include("tenant pool initSql: statement 2 of 3 skipped: CREATE is not allowed")
      log should not include "/etc/hosts"
    finally hardStop(p)
  }

  "a superuser's initSql" should "run as written on a real node" in {
    val (p, port) = startNode(
      moreSecrets = Map(NodeSecretFiles.TenantPoolInitTrustedKey -> TenantInit)
    )
    try
      onNode(port, "SELECT current_setting('memory_limit')") shouldBe List("1.8 GiB")
      onNode(port, "SELECT count(*) FROM duckdb_views() WHERE view_name = 'tenant_hosts'") shouldBe
        List("1")
    finally hardStop(p)
  }

  // Task 18: qod-node splits the whole init stream on DuckDB's own tokenizer, so an initSql whose
  // last statement has no `;` ran into the statement the script put after it (`SET threads = 3`
  // then `INSTALL ducklake;` read as one statement that does not parse) and both were lost: the
  // node came up without the setting, and without whatever followed. The script now closes every
  // text it splices in with a `;` on a line of its own. The node is a DuckLake one on a catalog
  // database of its own, so the statements after a database's text are the script's own.

  /** `sql` on the test Postgres server's maintenance database (through JDBC, which prints nothing).
    */
  private def onPostgres(sql: String): Unit =
    val c = java.sql.DriverManager
      .getConnection(TestPostgres.adminUrl, TestPostgres.pgUser, TestPostgres.pgPass)
    try
      val st = c.createStatement()
      try st.execute(sql)
      finally st.close()
    finally c.close()

  /** A DuckLake node on a Postgres catalog database of its own (dropped afterwards), spawned with
    * `secrets`, `env` and `extraSetupSql`; `body` gets its port and the catalog's name.
    */
  private def withLakeNode[A](
      secrets: Map[String, String] = Map.empty,
      env: Seq[(String, String)] = Nil,
      extraSetupSql: String = ""
  )(body: (Int, String) => A): A =
    TestPostgres.ensureReachable()
    val db = s"qod_initsql_test_${System.nanoTime().toHexString}"
    onPostgres(s"""CREATE DATABASE "$db"""")
    try
      val (p, port) = startNode(
        extraSetupSql,
        Seq(
          "kind"     -> "ducklake",
          "dbName"   -> db,
          "pgHost"   -> TestPostgres.pgHost,
          "pgPort"   -> TestPostgres.pgPort.toString,
          "pgUser"   -> TestPostgres.pgUser,
          "dataPath" -> Files.createDirectories(tempDir.resolve("lake")).toString
        ) ++ env,
        secrets + ("pgPassword" -> TestPostgres.pgPass)
      )
      try
        // First, so a failure names the cause: qod-node reports a statement it cannot parse as
        // `init: statement 4 of 17 failed: Parser Error: syntax error at or near "INSTALL"`.
        Files
          .readString(tempDir.resolve("node.log"))
          .linesIterator
          .filter(_.contains("syntax error"))
          .toList shouldBe Nil
        body(port, db)
      finally hardStop(p)
    finally onPostgres(s"""DROP DATABASE IF EXISTS "$db" WITH (FORCE)""")

  /** The node attached its DuckLake catalog `db`. */
  private def lakeUp(port: Int, db: String): Unit =
    onNode(port, s"SELECT type FROM duckdb_databases() WHERE database_name = '$db'") shouldBe
      List("ducklake")

  private def threads(port: Int): List[String] = onNode(port, "SELECT current_setting('threads')")

  private val Fed = "CREATE TABLE memory.main.fed AS SELECT 'fed-served' AS v"

  "a database's initSql with no final semicolon" should
    "apply, and not swallow the DuckLake setup after it" in
    withLakeNode(Map(NodeSecretFiles.TenantDbInitTrustedKey -> "SET threads = 3")) { (port, db) =>
      threads(port) shouldBe List("3")
      lakeUp(port, db)
    }

  it should "apply when it ends in a comment with no newline" in
    withLakeNode(
      Map(NodeSecretFiles.TenantDbInitTrustedKey -> "SET threads = 3 -- three threads")
    ) { (port, db) =>
      threads(port) shouldBe List("3")
      lakeUp(port, db)
    }

  "a pool's initSql with no final semicolon" should
    "apply, and not swallow the federation SQL after it" in
    withLakeNode(
      Map(NodeSecretFiles.TenantPoolInitTrustedKey -> "SET threads = 3"),
      extraSetupSql = Fed
    ) { (port, db) =>
      threads(port) shouldBe List("3")
      lakeUp(port, db)
      onNode(port, "SELECT v FROM memory.main.fed") shouldBe List("fed-served")
    }

  it should "apply when it ends in a comment with no newline" in
    withLakeNode(
      Map(NodeSecretFiles.TenantPoolInitTrustedKey -> "SET threads = 3 -- three threads"),
      extraSetupSql = Fed
    ) { (port, db) =>
      threads(port) shouldBe List("3")
      lakeUp(port, db)
      onNode(port, "SELECT v FROM memory.main.fed") shouldBe List("fed-served")
    }

  // A tenant admin's text sits between marker lines, which qod-node cuts the stream at before it
  // tokenizes anything: the statement after it starts a section of its own, so it needs no `;`.
  // This arm never had the defect (it passed before the fix too); the case keeps it that way.
  "a tenant admin's initSql with no final semicolon" should
    "apply without help, its end marker closing it" in
    withLakeNode(
      Map(
        NodeSecretFiles.TenantDbInitKey   -> "SET threads = 3",
        NodeSecretFiles.TenantPoolInitKey -> "SET memory_limit = '2GB' -- two gigabytes"
      ),
      extraSetupSql = Fed
    ) { (port, db) =>
      threads(port) shouldBe List("3")
      onNode(port, "SELECT current_setting('memory_limit')") shouldBe List("1.8 GiB")
      lakeUp(port, db)
      onNode(port, "SELECT v FROM memory.main.fed") shouldBe List("fed-served")
    }

  // The manager writes these two with their own `;`, but the script does not rely on it: the
  // statement after each is the script's own (the HTTP and Avro extensions, then DuckLake). Each
  // ends in a comment with no newline here, which is no `;` and swallows one written after it.
  "the manager's resource SQL with no final semicolon" should "not swallow the INSTALL after it" in
    withLakeNode(env = Seq("resourceSql" -> "SET threads = 3 -- three threads")) { (port, db) =>
      threads(port) shouldBe List("3")
      lakeUp(port, db)
    }

  "the manager's object-store SQL with no final semicolon" should
    "not swallow the DuckLake setup after it" in {
      val store = ObjectStoreSecret
        .sql(
          Map("s3_access_key_id" -> "own-id", "s3_secret_access_key" -> "own-secret"),
          "s3://lake/acme_lake",
          "ducklake"
        )
        .stripSuffix(";") + " -- the database's own keys"
      withLakeNode(Map("objectStoreSql" -> store)) { (port, db) =>
        onNode(
          port,
          "SELECT count(*) FROM duckdb_secrets() WHERE name = 'qod_db_store'"
        ) shouldBe List("1")
        lakeUp(port, db)
      }
    }

  // The terminator is a statement the init log must not count: qod-node's splitter drops an empty
  // one, so a `;` line after a text that already ends in one changes nothing, and after a bare text
  // it ends the text and counts as nothing either.
  "qod-node's statement splitter" should "report no statement for a terminator" in {
    assume(QodNodeTestBinary.path.nonEmpty, "no qod-node binary (QOD_NODE_BIN or a local build)")
    def statements(sql: String): Int =
      val out = (Process(Seq(QodNodeTestBinary.path.get.toString, "--check-init")) #<
        new java.io.ByteArrayInputStream(sql.getBytes("UTF-8"))).!!
      "\"statements\":(\\d+)".r.findFirstMatchIn(out).map(_.group(1).toInt).getOrElse(fail(out))
    statements("SET threads = 2;") shouldBe 1
    statements("SET threads = 2;\n;\n") shouldBe 1
    statements("SET threads = 2\n;\n") shouldBe 1
    statements("SET threads = 2 -- two threads\n;\n") shouldBe 1
  }

  // Task 20 review M5 (CONTRACT Addendum 4 item 28 keeps errors_as_json on the allowlist): a tenant
  // admin's SET GLOBAL errors_as_json must not change the error text the manager classifies by
  // substring (FlightSqlRouter.classifyPermanent: "does not exist" / "not found" / "catalog error";
  // QuackFrontDoor: "Current transaction is aborted", "conflict").
  "a tenant admin's SET GLOBAL errors_as_json" should "leave the manager's error classification as is" in {
    val (p, port) = startNode(
      moreSecrets = Map(NodeSecretFiles.TenantPoolInitKey -> "SET GLOBAL errors_as_json = true;")
    )
    try
      onNode(port, "SELECT current_setting('errors_as_json')") shouldBe List("true")
      val (rc, _, err) = onNodeResult(port, "SELECT * FROM memory.main.no_such_table")
      rc shouldBe 1
      withClue(err) {
        err should include("Catalog Error")
        err should include("does not exist")
      }
    finally hardStop(p)
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
      Map(
        NodeSecretFiles.TokenKey               -> Token,
        "pgPassword"                           -> "p",
        NodeSecretFiles.TenantDbInitTrustedKey -> "SELECT 'db-init';"
      ),
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
