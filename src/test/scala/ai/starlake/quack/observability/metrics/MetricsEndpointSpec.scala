package ai.starlake.quack.observability.metrics

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.micrometer.prometheusmetrics.{PrometheusConfig as MmPrometheusConfig, PrometheusMeterRegistry}
import org.http4s._
import org.http4s.implicits._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.tapir.server.http4s.Http4sServerInterpreter

class MetricsEndpointSpec extends AnyFlatSpec with Matchers:

  "MetricsEndpoint" should "return 200 with the prometheus text format and the expected metric line" in:
    val prom    = new PrometheusMeterRegistry(MmPrometheusConfig.DEFAULT)
    prom.counter("statements_total", "tenant", "acme", "pool", "sales", "status", "ok").increment()
    val refreshes = new java.util.concurrent.atomic.AtomicInteger(0)
    val endpoint  = new MetricsEndpoint(Some(prom), () => refreshes.incrementAndGet())
    val routes    = Http4sServerInterpreter[IO]().toRoutes(endpoint.serverEndpoints)

    val req  = Request[IO](Method.GET, uri"/metrics")
    val resp = routes.orNotFound.run(req).unsafeRunSync()

    resp.status.code shouldBe 200
    resp.headers.get(org.typelevel.ci.CIString("Content-Type")).map(_.head.value)
      .getOrElse("") should include ("text/plain")
    val body = resp.bodyText.compile.string.unsafeRunSync()
    body should include ("statements_total{")
    body should include ("tenant=\"acme\"")
    body should include ("status=\"ok\"")
    refreshes.get() shouldBe 1

  it should "not mount the route when there is no Prometheus child" in:
    val endpoint = new MetricsEndpoint(None, () => ())
    val routes   = Http4sServerInterpreter[IO]().toRoutes(endpoint.serverEndpoints)

    val resp = routes.orNotFound.run(Request[IO](Method.GET, uri"/metrics")).unsafeRunSync()
    resp.status.code shouldBe 404