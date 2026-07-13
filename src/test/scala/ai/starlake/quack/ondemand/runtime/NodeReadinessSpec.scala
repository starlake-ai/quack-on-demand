package ai.starlake.quack.ondemand.runtime

import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.ServerSocket
import scala.concurrent.duration._

/** Pins the readiness gate the maintenance spawn path relies on: a freshly forked node takes
  * seconds to boot (INSTALL/LOAD + ATTACH) before `quack_serve` listens, so the first chain
  * statement must not be sent until the node's port accepts a TCP connection. Regression test for
  * the "run maintenance now" failure `flush: Permanent(java.net.ConnectException)`.
  */
class NodeReadinessSpec extends AnyFlatSpec with Matchers:

  private def freePort(): Int =
    val s = new ServerSocket(0)
    try s.getLocalPort
    finally s.close()

  "awaitReachable" should "return true once the port starts listening within the timeout" in {
    val port     = freePort()
    val listener = new Thread(() => {
      Thread.sleep(500)
      val server = new ServerSocket(port)
      try
        val client = server.accept()
        client.close()
      finally server.close()
    })
    listener.setDaemon(true)
    listener.start()

    val ok = NodeReadiness
      .awaitReachable("127.0.0.1", port, timeout = 5.seconds, pollInterval = 100.millis)
      .unsafeRunSync()
    ok shouldBe true
  }

  it should "return true immediately when the port is already listening" in {
    val server = new ServerSocket(0)
    try
      val ok = NodeReadiness
        .awaitReachable("127.0.0.1", server.getLocalPort, timeout = 2.seconds)
        .unsafeRunSync()
      ok shouldBe true
    finally server.close()
  }

  it should "return false when nothing listens within the timeout" in {
    val port    = freePort()
    val started = System.nanoTime()
    val ok      = NodeReadiness
      .awaitReachable("127.0.0.1", port, timeout = 700.millis, pollInterval = 100.millis)
      .unsafeRunSync()
    val elapsedMs = (System.nanoTime() - started) / 1000000L
    ok shouldBe false
    elapsedMs should be >= 600L // honored the deadline instead of failing on first refusal
    elapsedMs should be < 5000L // and did not hang past it
  }

  it should "return false promptly when the node process dies during the wait" in {
    val port    = freePort()
    val started = System.nanoTime()
    val ok      = NodeReadiness
      .awaitReachable(
        "127.0.0.1",
        port,
        timeout = 10.seconds,
        isAlive = () => false,
        pollInterval = 100.millis
      )
      .unsafeRunSync()
    val elapsedMs = (System.nanoTime() - started) / 1000000L
    ok shouldBe false
    elapsedMs should be < 2000L // dead process short-circuits; no full-timeout wait
  }
