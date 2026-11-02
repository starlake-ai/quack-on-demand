package ai.starlake.quack.edge.authz

import ai.starlake.quack.edge.BoundedBody
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s.{HttpRoutes, Status}
import org.http4s.dsl.io.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{BufferedReader, InputStreamReader}
import java.net.{InetAddress, ServerSocket, Socket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

class NodeInternalServerSpec extends AnyFlatSpec with Matchers:

  private val routes = HttpRoutes.of[IO] {
    case POST -> Root / "api" / "internal" / "authz" / "decide" => Ok("verdict")
  }

  private def freePort(): Int =
    val s = new ServerSocket(0)
    try s.getLocalPort
    finally s.close()

  "the node verdict listener" should "serve its routes and nothing else" in:
    val port               = freePort()
    val http               = HttpClient.newHttpClient()
    def post(path: String) = http.send(
      HttpRequest
        .newBuilder(URI.create(s"http://127.0.0.1:$port$path"))
        .POST(HttpRequest.BodyPublishers.ofString("{}"))
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
    new NodeInternalServer("127.0.0.1", port, 256, List(routes)).resource
      .use(_ =>
        IO.blocking {
          post("/api/internal/authz/decide").body() shouldBe "verdict"
          post("/api/auth/login").statusCode() shouldBe 404
        }
      )
      .unsafeRunSync()

  it should "name QOD_INTERNAL_PORT when it cannot bind" in:
    val taken = new ServerSocket(0, 50, InetAddress.getLoopbackAddress)
    try
      val ex = intercept[RuntimeException](
        new NodeInternalServer("127.0.0.1", taken.getLocalPort, 256, List(routes)).resource
          .use(_ => IO.unit)
          .unsafeRunSync()
      )
      ex.getMessage should include("QOD_INTERNAL_PORT")
    finally taken.close()

  it should "refuse chunked framing and nested JSON on the wire, before a route runs" in:
    val port       = freePort()
    val http       = HttpClient.newHttpClient()
    val served     = new AtomicInteger()
    val nodeRoutes = HttpRoutes.of[IO] {
      case req @ POST -> Root / "api" / "internal" / "authz" / "decide" =>
        NodeCalls.serve(req, 4096)((_, why) => (Status.Unauthorized, why)) { (_, _, _) =>
          served.incrementAndGet()
          (Status.Ok, "verdict")
        }
    }
    def call(body: HttpRequest.BodyPublisher) = http.send(
      HttpRequest
        .newBuilder(URI.create(s"http://127.0.0.1:$port/api/internal/authz/decide"))
        .header("X-QoD-Node", "quack-a-1")
        .header("X-QoD-Signature", "0" * 64)
        .POST(body)
        .build(),
      HttpResponse.BodyHandlers.ofString()
    )
    def json(levels: Int) = HttpRequest.BodyPublishers.ofString("[" * levels + "]" * levels)
    // The whole request goes out in one write, so the refusal of its framing meets no unread bytes.
    def raw(request: String): String =
      scala.util.Using.resource(new Socket("127.0.0.1", port)) { socket =>
        socket.setSoTimeout(5000)
        socket.getOutputStream.write(request.getBytes(UTF_8))
        socket.getOutputStream.flush()
        new String(socket.getInputStream.readAllBytes(), UTF_8)
      }
    new NodeInternalServer("127.0.0.1", port, 256, List(nodeRoutes)).resource
      .use(_ =>
        IO.blocking {
          val reply = raw(
            "POST /api/internal/authz/decide HTTP/1.1\r\nHost: l\r\nX-QoD-Node: quack-a-1\r\n" +
              s"X-QoD-Signature: ${"0" * 64}\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n[]\r\n0\r\n\r\n"
          )
          reply should startWith("HTTP/1.1 411")
          reply should include(NodeCalls.ChunkedRefused)
          reply.toLowerCase should include("connection: close")
          val deep = call(json(BoundedBody.MaxNesting + 1))
          (deep.statusCode(), deep.body()) shouldBe
            (Status.BadRequest.code, NodeCalls.BodyTooDeep)
          served.get() shouldBe 0
          val ok = call(json(BoundedBody.MaxNesting))
          (ok.statusCode(), ok.body()) shouldBe (Status.Ok.code, "verdict")
          served.get() shouldBe 1
        }
      )
      .unsafeRunSync()

  // What bounds the listener: nobody holding a node key is needed to reach these, so each limit is
  // pinned on the wire.

  /** A call to the decide route as qod-node makes it: both headers well formed, `lengthLine` after
    * them, the body (if any) to follow.
    */
  private def decideCall(lengthLine: String): String =
    "POST /api/internal/authz/decide HTTP/1.1\r\nHost: l\r\nX-QoD-Node: quack-a-1\r\n" +
      s"X-QoD-Signature: ${"0" * 64}\r\n$lengthLine\r\n\r\n"

  /** A response off `reader`: its status line and, by its Content-Length, its body. */
  private def readResponse(reader: BufferedReader): (String, String) =
    val status  = reader.readLine()
    val headers =
      Iterator.continually(reader.readLine()).takeWhile(h => h != null && h.nonEmpty).toList
    val length = headers
      .collectFirst {
        case h if h.toLowerCase.startsWith("content-length:") =>
          h.substring("content-length:".length).trim.toInt
      }
      .getOrElse(0)
    (status, new String(Array.fill(length)(reader.read().toChar)))

  private def reader(socket: Socket): BufferedReader =
    new BufferedReader(new InputStreamReader(socket.getInputStream, ISO_8859_1))

  it should "refuse a host or port it cannot bind, never falling back to another" in:
    val badHost = intercept[IllegalArgumentException](
      new NodeInternalServer("not a host", 20901, 256, Nil)
    )
    badHost.getMessage should include("not a host")
    val badPort = intercept[IllegalArgumentException](
      new NodeInternalServer("127.0.0.1", 70000, 256, Nil)
    )
    badPort.getMessage should include("70000")

  it should "hold a client while its connection slots are taken, and answer it once one frees" in:
    val port = freePort()
    new NodeInternalServer("127.0.0.1", port, 1, List(routes)).resource
      .use(_ =>
        IO.blocking {
          def send(socket: Socket): Unit =
            socket.getOutputStream.write((decideCall("Content-Length: 2") + "{}").getBytes(UTF_8))
            socket.getOutputStream.flush()
          // One slot, held by a connection that is answered and then kept open.
          val holder = new Socket("127.0.0.1", port)
          try
            holder.setSoTimeout(15000)
            send(holder)
            readResponse(reader(holder))._1 should startWith("HTTP/1.1 200")
            val client = new Socket("127.0.0.1", port)
            try
              send(client)
              // The client connects and sends its call, and is not answered while the slot is taken.
              client.setSoTimeout(1500)
              a[java.net.SocketTimeoutException] should be thrownBy client.getInputStream.read()
              // Once the holder leaves, the slot is free and the client is answered.
              holder.close()
              client.setSoTimeout(15000)
              readResponse(reader(client)) shouldBe ("HTTP/1.1 200 OK", "verdict")
            finally client.close()
          finally holder.close()
        }
      )
      .unsafeRunSync()

  it should "close a connection that goes idle" in:
    val port = freePort()
    new NodeInternalServer("127.0.0.1", port, 256, List(routes)).resource
      .use(_ =>
        IO.blocking {
          scala.util.Using.resource(new Socket("127.0.0.1", port)) { socket =>
            // A node opens a connection per call and leaves: one kept open and silent is closed
            // well before Ember's default minute.
            socket.setSoTimeout(12000)
            socket.getOutputStream.write((decideCall("Content-Length: 2") + "{}").getBytes(UTF_8))
            socket.getOutputStream.flush()
            val in = reader(socket)
            readResponse(in)._1 should startWith("HTTP/1.1 200")
            in.read() shouldBe -1
          }
        }
      )
      .unsafeRunSync()

  it should "answer a body that stops arriving 408 at the body deadline, closing the connection" in:
    val port       = freePort()
    val served     = new AtomicInteger()
    val nodeRoutes = HttpRoutes.of[IO] {
      case req @ POST -> Root / "api" / "internal" / "authz" / "decide" =>
        NodeCalls.serve(req, 4096)((_, why) => (Status.Unauthorized, why)) { (_, _, _) =>
          served.incrementAndGet()
          (Status.Ok, "verdict")
        }
    }
    new NodeInternalServer("127.0.0.1", port, 256, List(nodeRoutes)).resource
      .use(_ =>
        IO.blocking {
          scala.util.Using.resource(new Socket("127.0.0.1", port)) { socket =>
            socket.setSoTimeout(8000)
            // Well-formed headers announcing 100 bytes, and one byte of them.
            socket.getOutputStream.write((decideCall("Content-Length: 100") + "{").getBytes(UTF_8))
            socket.getOutputStream.flush()
            val sent   = System.nanoTime()
            val reply  = new String(socket.getInputStream.readAllBytes(), UTF_8)
            val tookMs = (System.nanoTime() - sent) / 1_000_000
            reply should startWith("HTTP/1.1 408")
            reply should include(NodeCalls.BodyTooSlow)
            reply.toLowerCase should include("connection: close")
            // The body deadline (5 s), not an earlier refusal and not the listener's idle timeout.
            tookMs should be >= 4000L
            tookMs should be < 8000L
          }
          served.get() shouldBe 0
        }
      )
      .unsafeRunSync()

  it should "read a body no more than ReadBufferBytes at a time" in:
    // Ember keeps a buffer of this size for every connection, whatever it sends, so it sets what a
    // flood of connections holds (BoundedBody.ReadBufferBytes). The route lets the body pile up in
    // the socket before it reads, so that a read has more than the buffer to take: with Ember's
    // default 256 KiB, a read hands the route well over 32 KiB at once.
    val port    = freePort()
    val largest = new AtomicInteger(0)
    val slow    = HttpRoutes.of[IO] {
      case req @ POST -> Root / "api" / "internal" / "authz" / "decide" =>
        IO.sleep(500.millis) *>
          req.body.chunks
            .evalMap(c => IO(largest.accumulateAndGet(c.size, (a, b) => math.max(a, b))))
            .compile
            .drain *> Ok("read")
    }
    val body = new Array[Byte](512 * 1024)
    new NodeInternalServer("127.0.0.1", port, 256, List(slow)).resource
      .use(_ =>
        IO.blocking {
          scala.util.Using.resource(new Socket("127.0.0.1", port)) { socket =>
            socket.setSoTimeout(15000)
            socket.getOutputStream.write(
              decideCall(s"Content-Length: ${body.length}").getBytes(UTF_8)
            )
            socket.getOutputStream.write(body)
            socket.getOutputStream.flush()
            readResponse(reader(socket))._1 should startWith("HTTP/1.1 200")
          }
          largest.get should be > 0
          largest.get should be <= BoundedBody.ReadBufferBytes
        }
      )
      .unsafeRunSync()
