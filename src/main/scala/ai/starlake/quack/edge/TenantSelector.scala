package ai.starlake.quack.edge

import ai.starlake.quack.model.PoolKey
import io.circe.parser._
import java.util.Base64

final case class Resolved(poolKey: PoolKey, user: String)

object TenantSelector:

  /** Decode a JWT body (no signature check - that's the auth module's job).
    * Returns the named claim value if present. */
  private def claimFromJwt(token: String, claim: String): Option[String] =
    token.split('.') match
      case Array(_, payload, _) =>
        val decoded = new String(Base64.getUrlDecoder.decode(padded(payload)))
        parse(decoded).toOption.flatMap(_.hcursor.get[String](claim).toOption)
      case _ => None

  private def usernameFromJwt(token: String): Option[String] = claimFromJwt(token, "sub")

  /** Base64URL bodies may be unpadded; add padding to reach a multiple of 4. */
  private def padded(s: String): String =
    val r = s.length % 4
    if r == 0 then s else s + "=" * (4 - r)

  /** Resolve tenant + pool + user from the incoming credentials.
    *
    *  - JWT path: `tenantClaim` -> tenant if present, else `defaultTenant`.
    *    Pool from `X-Pool` header if present, else `defaultPool`.
    *  - Username path (fallback when no JWT): split the username on `/`:
    *      * 3 parts (`tenant/pool/user`) -> explicit tenant + explicit pool (X-Pool ignored)
    *      * 2 parts (`pool/user`)        -> `defaultTenant` + explicit pool (X-Pool ignored)
    *      * 1 part  (`user`)             -> `defaultTenant`, `X-Pool` or `defaultPool`
    *      * any empty segment            -> rejected
    *  - `X-Pool` only fills the pool when it would otherwise come from `defaultPool`
    *    (1-part username, or JWT path without pool). It never overrides an explicit pool. */
  def resolve(
      bearer: Option[String],
      headers: Map[String, String],
      username: Option[String],
      tenantClaim: String,
      defaultTenant: String,
      defaultPool: String
  ): Either[String, Resolved] =
    val xPool = headers.get("X-Pool").filter(_.nonEmpty)

    bearer match
      case Some(token) if token.split('.').length == 3 =>
        val tenant = claimFromJwt(token, tenantClaim).getOrElse(defaultTenant)
        val pool   = xPool.getOrElse(defaultPool)
        val user   = usernameFromJwt(token).orElse(username).getOrElse("unknown")
        Right(Resolved(PoolKey(tenant, pool), user))

      case _ =>
        username match
          case Some(u) if u.nonEmpty =>
            val parts = u.split("/", -1).toList
            parts match
              case t :: p :: user :: Nil if t.nonEmpty && p.nonEmpty && user.nonEmpty =>
                Right(Resolved(PoolKey(t, p), user))
              case p :: user :: Nil if p.nonEmpty && user.nonEmpty =>
                Right(Resolved(PoolKey(defaultTenant, p), user))
              case user :: Nil if user.nonEmpty =>
                Right(Resolved(PoolKey(defaultTenant, xPool.getOrElse(defaultPool)), user))
              case _ =>
                Left(s"username '$u' has empty segment(s)")
          case _ =>
            Left("no JWT or username provided")