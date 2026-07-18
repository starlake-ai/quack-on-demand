package ai.starlake.quack.ondemand.module

import ai.starlake.quack.ondemand.state.LiquibaseRunner
import ai.starlake.quack.spi.ManagerModule
import com.typesafe.scalalogging.LazyLogging

/** Applies each module's Liquibase changelog against the control-plane database, after the core
  * changelog has run. Module tables use the qodhosted_* prefix (convention, mirrors qodstate_*). A
  * failing module migration propagates and aborts boot, exactly like a failing core one.
  * `runnerFor` is injectable so specs run without Postgres.
  */
object ModuleMigrations extends LazyLogging:
  def run(
      modules: List[ManagerModule],
      defaultMetastore: Map[String, String],
      runnerFor: (Map[String, String], String) => () => Unit = (meta, path) =>
        () => LiquibaseRunner.fromDefaultMetastore(meta, path).run()
  ): Unit =
    modules.foreach { m =>
      m.changelogPath.foreach { path =>
        logger.info(s"module ${m.name}: applying changelog $path")
        runnerFor(defaultMetastore, path)()
      }
    }
