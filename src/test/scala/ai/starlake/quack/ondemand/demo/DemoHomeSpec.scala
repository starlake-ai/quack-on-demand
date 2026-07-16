package ai.starlake.quack.ondemand.demo

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

class DemoHomeSpec extends AnyFlatSpec with Matchers:

  "DemoHome.create" should "make the home + subdirs and clean up" in {
    val base = Files.createTempDirectory("demo-home-spec")
    val home = DemoHome.create(Some(base.resolve("qod-demo").toString))
    Files.isDirectory(home.root) shouldBe true
    Files.isDirectory(home.pgDir) shouldBe true
    Files.isDirectory(home.dataPath) shouldBe true
    // `nativeDir` is still created for shape/symmetry, but (final-review Fix 1) DuckDB's JNI
    // native never actually unpacks there: `Files.createTempFile` resolves against the JVM's
    // startup snapshot of `java.io.tmpdir`, which a runtime `System.setProperty` cannot redirect,
    // so there is nothing left to assert a "pin" for here -- see the NOTE in DemoHome.scala.
    Files.isDirectory(home.nativeDir) shouldBe true

    home.deleteRecursively()
    Files.exists(home.root) shouldBe false
  }
