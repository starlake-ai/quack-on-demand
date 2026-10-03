package ai.starlake.quack.observability.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.TimeUnit

class RestEdgeInstrumentsSpec extends AnyFlatSpec with Matchers:

  "RestEdgeInstruments" should "count requests by route, format and status, and time them" in {
    val reg = new SimpleMeterRegistry
    val ri  = new RestEdgeInstruments(reg)
    ri.request("rows", "json", 200, 5_000_000L)
    ri.request("rows", "json", 200, 15_000_000L)
    ri.request("rows", "none", 404, 1_000_000L)
    reg
      .counter("qod_rest_requests_total", "route", "rows", "format", "json", "status", "200")
      .count() shouldBe 2.0
    reg
      .counter("qod_rest_requests_total", "route", "rows", "format", "none", "status", "404")
      .count() shouldBe 1.0
    val t = reg.get("qod_rest_request_seconds").tags("route", "rows", "format", "json").timer()
    t.count() shouldBe 2L
    t.totalTime(TimeUnit.MILLISECONDS) shouldBe 20.0
  }

  it should "count rejections by reason and stream aborts by route and format" in {
    val reg = new SimpleMeterRegistry
    val ri  = new RestEdgeInstruments(reg)
    ri.rejection("auth_failures")
    ri.rejection("concurrency")
    ri.rejection("concurrency")
    ri.streamAborted("rows", "arrow")
    reg.counter("qod_rest_rejections_total", "reason", "auth_failures").count() shouldBe 1.0
    reg.counter("qod_rest_rejections_total", "reason", "concurrency").count() shouldBe 2.0
    reg
      .counter("qod_rest_stream_aborts_total", "route", "rows", "format", "arrow")
      .count() shouldBe 1.0
  }
