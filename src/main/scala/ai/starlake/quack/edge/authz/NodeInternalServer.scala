package ai.starlake.quack.edge.authz

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
  * every tenant. Every route authenticates each call with the node's key.
  */
final class NodeInternalServer(host: String, port: Int, routes: List[HttpRoutes[IO]]):

  val resource: Resource[IO, Server] =
    EmberServerBuilder
      .default[IO]
      .withHost(Host.fromString(host).getOrElse(Host.fromString("0.0.0.0").get))
      .withPort(Port.fromInt(port).getOrElse(Port.fromInt(20901).get))
      .withHttpApp(routes.foldLeft(HttpRoutes.empty[IO])(_ <+> _).orNotFound)
      .withShutdownTimeout(1.second)
      .build
      .adaptError { case t =>
        new RuntimeException(
          s"the node verdict listener could not bind $host:$port (QOD_INTERNAL_PORT): ${t.getMessage}",
          t
        )
      }
