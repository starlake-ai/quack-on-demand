package ai.starlake.quack.ondemand.telemetry

import java.time.Instant

/** A data-plane handshake that was refused: the credential failed, or it named a principal with no
  * access to the pool. Never carries the credential. `user` is the name the client gave (Basic) or
  * the one its token named once validated; None when nothing names one (a bearer that failed).
  * `reason` is one of [[HandshakeAudit.Reasons]], never a provider's own text, which can quote what
  * an identity provider answered.
  */
final case class HandshakeRefusal(
    user: Option[String],
    tenant: Option[String],
    pool: Option[String],
    superuser: Boolean,
    client: Option[String],
    reason: String
)

/** Writes `auth.login.failure` for refused FlightSQL and native Quack handshakes (issue #202), the
  * rows the REST login writes for the console.
  *
  * A handshake needs no credential, so whoever connects sets the rate: at most one row a minute per
  * (front door, client address, user name), and a row is kept for the audit retention. A client
  * retrying a stale password writes one row a minute; a sender rotating user names writes one per
  * name, which the password check holds down for Basic. A bearer that failed names no user and is
  * keyed by its address alone. The key reads the cleaned values, so its size is bounded too.
  *
  * Rows go through `offer` (the event journal's bounded queue in production), never a synchronous
  * write on the handshake's thread.
  */
final class HandshakeAudit(
    offer: AuditEvent => Unit,
    limiter: AuditRateLimiter = new AuditRateLimiter()
):
  import AuditRecorder.{clean, MaxNameChars}

  /** `edge` is the front door: `flightsql` or `quack`, the row's origin. */
  def refused(edge: String, r: HandshakeRefusal): Unit =
    val user   = r.user.map(clean(_, MaxNameChars)).filter(_.nonEmpty)
    val client = r.client.map(clean(_, MaxNameChars)).filter(_.nonEmpty).getOrElse("unknown")
    if limiter.allow(s"$edge\u0000$client\u0000${user.getOrElse("")}") then
      offer(
        AuditEvent(
          Instant.now(),
          "auth",
          user.getOrElse("anonymous"),
          if r.superuser then "system" else "tenant",
          r.tenant.map(clean(_, MaxNameChars)).filter(_.nonEmpty),
          AuditActions.AuthLoginFailure,
          None,
          "denied",
          edge,
          Map("source" -> client, "reason" -> r.reason) ++
            user.map("username" -> _) ++
            r.pool.map(p => "pool" -> clean(p, MaxNameChars)).filter(_._2.nonEmpty)
        )
      )

object HandshakeAudit:

  /** Records nothing: what the manager wires unless `QOD_AUDIT_HANDSHAKE_FAILURES` is true, and
    * what specs that do not read audit rows pass.
    */
  val noop: HandshakeAudit = new HandshakeAudit(_ => ())

  /** The journal's queue, as production writes. */
  def toJournal(journal: EventJournal): HandshakeAudit = new HandshakeAudit(journal.offer)

  val InvalidCredentials     = "invalid_credentials"
  val AccountLocked          = "account_locked"
  val PasswordChangeRequired = "password_change_required"
  val NoCredentials          = "no_credentials"
  val NoAuthenticator        = "no_authenticator"
  val UnknownTarget          = "unknown_target"
  val SessionEnded           = "session_ended"
  val NoUserName             = "no_user_name"
  val UnverifiedEmail        = "unverified_email"
  val TenantForbidden        = "tenant_forbidden"
  val NoPoolAccess           = "no_pool_access"

  val Reasons: Set[String] = Set(
    InvalidCredentials,
    AccountLocked,
    PasswordChangeRequired,
    NoCredentials,
    NoAuthenticator,
    UnknownTarget,
    SessionEnded,
    NoUserName,
    UnverifiedEmail,
    TenantForbidden,
    NoPoolAccess
  )
