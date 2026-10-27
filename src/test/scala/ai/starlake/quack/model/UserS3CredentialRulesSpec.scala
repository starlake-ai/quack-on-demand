package ai.starlake.quack.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class UserS3CredentialRulesSpec extends AnyFlatSpec with Matchers:

  private val Secret = "user-sekr3t-value"
  private val Token  = "user-session-token"

  private def input(
      scope: String = "s3://acme-etl/",
      keyId: String = "AKIAEXAMPLE",
      secret: String = Secret,
      token: Option[String] = None,
      region: Option[String] = None,
      endpoint: Option[String] = None,
      urlStyle: Option[String] = None
  ) = UserS3CredentialRules.Input(scope, keyId, secret, token, region, endpoint, urlStyle)

  private val Allowed = List("minio.internal:9000", "http://127.0.0.1:9000")

  private def ok(in: UserS3CredentialRules.Input, allowed: List[String] = Allowed) =
    UserS3CredentialRules.validate(in, allowed)

  private def code(in: UserS3CredentialRules.Input, allowed: List[String] = Allowed) =
    ok(in, allowed).left.map(_._1)

  "a scope" should "be stored with one trailing slash, a bucket or a prefix in it" in {
    UserS3CredentialRules.normalizeScope("s3://acme-etl") shouldBe Right("s3://acme-etl/")
    UserS3CredentialRules.normalizeScope(" s3://acme-etl/ ") shouldBe Right("s3://acme-etl/")
    UserS3CredentialRules.normalizeScope("s3://acme-etl/in/2026") shouldBe
      Right("s3://acme-etl/in/2026/")
    UserS3CredentialRules.normalizeScope("s3://acme.etl-1/In/") shouldBe
      Right("s3://acme.etl-1/In/")
  }

  it should "refuse every other shape" in
    List(
      "S3://acme-etl/",
      "s3a://acme-etl/",
      "gs://acme-etl/",
      "https://acme-etl.s3.amazonaws.com/",
      "s3://",
      "s3://Acme-etl/",
      "s3://ab/",
      "s3://a..b/",
      "s3://-acme/",
      "s3://acme-etl//",
      "s3://acme-etl//in/",
      "s3://acme-etl/in//",
      "s3://acme-etl/./in/",
      "s3://acme-etl/in/../x/",
      "s3://acme-etl/in/..",
      "s3://acme-etl/in%2f/",
      "s3://acme-etl/in?x=1",
      "s3://acme-etl/in#x",
      "s3://acme-etl/in\\x/",
      "s3://acme-etl/in*/",
      "s3://acme-etl/in[0]/",
      "s3://acme-etl/{a,b}/",
      "s3://acme-etl/in\n/",
      "s3://acme-etl/" + "x" * 1100
    ).foreach { raw =>
      withClue(s"'$raw': ") {
        UserS3CredentialRules.normalizeScope(raw).left.map(_._1) shouldBe Left("invalid_scope")
      }
    }

  it should "refuse an access point alias, which reaches a bucket under another name" in
    List(
      "s3://myap-a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7-s3alias/",
      "s3://my-olap-a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5--ol-s3/in/",
      "s3://mfzwi23gnjvgw.mrap/"
    ).foreach { raw =>
      withClue(s"'$raw': ") {
        val refusal = UserS3CredentialRules.normalizeScope(raw).left.toOption
        refusal.map(_._1) shouldBe Some("invalid_scope")
        refusal.map(_._2).getOrElse("") should include("access point alias")
      }
    }

  "a credential" should "need its key id and secret, trimmed" in {
    code(input(keyId = "")) shouldBe Left("credential_required")
    code(input(keyId = "   ")) shouldBe Left("credential_required")
    code(input(secret = "")) shouldBe Left("credential_required")
    val a = ok(input(keyId = " AKIAEXAMPLE ", secret = s"$Secret\t")).toOption.get
    a.accessKeyId shouldBe "AKIAEXAMPLE"
    a.secretAccessKey shouldBe Secret
  }

  it should "take an optional session token, region and URL style, and check them" in {
    val a = ok(input(token = Some(Token), region = Some("eu-west-3"), urlStyle = Some("path")))
    a.map(x => (x.sessionToken, x.region, x.urlStyle)) shouldBe
      Right((Some(Token), Some("eu-west-3"), Some("path")))
    ok(input(token = Some("  "), region = Some(""))).map(x => (x.sessionToken, x.region)) shouldBe
      Right((None, None))
    code(input(region = Some("eu west"))) shouldBe Left("invalid_credential")
    code(input(urlStyle = Some("virtual"))) shouldBe Left("invalid_credential")
    code(input(secret = "line\nbreak")) shouldBe Left("invalid_credential")
    code(input(secret = "x" * 4097)) shouldBe Left("invalid_credential")
    code(input(token = Some("t" * 8193))) shouldBe Left("invalid_credential")
  }

  it should "never echo the secret or the token in a refusal" in {
    val hidden = "QQhiddenQQ"
    List(
      input(secret = s"a\n$hidden"),
      input(token = Some("a" + 1.toChar + hidden)),
      input(secret = hidden * 500),
      input(scope = "s3://Acme/", secret = hidden),
      input(endpoint = Some("evil.example:443"), secret = hidden)
    ).foreach { in =>
      val msg = ok(in).left.toOption.get._2
      msg should not include hidden
      msg should not include "AKIAEXAMPLE"
    }
  }

  "an endpoint" should "be admitted only when the operator lists it" in {
    ok(input(endpoint = Some("minio.internal:9000"))).map(_.endpoint) shouldBe
      Right(Some("minio.internal:9000"))
    ok(input(endpoint = Some("https://MINIO.internal:9000/"))).map(_.endpoint) shouldBe
      Right(Some("minio.internal:9000"))
    ok(input(endpoint = Some("http://127.0.0.1:9000"))).map(_.endpoint) shouldBe
      Right(Some("http://127.0.0.1:9000"))
    code(input(endpoint = Some("127.0.0.1:9000"))) shouldBe Left("endpoint_not_allowed")
    code(input(endpoint = Some("http://minio.internal:9000"))) shouldBe Left("endpoint_not_allowed")
    code(input(endpoint = Some("minio.internal:9001"))) shouldBe Left("endpoint_not_allowed")
    code(input(endpoint = Some("minio.internal:9000/bucket"))) shouldBe Left("invalid_endpoint")
    code(input(endpoint = Some("ftp://minio.internal"))) shouldBe Left("invalid_endpoint")
    code(input(endpoint = Some("minio.internal:9000")), Nil) shouldBe Left("endpoint_not_allowed")
    ok(input(endpoint = Some("  ")), Nil).map(_.endpoint) shouldBe Right(None)
  }

  "the allowed endpoints" should "parse host[:port] and http://host[:port], nothing else" in {
    UserS3CredentialRules.parseAllowedEndpoints("") shouldBe Right(Nil)
    UserS3CredentialRules.parseAllowedEndpoints(
      " MinIO.internal:9000 , http://127.0.0.1:9000,"
    ) shouldBe
      Right(List("minio.internal:9000", "http://127.0.0.1:9000"))
    UserS3CredentialRules.parseAllowedEndpoints("https://minio.internal").isLeft shouldBe true
    UserS3CredentialRules.parseAllowedEndpoints("minio.internal/x").isLeft shouldBe true
    UserS3CredentialRules.parseAllowedEndpoints("a b").isLeft shouldBe true
  }

  "toString" should "never print the secret or the session token" in {
    val in = input(token = Some(Token))
    in.toString should not include Secret
    in.toString should not include Token
    ok(in).toOption.get.toString should not include Secret
    ok(in).toOption.get.toString should not include Token
    val c = UserS3Credential(
      "0" * 32,
      "u-1",
      "s3://acme-etl/",
      "AKIAEXAMPLE",
      Secret,
      Some(Token),
      None,
      None,
      None,
      1L,
      2L
    )
    c.toString should not include Secret
    c.toString should not include Token
    c.toString should include(FederatedSecret.RedactedMarker)
  }

  "newId" should "be 32 hex digits whose first 8 differ from every taken id" in {
    UserS3CredentialRules.newId(Nil) should fullyMatch regex "[0-9a-f]{32}"
    val draws = Iterator("0123abcd" + "0" * 24, "feedbeef" + "2" * 24)
    UserS3CredentialRules.newId(List("0123abcd" + "f" * 24), () => draws.next()) shouldBe
      "feedbeef" + "2" * 24
  }

  "the node's view" should "name the secret by the id and tell TLS from the endpoint" in {
    val c =
      UserS3Credential(
        "0123abcd" + "0" * 24,
        "u-1",
        "s3://b/",
        "k",
        "s",
        None,
        None,
        None,
        None,
        1L,
        1L
      )
    c.secretName shouldBe "qod_uc_0123abcd"
    (c.useSsl, c.endpointHost) shouldBe (true, "")
    val minio = c.copy(endpoint = Some("http://127.0.0.1:9000"))
    (minio.useSsl, minio.endpointHost) shouldBe (false, "127.0.0.1:9000")
    val tls = c.copy(endpoint = Some("minio.internal:9000"))
    (tls.useSsl, tls.endpointHost) shouldBe (true, "minio.internal:9000")
  }

  "a scope" should "refuse the code points no listing may show: format, separator, lone surrogate" in
    List(
      "s3://acme-etl/in\u200b/",
      "s3://acme-etl/in\u202e/",
      "s3://acme-etl/in\u2028/",
      "s3://acme-etl/in\uDB40\uDC41/",
      "s3://acme-etl/in\uD834/"
    ).foreach { raw =>
      withClue(raw.codePoints.toArray.map(Integer.toHexString).mkString(" ")) {
        UserS3CredentialRules.normalizeScope(raw).left.map(_._1) shouldBe Left("invalid_scope")
      }
    }

  "an allowed endpoint's port" should "lie between 1 and 65535" in {
    UserS3CredentialRules.normalizeEndpoint("minio.internal:65535") shouldBe Some(
      "minio.internal:65535"
    )
    UserS3CredentialRules.normalizeEndpoint("minio.internal:1") shouldBe Some("minio.internal:1")
    List("minio.internal:0", "minio.internal:65536", "minio.internal:99999", "http://h.example:0")
      .foreach(e => withClue(e)(UserS3CredentialRules.normalizeEndpoint(e) shouldBe None))
    UserS3CredentialRules.parseAllowedEndpoints("minio.internal:99999").isLeft shouldBe true
  }
