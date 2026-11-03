package ai.starlake.quack.edge.authz

import ai.starlake.quack.edge.{BoundedBody, ConnectionBudget}
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
  * `maxConnections` slots and about 1.4 MiB of heap in each (the 1 MiB body, and the parsed header
  * block, read buffer and state Ember holds for the request: `ConnectionBudget.heldBytes`), and the
  * listener takes no more connections than a fifth of the heap holds. A node opens one connection
  * per call, sends its body at once and leaves, so the idle timeout is short.
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

  /** The listener's server as configured and not yet started, so that its limits can be read. */
  private[authz] val builder: EmberServerBuilder[IO] =
    EmberServerBuilder
      .default[IO]
      .withHost(bindHost)
      .withPort(bindPort)
      .withMaxConnections(
        ConnectionBudget.slots(
          "The node verdict listener",
          "QOD_INTERNAL_MAX_CONNECTIONS",
          maxConnections,
          ConnectionBudget.heldBytes(
            NodeInternalServer.MaxHeaderBytes,
            DecideRoute.MaxBodyBytes,
            tls = false
          ),
          // Both limits are constants: only the heap takes more.
          limitsTunable = false
        )
      )
      .withReceiveBufferSize(BoundedBody.ReadBufferBytes)
      .withMaxHeaderSize(NodeInternalServer.MaxHeaderBytes)
      .withErrorHandler(BoundedBody.headerLimitHandler(BoundedBody.headersTooLarge))
      .withIdleTimeout(10.seconds)
      .withHttpApp(routes.foldLeft(HttpRoutes.empty[IO])(_ <+> _).orNotFound)
      .withShutdownTimeout(1.second)

  val resource: Resource[IO, Server] =
    builder.build
      .adaptError { case t =>
        new RuntimeException(
          s"the node verdict listener could not bind $host:$port (QOD_INTERNAL_PORT): ${t.getMessage}",
          t
        )
      }

object NodeInternalServer:

  /** The largest request line, and the largest header block, the listener reads, in bytes each. A
    * node sends under 400, and Ember holds a parsed line and block for the whole request, about 40
    * heap bytes per wire byte, before any key is checked.
    */
  val MaxHeaderBytes: Int = 4096
