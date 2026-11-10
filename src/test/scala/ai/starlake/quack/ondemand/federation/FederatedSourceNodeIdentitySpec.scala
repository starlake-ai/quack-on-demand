package ai.starlake.quack.ondemand.federation

import ai.starlake.quack.model.{FederatedSource, FederatedSourceType}
import ai.starlake.quack.ondemand.federation.iceberg.{
  AwsCredentials,
  IcebergAuthType,
  IcebergRestConfig
}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** `credential_chain` has the node sign with its own identity over a scope the source names, so
  * only a superuser may ask for it. A client sending a stored source back unchanged asks for
  * nothing new.
  */
class FederatedSourceNodeIdentitySpec extends AnyFlatSpec with Matchers:

  private val chain = IcebergRestConfig(
    uri = "https://glue.example.com/iceberg",
    warehouse = "123456789012",
    authType = Some(IcebergAuthType.SigV4),
    awsCredentials = Some(AwsCredentials.CredentialChain),
    awsRegion = Some("eu-west-1"),
    awsScope = List("s3://lake/sales/")
  )

  private val staticKeys = chain.copy(
    awsCredentials = Some(AwsCredentials.Config),
    awsKeyId = Some("{{secret.KEY}}"),
    awsSecretAccessKey = Some("{{secret.SECRET}}")
  )

  private def source(cfg: IcebergRestConfig) = FederatedSource(
    id = "fs-1",
    tenantDbId = "td-1",
    alias = "lake",
    sourceType = FederatedSourceType.IcebergRest,
    config = Some(cfg.toJson)
  )

  "adoptsNodeIdentity" should "be true for a new source using the credential chain" in:
    FederatedSourceRules.adoptsNodeIdentity(None, source(chain)) shouldBe true

  it should "be false for a new source holding static keys" in:
    FederatedSourceRules.adoptsNodeIdentity(None, source(staticKeys)) shouldBe false

  it should "be false for a stored credential-chain source sent back unchanged" in:
    FederatedSourceRules.adoptsNodeIdentity(Some(source(chain)), source(chain)) shouldBe false

  it should "be true for a stored credential-chain source given another scope" in:
    val moved = chain.copy(awsScope = List("s3://lake/other/"))
    FederatedSourceRules.adoptsNodeIdentity(Some(source(chain)), source(moved)) shouldBe true

  it should "be true for a source moved from static keys to the credential chain" in:
    FederatedSourceRules.adoptsNodeIdentity(Some(source(staticKeys)), source(chain)) shouldBe true
