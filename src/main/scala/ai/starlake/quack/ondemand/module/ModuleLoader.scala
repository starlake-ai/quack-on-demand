package ai.starlake.quack.ondemand.module

import ai.starlake.quack.spi.ManagerModule
import com.typesafe.scalalogging.LazyLogging

import scala.jdk.CollectionConverters.*

/** Classpath discovery via `META-INF/services/ai.starlake.quack.spi.ManagerModule`. Presence on the
  * classpath is the opt-in; there is no config key. Instantiation failure propagates and aborts
  * boot by design: a hosted deploy with its module half-missing must not come up.
  */
object ModuleLoader extends LazyLogging:
  def discover(): List[ManagerModule] =
    val found = java.util.ServiceLoader.load(classOf[ManagerModule]).iterator().asScala.toList
    found.foreach(m => logger.info(s"module loaded: ${m.name} (${m.getClass.getName})"))
    found
