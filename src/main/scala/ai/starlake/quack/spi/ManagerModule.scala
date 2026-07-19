package ai.starlake.quack.spi

import cats.effect.IO
import sttp.tapir.server.ServerEndpoint

/** A static SPA mount: `urlPrefix` on the wire (e.g. "/portal") served from `classpathDir`
  * resources (e.g. "/portal") with an index.html SPA fallback, exactly like the core "/ui" mount.
  */
final case class StaticMount(urlPrefix: String, classpathDir: String)

/** The plug-in contract for jars discovered via
  * `META-INF/services/ai.starlake.quack.spi.ManagerModule`.
  *
  * Lifecycle: no-arg construction (ServiceLoader) -> changelogPath applied -> start(ctx) ->
  * endpoints/publicPathPrefixes/staticMounts read once -> onEvent for the process lifetime -> stop
  * during graceful shutdown. Boot-time failures (construction, migration, start) abort the manager
  * boot; onEvent failures are contained by the dispatcher.
  */
trait ManagerModule:
  def name: String
  def changelogPath: Option[String]
  def start(ctx: ManagerContext): IO[Unit]
  def endpoints: List[ServerEndpoint[Any, IO]]
  def publicPathPrefixes: Set[String]
  def staticMounts: List[StaticMount] = Nil
  /** Veto hooks for structure mutations (quota policy). Read by the manager AFTER start() returns,
    * same contract as endpoints: a module may build gates inside start().
    */
  def mutationGates: List[MutationGate] = Nil
  def onEvent(event: ManagerEvent): IO[Unit]
  def stop: IO[Unit]
