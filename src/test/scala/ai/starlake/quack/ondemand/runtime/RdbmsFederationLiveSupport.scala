package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.BuildVersions
import ai.starlake.quack.model.{FederatedSecret, FederatedSource}
import ai.starlake.quack.ondemand.federation.{FederationBlobBuilder, PostgresSecretResolver}
import cats.effect.IO
import cats.effect.unsafe.implicits.global

import java.net.{InetSocketAddress, Socket}
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import scala.sys.process.*
import scala.util.Try

/** A `memory` node spawned by the real `spawn-quack-node.sh` with qod-node, its federation blob
  * produced by the real [[FederationBlobBuilder]] from typed sources, and queried over its own
  * quack endpoint from another process, the way the manager reaches it. Shared by the Postgres and
  * MySQL live specs.
  */
private[runtime] final class RdbmsFederationLiveNode(tempDir: Path):

  val Token: String = "tok-rdbms-fed-5e21"

  private val script = Paths.get("scripts/spawn-quack-node.sh").toAbsolutePath.toString

  private var process: Option[java.lang.Process] = None
  private var nodePort: Int                      = 0

  def port: Int = nodePort

  def log: String = Try(Files.readString(tempDir.resolve("node.log"))).getOrElse("")

  /** The resolved federation blob for `sources`, exactly what the manager ships as `extraSetupSql`.
    */
  def blob(sources: List[FederatedSource], secrets: List[FederatedSecret]): String =
    new FederationBlobBuilder(
      loadEnabled = _ => IO.pure(sources),
      loadSecrets = id => IO.pure(secrets.filter(_.federatedSourceId == id)),
      resolver = new PostgresSecretResolver(),
      catalogAliasOf = _ => IO.pure(None)
    ).build("td-live").unsafeRunSync().getOrElse("")

  def start(extraSetupSql: String): Unit =
    nodePort = RdbmsFederationLiveNode.freePort()
    val secrets = NodeSecretFiles.writePrivateDir(
      Map(NodeSecretFiles.TokenKey -> Token, "extraSetupSql" -> extraSetupSql),
      Some(tempDir)
    )
    val pb = new java.lang.ProcessBuilder("bash", script, nodePort.toString)
      .directory(Files.createDirectories(tempDir.resolve("start")).toFile)
      .redirectErrorStream(true)
      .redirectOutput(tempDir.resolve("node.log").toFile)
    pb.environment()
      .putAll(
        Map(
          "kind"                 -> "memory",
          "schemaName"           -> "main",
          "dbName"               -> "acme_mem",
          "QOD_NODE_BIN"         -> QodNodeTestBinary.path.get.toString,
          "QOD_NODE_BIND"        -> "127.0.0.1",
          "HOME"                 -> Files.createDirectories(tempDir.resolve("node-home")).toString,
          NodeSecretFiles.DirEnv -> secrets.toString,
          NodeSecretFiles.EphemeralEnv -> "1"
        ).asJava
      )
    val p = pb.start()
    process = Some(p)
    val deadline = System.currentTimeMillis() + 60000
    while !RdbmsFederationLiveNode.listening(nodePort) && p.isAlive &&
      System.currentTimeMillis() < deadline
    do Thread.sleep(100)
    if !RdbmsFederationLiveNode.listening(nodePort) then
      throw new IllegalStateException(s"node did not come up:\n$log")

  /** SIGKILL the wrapper and every qod-node it started, so a run can never leave an orphan. */
  def stop(): Unit =
    process.foreach { p =>
      Try(p.descendants().forEach(d => d.destroyForcibly()))
      p.destroyForcibly()
      p.waitFor(20, TimeUnit.SECONDS)
    }
    process = None

  /** `sql` on the node through `quack_query` from a separate DuckDB process: (exit code, CSV rows,
    * stderr).
    */
  def run(sql: String): (Int, List[String], String) =
    val inner = sql.replace("'", "''")
    val out   = new StringBuilder
    val err   = new StringBuilder
    val rc    = Process(
      Seq(
        RdbmsFederationLiveNode.pinnedDuckdb.get.toString,
        "-csv",
        "-noheader",
        "-c",
        s"SELECT * FROM quack_query('quack:127.0.0.1:$nodePort', '$inner', token := '$Token');"
      ),
      tempDir.toFile,
      "DUCKDB_AGENT_MODE" -> "0",
      "HOME"              -> Files.createDirectories(tempDir.resolve("client-home")).toString
    ) ! ProcessLogger(l => out.append(l).append('\n'), l => err.append(l).append('\n'))
    (rc, out.toString.linesIterator.toList, err.toString)

private[runtime] object RdbmsFederationLiveNode:

  /** The pinned DuckDB CLI (the quack client, and the extension probe below), present only when a
    * qod-node binary is too: these specs need both, so one check covers them.
    */
  lazy val pinnedDuckdb: Option[Path] =
    val abi = BuildVersions.duckdb.split('.').take(3).mkString(".")
    sys.env
      .get("DUCKDB_BIN")
      .filter(_.nonEmpty)
      .map(Paths.get(_))
      .orElse(sys.env.get("DUCKDB_CACHE_DIR").map(d => Paths.get(d, abi, "bin", "duckdb")))
      .filter(Files.isExecutable(_))
      .filter(_ => QodNodeTestBinary.path.nonEmpty)

  /** Whether the pinned CLI can load `extension` (statically linked, or installable). */
  def canLoad(extension: String): Boolean =
    pinnedDuckdb.exists { bin =>
      val home = Files.createTempDirectory("rdbms-ext-probe")
      try
        Process(
          Seq(bin.toString, "-c", s"INSTALL $extension; LOAD $extension; SELECT 42;"),
          home.toFile,
          "DUCKDB_AGENT_MODE" -> "0",
          "HOME"              -> home.toString
        ).!(ProcessLogger(_ => (), _ => ())) == 0
      finally Try(Files.walk(home).iterator().asScala.toSeq.reverse.foreach(Files.deleteIfExists))
    }

  def freePort(): Int =
    val s = new java.net.ServerSocket(0)
    try s.getLocalPort
    finally s.close()

  def listening(port: Int): Boolean =
    Try {
      val s = new Socket()
      try s.connect(new InetSocketAddress("127.0.0.1", port), 200)
      finally s.close()
    }.isSuccess
