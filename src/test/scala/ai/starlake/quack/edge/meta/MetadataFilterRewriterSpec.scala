package ai.starlake.quack.edge.meta

import ai.starlake.quack.edge.cls.SchemaContext
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RolePermission}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MetadataFilterRewriterSpec extends AnyFlatSpec with Matchers:

  import MetadataFilterOutcome._

  private val rw  = new MetadataFilterRewriter(enabled = true)
  private val ctx =
    SchemaContext(defaultDatabase = Some("acme_tpch"), defaultSchema = Some("tpch1"))

  private val tenantUser =
    RbacUser(id = "u-1", tenant = Some("acme"), username = "alice", role = "user")
  private val superuser =
    RbacUser(id = "u-0", tenant = None, username = "root", role = "admin")

  private def grant(cat: String, sch: String, tab: String, verb: String = "RO") =
    RolePermission("rp-x", "r-1", cat, sch, tab, verb)

  private def eff(user: RbacUser, perms: RolePermission*) =
    EffectiveSet(user, Nil, Nil, perms.toList, Nil)

  /** Run the rewrite and echo, at info level, the original SQL, the grants in scope and the
    * outcome, so the test report shows the before/after of every case.
    */
  private def go(
      sql: String,
      effSet: EffectiveSet,
      rewriter: MetadataFilterRewriter = rw
  ): MetadataFilterOutcome =
    val outcome = rewriter.rewrite(sql, effSet, ctx)
    val grants  =
      effSet.permissions.map(p => s"${p.catalogName}.${p.schemaName}.${p.tableName}:${p.verb}")
    val result = outcome match
      case Rewritten(s)   => s
      case Passthrough    => s"$sql  [passthrough]"
      case Denied(reason) => s"[denied] $reason"
    info(s"original: $sql")
    info(s"grants:   ${if grants.isEmpty then "(none)" else grants.mkString("  ;  ")}")
    info(s"result:   $result")
    outcome

  "rewrite" should "wrap information_schema.tables with a single-table grant predicate" in {
    go(
      "SELECT table_name FROM information_schema.tables",
      eff(tenantUser, grant("acme_tpch", "tpch1", "customer"))
    ) match
      case Rewritten(sql) =>
        sql should include("information_schema.tables")
        sql should include("table_schema = 'tpch1' AND table_name = 'customer'")
        sql should include("table_schema IN ('information_schema', 'pg_catalog')")
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "OR multiple grants and honor wildcards" in {
    go(
      "SELECT * FROM information_schema.columns",
      eff(
        tenantUser,
        grant("acme_tpch", "tpch1", "customer"),
        grant("*", "sales", "*"),
        grant("acme_tpch", "*", "orders", verb = "RW")
      )
    ) match
      case Rewritten(sql) =>
        sql should include("table_schema = 'tpch1' AND table_name = 'customer'")
        sql should include("table_schema = 'sales'")
        sql should include("table_name = 'orders'")
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "ignore grants on other catalogs and non-Read verbs" in {
    go(
      "SELECT * FROM information_schema.tables",
      eff(
        tenantUser,
        grant("other_db", "tpch1", "customer"),
        grant("acme_tpch", "tpch1", "orders", verb = "DDL")
      )
    ) match
      case Rewritten(sql) =>
        (sql should not).include("customer")
        (sql should not).include("orders")
        sql should include("table_schema IN ('information_schema', 'pg_catalog')")
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "filter schemata on schema_name and make a table grant expose its schema" in {
    go(
      "SELECT schema_name FROM information_schema.schemata",
      eff(tenantUser, grant("acme_tpch", "tpch1", "customer"))
    ) match
      case Rewritten(sql) =>
        sql should include("schema_name = 'tpch1'")
        sql should include("schema_name IN ('information_schema', 'pg_catalog')")
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "produce the system-only predicate for a zero-grant principal" in {
    go("SELECT * FROM information_schema.tables", eff(tenantUser)) match
      case Rewritten(sql) =>
        sql should include("table_schema IN ('information_schema', 'pg_catalog')")
        (sql should not).include(" OR ")
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "preserve the alias of the wrapped reference" in {
    go(
      "SELECT t.table_name FROM information_schema.tables t",
      eff(tenantUser, grant("acme_tpch", "tpch1", "customer"))
    ) match
      case Rewritten(sql) =>
        sql.toLowerCase should include(") t")
        sql should include("t.table_name")
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "wrap a reference nested inside a subquery" in {
    go(
      "SELECT 1 WHERE EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'x')",
      eff(tenantUser, grant("acme_tpch", "tpch1", "customer"))
    ) match
      case Rewritten(sql) =>
        sql should include("table_schema = 'tpch1' AND table_name = 'customer'")
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "wrap a reference nested inside a CTE body" in {
    go(
      "WITH t AS (SELECT table_name FROM information_schema.tables) SELECT * FROM t",
      eff(tenantUser, grant("acme_tpch", "tpch1", "customer"))
    ) match
      case Rewritten(sql) =>
        sql should include("table_schema = 'tpch1' AND table_name = 'customer'")
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "wrap a reference on the right-hand side of a join" in {
    go(
      "SELECT * FROM tpch1.customer c JOIN information_schema.columns ic ON TRUE",
      eff(tenantUser, grant("acme_tpch", "tpch1", "customer"))
    ) match
      case Rewritten(sql) =>
        sql should include("table_schema = 'tpch1' AND table_name = 'customer'")
        sql should include("information_schema.columns")
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "wrap every arm of a set operation" in {
    go(
      "SELECT table_name FROM information_schema.tables " +
        "UNION ALL SELECT table_name FROM information_schema.views",
      eff(tenantUser, grant("acme_tpch", "tpch1", "customer"))
    ) match
      case Rewritten(sql) =>
        sql should include("information_schema.tables")
        sql should include("information_schema.views")
        java.util.regex.Pattern
          .quote("table_name = 'customer'")
          .r
          .findAllMatchIn(sql)
          .size shouldBe 2
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "wrap both legs of a self-join on the same filterable table" in {
    go(
      "SELECT * FROM information_schema.tables a, information_schema.tables b",
      eff(tenantUser, grant("acme_tpch", "tpch1", "customer"))
    ) match
      case Rewritten(sql) =>
        java.util.regex.Pattern
          .quote("table_name = 'customer'")
          .r
          .findAllMatchIn(sql)
          .size shouldBe 2
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "deny a filterable reference the substitution walker cannot reach (fail-closed)" in {
    go(
      "SELECT coalesce((SELECT count(*) FROM information_schema.tables), 0)",
      eff(tenantUser, grant("acme_tpch", "tpch1", "customer"))
    ) match
      case Denied(_) => succeed
      case other     => fail(s"expected Denied, got $other")
  }

  // The cross-check counts filterable table OCCURRENCES, not the de-duplicated names
  // jsqlparser's finder reports: with names, one substituted FROM item would mask every other
  // unreachable reference to the SAME table, which is a full enumeration bypass.

  it should "deny an unreachable reference masked by a substituted one (function argument)" in {
    go(
      "SELECT coalesce((SELECT string_agg(table_name, ',') FROM information_schema.tables), 'x') " +
        "FROM information_schema.tables",
      eff(tenantUser, grant("acme_tpch", "tpch1", "customer"))
    ) match
      case Denied(_) => succeed
      case other     => fail(s"expected Denied, got $other")
  }

  it should "deny an unreachable reference masked by a substituted one (CASE existence oracle)" in {
    go(
      "SELECT CASE WHEN EXISTS (SELECT 1 FROM information_schema.tables " +
        "WHERE table_name = 'secret') THEN 1 ELSE 0 END FROM information_schema.tables",
      eff(tenantUser, grant("acme_tpch", "tpch1", "customer"))
    ) match
      case Denied(_) => succeed
      case other     => fail(s"expected Denied, got $other")
  }

  // Neither the substitution walk nor jsqlparser's traversal descends ORDER BY, GROUP BY or
  // window PARTITION BY subqueries, so a reference hiding there is invisible to both. The
  // textual tripwire is what denies these: it does not depend on knowing the traversal's gaps.

  it should "deny a sole reference hidden in an ORDER BY subquery" in {
    go(
      "SELECT 1 FROM tpch1.customer ORDER BY (SELECT count(*) FROM information_schema.tables)",
      eff(tenantUser, grant("acme_tpch", "tpch1", "customer"))
    ) match
      case Denied(_) => succeed
      case other     => fail(s"expected Denied, got $other")
  }

  it should "deny a sole reference hidden in a GROUP BY existence oracle" in {
    go(
      "SELECT count(*) FROM tpch1.customer GROUP BY CASE WHEN EXISTS (SELECT 1 FROM " +
        "information_schema.columns WHERE table_name = 'salaries' AND column_name = 'ssn') " +
        "THEN 1 ELSE 0 END",
      eff(tenantUser, grant("acme_tpch", "tpch1", "customer"))
    ) match
      case Denied(_) => succeed
      case other     => fail(s"expected Denied, got $other")
  }

  it should "deny a sole reference hidden in a window PARTITION BY subquery" in {
    go(
      "SELECT count(*) OVER (PARTITION BY (SELECT count(*) FROM information_schema.tables)) " +
        "FROM tpch1.customer",
      eff(tenantUser, grant("acme_tpch", "tpch1", "customer"))
    ) match
      case Denied(_) => succeed
      case other     => fail(s"expected Denied, got $other")
  }

  it should "deny an ORDER BY reference masked by a substituted FROM item" in {
    go(
      "SELECT table_name FROM information_schema.tables " +
        "ORDER BY (SELECT count(*) FROM information_schema.tables)",
      eff(tenantUser, grant("acme_tpch", "tpch1", "customer"))
    ) match
      case Denied(_) => succeed
      case other     => fail(s"expected Denied, got $other")
  }

  it should "deny an ORDER BY reference behind an apostrophe in a quoted identifier" in {
    go(
      """SELECT "a'b" FROM tpch1.customer """ +
        "ORDER BY (SELECT count(*) FROM information_schema.tables WHERE table_schema = 'z')",
      eff(tenantUser, grant("acme_tpch", "tpch1", "customer"))
    ) match
      case Denied(_) => succeed
      case other     => fail(s"expected Denied, got $other")
  }

  it should "deny a GROUP BY oracle behind an apostrophe in a quoted identifier" in {
    go(
      """SELECT "a'b" FROM tpch1.customer GROUP BY CASE WHEN EXISTS (SELECT 1 FROM """ +
        "information_schema.columns WHERE table_name = 'salaries') THEN 1 ELSE 0 END",
      eff(tenantUser, grant("acme_tpch", "tpch1", "customer"))
    ) match
      case Denied(_) => succeed
      case other     => fail(s"expected Denied, got $other")
  }

  it should "pass a cross-catalog filterable reference through (the validator gates it)" in {
    go("SELECT * FROM other_db.information_schema.tables", eff(tenantUser)) shouldBe Passthrough
  }

  // The tripwire counts the RAW statement, so a phrase inside a literal or a comment is a phantom
  // match and denies. That over-denial is the accepted price of never under-counting: recognising
  // literals means reimplementing the engine's lexer, and a lexer that desynchronises swallows the
  // real reference instead. These two pin the surface so a future change cannot widen it silently.

  it should "deny the phrase inside a string literal (accepted over-denial)" in {
    go(
      "SELECT * FROM tpch1.customer WHERE note = 'see information_schema.tables'",
      eff(tenantUser)
    ) should matchPattern { case Denied(_) => }
  }

  it should "deny the phrase inside comments (accepted over-denial)" in {
    go("-- see information_schema.tables\nSELECT * FROM tpch1.customer", eff(tenantUser)) should
      matchPattern { case Denied(_) => }
    go("/* see information_schema.views */ SELECT * FROM tpch1.customer", eff(tenantUser)) should
      matchPattern { case Denied(_) => }
  }

  it should "escape quotes in grant values" in {
    go(
      "SELECT * FROM information_schema.tables",
      eff(tenantUser, grant("acme_tpch", "tp'ch", "cust'omer"))
    ) match
      case Rewritten(sql) =>
        sql should include("'tp''ch'")
        sql should include("'cust''omer'")
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "pass through superusers, explicit information_schema grants, wildcard-ALL, and disabled" in {
    val stmt = "SELECT * FROM information_schema.tables"
    go(stmt, eff(superuser)) shouldBe Passthrough
    go(stmt, eff(tenantUser, grant("acme_tpch", "information_schema", "*"))) shouldBe Passthrough
    go(stmt, eff(tenantUser, grant("*", "*", "*", verb = "ALL"))) shouldBe Passthrough
    go(stmt, eff(tenantUser), rewriter = new MetadataFilterRewriter(enabled = false)) shouldBe
      Passthrough
  }

  it should "pass through statements touching no filterable table" in {
    go("SELECT * FROM tpch1.customer", eff(tenantUser)) shouldBe Passthrough
    go("SELECT * FROM pg_catalog.pg_tables", eff(tenantUser)) shouldBe Passthrough
    go("SELECT * FROM information_schema.key_column_usage", eff(tenantUser)) shouldBe Passthrough
  }

  it should "replace SHOW TABLES with the filtered current-schema listing" in {
    go("SHOW TABLES", eff(tenantUser, grant("acme_tpch", "tpch1", "customer"))) match
      case Rewritten(sql) =>
        sql should include("table_name AS name")
        sql should include("table_schema = 'tpch1'")
        sql should include("table_name = 'customer'")
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "deny SHOW TABLES variants it cannot rewrite (fail-closed)" in {
    for stmt <- List("SHOW TABLES FROM tpch1", "SHOW TABLES IN tpch1", "SHOW TABLES LIKE 'c%'") do
      go(stmt, eff(tenantUser, grant("acme_tpch", "tpch1", "customer"))) match
        case Denied(_) => succeed
        case other     => fail(s"$stmt: expected Denied, got $other")
  }

  it should "replace a comment-prefixed SHOW TABLES that dodges the textual fast path" in {
    go("/* c */ SHOW TABLES", eff(tenantUser, grant("acme_tpch", "tpch1", "customer"))) match
      case Rewritten(sql) =>
        sql should include("table_name AS name")
        sql should include("table_schema = 'tpch1'")
        sql should include("table_name = 'customer'")
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "deny a comment-prefixed SHOW TABLES variant" in {
    go(
      "/* c */ SHOW TABLES FROM tpch1",
      eff(tenantUser, grant("acme_tpch", "tpch1", "customer"))
    ) match
      case Denied(_) => succeed
      case other     => fail(s"expected Denied, got $other")
  }

  it should "leave unparseable non-SHOW statements untouched" in {
    go("THIS IS NOT SQL", eff(tenantUser)) shouldBe Passthrough
  }
