package ai.starlake.quack.spi

import cats.effect.IO
import sttp.tapir.server.ServerEndpoint

/** A static content mount contributed by a module.
  *
  * `diskDir`: when set AND the directory exists, content is served from the filesystem instead of
  * the classpath (live-updatable without a redeploy, e.g. the marketing site's QOD_WWW_DIR);
  * otherwise the bundled classpath resources under `classpathDir` serve as the fallback.
  *
  * `spaFallback`: true (default) routes any unmatched path to `index.html` with status 200 (SPA
  * client routing, e.g. the portal); false serves the mount's `404.html` with a genuine 404 status
  * (marketing pages must not soft-404).
  */
final case class StaticMount(
    urlPrefix: String,
    classpathDir: String,
    diskDir: Option[String] = None,
    spaFallback: Boolean = true
)

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
