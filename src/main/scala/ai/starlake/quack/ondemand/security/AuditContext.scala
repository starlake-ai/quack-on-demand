package ai.starlake.quack.ondemand.security

import com.typesafe.config.Config

/** What every check reads: the configuration the manager booted with (the live one, with the values
  * the boot resolved laid over it: [[AuditConfig.resolved]]) and the control plane.
  *
  * `string`, `bool` and `csv` throw on a path the configuration does not hold, so a typo in a check
  * becomes an `error` finding (missing input is never `ok`). `optString` reads a path the shipped
  * configuration leaves without a value as empty; each use names such a path deliberately.
  */
final case class AuditContext(config: Config, facts: AuditFacts):
  def string(path: String): String    = config.getString(path).trim
  def bool(path: String): Boolean     = config.getBoolean(path)
  def csv(path: String): List[String] =
    string(path).split(',').iterator.map(_.trim).filter(_.nonEmpty).toList

  /** For a path with no default in `application.conf` (only `${?ENV}`): absent reads as empty. */
  def optString(path: String): String =
    if config.hasPath(path) then config.getString(path).trim else ""

object AuditContext:
  private val Ipv4Loopback = """127\.\d{1,3}\.\d{1,3}\.\d{1,3}""".r

  /** A bind host that only this machine reaches. Literal forms only: no name is resolved. */
  def isLoopback(host: String): Boolean =
    val h = host.trim.toLowerCase(java.util.Locale.ROOT).stripPrefix("[").stripSuffix("]")
    h == "localhost" || h == "::1" || Ipv4Loopback.matches(h)
