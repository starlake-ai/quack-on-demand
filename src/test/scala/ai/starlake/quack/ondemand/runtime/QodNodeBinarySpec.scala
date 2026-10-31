package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.BuildVersions
import ai.starlake.quack.edge.adapter.QuackNativeSupport
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, Paths}
import scala.compiletime.uninitialized
import scala.jdk.CollectionConverters.*

/** Which qod-node a local node runs, and that it starts here ([[QodNodeBinary]]). Stand-in binaries
  * are shell scripts printing a build marker; the last case installs the vendored binary of this
  * platform for real and runs it through its relative rpath.
  */
class QodNodeBinarySpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach:

  private var tmp: Path = uninitialized

  override def beforeEach(): Unit = tmp = Files.createTempDirectory("qod-node-binary-spec")

  override def afterEach(): Unit =
    Files.walk(tmp).iterator().asScala.toList.reverse.foreach(Files.deleteIfExists)

  private val isWindows = scala.util.Properties.isWin

  private def marker(
      protocol: Int = QodNodeBinary.Protocol,
      duckdb: String = QodNodeBinary.expectedDuckdb
  ) =
    s"qod-node-build-info:protocol=$protocol;duckdb=$duckdb;duckdb_source=d54cbbe3df"

  /** A stand-in qod-node: a shell script printing `out` for --build-info and exiting `code`. */
  private def script(out: String, code: Int = 0): Array[Byte] =
    s"#!/bin/sh\necho '$out'\nexit $code\n".getBytes(StandardCharsets.UTF_8)

  private def executable(name: String, bytes: Array[Byte]): Path =
    val p = tmp.resolve(name)
    Files.write(p, bytes)
    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------"))
    p

  "the build marker" should "parse into protocol, DuckDB release and source id" in {
    QodNodeBinary.parseMarker("noise\n" + marker() + "\n") shouldBe
      Some(
        QodNodeBinary.BuildInfo(QodNodeBinary.Protocol, QodNodeBinary.expectedDuckdb, "d54cbbe3df")
      )
    QodNodeBinary.parseMarker("qod-node 1.0") shouldBe None
  }

  it should "name the node protocol native/qod-node/CMakeLists.txt compiles in" in {
    val cmake = Files.readString(Paths.get("native/qod-node/CMakeLists.txt"))
    cmake should include(s"set(QOD_NODE_PROTOCOL ${QodNodeBinary.Protocol})")
  }

  it should "be the protocol the node image refuses to build without" in {
    // Kubernetes pods never pass through QodNodeBinary: the image build is their protocol check.
    val dockerfile = Files.readString(Paths.get("docker/quack-node/Dockerfile"))
    dockerfile should include(s"ARG QOD_NODE_PROTOCOL=${QodNodeBinary.Protocol}\n")
    dockerfile should include(
      "grep -q \"qod-node-build-info:protocol=${QOD_NODE_PROTOCOL};duckdb=v${DUCKDB_VERSION};\""
    )
  }

  it should "expect the pinned DuckDB release" in {
    QodNodeBinary.expectedDuckdb shouldBe
      "v" + BuildVersions.duckdb.split('.').take(3).mkString(".")
  }

  "QOD_NODE_BIN" should "win, once its --build-info matches this build" in {
    assume(!isWindows, "stand-in binaries are shell scripts")
    val bin = executable("qod-node", script(marker()))
    QodNodeBinary.resolve(
      Map(QodNodeBinary.EnvVar -> bin.toString),
      Some("osx-aarch64"),
      None,
      bundled = _ => fail("the bundled binary must not be read when QOD_NODE_BIN is set")
    ) shouldBe Right(bin)
  }

  it should "be refused when it is not an executable file" in {
    val left = QodNodeBinary.resolve(
      Map(QodNodeBinary.EnvVar -> tmp.resolve("missing").toString),
      Some("osx-aarch64"),
      None
    )
    left.left.toOption.get should include("is not an executable file")
  }

  it should "be refused when it speaks another node protocol or links another DuckDB" in {
    assume(!isWindows, "stand-in binaries are shell scripts")
    val otherProtocol = executable("p", script(marker(protocol = QodNodeBinary.Protocol + 1)))
    QodNodeBinary
      .resolve(Map(QodNodeBinary.EnvVar -> otherProtocol.toString), None, None)
      .left
      .toOption
      .get should include("node protocol")
    val otherDuckdb = executable("d", script(marker(duckdb = "v1.5.6")))
    QodNodeBinary
      .resolve(Map(QodNodeBinary.EnvVar -> otherDuckdb.toString), None, None)
      .left
      .toOption
      .get should include("v1.5.6")
  }

  it should "be refused when it does not start (no marker, a failing exit)" in {
    assume(!isWindows, "stand-in binaries are shell scripts")
    val broken =
      executable("b", script("dyld: Library not loaded: @rpath/libduckdb.dylib", code = 134))
    val left = QodNodeBinary.resolve(Map(QodNodeBinary.EnvVar -> broken.toString), None, None)
    left.left.toOption.get should (include("does not start here") and include("libduckdb"))
  }

  "with no QOD_NODE_BIN" should "name QOD_NODE_BIN when nothing is bundled for the platform" in {
    val left =
      QodNodeBinary.resolve(Map.empty, Some("windows-x86_64"), None, bundled = _ => None)
    left.left.toOption.get should (include("windows-x86_64") and include(QodNodeBinary.EnvVar))
  }

  it should "install the bundled binary beside the libduckdb cache, linked to its lib" in {
    assume(!isWindows, "stand-in binaries are shell scripts")
    val lib   = Files.createDirectories(tmp.resolve("cache/2.0.0/lib"))
    val bytes = script(marker())
    val bin   = QodNodeBinary
      .resolve(Map.empty, Some("osx-aarch64"), Some(lib), bundled = _ => Some(bytes))
      .toOption
      .get
    bin.getFileName.toString shouldBe "qod-node"
    bin.getParent.getFileName.toString shouldBe "bin"
    bin.getParent.getParent.getParent shouldBe tmp.resolve("cache/2.0.0/qod-node")
    Files.isExecutable(bin) shouldBe true
    Files.readSymbolicLink(bin.getParent.getParent.resolve("lib")) shouldBe lib
    // The same content installs to the same place; a moved libduckdb re-points the link.
    val lib2 = Files.createDirectories(tmp.resolve("cache/2.0.0/lib2"))
    QodNodeBinary.install(bytes, Some(lib2), tmp) shouldBe Right(bin)
    Files.readSymbolicLink(bin.getParent.getParent.resolve("lib")) shouldBe lib2
    // Another build gets a directory of its own: a running node's binary is never replaced.
    val other = QodNodeBinary.install(script(marker()) ++ "\n".getBytes, Some(lib), tmp)
    other.toOption.get should not be bin
    Files.readAllBytes(bin) shouldBe bytes
  }

  it should "install into a private temp directory when there is no libduckdb cache" in {
    assume(!isWindows, "stand-in binaries are shell scripts")
    val bin = QodNodeBinary.install(script(marker()), None, tmp).toOption.get
    bin.startsWith(tmp) shouldBe true
    Files.exists(bin.getParent.getParent.resolve("lib")) shouldBe false
  }

  /** The provisioned libduckdb's directory, read from DUCKDB_CACHE_DIR (build.sbt points forked
    * tests at the main checkout's cache), not from [[QuackNativeSupport.libDuckDbDir]]: that reads
    * a JVM-wide preload record other specs set to stub libraries.
    */
  private def provisionedLibDir: Option[Path] =
    val abi = BuildVersions.duckdb.split('.').take(3).mkString(".")
    sys.env
      .get("DUCKDB_CACHE_DIR")
      .filter(_.trim.nonEmpty)
      .map(d => Paths.get(d, abi, "lib"))
      .filter(dir => Files.isRegularFile(dir.resolve(System.mapLibraryName("duckdb"))))

  "the vendored qod-node of this platform" should "install and start through its relative rpath" in {
    val platform = QuackNativeSupport.platform()
    val bytes    = platform.flatMap(QodNodeBinary.bundledBytes)
    assume(bytes.nonEmpty, s"no qod-node is vendored for ${platform.getOrElse("this platform")}")
    val realLib = provisionedLibDir
    assume(realLib.nonEmpty, "no provisioned libduckdb (DUCKDB_CACHE_DIR)")
    // A cache of this test's own, its lib/ a link to the real one, so nothing is written there.
    val lib = tmp.resolve("cache/2.0.0/lib")
    Files.createDirectories(lib.getParent)
    Files.createSymbolicLink(lib, realLib.get)
    val bin = QodNodeBinary.install(bytes.get, Some(lib), tmp)
    bin.flatMap(QodNodeBinary.check(_, QodNodeBinary.runBuildInfo)) shouldBe bin
    bin.toOption.get.startsWith(tmp.resolve("cache/2.0.0/qod-node")) shouldBe true
  }
