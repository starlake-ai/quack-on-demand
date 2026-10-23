package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.BuildVersions
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import scala.sys.process.*
import scala.util.Try

/** The PowerShell spawn script, run through PowerShell on any OS (pwsh off Windows): the init SQL
  * it hands qod-node on stdin, the arguments it passes, the secrets it leaves, and the node
  * directories its sweep removes. A stand-in qod-node records what it is given (off Windows); the
  * real one serves a table end to end when a local build and the pinned DuckDB CLI are present.
  * Cancelled without PowerShell.
  */
class SpawnScriptWindowsInitSpec extends AnyFlatSpec with Matchers with SpawnScriptTempDir:

  private val ps1 = Paths.get("scripts/spawn-quack-node.ps1").toAbsolutePath.toString

  private lazy val pinnedDuckdb: Option[Path] =
    val abi = BuildVersions.duckdb.split('.').take(3).mkString(".")
    sys.env
      .get("DUCKDB_BIN")
      .filter(_.nonEmpty)
      .map(Paths.get(_))
      .orElse(sys.env.get("DUCKDB_CACHE_DIR").map(d => Paths.get(d, abi, "bin", "duckdb")))
      .filter(Files.isExecutable(_))

  /** The environment of a spawn with its node directories under `tmp` and its secrets in a fresh
    * private directory, returned with it.
    */
  private def spawnEnv(
      tmp: Path,
      secrets: Map[String, String] = Map.empty,
      extraEnv: Seq[(String, String)] = Nil
  ): (Seq[(String, String)], Path) =
    val dir = NodeSecretFiles.writePrivateDir(
      Map(NodeSecretFiles.TokenKey -> "tok-ps1-7a01") ++ secrets,
      Some(tempDir)
    )
    val env = Seq(
      "TMPDIR"                     -> tmp.toString,
      "TMP"                        -> tmp.toString,
      "TEMP"                       -> tmp.toString,
      "kind"                       -> "memory",
      "dbName"                     -> "m",
      "schemaName"                 -> "main",
      NodeSecretFiles.DirEnv       -> dir.toString,
      NodeSecretFiles.EphemeralEnv -> "1"
    ) ++ extraEnv
    (env, dir)

  /** Dry run of the ps1 from `start`; returns (exit code, stdout, stderr) and the secrets dir. */
  private def dryRun(
      tmp: Path,
      start: Path = tempDir.resolve("start"),
      secrets: Map[String, String] = Map.empty,
      extraEnv: Seq[(String, String)] = Nil
  ): ((Int, String, String), Path) =
    assume(PowerShell.command.nonEmpty, "no PowerShell on this host")
    val (env, dir) = spawnEnv(tmp, secrets, Seq("QOD_SPAWN_DRY_RUN" -> "1") ++ extraEnv)
    val out        = new StringBuilder
    val err        = new StringBuilder
    val rc         = Process(
      PowerShell.command.get ++ Seq("-File", ps1, "21999"),
      Files.createDirectories(start).toFile,
      env*
    ) ! ProcessLogger(l => out.append(l).append('\n'), l => err.append(l).append('\n'))
    ((rc, out.toString, err.toString), dir)

  /** The node directories under `tmp` (pwsh keeps its own diagnostics files in the temp root). */
  private def nodeDirs(tmp: Path): List[Path] =
    Files
      .list(tmp)
      .iterator()
      .asScala
      .filter(_.getFileName.toString.startsWith("quack-node."))
      .toList

  private def deadPid(): Long =
    val dead = new java.lang.ProcessBuilder("true").start()
    dead.waitFor()
    dead.pid()

  "the PowerShell node init SQL" should "leave the secret directory, quack and serving to qod-node" in {
    val tmp                   = Files.createDirectories(tempDir.resolve("t"))
    val ((rc, out, err), dir) =
      dryRun(tmp, extraEnv = Seq("dbInitSql" -> "SELECT 'db-init';"))
    withClue(err)(rc shouldBe 0)
    val lines = out.linesIterator.map(_.trim).filter(_.nonEmpty).toVector
    lines.head should startWith("SET temp_directory = '")
    lines should contain("SELECT 'db-init';")
    lines.exists(_.contains("secret_directory")) shouldBe false
    lines.exists(_.contains("quack_serve")) shouldBe false
    lines.exists(_.contains("LOAD quack")) shouldBe false
    // qod-node is no CLI: nothing in its init SQL is a dot-command, and the script writes none.
    lines.exists(_.startsWith(".")) shouldBe false
    out should not include "tok-ps1-7a01"
    // The dry run removes its node directory and the secrets directory it never handed over.
    nodeDirs(tmp) shouldBe Nil
    Files.exists(dir) shouldBe false
  }

  // A node whose script was killed leaves its read-only secret directory; the next spawn's sweep
  // gives it back to the current user before removing it (chmod 700 here, the ACL on Windows), and
  // says so on stderr when a node directory cannot be removed.
  "a later PowerShell spawn" should "sweep a dead node's read-only secret directory" in {
    assume(!scala.util.Properties.isWin, "checks the POSIX branch; the ACL branch runs on Windows")
    val tmp   = Files.createDirectories(tempDir.resolve("t"))
    val stale = Files.createDirectories(tmp.resolve("quack-node.deadbeef0001"))
    val sec   = Files.createDirectories(stale.resolve("secrets"))
    Files.writeString(stale.resolve("owner.pid"), s"${deadPid()} 0")
    Files.createFile(sec.resolve("none"))
    Files.setPosixFilePermissions(sec.resolve("none"), PosixFilePermissions.fromString("r--------"))
    Files.setPosixFilePermissions(sec, PosixFilePermissions.fromString("r-x------"))
    val ((rc, _, err), _) = dryRun(tmp)
    withClue(err)(rc shouldBe 0)
    Files.exists(stale) shouldBe false
    err should not include "could not remove"
  }

  // F06: off Windows the temp root is often the shared /tmp, so the sweep must not follow a planted
  // symlink nor delete a spill path a node directory merely names.
  it should "sweep only real directories and spill directories it made" in {
    assume(!scala.util.Properties.isWin, "symlinks need privileges on Windows")
    val tmp       = Files.createDirectories(tempDir.resolve("t"))
    val spillRoot = Files.createDirectories(tempDir.resolve("start").resolve(".tmp"))
    // Spill paths are written physical, as the script itself writes them (its starting directory is
    // the process's getcwd, e.g. /private/var/... for a macOS temp dir).
    // A symlinked entry pointing at a directory that looks stale, naming a real spill directory.
    val victim = Files.createDirectories(tempDir.resolve("victim"))
    Files.writeString(victim.resolve("owner.pid"), s"${deadPid()} 0")
    val linkedSpill = Files.createDirectories(spillRoot.resolve("qod-node.aaaaaaaaaaaa"))
    Files.writeString(victim.resolve("spill.path"), linkedSpill.toRealPath().toString)
    Files.createSymbolicLink(tmp.resolve("quack-node.deadbeef0002"), victim)
    // A stale directory naming a path outside every spill root.
    val planted = Files.createDirectories(tmp.resolve("quack-node.deadbeef0003"))
    Files.writeString(planted.resolve("owner.pid"), s"${deadPid()} 0")
    val outside = Files.createDirectories(tempDir.resolve("data").resolve("qod-node.bbbbbbbbbbbb"))
    Files.writeString(planted.resolve("spill.path"), outside.toRealPath().toString)
    // A stale directory of this host with its own spill directory.
    val real = Files.createDirectories(tmp.resolve("quack-node.deadbeef0004"))
    Files.writeString(real.resolve("owner.pid"), s"${deadPid()} 0")
    val realSpill = Files.createDirectories(spillRoot.resolve("qod-node.cccccccccccc"))
    Files.writeString(real.resolve("spill.path"), realSpill.toRealPath().toString)
    val ((rc, _, err), _) = dryRun(tmp)
    withClue(err)(rc shouldBe 0)
    Files.exists(victim.resolve("owner.pid")) shouldBe true
    Files.exists(linkedSpill) shouldBe true
    Files.exists(planted) shouldBe false
    Files.exists(outside) shouldBe true
    Files.exists(real) shouldBe false
    Files.exists(realSpill) shouldBe false
  }

  /** A stand-in qod-node (a POSIX shell script) that records its arguments, its environment, what
    * its secrets directory holds and its stdin, then exits.
    */
  private def fakeQodNode(probe: Path): Path =
    val at = tempDir.resolve("bin/qod-node")
    Files.createDirectories(at.getParent)
    Files.writeString(
      at,
      s"""#!/bin/sh
         |printf '%s\\n' "$$@" > '$probe/args'
         |env > '$probe/env'
         |prev=""
         |for a in "$$@"; do
         |  if [ "$$prev" = --secrets-dir ]; then ls -A "$$a" > '$probe/secrets'; fi
         |  prev="$$a"
         |done
         |cat > '$probe/init.part'
         |mv '$probe/init.part' '$probe/init'
         |sleep 0.5
         |""".stripMargin
    )
    Files.setPosixFilePermissions(at, PosixFilePermissions.fromString("rwx------"))
    at

  /** Runs the ps1 for real from `start` with qod-node `bin`; returns the started process. */
  private def launch(
      start: Path,
      bin: Path,
      port: Int,
      secrets: Map[String, String] = Map.empty,
      extraEnv: Seq[(String, String)] = Nil
  ): (java.lang.Process, Path, Path) =
    assume(PowerShell.command.nonEmpty, "no PowerShell on this host")
    val tmp        = Files.createDirectories(tempDir.resolve("t"))
    val (env, dir) = spawnEnv(tmp, secrets, Seq("QOD_NODE_BIN" -> bin.toString) ++ extraEnv)
    val pb         = new java.lang.ProcessBuilder(
      (PowerShell.command.get ++ Seq("-File", ps1, port.toString)).asJava
    ).directory(Files.createDirectories(start).toFile)
    pb.environment().putAll(env.toMap.asJava)
    pb.redirectErrorStream(true)
    (pb.start(), tmp, dir)

  "the PowerShell spawn" should "hand qod-node its arguments and the init SQL on stdin, as UTF-8" in {
    assume(!scala.util.Properties.isWin, "the stand-in qod-node is a shell script")
    val probe = Files.createDirectories(tempDir.resolve("probe"))
    // A quote, a blank and non-ASCII letters: the path reaches qod-node in an argument and the SQL.
    val start             = tempDir.resolve("Hélène o'brien t€mp")
    val (p, tmp, secrets) = launch(
      start,
      fakeQodNode(probe),
      23961,
      secrets = Map("pgPassword" -> "pw-ps1-9b", "extraSetupSql" -> "SELECT 'fed-ps1';")
    )
    val out = new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    p.waitFor(60, TimeUnit.SECONDS) shouldBe true
    // The stand-in ends on its own while the wrapper is alive, which the wrapper reports.
    withClue(out)(p.exitValue() shouldBe 1)
    out should include("while its wrapper was still running")
    val args  = Files.readAllLines(probe.resolve("args"), StandardCharsets.UTF_8).asScala.toList
    val pairs = args.sliding(2).collect { case List(k, v) if k.startsWith("--") => k -> v }.toMap
    pairs("--port") shouldBe "23961"
    pairs("--bind") shouldBe "0.0.0.0"
    pairs("--secrets-dir") shouldBe secrets.toString
    pairs("--parent-pid").toLong shouldBe p.pid()
    pairs("--init") shouldBe "-"
    args should contain("--secrets-ephemeral")
    val secretPath = Paths.get(pairs("--secret-directory"))
    secretPath.isAbsolute shouldBe true
    secretPath.toString should endWith("/secrets/none/s")
    secretPath.getParent.getParent.getParent.getFileName.toString should startWith("quack-node.")
    // The script read and deleted the secrets it uses; the token is qod-node's, and the directory
    // is gone once the node is.
    Files.readAllLines(probe.resolve("secrets")).asScala.toList shouldBe
      List(NodeSecretFiles.TokenKey)
    Files.exists(secrets) shouldBe false
    // The init SQL arrived on stdin as UTF-8, the starting directory's quote doubled.
    val sql   = Files.readString(probe.resolve("init"), StandardCharsets.UTF_8)
    val spill = start.toRealPath().resolve(".tmp").toString.replace("'", "''")
    sql should include(s"SET temp_directory = '$spill/qod-node.")
    sql should include("SELECT 'fed-ps1';")
    // No secret in an argument or the environment of the node. The checks never print the
    // environment, which holds whatever this test JVM inherited.
    val env   = Files.readString(probe.resolve("env"), StandardCharsets.UTF_8)
    val names = env.linesIterator.map(_.takeWhile(_ != '=')).toSet
    Seq("tok-ps1-7a01", "pw-ps1-9b").foreach { v =>
      withClue(s"$v in the node's arguments")(args.exists(_.contains(v)) shouldBe false)
      withClue(s"$v in the node's environment")(env.contains(v) shouldBe false)
    }
    (NodeSecretFiles.Keys :+ NodeSecretFiles.DirEnv :+ NodeSecretFiles.EphemeralEnv).foreach { k =>
      withClue(s"$k in the node's environment")(names.contains(k) shouldBe false)
    }
    // The node directory goes with the node.
    nodeDirs(tmp) shouldBe Nil
  }

  it should "refuse to start without qod-node" in {
    assume(PowerShell.command.nonEmpty, "no PowerShell on this host")
    val (p, _, secrets) =
      launch(tempDir.resolve("start"), tempDir.resolve("no/such/qod-node"), 23962)
    val out = new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    p.waitFor(60, TimeUnit.SECONDS) shouldBe true
    p.exitValue() shouldBe 1
    out should include("ERROR: qod-node not found")
    Files.exists(secrets) shouldBe false
  }

  "a PowerShell node" should "serve its init tables over quack with the real qod-node" in {
    assume(QodNodeTestBinary.path.nonEmpty, "no qod-node binary (QOD_NODE_BIN or a local build)")
    assume(pinnedDuckdb.nonEmpty, "the pinned DuckDB CLI is not available")
    val port            = 23963
    val (p, _, secrets) = launch(
      tempDir.resolve("start"),
      QodNodeTestBinary.path.get,
      port,
      secrets = Map("extraSetupSql" -> "CREATE TABLE fed AS SELECT 'fed-served' AS v;"),
      extraEnv = Seq("QOD_NODE_BIND" -> "127.0.0.1")
    )
    try
      val deadline        = System.currentTimeMillis() + 60000
      def query(): String =
        Process(
          Seq(pinnedDuckdb.get.toString, "-noheader", "-list"),
          tempDir.toFile,
          "DUCKDB_AGENT_MODE" -> "0",
          "HOME"              -> Files.createDirectories(tempDir.resolve("home")).toString
        ).#<(
          new java.io.ByteArrayInputStream(
            (s"INSTALL quack;\nLOAD quack;\nATTACH 'quack:127.0.0.1:$port' AS q " +
              s"(TYPE quack, TOKEN 'tok-ps1-7a01');\n" +
              "FROM quack_query_by_name('q', 'SELECT v FROM memory.main.fed');\n")
              .getBytes(StandardCharsets.UTF_8)
          )
        ).lazyLines_!(ProcessLogger(_ => ()))
          .mkString("\n")
      var got = ""
      while { got = Try(query()).getOrElse(""); !got.contains("fed-served") } &&
        System.currentTimeMillis() < deadline
      do Thread.sleep(500)
      got should include("fed-served")
      // qod-node read the token and removed the secrets directory as it started.
      Files.exists(secrets) shouldBe false
    finally
      Try(p.descendants().forEach(d => d.destroyForcibly()))
      p.destroyForcibly()
      p.waitFor(20, TimeUnit.SECONDS)
  }
