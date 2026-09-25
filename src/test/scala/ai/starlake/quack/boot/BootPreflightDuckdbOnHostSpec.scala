package ai.starlake.quack.boot

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

/** `BootPreflight.duckdbOnHost` mirrors `scripts/spawn-quack-node.sh`'s
  * `DUCKDB="${DUCKDB_BIN:-duckdb}"; command -v "$DUCKDB"`. These tests drive it through the `env`
  * parameter (a fake `DUCKDB_BIN` / `PATH`) rather than the real process environment.
  */
class BootPreflightDuckdbOnHostSpec extends AnyFlatSpec with Matchers:

  private def withExecutable(name: String)(test: Path => Unit): Unit =
    val dir = Files.createTempDirectory("qod-duckdb-test")
    val bin = dir.resolve(name)
    Files.createFile(bin)
    bin.toFile.setExecutable(true)
    try test(dir)
    finally
      Files.deleteIfExists(bin)
      Files.deleteIfExists(dir)

  "duckdbOnHost" should "find duckdb on PATH when DUCKDB_BIN is unset" in withExecutable("duckdb") {
    dir =>
      BootPreflight.duckdbOnHost(Map("PATH" -> dir.toString)) shouldBe true
  }

  it should "treat a blank DUCKDB_BIN the same as unset" in withExecutable("duckdb") { dir =>
    BootPreflight.duckdbOnHost(Map("DUCKDB_BIN" -> "  ", "PATH" -> dir.toString)) shouldBe true
  }

  it should "resolve a bare DUCKDB_BIN name through PATH, not the JVM's working directory" in
    withExecutable("mybin") { dir =>
      BootPreflight.duckdbOnHost(Map("DUCKDB_BIN" -> "mybin", "PATH" -> dir.toString)) shouldBe true
    }

  it should "check an explicit DUCKDB_BIN path directly, without consulting PATH" in
    withExecutable("duckdb") { dir =>
      val explicit = dir.resolve("duckdb").toString
      BootPreflight.duckdbOnHost(Map("DUCKDB_BIN" -> explicit, "PATH" -> "")) shouldBe true
    }

  it should "return false for a missing explicit DUCKDB_BIN path" in {
    BootPreflight.duckdbOnHost(Map("DUCKDB_BIN" -> "/no/such/path/duckdb")) shouldBe false
  }

  it should "return false, not throw, when PATH is empty or unset" in {
    noException should be thrownBy BootPreflight.duckdbOnHost(Map.empty)
    BootPreflight.duckdbOnHost(Map.empty) shouldBe false
  }

  it should "skip an unparsable PATH entry instead of throwing" in withExecutable("duckdb") { dir =>
    val badEntry = "\u0000bad"
    val path     = s"$badEntry${java.io.File.pathSeparatorChar}${dir.toString}"
    noException should be thrownBy BootPreflight.duckdbOnHost(Map("PATH" -> path))
    BootPreflight.duckdbOnHost(Map("PATH" -> path)) shouldBe true
  }
