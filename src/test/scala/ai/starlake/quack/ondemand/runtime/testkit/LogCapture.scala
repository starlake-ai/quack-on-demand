package ai.starlake.quack.ondemand.runtime.testkit

import ch.qos.logback.classic.{Level, Logger as LogbackLogger}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters.*

/** Shared capture of what a logback logger receives, for specs that pin a log line (its level
  * included) and keep it off the console.
  */
object LogCapture:

  /** `body`'s result, and every event the logger `name` receives meanwhile at every level. The
    * logger's additivity is off meanwhile, so nothing reaches the console; its level, additivity
    * and appenders are restored after.
    */
  def logged[A](name: String)(body: => A): (A, List[ILoggingEvent]) =
    val logger            = LoggerFactory.getLogger(name).asInstanceOf[LogbackLogger]
    val (level, additive) = (logger.getLevel, logger.isAdditive)
    val appender          = new ListAppender[ILoggingEvent]()
    appender.start()
    logger.addAppender(appender)
    logger.setLevel(Level.TRACE)
    logger.setAdditive(false)
    // ListAppender appends under the appender's lock (AppenderBase.doAppend): a backend logs from
    // its own threads.
    try (body, appender.synchronized(appender.list.asScala.toList))
    finally
      logger.detachAppender(appender)
      logger.setLevel(level)
      logger.setAdditive(additive)

  private val DroppedKeyLine = """(\S+): ignoring metastore key '(.*)', which reaches no node""".r

  /** The lines among `events` that name a metastore key a launcher dropped
    * (`NodeMetastore.DroppedKeys`), as (level, pool, key) in order; every other event is left out.
    */
  def droppedKeyLines(events: List[ILoggingEvent]): List[(Level, String, String)] =
    events.flatMap { e =>
      DroppedKeyLine
        .findPrefixMatchOf(e.getFormattedMessage)
        .map(m => (e.getLevel, m.group(1), m.group(2)))
    }
