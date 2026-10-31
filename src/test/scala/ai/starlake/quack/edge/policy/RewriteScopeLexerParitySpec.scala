package ai.starlake.quack.edge.policy

import ai.starlake.acl.model.Config
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Differential cases: statements whose literals or comments JSqlParser's own lexer reads
  * differently from DuckDB. DuckDB reads the subquery in each as code (checked on the 2.0 and 1.5.6
  * CLIs), so the parse must see the table it reads. Before the parser read lexer-canonical input,
  * each read no table. Pinned on [[RewriteScope]], the parse the policy steps keep.
  */
class RewriteScopeLexerParitySpec extends AnyFlatSpec with Matchers:

  private val cfg = Config.forDuckDB(Some("lake"), Some("main"), Set("lake"))

  private def scope(sql: String): RewriteScope.Scope =
    RewriteScope.scopeOf(sql, cfg, "lake", clientDataScans = false)

  private def gatedTables(sql: String): Set[String] =
    scope(sql) match
      case RewriteScope.Scope.Reaches(g, _) if g.nonEmpty => g.map(_.table.table)
      case other => fail(s"expected tables, got $other for: $sql")

  "the parse" should "see a table after an escape string holding an escaped quote" in:
    gatedTables("SELECT E'\\'' , (SELECT v FROM secret) AS x --'") should contain("secret")
    gatedTables("SELECT e'\\'' , (SELECT v FROM secret) AS x --'") should contain("secret")
    gatedTables("SELECT E'a\\\\' , (SELECT v FROM secret) AS x") should contain("secret")

  it should "see a table after a dollar-quoted string holding a quote" in:
    gatedTables("SELECT $x$'$x$ AS a, (SELECT v FROM secret) AS x --'") should contain("secret")
    gatedTables("SELECT $$'$$ AS a, (SELECT v FROM secret) AS x --'") should contain("secret")

  it should "see a table after a plain literal ending in a backslash" in:
    gatedTables("SELECT '\\' AS a, (SELECT v FROM secret) AS x") should contain("secret")

  it should "see a table after DuckDB's integer division operator" in:
    // JSqlParser reads `//` as a line comment
    gatedTables("SELECT 7 // 2, (SELECT v FROM secret) AS x") should contain("secret")

  it should "not read past a table behind a word directly followed by a literal" in:
    // JSqlParser reads q'{ ... }' as one Oracle quote. DuckDB reads the word q, then a literal (a
    // cast to a type named q); the parser input sets them apart, or the parse refuses the shape.
    scope("SELECT q'{' AS x, (SELECT v FROM secret) AS y, '}' AS z") match
      case RewriteScope.Scope.Reaches(g, _) if g.nonEmpty =>
        g.map(_.table.table) should contain("secret")
      case RewriteScope.Scope.Refused(_) => succeed
      case other                         => fail(s"read nothing: $other")

  it should "keep reading an ordinary statement unchanged" in:
    gatedTables("SELECT v FROM secret WHERE v = E'it\\'s' AND w = $$x$$") shouldBe Set("secret")
