package ai.starlake.quack.edge.authz

import ai.starlake.quack.edge.BoundedBody
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s.{HttpRoutes, Status}
import org.http4s.dsl.io.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.{InetAddress, ServerSocket, Socket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicInteger

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
    new NodeInternalServer("127.0.0.1", port, List(routes)).resource
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
        new NodeInternalServer("127.0.0.1", taken.getLocalPort, List(routes)).resource
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
    new NodeInternalServer("127.0.0.1", port, List(nodeRoutes)).resource
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
