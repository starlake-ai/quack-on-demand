package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.BuildVersions
import ai.starlake.quack.edge.adapter.QuackNativeSupport
import com.typesafe.scalalogging.LazyLogging

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption}
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import scala.util.{Try, Using}

/** The qod-node executable a local node runs (native/qod-node); the spawn scripts get it as
  * `QOD_NODE_BIN`.
  *
  * `QOD_NODE_BIN` set on the manager wins: a local build, or on Windows the static `qod-node.exe`
  * the launchers download (too large to bundle). Otherwise the binary bundled for this platform
  * (build.sbt copies `native/qod-node/binaries/<platform>/qod-node` to `/qod-node/<platform>/`;
  * Linux and macOS) is installed as `<root>/<sha12>/bin/qod-node`, `<sha12>` naming its content so
  * managers of different builds never replace each other's binary under a running node. It links
  * libduckdb dynamically through the relative rpath `../lib`, and `<root>/<sha12>/lib` links to the
  * directory of the libduckdb this manager loaded (with none, as in an image that installs
  * libduckdb in /usr/local/lib, the loader path decides). `root` is `qod-node/` beside that `lib/`
  * (the launchers' DuckDB cache), else a fresh private directory under the temp root, removed on
  * exit.
  *
  * Either way the binary must answer `--build-info` with this build's node protocol and DuckDB
  * release, which also proves it starts here: a shared build resolves libduckdb before its main
  * runs, and checks that it is the release it was built against.
  */
