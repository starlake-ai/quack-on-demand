package ai.starlake.quack.model

import java.util.{Locale, UUID}

/** What a user may store as an S3 credential of their own (in-engine authorization, decision 23),
  * checked when one is added (REST, the CLI, the UI). Whether a statement may then use a URL is
  * decided at use time against the user's stored scopes and the data-folder deny set; this object
  * only decides what may be stored (the deny set, which needs the live data paths, is checked by
  * the REST handler before the store write).
  *
  *   - `scope`: `s3://<bucket>/` or `s3://<bucket>/<prefix>/`, stored with one trailing `/`. The
  *     bucket follows S3's rules, is lowercase (DuckDB matches secret scopes case-sensitively) and
  *     is no access point alias ([[S3BucketName]]); no segment is empty, `.` or `..`, and none
  *     holds `%`, `?`, `#`, `\`, a glob character, or a code point no listing may show
  *     ([[UnshownCodePoints]]: control, format, separator) (decision 18's URL rules).
  *   - the access key id and the secret are required; the session token, the region and the URL
  *     style (`path` or `vhost`) are optional.
  *   - an endpoint only when the operator lists it (`QOD_USER_S3_ALLOWED_ENDPOINTS`): a node would
  *     otherwise make requests to any host a user names. None is AWS's own endpoint.
  *
  * Refusals are `(code, message)`; no message carries a key, the secret or the token.
  */
