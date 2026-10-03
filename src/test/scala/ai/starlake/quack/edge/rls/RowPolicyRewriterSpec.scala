package ai.starlake.quack.edge.rls

import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RowPolicyRewriterSpec extends AnyFlatSpec with Matchers:
  import RowPolicyRewriter._

  private val superuser = RbacUser(id = "u-super", tenant = None, username = "root", kind = "admin")
  private val tenantUser =
    RbacUser(id = "u-1", tenant = Some("acme"), username = "alice", kind = "user")

  private def policy(
      predicate: String,
      catalog: String = "*",
      schema: String = "tpch1",
      table: String = "customer",
      id: String = "rp-1"
  ): RoleRowPolicy =
    RoleRowPolicy(id, "r-1", catalog, schema, table, predicate)

  private def eff(
      user: RbacUser,
      rowPolicies: List[RoleRowPolicy] = Nil,
      roles: List[RbacRole] = Nil,
      groups: List[RbacGroup] = Nil
  ): EffectiveSet =
    EffectiveSet(user, roles, groups, Nil, Nil, Nil, rowPolicies)

  private val rw  = new RowPolicyRewriter(enabled = true)
  private val ctx =
    SchemaContext(defaultDatabase = Some("acme_tpch"), defaultSchema = Some("tpch1"))

  /** Run the rewrite and echo, at info level, the original SQL, the policy predicate(s) in scope,
    * and the resulting SQL (or the passthrough outcome). Every test routes through this so the test
    * report shows the before/after of each case.
    */
  private def go(
      sql: String,
      effSet: EffectiveSet,
      kind: StatementKind = StatementKind.Select,
      rewriter: RowPolicyRewriter = rw
  ): Outcome =
    val outcome = rewriter.rewrite(sql, kind, effSet, ctx)
    val preds   = effSet.rowPolicies.map(_.predicateSql)
    val result  = outcome match
      case Rewritten(s)           => s
      case Passthrough            => s"$sql  [passthrough]"
      case PassthroughParseFailed => s"$sql  [parse-failed]"
      case Failed(reason)         => s"$sql  [failed: $reason]"
      case Refused(reason)        => s"$sql  [refused: $reason]"
    info(s"original:  $sql")
    info(s"predicate: ${if preds.isEmpty then "(none)" else preds.mkString("  ;  ")}")
    info(s"result:    $result")
    outcome

  private def rewritten(o: Outcome): String = o match
    case Rewritten(sql) => sql.toLowerCase
    case other          => fail(s"expected Rewritten, got $other")

  // ---------- short-circuits ----------

  "rewrite" should "passthrough when the feature is disabled" in {
    go(
      "SELECT * FROM customer",
      eff(tenantUser, List(policy("c_region = 'eu'"))),
      rewriter = new RowPolicyRewriter(enabled = false)
    ) shouldBe Passthrough
  }

  it should "passthrough for superusers" in {
    go(
      "SELECT * FROM customer",
      eff(superuser, List(policy("c_region = 'eu'")))
    ) shouldBe Passthrough
  }

  it should "passthrough when the user has no row policies" in {
    go("SELECT * FROM customer", eff(tenantUser, Nil)) shouldBe Passthrough
  }

  it should "passthrough for non-Select statement kinds" in {
    val effSet = eff(tenantUser, List(policy("c_region = 'eu'")))
    go("INSERT INTO audit VALUES (1)", effSet, StatementKind.Dml) shouldBe Passthrough
    go("CREATE TABLE x(y INT)", effSet, StatementKind.Ddl) shouldBe Passthrough
    go("BEGIN", effSet, StatementKind.Begin) shouldBe Passthrough
  }

  it should "emit PassthroughParseFailed when the SQL fails to parse" in {
    go("SELEC' WRONG", eff(tenantUser, List(policy("c_region = 'eu'")))) shouldBe
      PassthroughParseFailed
  }

  it should "fail closed (not passthrough) when a stored predicate cannot be applied at splice time" in {
    // Simulates a row already stored before RowPredicateValidator rejected splice-unsafe
    // predicates: a trailing line comment that, once the rewriter wraps it in "(" + pred + ")",
    // comments out the wrapper's own closing paren and throws on re-parse.
    val effSet = eff(tenantUser, List(policy("a = 1 --")))
    go("SELECT * FROM customer", effSet) match
      case Failed(_) => succeed
      case other     => fail(s"expected Failed, got $other")
  }

  it should "passthrough when no referenced table matches a policy" in {
    go(
      "SELECT * FROM orders",
      eff(tenantUser, List(policy("c_region = 'eu'", table = "customer")))
    ) shouldBe
      Passthrough
  }

  // ---------- wrapping ----------

  it should "wrap a matched base table in a filtered subselect" in {
    val sql =
      rewritten(go("SELECT c_id FROM customer", eff(tenantUser, List(policy("c_region = 'eu'")))))
    sql should include("select * from customer where")
    sql should include("c_region = 'eu'")
  }

  it should "wrap a double-quoted table reference (BI folded SQL shape)" in {
    // Power BI's folded SQL quotes every identifier; jsqlparser keeps the quotes
    // on getName/getSchemaName/getDatabaseName, and an unstripped match let the
    // reference silently escape its row policies (fail-open).
    val sql = rewritten(
      go(
        "select distinct \"c_mktsegment\" from \"tpch1\".\"customer\"",
        eff(tenantUser, List(policy("c_mktsegment = 'BUILDING'")))
      )
    )
    sql should include("c_mktsegment = 'building'")
    // the caller's quoting survives in the rewritten reference
    sql should include("\"tpch1\".\"customer\"")
  }

  it should "wrap a fully catalog-qualified quoted reference" in {
    val sql = rewritten(
      go(
        "select \"c_id\" from \"acme_tpch\".\"tpch1\".\"customer\"",
        eff(tenantUser, List(policy("c_region = 'eu'")))
      )
    )
    sql should include("c_region = 'eu'")
  }

  it should "wrap a backtick-quoted table reference like its double-quoted twin" in
    // DuckDB 2.0 reads a backtick pair as an identifier quote, and the ACL authorizes
    // `customer` for it: a match that strips only double quotes left the read unfiltered.
    Seq(
      "SELECT * FROM `customer`",
      "SELECT c_id FROM `tpch1`.`customer`",
      "SELECT c_id FROM `acme_tpch`.`tpch1`.`customer` AS c",
      "SELECT c_id FROM `Customer` WHERE c_id > 1",
      "SELECT * FROM (SELECT c_id FROM `customer`) s",
      "SELECT c_id FROM customer WHERE c_id IN (SELECT c_id FROM `customer`)"
    ).foreach { q =>
      withClue(q) {
        val sql = rewritten(go(q, eff(tenantUser, List(policy("c_region = 'eu'")))))
        sql should include("c_region = 'eu'")
        sql should not include "`"
      }
    }

  it should "still not match a policy for a different quoted table" in {
    go(
      "select \"o_id\" from \"tpch1\".\"orders\"",
      eff(tenantUser, List(policy("c_region = 'eu'", table = "customer")))
    ) shouldBe Passthrough
  }

  it should "preserve the caller's table alias" in {
    val sql = rewritten(
      go("SELECT c.c_id FROM customer c", eff(tenantUser, List(policy("c_region = 'eu'"))))
    )
    // outer reference c.c_id must still resolve -> wrapper carries alias `c`
    sql should include("c_id")
    sql.replaceAll("\\s+", " ") should (include(") as c") or include(") c"))
  }

  it should "substitute the ${user} identity token with a quoted literal" in {
    val sql =
      rewritten(go("SELECT * FROM customer", eff(tenantUser, List(policy("c_owner = ${user}")))))
    sql should include("c_owner = 'alice'")
  }

  it should "expand a ${groups} list token for an IN predicate" in {
    val groups = List(RbacGroup("g-1", "acme", "sales"), RbacGroup("g-2", "acme", "eng"))
    val sql    = rewritten(
      go(
        "SELECT * FROM customer",
        eff(tenantUser, List(policy("c_dept IN (${groups})")), groups = groups)
      )
    )
    sql should include("'sales'")
    sql should include("'eng'")
  }

  it should "collapse an empty ${groups} list to NULL (matches no rows)" in {
    val sql = rewritten(
      go("SELECT * FROM customer", eff(tenantUser, List(policy("c_dept IN (${groups})"))))
    )
    sql.replaceAll("\\s+", " ") should include("in (null)")
  }

  it should "OR-combine multiple policies on the same table" in {
    val policies = List(
      policy("c_region = 'eu'", id = "rp-1"),
      policy("c_tier = 'gold'", id = "rp-2")
    )
    val sql = rewritten(go("SELECT * FROM customer", eff(tenantUser, policies)))
    sql should include("c_region = 'eu'")
    sql should include("c_tier = 'gold'")
    sql should include(" or ")
  }

  it should "match a wildcard-table policy against any table" in {
    val sql = rewritten(
      go(
        "SELECT * FROM orders",
        eff(tenantUser, List(policy("tenant_id = ${tenantId}", table = "*")))
      )
    )
    sql should include("select * from orders where")
    sql should include("tenant_id = 'acme'")
  }

  it should "filter both sides of a join" in {
    val policies = List(
      policy("c_region = 'eu'", table = "customer", id = "rp-1"),
      policy("o_year = 2024", table = "orders", id = "rp-2")
    )
    val sql = rewritten(
      go("SELECT * FROM customer c JOIN orders o ON c.c_id = o.c_id", eff(tenantUser, policies))
    )
    sql should include("c_region = 'eu'")
    sql should include("o_year = 2024")
  }

  it should "SQL-escape a single quote in a substituted identity value" in {
    val odd = RbacUser(id = "u-2", tenant = Some("acme"), username = "o'brien", kind = "user")
    val sql = rewritten(go("SELECT * FROM customer", eff(odd, List(policy("c_owner = ${user}")))))
    sql should include("'o''brien'")
  }

  // ---------- walk completeness: shapes that used to pass through unfiltered ----------
  //
  // Every case below is a SELECT that reads a row-filtered table through a node shape the
  // hand-rolled FROM walk did not reach (deep-review C2/C3). Each must come back Rewritten
  // with the predicate applied, never Passthrough.

  it should "wrap the table in DuckDB FROM-first shorthand" in {
    val sql = rewritten(go("FROM customer", eff(tenantUser, List(policy("c_region = 'eu'")))))
    sql should include("c_region = 'eu'")
  }

  it should "wrap a table inside a parenthesized join" in {
    val sql = rewritten(
      go(
        "SELECT * FROM (customer c JOIN orders o ON c.c_id = o.c_id)",
        eff(tenantUser, List(policy("c_region = 'eu'")))
      )
    )
    sql should include("c_region = 'eu'")
  }

  it should "wrap a table read by a scalar subquery in the projection" in {
    val sql = rewritten(
      go(
        "SELECT (SELECT max(c_id) FROM customer)",
        eff(tenantUser, List(policy("c_region = 'eu'")))
      )
    )
    sql should include("c_region = 'eu'")
  }

  it should "wrap a table inside a WHERE EXISTS subquery" in {
    val sql = rewritten(
      go(
        "SELECT * FROM orders o WHERE EXISTS (SELECT 1 FROM customer c WHERE c.c_id = o.c_id)",
        eff(tenantUser, List(policy("c_region = 'eu'")))
      )
    )
    sql should include("c_region = 'eu'")
  }

  it should "wrap a table inside a WHERE IN subquery" in {
    val sql = rewritten(
      go(
        "SELECT * FROM orders WHERE o_custkey IN (SELECT c_id FROM customer)",
        eff(tenantUser, List(policy("c_region = 'eu'")))
      )
    )
    sql should include("c_region = 'eu'")
  }

  it should "wrap a table inside a HAVING subquery" in {
    val sql = rewritten(
      go(
        "SELECT o_year, count(*) FROM orders GROUP BY o_year " +
          "HAVING count(*) > (SELECT max(c_id) FROM customer)",
        eff(tenantUser, List(policy("c_region = 'eu'")))
      )
    )
    sql should include("c_region = 'eu'")
  }

  // -------- system-schema metadata queries (FlightSQL GetDbSchemas and catalog browsing) --------
  //
  // The RLS rewriter has no schema resolver: it only jsqlparser-parses the statement and wraps
  // tables that match a row policy. Metadata queries over information_schema / pg_catalog parse
  // fine and match no user-table policy, so they must pass through untouched (never the
  // PassthroughParseFailed arm, which the router denies fail-closed). These tests pin that the
  // RLS path never needed the CLS system-schema fix.

  it should "pass the FlightSQL GetDbSchemas schemata query through for a principal with row policies" in {
    val sql =
      "SELECT catalog_name, schema_name AS db_schema_name FROM information_schema.schemata " +
        "WHERE schema_name NOT IN ('information_schema','pg_catalog') ORDER BY 1,2"
    go(sql, eff(tenantUser, List(policy("c_region = 'eu'")))) shouldBe Passthrough
  }

  it should "pass information_schema and pg_catalog browsing queries through untouched" in {
    val effSet = eff(tenantUser, List(policy("c_region = 'eu'")))
    go("SELECT table_name FROM information_schema.tables", effSet) shouldBe Passthrough
    go("SELECT * FROM pg_catalog.pg_tables", effSet) shouldBe Passthrough
  }

  it should "still wrap the user table when it appears alongside a system table" in {
    val sql = rewritten(
      go(
        "SELECT it.table_name, c.c_id FROM information_schema.tables it, tpch1.customer c",
        eff(tenantUser, List(policy("c_region = 'eu'")))
      )
    )
    sql should include("c_region = 'eu'")
    sql should include("information_schema.tables")
  }

  // ---- statements that read no physical table (issue #114, second report) -----------------

  "a statement reading only catalog functions" should "pass through with row policies in scope" in {
    val sync =
      "SELECT schema_name, sql, 'table' FROM duckdb_tables() " +
        "UNION ALL SELECT schema_name, view_name, 'view' FROM duckdb_views()"
    go(sync, eff(tenantUser, List(policy("c_region = 'EU'")))) shouldBe Passthrough
  }

  it should "wrap the table under a positional-reference scan (numbering survives the wrap)" in {
    // The quack client pushes scans down as `SELECT #1, #2 FROM customer`; the derived table the
    // wrap installs keeps the base table's column order, so `#n` keeps meaning the same column.
    val out =
      rewritten(go("SELECT #1, #2 FROM customer", eff(tenantUser, List(policy("c_region = 'EU'")))))
    out should include("c_region = 'eu'")
    out should include("#1, #2")
  }

  // ---- lexer-canonical parser input -------------------------------------------------------

  "a statement whose literals JSqlParser lexes differently from DuckDB" should
    "still have its policy table filtered" in {
      // DuckDB reads one escape-string literal, then the subquery; JSqlParser's own lexer read the
      // subquery as part of a string and left it unfiltered
      val out = rewritten(
        go(
          "SELECT E'\\'' AS a, (SELECT count(*) FROM customer) AS n --'",
          eff(tenantUser, List(policy("c_region = 'EU'")))
        )
      )
      out should include("c_region = 'eu'")
    }

  it should "ship a rewrite that keeps each literal's value" in {
    val out = rewritten(
      go(
        "SELECT E'it\\'s' AS a, $$x'y$$ AS b FROM customer",
        eff(tenantUser, List(policy("c_region = 'EU'")))
      )
    )
    out should include("'it''s' as a")
    out should include("'x''y' as b")
  }

  it should "refuse rather than ship a rewrite whose parser input is not the statement" in {
    // `//` reaches JSqlParser as `/`: a rewrite printed from that would divide differently
    go(
      "SELECT c_custkey // 2 FROM customer",
      eff(tenantUser, List(policy("c_region = 'EU'")))
    ) match
      case Refused(reason) => reason should include("//")
      case other           => fail(s"expected Refused, got $other")
    go("SELECT 7 // 2", eff(tenantUser, List(policy("c_region = 'EU'")))) shouldBe Passthrough
  }

  // ---- statements in table position -------------------------------------------------------

  "a parenthesized statement naming a policy table" should "be refused, not passed through" in {
    // JSqlParser reads `(TABLE customer)` as a table named TABLE aliased customer: no slot to wrap,
    // while DuckDB returns every row of customer
    val effSet = eff(tenantUser, List(policy("c_region = 'EU'")))
    go("SELECT * FROM (TABLE customer)", effSet) shouldBe a[Refused]
    go("SELECT * FROM (SUMMARIZE customer)", effSet) shouldBe a[Refused]
    go("SELECT * FROM orders JOIN (TABLE \"customer\") c ON true", effSet) shouldBe a[Refused]
  }

  it should "pass through when the named table carries no policy" in {
    go(
      "SELECT * FROM (TABLE orders)",
      eff(tenantUser, List(policy("c_region = 'EU'")))
    ) shouldBe Passthrough
  }

  "a TABLE statement passed to ARRAY() or ANY naming a policy table" should "be refused" in {
    // JSqlParser holds `ARRAY(TABLE customer)` as a function whose argument is a column: no slot
    // to wrap, while DuckDB reads every row of customer.
    val effSet = eff(tenantUser, List(policy("c_region = 'EU'")))
    go("SELECT ARRAY(TABLE customer)", effSet) shouldBe a[Refused]
    go("SELECT len(ARRAY(TABLE tpch1.customer))", effSet) shouldBe a[Refused]
    go("SELECT 1 WHERE 'x' = ANY (TABLE \"customer\")", effSet) shouldBe a[Refused]
    go("SELECT ARRAY(TABLE orders)", effSet) shouldBe Passthrough
  }

  // ---- expression positions outside JSqlParser's node types --------------------------------

  "a policy table read in a MAP literal or a json_object argument" should "be filtered" in {
    val effSet = eff(tenantUser, List(policy("c_region = 'EU'")))
    rewritten(go("SELECT map {'a': (SELECT count(*) FROM customer)}", effSet)) should include(
      "c_region = 'eu'"
    )
    rewritten(go("SELECT MAP {(SELECT max(c_id) FROM customer): 1}", effSet)) should include(
      "c_region = 'eu'"
    )
    rewritten(go("SELECT json_object('a', (SELECT count(*) FROM customer))", effSet)) should
      include("c_region = 'eu'")
  }

  // ---- audit F21: reads the walk cannot filter are refused, never passed through -----------

  private val frOnly = eff(tenantUser, List(policy("c_nation = 'FR'")))

  "a PIVOT or UNPIVOT source naming a policy table" should "be refused in every position" in
    // JSqlParser holds `PIVOT customer ...` as a PivotQuery with customer as a bare child: no
    // slot the walk wraps, while DuckDB pivots every row of customer.
    List(
      "SELECT * FROM (PIVOT customer ON c_nation USING count(*))",
      "FROM (PIVOT customer ON c_nation USING count(*))",
      "WITH p AS (PIVOT customer ON c_nation USING count(*)) SELECT * FROM p",
      "SELECT (SELECT count(*) FROM (PIVOT customer ON c_nation USING count(*)))",
      "SELECT * FROM customer c JOIN (PIVOT customer ON c_nation USING count(*)) p ON true",
      "SELECT 1 WHERE EXISTS (SELECT 1 FROM (PIVOT customer ON c_nation USING count(*)) p " +
        "WHERE p.c_email LIKE 'bob%')",
      "SELECT * FROM (PIVOT tpch1.customer ON c_nation USING count(*))"
    ).foreach { sql =>
      withClue(sql)(go(sql, frOnly) shouldBe a[Refused])
    }

  it should "leave UNPIVOT, which JSqlParser cannot parse, to the router's parse-failed denial" in {
    go(
      "SELECT * FROM (UNPIVOT customer ON c_email, c_phone INTO NAME k VALUE v)",
      frOnly
    ) shouldBe PassthroughParseFailed
  }

  it should "still filter a pivoted subquery, and pass a table with no policy" in {
    rewritten(
      go("SELECT * FROM (PIVOT (SELECT * FROM customer) ON c_nation USING count(*))", frOnly)
    ) should include("c_nation = 'fr'")
    go("SELECT * FROM (PIVOT orders ON o_status USING count(*))", frOnly) shouldBe Passthrough
  }

  "an EXPLAIN of a statement reading a policy table" should "be refused" in
    // DuckDB runs the statement under EXPLAIN ANALYZE, unfiltered, and every plan reports row
    // counts; the walk never entered an ExplainStatement.
    List(
      "EXPLAIN ANALYZE SELECT count(*) FROM customer",
      "EXPLAIN ANALYZE SELECT error(string_agg(c_email, ',')) FROM customer",
      "EXPLAIN SELECT * FROM customer",
      "EXPLAIN ANALYZE SELECT * FROM orders o WHERE EXISTS (SELECT 1 FROM customer)"
    ).foreach { sql =>
      withClue(sql)(go(sql, frOnly) shouldBe a[Refused])
    }

  it should "pass when the explained statement reads no policy table" in {
    go("EXPLAIN ANALYZE SELECT count(*) FROM orders", frOnly) shouldBe Passthrough
  }

  // ---- time travel --------------------------------------------------------------------------
  //
  // jsqlparser cannot parse DuckLake's `AT (VERSION => n)`, and takes `AT (TIMESTAMP => ...)` only
  // before an alias while DuckDB takes it only after one. The clause is stripped with the ACL
  // parser's scanner and put back on the same table reference: inside the filtered view, on the
  // base table, never on the derived table.

  private def raw(o: Outcome): String = o match
    case Rewritten(sql) => sql
    case other          => fail(s"expected Rewritten, got $other")

  private val regionEu = policy("c_region = 'eu'")

  "a time-travel read" should "keep AT (VERSION => n) on the base table inside the wrapper" in {
    raw(
      go("SELECT * FROM tpch1.customer AT (VERSION => 7)", eff(tenantUser, List(regionEu)))
    ) shouldBe
      "SELECT * FROM (SELECT * FROM tpch1.customer AT (VERSION => 7) WHERE (c_region = 'eu')) " +
      "AS customer"
  }

  it should "move the caller's alias to the wrapper and keep the clause on the base table" in {
    raw(
      go("SELECT c.c_id FROM tpch1.customer c AT (VERSION => 7)", eff(tenantUser, List(regionEu)))
    ) shouldBe
      "SELECT c.c_id FROM (SELECT * FROM tpch1.customer AT (VERSION => 7) WHERE " +
      "(c_region = 'eu')) c"
  }

  it should "keep each table's own clause when several tables carry one" in {
    val policies = List(regionEu, policy("o_year = 2024", table = "orders", id = "rp-2"))
    val sql      = raw(
      go(
        "SELECT * FROM customer AS c AT (VERSION => 7) JOIN orders o AT (VERSION => 9) " +
          "ON c.c_id = o.c_id",
        eff(tenantUser, policies)
      )
    )
    sql should include("FROM customer AT (VERSION => 7) WHERE (c_region = 'eu')) AS c")
    sql should include("FROM orders AT (VERSION => 9) WHERE (o_year = 2024)) o")
  }

  it should "leave the clause after the alias on a table no policy covers" in {
    val sql = raw(
      go(
        "SELECT * FROM customer c AT (VERSION => 7), orders o AT (VERSION => 9)",
        eff(tenantUser, List(regionEu))
      )
    )
    sql should include("FROM customer AT (VERSION => 7) WHERE (c_region = 'eu')) c")
    sql should include("orders o AT (VERSION => 9)")
  }

  it should "keep the caller's spelling of the clause, case and spacing included" in {
    raw(go("SELECT * FROM customer x at(version=>7)", eff(tenantUser, List(regionEu)))) should
      include("FROM customer at(version=>7) WHERE (c_region = 'eu')) x")
  }

  it should "keep an AT (TIMESTAMP => expr) clause the same way" in {
    val clause = "AT (TIMESTAMP => TIMESTAMP '2026-01-01 10:00:00')"
    raw(go(s"SELECT * FROM customer c $clause", eff(tenantUser, List(regionEu)))) should
      include(s"FROM customer $clause WHERE (c_region = 'eu')) c")
  }

  it should "keep the clause on a table inside a subquery and a CTE" in {
    val sql = raw(
      go(
        "WITH k AS (SELECT c_id FROM customer AT (VERSION => 3)) SELECT * FROM k " +
          "WHERE c_id IN (SELECT c_id FROM customer z AT (VERSION => 4))",
        eff(tenantUser, List(regionEu))
      )
    )
    sql should include("FROM customer AT (VERSION => 3) WHERE (c_region = 'eu')) AS customer")
    sql should include("FROM customer AT (VERSION => 4) WHERE (c_region = 'eu')) z")
  }

  it should "pass through, clause intact in the caller's text, when no policy covers the table" in {
    go("SELECT * FROM orders AT (VERSION => 9)", eff(tenantUser, List(regionEu))) shouldBe
      Passthrough
  }

  it should "fail closed when the clause follows a derived table instead of a table" in {
    go(
      "SELECT * FROM (SELECT * FROM customer) d AT (VERSION => 7)",
      eff(tenantUser, List(regionEu))
    ) shouldBe PassthroughParseFailed
  }

  it should "fail closed when the caller's text carries the carrier's reserved marker" in {
    go(
      "SELECT '__qod_time_travel_0__' FROM customer AT (VERSION => 7)",
      eff(tenantUser, List(regionEu))
    ) shouldBe PassthroughParseFailed
  }
