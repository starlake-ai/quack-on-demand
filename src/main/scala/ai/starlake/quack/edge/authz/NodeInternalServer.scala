package ai.starlake.quack.edge.authz

import ai.starlake.quack.edge.BoundedBody
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port}
import org.http4s.HttpRoutes
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server

import scala.concurrent.duration.*

/** The listener nodes call for verdicts and users' credentials: the routes [[DecideRoute]] and
  * [[CredentialsRoute]], in plain HTTP since qod-node speaks nothing else. It stands apart from the
  * REST port so that clients filling that port's connection slots cannot stop authorization for
  * every tenant. Every route authenticates each call with the node's key, but only after reading
  * its body, which the signature covers, so a client that reaches this port can hold
  * `maxConnections` slots and about 1 MiB of heap in each. A node opens one connection per call,
  * sends its body at once and leaves, so the idle timeout is short.
  */
final class NodeInternalServer(
    host: String,
    port: Int,
    maxConnections: Int,
    routes: List[HttpRoutes[IO]]
):

  private val bindHost = Host
    .fromString(host)
    .getOrElse(throw new IllegalArgumentException(s"the node verdict listener cannot bind '$host'"))

  private val bindPort = Port
    .fromInt(port)
    .getOrElse(
      throw new IllegalArgumentException(s"the node verdict listener cannot bind port $port")
    )

  val resource: Resource[IO, Server] =
    EmberServerBuilder
      .default[IO]
      .withHost(bindHost)
      .withPort(bindPort)
      .withMaxConnections(maxConnections)
      .withReceiveBufferSize(BoundedBody.ReadBufferBytes)
      .withIdleTimeout(10.seconds)
      .withHttpApp(routes.foldLeft(HttpRoutes.empty[IO])(_ <+> _).orNotFound)
      .withShutdownTimeout(1.second)
      .build
      .adaptError { case t =>
        new RuntimeException(
          s"the node verdict listener could not bind $host:$port (QOD_INTERNAL_PORT): ${t.getMessage}",
          t
        )
      }
