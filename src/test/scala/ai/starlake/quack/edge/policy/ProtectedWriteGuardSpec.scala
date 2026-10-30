package ai.starlake.quack.edge.policy

import ai.starlake.quack.edge.cls.{ColumnCatalog, SchemaContext}
import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.{RbacUser, RoleColumnPolicy, RoleRowPolicy}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ProtectedWriteGuardSpec extends AnyFlatSpec with Matchers:

  import GuardOutcome._

  private val tenantUser =
    RbacUser(id = "u-1", tenant = Some("acme"), username = "alice", kind = "user")
  private val superuser =
    RbacUser(id = "u-0", tenant = None, username = "root", kind = "admin")

  // customer has c_id (unmasked) and c_email (masked)
  private val maskEmail =
    RoleColumnPolicy("cp-1", "r-1", "*", "tpch1", "customer", "c_email", "mask", Some("'***'"))
  private val rowPolicy =
    RoleRowPolicy("rp-1", "r-1", "*", "tpch1", "customer", "c_region = 'X'")

  private def eff(
      user: RbacUser,
      cols: List[RoleColumnPolicy] = Nil,
      rows: List[RoleRowPolicy] = Nil
  ): EffectiveSet =
    EffectiveSet(user, Nil, Nil, Nil, Nil, cols, rows)

  private val ctx =
    SchemaContext(
      defaultDatabase = Some("acme_tpch"),
      defaultSchema = Some("tpch1"),
      attachedCatalogs = Set.empty
    )

  // A catalog that knows customer's columns, so SELECT * expands and masks c_email.
  private def guardWithCls(clsOn: Boolean = true, rlsOn: Boolean = true): ProtectedWriteGuard =
    val cat = new ColumnCatalog.MapCatalog(
      Map(("acme_tpch", "tpch1", "customer") -> List("c_id", "c_email", "c_region"))
    )
    new ProtectedWriteGuard(cat, clsEnabled = clsOn, rlsEnabled = rlsOn)

  // A guard whose catalog does NOT know customer, so the Deny-mode oracle cannot resolve it.
  private def guardEmptyCatalog: ProtectedWriteGuard =
    new ProtectedWriteGuard(
      new ColumnCatalog.MapCatalog(Map.empty),
      clsEnabled = true,
      rlsEnabled = true
    )

  "check" should "allow a write reading only unmasked columns of a CLS table" in {
    guardWithCls().check(
      "INSERT INTO tpch1.scratch SELECT c_id FROM tpch1.customer",
      StatementKind.Dml,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe Allow
  }

  it should "deny a write projecting a masked column" in {
    guardWithCls().check(
      "CREATE TABLE tpch1.leak AS SELECT c_email FROM tpch1.customer",
      StatementKind.Ddl,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe a[Deny]
  }

  it should "deny a masked column used only in a WHERE predicate" in {
    guardWithCls().check(
      "INSERT INTO tpch1.scratch SELECT c_id FROM tpch1.customer WHERE c_email = 'x@y.z'",
      StatementKind.Dml,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe a[Deny]
  }

  it should "deny SELECT * from a masked table (star expands to include c_email)" in {
    guardWithCls().check(
      "CREATE TABLE tpch1.leak AS SELECT * FROM tpch1.customer",
      StatementKind.Ddl,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe a[Deny]
  }

  it should "deny CREATE VIEW that reads a masked column" in {
    guardWithCls().check(
      "CREATE VIEW tpch1.v AS SELECT c_email FROM tpch1.customer",
      StatementKind.Ddl,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe a[Deny]
  }

  it should "deny ANY read of an RLS-protected table, even unmasked columns" in {
    guardWithCls().check(
      "INSERT INTO tpch1.scratch SELECT c_id FROM tpch1.customer",
      StatementKind.Dml,
      eff(tenantUser, rows = List(rowPolicy)),
      ctx
    ) shouldBe a[Deny]
  }

  it should "deny an UPDATE reading a masked table (fail-closed, no single inner SELECT)" in {
    guardWithCls().check(
      "UPDATE tpch1.audit SET e = (SELECT c_email FROM tpch1.customer LIMIT 1)",
      StatementKind.Dml,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe a[Deny]
  }

  it should "deny a MERGE whose THEN DELETE predicate reads a masked table" in {
    guardWithCls().check(
      "MERGE INTO tpch1.scratch USING (SELECT 1 AS n) s ON scratch.k = s.n " +
        "WHEN MATCHED AND (SELECT ascii(substr(c_email, 1, 1)) FROM tpch1.customer LIMIT 1) = 97 " +
        "THEN DELETE",
      StatementKind.Dml,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe a[Deny]
  }

  it should "deny a MERGE whose NOT MATCHED BY SOURCE THEN DELETE predicate reads an RLS table" in {
    guardWithCls().check(
      "MERGE INTO tpch1.scratch USING (SELECT 1 AS n) s ON scratch.k = s.n " +
        "WHEN NOT MATCHED BY SOURCE AND EXISTS (SELECT 1 FROM tpch1.customer WHERE c_id = 7) " +
        "THEN DELETE",
      StatementKind.Dml,
      eff(tenantUser, rows = List(rowPolicy)),
      ctx
    ) shouldBe a[Deny]
  }

  it should "allow a write reading a table with no policy" in {
    guardWithCls().check(
      "INSERT INTO tpch1.scratch SELECT o_id FROM tpch1.orders",
      StatementKind.Dml,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe Allow
  }

  it should "allow bare writes that read no table" in {
    val g = guardWithCls()
    g.check(
      "INSERT INTO tpch1.audit VALUES (1)",
      StatementKind.Dml,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe Allow
    g.check(
      "CREATE TABLE tpch1.x (y INT)",
      StatementKind.Ddl,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe Allow
  }

  it should "pass through SELECT (the rewriters handle it)" in {
    guardWithCls().check(
      "SELECT c_email FROM tpch1.customer",
      StatementKind.Select,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe Allow
  }

  it should "pass through superusers and no-policy principals" in {
    guardWithCls().check(
      "CREATE TABLE tpch1.leak AS SELECT c_email FROM tpch1.customer",
      StatementKind.Ddl,
      eff(superuser, cols = List(maskEmail)),
      ctx
    ) shouldBe Allow
    guardWithCls().check(
      "CREATE TABLE tpch1.leak AS SELECT c_email FROM tpch1.customer",
      StatementKind.Ddl,
      eff(tenantUser),
      ctx
    ) shouldBe Allow
  }

  it should "not consult column policies when CLS is disabled, nor row policies when RLS is disabled" in {
    // CLS off: the mask is not enforced, so the write is allowed.
    guardWithCls(clsOn = false).check(
      "CREATE TABLE tpch1.leak AS SELECT c_email FROM tpch1.customer",
      StatementKind.Ddl,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe Allow
    // RLS off: the row policy is not enforced.
    guardWithCls(rlsOn = false).check(
      "INSERT INTO tpch1.scratch SELECT c_id FROM tpch1.customer",
      StatementKind.Dml,
      eff(tenantUser, rows = List(rowPolicy)),
      ctx
    ) shouldBe Allow
  }

  it should "deny an unparseable write that a policy-holder submits (fail-closed)" in {
    guardWithCls().check(
      "INSERT INTO tpch1.scratch SELECT c_email FROM tpch1.customer WHERE ((",
      StatementKind.Dml,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe a[Deny]
  }

  it should "deny a write reading a CLS table the catalog cannot resolve (Deny-mode oracle)" in {
    guardEmptyCatalog.check(
      "CREATE TABLE tpch1.leak AS SELECT c_email FROM tpch1.customer",
      StatementKind.Ddl,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe a[Deny]
  }

  it should "deny a batch that launders a masked read in a sibling statement" in {
    guardWithCls().check(
      "INSERT INTO tpch1.x SELECT o_id FROM tpch1.orders; " +
        "CREATE TABLE tpch1.leak AS SELECT c_email FROM tpch1.customer",
      StatementKind.Ddl,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe a[Deny]
  }

  it should "allow a benign multi-statement batch that reads only unmasked columns" in {
    guardWithCls().check(
      "INSERT INTO tpch1.x SELECT o_id FROM tpch1.orders; " +
        "INSERT INTO tpch1.y SELECT c_id FROM tpch1.customer",
      StatementKind.Dml,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe Allow
  }

  it should "deny an INSERT VALUES that embeds a subquery reading a masked column" in {
    guardWithCls().check(
      "INSERT INTO tpch1.audit VALUES ((SELECT c_email FROM tpch1.customer LIMIT 1))",
      StatementKind.Dml,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe a[Deny]
  }

  it should "deny an INSERT VALUES that embeds a subquery reading an RLS table" in {
    guardWithCls().check(
      "INSERT INTO tpch1.audit VALUES ((SELECT c_id FROM tpch1.customer LIMIT 1))",
      StatementKind.Dml,
      eff(tenantUser, rows = List(rowPolicy)),
      ctx
    ) shouldBe a[Deny]
  }

  it should "deny a write reading a masked table through a VALUES-derived table" in {
    guardWithCls().check(
      "INSERT INTO tpch1.t SELECT * FROM (VALUES ((SELECT c_email FROM tpch1.customer))) v",
      StatementKind.Dml,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe a[Deny]
  }

  it should "deny a write reading an RLS table through a VALUES-derived table" in {
    guardWithCls().check(
      "INSERT INTO tpch1.t SELECT * FROM (VALUES ((SELECT c_id FROM tpch1.customer))) v",
      StatementKind.Dml,
      eff(tenantUser, rows = List(rowPolicy)),
      ctx
    ) shouldBe a[Deny]
  }

  // Subqueries hidden in window / aggregate clauses (arm-preempted / raw-list children).
  private val windowAggForms = List(
    "OVER (ORDER BY (SELECT %s FROM tpch1.customer))"         -> "over order by",
    "OVER (PARTITION BY (SELECT %s FROM tpch1.customer))"     -> "over partition by",
    "FILTER (WHERE b IN (SELECT %s FROM tpch1.customer))"     -> "aggregate filter",
    "WITHIN GROUP (ORDER BY (SELECT %s FROM tpch1.customer))" -> "within group order by"
  )

  windowAggForms.foreach { case (clause, label) =>
    it should s"deny an RLS read hidden in a $label clause" in {
      val sql = s"INSERT INTO tpch1.t SELECT rank() ${clause.format("c_id")} FROM tpch1.s"
      guardWithCls().check(
        sql,
        StatementKind.Dml,
        eff(tenantUser, rows = List(rowPolicy)),
        ctx
      ) shouldBe a[Deny]
    }
  }

  it should "deny a masked read hidden in a window PARTITION BY clause (colHit fires)" in {
    guardWithCls().check(
      "INSERT INTO tpch1.t SELECT rank() OVER (PARTITION BY (SELECT c_email FROM tpch1.customer)) FROM tpch1.s",
      StatementKind.Dml,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe a[Deny]
  }

  // Subqueries in SELECT-level clauses the hand-walk omitted (reached by the symmetric descend).
  private val selectClauseForms = List(
    "SELECT %s FROM tpch1.s GROUP BY (SELECT c_id FROM tpch1.customer)"      -> "group by",
    "SELECT DISTINCT ON ((SELECT c_id FROM tpch1.customer)) %s FROM tpch1.s" -> "distinct on",
    "SELECT %s FROM tpch1.s QUALIFY row_number() OVER (ORDER BY (SELECT c_id FROM tpch1.customer)) = 1" -> "qualify",
    "SELECT %s FROM tpch1.s WINDOW w AS (ORDER BY (SELECT c_id FROM tpch1.customer))" -> "named window",
    "SELECT %s FROM tpch1.s GROUP BY GROUPING SETS ((a), ((SELECT c_id FROM tpch1.customer)))" -> "grouping sets"
  )

  selectClauseForms.foreach { case (body, label) =>
    it should s"deny an RLS read hidden in a $label clause" in {
      val sql = s"INSERT INTO tpch1.t ${body.format("a")}"
      guardWithCls().check(
        sql,
        StatementKind.Dml,
        eff(tenantUser, rows = List(rowPolicy)),
        ctx
      ) shouldBe a[Deny]
    }
  }

  it should "deny a masked read hidden in a GROUP BY clause (colHit fires)" in {
    guardWithCls().check(
      "INSERT INTO tpch1.t SELECT a FROM tpch1.s GROUP BY (SELECT c_email FROM tpch1.customer)",
      StatementKind.Dml,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe a[Deny]
  }

  // ---- catalog-reaching table functions on the READ path ------------------
  //
  // query() / query_table() name their target in a string, so the RLS / CLS rewriters see a
  // table function, not a base table, and forward the read unfiltered and unmasked.

  "check on a SELECT" should "deny query_table / query for a principal with a row policy" in
    List(
      "SELECT * FROM query_table('tpch1.customer')",
      "SELECT * FROM query('SELECT * FROM tpch1.customer')",
      "SELECT c_id FROM tpch1.orders WHERE c_id IN (SELECT c_id FROM query_table('tpch1.customer'))"
    ).foreach { sql =>
      withClue(sql) {
        guardWithCls().check(
          sql,
          StatementKind.Select,
          eff(tenantUser, rows = List(rowPolicy)),
          ctx
        ) shouldBe a[Deny]
      }
    }

  it should "deny query_table for a principal with a column policy" in {
    guardWithCls().check(
      "SELECT c_email FROM query_table('tpch1.customer')",
      StatementKind.Select,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe a[Deny]
  }

  it should "deny a backtick-quoted query_table for a principal with a row policy" in {
    // DuckDB 2.0 accepts backtick-quoted function names; this reads the table on the 2.0 CLI.
    guardWithCls().check(
      "SELECT * FROM `query_table`('tpch1.customer')",
      StatementKind.Select,
      eff(tenantUser, rows = List(rowPolicy)),
      ctx
    ) shouldBe a[Deny]
  }

  it should "leave principals without policies, and superusers, alone" in {
    val sql = "SELECT * FROM query_table('tpch1.customer')"
    guardWithCls().check(sql, StatementKind.Select, eff(tenantUser), ctx) shouldBe Allow
    guardWithCls().check(
      sql,
      StatementKind.Select,
      eff(superuser, rows = List(rowPolicy)),
      ctx
    ) shouldBe Allow
  }

  it should "deny a quoted name holding a dot, which the rewriters would match as a.b" in
    // A row policy on main."a.b" is keyed on that one name; the rewriter sees schema a, table b.
    List("SELECT * FROM \"a.b\"", "DELETE FROM \"a.b\"").foreach { sql =>
      withClue(sql) {
        guardWithCls().check(
          sql,
          StatementKind.Select,
          eff(tenantUser, rows = List(rowPolicy)),
          ctx
        ) match
          case Deny(reason) => reason should include("quoted identifier containing a dot")
          case other        => fail(s"expected Deny, got $other")
      }
    }

  it should "leave an ordinary SELECT to the rewriters" in {
    guardWithCls().check(
      "SELECT * FROM tpch1.customer",
      StatementKind.Select,
      eff(tenantUser, rows = List(rowPolicy)),
      ctx
    ) shouldBe Allow
  }

  // The rewriters qualify every bare name against the pool's default schema; a statement batched
  // after a USE / SET search_path resolves elsewhere, past the policy keyed on the default one.
  it should "deny a batch that moves the schema or search path to a principal with policies" in
    List(
      "SET search_path = 'acme_tpch.other'; SELECT * FROM customer",
      "USE acme_tpch.other; SELECT * FROM customer",
      "SET schema = 'other'; DELETE FROM customer"
    ).foreach { sql =>
      withClue(sql) {
        guardWithCls().check(
          sql,
          StatementKind.Other,
          eff(tenantUser, rows = List(rowPolicy)),
          ctx
        ) match
          case Deny(reason) => reason should include("own statement")
          case other        => fail(s"expected Deny, got $other")
      }
    }

  it should "leave a lone SET search_path, and a batch of a principal without policies, alone" in {
    guardWithCls().check(
      "SET search_path = 'acme_tpch.other'",
      StatementKind.Other,
      eff(tenantUser, rows = List(rowPolicy)),
      ctx
    ) match
      case Deny(reason) => reason should not include "own statement"
      case Allow        => succeed
    guardWithCls().check(
      "SET search_path = 'acme_tpch.other'; SELECT * FROM customer",
      StatementKind.Other,
      eff(tenantUser),
      ctx
    ) shouldBe Allow
  }

  it should "deny a SQL-valued setting to a principal with policies" in
    List(
      "SET pg_staleness_query = 'SELECT 1'",
      "RESET pg_pool_health_check_query",
      "EXPLAIN ANALYZE SET quack_authorization_function = 'f'"
    ).foreach { sql =>
      withClue(sql) {
        guardWithCls().check(
          sql,
          StatementKind.Other,
          eff(tenantUser, cols = List(maskEmail)),
          ctx
        ) match
          case Deny(reason) => reason should include("runs SQL")
          case other        => fail(s"expected Deny, got $other")
      }
    }

  it should "not take a session setting for a SQL-valued one" in {
    guardWithCls().check(
      "SET SESSION TimeZone = 'UTC'",
      StatementKind.Other,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) match
      case Deny(reason) => reason should not include "runs SQL"
      case Allow        => succeed
  }

  // What a DuckDB 2.0 quack client sends when it uses an attached catalog.

  it should "allow the quack client's BEGIN TRANSACTION for a principal with policies" in {
    guardWithCls().check(
      "BEGIN TRANSACTION",
      StatementKind.Begin,
      eff(tenantUser, cols = List(maskEmail), rows = List(rowPolicy)),
      ctx
    ) shouldBe Allow
  }

  private val ClientFedInsert =
    "INSERT INTO tpch1.customer SELECT * FROM scan_data_from_quack_client(" +
      "'0f8e2c3a-1b2c-4d5e-8f90-0123456789ab', NULL::STRUCT(col0 BIGINT), ordered := true)"

  it should "allow a client-fed INSERT from the native front door for a principal with policies" in {
    // the rows come from the client's own SEND_DATA stream: no protected table is read
    guardWithCls().check(
      ClientFedInsert,
      StatementKind.Dml,
      eff(tenantUser, cols = List(maskEmail), rows = List(rowPolicy)),
      ctx,
      clientDataScans = true
    ) shouldBe Allow
  }

  it should "deny the same client-fed INSERT when it did not arrive through the native front door" in {
    // any other edge cannot feed the scan, so it stays an incomplete extraction there
    guardWithCls().check(
      ClientFedInsert,
      StatementKind.Dml,
      eff(tenantUser, cols = List(maskEmail), rows = List(rowPolicy)),
      ctx
    ) shouldBe a[Deny]
  }

  it should "still deny a client-fed INSERT that also reads a row-policy table" in {
    guardWithCls().check(
      "INSERT INTO tpch1.scratch SELECT * FROM scan_data_from_quack_client(" +
        "'0f8e2c3a-1b2c-4d5e-8f90-0123456789ab', NULL::STRUCT(col0 BIGINT), ordered := true) " +
        "WHERE col0 IN (SELECT c_id FROM tpch1.customer)",
      StatementKind.Dml,
      eff(tenantUser, rows = List(rowPolicy)),
      ctx,
      clientDataScans = true
    ) shouldBe a[Deny]
  }

  it should "deny a malformed client data scan for a principal with policies" in {
    guardWithCls().check(
      "INSERT INTO tpch1.scratch SELECT * FROM scan_data_from_quack_client('x', " +
        "NULL::STRUCT(col0 BIGINT), ordered := true)",
      StatementKind.Dml,
      eff(tenantUser, rows = List(rowPolicy)),
      ctx,
      clientDataScans = true
    ) shouldBe a[Deny]
  }

  // SUMMARIZE returns per-column min / max / quantiles of the true values. It is not a SELECT, so
  // neither rewriter masks or filters it; the guard is what stands between it and a protected table.
  "SUMMARIZE of a protected table" should "be denied for a principal with a row policy on it" in {
    guardWithCls().check(
      "SUMMARIZE tpch1.customer",
      StatementKind.Other,
      eff(tenantUser, rows = List(rowPolicy)),
      ctx
    ) shouldBe a[Deny]
  }

  it should "be denied for a principal with a column policy on it" in {
    guardWithCls().check(
      "SUMMARIZE customer",
      StatementKind.Other,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe a[Deny]
    guardWithCls().check(
      "SUMMARIZE SELECT c_email FROM tpch1.customer",
      StatementKind.Other,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe a[Deny]
  }

  "a write whose source passes a policy table to ARRAY(TABLE ..)" should "be denied" in
    List(eff(tenantUser, cols = List(maskEmail)), eff(tenantUser, rows = List(rowPolicy)))
      .foreach { e =>
        guardWithCls().check(
          "INSERT INTO tpch1.t SELECT len(ARRAY(TABLE tpch1.customer))",
          StatementKind.Dml,
          e,
          ctx
        ) shouldBe a[Deny]
      }

  // F07: the guard's column-policy oracle is the deny-mode rewriter, which unquoted double quotes
  // only, so a backtick-quoted masked column laundered its raw values into a table of the caller's.
  "a write reading a backtick-quoted policy table or column" should "be denied like its twin" in
    List(
      "CREATE TABLE tpch1.leak AS SELECT upper(`c_email`) AS e FROM tpch1.customer" ->
        "CREATE TABLE tpch1.leak AS SELECT upper(\"c_email\") AS e FROM tpch1.customer",
      "CREATE TABLE tpch1.leak AS SELECT `c_email` FROM tpch1.customer" ->
        "CREATE TABLE tpch1.leak AS SELECT \"c_email\" FROM tpch1.customer",
      "CREATE TABLE tpch1.leak AS SELECT c_email FROM `tpch1`.`customer`" ->
        "CREATE TABLE tpch1.leak AS SELECT c_email FROM \"tpch1\".\"customer\"",
      "INSERT INTO tpch1.t SELECT c_id FROM tpch1.customer WHERE `c_email` = 'x'" ->
        "INSERT INTO tpch1.t SELECT c_id FROM tpch1.customer WHERE \"c_email\" = 'x'",
      "CREATE TABLE tpch1.leak AS SELECT * FROM `customer`" ->
        "CREATE TABLE tpch1.leak AS SELECT * FROM \"customer\""
    ).foreach { (backtick, twin) =>
      List(eff(tenantUser, cols = List(maskEmail)), eff(tenantUser, rows = List(rowPolicy)))
        .foreach { e =>
          withClue(s"$backtick ${e.columnPolicies.size}/${e.rowPolicies.size}") {
            val kind =
              if backtick.startsWith("INSERT") then StatementKind.Dml else StatementKind.Ddl
            guardWithCls().check(twin, kind, e, ctx) shouldBe a[Deny]
            guardWithCls().check(backtick, kind, e, ctx) shouldBe a[Deny]
          }
        }
    }

  it should "still allow a write reading only unmasked columns through backticks" in {
    guardWithCls().check(
      "INSERT INTO tpch1.scratch SELECT `c_id` FROM `tpch1`.`customer`",
      StatementKind.Dml,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe Allow
  }

  // Audit F18: the classifier reads a batch's first statement only, so a batch led by a read
  // classifies Select, and the node runs every statement of it.

  private def classified(sql: String, e: EffectiveSet): GuardOutcome =
    guardWithCls().check(sql, ai.starlake.quack.route.StatementClassifier.classify(sql), e, ctx)

  private val maskAndRow =
    List(eff(tenantUser, cols = List(maskEmail)), eff(tenantUser, rows = List(rowPolicy)))

  "a batch led by a read" should "be denied when a later write reads a protected table" in
    List(
      "SELECT 1; INSERT INTO tpch1.scratch SELECT c_email FROM tpch1.customer",
      "SELECT 1; CREATE TABLE tpch1.leak AS SELECT * FROM tpch1.customer",
      "FROM tpch1.orders; INSERT INTO tpch1.scratch SELECT c_email FROM tpch1.customer",
      "VALUES (1); INSERT INTO tpch1.scratch SELECT c_email FROM tpch1.customer",
      "SHOW TABLES; INSERT INTO tpch1.scratch SELECT c_email FROM tpch1.customer",
      "SELECT 1 /* ; */; INSERT INTO tpch1.scratch SELECT c_email FROM tpch1.customer"
    ).foreach { sql =>
      maskAndRow.foreach { e =>
        withClue(s"$sql ${e.columnPolicies.size}/${e.rowPolicies.size}") {
          ai.starlake.quack.route.StatementClassifier.classify(sql) shouldBe StatementKind.Select
          classified(sql, e) shouldBe a[Deny]
        }
      }
    }

  it should "be denied when it deletes rows by a predicate on a protected table" in
    maskAndRow.foreach { e =>
      classified(
        "SELECT 1; DELETE FROM tpch1.scratch WHERE id IN " +
          "(SELECT c_id FROM tpch1.customer WHERE c_email LIKE 'a%')",
        e
      ) shouldBe a[Deny]
    }

  it should "be denied, with its own reason, when a later read reads a protected table" in
    // The rewriters keep only the first statement, so the second read would reach the node raw.
    maskAndRow.foreach { e =>
      classified("SELECT 1; SELECT c_email FROM tpch1.customer", e) match
        case Deny(reason) => reason should include("multi-statement batch")
        case other        => fail(s"expected Deny, got $other")
    }

  it should "be denied when its first statement reads a protected table (audit F33)" in
    // The rewriters would rewrite that first statement and print only it, dropping the rest.
    List(
      "SELECT c_email FROM tpch1.customer; SELECT 2",
      "SELECT c_id FROM tpch1.customer; INSERT INTO tpch1.scratch VALUES (1)"
    ).foreach { sql =>
      maskAndRow.foreach { e =>
        withClue(s"$sql ${e.columnPolicies.size}/${e.rowPolicies.size}") {
          classified(sql, e) shouldBe a[Deny]
        }
      }
    }

  it should "still allow a batch that reads nothing protected" in {
    classified(
      "SELECT 1; INSERT INTO tpch1.scratch SELECT c_id FROM tpch1.customer",
      eff(tenantUser, cols = List(maskEmail))
    ) shouldBe Allow
    maskAndRow.foreach { e =>
      classified("SELECT 1; INSERT INTO tpch1.scratch VALUES (1)", e) shouldBe Allow
      classified("SELECT 1; SELECT 2", e) shouldBe Allow
    }
  }

  "a single SELECT carrying a semicolon" should "still be left to the rewriters" in
    List(
      "SELECT c_email FROM tpch1.customer;",
      "SELECT c_email FROM tpch1.customer ;  -- trailing",
      "SELECT c_email, ';' AS s FROM tpch1.customer",
      "SELECT c_email AS \"a;b\" FROM tpch1.customer",
      "SELECT c_email FROM tpch1.customer /* a; b */"
    ).foreach { sql =>
      maskAndRow.foreach { e =>
        withClue(sql)(classified(sql, e) shouldBe Allow)
      }
    }

  // Audit F19: JSqlParser hangs a statement-level WITH on the Insert, not on its Select, so the
  // CLS oracle used to see `SELECT * FROM leak` with the CTE stripped, resolve `leak` to the
  // physical (unpoliced) table and allow, while DuckDB binds `FROM leak` to the CTE that reads
  // the masked column.

  // A catalog that also knows the unpoliced sinks, so a stripped CTE name resolves physically.
  private def guardWithSinks: ProtectedWriteGuard =
    val cat = new ColumnCatalog.MapCatalog(
      Map(
        ("acme_tpch", "tpch1", "customer") -> List("c_id", "c_email", "c_region"),
        ("acme_tpch", "tpch1", "leak")     -> List("c_email"),
        ("acme_tpch", "tpch1", "scratch")  -> List("c_id", "c_email")
      )
    )
    new ProtectedWriteGuard(cat, clsEnabled = true, rlsEnabled = true)

  private val maskDeny = maskEmail.copy(id = "cp-2", action = "deny", transformSql = None)

  "an INSERT with a statement-level WITH" should "be denied when a CTE shadowing a table reads " +
    "a masked column" in
    List(
      "WITH leak AS (SELECT c_email FROM tpch1.customer) INSERT INTO leak SELECT * FROM leak",
      "WITH leak AS (SELECT c_email FROM tpch1.customer) " +
        "INSERT INTO tpch1.leak SELECT * FROM leak",
      "WITH leak AS (SELECT c_email FROM tpch1.customer) " +
        "INSERT INTO leak SELECT c_email FROM leak",
      "WITH leak AS (SELECT c_email FROM tpch1.customer) " +
        "INSERT INTO leak BY NAME SELECT * FROM leak",
      "WITH leak AS MATERIALIZED (SELECT c_email FROM tpch1.customer) " +
        "INSERT INTO leak SELECT * FROM leak",
      "WITH scratch AS (SELECT c_id, c_email FROM tpch1.customer) " +
        "INSERT INTO leak SELECT c_email FROM scratch",
      "WITH customer AS (SELECT c_email AS c_id FROM tpch1.customer) " +
        "INSERT INTO scratch SELECT c_id FROM customer"
    ).foreach { sql =>
      List(maskEmail, maskDeny).foreach { p =>
        withClue(s"$sql [${p.action}]") {
          guardWithSinks.check(
            sql,
            StatementKind.Dml,
            eff(tenantUser, cols = List(p)),
            ctx
          ) shouldBe
            a[Deny]
        }
      }
    }

  it should "still allow a CTE that reads only unmasked columns" in {
    guardWithSinks.check(
      "WITH x AS (SELECT c_id FROM tpch1.customer) INSERT INTO scratch SELECT c_id FROM x",
      StatementKind.Dml,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe Allow
  }

  it should "be denied when both the statement and its SELECT carry a WITH" in {
    guardWithSinks.check(
      "WITH leak AS (SELECT c_email FROM tpch1.customer) " +
        "INSERT INTO scratch WITH y AS (SELECT 1 AS c_id) SELECT c_id FROM y, leak",
      StatementKind.Dml,
      eff(tenantUser, cols = List(maskEmail)),
      ctx
    ) shouldBe a[Deny]
  }

  // Audit F20 / F22: DuckDB binds a two-part `X.t` catalog-first when X is an attached catalog
  // (the tenant-db alias, a federated alias), under the USE prelude to the very table a bare name
  // reads. Read schema-first, the name matched no policy keyed on the real schema: a write copied
  // masked or filtered data out, and a plain SELECT read every row past a row policy. The guard
  // refuses it for a policy holder.

  private val attachedCtx = ctx.copy(attachedCatalogs = Set("acme_tpch", "pg_src"))

  private def withAttached(sql: String, e: EffectiveSet): GuardOutcome =
    guardWithCls().check(
      sql,
      ai.starlake.quack.route.StatementClassifier.classify(sql),
      e,
      attachedCtx
    )

  "a two-part name headed by an attached catalog" should "be denied in a write" in
    List(
      "CREATE TABLE tpch1.mine AS SELECT c_email FROM acme_tpch.customer",
      "CREATE TABLE tpch1.mine AS SELECT * FROM acme_tpch.customer",
      "INSERT INTO tpch1.scratch SELECT c_email FROM acme_tpch.customer",
      "CREATE VIEW tpch1.v AS SELECT c_email FROM acme_tpch.customer",
      "CREATE TABLE tpch1.mine AS SELECT c_email FROM pg_src.customer",
      "CREATE TABLE tpch1.mine AS SELECT c_email FROM \"ACME_TPCH\".customer"
    ).foreach { sql =>
      maskAndRow.foreach { e =>
        withClue(s"$sql ${e.columnPolicies.size}/${e.rowPolicies.size}") {
          withAttached(sql, e) match
            case Deny(reason) => reason should include("ambiguous two-part name")
            case other        => fail(s"expected Deny, got $other")
        }
      }
    }

  it should "be denied in a plain SELECT, before the rewriters read it schema-first" in
    List(
      "SELECT * FROM acme_tpch.customer",
      "SELECT 1 FROM acme_tpch.customer",
      "SELECT 1 WHERE EXISTS (SELECT 1 FROM acme_tpch.customer WHERE c_region = 'Y')",
      "SELECT 1 WHERE 7 IN (SELECT c_id FROM acme_tpch.customer)",
      "SELECT c_id FROM tpch1.nation JOIN acme_tpch.customer ON true",
      "SELECT * FROM pg_src.customer"
    ).foreach { sql =>
      maskAndRow.foreach { e =>
        withClue(s"$sql ${e.columnPolicies.size}/${e.rowPolicies.size}") {
          withAttached(sql, e) match
            case Deny(reason) => reason should include("ambiguous two-part name")
            case other        => fail(s"expected Deny, got $other")
        }
      }
    }

  it should "still leave a fully qualified, a schema-qualified or a bare name to the rewriters" in
    List(
      "SELECT * FROM acme_tpch.tpch1.customer",
      "SELECT * FROM tpch1.customer",
      "SELECT * FROM customer"
    ).foreach { sql =>
      maskAndRow.foreach { e =>
        withClue(sql)(withAttached(sql, e) shouldBe Allow)
      }
    }

  it should "change nothing for a principal holding no policy" in {
    withAttached("SELECT * FROM acme_tpch.customer", eff(tenantUser)) shouldBe Allow
    withAttached(
      "CREATE TABLE tpch1.mine AS SELECT c_email FROM acme_tpch.customer",
      eff(tenantUser)
    ) shouldBe Allow
  }

  // histogram_values(source, col) runs query_table(source) and histogram runs histogram_values:
  // the rewriters see a table function, never the protected table its string names.
  "a reading table macro" should "be denied to a principal with policies, in a table position" in
    List(
      "SELECT * FROM histogram('tpch1.customer', c_email)",
      "SELECT * FROM histogram_values('tpch1.customer', c_region)",
      "SELECT * FROM main.\"histogram\"('tpch1.customer', c_email)",
      "SELECT c_id FROM tpch1.orders WHERE c_id IN " +
        "(SELECT bin FROM histogram_values('tpch1.customer', c_id))"
    ).foreach { sql =>
      withClue(sql) {
        guardWithCls().check(
          sql,
          StatementKind.Select,
          eff(tenantUser, rows = List(rowPolicy)),
          ctx
        ) match
          case Deny(reason) => reason should include("histogram")
          case other        => fail(s"expected Deny, got $other")
      }
    }

  it should "leave the aggregate of that name to the rewriters" in {
    guardWithCls().check(
      "SELECT histogram(c_region) FROM tpch1.customer",
      StatementKind.Select,
      eff(tenantUser, rows = List(rowPolicy)),
      ctx
    ) shouldBe Allow
  }

  // Decisions 15 and 18: the node judges every file location; the guard no longer screens files.
  "a file reader" should "be left to the node in a policy holder's read, and stay denied in a write" in {
    val withRows = eff(tenantUser, rows = List(rowPolicy))
    guardWithCls().check(
      "SELECT * FROM read_csv('s3://acme-etl/in/x.csv')",
      StatementKind.Select,
      withRows,
      ctx
    ) shouldBe Allow
    // the guard cannot isolate a write's source it cannot parse: an incomplete extraction
    guardWithCls().check(
      "CREATE TABLE tpch1.scratch AS SELECT * FROM read_csv('s3://acme-etl/in/x.csv')",
      StatementKind.Ddl,
      withRows,
      ctx
    ) shouldBe a[Deny]
    guardWithCls().check(
      "COPY (SELECT * FROM tpch1.customer) TO 's3://acme-etl/out/c.parquet'",
      StatementKind.Dml,
      withRows,
      ctx
    ) shouldBe a[Deny]
  }
