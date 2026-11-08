package ai.starlake.quack.ondemand.security

import com.typesafe.config.Config

/** What every check reads: the live configuration (env overrides applied) and the control plane. */
final case class AuditContext(config: Config, facts: AuditFacts):
  def string(path: String): String =
    if config.hasPath(path) then config.getString(path).trim else ""
  def bool(path: String): Boolean =
    config.hasPath(path) && config.getBoolean(path)
  def csv(path: String): List[String] =
    string(path).split(',').iterator.map(_.trim).filter(_.nonEmpty).toList

object AuditContext:
  /** A bind host that only this machine reaches. Literal forms only: no name is resolved. */
  def isLoopback(host: String): Boolean =
    val h = host.trim.toLowerCase(java.util.Locale.ROOT).stripPrefix("[").stripSuffix("]")
    h == "localhost" || h == "::1" || h.startsWith("127.")
