package ai.starlake.quack.ondemand.api

import ai.starlake.quack.mail.MailSender
import ai.starlake.quack.ondemand.state.UserStore
import ai.starlake.quack.ondemand.telemetry.AuditRateLimiter
import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import sttp.model.StatusCode

import java.nio.charset.StandardCharsets

/** Public, pre-session password recovery: `forgot-password` mints a single-use reset link and mails
  * it; `reset-password` redeems that link.
  *
  * ANTI-ENUMERATION is the load-bearing property of `forgot-password`: it ALWAYS returns
  * `Right(())` and never lets the status, body, or timing distinguish an existing emailed account
  * from a missing one, an emailless one, or one that just hit the rate limiter. Mail is sent only
  * when the `(tenant, username)` row exists AND carries an email AND the per-account limiter
  * admits.
  *
  * SINGLE-USE is the load-bearing property of `reset-password`: the token is verified in two steps
  * (see [[ResetTokenStore]]). Step one (`subjectOf`) resolves the token to a userId using signature
  * + expiry only; step two (`verify`) recomputes the fingerprint from the row's CURRENT hash, so a
  * link stops working the instant the password changes -- including via the reset itself.
  *
  * @param resolveTenant
  *   maps a login-form tenant (id OR display name) to the surrogate id stored in `qodstate_user`,
  *   exactly as [[AuthHandlers]] does; unknown values pass through verbatim so lookups miss
  *   cleanly.
  * @param publicBaseUrl
  *   externally visible manager base URL. When empty the reset link is host-relative
  *   (`/ui/reset-password?token=...`); Main logs a boot warning in that case.
  */
final class PasswordResetHandlers(
    users: UserStore,
    tokens: ResetTokenStore,
    mail: MailSender,
    resolveTenant: String => Option[String],
    publicBaseUrl: String,
    limiter: AuditRateLimiter = new AuditRateLimiter()
) extends LazyLogging:

  type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]

  private def resetLink(token: String): String =
    s"$publicBaseUrl/ui/reset-password?token=$token"

  /** Always 200. Sends a reset link only for an existing, emailed, non-rate-limited account; a
    * missing row, an emailless row, and an over-limit account are all indistinguishable from the
    * caller's side. Mail failures are logged, never surfaced.
    */
  def forgotPassword(req: ForgotPasswordRequest): Out[Unit] =
    // Only the uniform DB lookup rides on the response; the mint + synchronous
    // SMTP send is started as a detached fiber so the reply time no longer
    // depends on whether an account exists, has an email, or the limiter admits.
    // Every arm returns Right(()) after just the lookup -- the timing channel is
    // closed as tightly as the byte-identical body.
    val lookup: IO[Option[(String, String, String)]] = IO.blocking {
      val scopeTenant =
        req.tenant.map(_.trim).filter(_.nonEmpty).map(t => resolveTenant(t).getOrElse(t))
      users.findForReset(scopeTenant, req.username) match
        // The rate-limit key is per (tenant, username) so it can't be turned into
        // an email bomb; the guard is only evaluated for a row that actually has
        // an email, so a missing / emailless account never consumes budget.
        case Some((id, Some(email), hash))
            if limiter.allow(s"reset:${scopeTenant.getOrElse("")}:${req.username}") =>
          Some((id, email, hash))
        case _ => None
    }

    lookup.flatMap {
      case Some((id, email, hash)) =>
        val send = IO.blocking {
          val token = tokens.mint(id, hash)
          val body  =
            s"""We received a request to reset your Quack on Demand password.
               |
               |Open this link to choose a new password (valid for 1 hour):
               |${resetLink(token)}
               |
               |If you did not request this, you can safely ignore this email.""".stripMargin
          mail.send(email, "Reset your password", body) match
            case Left(reason) =>
              // Never leak the recipient: a failed send is an operational signal, not
              // an account oracle. The address is deliberately kept out of the log.
              logger.warn(s"forgot-password: a reset email could not be sent: $reason")
            case Right(()) => ()
        }
        send.start.void.as(Right(()))
      case None =>
        IO.pure(Right(()))
    }

  /** Redeem a single-use link. Policy first (non-empty, at most 71 UTF-8 bytes -> 400
    * invalid_password), then the two-step token check; any token error OR a missing row -> 400
    * invalid_token. On success the row's password is rotated by id.
    */
  def resetPassword(req: ResetPasswordRequest): Out[Unit] = IO.blocking {
    if req.newPassword.isEmpty then
      Left((StatusCode.BadRequest, ErrorResponse("invalid_password", "new password is required")))
    else if req.newPassword.getBytes(StandardCharsets.UTF_8).length > 71 then
      Left(
        (
          StatusCode.BadRequest,
          ErrorResponse("invalid_password", "new password must be at most 71 bytes")
        )
      )
    else
      // Two-step: subjectOf (signature + expiry) -> userId; the fingerprint check
      // needs the row's CURRENT hash, so the row lookup sits between the two. Any
      // failed step collapses to the same 400 invalid_token -- no differential.
      val redeemed =
        for
          userId <- tokens.subjectOf(req.token).left.map(_ => ())
          hash   <- users.passwordHashById(userId).toRight(())
          _      <- tokens.verify(req.token, hash).left.map(_ => ())
        yield userId
      redeemed match
        case Right(userId) =>
          users.setPasswordById(userId, req.newPassword)
          Right(())
        case Left(_) =>
          Left(
            (
              StatusCode.BadRequest,
              ErrorResponse("invalid_token", "the reset link is invalid or has expired")
            )
          )
  }
