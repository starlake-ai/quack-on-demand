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

  "Names.normalizeTenantId" should "lowercase a name of letters and digits" in:
    Names.normalizeTenantId("Acme")   shouldBe Right("acme")
    Names.normalizeTenantId("acme2")  shouldBe Right("acme2")
    Names.normalizeTenantId("A1b2C3") shouldBe Right("a1b2c3")

  it should "refuse an underscore anywhere, saying what a tenant name holds and why" in:
    List("acme_corp", "_acme", "acme_", "a__b").foreach { id =>
      val out = Names.normalizeTenantId(id)
      withClue(id)(out.isLeft shouldBe true)
      out.swap.toOption.get should include("letters and digits only")
      out.swap.toOption.get should include("<tenant>_<database>")
    }

  it should "refuse a name not starting with a letter, or holding anything else" in:
    List("1acme", "-acme", "acme-corp", "acme.eu", "acme corp", "", null).foreach { id =>
      withClue(String.valueOf(id))(Names.normalizeTenantId(id).isLeft shouldBe true)
    }

  it should "refuse a name past MaxLength and accept one at it" in:
    Names.normalizeTenantId("a" * Names.MaxLength).isRight      shouldBe true
    Names.normalizeTenantId("a" * (Names.MaxLength + 1)).isLeft shouldBe true

  it should "keep a letter outside ASCII out" in:
    // The Kelvin sign lowercases to an ASCII k; a tenant id is a slug, composed as text.
    Names.normalizeTenantId("Kelvin").isLeft shouldBe true

  "Names.newSurrogateId" should "produce a prefix + 32 lowercase hex chars" in:
    val id = Names.newSurrogateId("t")
    id should fullyMatch regex "^t-[0-9a-f]{32}$"

  it should "honour the requested prefix" in:
    Names.newSurrogateId("td")   should startWith ("td-")
    Names.newSurrogateId("p")    should startWith ("p-")
    Names.newSurrogateId("rp")   should startWith ("rp-")
    Names.newSurrogateId("pp")   should startWith ("pp-")
    Names.newSurrogateId("g")    should startWith ("g-")
    Names.newSurrogateId("fsec") should startWith ("fsec-")

  it should "not collide across a reasonable batch (128-bit entropy)" in:
    // Birthday math: 128-bit entropy hits 50% collision around 2^64 ids -- a
    // batch of 10K should never collide. (The legacy 32-bit form collided at
    // ~77K, sanity check that we're not back there.)
    val ids = (1 to 10000).map(_ => Names.newSurrogateId("t")).toSet
    ids.size shouldBe 10000

  "Names.reading" should "lowercase a name and read an underscore as a hyphen" in:
    Names.reading("Eu_West") shouldBe "eu-west"
    Names.reading("eu-west") shouldBe "eu-west"
    Names.reading("acme") shouldBe "acme"

  "Names.readsAsOneField" should "take a name with a middle underscore or hyphen" in:
    List("a", "acme", "acme_corp", "a_b_c", "eu-west", "a1", "A_B").foreach { name =>
      withClue(name)(Names.readsAsOneField(name) shouldBe true)
    }

  it should "refuse what cannot be split back apart around `--`" in:
    List(
      "",
      "_",
      "-",
      "_a",
      "a_",
      "-a",
      "a-",
      "a__b",
      "a--b",
      "a_-b",
      "a-_b",
      "__merge",
      "__br_ab12cd34",
      "acme__br_ab12cd34"
    ).foreach(name => withClue(name)(Names.readsAsOneField(name) shouldBe false))

  "Names.fieldRefusal" should "say what is refused and why, for a name that breaks the rule" in:
    Names.fieldRefusal("acme", "tenant id") shouldBe None
    Names.fieldRefusal("acme_corp", "tenant id") shouldBe None
    List("a__b", "_a", "a_").foreach { name =>
      val refusal = Names.fieldRefusal(name, "tenant id")
      withClue(name) {
        refusal.get should include(s"tenant id '$name'")
        refusal.get should include("'--'")
      }
    }

  "Names.poolNameRefusal" should "accept letters, digits, `_` and `-`, starting with a letter" in:
    List("bi", "eu-west", "eu_west", "a1", "A1", "a-b_c", "p" * Names.MaxLength).foreach { name =>
      withClue(name)(Names.poolNameRefusal(name) shouldBe None)
    }

  it should "refuse anything else, saying so about that name" in:
    List(
      "",
      "1a",
      "-a",
      "_a",
      "my pool",
      "a/b",
      "a.b",
      "a\u0000b",
      "café",
      "__br_x",
      "__merge",
      "p" * (Names.MaxLength + 1)
    ).foreach { name =>
      withClue(name) {
        val refusal = Names.poolNameRefusal(name)
        refusal.get should include(s"'$name'")
        refusal.get should include("starting with a letter")
      }
    }

  it should "refuse a pool name that would not stand as one field" in:
    List("a_", "a-", "a__b", "a--b", "a_-b", "a-_b").foreach { name =>
      withClue(name) {
        val refusal = Names.poolNameRefusal(name)
        refusal.get should include(s"'$name'")
        refusal.get should include("'--'")
      }
    }
