package ai.starlake.quack.secrets

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SecretRefResolverSpec extends AnyFlatSpec with Matchers:

  private def leftContains(r: Either[String, String], snippet: String): Boolean =
    r.left.toOption.exists(_.contains(snippet))

  "SecretRefResolver.fromEnv" should "resolve env:NAME via the injected lookup" in:
    val r = SecretRefResolver.fromEnv {
      case "MY_SECRET" => Some("plaintext-value")
      case _           => None
    }
    r.resolve("env:MY_SECRET") shouldBe Right("plaintext-value")

  it should "return Left when the env var is not set" in:
    val r = SecretRefResolver.fromEnv(_ => None)
    leftContains(r.resolve("env:MISSING"), "not set") shouldBe true

  it should "return Left for cloud-backed prefixes (not yet wired)" in:
    val r = SecretRefResolver.fromEnv(_ => None)
    leftContains(r.resolve("aws-sm:arn:.."),     "not yet wired") shouldBe true
    leftContains(r.resolve("gcp-sm:projects/"),  "not yet wired") shouldBe true
    leftContains(r.resolve("azure-kv:name"),     "not yet wired") shouldBe true
    leftContains(r.resolve("vault:secret/x"),    "not yet wired") shouldBe true

  it should "return Left for unknown / malformed refs" in:
    val r = SecretRefResolver.fromEnv(_ => None)
    leftContains(r.resolve("plain-string"),      "invalid secret ref") shouldBe true
    r.resolve("env:")              shouldBe a[Left[?, ?]]
    r.resolve("unknown:something") shouldBe a[Left[?, ?]]
