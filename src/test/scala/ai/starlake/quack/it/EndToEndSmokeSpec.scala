package ai.starlake.quack.it

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.sys.process._
import java.net.{HttpURLConnection, URI}
import scala.io.Source
import scala.util.Using

class EndToEndSmokeSpec extends AnyFlatSpec with Matchers:

  // This test requires:
  //   - The `quack-on-demand` jar built (via `sbt assembly`) or `sbt run` running on port 20900
  //   - python3 on PATH (for the fake quack stub)
  //   - bash and curl on PATH
  // This spec never spawns its own manager -- it only probes whatever is
  // already bound to :20900. `checkManager()` distinguishes three cases so a
  // stale/foreign process on that port fails loudly and clearly instead of
  // producing a confusing downstream assertion failure from test-api.sh:
  //   - nothing listening                       -> cancel, "start it first"
  //   - something listening but wrong shape      -> cancel, names what was hit
  //   - a quack-on-demand manager (health shape) -> proceed

  "test-api.sh" should "complete end-to-end against a running manager" in:
    checkManager() match
      case ManagerCheck.NotListening =>
        cancel(
          "manager not running on 20900; start it (e.g. ./scripts/run-jar.sh) before this test"
        )
      case ManagerCheck.WrongShape(detail) =>
        cancel(
          "something is listening on :20900 but it doesn't look like a quack-on-demand " +
            s"manager ($detail); refusing to run test-api.sh against it"
        )
      case ManagerCheck.Up =>
        val rc = Process("./test-api.sh").!
        rc shouldBe 0

  private enum ManagerCheck:
    case Up
    case NotListening
    case WrongShape(detail: String)

  /** Probe the cheap unauthenticated `/health` endpoint (see
    * `ai.starlake.quack.ondemand.api.Endpoints.health` / `HealthHandler.health`) and check the
    * response looks like the manager's `HealthResponse` shape (`{"status":"ok",...}`) rather than
    * merely "some HTTP server answered 200" -- a different service bound to :20900 would pass a
    * bare status-code check but fail this shape check.
    */
  private def checkManager(): ManagerCheck =
    try
      val c = URI
        .create("http://127.0.0.1:20900/health")
        .toURL
        .openConnection()
        .asInstanceOf[HttpURLConnection]
      c.setConnectTimeout(500)
      c.setReadTimeout(500)
      val code = c.getResponseCode
      if code != 200 then ManagerCheck.WrongShape(s"GET /health returned HTTP $code")
      else
        val body = Using.resource(Source.fromInputStream(c.getInputStream))(_.mkString)
        c.disconnect()
        if body.contains("\"status\"") && body.contains("\"ok\"") then ManagerCheck.Up
        else ManagerCheck.WrongShape(s"GET /health body did not match the expected shape: $body")
    catch case _: Throwable => ManagerCheck.NotListening
