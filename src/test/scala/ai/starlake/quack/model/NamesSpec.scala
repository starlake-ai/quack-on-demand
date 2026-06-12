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

  "Names.looksLikeTenantId" should "match the surrogate shape minted by PoolSupervisor" in:
    Names.looksLikeTenantId("t-02d0e86e") shouldBe true
    Names.looksLikeTenantId("t-00000000") shouldBe true
    Names.looksLikeTenantId("t-deadbeef") shouldBe true

  it should "reject display names and other shapes" in:
    Names.looksLikeTenantId("tpch")          shouldBe false  // valid display name, not an id
    Names.looksLikeTenantId("t_02d0e86e")    shouldBe false  // underscore, not hyphen
    Names.looksLikeTenantId("t-02D0E86E")    shouldBe false  // uppercase hex not minted
    Names.looksLikeTenantId("t-02d0e86")     shouldBe false  // 7 hex
    Names.looksLikeTenantId("t-02d0e86eX")   shouldBe false  // trailing junk
    Names.looksLikeTenantId("td-02d0e86e")   shouldBe false  // wrong prefix
    Names.looksLikeTenantId("")              shouldBe false
    Names.looksLikeTenantId(null)            shouldBe false

  it should "be disjoint from isValid (display-name shape)" in:
    // Display names exclude hyphen; tenant ids require it. The two spaces never overlap,
    // so the FlightSQL `tenant` connection param is unambiguous.
    val ids   = List("t-00000000", "t-02d0e86e", "t-ffffffff")
    val names = List("tpch", "acme", "_internal", "t1", "tenant1")
    ids.foreach(s   => withClue(s)(Names.isValid(s)          shouldBe false))
    names.foreach(s => withClue(s)(Names.looksLikeTenantId(s) shouldBe false))

  it should "still accept the new 32-hex form alongside legacy 8-hex ids" in:
    // Migrated 2026-06-12 from 8-char (32 bits, ~77K rows to 50% collision) to
    // the full UUID hex form (128 bits, effectively no collisions). Both must
    // continue to match so existing rows stay addressable.
    Names.looksLikeTenantId("t-abc12345abc12345abc12345abc12345") shouldBe true
    Names.looksLikeTenantId("t-02d0e86e9c5d4a3b8f6e1c2d4a3b8f6e") shouldBe true

  "Names.newSurrogateId" should "produce a prefix + 32 lowercase hex chars" in:
    val id = Names.newSurrogateId("t")
    id should fullyMatch regex "^t-[0-9a-f]{32}$"
    Names.looksLikeTenantId(id) shouldBe true

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
