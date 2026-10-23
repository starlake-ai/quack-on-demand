package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.BuildVersions
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import scala.sys.process.*

/** DuckDB 2.0 replacement-scans an unquoted dotted name as a file when no such schema exists:
  * `SELECT * FROM x.csv` reads `./x.csv`. An unquoted name cannot hold a path separator, so the
  * only directory it reaches is the node process's working directory. The spawn script therefore
  * starts qod-node in a fresh, empty, private directory of its own, after making every relative
  * path the node relies on (dataPath, the qod-node binary, the spill directory) absolute against
  * the directory the script was started from, and removes that directory when the node stops.
  */
class SpawnScriptWorkDirSpec extends AnyFlatSpec with Matchers with SpawnScriptTempDir:

  private val script = Paths.get("scripts/spawn-quack-node.sh").toAbsolutePath.toString

  private val TempDirRe = """(?m)^SET temp_directory = '([^']+)';$""".r

  /** Run the spawn script from `cwd`, the secrets going through a private directory as in
    * production. Returns the started process; the caller waits for or stops it.
    */
  private def launch(cwd: Path, env: Seq[(String, String)]): java.lang.Process =
    val (secret, plain) = env.partition { case (k, _) => NodeSecretFiles.isKey(k) }
    val dir             = NodeSecretFiles.writePrivateDir(
      (Seq(
        NodeSecretFiles.TokenKey -> "tok-workdir",
        NodeSecretFiles.AuthzKey -> ("ab" * 32)
      ) ++ secret).toMap,
      Some(tempDir)
    )
    val pb = new java.lang.ProcessBuilder("bash", script, "23351").directory(cwd.toFile)
    pb.environment()
      .putAll(
        (plain ++ Seq(
          NodeSecretFiles.DirEnv       -> dir.toString,
          NodeSecretFiles.EphemeralEnv -> "1",
          NodeSecretFiles.NodeIdEnv    -> "quack-workdir-node-1"
        )).toMap.asJava
      )
    pb.redirectErrorStream(true)
    pb.start()

  private def dryRun(cwd: Path, env: (String, String)*): String =
    val p   = launch(cwd, Seq("QOD_SPAWN_DRY_RUN" -> "1") ++ env)
    val out = new String(p.getInputStream.readAllBytes())
    p.waitFor(30, TimeUnit.SECONDS) shouldBe true
    withClue(out)(p.exitValue() shouldBe 0)
    out

  private val memory = Seq("kind" -> "memory", "schemaName" -> "main", "dbName" -> "acme_mem")

  private def ducklake(dataPath: String) = Seq(
    "kind"         -> "ducklake",
    "schemaName"   -> "main",
    "catalogAlias" -> "acme_lake",
    "dbName"       -> "acme_lake",
    "dataPath"     -> dataPath,
    "pgHost"       -> "h",
    "pgPort"       -> "5432",
    "pgUser"       -> "u",
    "pgPassword"   -> "p"
  )

  private def dir(name: String): Path = Files.createDirectories(tempDir.resolve(name))

  "the node init SQL" should "spill to an absolute per-node directory, before the operator's own SQL" in {
    val sql   = dryRun(dir("start"), (memory :+ ("dbInitSql" -> "SELECT 'db-init';"))*)
    val spill = TempDirRe.findFirstMatchIn(sql).map(_.group(1)).getOrElse(fail(sql))
    Paths.get(spill).isAbsolute shouldBe true
    // Per node: never the shared default `.tmp` every node of the host would write to.
    Paths.get(spill).getFileName.toString should not be ".tmp"
    sql.indexOf("SET temp_directory") should be < sql.indexOf("SELECT 'db-init';")
  }

  it should "remove the per-node directories when the script exits" in {
    val tmp   = dir("tmpdir")
    val sql   = dryRun(dir("start"), (memory :+ ("TMPDIR" -> tmp.toString))*)
    val spill = TempDirRe.findFirstMatchIn(sql).map(_.group(1)).getOrElse(fail(sql))
    Files.exists(Paths.get(spill)) shouldBe false
    Files.list(tmp).iterator().asScala.toList shouldBe Nil
  }

  it should "resolve a relative ducklake dataPath against the starting directory" in {
    val start = dir("start")
    val sql   = dryRun(start, ducklake("./lake/acme")*)
    val abs   = start.toRealPath().resolve("lake/acme").toString
    sql should include(s"(DATA_PATH '$abs', OVERRIDE_DATA_PATH true, ")
    Files.isDirectory(start.resolve("lake/acme")) shouldBe true
  }

  it should "leave an absolute ducklake dataPath as is, with no data path override" in {
    val abs = tempDir.resolve("lake").toString
    val sql = dryRun(dir("start"), ducklake(abs)*)
    sql should include(s"(DATA_PATH '$abs', METADATA_PARAMETERS")
    sql should not include "OVERRIDE_DATA_PATH"
  }

  it should "resolve a relative duckdb-file dataPath against the starting directory" in {
    val start = dir("start")
    val sql   = dryRun(
      start,
      "kind"         -> "duckdb-file",
      "schemaName"   -> "main",
      "catalogAlias" -> "acme_file",
      "dbName"       -> "acme_file",
      "dataPath"     -> "files/acme.duckdb"
    )
    sql should include(
      s"ATTACH '${start.toRealPath().resolve("files/acme.duckdb")}' AS \"acme_file\";"
    )
  }

  /** A stand-in qod-node that records where it runs, its arguments and its init SQL (`--init -`:
    * stdin), then blocks until SIGTERM like the real node.
    */
  private def fakeQodNode(at: Path, probe: Path): Path =
    Files.createDirectories(at.getParent)
    Files.writeString(
      at,
      s"""#!/bin/sh
         |printf '%s\\n' "$$@" > '$probe/args'
         |pwd -P > '$probe/cwd.part'
         |ls -A | wc -l | tr -d ' ' > '$probe/entries'
         |(stat -f %Lp . 2>/dev/null || stat -c %a .) > '$probe/mode'
         |mv '$probe/cwd.part' '$probe/cwd'
         |cat > '$probe/init'
         |trap 'exit 143' TERM INT
         |while :; do sleep 1; done
         |""".stripMargin
    )
    Files.setPosixFilePermissions(at, PosixFilePermissions.fromString("rwx------"))
    at

  private def awaitFile(f: Path, timeoutMs: Long = 20000): Unit =
    val deadline = System.currentTimeMillis() + timeoutMs
    while !Files.exists(f) && System.currentTimeMillis() < deadline do Thread.sleep(50)
    withClue(s"$f never appeared")(Files.exists(f) shouldBe true)

  private def stop(p: java.lang.Process): Unit =
    p.destroy()
    if !p.waitFor(20, TimeUnit.SECONDS) then p.destroyForcibly()

  private def awaitContains(f: Path, s: String): String =
    val deadline = System.currentTimeMillis() + 20000
    var text     = ""
    while { text = Files.readString(f); !text.contains(s) } && System.currentTimeMillis() < deadline
    do Thread.sleep(50)
    text

  "a node" should "run qod-node in an empty private directory that is removed when it stops" in {
    val start = dir("start")
    Files.writeString(start.resolve("x.csv"), "a,b\n1,2\n")
    val probe = dir("probe")
    val bin   = fakeQodNode(tempDir.resolve("bin/qod-node"), probe)
    val p     = launch(start, memory ++ Seq("QOD_NODE_BIN" -> bin.toString))
    try
      awaitFile(probe.resolve("cwd"))
      val cwd = Paths.get(Files.readString(probe.resolve("cwd")).trim)
      cwd should not be start.toRealPath()
      Files.readString(probe.resolve("entries")).trim shouldBe "0"
      Files.readString(probe.resolve("mode")).trim shouldBe "700"
      awaitFile(probe.resolve("init"))
      val sql   = awaitContains(probe.resolve("init"), "SET temp_directory")
      val spill = TempDirRe.findFirstMatchIn(sql).map(_.group(1)).getOrElse(fail(sql))
      Files.isDirectory(Paths.get(spill)) shouldBe true
      stop(p)
      Files.exists(cwd) shouldBe false
      Files.exists(Paths.get(spill)) shouldBe false
    finally stop(p)
  }

  it should "hand qod-node the port, bind, secret directory, its own pid and the init on stdin" in {
    val start = dir("start")
    val probe = dir("probe")
    val bin   = fakeQodNode(tempDir.resolve("bin/qod-node"), probe)
    val p     =
      launch(start, memory ++ Seq("QOD_NODE_BIN" -> bin.toString, "QOD_NODE_BIND" -> "127.0.0.1"))
    try
      awaitFile(probe.resolve("cwd"))
      val args  = Files.readAllLines(probe.resolve("args")).asScala.toList
      val pairs = args.sliding(2).collect { case List(k, v) if k.startsWith("--") => k -> v }.toMap
      pairs("--port") shouldBe "23351"
      pairs("--bind") shouldBe "127.0.0.1"
      pairs("--secret-directory") should endWith("/secrets/none/s")
      pairs("--init") shouldBe "-"
      pairs("--parent-pid").toLong shouldBe p.pid()
      args should contain("--secrets-ephemeral")
    finally stop(p)
  }

  it should "resolve a relative QOD_NODE_BIN before moving qod-node into its own directory" in {
    val start = dir("start")
    val probe = dir("probe")
    fakeQodNode(start.resolve("bin/qod-node"), probe)
    val p = launch(start, memory ++ Seq("QOD_NODE_BIN" -> "bin/qod-node"))
    try awaitFile(probe.resolve("cwd"))
    finally stop(p)
  }

  /** The DuckDB CLI this build pins (the control query below): `DUCKDB_BIN`, else the
    * `.duckdb/<abi>` cache build.sbt points forked tests at.
    */
  private lazy val pinnedDuckdb: Option[Path] =
    val abi = BuildVersions.duckdb.split('.').take(3).mkString(".")
    sys.env
      .get("DUCKDB_BIN")
      .filter(_.nonEmpty)
      .map(Paths.get(_))
      .orElse(sys.env.get("DUCKDB_CACHE_DIR").map(d => Paths.get(d, abi, "bin", "duckdb")))
      .filter(Files.isExecutable(_))

  it should "keep a file of its starting directory out of reach of an unquoted dotted name" in {
    assume(pinnedDuckdb.nonEmpty, "the pinned DuckDB CLI is not available")
    assume(QodNodeTestBinary.path.nonEmpty, "no qod-node binary (QOD_NODE_BIN or a local build)")
    val duckdb = pinnedDuckdb.get.toString
    val start  = dir("start")
    Files.writeString(start.resolve("x.csv"), "a,b\n1,2\n")
    val probe = dir("probe")
    // Control: from that directory, the same name does read the file.
    Process(
      Seq(duckdb, "-csv", "-noheader", "-c", "SELECT a FROM x.csv"),
      start.toFile,
      "DUCKDB_AGENT_MODE" -> "0"
    ).!!.trim shouldBe "1"
    val p = launch(
      start,
      memory ++ Seq(
        "QOD_NODE_BIN"  -> QodNodeTestBinary.path.get.toString,
        "extraSetupSql" ->
          (s"COPY (SELECT * FROM x.csv) TO '$probe/leak.csv';\n" +
            s"COPY (SELECT count(*) AS n FROM glob('*')) TO '$probe/done.csv';")
      )
    )
    try
      awaitFile(probe.resolve("done.csv"))
      Files.exists(probe.resolve("leak.csv")) shouldBe false
      Files.readString(probe.resolve("done.csv")).linesIterator.toList.last.trim shouldBe "0"
    finally stop(p)
  }

