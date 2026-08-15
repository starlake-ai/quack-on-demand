package ai.starlake.quack.ondemand.state

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EmailPolicySpec extends AnyFlatSpec with Matchers:

  "EmailFormat.matches" should "accept plausible emails and reject non-emails" in {
    EmailFormat.matches("a@b.co") shouldBe true
    EmailFormat.matches("x.y+z@sub.example.com") shouldBe true
    EmailFormat.matches("  a@b.co  ") shouldBe true // trimmed
    EmailFormat.matches("plainuser") shouldBe false
    EmailFormat.matches("a@b") shouldBe false     // no dot in domain
    EmailFormat.matches("a b@c.d") shouldBe false // whitespace
    EmailFormat.matches("@b.co") shouldBe false   // empty local
    EmailFormat.matches("a@@b.co") shouldBe false // two @
    EmailFormat.matches("") shouldBe false
  }

  "EmailPolicy.resolve" should "force an email-format username's email to the username" in {
    EmailPolicy.resolve("a@b.co", None) shouldBe Right(Some("a@b.co"))
    EmailPolicy.resolve("a@b.co", Some("")) shouldBe Right(Some("a@b.co"))
    EmailPolicy.resolve("a@b.co", Some("a@b.co")) shouldBe Right(Some("a@b.co"))
  }

  it should "reject a different email on an email-format username" in {
    EmailPolicy.resolve("a@b.co", Some("other@x.io")).isLeft shouldBe true
  }

  it should "pass a supplied email through for a non-email username" in {
    EmailPolicy.resolve("alice", Some("alice@x.io")) shouldBe Right(Some("alice@x.io"))
    EmailPolicy.resolve("alice", None) shouldBe Right(None)
    EmailPolicy.resolve("alice", Some("")) shouldBe Right(Some(""))
  }
