package ai.starlake.quack.docs

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}

class OpenApiFreshnessSpec extends AnyFlatSpec with Matchers:

  "website/static/openapi.yaml" should "match the current Tapir endpoint definitions" in {
    val path = Paths.get("website/static/openapi.yaml")
    withClue("committed OpenAPI missing; run `sbt genOpenApi` and commit: ") {
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
    withClue("website/static/openapi.yaml is stale; run `sbt genOpenApi` and commit: ") {
      committed shouldBe fresh
    }
  }
