package ai.starlake.quack.ondemand.security

import com.typesafe.config.{ConfigException, ConfigFactory}
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

  "AuditContext's readers" should "throw on a path the configuration does not hold" in {
    val ctx = AuditContext(ConfigFactory.parseString("a.present = \" x \""), StubAuditFacts())
    ctx.string("a.present") shouldBe "x"
    a[ConfigException.Missing] should be thrownBy ctx.string("a.typo")
    a[ConfigException.Missing] should be thrownBy ctx.bool("a.typo")
    a[ConfigException.Missing] should be thrownBy ctx.csv("a.typo")
  }

  "AuditContext.optString" should "read a path with no default as empty when absent" in {
    val ctx = AuditContext(ConfigFactory.parseString("a.present = \" x \""), StubAuditFacts())
    ctx.optString("a.present") shouldBe "x"
    ctx.optString("a.absent") shouldBe ""
  }
