package ai.starlake.quack.module

import ai.starlake.quack.ondemand.module.ModuleEventBus
import ai.starlake.quack.spi.{ManagerContext, ManagerEvent, ManagerEventSink, ManagerModule}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.traverse.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.tapir.server.ServerEndpoint

class ModuleEventBusSpec extends AnyFlatSpec with Matchers:

  /** Recording module; optionally throws on the first N events to prove containment. */
  private class Recorder(failFirst: Int = 0) extends ManagerModule:
    val received      = new java.util.concurrent.CopyOnWriteArrayList[ManagerEvent]()
    private val fails = new java.util.concurrent.atomic.AtomicInteger(failFirst)
    def name: String  = "recorder"
    def changelogPath: Option[String]            = None
    def start(ctx: ManagerContext): IO[Unit]     = IO.unit
    def endpoints: List[ServerEndpoint[Any, IO]] = Nil
    def publicPathPrefixes: Set[String]          = Set.empty
    def onEvent(event: ManagerEvent): IO[Unit]   =
      if fails.getAndDecrement() > 0 then IO.raiseError(new RuntimeException("boom"))
      else IO { received.add(event); () }
    def stop: IO[Unit] = IO.unit

  private def awaitUntil(deadlineMs: Long = 5000)(cond: => Boolean): Unit =
    val end = System.currentTimeMillis() + deadlineMs
    while !cond && System.currentTimeMillis() < end do Thread.sleep(20)
    cond shouldBe true

  "ModuleEventBus" should "deliver emitted events to the module" in {
    val m      = new Recorder()
    val bus    = new ModuleEventBus(List(m))
    val fibers = bus.dispatchers.traverse(_.start).unsafeRunSync()
    try
      bus.sink.emit(ManagerEvent.TenantCreated("acme"))
      awaitUntil()(m.received.size == 1)
      m.received.get(0) shouldBe ManagerEvent.TenantCreated("acme")
    finally
      bus.shutdown()
      fibers.traverse(_.join).unsafeRunSync()
  }

  it should "drop on overflow without blocking and count drops" in {
    val m   = new Recorder()
    val bus = new ModuleEventBus(List(m), capacity = 2) // no dispatcher started
    (1 to 5).foreach(i => bus.sink.emit(ManagerEvent.TenantCreated(s"t$i")))
    bus.droppedCount("recorder") shouldBe 3L
  }

  it should "contain onEvent failures and keep dispatching" in {
    val m      = new Recorder(failFirst = 1)
    val bus    = new ModuleEventBus(List(m))
    val fibers = bus.dispatchers.traverse(_.start).unsafeRunSync()
    try
      bus.sink.emit(ManagerEvent.TenantCreated("dies"))
      bus.sink.emit(ManagerEvent.TenantCreated("lives"))
      awaitUntil()(m.received.size == 1)
      m.received.get(0) shouldBe ManagerEvent.TenantCreated("lives")
    finally
      bus.shutdown()
      fibers.traverse(_.join).unsafeRunSync()
  }

  it should "expose the shared noop sink when no modules are loaded" in {
    new ModuleEventBus(Nil).sink shouldBe theSameInstanceAs(ManagerEventSink.noop)
  }
