package ai.starlake.quack.edge.authz

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}

/** qod-node refuses, before any call, a decide request the verdict listener would answer 413
  * (`kMaxDecideBodyBytes` in native/qod-node/src/decide_client.hpp), and reads a 413 as a refusal:
  * the statement chooses the description's size, so a request too large must never read as an
  * outage. Both sides must agree on the limit.
  */
class DecideBodyLimitSpec extends AnyFlatSpec with Matchers:

  "qod-node's decide body limit" should "equal DecideRoute.MaxBodyBytes" in {
    val header = Files.readString(Paths.get("native/qod-node/src/decide_client.hpp"))
    val found  = """constexpr size_t kMaxDecideBodyBytes = (\d+);""".r
      .findFirstMatchIn(header)
      .map(_.group(1).toInt)
    found shouldBe Some(DecideRoute.MaxBodyBytes)
  }
