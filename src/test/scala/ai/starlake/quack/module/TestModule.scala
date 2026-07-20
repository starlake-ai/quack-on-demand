package ai.starlake.quack.module

import ai.starlake.quack.spi.*
import cats.effect.IO
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint

/** Fixture module. No-arg constructible for the ServiceLoader spec; other specs instantiate it
  * directly to reach `received` / `started` / `stoppedFlag`.
  */
class TestModule extends ManagerModule:
  val received = new java.util.concurrent.CopyOnWriteArrayList[ManagerEvent]()
  @volatile var started: Option[ManagerContext] = None
  @volatile var stoppedFlag: Boolean            = false

  def name: String                  = "test-module"
  def changelogPath: Option[String] = None

  def start(ctx: ManagerContext): IO[Unit] = IO { started = Some(ctx) }

  def endpoints: List[ServerEndpoint[Any, IO]] = List(
    endpoint.get
      .in("api" / "hosted-test" / "ping")
      .out(stringBody)
      .serverLogicSuccess[IO](_ => IO.pure("pong")),
    endpoint.get
      .in("api" / "hosted-test" / "secret")
      .out(stringBody)
      .serverLogicSuccess[IO](_ => IO.pure("classified"))
  )

  def publicPathPrefixes: Set[String] = Set("/api/hosted-test/ping")

  def onEvent(event: ManagerEvent): IO[Unit] = IO { received.add(event); () }

  def stop: IO[Unit] = IO { stoppedFlag = true }
