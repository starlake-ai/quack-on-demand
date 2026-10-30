package ai.starlake.quack.it.authz

import com.sun.net.httpserver.{HttpExchange, HttpServer}

import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.util.Try

/** An OPA endpoint on 127.0.0.1 for the OPA-tenant case: the `connect` rule always allows; the
  * `statement` rule denies an input naming table `denyTable` (with a reason), allows any other, and
  * answers HTTP 500 while [[failStatements]] is set, an outage. Counts the statement decisions it
  * was asked, so a spec can tell a local refusal never reached it.
  */
final class FakeOpa(denyTable: String):

  private val server =
    HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0)
  private val asked  = new AtomicInteger(0)
  private val denied = ("\"table\"\\s*:\\s*\"" + denyTable + "\"").r

  /** While true, every statement decision answers 500. */
  val failStatements = new AtomicBoolean(false)

  def start(): Unit =
    server.createContext("/", (exchange: HttpExchange) => serve(exchange))
    server.start()

  def stop(): Unit = Try(server.stop(0))

  /** The base URL a tenant's `opaUrl` names. */
  def url: String = s"http://127.0.0.1:${server.getAddress.getPort}"

  /** How many statement decisions it was asked so far. */
  def statementCalls: Int = asked.get()

  private def serve(exchange: HttpExchange): Unit =
    try
      val body = new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      val path = exchange.getRequestURI.getPath
      val (status, answer) =
        if path.endsWith("/connect") then (200, """{"result":{"allow":true}}""")
        else if path.endsWith("/statement") then
          asked.incrementAndGet()
          if failStatements.get() then (500, """{"error":"e2e outage"}""")
          else if denied.findFirstIn(body).isDefined then
            (200, s"""{"result":{"allow":false,"reason":"e2e: $denyTable is off limits"}}""")
          else (200, """{"result":{"allow":true}}""")
        else (404, "{}")
      val bytes = answer.getBytes(StandardCharsets.UTF_8)
      exchange.getResponseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(status, bytes.length.toLong)
      exchange.getResponseBody.write(bytes)
    finally exchange.close()
