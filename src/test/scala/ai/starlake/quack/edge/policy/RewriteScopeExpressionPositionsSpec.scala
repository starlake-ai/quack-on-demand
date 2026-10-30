package ai.starlake.quack.edge.policy

import ai.starlake.acl.model.Config
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Expression positions JSqlParser holds outside its own AST node types: a MAP literal keeps its
  * entries as java.util.Map.Entry pairs, and a JSON key/value pair (json_object) types its key and
  * value as Object. A subquery in either was never walked, so the statement read no table, while
  * DuckDB 2.0 runs the subquery (checked on the CLI). Pinned on [[RewriteScope]], the parse the
  * policy steps keep.
  */
class RewriteScopeExpressionPositionsSpec extends AnyFlatSpec with Matchers:

  private val cfg = Config.forDuckDB(Some("lake"), Some("main"), Set("lake"))

  private def scope(sql: String): RewriteScope.Scope =
    RewriteScope.scopeOf(sql, cfg, "lake", clientDataScans = false)

  private def gated(sql: String): Set[String] =
    scope(sql) match
      case RewriteScope.Scope.Reaches(g, _) if g.nonEmpty => g.map(_.table.canonical)
      case other => fail(s"expected tables, got $other for: $sql")

  /** The scope of `sql` reads `secret` (with `exactly`, nothing else), or is refused. */
  private def readsSecretOrRefused(
      sql: String,
      exactly: Boolean = false
  ): org.scalatest.Assertion =
    scope(sql) match
      case RewriteScope.Scope.Refused(_)                  => succeed
      case RewriteScope.Scope.Reaches(g, _) if g.nonEmpty =>
        if exactly then g.map(_.table.canonical) shouldBe Set("lake.main.secret")
        else g.map(_.table.canonical) should contain("lake.main.secret")
      case other => fail(s"admitted ungated: $other")

  "a subquery in a MAP literal" should "be gated as a value" in:
    gated("SELECT map {'a': (SELECT v FROM secret)}") shouldBe Set("lake.main.secret")
    gated("SELECT map {'a': (SELECT v FROM secret)}['a']") shouldBe Set("lake.main.secret")

  it should "be gated as a key" in:
    gated("SELECT MAP {(SELECT v FROM secret): 1}") shouldBe Set("lake.main.secret")

  it should "be gated inside a write's source" in:
    gated("INSERT INTO t SELECT map {'a': (SELECT v FROM secret)}") should contain(
      "lake.main.secret"
    )

  "a subquery in a json_object argument" should "be gated" in:
    gated("SELECT json_object('a', (SELECT v FROM secret))") shouldBe Set("lake.main.secret")

  // DuckDB's `ARRAY(<query>)` hands its query to the function as a BARE select (no parentheses
  // of its own). The walker used to mark it visited as an expression before walking it as a
  // select, so every form below read no table while the 2.0 CLI returned the rows.
  "a query passed bare to ARRAY()" should "gate every table it reads" in:
    gated("SELECT ARRAY(SELECT v FROM secret)") shouldBe Set("lake.main.secret")
    gated("SELECT array(SELECT v FROM lake.main.secret)") shouldBe Set("lake.main.secret")
    gated("SELECT ARRAY(SELECT v FROM secret)[1]") shouldBe Set("lake.main.secret")
    gated("SELECT unnest(ARRAY(SELECT v FROM secret))") shouldBe Set("lake.main.secret")
    gated("SELECT ARRAY(SELECT v FROM secret UNION ALL SELECT w FROM other)") shouldBe
      Set("lake.main.secret", "lake.main.other")
    gated("SELECT ARRAY(SELECT v FROM secret JOIN other ON true)") shouldBe
      Set("lake.main.secret", "lake.main.other")
    gated("SELECT ARRAY(SELECT v FROM secret s, other o WHERE s.id = o.id)") shouldBe
      Set("lake.main.secret", "lake.main.other")
    gated("SELECT ARRAY(SELECT v FROM secret WHERE v IN (SELECT w FROM other))") shouldBe
      Set("lake.main.secret", "lake.main.other")
    gated("SELECT ARRAY(WITH c AS (SELECT v FROM secret) SELECT v FROM c)") shouldBe
      Set("lake.main.secret")
    gated("SELECT ARRAY(SELECT ARRAY(SELECT v FROM secret))") shouldBe Set("lake.main.secret")

  it should "gate the read inside a write's source" in:
    gated("INSERT INTO t SELECT ARRAY(SELECT v FROM secret)") should contain("lake.main.secret")
    gated("INSERT INTO t VALUES (ARRAY(SELECT v FROM secret))") should contain("lake.main.secret")
    gated("UPDATE t SET a = ARRAY(SELECT v FROM secret)") should contain("lake.main.secret")
    gated("CREATE TABLE t2 AS SELECT ARRAY(SELECT v FROM secret) AS a") should contain(
      "lake.main.secret"
    )

  it should "refuse it in a table function's argument" in:
    readsSecretOrRefused("SELECT * FROM unnest(ARRAY(SELECT v FROM secret))")

  // `SET VARIABLE x = (<query>)` runs the query: alone (its error message carries the value, as
  // `Could not convert string '<value>' to INT32`) or batched with `getvariable` (review C2). It
  // is no table-free control flow.
  "a SET VARIABLE whose value reads a table" should "never read no table" in:
    List(
      "SET VARIABLE x = (SELECT v FROM secret)",
      "SET VARIABLE x = (SELECT v::INT FROM secret)",
      "SET VARIABLE x = map {'a': (SELECT v FROM secret)}",
      "SET VARIABLE x = ARRAY(SELECT v FROM secret)",
      "SET VARIABLE x = [(SELECT v FROM secret)]",
      "SET VARIABLE x = (SELECT v FROM secret); SELECT getvariable('x')",
      "SELECT 1; SET VARIABLE x = (SELECT v FROM secret)"
    ).foreach(sql => withClue(sql)(readsSecretOrRefused(sql)))

  it should "stay control flow with a table-free value" in:
    List("SET VARIABLE x = 42", "SET VARIABLE x = [1, 2]", "SET VARIABLE x = (SELECT 1)")
      .foreach { sql =>
        withClue(sql)(scope(sql) shouldBe RewriteScope.Scope.Reaches(Set.empty, Set.empty))
      }

  // DuckDB's `ARRAY(TABLE t)` and `x = ANY (TABLE t)` read every row of t: a TABLE statement in
  // the one function argument / quantified-comparison position DuckDB accepts it. JSqlParser holds
  // it as a function flagged with the TABLE keyword whose argument is a COLUMN named t, so no read
  // was gated, while the 2.0 CLI returned t's rows.
  "a TABLE statement passed to ARRAY() or a quantified comparison" should "gate the table" in:
    List(
      "SELECT ARRAY(TABLE secret)",
      "SELECT array(table \"secret\")",
      "SELECT ARRAY(TABLE main.secret)",
      "SELECT ARRAY(TABLE lake.main.secret)",
      "SELECT len(ARRAY(TABLE secret))",
      "SELECT unnest(ARRAY(TABLE secret))",
      "SELECT ARRAY(TABLE secret)[1]",
      "SELECT ARRAY(TABLE secret ORDER BY 1)",
      "SELECT ARRAY(TABLE secret LIMIT 1)",
      "SELECT 1 WHERE 'x' = ANY (TABLE secret)",
      "SELECT 1 WHERE 'x' = ALL (TABLE secret)",
      "SELECT 1 WHERE 'x' = SOME (TABLE secret)",
      "SELECT 1 WHERE NOT 'x' = ANY (TABLE secret)",
      "SELECT map {'a': ARRAY(TABLE secret)}",
      "SET VARIABLE x = ARRAY(TABLE secret)"
    ).foreach(sql => withClue(sql)(readsSecretOrRefused(sql, exactly = true)))

  it should "gate it beside the other reads of a statement and in a write's source" in:
    gated("SELECT a FROM t WHERE ARRAY(TABLE secret)[1] LIKE 'top%'") shouldBe
      Set("lake.main.t", "lake.main.secret")
    gated("INSERT INTO t SELECT len(ARRAY(TABLE secret)), 1") should contain("lake.main.secret")
    gated("UPDATE t SET a = len(ARRAY(TABLE secret))") should contain("lake.main.secret")
    gated("SELECT ARRAY(TABLE secret) FROM t") shouldBe Set("lake.main.t", "lake.main.secret")

  it should "bind a CTE of that name to the CTE, as DuckDB does" in:
    scope("WITH secret AS (SELECT 1 AS v) SELECT ARRAY(TABLE secret)") shouldBe
      RewriteScope.Scope.Reaches(Set.empty, Set.empty)
