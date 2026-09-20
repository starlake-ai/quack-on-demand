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

  it should "reject a reserved alias even when the caller passes no extra reserved aliases" in {
    val errs = oauth2.validate("memory", Set.empty)
    errs.exists(_.contains("reserved")) shouldBe true
  }

  it should "reject an alias that is not a plain identifier" in {
    val errs = oauth2.validate("sales-lake", Set.empty)
    errs.exists(_.contains("identifier")) shouldBe true
  }

  it should "reject a literal clientSecret value" in {
    val errs = oauth2.copy(clientSecret = Some("hunter2")).validate("sales_lake", Set.empty)
    errs.exists(_.contains("clientSecret")) shouldBe true
    errs.exists(_.contains("{{secret.")) shouldBe true
  }

  it should "reject a clientSecret with a placeholder embedded in a literal" in {
    // placeholderErrors (clientSecret / token) is unchanged: full-string match, unlike the
    // embedding-aware strayBraceErrors used by uri/warehouse/clientId/oauth2*.
    val errs = oauth2.copy(clientSecret = Some("sk-{{secret.X}}")).validate("sales_lake", Set.empty)
    errs.exists(_.contains("clientSecret")) shouldBe true
  }

  it should "reject a uri containing a stray or malformed '{{'" in {
    val errs = oauth2
      .copy(uri = "https://catalog.example.com/cat{{alog")
      .validate(
        "sales_lake",
        Set.empty
      )
    errs.exists(_.contains("uri")) shouldBe true
    errs.exists(_.contains("{{")) shouldBe true
  }

  it should "accept a uri that is a well-formed {{secret.NAME}} placeholder" in {
    val errs = oauth2.copy(uri = "{{secret.HOST}}").validate("sales_lake", Set.empty)
    errs.exists(_.contains("uri")) shouldBe false
  }

  it should "accept a uri with a well-formed placeholder embedded among literal text" in {
    // Regression test: FederationBlobBuilder.substitute resolves {{secret.NAME}} wherever it
    // appears in the rendered SQL, not only when it is the field's entire value - the validator
    // must accept exactly what the builder can resolve.
    val errs = oauth2
      .copy(uri = "https://host/{{secret.TOKEN}}/api")
      .validate("sales_lake", Set.empty)
    errs.exists(_.contains("uri")) shouldBe false
  }

  it should "reject a uri where one well-formed placeholder does not excuse a second stray brace" in {
    val errs = oauth2
      .copy(uri = "https://host/{{secret.OK}}/x{{broken")
      .validate("sales_lake", Set.empty)
    errs.exists(_.contains("uri")) shouldBe true
    errs.exists(_.contains("{{")) shouldBe true
  }

  it should "reject a warehouse containing a stray or malformed '{{'" in {
    val errs = oauth2.copy(warehouse = "ware{{house").validate("sales_lake", Set.empty)
    errs.exists(_.contains("warehouse")) shouldBe true
    errs.exists(_.contains("{{")) shouldBe true
  }

  it should "accept a warehouse containing a well-formed {{secret.NAME}} placeholder" in {
    val errs = oauth2.copy(warehouse = "{{secret.WAREHOUSE}}").validate("sales_lake", Set.empty)
    errs.exists(_.contains("warehouse")) shouldBe false
  }

  it should "accept a warehouse with a well-formed placeholder embedded among literal text" in {
    val errs = oauth2.copy(warehouse = "wh-{{secret.ACCT}}").validate("sales_lake", Set.empty)
    errs.exists(_.contains("warehouse")) shouldBe false
  }

  it should "accept a clientSecret placeholder when nothing else is a placeholder" in {
    val cfg = IcebergRestConfig(
      uri = "https://catalog.example.com/api/catalog",
      warehouse = "sales",
      authType = Some(IcebergAuthType.OAuth2),
      clientId = Some("plain-client-id"),
      clientSecret = Some("{{secret.CSEC}}")
    )
    cfg.validate("sales_lake", Set.empty).exists(_.contains("clientSecret")) shouldBe false
  }

  it should "reject a clientId containing a stray or malformed '{{'" in {
    // "{{secret.CID}}extra" is no longer a fixture for this case: FederationBlobBuilder resolves
    // the well-formed placeholder and leaves "extra" as ordinary trailing literal text, which is
    // not a failure - see "accept a clientId with a well-formed placeholder followed by literal
    // text" below. A genuinely malformed brace is required to exercise rejection.
    val errs = oauth2.copy(clientId = Some("cid{{oops")).validate("sales_lake", Set.empty)
    errs.exists(_.contains("clientId")) shouldBe true
    errs.exists(_.contains("{{")) shouldBe true
  }

  it should "accept a clientId with a well-formed placeholder followed by literal text" in {
    val errs =
      oauth2.copy(clientId = Some("{{secret.CID}}extra")).validate("sales_lake", Set.empty)
    errs.exists(_.contains("clientId")) shouldBe false
  }

  it should "accept an oauth2ServerUri with a well-formed placeholder embedded in a literal uri" in {
    // Same shape as the uri/warehouse regression test: FederationBlobBuilder substitutes the
    // placeholder wherever it appears, so this resolves cleanly and must not be rejected.
    val errs = oauth2
      .copy(oauth2ServerUri = Some("https://idp.example.com/{{secret.HOST}}"))
      .validate("sales_lake", Set.empty)
    errs.exists(_.contains("oauth2ServerUri")) shouldBe false
  }

  it should "reject an oauth2ServerUri containing a stray or malformed '{{'" in {
    val errs = oauth2
      .copy(oauth2ServerUri = Some("https://idp.example.com/serv{{oops"))
      .validate("sales_lake", Set.empty)
    errs.exists(_.contains("oauth2ServerUri")) shouldBe true
    errs.exists(_.contains("{{")) shouldBe true
  }

  it should "reject an oauth2Scope containing a malformed '{{'" in {
    val errs =
      oauth2.copy(oauth2Scope = Some("scope{{oops")).validate("sales_lake", Set.empty)
    errs.exists(_.contains("oauth2Scope")) shouldBe true
    errs.exists(_.contains("{{")) shouldBe true
  }

  it should "reject an oauth2GrantType containing a malformed '{{'" in {
    val errs = oauth2
      .copy(oauth2GrantType = Some("grant{{oops"))
      .validate("sales_lake", Set.empty)
    errs.exists(_.contains("oauth2GrantType")) shouldBe true
    errs.exists(_.contains("{{")) shouldBe true
  }

  it should "accept a well-formed {{secret.NAME}} placeholder for clientId" in {
    oauth2.copy(clientId = Some("{{secret.CID}}")).validate("sales_lake", Set.empty) shouldBe empty
  }

  it should "refuse stray oauth2ServerUri / oauth2Scope / oauth2GrantType for authType token" in {
    val cfg = oauth2.copy(
      authType = Some(IcebergAuthType.Token),
      clientId = None,
      clientSecret = None,
      token = Some("{{secret.BEARER}}")
    )
    val errs = cfg.validate("sales_lake", Set.empty)
    errs.exists(_.contains("oauth2ServerUri")) shouldBe true
    errs.exists(_.contains("oauth2Scope")) shouldBe true
    errs.exists(_.contains("oauth2GrantType")) shouldBe true
  }

  it should "reject a literal token value" in {
    val cfg = oauth2.copy(
      authType = Some(IcebergAuthType.Token),
      clientId = None,
      clientSecret = None,
      oauth2Scope = None,
      token = Some("abc")
    )
    val errs = cfg.validate("sales_lake", Set.empty)
    errs.exists(_.contains("token")) shouldBe true
    errs.exists(_.contains("{{secret.")) shouldBe true
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

  it should "round-trip a glue endpointType config" in {
    val cfg = IcebergRestConfig(
      warehouse = "123456789012:mycatalog",
      endpointType = Some(IcebergEndpointType.Glue)
    )
    IcebergRestConfig.fromJson(cfg.toJson) shouldBe Right(cfg)
  }

  it should "round-trip authType NoAuth" in {
    val cfg = IcebergRestConfig(uri = "u", warehouse = "w", authType = Some(IcebergAuthType.NoAuth))
    IcebergRestConfig.fromJson(cfg.toJson) shouldBe Right(cfg)
  }

  it should "round-trip authType SigV4" in {
    val cfg = IcebergRestConfig(uri = "u", warehouse = "w", authType = Some(IcebergAuthType.SigV4))
    IcebergRestConfig.fromJson(cfg.toJson) shouldBe Right(cfg)
  }

  it should "round-trip authType Token including the token field" in {
    val cfg = IcebergRestConfig(
      uri = "u",
      warehouse = "w",
      authType = Some(IcebergAuthType.Token),
      token = Some("{{secret.TOK}}")
    )
    IcebergRestConfig.fromJson(cfg.toJson) shouldBe Right(cfg)
  }

  it should "decode a partial JSON object using the field defaults" in {
    val r = IcebergRestConfig.fromJson("""{"warehouse":"wh","endpointType":"s3_tables"}""")
    r.isRight shouldBe true
    r.toOption.get.uri shouldBe ""
    r.toOption.get.warehouse shouldBe "wh"
  }

  it should "report a decode error rather than throwing" in {
    IcebergRestConfig.fromJson("{ not json").isLeft shouldBe true
  }

  it should "reject an unknown authType wire value" in {
    val r = IcebergRestConfig.fromJson("""{"uri":"u","warehouse":"w","authType":"kerberos"}""")
    r.isLeft shouldBe true
    r.left.toOption.get should include("kerberos")
  }

  it should "reject an unknown endpointType wire value" in {
    val r = IcebergRestConfig.fromJson("""{"uri":"u","warehouse":"w","endpointType":"nope"}""")
    r.isLeft shouldBe true
    r.left.toOption.get should include("nope")
  }

  "validated" should "lowercase the alias" in {
    val r = IcebergRestConfig.validated(oauth2, "Sales_Lake")
    r.isRight shouldBe true
    r.toOption.get.alias shouldBe "sales_lake"
  }

  it should "reject a 64-char alias with a message naming the 63-char bound" in {
    val tooLong = "a" * 64
    val r       = IcebergRestConfig.validated(oauth2, tooLong)
    r.isLeft shouldBe true
    r.left.toOption.get.exists(_.contains("63")) shouldBe true
  }

  it should "reject a reserved alias" in {
    val r = IcebergRestConfig.validated(oauth2, "memory")
    r.isLeft shouldBe true
    r.left.toOption.get.exists(_.contains("reserved")) shouldBe true
  }

  it should "carry every error at once for an invalid config" in {
    val r = IcebergRestConfig.validated(oauth2.copy(warehouse = "", uri = ""), "sales_lake")
    r.isLeft shouldBe true
    val errs = r.left.toOption.get
    errs.exists(_.contains("warehouse")) shouldBe true
    errs.exists(_.contains("uri")) shouldBe true
  }

  it should "reject an alias present in extraReserved" in {
    val r = IcebergRestConfig.validated(oauth2, "sales_lake", Set("sales_lake"))
    r.isLeft shouldBe true
    r.left.toOption.get.exists(_.contains("reserved")) shouldBe true
  }

  it should "reject an alias present in extraReserved regardless of case" in {
    val r = IcebergRestConfig.validated(oauth2, "sales_lake", Set("SALES_LAKE"))
    r.isLeft shouldBe true
    r.left.toOption.get.exists(_.contains("reserved")) shouldBe true
  }

  it should "accept an alias not present in extraReserved" in {
    val r = IcebergRestConfig.validated(oauth2, "sales_lake", Set("other"))
    r.isRight shouldBe true
  }

  it should "behave exactly as the two-argument form when extraReserved is omitted" in {
    IcebergRestConfig.validated(oauth2, "sales_lake") shouldBe
      IcebergRestConfig.validated(oauth2, "sales_lake", Set.empty)
  }

  // ValidatedIcebergConfig's constructor is `private[iceberg]`: outside this package it cannot be
  // constructed directly, only obtained through `IcebergRestConfig.validated`.

  "the wire shape" should "encode authType as its lowercase wire string" in {
    val cfg = IcebergRestConfig(uri = "u", warehouse = "w", authType = Some(IcebergAuthType.OAuth2))
    cfg.toJson should include(""""authType":"oauth2"""")
  }

  it should "encode endpointType as its lowercase, underscored wire string" in {
    val cfg =
      IcebergRestConfig(
        uri = "u",
        warehouse = "w",
        endpointType = Some(IcebergEndpointType.S3Tables)
      )
    cfg.toJson should include(""""endpointType":"s3_tables"""")
  }
