package ai.starlake.quack.docs

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}

class OpenApiFreshnessSpec extends AnyFlatSpec with Matchers:

  private val regenHint =
    "run `sbt \"runMain ai.starlake.quack.docs.GenOpenApi cli/tests/resources/openapi.yaml <version>\"` and commit: "

  "cli/tests/resources/openapi.yaml" should "match the current Tapir endpoint definitions" in {
    val path = Paths.get("cli/tests/resources/openapi.yaml")
    withClue(s"committed OpenAPI contract copy missing; $regenHint") {
      Files.exists(path) shouldBe true
    }
    val committed = Files.readString(path)
    val version   = committed.linesIterator
      .collectFirst {
        case l if l.trim.startsWith("version:") =>
          l.split(":", 2)(1).trim.stripPrefix("'").stripSuffix("'")
      }
      .getOrElse("0.0.0")
    val fresh = GenOpenApi.render(version)
    withClue(s"cli/tests/resources/openapi.yaml is stale; $regenHint") {
      committed shouldBe fresh
    }
  }
