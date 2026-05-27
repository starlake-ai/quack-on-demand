package ai.starlake.quack.it

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.sys.process._
import java.net.{HttpURLConnection, URI}

class EndToEndSmokeSpec extends AnyFlatSpec with Matchers:

  // This test requires:
  //   - The `quack-on-demand` jar built (via `sbt assembly`) or `sbt run` running on port 20900
  //   - python3 on PATH (for the fake quack stub)
  //   - bash and curl on PATH
  // It is `assume`d to skip cleanly when the manager isn't up.

  "test-api.sh" should "complete end-to-end against a running manager" in:
    assume(isManagerUp(), "manager not running on 20900; start it before this test")
    val rc = Process("./test-api.sh").!
    rc shouldBe 0

  private def isManagerUp(): Boolean =
    try
      val c = URI.create("http://127.0.0.1:20900/health").toURL.openConnection().asInstanceOf[HttpURLConnection]
      c.setConnectTimeout(500); c.setReadTimeout(500)
      val ok = c.getResponseCode == 200
      c.disconnect()
      ok
    catch case _: Throwable => false