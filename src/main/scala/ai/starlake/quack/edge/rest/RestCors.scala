package ai.starlake.quack.edge.rest

import java.net.URI
import java.util.Locale
import scala.util.Try

/** Cross-origin access to the REST data edge, from `corsAllowedOrigins`.
  *
  * Off (no CORS header at all, and `OPTIONS` stays a 405) unless origins are configured: either the
  * single entry `*`, or exact origins (`scheme://host[:port]`, http or https, nothing else). A
  * browser then gets `Access-Control-Allow-Origin` for an allowed `Origin` (the origin itself, or
  * `*`), the edge's own headers exposed to scripts, and a preflight answer that admits `GET` with
  * `Authorization` and `Accept` only. `Access-Control-Allow-Credentials` is never sent: the edge
  * reads no cookie, so a browser caller presents its token explicitly and ambient credentials can
  * never ride along. Every response carries `Vary: Origin` once CORS is on, so a shared cache never
  * serves one origin's answer to another.
  */
final case class RestCors(allowAny: Boolean, origins: Set[String]):

  def enabled: Boolean = allowAny || origins.nonEmpty

  /** The `Access-Control-Allow-Origin` value for a request's `Origin`, when it is allowed. */
  def allowOrigin(origin: String): Option[String] =
    if allowAny then Some("*") else Option.when(origins.contains(origin))(origin)

object RestCors:

  val Off: RestCors = RestCors(allowAny = false, origins = Set.empty)

  val AllowMethods  = "GET"
  val AllowHeaders  = "Authorization, Accept"
  val ExposeHeaders =
    "X-QoD-Snapshot, X-QoD-Truncated, X-QoD-Limit, Content-Range, X-Request-Id, Retry-After"

  /** How long a browser may cache a preflight answer, in seconds. */
  val MaxAgeSec = 600

  /** Parses the comma-separated setting; the error names the env var and the offending entry. */
  def parse(raw: String): Either[String, RestCors] =
    val entries = raw.split(",").toList.map(_.trim).filter(_.nonEmpty)
    if entries.isEmpty then Right(Off)
    else if entries.contains("*") then
      if entries.size == 1 then Right(RestCors(allowAny = true, Set.empty))
      else Left("QOD_REST_CORS_ALLOWED_ORIGINS: '*' cannot be combined with other origins")
    else
      entries
        .map(e => origin(e).toRight(s"QOD_REST_CORS_ALLOWED_ORIGINS: not an origin: '$e'"))
        .partitionMap(identity) match
        case (Nil, ok) => Right(RestCors(allowAny = false, ok.toSet))
        case (errs, _) => Left(errs.head)

  /** An entry as the browser serialises an origin (lowercase scheme and host, the port only when
    * given and not the scheme's default: a browser sends `https://a.example`, never
    * `https://a.example:443`), or None when it is anything more or less than
    * `scheme://host[:port]`.
    */
  private def origin(entry: String): Option[String] =
    Try(new URI(entry)).toOption.flatMap { u =>
      val scheme = Option(u.getScheme).map(_.toLowerCase(Locale.ROOT))
      val host   = Option(u.getHost).map(_.toLowerCase(Locale.ROOT))
      val bare   = Option(u.getRawPath).forall(_.isEmpty) && u.getRawQuery == null &&
        u.getRawFragment == null && u.getRawUserInfo == null
      val port = u.getPort
      for
        s <- scheme.filter(s => s == "http" || s == "https")
        h <- host.filter(_.nonEmpty)
        if bare && (port == -1 || (port >= 1 && port <= 65535))
      yield
        val default = (s == "https" && port == 443) || (s == "http" && port == 80)
        if port == -1 || default then s"$s://$h" else s"$s://$h:$port"
    }
