package ai.starlake.quack.spi

import cats.effect.IO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.tapir.server.ServerEndpoint

class SpiDefaultsSpec extends AnyFlatSpec with Matchers:

  private class MinimalModule extends ManagerModule:
    def name: String                             = "minimal"
    def changelogPath: Option[String]            = None
    def start(ctx: ManagerContext): IO[Unit]     = IO.unit
    def endpoints: List[ServerEndpoint[Any, IO]] = Nil
    def publicPathPrefixes: Set[String]          = Set.empty
    def onEvent(event: ManagerEvent): IO[Unit]   = IO.unit
    def stop: IO[Unit]                           = IO.unit

  "ManagerModule" should "default staticMounts to Nil" in {
    (new MinimalModule).staticMounts shouldBe Nil
  }

  it should "default mutationGates to Nil" in {
    val m = new MinimalModule
    m.mutationGates shouldBe Nil
  }

  "ManagerEventSink.noop" should "accept events without effect" in {
    noException should be thrownBy ManagerEventSink.noop.emit(
      ManagerEvent.TenantCreated("acme")
    )
  }
