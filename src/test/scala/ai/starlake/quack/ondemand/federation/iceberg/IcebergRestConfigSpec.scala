package ai.starlake.quack.ondemand.federation.iceberg

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class IcebergRestConfigSpec extends AnyFlatSpec with Matchers:

  private def oauth2 = IcebergRestConfig(
    uri = "https://catalog.example.com/api/catalog",
    warehouse = "sales",
    authType = Some(IcebergAuthType.OAuth2),
    clientId = Some("{{secret.CID}}"),
    clientSecret = Some("{{secret.CSEC}}"),
    oauth2Scope = Some("PRINCIPAL_ROLE:qod_reader")
  )

  "validate" should "accept a well-formed oauth2 config" in {
    oauth2.validate("sales_lake", IcebergRestConfig.ReservedAliases) shouldBe empty
  }

  it should "require a warehouse" in {
    val errs = oauth2.copy(warehouse = "  ").validate("sales_lake", Set.empty)
    errs.exists(_.contains("warehouse")) shouldBe true
  }

  it should "refuse authType combined with endpointType" in {
    val errs = oauth2
      .copy(endpointType = Some(IcebergEndpointType.Glue))
      .validate("sales_lake", Set.empty)
    errs.exists(_.contains("exactly one")) shouldBe true
  }

  it should "refuse neither authType nor endpointType" in {
    val errs = oauth2.copy(authType = None).validate("sales_lake", Set.empty)
    errs.exists(_.contains("exactly one")) shouldBe true
  }

  it should "require client_id and client_secret for oauth2" in {
    val errs = oauth2.copy(clientId = None, clientSecret = None).validate("sales_lake", Set.empty)
    errs.exists(_.contains("clientId")) shouldBe true
    errs.exists(_.contains("clientSecret")) shouldBe true
  }

  it should "require a token for authType token" in {
    val cfg = oauth2.copy(
      authType = Some(IcebergAuthType.Token),
      clientId = None,
      clientSecret = None,
      oauth2Scope = None
    )
    cfg.validate("sales_lake", Set.empty).exists(_.contains("token")) shouldBe true
  }

  it should "refuse stray credential fields on sigv4" in {
    val cfg = oauth2.copy(authType = Some(IcebergAuthType.SigV4))
    cfg.validate("sales_lake", Set.empty).exists(_.contains("takes no")) shouldBe true
  }

  it should "allow a glue endpointType with no uri" in {
    val cfg = IcebergRestConfig(
      warehouse = "123456789012:mycatalog",
      endpointType = Some(IcebergEndpointType.Glue)
    )
    cfg.validate("glue_lake", IcebergRestConfig.ReservedAliases) shouldBe empty
  }

  it should "require a uri when authType is set" in {
    val errs = oauth2.copy(uri = "").validate("sales_lake", Set.empty)
    errs.exists(_.contains("uri")) shouldBe true
  }

  it should "reject a reserved alias" in {
    val errs = oauth2.validate("memory", IcebergRestConfig.ReservedAliases)
    errs.exists(_.contains("reserved")) shouldBe true
  }

  it should "reject an alias that is not a plain identifier" in {
    val errs = oauth2.validate("sales-lake", Set.empty)
    errs.exists(_.contains("identifier")) shouldBe true
  }

  "json" should "round-trip every field" in {
    val cfg = oauth2.copy(
      oauth2ServerUri = Some("https://idp.example.com/oauth/tokens"),
      oauth2GrantType = Some("client_credentials")
    )
    IcebergRestConfig.fromJson(cfg.toJson) shouldBe Right(cfg)
  }

  it should "round-trip an endpointType config" in {
    val cfg = IcebergRestConfig(
      warehouse = "wh",
      endpointType = Some(IcebergEndpointType.S3Tables)
    )
    IcebergRestConfig.fromJson(cfg.toJson) shouldBe Right(cfg)
  }

  it should "report a decode error rather than throwing" in {
    IcebergRestConfig.fromJson("{ not json").isLeft shouldBe true
  }

  it should "reject an unknown authType wire value" in {
    IcebergRestConfig.fromJson("""{"warehouse":"w","authType":"kerberos"}""").isLeft shouldBe true
  }
