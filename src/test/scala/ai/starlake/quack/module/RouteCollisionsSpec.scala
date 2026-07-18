package ai.starlake.quack.module

import ai.starlake.quack.ondemand.module.RouteCollisions
import cats.effect.IO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint

class RouteCollisionsSpec extends AnyFlatSpec with Matchers:

  private def get(path: String): ServerEndpoint[Any, IO] =
    endpoint.get
      .in(path.stripPrefix("/").split('/').foldLeft(emptyInput: EndpointInput[Unit])(_ / _))
      .out(stringBody)
      .serverLogicSuccess[IO](_ => IO.pure("ok"))

  "RouteCollisions.check" should "report duplicate method plus path pairs" in {
    val dups = RouteCollisions.check(List(get("/api/a"), get("/api/a"), get("/api/b")))
    dups should have size 1
    dups.head should include("/api/a")
  }

  it should "return empty for distinct routes" in {
    RouteCollisions.check(List(get("/api/a"), get("/api/b"))) shouldBe empty
  }
