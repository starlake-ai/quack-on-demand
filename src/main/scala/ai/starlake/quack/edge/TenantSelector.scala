package ai.starlake.quack.edge

import ai.starlake.quack.model.PoolKey
import io.circe.parser._

import java.util.Base64

final case class Resolved(poolKey: PoolKey, user: String)

object TenantSelector:

  /** Decode a JWT body (no signature check - that's the auth module's job). Returns the named claim
    * value if present.
    */
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

  /** Resolve `(tenant, tenantDb, pool, user)` from the incoming credentials. The edge applies NO
    * defaults: every client must fully address its target via the `tenant` and `pool` headers (or,
    * on the JWT path, `tenant` may come from the configured `tenantClaim` instead). The owning
    * `tenantDb` is resolved server-side via `lookupPool`, which also enforces the `tenant.disabled`
    * / `pool.disabled` kill switches: a Left propagates back as an UNAUTHENTICATED error at the
    * FlightSQL edge.
    *
    *   - JWT path: `tenant` from a `tenant` header OR the JWT `tenantClaim` (header wins); `pool`
    *     header required; user from the `sub` claim (falls back to the bare Basic username if both
    *     are present).
    *   - Basic path: bare username; `tenant` + `pool` headers required.
    *
    * Header names are plain (`tenant`, `pool`) so that Flight JDBC URL connection parameters route
    * here naturally (`jdbc:arrow-flight-sql://host:port/?tenant=…&pool=…`). The `X-*` prefix is
    * reserved for the manager's HTTP API.
    */
  def resolve(
      bearer: Option[String],
      headers: Map[String, String],
      username: Option[String],
      tenantClaim: String,
      lookupPool: (String, String) => Either[String, String]
  ): Either[String, Resolved] =
    val hPool   = headers.get("pool").filter(_.nonEmpty)
    val hTenant = headers.get("tenant").filter(_.nonEmpty)

    def resolvePoolKey(tenant: String, pool: String): Either[String, PoolKey] =
      lookupPool(tenant, pool).map(td => PoolKey(tenant, td, pool))

    bearer match
      case Some(token) if token.split('.').length == 3 =>
        for
          tenant <- hTenant
            .orElse(claimFromJwt(token, tenantClaim))
            .toRight(s"missing tenant: provide a 'tenant' header or a '$tenantClaim' JWT claim")
          pool <- hPool.toRight("'pool' header required")
          key  <- resolvePoolKey(tenant, pool)
        yield
          val user = usernameFromJwt(token).orElse(username).getOrElse("unknown")
          Resolved(key, user)

      case _ =>
        username.filter(_.nonEmpty) match
          case None =>
            Left("no JWT or username provided")
          case Some(user) =>
            for
              tenant <- hTenant.toRight("'tenant' header required")
              pool   <- hPool.toRight("'pool' header required")
              key    <- resolvePoolKey(tenant, pool)
            yield Resolved(key, user)
