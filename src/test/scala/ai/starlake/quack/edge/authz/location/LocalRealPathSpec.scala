package ai.starlake.quack.edge.authz.location

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.nio.file.{Files, Path}

/** `LocalRealPath.resolve` is qod-node's `ResolveLocalPath` (native/qod-node/src/locations.cpp):
  * the cases of native/qod-node/tests/locations_test.cpp, one for one, so the gateway resolves a
  * local location exactly as the node does before both judge it (Task 12 review M1).
  */
class LocalRealPathSpec extends AnyFlatSpec with Matchers:

  private val Windows = File.separatorChar == '\\'
  private val Sep     = File.separator

  private def withBase(body: Path => Unit): Unit =
    val base = Files.createTempDirectory("qod-locations-")
    try
      Files.createDirectories(base.resolve("allowed").resolve("sub"))
      Files.createDirectories(base.resolve("outside"))
      Files.writeString(base.resolve("allowed").resolve("f.csv"), "a\n1\n")
      Files.writeString(base.resolve("outside").resolve("secret.csv"), "s\n1\n")
      body(base)
    finally
      Files
        .walk(base)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(p => Files.deleteIfExists(p))

  private def real(p: Path): String = p.toRealPath().toString

  private def refused(path: String, needle: String) =
    LocalRealPath.resolve(path) match
      case Left(why)       => why should include(needle)
      case Right(resolved) => fail(s"accepted $path as $resolved")

  "resolve" should "give the real path of the deepest existing ancestor, the rest as given" in
    withBase { base =>
      val allowed = base.resolve("allowed").toString
      LocalRealPath.resolve(allowed + Sep + "f.csv") shouldBe
        Right(real(base.resolve("allowed").resolve("f.csv")))
      LocalRealPath.resolve(allowed + Sep + "new" + Sep + "out.parquet") shouldBe
        Right(real(base.resolve("allowed")) + Sep + "new" + Sep + "out.parquet")
      LocalRealPath.resolve(allowed + Sep + "*.csv") shouldBe
        Right(real(base.resolve("allowed")) + Sep + "*.csv")
      LocalRealPath.resolve(allowed + Sep) shouldBe Right(real(base.resolve("allowed")) + Sep)
    }

  it should "refuse what the node's lexical rules refuse" in
    withBase { base =>
      val allowed     = base.resolve("allowed").toString
      val notAbsolute =
        if Windows then "is not an absolute path with a drive letter" else "is a relative path"
      refused(allowed + Sep + "." + Sep + "f.csv", "empty, '.' or '..'")
      refused(allowed + Sep + ".." + Sep + "outside" + Sep + "secret.csv", "empty, '.' or '..'")
      refused(allowed + Sep + Sep + "f.csv", "empty, '.' or '..'")
      refused(allowed + Sep + Sep, "empty, '.' or '..'")
      refused("f.csv", notAbsolute)
      refused("~/f.csv", notAbsolute)
      refused("", notAbsolute)
      refused(allowed + "\u0000x", "NUL")
    }

  it should "follow symbolic links, and refuse one that leads nowhere" in {
    assume(!Windows, "the node's symbolic link cases are POSIX only")
    withBase { base =>
      val allowed = base.resolve("allowed")
      Files.createSymbolicLink(allowed.resolve("escape"), base.resolve("outside"))
      LocalRealPath.resolve(s"$allowed/escape/x.csv") shouldBe
        Right(real(base.resolve("outside")) + "/x.csv")
      Files.createSymbolicLink(allowed.resolve("link.csv"), base.resolve("outside/secret.csv"))
      LocalRealPath.resolve(s"$allowed/link.csv") shouldBe
        Right(real(base.resolve("outside/secret.csv")))
      Files.createSymbolicLink(allowed.resolve("dangling"), base.resolve("missing"))
      refused(s"$allowed/dangling", "cannot be resolved")
      refused(s"$allowed/dangling/x.csv", "cannot be resolved")
      refused("C:\\data\\x.csv", "is a relative path")
      // `of` keeps a path the node refuses as written (the deny set's lexical entry)
      LocalRealPath.of(s"$allowed/dangling") shouldBe s"$allowed/dangling"
    }
  }
