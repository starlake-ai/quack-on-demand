package ai.starlake.quack.ondemand.runtime

import cats.effect.IO

import java.net.{InetSocketAddress, Socket}
import scala.concurrent.duration._

/** Polls a freshly spawned node's port until it accepts a TCP connection. `LocalQuackBackend.start`
  * returns at fork time, seconds before the node's DuckDB finishes INSTALL/LOAD + ATTACH and
  * `quack_serve` starts listening; any caller that sends SQL straight after `start` (the
  * maintenance runner's ephemeral `__maint` node) must gate on this first or the first statement
  * dies with ConnectException.
  */
object NodeReadiness:

  private val ConnectProbeTimeoutMs = 250

  /** True once (host, port) accepts a TCP connection before `timeout` elapses; false when the
    * deadline passes or `isAlive()` reports the node process dead (short-circuits the wait).
    */
  def awaitReachable(
      host: String,
      port: Int,
      timeout: FiniteDuration,
      isAlive: () => Boolean = () => true,
      pollInterval: FiniteDuration = 250.millis
  ): IO[Boolean] =
    IO.monotonic.flatMap { start =>
      def canConnect(): Boolean =
        val sock = new Socket()
        try
          sock.connect(new InetSocketAddress(host, port), ConnectProbeTimeoutMs)
          true
        catch case _: Throwable => false
        finally
          try sock.close()
          catch case _: Throwable => ()

      def loop: IO[Boolean] =
        if !isAlive() then IO.pure(false)
        else
          IO.blocking(canConnect()).flatMap { connected =>
            if connected then IO.pure(true)
            else
              IO.monotonic.flatMap { now =>
                if now - start + pollInterval > timeout then IO.pure(false)
                else IO.sleep(pollInterval) *> loop
              }
          }

      loop
    }