object UserS3CredentialRules:

  /** A credential as a request carries it. */
  final case class Input(
      scope: String,
      accessKeyId: String,
      secretAccessKey: String,
      sessionToken: Option[String] = None,
      region: Option[String] = None,
      endpoint: Option[String] = None,
      urlStyle: Option[String] = None
  ):
    override def toString: String =
      s"Input(scope=$scope,accessKeyId=$accessKeyId," +
        s"secretAccessKey=${FederatedSecret.RedactedMarker}," +
        s"sessionToken=${sessionToken.map(_ => FederatedSecret.RedactedMarker)},region=$region," +
        s"endpoint=$endpoint,urlStyle=$urlStyle)"

  /** A credential as it is stored, every value trimmed and checked. Only [[validate]] makes one. */
  final case class Accepted private[model] (
      scope: String,
      accessKeyId: String,
      secretAccessKey: String,
      sessionToken: Option[String],
      region: Option[String],
      endpoint: Option[String],
      urlStyle: Option[String]
  ):
    override def toString: String =
      s"Accepted(scope=$scope,accessKeyId=$accessKeyId," +
        s"secretAccessKey=${FederatedSecret.RedactedMarker}," +
        s"sessionToken=${sessionToken.map(_ => FederatedSecret.RedactedMarker)},region=$region," +
        s"endpoint=$endpoint,urlStyle=$urlStyle)"

  private val RegionRe     = "^[a-z0-9-]{1,64}$".r
  private val HostPortRe   = "^[a-z0-9]([a-z0-9.-]*[a-z0-9])?(:[0-9]{1,5})?$".r
  private val Forbidden    = "%?#\\*[]{}"
  private val UrlStyles    = Set("path", "vhost")
  private val MaxScope     = 1024
  private val MaxKeyId     = 256
  private val MaxSecret    = 4096
  private val MaxToken     = 8192
  private val ScopeRefusal =
    "invalid_scope" -> ("a scope is s3://<bucket>/ or s3://<bucket>/<prefix>/: a lowercase bucket, " +
      "whole path segments, no empty, '.' or '..' segment, and no '%', '?', '#', '\\', glob, " +
      "control, format or separator character")
  private val AliasRefusal =
    "invalid_scope" -> ("a scope names the bucket itself, never an access point alias (a name " +
      s"ending in ${S3BucketName.AliasSuffixes.mkString(", ")})")

  def validate(in: Input, allowedEndpoints: List[String]): Either[(String, String), Accepted] =
    for
      scope  <- normalizeScope(in.scope)
      keyId  <- required("accessKeyId", in.accessKeyId, MaxKeyId)
      secret <- required("secretAccessKey", in.secretAccessKey, MaxSecret)
      token  <- optional("sessionToken", in.sessionToken, MaxToken)
      region <- optional("region", in.region, 64).flatMap(checked(RegionRe.matches, regionRefusal))
      style  <- optional("urlStyle", in.urlStyle, 16).flatMap(
        checked(UrlStyles.contains, styleRefusal)
      )
      endpoint <- endpointOf(in.endpoint, allowedEndpoints)
    yield Accepted(scope, keyId, secret, token, region, endpoint, style)

  /** `raw` as a stored scope (see the object's scaladoc), or the refusal. */
  def normalizeScope(raw: String): Either[(String, String), String] =
    val s = raw.trim
    if !s.startsWith("s3://") || s.length > MaxScope || UnshownCodePoints.holdsAny(s) ||
      s.exists(c => Forbidden.indexOf(c) >= 0)
    then Left(ScopeRefusal)
    else
      val rest   = s.substring("s3://".length)
      val slash  = rest.indexOf('/')
      val bucket = if slash < 0 then rest else rest.substring(0, slash)
      val path   = if slash < 0 then "" else rest.substring(slash + 1)
      val body   = path.stripSuffix("/")
      val parts  = if body.isEmpty then Nil else body.split("/", -1).toList
      if S3BucketName.isAlias(bucket) then Left(AliasRefusal)
      else if !S3BucketName.valid(bucket) || path.startsWith("/") then Left(ScopeRefusal)
      else if parts.exists(p => p.isEmpty || p == "." || p == "..") then Left(ScopeRefusal)
      else Right(s"s3://$bucket/" + parts.map(_ + "/").mkString)

  /** `e` as an allowed-endpoint entry: `host[:port]` for TLS (an `https://` prefix dropped),
    * `http://host[:port]` for plain HTTP, the host lowercase, the port 1 to 65535, no path. None
    * when it is neither.
    */
  def normalizeEndpoint(e: String): Option[String] =
    val t                  = e.trim.stripSuffix("/")
    val lower              = t.toLowerCase(Locale.ROOT)
    val (prefix, hostPort) =
      if lower.startsWith("http://") then ("http://", lower.substring("http://".length))
      else if lower.startsWith("https://") then ("", lower.substring("https://".length))
      else ("", lower)
    val portOk = hostPort.lastIndexOf(':') match
      case -1 => true
      case i  => hostPort.substring(i + 1).toIntOption.exists(p => p >= 1 && p <= 65535)
    Option.when(HostPortRe.matches(hostPort) && !hostPort.contains("..") && portOk)(
      prefix + hostPort
    )

  /** `QOD_USER_S3_ALLOWED_ENDPOINTS`: comma separated `host[:port]` (TLS) or `http://host[:port]`
    * (plain). An `https://` entry is refused (write `host[:port]`), so an entry reads as exactly
    * what it allows. Left names the first entry that is neither.
    */
  def parseAllowedEndpoints(raw: String): Either[String, List[String]] =
    raw
      .split(",")
      .toList
      .map(_.trim)
      .filter(_.nonEmpty)
      .foldLeft(Right(Nil): Either[String, List[String]]) { (acc, entry) =>
        acc.flatMap { sofar =>
          if entry.toLowerCase(Locale.ROOT).startsWith("https://") then
            Left(s"'$entry': write host[:port] for a TLS endpoint")
          else
            normalizeEndpoint(entry)
              .toRight(s"'$entry' is neither host[:port] nor http://host[:port]")
              .map(sofar :+ _)
        }
      }
      .map(_.distinct)

  /** A fresh credential id (32 lowercase hex digits) whose first 8 differ from those of every id in
    * `taken`: a node names a credential's connection secret `qod_uc_<first 8>`, and two credentials
    * of one user must never share a secret name.
    */
  @annotation.tailrec
  def newId(
      taken: Iterable[String],
      fresh: () => String = () => UUID.randomUUID().toString.replace("-", "")
  ): String =
    val id = fresh()
    if taken.exists(_.take(8) == id.take(8)) then newId(taken, fresh) else id

  private val regionRefusal = "invalid_credential" -> "region is lowercase letters, digits and '-'"
  private val styleRefusal  = "invalid_credential" -> "urlStyle is path or vhost"

  private def checked(ok: String => Boolean, refusal: (String, String))(
      v: Option[String]
  ): Either[(String, String), Option[String]] =
    if v.forall(ok) then Right(v) else Left(refusal)

  private def required(name: String, v: String, max: Int): Either[(String, String), String] =
    val t = v.trim
    if t.isEmpty then Left("credential_required" -> s"$name is required")
    else if t.exists(_.isControl) || t.length > max then
      Left("invalid_credential" -> s"$name holds no control character and at most $max characters")
    else Right(t)

  private def optional(
      name: String,
      v: Option[String],
      max: Int
  ): Either[(String, String), Option[String]] =
    v.map(_.trim).filter(_.nonEmpty) match
      case None    => Right(None)
      case Some(t) =>
        if t.exists(_.isControl) || t.length > max then
          Left(
            "invalid_credential" -> s"$name holds no control character and at most $max characters"
          )
        else Right(Some(t))

  private def endpointOf(
      raw: Option[String],
      allowed: List[String]
  ): Either[(String, String), Option[String]] =
    raw.map(_.trim).filter(_.nonEmpty) match
      case None    => Right(None)
      case Some(e) =>
        normalizeEndpoint(e) match
          case None =>
            Left(
              "invalid_endpoint" -> ("an endpoint is host[:port], with http:// for plain HTTP " +
                "(https:// or nothing for TLS), and no path")
            )
          case Some(n) if allowed.contains(n) => Right(Some(n))
          case Some(n)                        =>
            Left(
              "endpoint_not_allowed" -> (
                if allowed.isEmpty then
                  "the operator allows AWS's own endpoint only (QOD_USER_S3_ALLOWED_ENDPOINTS)"
                else
                  s"$n is not an endpoint the operator allows (QOD_USER_S3_ALLOWED_ENDPOINTS: " +
                    s"${allowed.mkString(", ")})"
              )
            )
