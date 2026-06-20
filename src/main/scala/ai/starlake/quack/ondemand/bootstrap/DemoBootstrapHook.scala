// src/main/scala/ai/starlake/quack/ondemand/bootstrap/DemoBootstrapHook.scala
package ai.starlake.quack.ondemand.bootstrap

import ai.starlake.quack.ondemand.manifest.{ConfigManifest, ManifestImporter}
import ai.starlake.quack.ondemand.state.{ControlPlaneStore, FederatedSourceStore}
import cats.effect.IO
import io.circe.yaml.v12.parser
import org.slf4j.LoggerFactory

import scala.util.Try

/** Reads the bundled (or operator-provided) demo manifest pointed at by `QOD_BOOTSTRAP_YAML` and
  * imports it via [[ManifestImporter]]. Non-fatal at every failure boundary -- a broken YAML loses
  * you the demo, not the manager.
  *
  * Dependencies are injected so the hook is unit-testable without touching the filesystem or the
  * real process environment.
  */
object DemoBootstrapHook:

  private val EnvKey    = "QOD_BOOTSTRAP_YAML"
  private val DemoNames = Set("acme", "globex")

  private val logger = LoggerFactory.getLogger(getClass)

  /** Runs the hook. Always succeeds: failure paths log and return Unit.
    *
    * @param env
    *   env-var reader (e.g. `sys.env.get`)
    * @param readFile
    *   file reader returning the manifest YAML body
    * @param store
    *   control-plane store to apply the manifest to
    * @param fedStore
    *   optional federated-source store; when provided, any federatedSources in the manifest are
    *   persisted too. Pass None to silently drop federation entries (the unit-test path uses this).
    */
  def run(
      env: String => Option[String],
      readFile: String => Try[String],
      store: ControlPlaneStore,
      fedStore: Option[FederatedSourceStore] = None
  ): IO[Unit] = IO.blocking {
    env(EnvKey) match
      case None =>
        logger.info(s"bootstrap: no manifest configured ($EnvKey unset)")
      case Some(path) =>
        readFile(path) match
          case scala.util.Failure(err) =>
            logger.warn(s"bootstrap: manifest '$path' not readable: ${err.getMessage}")
          case scala.util.Success(body) =>
            parser.parse(body).flatMap(_.as[ConfigManifest]) match
              case Left(parseErr) =>
                logger.warn(s"bootstrap: failed to parse manifest '$path': ${parseErr.getMessage}")
              case Right(manifest) =>
                applyIfFresh(manifest, store, fedStore, path)
  }

  private def applyIfFresh(
      manifest: ConfigManifest,
      store: ControlPlaneStore,
      fedStore: Option[FederatedSourceStore],
      path: String
  ): Unit =
    val existing   = store.listTenants().map(_.id).toSet
    val collisions = existing.intersect(DemoNames)
    if collisions.nonEmpty then
      logger.info(
        s"bootstrap: demo tenants already present (${collisions.mkString(", ")}), " +
          s"skipping import of '$path'"
      )
    else
      ManifestImporter.apply(manifest, store, fedStore) match
        case Left(errs) =>
          errs.foreach(e => logger.warn(s"bootstrap: import error: $e"))
        case Right(()) =>
          val fedCount = manifest.tenants.flatMap(_.tenantDbs).map(_.federatedSources.size).sum
          logger.info(
            s"bootstrap: imported manifest from '$path' " +
              s"(tenants=${manifest.tenants.size}, roles=${manifest.roles.size}, " +
              s"groups=${manifest.groups.size}, users=${manifest.users.size}, " +
              s"federatedSources=$fedCount)"
          )
