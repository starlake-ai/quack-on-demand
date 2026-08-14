package ai.starlake.quack.mail

import ai.starlake.quack.SmtpConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MailSenderSpec extends AnyFlatSpec with Matchers:
  "LogMailSender" should "return Right and not throw" in {
    new LogMailSender().send("a@x.io", "subj", "body") shouldBe Right(())
  }

  "SmtpMailSender" should "return Left when the host is unreachable, not throw" in {
    val cfg =
      SmtpConfig(host = Some("127.0.0.1"), port = 1, user = None, password = None, from = "no@x.io")
    new SmtpMailSender(cfg).send("a@x.io", "subj", "body").isLeft shouldBe true
  }
