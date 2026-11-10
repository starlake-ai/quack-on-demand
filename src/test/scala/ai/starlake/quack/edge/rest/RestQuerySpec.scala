package ai.starlake.quack.edge.rest

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/** The schema-free grammar: every operator and every rejection with its code.
  */
class RestQuerySpec extends AnyFlatSpec with Matchers:

  private def parse(ps: (String, String)*): Either[RestError, RestQuery] = RestQuery.parse(ps)
  private def ok(ps: (String, String)*): RestQuery                       =
    parse(ps*).fold(e => fail(s"unexpected ${e.code}: ${e.message}"), identity)
  private def code(ps: (String, String)*): String =
    parse(ps*).fold(_.code, q => fail(s"expected an error, got $q"))
  private def only(ps: (String, String)*): Filter =
    val q = ok(ps*); q.filters should have size 1; q.filters.head

  "parse" should "build a comparison for each of eq neq gt gte lt lte, with and without not." in {
    val ops = List(
      "eq"  -> Comparison.Eq,
      "neq" -> Comparison.Neq,
      "gt"  -> Comparison.Gt,
      "gte" -> Comparison.Gte,
      "lt"  -> Comparison.Lt,
      "lte" -> Comparison.Lte
    )
    for (wire, cmp) <- ops do
      only("a" -> s"$wire.5") shouldBe Filter("a", false, Predicate.Compare(cmp, "5"))
      only("a" -> s"not.$wire.5") shouldBe Filter("a", true, Predicate.Compare(cmp, "5"))
  }

  it should "keep everything after the operator's dot as the value, dots included" in {
    only("a" -> "eq.1.5.x").predicate shouldBe Predicate.Compare(Comparison.Eq, "1.5.x")
  }

  it should "map a pattern without a literal % or _ to a LIKE pattern, * to %, \\ kept as is" in {
    only("a" -> "like.ab*").predicate shouldBe Predicate.Pattern(false, PatternForm.Like, "ab%")
    only("a" -> "not.ilike.*x*").predicate shouldBe
      Predicate.Pattern(true, PatternForm.Like, "%x%")
    only("a" -> "like.*a\\b*c*d").predicate shouldBe
      Predicate.Pattern(false, PatternForm.Like, "%a\\b%c%d")
  }

  it should "map a pattern with a literal % or _ to a linear form by where its wildcards sit" in {
    only("a" -> "like.50%_").predicate shouldBe
      Predicate.Pattern(false, PatternForm.Equals, "50%_")
    only("a" -> "like.50%*").predicate shouldBe
      Predicate.Pattern(false, PatternForm.StartsWith, "50%")
    only("a" -> "ilike.**_x").predicate shouldBe
      Predicate.Pattern(true, PatternForm.EndsWith, "_x")
    only("a" -> "not.like.*50%\\*").predicate shouldBe
      Predicate.Pattern(false, PatternForm.Contains, "50%\\")
  }

  it should "refuse a literal % or _ in a pattern with an inner wildcard" in {
    code("a" -> "like.a*5%") shouldBe "invalid_filter"
    code("a" -> "ilike.*a*_*") shouldBe "invalid_filter"
    code("a" -> "like._*_") shouldBe "invalid_filter"
  }

  it should "parse in lists with bare and quoted items, including , ) \" and \\ inside quotes" in {
    only("a" -> "in.(1,2,3)").predicate shouldBe Predicate.Items(Vector("1", "2", "3"))
    only("a" -> """in.("a,b","c)d","e\"f","g\\h",plain)""").predicate shouldBe
      Predicate.Items(Vector("a,b", "c)d", "e\"f", "g\\h", "plain"))
    only("a" -> "not.in.(\"\")").shouldBe(Filter("a", true, Predicate.Items(Vector(""))))
  }

  it should "parse is.null, is.true and is.false" in {
    only("a" -> "is.null").predicate shouldBe Predicate.Is(IsTarget.Null)
    only("a" -> "is.true").predicate shouldBe Predicate.Is(IsTarget.True)
    only("a" -> "not.is.false") shouldBe Filter("a", true, Predicate.Is(IsTarget.False))
  }

  it should "AND repeated filters on one column, in arrival order" in {
    ok("a" -> "gt.1", "b" -> "eq.x", "a" -> "lt.9").filters.map(_.param) shouldBe
      Vector("a", "b", "a")
  }

  it should "parse every order suffix, reading suffixes from the right" in {
    ok("order" -> "a,b.desc,c.asc.nullsfirst,d.nullslast,e.f.desc").order shouldBe Vector(
      OrderTerm("a", false, None),
      OrderTerm("b", true, None),
      OrderTerm("c", false, Some(NullsOrder.First)),
      OrderTerm("d", false, Some(NullsOrder.Last)),
      OrderTerm("e.f", true, None)
    )
  }

  it should "default to no select list, no order, no limit and offset 0" in {
    val q = ok()
    (q.select, q.order, q.limit, q.offset, q.filters) shouldBe
      (None, Vector.empty, None, 0, Vector.empty)
  }

  it should "parse select, limit, offset, pool, format and each asOf selector" in {
    val q = ok(
      "select" -> "a,B",
      "limit"  -> "10",
      "offset" -> "20",
      "order"  -> "a",
      "pool"   -> "p1",
      "format" -> "csv"
    )
    (q.select, q.limit, q.offset, q.pool, q.format) shouldBe
      (Some(Vector("a", "B")), Some(10), 20, Some("p1"), Some("csv"))
    ok("asOf" -> "42").asOf shouldBe Some(42L)
    ok("asOfTag" -> "v1").asOfTag shouldBe Some("v1")
    ok("asOfTs" -> "2024-01-01T00:00:00Z").asOfTs shouldBe Some(
      Instant.parse("2024-01-01T00:00:00Z")
    )
    ok("limit" -> "2147483647").limit shouldBe Some(Int.MaxValue)
    ok("offset" -> "0").offset shouldBe 0
  }

  it should "refuse an unknown operator, a missing operator and an empty value" in {
    code("a" -> "foo.1") shouldBe "invalid_filter"
    code("a" -> "5") shouldBe "invalid_filter"
    code("a" -> "not.not.eq.1") shouldBe "invalid_filter"
    code("a" -> "eq.") shouldBe "invalid_filter"
    code("a" -> "like.") shouldBe "invalid_filter"
    code("a" -> "is.maybe") shouldBe "invalid_filter"
  }

  it should "refuse an unbalanced or malformed in list" in {
    for bad <- List(
        "in.(1,2",
        "in.1,2)",
        "in.()",
        "in.(1,,2)",
        "in.(\"a)",
        "in.(\"a\"b)",
        "in.(\"a\\x\")",
        "in.(a(b)"
      )
    do withClue(bad)(code("a" -> bad) shouldBe "invalid_filter")
  }

  it should "refuse a reserved parameter given twice" in {
    for n <- RestQuery.Reserved.toList.filterNot(_ == "branch") do
      withClue(n)(code(n -> "1", n -> "1") shouldBe "invalid_parameter")
  }

  it should "refuse branch" in {
    code("branch" -> "main") shouldBe "invalid_parameter"
  }

  it should "refuse a duplicate select column, ASCII-case-insensitively" in {
    code("select" -> "a,b,a") shouldBe "invalid_parameter"
    code("select" -> "a,A") shouldBe "invalid_parameter"
    code("select" -> "a,,b") shouldBe "invalid_parameter"
  }

  it should "apply each cap at N+1 and not at N" in {
    def filters(n: Int) = (1 to n).map(i => s"c$i" -> "eq.1")
    parse(filters(RestQuery.MaxFilters)*).isRight shouldBe true
    code(filters(RestQuery.MaxFilters + 1)*) shouldBe "invalid_parameter"

    def in(n: Int) = "in.(" + (1 to n).mkString(",") + ")"
    parse("a" -> in(RestQuery.MaxInItems)).isRight shouldBe true
    code("a" -> in(RestQuery.MaxInItems + 1)) shouldBe "invalid_filter"

    def order(n: Int) = (1 to n).map(i => s"c$i").mkString(",")
    parse("order" -> order(RestQuery.MaxOrderTerms)).isRight shouldBe true
    code("order" -> order(RestQuery.MaxOrderTerms + 1)) shouldBe "invalid_parameter"

    parse("select" -> order(RestQuery.MaxSelected)).isRight shouldBe true
    code("select" -> order(RestQuery.MaxSelected + 1)) shouldBe "invalid_parameter"

    val atCap = "eq." + "x" * (RestQuery.MaxValueBytes - 3)
    parse("a" -> atCap).isRight shouldBe true
    code("a" -> (atCap + "x")) shouldBe "invalid_parameter"
    // The cap counts UTF-8 bytes, not chars.
    code("a" -> ("eq." + "é" * (RestQuery.MaxValueBytes / 2))) shouldBe "invalid_parameter"

    def wild(n: Int) = "like." + "*a" * n
    parse("a" -> wild(RestQuery.MaxWildcards)).isRight shouldBe true
    code("a" -> wild(RestQuery.MaxWildcards + 1)) shouldBe "invalid_filter"
  }

  it should "refuse a NUL byte in a name or a value" in {
    code("a" -> "eq.x\u0000") shouldBe "invalid_parameter"
    code("a\u0000" -> "eq.x") shouldBe "invalid_parameter"
    code("asOfTag" -> "t\u0000") shouldBe "invalid_parameter"
  }

  it should "refuse an empty parameter name" in {
    code("" -> "eq.1") shouldBe "invalid_parameter"
  }

  it should "refuse offset > 0 without order, and admit it with order or at 0" in {
    code("offset" -> "10") shouldBe "order_required"
    parse("offset" -> "10", "order" -> "a").isRight shouldBe true
    parse("offset" -> "0").isRight shouldBe true
  }

  it should "refuse more than one asOf selector" in {
    code("asOf" -> "1", "asOfTag" -> "t") shouldBe "invalid_selector"
    code("asOfTs" -> "2024-01-01T00:00:00Z", "asOfTag" -> "t") shouldBe "invalid_selector"
  }

  it should "refuse a malformed asOf or asOfTs" in {
    code("asOf" -> "x") shouldBe "invalid_selector"
    code("asOf" -> "-1") shouldBe "invalid_selector"
    code("asOf" -> "99999999999999999999") shouldBe "invalid_selector"
    code("asOfTs" -> "yesterday") shouldBe "invalid_selector"
    code("asOfTag" -> "") shouldBe "invalid_selector"
  }

  it should "refuse limit values +1, 01, 1e3, 0 and 2147483648" in {
    for bad <- List("+1", "01", "1e3", "0", "2147483648", "-1", "", " 1") do
      withClue(bad)(code("limit" -> bad) shouldBe "invalid_parameter")
    for bad <- List("+1", "01", "1e3", "2147483648", "-1") do
      withClue(bad)(code("offset" -> bad, "order" -> "a") shouldBe "invalid_parameter")
  }

  it should "refuse a reserved name in any case with a filter-shaped value as reserved_column" in {
    for name <- RestQuery.Reserved.toList ++ List("LIMIT", "Order", "asof", "ASOFTAG", "Format")
    do
      for value <- List("eq.5", "not.in.(a,b)", "is.null", "like.x*", "lte.")
      do withClue(s"$name=$value")(code(name -> value) shouldBe "reserved_column")
    // Decided before any other check, with no table in sight.
    code("limit" -> "eq.5", "offset" -> "3") shouldBe "reserved_column"
    // The message names the parameter, never its value.
    parse("Limit" -> "eq.secret").left.map(_.message).fold(identity, _ => "") should not include
      "secret"
  }

  it should "keep a reserved name with an ordinary value as the parameter it is" in {
    ok("limit" -> "5").limit shouldBe Some(5)
    ok("order" -> "eq").order shouldBe Vector(OrderTerm("eq", false, None))
    ok("order" -> "a.desc").order shouldBe Vector(OrderTerm("a", true, None))
    ok("asOfTag" -> "release.v1").asOfTag shouldBe Some("release.v1")
    ok("format" -> "csv").format shouldBe Some("csv")
    // An unknown operator is not filter-shaped: the parameter's own syntax answers.
    code("limit" -> "five.5") shouldBe "invalid_parameter"
    // A differently cased reserved name with an ordinary value is a column filter, and an
    // ordinary value is not a filter expression.
    code("Limit" -> "5") shouldBe "invalid_filter"
  }

  it should "never echo a parameter value in an error message" in {
    val secret = "secret@x.io"
    for ps <- List(
        Seq("email"  -> s"bogus.$secret"),
        Seq("email"  -> s"in.($secret"),
        Seq("limit"  -> secret),
        Seq("asOf"   -> secret),
        Seq("asOfTs" -> secret),
        Seq("branch" -> secret),
        Seq("email"  -> (s"eq.$secret" + "x" * 5000))
      )
    do
      val e = parse(ps*).left.toOption.get
      withClue(ps)(e.message should not include "secret")
  }

  it should "sanitise echoed parameter names to 64 printable ASCII chars" in {
    val e = parse(("bad\u0001nameé" + "x" * 100) -> "foo.1").left.toOption.get
    e.message should include("bad?name?")
    e.message.exists(c => c < 0x20 || c > 0x7e) shouldBe false
    RestError.sanitize("y" * 100) shouldBe "y" * 64
  }

  "decodeQueryString" should "decode percent sequences once and keep + literal" in {
    RestQuery.decodeQueryString("a=eq.1%2B1+x&b=%2541&c") shouldBe
      Right(Vector("a" -> "eq.1+1+x", "b" -> "%41", "c" -> ""))
    RestQuery.decodeQueryString("n%C3%A9=eq.%E2%82%AC&&") shouldBe Right(Vector("né" -> "eq.€"))
    RestQuery.decodeQueryString("") shouldBe Right(Vector.empty)
    RestQuery.decodeQueryString("a=b=c") shouldBe Right(Vector("a" -> "b=c"))
  }

  it should "refuse an invalid percent sequence or invalid UTF-8" in {
    for bad <- List("a=%", "a=%4", "a=%zz", "a%=1", "a=%C3", "a=%FF", "a=%C3%28") do
      withClue(bad)(
        RestQuery.decodeQueryString(bad).left.map(_.code) shouldBe
          Left("invalid_parameter")
      )
  }

  it should "surface a decoded NUL for parse to refuse" in {
    RestQuery.decodeQueryString("a=eq.%00").flatMap(RestQuery.parse).left.map(_.code) shouldBe
      Left("invalid_parameter")
  }
