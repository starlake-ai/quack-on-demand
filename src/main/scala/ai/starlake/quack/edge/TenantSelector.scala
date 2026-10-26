package ai.starlake.quack.edge

import ai.starlake.quack.model.PoolKey
import io.circe.parser._

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.util.Try

final case class Resolved(poolKey: PoolKey, user: String)

object TenantSelector:

  /** Decode a JWT body (no signature check - that's the auth module's job). Returns the named claim
    * value if present. The bearer comes from an unauthenticated client and runs here before any
    * provider validated it, so a body that is not base64url, or not JSON, names no claim instead of
    * throwing.
    */
  private def claimFromJwt(token: String, claim: String): Option[String] =
    token.split('.') match
      case Array(_, payload, _) =>
        Try(
          new String(Base64.getUrlDecoder.decode(padded(payload)), StandardCharsets.UTF_8)
        ).toOption
          .flatMap(decoded => parse(decoded).toOption)
          .flatMap(_.hcursor.get[String](claim).toOption)
      case _ => None

  private def usernameFromJwt(token: String): Option[String] = claimFromJwt(token, "sub")

  /** Base64URL bodies may be unpadded; add padding to reach a multiple of 4. */
  private def padded(s: String): String =
    val r = s.length % 4
    if r == 0 then s else s + "=" * (4 - r)

  /** Resolve `(tenant, tenantDb, pool, user)` from the incoming credentials. The edge applies NO
    * defaults: every client must address its target via the `tenant` and `pool` headers. The owning
    * `tenantDb` is resolved server-side via `lookupPool`, which also enforces the `tenant.disabled`
    * / `pool.disabled` kill switches: a Left propagates back as an UNAUTHENTICATED error at the
    * FlightSQL edge.
    *
    *   - JWT path: `tenant` + `pool` headers required (URL is authoritative; JWT claims are never
    *     trusted for routing). User is the validated `username` the auth module resolved (the JWT's
    *     `preferred_username`, which is what `qodstate_user.username` matches); it falls back to
    *     the raw `sub` claim only when no validated username is available (the pre-resolution the
    *     handshake runs before it validates the credential, to pick the tenant realm).
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
      lookupPool: (String, String) => Either[String, String]
  ): Either[String, Resolved] =
    val hPool   = headers.get("pool").filter(_.nonEmpty)
    val hTenant = headers.get("tenant").filter(_.nonEmpty)

    def resolvePoolKey(tenant: String, pool: String): Either[String, PoolKey] =
      lookupPool(tenant, pool).map(td => PoolKey(tenant, td, pool))

    bearer match
      case Some(token) if token.split('.').length == 3 =>
        for
          tenant <- hTenant.toRight("'tenant' header required")
          pool   <- hPool.toRight("'pool' header required")
          key    <- resolvePoolKey(tenant, pool)
        yield
          // Prefer the username the auth module validated (the JWT's
          // preferred_username, matched against qodstate_user.username). Fall
          // back to the raw `sub` only when no validated username is available
          // (the handshake's pre-resolution, before the credential is validated).
          val user = username.filter(_.nonEmpty).orElse(usernameFromJwt(token)).getOrElse("unknown")
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
