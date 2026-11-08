package ai.starlake.quack.ondemand.runtime

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}
import scala.util.matching.Regex

/** The spill leaf's name and the manager marker are read in three places: qod-node, which makes the
  * leaf (`native/qod-node/src/spill_dir.cpp`), and both spawn scripts, which remove only a leaf
  * whose name matches and refuse any other marker. Each place writes its own regex, so this spec
  * reads the three out of the sources and holds them to one pattern, anchors aside (`^...$` in C++
  * and bash, where they bind the whole string; `\A...\z` or `^...$` in PowerShell). The wheel's
  * copies of the scripts are byte-equal to these (cli/tests/test_demo.py).
  */
class SpillLeafPatternSpec extends AnyFlatSpec with Matchers:

  private def read(path: String): String = Files.readString(Paths.get(path))

  private val cpp = read("native/qod-node/src/spill_dir.cpp")
  private val sh  = read("scripts/spawn-quack-node.sh")
  private val ps1 = read("scripts/spawn-quack-node.ps1")

  /** The one match of `in` in `text`, its first group, failing the test naming `what` otherwise. */
  private def single(in: Regex, text: String, what: String): String =
    in.findAllMatchIn(text).map(_.group(1)).toList match
      case List(found) => found
      case other       => fail(s"$what: expected one pattern, found ${other.size}: $other")

  /** The pattern without its anchors at the two ends. */
  private def core(pattern: String): String =
    pattern.stripPrefix("\\A").stripPrefix("^").stripSuffix("\\z").stripSuffix("$")

  private val cppLeaf   = single("""kLeaf\(R"\((.*?)\)"\)""".r, cpp, "spill_dir.cpp kLeaf")
  private val cppMarker = single("""kMarker\(R"\((.*?)\)"\)""".r, cpp, "spill_dir.cpp kMarker")
  private val shLeaf    = single("""\[\[ "\$name" =~ (\S*qod-spill\S*) \]\]""".r, sh, "sh leaf")
  private val shMarker  =
    single("""\[\[ -n "\$MARKER" && ! "\$MARKER" =~ (\S+) \]\]""".r, sh, "sh marker")
  private val ps1Leaf   = single("""\$name -cnotmatch '([^']*qod-spill[^']*)'""".r, ps1, "ps1 leaf")
  private val ps1Marker = single("""\$Marker -cnotmatch '([^']+)'""".r, ps1, "ps1 marker")

  "the spill leaf's name" should "be the same pattern in qod-node and both spawn scripts" in
    withClue(s"cpp=$cppLeaf sh=$shLeaf ps1=$ps1Leaf: ") {
      core(shLeaf) shouldBe core(cppLeaf)
      core(ps1Leaf) shouldBe core(cppLeaf)
    }

  it should "match a leaf qod-node makes and nothing else" in {
    val leaf = ("^" + core(cppLeaf) + "$").r
    Seq("qod-spill.20900-ab12.Ab3dE9", "qod-spill.Ab3dE9").foreach(n =>
      withClue(n)(leaf.matches(n) shouldBe true)
    )
    Seq("qod-spill.Ab3dE", "qod-node.Ab3dE9", "qod-spill.20900-AB12.Ab3dE9", "qod-spill.x.Ab3dE9")
      .foreach(n => withClue(n)(leaf.matches(n) shouldBe false))
  }

  "the manager marker" should "be the same pattern in qod-node and both spawn scripts" in {
    withClue(s"cpp=$cppMarker sh=$shMarker ps1=$ps1Marker: ") {
      core(shMarker) shouldBe core(cppMarker)
      core(ps1Marker) shouldBe core(cppMarker)
    }
    // PowerShell's '$' also matches before a final line break: only \A...\z binds the whole string.
    ps1Marker should (startWith("\\A") and endWith("\\z"))
  }
