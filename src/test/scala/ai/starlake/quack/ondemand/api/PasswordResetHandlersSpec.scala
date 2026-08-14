package ai.starlake.quack.ondemand.api

import ai.starlake.quack.mail.MailSender
import ai.starlake.quack.ondemand.state.{LiquibaseRunner, PostgresControlPlaneStore, UserStore}
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import ai.starlake.quack.ondemand.telemetry.AuditRateLimiter
import at.favre.lib.crypto.bcrypt.BCrypt
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

import java.time.Instant
import scala.concurrent.duration.DurationInt
import scala.util.Try

/** Wire contract of the public `forgot-password` + `reset-password` handlers against a real
  * Postgres [[UserStore]].
  *
  * Two load-bearing properties:
  *   - anti-enumeration on `forgot-password`: it ALWAYS returns `Right(())` and never lets the
  *     status / body reveal whether the account exists, has an email, or hit the rate limiter. Mail
  *     is sent ONLY when the `(tenant, username)` row exists AND carries an email AND the limiter
  *     admits.
  *   - single-use on `reset-password`: the two-step token check fingerprints the row's CURRENT
  *     hash, so a link stops working the instant the password changes (including via the reset
  *     itself).
  */
class PasswordResetHandlersSpec extends AnyFlatSpec with Matchers:

  TestPostgres.dropStrayTestDatabases("qodprh")

  /** 32 ASCII bytes -- satisfies HS256 minimum key length. */
  private val secret = "0123456789abcdef0123456789abcdef"
  private val base   = "https://qod.test"

  /** Captures the last message a handler asked to deliver; `send` never fails. */
  private final class CapturingMail extends MailSender:
    @volatile var lastTo: Option[String]                                      = None
    @volatile var lastSubject: Option[String]                                 = None
    @volatile var lastBody: Option[String]                                    = None
    def send(to: String, subject: String, body: String): Either[String, Unit] =
      lastTo = Some(to)
      lastSubject = Some(subject)
      lastBody = Some(body)
      Right(())
    def reset(): Unit =
      lastTo = None
      lastSubject = None
      lastBody = None

  private def handlers(
      users: UserStore,
      mail: MailSender,
      tokens: ResetTokenStore = new ResetTokenStore(secret),
      limiter: AuditRateLimiter = new AuditRateLimiter()
  ): PasswordResetHandlers =
    // resolveTenant is a pass-through here (unknown -> keep the raw value): the
    // fixtures store rows under the literal tenant id they were upserted with.
    new PasswordResetHandlers(users, tokens, mail, _ => None, base, limiter)

  private val tokenRe                            = """token=([^\s]+)""".r
  private def extractToken(body: String): String =
    tokenRe.findFirstMatchIn(body).map(_.group(1)).getOrElse(fail(s"no token in body: $body"))

  private def withFreshDb(test: (PostgresControlPlaneStore, UserStore) => Unit): Unit =
    TestPostgres.ensureReachable()
    val dbName = s"qodprh_test_${System.nanoTime()}"
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$dbName"""")
    try
      val url = TestPostgres.dbUrl(dbName)
      new LiquibaseRunner(url, TestPostgres.pgUser, TestPostgres.pgPass).run()
      val store     = new PostgresControlPlaneStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      val userStore = new UserStore(url, TestPostgres.pgUser, TestPostgres.pgPass)
      try test(store, userStore)
      finally
        userStore.close()
        store.close()
    finally Try(TestPostgres.dropDatabase(dbName))

  // ------------------------------------------------------------------
  // forgot-password
  // ------------------------------------------------------------------

  "forgot-password" should "200 and send a reset link for an emailed user" in withFreshDb {
    (_, users) =>
      val mail = new CapturingMail
      users.upsertUser(Some("t1"), "carol", "pw", "user", email = Some(Some("carol@x.io")))
      handlers(users, mail)
        .forgotPassword(ForgotPasswordRequest(Some("t1"), "carol"))
        .unsafeRunSync() shouldBe Right(())
      mail.lastTo shouldBe Some("carol@x.io")
      mail.lastBody.get should include(s"$base/ui/reset-password?token=")
  }

  it should "200 but send nothing for a user without an email" in withFreshDb { (_, users) =>
    val mail = new CapturingMail
    users.upsertUser(Some("t1"), "dan", "pw", "user") // no email
    handlers(users, mail)
      .forgotPassword(ForgotPasswordRequest(Some("t1"), "dan"))
      .unsafeRunSync() shouldBe Right(())
    mail.lastTo shouldBe None
  }

  it should "200 but send nothing for a nonexistent user (anti-enumeration)" in withFreshDb {
    (_, users) =>
      val mail = new CapturingMail
      handlers(users, mail)
        .forgotPassword(ForgotPasswordRequest(Some("t1"), "ghost"))
        .unsafeRunSync() shouldBe Right(())
      mail.lastTo shouldBe None
  }

  it should "200 but suppress a second send within the rate window" in withFreshDb { (_, users) =>
    val mail = new CapturingMail
    val h    = handlers(users, mail) // one handler => one shared limiter
    users.upsertUser(Some("t1"), "carol", "pw", "user", email = Some(Some("carol@x.io")))

    h.forgotPassword(ForgotPasswordRequest(Some("t1"), "carol")).unsafeRunSync() shouldBe Right(())
    mail.lastTo shouldBe Some("carol@x.io")

    mail.reset()
    h.forgotPassword(ForgotPasswordRequest(Some("t1"), "carol")).unsafeRunSync() shouldBe Right(())
    mail.lastTo shouldBe None // over the limit: still 200, but no second mail
  }

  // ------------------------------------------------------------------
  // reset-password
  // ------------------------------------------------------------------

  "reset-password" should "set the new password from a valid token" in withFreshDb {
    (store, users) =>
      val mail = new CapturingMail
      val h    = handlers(users, mail)
      users.upsertUser(Some("t1"), "carol", "oldpw", "user", email = Some(Some("carol@x.io")))

      h.forgotPassword(ForgotPasswordRequest(Some("t1"), "carol")).unsafeRunSync()
      val token = extractToken(mail.lastBody.get)

      h.resetPassword(ResetPasswordRequest(token, "newpass")).unsafeRunSync() shouldBe Right(())

      val hash = store.getPasswordHash(Some("t1"), "carol").get
      BCrypt.verifyer().verify("newpass".toCharArray, hash).verified shouldBe true
      BCrypt.verifyer().verify("oldpw".toCharArray, hash).verified shouldBe false
  }

  it should "400 invalid_token on a tampered token" in withFreshDb { (_, users) =>
    val mail = new CapturingMail
    handlers(users, mail)
      .resetPassword(ResetPasswordRequest("garbage.not.a.jwt", "newpass"))
      .unsafeRunSync() match
      case Left((status, err)) =>
        status shouldBe StatusCode.BadRequest
        err.error shouldBe "invalid_token"
      case Right(_) => fail("expected 400 invalid_token")
  }

  it should "400 invalid_token on an expired token" in withFreshDb { (_, users) =>
    val mail    = new CapturingMail
    var now     = Instant.parse("2026-01-01T00:00:00Z")
    val expired = new ResetTokenStore(secret, ttl = 1.minute, clock = () => now)
    val h       = handlers(users, mail, tokens = expired)
    users.upsertUser(Some("t1"), "carol", "pw", "user", email = Some(Some("carol@x.io")))

    h.forgotPassword(ForgotPasswordRequest(Some("t1"), "carol")).unsafeRunSync()
    val token = extractToken(mail.lastBody.get)

    now = now.plusSeconds(120) // past the 1-minute ttl
    h.resetPassword(ResetPasswordRequest(token, "newpass")).unsafeRunSync() match
      case Left((status, err)) =>
        status shouldBe StatusCode.BadRequest
        err.error shouldBe "invalid_token"
      case Right(_) => fail("expected 400 invalid_token")
  }

  it should "400 invalid_token when reused after the password changed (single-use)" in withFreshDb {
    (_, users) =>
      val mail = new CapturingMail
      val h    = handlers(users, mail)
      users.upsertUser(Some("t1"), "carol", "oldpw", "user", email = Some(Some("carol@x.io")))

      h.forgotPassword(ForgotPasswordRequest(Some("t1"), "carol")).unsafeRunSync()
      val token = extractToken(mail.lastBody.get)

      h.resetPassword(ResetPasswordRequest(token, "newpass")).unsafeRunSync() shouldBe Right(())

      // The same link no longer verifies: the row's hash moved, so the token's
      // fingerprint no longer matches.
      h.resetPassword(ResetPasswordRequest(token, "newerpass")).unsafeRunSync() match
        case Left((status, err)) =>
          status shouldBe StatusCode.BadRequest
          err.error shouldBe "invalid_token"
        case Right(_) => fail("expected 400 invalid_token on reuse")
  }

  it should "400 invalid_password when the new password is empty or over 71 bytes" in withFreshDb {
    (_, users) =>
      val mail = new CapturingMail
      val h    = handlers(users, mail)
      users.upsertUser(Some("t1"), "carol", "oldpw", "user", email = Some(Some("carol@x.io")))

      h.forgotPassword(ForgotPasswordRequest(Some("t1"), "carol")).unsafeRunSync()
      val token = extractToken(mail.lastBody.get)

      // Empty and over-long both fail the policy gate BEFORE the token is checked,
      // so the token is never consumed by either attempt.
      h.resetPassword(ResetPasswordRequest(token, "")).unsafeRunSync() match
        case Left((status, err)) =>
          status shouldBe StatusCode.BadRequest
          err.error shouldBe "invalid_password"
        case Right(_) => fail("expected 400 invalid_password on empty")

      h.resetPassword(ResetPasswordRequest(token, "x" * 100)).unsafeRunSync() match
        case Left((status, err)) =>
          status shouldBe StatusCode.BadRequest
          err.error shouldBe "invalid_password"
        case Right(_) => fail("expected 400 invalid_password on over-long")
  }