object QodNodeBinary extends LazyLogging:

  /** The node protocol this manager speaks (`QOD_NODE_PROTOCOL` in native/qod-node/CMakeLists.txt).
    */
  val Protocol: Int = 3

  val EnvVar: String = "QOD_NODE_BIN"

  /** What a binary says it was built against (its `--build-info` marker). */
  final case class BuildInfo(protocol: Int, duckdb: String, duckdbSource: String)

  private val Marker =
    """qod-node-build-info:protocol=(\d+);duckdb=([^;\s]+);duckdb_source=([0-9a-f]+)""".r

  def parseMarker(text: String): Option[BuildInfo] =
    Marker.findFirstMatchIn(text).map(m => BuildInfo(m.group(1).toInt, m.group(2), m.group(3)))

  /** The DuckDB release a qod-node of this build links, as its marker spells it: `v<x.y.z>`. */
  val expectedDuckdb: String = "v" + BuildVersions.duckdb.split('.').take(3).mkString(".")

  /** Resolved once per JVM: Main forces it at boot whenever this manager runs local nodes. */
  lazy val default: Either[String, Path] =
    resolve(sys.env, QuackNativeSupport.platform(), QuackNativeSupport.libDuckDbDir)

  /** The checked binary, or why there is none. Every collaborator is injectable for tests. */
  def resolve(
      env: Map[String, String],
      platform: Option[String],
      libDuckDbDir: Option[Path],
      tempRoot: => Path = Path.of(sys.props("java.io.tmpdir")),
      bundled: String => Option[Array[Byte]] = bundledBytes,
      buildInfo: Path => Either[String, String] = runBuildInfo
  ): Either[String, Path] =
    val located = env.get(EnvVar).map(_.trim).filter(_.nonEmpty) match
      case Some(explicit) =>
        val p = Path.of(explicit).toAbsolutePath
        if Files.isRegularFile(p) && Files.isExecutable(p) then Right(p)
        else Left(s"$EnvVar=$explicit is not an executable file")
      case None =>
        platform.flatMap(bundled) match
          case Some(bytes) => install(bytes, libDuckDbDir, tempRoot)
          case None        =>
            Left(
              s"no qod-node is bundled for ${platform.getOrElse("this platform")}: this build " +
                "bundles it for Linux and macOS (x86_64 and aarch64). Set " +
                s"$EnvVar to a qod-node executable built against DuckDB $expectedDuckdb (the " +
                "Windows launchers download qod-node.exe and set it)."
            )
    located.flatMap(check(_, buildInfo))

  /** The bundled binary for `platform`, None when this jar has none. */
  def bundledBytes(platform: String): Option[Array[Byte]] =
    Option(getClass.getResourceAsStream(s"/qod-node/$platform/qod-node"))
      .map(in => Using.resource(in)(_.readAllBytes()))

  /** Installs `bytes` as `<root>/<sha12>/bin/qod-node` (see the object comment) and returns it. */
  private[runtime] def install(
      bytes: Array[Byte],
      libDuckDbDir: Option[Path],
      tempRoot: => Path
  ): Either[String, Path] =
    val sha12 = sha256Hex(bytes).take(12)
    val root  = libDuckDbDir.map(_.getParent.resolve("qod-node")).filter(usable).getOrElse {
      val dir = Files.createTempDirectory(tempRoot, "qod-node-")
      sys.addShutdownHook(deleteTree(dir))
      dir
    }
    val dir = root.resolve(sha12)
    val bin = dir.resolve("bin").resolve("qod-node")
    Try {
      Files.createDirectories(bin.getParent)
      if !(Files.isRegularFile(bin) && sha256Hex(Files.readAllBytes(bin)) == sha256Hex(bytes)) then
        // Written aside and renamed into place: a node may be running the binary already there.
        val tmp = Files.createTempFile(bin.getParent, ".qod-node-", ".tmp")
        Files.write(tmp, bytes)
        Files.move(tmp, bin, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      Files.setPosixFilePermissions(bin, PosixFilePermissions.fromString("rwx------"))
      libDuckDbDir.foreach(linkLib(dir, _))
      bin
    }.toEither.left.map(e => s"could not install the bundled qod-node in $dir: ${e.getMessage}")

  /** Points `<dir>/lib` at `target`, replacing a link to anywhere else in one rename. */
  private def linkLib(dir: Path, target: Path): Unit =
    val link    = dir.resolve("lib")
    val current = Try(Files.readSymbolicLink(link)).toOption
    if !current.contains(target) then
      val tmp = dir.resolve(s".lib-${ProcessHandle.current().pid()}")
      Files.deleteIfExists(tmp)
      Files.createSymbolicLink(tmp, target)
      Files.move(tmp, link, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

  /** A directory this process can create files in, never a symlink. */
  private def usable(dir: Path): Boolean =
    Try {
      Files.createDirectories(dir)
      Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) && Files.isWritable(dir)
    }.getOrElse(false)

  /** Right(bin) when its `--build-info` names this manager's node protocol and DuckDB release. */
  def check(bin: Path, buildInfo: Path => Either[String, String]): Either[String, Path] =
    buildInfo(bin).flatMap { out =>
      parseMarker(out) match
        case None =>
          Left(s"$bin --build-info printed no qod-node build marker: ${out.trim.take(300)}")
        case Some(info) if info.protocol != Protocol =>
          Left(
            s"$bin speaks node protocol ${info.protocol}; this manager speaks $Protocol. Use the " +
              "qod-node of this release."
          )
        case Some(info) if info.duckdb != expectedDuckdb =>
          Left(
            s"$bin is built against DuckDB ${info.duckdb}; this manager pins $expectedDuckdb. " +
              "Use the qod-node of this release."
          )
        case Some(info) =>
          logger.info(
            s"local nodes run $bin (node protocol ${info.protocol}, DuckDB ${info.duckdb} " +
              s"${info.duckdbSource})"
          )
          Right(bin)
    }

  /** `<bin> --build-info` within 10 s: its output, or why it did not answer. A shared build that
    * cannot load libduckdb, or loads another release, fails here.
    */
  def runBuildInfo(bin: Path): Either[String, String] =
    try
      val out = Files.createTempFile("qod-node-build-info-", ".txt")
      try
        val p = new ProcessBuilder(bin.toString, "--build-info")
          .redirectErrorStream(true)
          .redirectOutput(out.toFile)
          .redirectInput(ProcessBuilder.Redirect.from(java.io.File(nullDevice)))
          .start()
        if !p.waitFor(10, TimeUnit.SECONDS) then
          p.destroyForcibly()
          Left(s"$bin --build-info did not answer within 10 s")
        else
          val text = new String(Files.readAllBytes(out), StandardCharsets.UTF_8)
          if p.exitValue() == 0 then Right(text)
          else Left(s"$bin does not start here (exit ${p.exitValue()}): ${text.trim.take(500)}")
      finally Files.deleteIfExists(out)
    catch case e: java.io.IOException => Left(s"cannot run $bin: ${e.getMessage}")

  private def nullDevice: String =
    if sys.props.getOrElse("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win") then
      "NUL"
    else "/dev/null"

  private def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  private def deleteTree(dir: Path): Unit =
    Try {
      Using.resource(Files.walk(dir)) { s =>
        s.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
      }
    }
    ()
