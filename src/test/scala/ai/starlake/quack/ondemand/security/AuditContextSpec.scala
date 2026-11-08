package ai.starlake.quack.ondemand.security

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AuditContextSpec extends AnyFlatSpec with Matchers:

  "AuditContext.isLoopback" should "accept loopback literals" in
    List("127.0.0.1", "127.1.2.3", "localhost", "[::1]", "::1").foreach { h =>
      withClue(h)(AuditContext.isLoopback(h) shouldBe true)
    }

  it should "refuse anything else" in
    List("127.example.com", "0.0.0.0", "10.0.0.1", "127.0.0", "127.0.0.1.evil.com", "").foreach { h =>
      withClue(h)(AuditContext.isLoopback(h) shouldBe false)
    }
