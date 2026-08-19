package ai.starlake.quack.ondemand.api

import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Grant carried by a single-use SSO ticket: the session bearer token to hand
  * to Starlake plus the identity it belongs to.
  */
final case class SsoGrant(
  sessionToken: String,
  username: String,
  tenant: Option[String],
  admin: Boolean,
  superuser: Boolean
)

/** In-process, single-use, short-TTL ticket store for the QoD to Starlake SSO
  * handoff. Tickets are 128-bit random values; the grant (including the raw
  * session token) never leaves the manager until redeemed server-to-server.
  * Single-manager scope: like the SessionTokenStore jti denylist, this map is
  * per-process; the 60s TTL keeps the HA gap negligible.
  */
final class SsoTicketStore(
  now: () => Instant = () => Instant.now(),
  ttlSeconds: Long = 60
):
  private val rng = new SecureRandom()
  private val entries = new ConcurrentHashMap[String, (SsoGrant, Instant)]()

  private def sweep(): Unit =
    val current = now()
    entries.entrySet().removeIf(e => !e.getValue._2.isAfter(current))

  def mint(grant: SsoGrant): String =
    sweep()
    val bytes = new Array[Byte](16)
    rng.nextBytes(bytes)
    val ticket = Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
    entries.put(ticket, (grant, now().plusSeconds(ttlSeconds)))
    ticket

  def redeem(ticket: String): Option[SsoGrant] =
    sweep()
    Option(entries.remove(ticket)).collect {
      case (grant, expiry) if expiry.isAfter(now()) => grant
    }

  def size: Int = entries.size()
