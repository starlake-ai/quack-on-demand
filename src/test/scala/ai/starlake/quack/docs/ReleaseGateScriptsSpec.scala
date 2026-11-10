package ai.starlake.quack.docs

import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import scala.compiletime.uninitialized
import scala.jdk.CollectionConverters.*
import scala.sys.process.*

/** Drives the release gates on synthetic trees: the per-binary build-marker checks
  * (scripts/release/quackwire-build-info.sh, scripts/release/qod-node-build-info.sh) and
  * `verify_quackwire_binaries` / `verify_qod_node_binaries` built on them, and the release-pin
  * guard (scripts/release/check-release-pins.sh). The VERSION stamp vouches for the whole binary
  * set, so a stale binary with a correct sidecar passed the old gate; these pin that it no longer
  * does.
  */
class ReleaseGateScriptsSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach:

  private var tempDir: Path = uninitialized

  override def beforeEach(): Unit =
    tempDir = Files.createTempDirectory("release-gate-scripts-spec")

  override def afterEach(): Unit =
    Files.walk(tempDir).iterator().asScala.toSeq.sortBy(_.toString.length).reverse.foreach {
      Files.deleteIfExists
    }

  private val Pin = "2.0.0-974927a394b1-1"

  private def marker(
      protocol: String = "3",
      duckdb: String = "v2.0.0-alpha46057",
      quack: String = "974927a394b188755284682b73398ed50e86316c"
  ): String =
    s"quackwire-build-info:protocol=$protocol;duckdb=$duckdb;duckdb_source=b15358c371;quack=$quack"

  /** A stand-in binary: the marker and the glibc version names surrounded by NUL-padded junk, as in
    * .rodata and .dynstr.
    */
  private def fakeBinary(path: Path, marker: Option[String], glibc: Seq[String] = Nil): Path =
    Files.createDirectories(path.getParent)
    val junk  = Array.fill[Byte](64)(0x7f) ++ Array.fill[Byte](8)(0)
    val names = glibc.flatMap(g => g.getBytes("US-ASCII").toSeq :+ 0.toByte).toArray
    Files.write(
      path,
      junk ++ marker.fold(Array.emptyByteArray)(_.getBytes("US-ASCII")) ++ junk ++ names
    )

  /** What a Linux stand-in names: within the floor unless the test says otherwise. */
  private def linuxGlibc(plat: String, tooNew: Set[String]): Seq[String] =
    if !plat.startsWith("linux-") then Nil
    else if tooNew(plat) then Seq("GLIBC_2.2.5", "GLIBC_2.34")
    else Seq("GLIBC_2.2.5", "GLIBC_2.17")

  private def sha256(path: Path): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(Files.readAllBytes(path))
      .map("%02x".format(_))
      .mkString

  /** Runs `script` with bash in `cwd`; returns (exit code, stdout + stderr). */
  private def bash(cwd: Path, script: String): (Int, String) =
    val out  = new StringBuilder
    val sink = ProcessLogger(l => out.append(l).append('\n'), l => out.append(l).append('\n'))
    val code = Process(Seq("bash", "-c", script), cwd.toFile).!(sink)
    (code, out.toString)

  private val buildInfoScript = Paths.get("scripts/release/quackwire-build-info.sh").toAbsolutePath

  private def checkMarker(file: Path, pin: String = Pin): (Int, String) =
    bash(tempDir, s"""source "$buildInfoScript" && quackwire_check_build_marker "$file" "$pin"""")

  "quackwire_check_build_marker" should "accept a binary whose marker matches the pin" in {
    val f = fakeBinary(tempDir.resolve("ok.so"), Some(marker()))
    checkMarker(f)._1 shouldBe 0
  }

  it should "refuse a binary with no marker as a pre-marker (stale) build" in {
    val (code, out) = checkMarker(fakeBinary(tempDir.resolve("old.so"), None))
    code shouldBe 1
    out should include("predates build markers")
  }

  it should "refuse a binary built against another DuckDB release" in {
    val (code, out) =
      checkMarker(fakeBinary(tempDir.resolve("a.so"), Some(marker(duckdb = "v1.5.6"))))
    code shouldBe 1
    out should include("DuckDB 'v1.5.6'")
  }

  it should "refuse a binary built from another duckdb-quack commit" in {
    val f           = fakeBinary(tempDir.resolve("b.so"), Some(marker(quack = "7e80f7ffcc98d0b3")))
    val (code, out) = checkMarker(f)
    code shouldBe 1
    out should include("duckdb-quack '7e80f7ffcc98'")
  }

  it should "refuse another Quack protocol generation" in {
    val (code, out) = checkMarker(fakeBinary(tempDir.resolve("c.so"), Some(marker(protocol = "1"))))
    code shouldBe 1
    out should include("Quack protocol '1'")
  }

  /** A checkout holding just what `verify_quackwire_binaries` reads, every sidecar correct. */
  private def releaseTree(stale: Set[String], tooNew: Set[String] = Set.empty): Path =
    val root = tempDir.resolve("tree")
    Files.createDirectories(root.resolve("scripts/release"))
    Seq(
      "release-lib.sh",
      "quackwire-build-info.sh",
      "qod-node-build-info.sh",
      "glibc-floor.sh"
    ).foreach { f =>
      Files.copy(Paths.get(s"scripts/release/$f"), root.resolve(s"scripts/release/$f"))
    }
    Files.writeString(root.resolve("build.sbt"), s"""val libquackwireVersion = "$Pin"\n""")
    val bins = root.resolve("libquackwire/binaries")
    Seq(
      "linux-x86_64/libquackwire.so",
      "linux-aarch64/libquackwire.so",
      "osx-x86_64/libquackwire.dylib",
      "osx-aarch64/libquackwire.dylib",
      "windows-x86_64/quackwire.dll"
    ).foreach { rel =>
      val plat = rel.takeWhile(_ != '/')
      val bin  = fakeBinary(
        bins.resolve(rel),
        if stale(plat) then None else Some(marker()),
        linuxGlibc(plat, tooNew)
      )
      Files.writeString(bins.resolve(s"$rel.sha256"), sha256(bin) + "\n")
    }
    Files.writeString(bins.resolve("VERSION"), Pin + "\n")
    root

  private def verify(root: Path): (Int, String) =
    bash(root, "source scripts/release/release-lib.sh && verify_quackwire_binaries")

  "verify_quackwire_binaries" should "pass a binary set whose every marker matches the pin" in {
    val (code, out) = verify(releaseTree(stale = Set.empty))
    withClue(out)(code shouldBe 0)
    out should include(s"phase 1 OK: vendored libquackwire binaries match $Pin")
  }

  it should "refuse a stale binary even when VERSION and its sidecar agree" in {
    val (code, out) = verify(releaseTree(stale = Set("linux-x86_64")))
    code shouldBe 1
    out should include("linux-x86_64/libquackwire.so has no quackwire-build-info marker")
  }

  it should "refuse a stale Windows dll too" in {
    val (code, out) = verify(releaseTree(stale = Set("windows-x86_64")))
    code shouldBe 1
    out should include("windows-x86_64/quackwire.dll")
  }

  it should "refuse a Linux library needing a glibc past the floor" in {
    val (code, out) = verify(releaseTree(stale = Set.empty, tooNew = Set("linux-aarch64")))
    code shouldBe 1
    out should include("linux-aarch64/libquackwire.so needs glibc 2.34")
  }

  // ---- the glibc floor ---------------------------------------------------------------------

  private def checkFloor(names: String*): (Int, String) =
    val f = fakeBinary(tempDir.resolve("elf"), None, names)
    bash(
      Paths.get(".").toAbsolutePath,
      s"source scripts/release/glibc-floor.sh && glibc_floor_check '$f'"
    )

  "glibc_floor_check" should "compare versions numerically, 2.9 being older than 2.28" in {
    checkFloor("GLIBC_2.9", "GLIBC_2.2.5", "GLIBC_2.28")._1 shouldBe 0
  }

  it should "refuse the first version past the floor" in {
    val (code, out) = checkFloor("GLIBC_2.9", "GLIBC_2.29")
    code shouldBe 1
    out should include("needs glibc 2.29; the limit is 2.28")
  }

  it should "not read GLIBC_PRIVATE as a version" in {
    checkFloor("GLIBC_PRIVATE", "GLIBC_2.17")._1 shouldBe 0
  }

  it should "refuse a file naming no glibc at all rather than pass it" in {
    val (code, out) = checkFloor()
    code shouldBe 1
    out should include("names no glibc version")
  }

  // ---- qod-node ----------------------------------------------------------------------------

  private def nodeMarker(
      protocol: String = "1",
      duckdb: String = "v2.0.0",
      source: String = "d54cbbe3df"
  ) =
    s"qod-node-build-info:protocol=$protocol;duckdb=$duckdb;duckdb_source=$source"

  /** A checkout holding what `verify_qod_node_binaries` reads: the pin (CMakeLists protocol,
    * Versions.duckdb), the four vendored binaries with their sidecars, and VERSION.
    */
  private def nodeTree(
      markers: Map[String, Option[String]] = Map.empty,
      version: String = "protocol=1;duckdb=v2.0.0;duckdb_source=d54cbbe3df",
      tooNew: Set[String] = Set.empty
  ): Path =
    val root = releaseTree(stale = Set.empty)
    Files.createDirectories(root.resolve("native/qod-node"))
    Files.writeString(root.resolve("native/qod-node/CMakeLists.txt"), "set(QOD_NODE_PROTOCOL 1)\n")
    Files.createDirectories(root.resolve("project"))
    Files.writeString(
      root.resolve("project/Versions.scala"),
      "object Versions {\n  val duckdb         = \"2.0.0.0\"\n}\n"
    )
    val bins = root.resolve("native/qod-node/binaries")
    Seq("linux-x86_64", "linux-aarch64", "osx-x86_64", "osx-aarch64").foreach { plat =>
      markers.getOrElse(plat, Some(nodeMarker())) match
        case Some(m) =>
          val bin = fakeBinary(bins.resolve(s"$plat/qod-node"), Some(m), linuxGlibc(plat, tooNew))
          Files.writeString(bins.resolve(s"$plat/qod-node.sha256"), sha256(bin) + "\n")
        case None => ()
    }
    Files.writeString(bins.resolve("VERSION"), version + "\n")
    root

  private def verifyNodes(root: Path): (Int, String) =
    bash(root, "source scripts/release/release-lib.sh && verify_qod_node_binaries")

  "verify_qod_node_binaries" should "pass a full set on the pin, one DuckDB source for all" in {
    val (code, out) = verifyNodes(nodeTree())
    withClue(out)(code shouldBe 0)
    out should include("vendored qod-node binaries match protocol=1;duckdb=v2.0.0")
  }

  it should "refuse a missing platform" in {
    val (code, out) = verifyNodes(nodeTree(markers = Map("osx-x86_64" -> None)))
    code shouldBe 1
    out should include("missing vendored qod-node binaries: osx-x86_64")
  }

  it should "refuse a Linux binary needing a glibc past the floor" in {
    val (code, out) = verifyNodes(nodeTree(tooNew = Set("linux-x86_64")))
    code shouldBe 1
    out should include("linux-x86_64/qod-node needs glibc 2.34")
  }

  it should "refuse a binary of another node protocol" in {
    val (code, out) =
      verifyNodes(nodeTree(markers = Map("linux-x86_64" -> Some(nodeMarker(protocol = "2")))))
    code shouldBe 1
    out should include("node protocol 2")
  }

  it should "refuse a binary of another DuckDB release" in {
    val (code, out) =
      verifyNodes(nodeTree(markers = Map("linux-aarch64" -> Some(nodeMarker(duckdb = "v1.5.6")))))
    code shouldBe 1
    out should include("DuckDB v1.5.6")
  }

  it should "refuse a set built against different DuckDB sources" in {
    val (code, out) =
      verifyNodes(nodeTree(markers = Map("osx-aarch64" -> Some(nodeMarker(source = "0123abcd")))))
    code shouldBe 1
    out should include("different DuckDB sources")
  }

  it should "refuse a VERSION stamp that does not name the set" in {
    val (code, out) =
      verifyNodes(nodeTree(version = "protocol=1;duckdb=v2.0.0;duckdb_source=0123abcd"))
    code shouldBe 1
    out should include("VERSION does not read")
  }

  private val pinsScript = Paths.get("scripts/release/check-release-pins.sh").toAbsolutePath

  private def pinsTree(jdbc: String, buildSbt: String): Path =
    val root = Files.createDirectories(tempDir.resolve("pins"))
    Files.createDirectories(root.resolve("project"))
    Files.writeString(
      root.resolve("project/Versions.scala"),
      s"""object Versions {\n  val duckdb         = "2.0.0.0"\n  val duckdbJdbc     = $jdbc\n}\n"""
    )
    Files.writeString(root.resolve("build.sbt"), buildSbt)
    root

  private def checkPins(root: Path): (Int, String) = bash(root, s""""$pinsScript" "$root"""")

  "check-release-pins.sh" should "pass the release-day pins: the JDBC from Central, no mavenLocal" in {
    val root = pinsTree("duckdb", "// resolvers += Resolver.mavenLocal (removed on release day)\n")
    val (code, out) = checkPins(root)
    withClue(out)(code shouldBe 0)
  }

  it should "refuse a pre-release JDBC" in {
    val (code, out) = checkPins(pinsTree("\"2.0.0.0-pre-b15358c\"", ""))
    code shouldBe 1
    out should include("pre-release DuckDB JDBC")
  }

  it should "refuse a mavenLocal resolver" in {
    val (code, out) = checkPins(pinsTree("duckdb", "    resolvers += Resolver.mavenLocal,\n"))
    code shouldBe 1
    out should include("build.sbt:1 resolves from ~/.m2")
  }

  // Audit F26: the release-day list lived only in gitignored files; the red check now names the
  // tracked one, and that one covers what the check refuses.
  private val releaseDay = "scripts/release/RELEASE-DAY-DUCKDB-2.0.md"

  it should "name the tracked release-day checklist when it refuses" in {
    val (code, out) = checkPins(pinsTree("\"2.0.0.0-pre-b15358c\"", ""))
    code shouldBe 1
    out should include(releaseDay)
  }

  "the release-day checklist" should "be tracked and cover the pins the guard refuses" in {
    val doc = Paths.get(releaseDay)
    Files.exists(doc) shouldBe true
    val text = Files.readString(doc)
    List("Versions.duckdbJdbc", "Resolver.mavenLocal", "check-release-pins.sh", "refresh-quackwire")
      .foreach(term => withClue(term)(text should include(term)))
  }
