package ai.starlake.quack.ondemand.demo

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

class DemoHomeSpec extends AnyFlatSpec with Matchers:

  "DemoHome.create" should "make the home + subdirs, pin the native extraction dir, and clean up" in {
    val base = Files.createTempDirectory("demo-home-spec")
    val home = DemoHome.create(Some(base.resolve("qod-demo").toString))
    Files.isDirectory(home.root) shouldBe true
    Files.isDirectory(home.pgDir) shouldBe true
    Files.isDirectory(home.dataPath) shouldBe true
    Files.isDirectory(home.nativeDir) shouldBe true
    // Guardrail 2: extraction dir is pinned to the demo home, not an ambient temp.
    sys.props(DemoHome.NativeExtractionProp) shouldBe home.nativeDir.toString

    home.deleteRecursively()
    Files.exists(home.root) shouldBe false
  }