// PowerShell twin. scripts/spawn-quack-node.ps1 runs on Windows only; elsewhere these report
// CANCELED via `assume`.
class SpawnScriptWorkDirWindowsSpec extends AnyFlatSpec with Matchers with SpawnScriptTempDir:

  private val isWindows = System.getProperty("os.name").toLowerCase.contains("win")

  private val TempDirRe = """(?m)^SET temp_directory = '([^']+)';\r?$""".r

  private def dryRun(cwd: Path, env: (String, String)*): String =
    val dir = NodeSecretFiles.writePrivateDir(
      Map(NodeSecretFiles.TokenKey -> "tok-workdir", "pgPassword" -> "p"),
      Some(tempDir)
    )
    val ps1 = Paths.get("scripts/spawn-quack-node.ps1").toAbsolutePath.toString
    Process(
      Seq("powershell.exe", "-File", ps1, "21999"),
      cwd.toFile,
      (env ++ Seq(
        "QOD_SPAWN_DRY_RUN"          -> "1",
        NodeSecretFiles.DirEnv       -> dir.toString,
        NodeSecretFiles.EphemeralEnv -> "1"
      ))*
    ).!!

  "the PowerShell node init SQL" should "spill to an absolute per-node directory removed on exit" in {
    assume(isWindows, "spawn-quack-node.ps1 runs on Windows only")
    val sql   = dryRun(Files.createDirectories(tempDir.resolve("start")), "kind" -> "memory")
    val spill = TempDirRe.findFirstMatchIn(sql).map(_.group(1)).getOrElse(fail(sql))
    Paths.get(spill).isAbsolute shouldBe true
    Files.exists(Paths.get(spill)) shouldBe false
  }

  it should "resolve a relative ducklake dataPath against the starting directory" in {
    assume(isWindows, "spawn-quack-node.ps1 runs on Windows only")
    val start = Files.createDirectories(tempDir.resolve("start"))
    val sql   = dryRun(
      start,
      "kind"         -> "ducklake",
      "schemaName"   -> "main",
      "catalogAlias" -> "acme_lake",
      "dbName"       -> "acme_lake",
      "dataPath"     -> "lake\\acme",
      "pgHost"       -> "h",
      "pgPort"       -> "5432",
      "pgUser"       -> "u"
    )
    sql should include(
      s"(DATA_PATH '${start.toRealPath().resolve("lake\\acme")}', OVERRIDE_DATA_PATH true, "
    )
  }
