package ai.starlake.quack.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.Locale

/** Naming and role parsing must not depend on the JVM's default locale.
  *
  * `Names.normalize` validates against `^[a-zA-Z_][a-zA-Z0-9_]*$` and THEN folds case. Under a
  * Turkish or Azeri default locale the fold emits a dotless i, so the value returned is one the
  * pattern just checked would have rejected: validation is defeated by the normalisation that
  * follows it. That name goes on to be a Postgres database name, a DuckDB catalog alias, part of an
  * RFC-1123 Kubernetes Secret name, and a path segment.
  */
class NamesLocaleSpec extends AnyFlatSpec with Matchers:

  private def withTurkishLocale[A](body: => A): A =
    val previous = Locale.getDefault
    Locale.setDefault(Locale.forLanguageTag("tr-TR"))
    try body
    finally Locale.setDefault(previous)

  private val Ascii = "^[a-z0-9_]*$".r

  "the Turkish locale" should "actually fold the ASCII i, or these tests prove nothing" in
    withTurkishLocale {
      "SALESI".toLowerCase should not be "salesi"
      "writeonly".toUpperCase should not be "WRITEONLY"
    }

  "Names.normalize" should "return an ASCII name it would itself accept, under a Turkish locale" in
    withTurkishLocale {
      val out = Names.normalize("SALESI")
      out shouldBe "salesi"
      Ascii.matches(out) shouldBe true
      Names.isValid(out) shouldBe true
    }

  it should "agree with the default locale for a name carrying an I" in {
    val underDefault = Names.normalize("SALESI")
    val underTurkish = withTurkishLocale(Names.normalize("SALESI"))
    underTurkish shouldBe underDefault
  }

  "Names.normalizeOrError" should "return an ASCII name under a Turkish locale" in
    withTurkishLocale {
      Names.normalizeOrError("TENANTI") shouldBe Right("tenanti")
    }

  "Names.normalizeTenantDbName" should "compose an ASCII database name under a Turkish locale" in
    withTurkishLocale {
      Names.normalizeTenantDbName("ACME", "SALESI") shouldBe Right("acme_salesi")
    }

  "PoolKey.apply" should "lowercase to ASCII under a Turkish locale" in
    withTurkishLocale {
      val k = PoolKey("ACMEI", "ACMEI_SALESI", "BI")
      k.tenant shouldBe "acmei"
      k.tenantDb shouldBe "acmei_salesi"
      k.pool shouldBe "bi"
    }

  "Role.parse" should "still parse every role under a Turkish locale" in
    withTurkishLocale {
      Role.parse("readonly") shouldBe Right(Role.ReadOnly)
      Role.parse("writeonly") shouldBe Right(Role.WriteOnly)
      Role.parse("dual") shouldBe Right(Role.Dual)
    }
