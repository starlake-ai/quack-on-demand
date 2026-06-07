package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.model.FederatedSecret
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DispatchingSecretResolverSpec extends AnyFlatSpec with Matchers {

  /** Stub resolver that just records which backend was called and
    * returns a fixed label so the test can assert the route taken. */
  private final class TaggingResolver(tag: String) extends SecretResolver {
    def resolve(secret: FederatedSecret): IO[String] = IO.pure(s"$tag:${secret.name}")
  }

  private def newDispatcher(): DispatchingSecretResolver =
    new DispatchingSecretResolver(
      postgres = new TaggingResolver("pg"),
      env      = new TaggingResolver("env"),
      awsSm    = new TaggingResolver("aws"),
      gcpSm    = new TaggingResolver("gcp"),
      azureKv  = new TaggingResolver("az"),
      vault    = new TaggingResolver("vault")
    )

  "DispatchingSecretResolver" should "route value-backed secrets to Postgres" in {
    val s = FederatedSecret("id", "src", "PWD", Some("hunter2"), None)
    newDispatcher().resolve(s).unsafeRunSync() shouldBe "pg:PWD"
  }

  it should "route env: externalRef to the env resolver" in {
    val s = FederatedSecret("id", "src", "PWD", None, Some("env:MY_VAR"))
    newDispatcher().resolve(s).unsafeRunSync() shouldBe "env:PWD"
  }

  it should "route aws-sm: externalRef to AWS Secrets Manager" in {
    val s = FederatedSecret("id", "src", "PWD", None, Some("aws-sm:arn:aws:secretsmanager:us-east-1:1:secret:foo"))
    newDispatcher().resolve(s).unsafeRunSync() shouldBe "aws:PWD"
  }

  it should "route gcp-sm: externalRef to GCP Secret Manager" in {
    val s = FederatedSecret("id", "src", "PWD", None, Some("gcp-sm:projects/p/secrets/x/versions/latest"))
    newDispatcher().resolve(s).unsafeRunSync() shouldBe "gcp:PWD"
  }

  it should "route azure-kv: externalRef to Azure Key Vault" in {
    val s = FederatedSecret("id", "src", "PWD", None, Some("azure-kv:my-secret"))
    newDispatcher().resolve(s).unsafeRunSync() shouldBe "az:PWD"
  }

  it should "route vault: externalRef to Vault" in {
    val s = FederatedSecret("id", "src", "PWD", None, Some("vault:secret/data/x#k"))
    newDispatcher().resolve(s).unsafeRunSync() shouldBe "vault:PWD"
  }

  it should "fail with a helpful message on an unknown externalRef prefix" in {
    val s = FederatedSecret("id", "src", "PWD", None, Some("unknown:foo"))
    val ex = intercept[Throwable] {
      newDispatcher().resolve(s).unsafeRunSync()
    }
    ex.getMessage should include("unknown externalRef prefix 'unknown:'")
  }
}