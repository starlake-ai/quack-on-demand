package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.model.FederatedSecret
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SecretResolverSpec extends AnyFlatSpec with Matchers {

  "PostgresSecretResolver" should "return the row's value" in {
    val s = FederatedSecret("id", "src", "PWD", Some("hunter2"), None)
    val r = new PostgresSecretResolver()
    r.resolve(s).unsafeRunSync() shouldBe "hunter2"
  }

  it should "fail when value is missing" in {
    val s = FederatedSecret("id", "src", "PWD", None, Some("env:X"))
    val ex = intercept[Throwable] {
      (new PostgresSecretResolver()).resolve(s).unsafeRunSync()
    }
    ex.getMessage should include("PWD")
  }

  "EnvSecretResolver" should "resolve env:VAR via injected lookup" in {
    val s = FederatedSecret("id", "src", "PWD", None, Some("env:QOD_TEST_SECRET"))
    val r = new EnvSecretResolver(name => if name == "QOD_TEST_SECRET" then Some("from-env") else None)
    r.resolve(s).unsafeRunSync() shouldBe "from-env"
  }

  it should "fail on unknown env var" in {
    val s = FederatedSecret("id", "src", "PWD", None, Some("env:UNSET_XYZ"))
    val r = new EnvSecretResolver(_ => None)
    val ex = intercept[Throwable] { r.resolve(s).unsafeRunSync() }
    ex.getMessage should include("UNSET_XYZ")
  }

  it should "reject externalRef without env: prefix" in {
    val s = FederatedSecret("id", "src", "PWD", None, Some("vault:secret/x"))
    val ex = intercept[Throwable] {
      (new EnvSecretResolver(_ => None)).resolve(s).unsafeRunSync()
    }
    ex.getMessage should include("expected env:")
  }

  it should "reject when externalRef is missing entirely" in {
    val s = FederatedSecret("id", "src", "PWD", Some("plain"), None)  // value-backed
    val ex = intercept[Throwable] {
      (new EnvSecretResolver(_ => None)).resolve(s).unsafeRunSync()
    }
    ex.getMessage should include("needs externalRef")
  }

  // Stubs - assert they throw, so we notice if someone half-implements them
  for ((label, mk) <- Seq(
    "AwsSecretsManagerResolver"   -> (() => new AwsSecretsManagerResolver()),
    "GcpSecretsManagerResolver"   -> (() => new GcpSecretsManagerResolver()),
    "AzureSecretsManagerResolver" -> (() => new AzureSecretsManagerResolver()),
    "VaultSecretResolver"         -> (() => new VaultSecretResolver())
  )) {
    label should "be stubbed until SDK is wired" in {
      val s = FederatedSecret("id", "src", "PWD", None, Some("x:y"))
      a[NotImplementedError] should be thrownBy mk().resolve(s).unsafeRunSync()
    }
  }
}