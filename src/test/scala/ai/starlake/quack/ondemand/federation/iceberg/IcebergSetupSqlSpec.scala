package ai.starlake.quack.ondemand.federation.iceberg

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class IcebergSetupSqlSpec extends AnyFlatSpec with Matchers:

  private def v(cfg: IcebergRestConfig, alias: String): ValidatedIcebergConfig =
    IcebergRestConfig.validated(cfg, alias).toOption.get

  private val oauth2 = IcebergRestConfig(
    uri = "https://catalog.example.com/api/catalog",
    warehouse = "sales",
    authType = Some(IcebergAuthType.OAuth2),
    clientId = Some("{{secret.CID}}"),
    clientSecret = Some("{{secret.CSEC}}"),
    oauth2Scope = Some("PRINCIPAL_ROLE:qod_reader")
  )

  "render" should "install and load the extension first" in {
    IcebergSetupSql.render(v(oauth2, "sales_lake")) should startWith(
      "INSTALL iceberg; LOAD iceberg;"
    )
  }

  it should "emit an oauth2 secret and reference it from the attach" in {
    val sql = IcebergSetupSql.render(v(oauth2, "sales_lake"))
    sql should include("""CREATE OR REPLACE SECRET "qod_ice_sales_lake"""")
    sql should include("TYPE ICEBERG")
    sql should include("""CLIENT_ID '{{secret.CID}}'""")
    sql should include("""CLIENT_SECRET '{{secret.CSEC}}'""")
    sql should include("""OAUTH2_SCOPE 'PRINCIPAL_ROLE:qod_reader'""")
    sql should include("""ATTACH 'sales' AS "sales_lake"""")
    sql should include("""SECRET "qod_ice_sales_lake"""")
    sql should include("""ENDPOINT 'https://catalog.example.com/api/catalog'""")
  }

  it should "omit AUTHORIZATION_TYPE for oauth2 because it is DuckDB's default" in {
    IcebergSetupSql.render(v(oauth2, "sales_lake")) should not include "AUTHORIZATION_TYPE"
  }

  it should "omit oauth2 options that are not set" in {
    val sql = IcebergSetupSql.render(v(oauth2, "sales_lake"))
    sql should not include "OAUTH2_SERVER_URI"
    sql should not include "OAUTH2_GRANT_TYPE"
  }

  it should "emit a token secret with no AUTHORIZATION_TYPE" in {
    val cfg = IcebergRestConfig(
      uri = "https://c.example.com",
      warehouse = "wh",
      authType = Some(IcebergAuthType.Token),
      token = Some("{{secret.BEARER}}")
    )
    val sql = IcebergSetupSql.render(v(cfg, "lake"))
    sql should include("""TOKEN '{{secret.BEARER}}'""")
    sql should not include "AUTHORIZATION_TYPE"
    sql should include("""SECRET "qod_ice_lake"""")
  }

  it should "emit ENDPOINT inside the oauth2 secret so ATTACH can reach the token endpoint" in {
    // Regression test: without ENDPOINT in the SECRET, DuckDB fails ATTACH with
    // "AUTHORIZATION_TYPE is 'oauth2', yet no 'oauth2_server_uri' was provided, and no
    // 'endpoint' was provided to fall back on" even though the ATTACH itself carries ENDPOINT -
    // DuckDB only reads that fallback off the SECRET.
    val sql         = IcebergSetupSql.render(v(oauth2, "sales_lake"))
    val secretBlock = sql.substring(0, sql.indexOf("ATTACH"))
    secretBlock should include("""ENDPOINT 'https://catalog.example.com/api/catalog'""")
  }

  it should "still carry ENDPOINT on the ATTACH alongside the secret's own ENDPOINT" in {
    val sql         = IcebergSetupSql.render(v(oauth2, "sales_lake"))
    val attachBlock = sql.substring(sql.indexOf("ATTACH"))
    attachBlock should include("""ENDPOINT 'https://catalog.example.com/api/catalog'""")
  }

  it should "not emit ENDPOINT inside a token secret, while the ATTACH still carries it" in {
    val cfg = IcebergRestConfig(
      uri = "https://c.example.com",
      warehouse = "wh",
      authType = Some(IcebergAuthType.Token),
      token = Some("{{secret.BEARER}}")
    )
    val sql         = IcebergSetupSql.render(v(cfg, "lake"))
    val secretBlock = sql.substring(0, sql.indexOf("ATTACH"))
    val attachBlock = sql.substring(sql.indexOf("ATTACH"))
    secretBlock should not include "ENDPOINT"
    attachBlock should include("""ENDPOINT 'https://c.example.com'""")
  }

  it should "still emit OAUTH2_SERVER_URI plus the secret ENDPOINT when oauth2ServerUri is set" in {
    val cfg = oauth2.copy(oauth2ServerUri = Some("https://auth.example.com/oauth/token"))
    val sql = IcebergSetupSql.render(v(cfg, "sales_lake"))
    sql should include("""OAUTH2_SERVER_URI 'https://auth.example.com/oauth/token'""")
    val secretBlock = sql.substring(0, sql.indexOf("ATTACH"))
    secretBlock should include("""ENDPOINT 'https://catalog.example.com/api/catalog'""")
  }

  it should "emit AUTHORIZATION_TYPE none and no secret for unauthenticated catalogs" in {
    val cfg = IcebergRestConfig(
      uri = "http://localhost:8181/catalog",
      warehouse = "wh",
      authType = Some(IcebergAuthType.NoAuth)
    )
    val sql = IcebergSetupSql.render(v(cfg, "lake"))
    sql should include("""AUTHORIZATION_TYPE 'none'""")
    sql should not include "CREATE OR REPLACE SECRET"
    sql should not include "SECRET \"qod_ice_lake\""
  }

  it should "emit AUTHORIZATION_TYPE sigv4 with no secret" in {
    val cfg = IcebergRestConfig(
      uri = "https://glue.eu-west-1.amazonaws.com/iceberg",
      warehouse = "wh",
      authType = Some(IcebergAuthType.SigV4)
    )
    val sql = IcebergSetupSql.render(v(cfg, "lake"))
    sql should include("""AUTHORIZATION_TYPE 'sigv4'""")
    sql should not include "CREATE OR REPLACE SECRET"
  }

  it should "emit ENDPOINT_TYPE and no AUTHORIZATION_TYPE for glue" in {
    val cfg = IcebergRestConfig(
      warehouse = "123456789012:mycatalog",
      endpointType = Some(IcebergEndpointType.Glue)
    )
    val sql = IcebergSetupSql.render(v(cfg, "glue_lake"))
    sql should include("""ENDPOINT_TYPE 'glue'""")
    sql should not include "AUTHORIZATION_TYPE"
    sql should not include "ENDPOINT '"
  }

  it should "emit ENDPOINT_TYPE s3_tables" in {
    val cfg = IcebergRestConfig(
      warehouse = "arn:aws:s3tables:eu-west-1:123456789012:bucket/b",
      endpointType = Some(IcebergEndpointType.S3Tables)
    )
    IcebergSetupSql.render(v(cfg, "s3t")) should include("""ENDPOINT_TYPE 's3_tables'""")
  }

  it should "escape single quotes in literals" in {
    val cfg = oauth2.copy(warehouse = "o'brien")
    IcebergSetupSql.render(v(cfg, "lake")) should include("""ATTACH 'o''brien'""")
  }

  it should "render a padded clientSecret without the padding it was validated with" in {
    val cfg = oauth2.copy(clientSecret = Some(" {{secret.CSEC}} "))
    val sql = IcebergSetupSql.render(v(cfg, "sales_lake"))
    sql should include("""CLIENT_SECRET '{{secret.CSEC}}'""")
    sql should not include "' {{secret.CSEC}} '"
  }

  it should "trim a padded warehouse before it lands on the ATTACH" in {
    val cfg = oauth2.copy(warehouse = " sales ")
    IcebergSetupSql.render(v(cfg, "sales_lake")) should include("""ATTACH 'sales' AS""")
  }

  it should "leave secret placeholders untouched so the blob builder can substitute them" in {
    val sql = IcebergSetupSql.render(v(oauth2, "sales_lake"))
    sql should include("{{secret.CID}}")
    sql should include("{{secret.CSEC}}")
  }

  it should "produce statements the DuckDB parser accepts in order" in {
    val sql = IcebergSetupSql.render(v(oauth2, "sales_lake"))
    sql.indexOf("CREATE OR REPLACE SECRET") should be < sql.indexOf("ATTACH")
    sql.trim should endWith(");")
  }

  "secretName" should "prefix the alias as-is, without lowercasing it" in {
    IcebergSetupSql.secretName("sales_lake") shouldBe "qod_ice_sales_lake"
    IcebergSetupSql.secretName("Sales_Lake") shouldBe "qod_ice_Sales_Lake"
  }

  "render with readOnly = true" should "emit a bare READ_ONLY option on the ATTACH" in {
    val sql         = IcebergSetupSql.render(v(oauth2, "sales_lake"), readOnly = true)
    val attachBlock = sql.substring(sql.indexOf("ATTACH"))
    attachBlock should include("READ_ONLY")
  }

  it should "emit READ_ONLY as a bare flag, not a key-value pair" in {
    val sql = IcebergSetupSql.render(v(oauth2, "sales_lake"), readOnly = true)
    // Exact substring: READ_ONLY is appended last (see render), immediately before the ATTACH's
    // closing "\n);" - a bare flag, never followed by a space then a value.
    sql should include("READ_ONLY\n);")
  }

  it should "not put READ_ONLY on the CREATE SECRET block" in {
    val sql         = IcebergSetupSql.render(v(oauth2, "sales_lake"), readOnly = true)
    val secretBlock = sql.substring(0, sql.indexOf("ATTACH"))
    secretBlock should not include "READ_ONLY"
  }

  "render with readOnly = false (the default)" should "emit no READ_ONLY anywhere" in {
    IcebergSetupSql.render(v(oauth2, "sales_lake")) should not include "READ_ONLY"
  }

  it should "emit no READ_ONLY when explicitly passed false" in {
    IcebergSetupSql.render(v(oauth2, "sales_lake"), readOnly = false) should not include "READ_ONLY"
  }
