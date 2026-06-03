package ai.starlake.quack.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class NamesSpec extends AnyFlatSpec with Matchers:

  "Names.isValid" should "accept Postgres-identifier strings" in:
    Names.isValid("acme")        shouldBe true
    Names.isValid("acme_corp")   shouldBe true
    Names.isValid("_acme")       shouldBe true
    Names.isValid("a1b2_c3")     shouldBe true
    Names.isValid("Acme")        shouldBe true

  it should "reject names not starting with a letter or underscore" in:
    Names.isValid("1acme") shouldBe false
    Names.isValid("-acme") shouldBe false
    Names.isValid(".acme") shouldBe false

  it should "reject names containing disallowed characters" in:
    Names.isValid("acme-corp")    shouldBe false
    Names.isValid("acme.eu.prod") shouldBe false
    Names.isValid("acme corp")    shouldBe false

  it should "reject empty / null / over-length names" in:
    Names.isValid("")                          shouldBe false
    Names.isValid(null)                        shouldBe false
    Names.isValid("a" * (Names.MaxLength + 1)) shouldBe false

  it should "accept a name at exactly MaxLength" in:
    Names.MaxLength shouldBe 63
    Names.isValid("a" + "b" * (Names.MaxLength - 1)) shouldBe true

  "Names.normalize" should "lowercase a valid input" in:
    Names.normalize("Acme")          shouldBe "acme"
    Names.normalize("ACME_EU_Prod")  shouldBe "acme_eu_prod"

  it should "throw on a bad input" in:
    intercept[IllegalArgumentException](Names.normalize("1acme"))
    intercept[IllegalArgumentException](Names.normalize(""))
    intercept[IllegalArgumentException](Names.normalize("bad name"))

  "Names.normalizeTenantDbName" should "leave an already-prefixed input unchanged" in:
    Names.normalizeTenantDbName("acme", "acme_prod")    shouldBe Right("acme_prod")
    Names.normalizeTenantDbName("acme", "acme_eu_west") shouldBe Right("acme_eu_west")

  it should "prepend the tenant prefix to a bare suffix" in:
    Names.normalizeTenantDbName("acme", "prod")  shouldBe Right("acme_prod")
    Names.normalizeTenantDbName("tpch", "tpch1") shouldBe Right("tpch_tpch1")

  it should "lowercase before composing" in:
    Names.normalizeTenantDbName("ACME", "Prod")      shouldBe Right("acme_prod")
    Names.normalizeTenantDbName("ACME", "Acme_Prod") shouldBe Right("acme_prod")

  it should "reject suffix == tenant (DuckDB attach restriction)" in:
    val out = Names.normalizeTenantDbName("acme", "acme")
    out.isLeft shouldBe true
    out.swap.toOption.get should include("must not equal the tenant name")

  it should "reject suffixes failing base validation" in:
    Names.normalizeTenantDbName("acme", "acme-prod").isLeft shouldBe true
    Names.normalizeTenantDbName("acme", "").isLeft          shouldBe true

  it should "reject when the composed name exceeds MaxLength" in:
    val long = "a" * (Names.MaxLength - 4) // 4 = len("acme_")-1: composing pushes past 63
    Names.normalizeTenantDbName("acme", long).isLeft shouldBe true
