package ai.starlake.quack.ondemand.federation.iceberg

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.util.Base64

class AttachErrorRedactorSpec extends AnyFlatSpec with Matchers:

  private val secret = "SENTINEL_CLIENT_SECRET_AAA111"

  /** The shape IcebergSetupSql.render emits, once FederationBlobBuilder has substituted the
    * resolved secret values.
    */
  private val renderedSql =
    s"""INSTALL iceberg; LOAD iceberg;
       |CREATE OR REPLACE SECRET "qod_ice_sales" (
       |  TYPE ICEBERG,
       |  CLIENT_ID 'qodprobeclient',
       |  CLIENT_SECRET '$secret',
       |  ENDPOINT 'http://127.0.0.1:18098'
       |);
       |ATTACH 'probe_warehouse' AS "sales" (
       |  TYPE ICEBERG,
       |  SECRET "qod_ice_sales",
       |  ENDPOINT 'http://127.0.0.1:18098'
       |);""".stripMargin

  "credentialsIn" should "pull the CLIENT_SECRET literal out of a rendered block" in {
    AttachErrorRedactor.credentialsIn(renderedSql) shouldBe Set(secret)
  }

  it should "pull the TOKEN literal and un-double its escaped quotes" in {
    AttachErrorRedactor.credentialsIn(
      "CREATE OR REPLACE SECRET \"s\" (\n  TYPE ICEBERG,\n  TOKEN 'ab''cd1234'\n);"
    ) shouldBe Set("ab'cd1234")
  }

  it should "not treat the client id as a credential" in {
    AttachErrorRedactor.credentialsIn(renderedSql) should not contain "qodprobeclient"
  }

  "scrub" should "remove a credential echoed back in plaintext" in {
    val err = s"Invalid Configuration Error: rejected credential $secret"
    AttachErrorRedactor.scrub(err, Set(secret)) shouldBe
      "Invalid Configuration Error: rejected credential [redacted]"
  }

  // The proven vector: DuckDB sends oauth2 credentials as `Authorization: Basic
  // base64(client_id:client_secret)` and splices the catalog's response body verbatim into its
  // error, so a catalog that echoes the header it received leaks the plaintext secret.
  it should "remove a credential carried inside an HTTP Basic blob" in {
    val basic = Base64.getEncoder.encodeToString(
      s"qodprobeclient:$secret".getBytes(StandardCharsets.UTF_8)
    )
    val err =
      s"""Invalid Configuration Error: Could not get token from http://c/v1/oauth/tokens: """ +
        s"""HTTP Unauthorized_401 - {"error": {"message": "rejected credential Basic $basic"}}"""
    val out = AttachErrorRedactor.scrub(err, AttachErrorRedactor.credentialsIn(renderedSql))
    out should not include basic
    out should not include secret
    new String(Base64.getMimeDecoder.decode(basic), StandardCharsets.UTF_8) should include(secret)
    out should include("Could not get token from http://c/v1/oauth/tokens")
    out should include("Unauthorized_401")
  }

  it should "leave an error that carries no credential untouched" in {
    val err = "Invalid Configuration Error: No ICEBERG secret by the name of 'qod_ice_sales' " +
      "could be found"
    AttachErrorRedactor.scrub(err, Set(secret)) shouldBe err
  }

  it should "leave an unrelated base64-looking run alone" in {
    val err = "Binder Error: unknown parameter in AAAABBBBCCCCDDDDEEEE"
    AttachErrorRedactor.scrub(err, Set(secret)) shouldBe err
  }

  it should "not shred the message for an implausibly short credential" in {
    val err = "Invalid Configuration Error: could not connect to a1"
    AttachErrorRedactor.scrub(err, Set("a1")) shouldBe err
  }

  it should "cap a hostile catalog's unbounded response body" in {
    val err = "Invalid Configuration Error: " + ("x" * 50000)
    val out = AttachErrorRedactor.scrub(err, Set.empty)
    out.length shouldBe AttachErrorRedactor.MaxErrorChars + " ... [truncated]".length
    out should endWith(" ... [truncated]")
  }
