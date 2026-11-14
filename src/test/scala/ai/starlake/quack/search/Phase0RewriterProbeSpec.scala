package ai.starlake.quack.search

import ai.starlake.quack.edge.cls.{ColumnCatalog, ColumnPolicyRewriter}
import ai.starlake.quack.edge.rls.RowPolicyRewriter
import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.*
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.DriverManager

/** Search Phase 0 probe (spec Q4, Q14): what the row-policy and column-policy rewriters do with the
  * statements of the search spec today, and whether their output binds on DuckDB. Each case prints
  * the outcome and the bind result and asserts the outcome observed today, so a change flips it on
  * purpose: the CLS separation (change 9 of docs/search-PHASE0.md) flips S1, D1 and D3.
  * `qod_search` does not exist on plain DuckDB, so a statement holding it is bound in a variant
  * where the call is replaced by its own input subquery: the table references, the join-back and
  * the `rowid` references are the same.
  */
class Phase0RewriterProbeSpec extends AnyFlatSpec with Matchers:

  private val alice = RbacUser(id = "u-1", tenant = Some("acme"), username = "alice", kind = "user")

  private val rowPolicy =
    RoleRowPolicy("rp-1", "r-1", "*", "main", "pages", "cat = 3")
  private val maskBody =
    RoleColumnPolicy("cp-1", "r-1", "*", "main", "pages", "body", "mask", Some("'***'"))

  private def withRows: EffectiveSet = EffectiveSet(alice, Nil, Nil, Nil, Nil, Nil, List(rowPolicy))
  private def withMask: EffectiveSet = EffectiveSet(alice, Nil, Nil, Nil, Nil, List(maskBody))

  private val rls    = new RowPolicyRewriter(enabled = true)
  private val rlsCtx = ai.starlake.quack.edge.rls.SchemaContext(Some("lake"), Some("main"))

  private val catalog = new ColumnCatalog.MapCatalog(
    Map(
      ("lake", "main", "pages") -> List("id", "title", "body", "lang", "cat"),
      ("lake", "main", "other") -> List("id", "secret")
    )
  )
  private val cls    = new ColumnPolicyRewriter(catalog, enabled = true)
  private val clsCtx =
    ai.starlake.quack.edge.cls.SchemaContext(Some("lake"), Some("main"), Set("lake"))

  private val search =
    "SELECT p.title, h.score FROM qod_search((SELECT rowid FROM lake.main.pages WHERE lang = 'fr'), " +
      "'lake.main.pages', ['title', 'body'], 'lakehouse') h " +
      "JOIN lake.main.pages p ON p.rowid = h.rowid ORDER BY h.score DESC"

  /** The same table references without the search call, so the result can be bound on DuckDB. */
  private val searchShape =
    "SELECT p.title FROM (SELECT rowid FROM lake.main.pages WHERE lang = 'fr') h " +
      "JOIN lake.main.pages p ON p.rowid = h.rowid"

  /** Each case with the outcome observed today, per rewriter (RLS, CLS): asserted, so Phase 1 sees
    * the column-policy blocker flip. Rewritten for RLS means the output still fails to bind on
    * `rowid` (logged on the bind line).
    */
  private val cases: List[(String, String, String, String)] = List(
    ("S1 search statement of the spec", search, "Rewritten", "PassthroughParseFailed"),
    ("S2 the same shape without the search call", searchShape, "Rewritten", "Passthrough"),
    (
      "S3 input alone",
      "SELECT rowid FROM lake.main.pages WHERE lang = 'fr'",
      "Rewritten",
      "Passthrough"
    ),
    (
      "S4 join-back alone",
      "SELECT p.title FROM lake.main.pages p WHERE p.rowid IN (1, 2, 3)",
      "Rewritten",
      "Passthrough"
    ),
    ("S5 star plus rowid", "SELECT *, rowid FROM lake.main.pages", "Rewritten", "Rewritten"),
    (
      "S6 snippet over the join-back",
      "SELECT qod_snippet(p.body, 'lakehouse') FROM lake.main.pages p WHERE p.rowid = 1",
      "Rewritten",
      "Rewritten"
    ),
    // Diagnostics for the column-policy outcome of S1: which part of it the resolver refuses.
    (
      "D1 search call alone, no join-back",
      "SELECT h.rowid FROM qod_search((SELECT rowid FROM lake.main.pages WHERE lang = 'fr'), " +
        "'lake.main.pages', ['title', 'body'], 'lakehouse') h",
      "Rewritten",
      "PassthroughParseFailed"
    ),
    (
      "D2 a built-in table function joined to the table",
      "SELECT p.title FROM range(3) h(rowid) JOIN lake.main.pages p ON p.rowid = h.rowid",
      "Rewritten",
      "PassthroughParseFailed"
    ),
    (
      "D3 search call without a subquery argument",
      "SELECT h.rowid FROM qod_search('lake.main.pages', ['title'], 'lakehouse') h",
      "Passthrough",
      "PassthroughParseFailed"
    )
  )

  /** The outcome's case name (`Rewritten`, `Passthrough`, `PassthroughParseFailed`, ...). */
  private def outcomeName(o: Any): String = o match
    case p: Product => p.productPrefix
    case other      => other.toString

  private def bind(sql: String): String =
    val conn = DriverManager.getConnection("jdbc:duckdb:")
    try
      val st = conn.createStatement()
      st.execute("ATTACH ':memory:' AS lake")
      st.execute(
        "CREATE TABLE lake.main.pages AS SELECT i AS id, 't' || i AS title, 'b' || i AS body, " +
          "'fr' AS lang, i % 7 AS cat FROM range(10) r(i)"
      )
      st.execute("CREATE MACRO qod_snippet(t, q) AS t")
      try
        st.executeQuery(sql).close()
        "binds"
      catch case e: java.sql.SQLException => "fails: " + e.getMessage.linesIterator.next()
    finally conn.close()

  private def bindable(sql: String): String =
    // A rewritten search statement still holds qod_search: replace the call by its first argument.
    val call = "qod_search("
    val at   = sql.toLowerCase.indexOf(call)
    if at < 0 then sql
    else
      val open  = at + call.length
      var depth = 0
      var i     = open
      while i < sql.length && !(depth == 0 && sql.charAt(i) == ',') do
        if sql.charAt(i) == '(' then depth += 1
        if sql.charAt(i) == ')' then depth -= 1
        i += 1
      val input = sql.substring(open, i).trim
      val close = sql.indexOf(") h", i)
      if close < 0 then
        throw new IllegalStateException(
          s"bindable: no ') h' after the search call's input in: $sql"
        )
      sql.substring(0, at) + input + sql.substring(close + 1)

  for (name, sql, rlsExpected, clsExpected) <- cases do
    "the row-policy rewriter" should s"record $name" in {
      val outcome = rls.rewrite(sql, StatementKind.Select, withRows, rlsCtx)
      info(s"input:   $sql")
      info(s"as-is:   ${bind(bindable(sql))}")
      info(s"outcome: $outcome")
      outcome match
        case RowPolicyRewriter.Rewritten(out) => info(s"bind:    ${bind(bindable(out))}")
        case _                                => info("bind:    (not rewritten)")
      outcomeName(outcome) shouldBe rlsExpected
    }

    "the column-policy rewriter" should s"record $name" in {
      val outcome = cls.rewrite(sql, StatementKind.Select, withMask, clsCtx).unsafeRunSync()
      info(s"input:   $sql")
      info(s"as-is:   ${bind(bindable(sql))}")
      info(s"outcome: $outcome")
      outcome match
        case ColumnPolicyRewriter.Rewritten(out) => info(s"bind:    ${bind(bindable(out))}")
        case _                                   => info("bind:    (not rewritten)")
      outcomeName(outcome) shouldBe clsExpected
    }

  // S7: the principal's only column policy is on another table (`lake.main.other`); the search
  // statement names no table that policy covers.
  private val maskOther =
    RoleColumnPolicy("cp-2", "r-1", "*", "main", "other", "secret", "mask", Some("'***'"))
  private def withOtherMask: EffectiveSet =
    EffectiveSet(alice, Nil, Nil, Nil, Nil, List(maskOther))

  "the column-policy rewriter" should
    "record S7 search statement, only column policy on another table" in {
      val outcome = cls.rewrite(search, StatementKind.Select, withOtherMask, clsCtx).unsafeRunSync()
      info(s"policy:  mask lake.main.other.secret only")
      info(s"input:   $search")
      info(s"outcome: $outcome")
      outcomeName(outcome) shouldBe "PassthroughParseFailed"
    }

  it should "record S8 the same shape without the call, only column policy on another table" in {
    val outcome =
      cls.rewrite(searchShape, StatementKind.Select, withOtherMask, clsCtx).unsafeRunSync()
    info(s"policy:  mask lake.main.other.secret only")
    info(s"input:   $searchShape")
    info(s"outcome: $outcome")
    outcomeName(outcome) shouldBe "Passthrough"
  }

  // G1, G2: the protected-write guard (ProtectedWriteGuard, decision 27), which runs before both
  // rewriters for a policy holder, on the search statement. Records whether its screens
  // (CatalogReachingCalls among them) refuse the call today.
  private val guard =
    new ai.starlake.quack.edge.policy.ProtectedWriteGuard(
      catalog,
      clsEnabled = true,
      rlsEnabled = true
    )
  private def guardName(o: ai.starlake.quack.edge.policy.GuardOutcome): String = o match
    case ai.starlake.quack.edge.policy.GuardOutcome.Allow   => "Allow"
    case ai.starlake.quack.edge.policy.GuardOutcome.Deny(_) => "Deny"

  "the protected-write guard" should "record G1 the search statement for a row-policy holder" in {
    val outcome = guard.check(search, StatementKind.Select, withRows, clsCtx)
    info(s"outcome: $outcome")
    guardName(outcome) shouldBe "Allow"
  }

  it should "record G2 the search statement for a column-policy holder" in {
    val outcome = guard.check(search, StatementKind.Select, withMask, clsCtx)
    info(s"outcome: $outcome")
    guardName(outcome) shouldBe "Allow"
  }
