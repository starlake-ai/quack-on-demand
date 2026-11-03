package ai.starlake.quack.edge.auth

import com.sun.net.httpserver.HttpServer

import java.io.IOException
import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue, Executors}

/** A stand-in identity provider on loopback, for the specs that drive a real `HttpClient` through
  * the manager's wiring: it answers each path with a canned status and body, and records the
  * request bodies it received. Close it when the case is done.
  */
final class LocalIdp extends AutoCloseable:
  private val executor = Executors.newCachedThreadPool()
  private val server   =
    HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress, 0), 0)
  private val routes = new ConcurrentHashMap[String, String => (Int, String)]()

  /** (path, request body) of every request served, in arrival order. */
  val requests = new ConcurrentLinkedQueue[(String, String)]()

  /** How many answers the client stopped reading before the end: a client that took the whole
    * answer leaves this alone, so a case can tell a reader that cut an answer short from one that
    * was merely given an error.
    */
  val aborted = new AtomicInteger

  server.setExecutor(executor)
  server.createContext(
    "/",
    ex =>
      try
        val in = new String(ex.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
        requests.add((ex.getRequestURI.getPath, in))
        val answer         = Option(routes.get(ex.getRequestURI.getPath)).map(_(in))
        val (status, body) = answer.getOrElse((404, "not found"))
        val bytes          = body.getBytes(StandardCharsets.UTF_8)
        ex.sendResponseHeaders(status, bytes.length.toLong)
        try ex.getResponseBody.write(bytes)
        catch case _: IOException => aborted.incrementAndGet()
      catch case _: IOException => ()
      finally ex.close()
  )
  server.start()

  def base: String = s"http://127.0.0.1:${server.getAddress.getPort}"

  /** Answers `path` with `status` and `body`. */
  def serve(path: String, status: Int, body: String): Unit =
    routes.put(path, _ => (status, body))

  /** Answers `path` with what `answer` makes of the request body. */
  def serveWith(path: String)(answer: String => (Int, String)): Unit =
    routes.put(path, answer)

  override def close(): Unit =
    server.stop(0)
    executor.shutdownNow()
    ()

object LocalIdp:

  /** An OIDC discovery document for `issuer`, with `padBytes` of padding that a reader has to take
    * in whole to parse it: valid JSON, so only a size limit can refuse it.
    */
  def discoveryDoc(issuer: String, padBytes: Int = 0): String =
    s"""{"issuer":"$issuer","authorization_endpoint":"$issuer/auth",""" +
      s""""token_endpoint":"$issuer/token","jwks_uri":"$issuer/jwks",""" +
      s""""pad":"${"x" * padBytes}"}"""
