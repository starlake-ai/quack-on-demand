// src/test/scala/ai/starlake/quack/ondemand/DuckLakeInitializerEscapeSpec.scala
package ai.starlake.quack.ondemand

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Unit-level pins for the two escape helpers that defend the ATTACH connstring
  * against special chars in `pgPassword` (and friends). Spinning up a real
  * Postgres + DuckDB to test end-to-end belongs in
  * [[DuckLakeInitializerRaceSpec]]; here we just lock in the string outputs.
  */
class DuckLakeInitializerEscapeSpec extends AnyFlatSpec with Matchers:

  // ---------- duckdbLiteral ----------

  "duckdbLiteral" should "wrap an empty value in '' (the empty SQL string literal)" in {
    DuckLakeInitializer.duckdbLiteral("") shouldBe "''"
  }

  it should "wrap a plain value with no special chars" in {
    DuckLakeInitializer.duckdbLiteral("acme") shouldBe "'acme'"
  }

  it should "double a single embedded apostrophe" in {
    DuckLakeInitializer.duckdbLiteral("foo'bar") shouldBe "'foo''bar'"
  }

  it should "leave backslashes untouched (DuckDB strings are not C-style)" in {
    DuckLakeInitializer.duckdbLiteral("a\\b") shouldBe "'a\\b'"
  }

  // ---------- libpqValue ----------

  "libpqValue" should "single-quote a plain value so libpq parses unambiguously" in {
    DuckLakeInitializer.libpqValue("acme_db") shouldBe "'acme_db'"
  }

  it should "escape an embedded apostrophe with a backslash" in {
    DuckLakeInitializer.libpqValue("foo'bar") shouldBe "'foo\\'bar'"
  }

  it should "escape an embedded backslash with a backslash" in {
    DuckLakeInitializer.libpqValue("a\\b") shouldBe "'a\\\\b'"
  }

  it should "escape backslash BEFORE apostrophe so the escape mark isn't itself doubled" in {
    // Input: \'  -- a backslash followed by an apostrophe.
    // Wrong order (apostrophe-first then backslash-doubling) would yield
    // '\\\\\\'' (\\\\ = 2 backslashes representing 1 escaped \, plus \\' = \');
    // the libpq parser would then see `\\\'`  = `\\` (literal `\`) + `\'`
    // (literal `'`), value = `\'`. Correct ordering yields `'\\\'' `, which
    // libpq parses as backslash (one `\` from `\\`) + apostrophe (from
    // `\'`), value = `\'`. Either path happens to recover the same value
    // here -- the case below shows where the order genuinely matters.
    DuckLakeInitializer.libpqValue("\\'") shouldBe "'\\\\\\''"
  }

  it should "preserve spaces in the value" in {
    DuckLakeInitializer.libpqValue("a b c") shouldBe "'a b c'"
  }

  // ---------- layered: libpq inside DuckDB literal ----------

  "duckdbLiteral(libpqValue(_))" should "round-trip an apostrophe through both layers" in {
    // Password = foo'bar. Expected on the wire:
    //   - libpqValue       : 'foo\'bar'              (libpq-escaped, single-quoted)
    //   - duckdbLiteral    : '''foo\''bar'''         (DuckDB-literal, single quotes doubled)
    // When DuckDB parses '''foo\''bar''' it un-doubles each '' -> ',
    // yielding the inner string 'foo\'bar'. libpq then parses that as the
    // single value foo'bar. Verified mechanically by the equality below.
    val out = DuckLakeInitializer.duckdbLiteral(DuckLakeInitializer.libpqValue("foo'bar"))
    out shouldBe "'''foo\\''bar'''"
  }

  it should "round-trip a backslash through both layers" in {
    // Password = a\b. libpqValue wraps + doubles backslash -> 'a\\b'
    // (6 chars). duckdbLiteral doubles each ' (3 of them total: leading,
    // trailing, and the outer wrap), giving '''a\\b''' (10 chars). DuckDB
    // parses each '' back to ' yielding 'a\\b'; libpq then parses to a\b.
    val out = DuckLakeInitializer.duckdbLiteral(DuckLakeInitializer.libpqValue("a\\b"))
    out shouldBe "'''a\\\\b'''"
  }
