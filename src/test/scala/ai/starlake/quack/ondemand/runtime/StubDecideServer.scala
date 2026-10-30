package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.edge.authz.{NodeHandles, Verdict, Verdicts}
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.circe.Json
import io.circe.parser.parse

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.jdk.CollectionConverters.*

/** A stand-in for the manager's decision endpoint (`POST /api/internal/authz/decide`), for specs
  * that run a real qod-node without a manager. It checks the node's request signature with the
  * node's key (derived from `secret`, as the manager derives it), answers with a verdict the
  * manager's own signer signs in the wire format the node verifies (decision 14), and records what
  * it was asked. `decide` picks the answer per (kind, description): `Right(())` allows,
  * `Left(reason)` refuses; a `status` other than 200 is answered instead of any verdict.
  */
final class StubDecideServer private (secret: String, server: HttpServer):

  @volatile var decide: (String, Json) => Either[String, Unit] = (_, _) => Right(())
  @volatile var status: Int                                    = 200

  private val asked = new ConcurrentLinkedQueue[StubDecideServer.Asked]()
  private val plans = new java.util.concurrent.atomic.AtomicLong(0)

  def url: String = s"http://127.0.0.1:${server.getAddress.getPort}"

  /** Every (kind, description) asked so far, oldest first. */
  def requests: List[(String, Json)] = asked.asScala.toList.map(a => (a.kind, a.description))

  /** The plan decisions asked so far, in constant time (the latency spec reads it every statement).
    */
  def planCalls: Long = plans.get()

  /** The principal id of every call so far, oldest first. */
  def principals: List[String] = asked.asScala.toList.map(_.principal)

  def stop(): Unit = server.stop(0)

  private[runtime] def handle(exchange: HttpExchange): Unit =
    try
      val body      = exchange.getRequestBody.readAllBytes()
      val node      = Option(exchange.getRequestHeaders.getFirst("X-QoD-Node")).getOrElse("")
      val signature = Option(exchange.getRequestHeaders.getFirst("X-QoD-Signature")).getOrElse("")
      val key       = NodeHandles.nodeKey(secret, node)
      val signed    = StubDecideServer.hex(StubDecideServer.hmac(key, body))
      if node.isEmpty || !MessageDigest.isEqual(signed.getBytes(UTF_8), signature.getBytes(UTF_8))
      then respond(exchange, 401, "signature does not verify")
      else if status != 200 then respond(exchange, status, "decision unavailable")
      else
        verdict(key, new String(body, UTF_8)) match
          case Right(answer) => respond(exchange, 200, answer)
          case Left(error)   => respond(exchange, 400, error)
    finally exchange.close()

  /** The signed verdict for one decide request body, or why the body is malformed. */
  private def verdict(key: Array[Byte], body: String): Either[String, String] =
    for
      request <- parse(body).left.map(_.message)
      cursor = request.hcursor
      kind    <- cursor.get[String]("kind").left.map(_.message)
      encoded <- cursor.get[String]("description").left.map(_.message)
      handle  <- cursor.get[String]("handle").left.map(_.message)
      bytes = Base64.getUrlDecoder.decode(encoded)
      description <- parse(new String(bytes, UTF_8)).left.map(_.message)
      payload     <- parse(
        new String(Base64.getUrlDecoder.decode(handle.takeWhile(_ != '.')), UTF_8)
      ).left
        .map(_.message)
      principal <- payload.hcursor.downField("principal").get[String]("id").left.map(_.message)
      version   <- payload.hcursor.get[Long]("policyVersion").left.map(_.message)
      ctx       <- payload.hcursor.get[String]("ctx").left.map(_.message)
    yield
      asked.add(StubDecideServer.Asked(kind, description, principal))
      if kind == "plan" then plans.incrementAndGet()
      val answer = decide(kind, description)
      val now    = System.currentTimeMillis()
      Verdicts.sign(
        Verdict(
          kind = kind,
          decision = if answer.isRight then "allow" else "refuse",
          reason = answer.left.getOrElse(""),
          retryable = false,
          principal = principal,
          policyVersion = version,
          fingerprint = StubDecideServer.hex(MessageDigest.getInstance("SHA-256").digest(bytes)),
          // A screen verdict expires at once (the node never caches it), a plan verdict in a minute.
          expiresAt = if kind == "screen" then now else now + 60000L,
          ctx = ctx
        ),
        key
      )

  private def respond(exchange: HttpExchange, code: Int, text: String): Unit =
    val bytes = text.getBytes(UTF_8)
    exchange.getResponseHeaders.set("Content-Type", "text/plain")
    exchange.sendResponseHeaders(code, bytes.length.toLong)
    exchange.getResponseBody.write(bytes)

object StubDecideServer:

  private final case class Asked(kind: String, description: Json, principal: String)

  /** Started on a free loopback port; give nodes keys whose replica is its
    * [[StubDecideServer.url]]:
    * `new NodeKeys(secret, stub.url, versions = PolicyVersions.zero, readOnlyCatalogs = ReadOnlyCatalogs.none)`.
    */
  def start(secret: String): StubDecideServer =
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    val stub   = new StubDecideServer(secret, server)
    server.createContext("/api/internal/authz/decide", exchange => stub.handle(exchange))
    server.start()
    stub

  private[runtime] def hmac(key: Array[Byte], data: Array[Byte]): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key, "HmacSHA256"))
    mac.doFinal(data)

  private[runtime] def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString
