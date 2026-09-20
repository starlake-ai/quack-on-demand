package ai.starlake.quack.edge.quack

import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8

/** Identity carried in a Quack `auth_string`.
  *
  * The Quack wire has one opaque token string and no user identity, so QoD defines the token as the
  * same query-string parameters a FlightSQL JDBC URL takes after `?`, percent-encoded:
  * `tenant=acme&pool=bi&user=alice&password=s3cret`, or `tenant=...&pool=...&token=<OIDC JWT>` for
  * an external bearer, plus `superuser=true` to pick the system realm exactly as the Flight header
  * does. `tenant` accepts the display name or the `t-<8 hex>` id.
  *
  * Personal access tokens are refused on this wire (the same decision as for FlightSQL); the `pat`
  * key is reserved so a future acceptance does not change the grammar.
  */
final case class QuackCredentials(
    tenant: Option[String],
    pool: Option[String],
    basic: Option[(String, String)],
    bearer: Option[String],
    superuser: Boolean
)

object QuackCredentials:

  private val Known = Set("tenant", "pool", "user", "password", "token", "superuser", "pat")

  def parse(authString: String): Either[String, QuackCredentials] =
    val raw = Option(authString).map(_.trim).getOrElse("")
    if raw.isEmpty then Left("empty token; expected tenant=...&pool=...&user=...&password=...")
    else
      val pairs                                          = raw.split("&").toList.filter(_.nonEmpty)
      val parsed: Either[String, List[(String, String)]] =
        pairs.foldLeft[Either[String, List[(String, String)]]](Right(Nil)) { (acc, kv) =>
          acc.flatMap { done =>
            kv.indexOf('=') match
              case -1 => Left(s"expected key=value but found '${kv.take(32)}'")
              case i  =>
                val k = URLDecoder.decode(kv.substring(0, i), UTF_8).trim.toLowerCase
                val v = URLDecoder.decode(kv.substring(i + 1), UTF_8)
                if !Known.contains(k) then Left(s"unknown key '$k' in token")
                else Right(done :+ (k -> v))
          }
        }
      parsed.flatMap { kvs =>
        val m                                   = kvs.toMap
        def nonEmpty(k: String): Option[String] = m.get(k).map(_.trim).filter(_.nonEmpty)
        val user                                = nonEmpty("user")
        val password                            = m.get("password")
        val bearer                              = nonEmpty("token")
        val superuser = nonEmpty("superuser").exists(_.equalsIgnoreCase("true"))
        if m.contains("pat") then
          Left("personal access tokens are not accepted on the Quack wire; use user and password")
        else if password.isDefined && bearer.isDefined then
          Left("token carries both a password and a bearer token; pass one of them")
        else if password.isDefined && user.isEmpty then Left("password given without a user")
        else if password.isEmpty && bearer.isEmpty then
          Left("token needs a password (with user) or a bearer token")
        else
          Right(
            QuackCredentials(
              tenant = nonEmpty("tenant"),
              pool = nonEmpty("pool"),
              basic = for
                u <- user
                p <- password
              yield (u, p),
              bearer = bearer,
              superuser = superuser
            )
          )
      }
