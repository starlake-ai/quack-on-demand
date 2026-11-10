package ai.starlake.quack.edge.cls

import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state._
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ColumnPolicyRewriterSpec extends AnyFlatSpec with Matchers:
  import ColumnPolicyRewriter._

  private val superuser = RbacUser(id = "u-super", tenant = None, username = "root", kind = "admin")
  private val tenantUser =
    RbacUser(id = "u-1", tenant = Some("acme"), username = "alice", kind = "user")

  private def eff(user: RbacUser, policies: List[RoleColumnPolicy] = Nil): EffectiveSet =
    EffectiveSet(user, Nil, Nil, Nil, Nil, policies)

  // v2 needs schema info for the resolver to walk column references. The default catalog
  // covers `customer` with the columns referenced by the masking-exercising tests below.
  // The `Map.empty` case is still tested explicitly via `passthrough SELECT * when the catalog
  // has no entry for the table` which constructs its own rewriter.
  private val defaultCat: ColumnCatalog =
    new ColumnCatalog.MapCatalog(
      Map(("acme_tpch", "tpch1", "customer") -> List("c_id", "c_email", "c_phone", "c_ssn"))
    )
  private def rw: ColumnPolicyRewriter = new ColumnPolicyRewriter(defaultCat, enabled = true)
  private val ctx                      =
    SchemaContext(
      defaultDatabase = Some("acme_tpch"),
      defaultSchema = Some("tpch1"),
      attachedCatalogs = Set.empty
    )

  "rewrite" should "passthrough when the feature is disabled" in {
    val policies = List(
      RoleColumnPolicy("cp-1", "r-1", "*", "tpch1", "customer", "c_email", "mask", Some("'***'"))
    )
    val disabled = new ColumnPolicyRewriter(defaultCat, enabled = false)
    disabled
      .rewrite(
        "SELECT c_email FROM tpch1.customer",
        StatementKind.Select,
        eff(tenantUser, policies),
        ctx
      )
      .unsafeRunSync() shouldBe Passthrough
  }

  it should "passthrough for superusers" in {
    rw.rewrite("SELECT c_email FROM customer", StatementKind.Select, eff(superuser), ctx)
      .unsafeRunSync() shouldBe Passthrough
  }

  it should "passthrough when the user has no column policies" in {
    rw.rewrite("SELECT c_email FROM customer", StatementKind.Select, eff(tenantUser, Nil), ctx)
      .unsafeRunSync() shouldBe Passthrough
  }

  it should "passthrough for non-Read statement kinds" in {
    val policies = List(
      RoleColumnPolicy("cp-1", "r-1", "*", "tpch1", "customer", "c_email", "mask", Some("'***'"))
    )
    val effSet = eff(tenantUser, policies)
    rw.rewrite("INSERT INTO audit VALUES (1)", StatementKind.Dml, effSet, ctx)
      .unsafeRunSync() shouldBe Passthrough
    rw.rewrite("CREATE TABLE x(y INT)", StatementKind.Ddl, effSet, ctx)
      .unsafeRunSync() shouldBe Passthrough
    rw.rewrite("BEGIN", StatementKind.Begin, effSet, ctx).unsafeRunSync() shouldBe Passthrough
  }

  it should "emit PassthroughParseFailed (distinct from Passthrough) when the SQL fails to parse" in {
    val policies = List(
      RoleColumnPolicy("cp-1", "r-1", "*", "tpch1", "customer", "c_email", "mask", Some("'***'"))
    )
    rw.rewrite("SELEC' WRONG", StatementKind.Select, eff(tenantUser, policies), ctx)
      .unsafeRunSync() shouldBe PassthroughParseFailed
  }

  private val maskEmail =
    RoleColumnPolicy("cp-1", "r-1", "*", "tpch1", "customer", "c_email", "mask", Some("'***'"))

  it should "deny a rewrite it cannot print as the caller's statement, saying why" in {
    // `//` reaches JSqlParser as `/`: a rewrite printed from that would divide differently
    rw.rewrite(
      "SELECT c_email, c_id // 2 FROM customer",
      StatementKind.Select,
      eff(tenantUser, List(maskEmail)),
      ctx
    ).unsafeRunSync() match
      case Denied(reason) => reason should include("//")
      case other          => fail(s"expected Denied, got $other")
  }

  // ---- statements that read no physical table (issue #114, second report) -----------------
  //
  // The quack client's ATTACH syncs the remote catalog with `duckdb_tables() UNION ALL
  // duckdb_views()`. The column resolver cannot model a table function and reports ParseFailed,
  // which the router denies fail-closed, so every principal holding ANY column policy could not
  // attach. Nothing in such a statement can carry a masked column.

  private val ClientSync =
    "SELECT schema_name, sql, 'table' FROM duckdb_tables() " +
      "UNION ALL SELECT schema_name, view_name, 'view' FROM duckdb_views()"

  it should "pass the quack client's catalog sync through even with policies in scope" in {
    rw.rewrite(ClientSync, StatementKind.Select, eff(tenantUser, List(maskEmail)), ctx)
      .unsafeRunSync() shouldBe Passthrough
    rw.rewrite(
      "SELECT * FROM duckdb_schemas() s",
      StatementKind.Select,
      eff(tenantUser, List(maskEmail)),
      ctx
    ).unsafeRunSync() shouldBe Passthrough
  }

  it should "not shortcut when a policy-bearing table hides beside the catalog function" in {
    // ORDER BY subqueries are a known blind spot of jsqlparser's traversal; the decision rides
    // on the ACL parser's complete walk, so this still reaches the resolver and fails closed.
    rw.rewrite(
      "SELECT 1 FROM duckdb_tables() ORDER BY (SELECT c_email FROM customer LIMIT 1)",
      StatementKind.Select,
      eff(tenantUser, List(maskEmail)),
      ctx
    ).unsafeRunSync() should not be Passthrough
    rw.rewrite(
      "SELECT t.table_name, c.c_email FROM duckdb_tables() t, customer c",
      StatementKind.Select,
      eff(tenantUser, List(maskEmail)),
      ctx
    ).unsafeRunSync() should not be Passthrough
  }

  // ---- DuckDB positional column references (issue #114, second report) --------------------
  //
  // The quack client pushes every scan down as `SELECT #1, #2 FROM <table>`: positional
  // references, no column names. The resolver saw a column literally named `#1`, found nothing to
  // mask, and in the default lenient mode forwarded the scan unmasked: a column policy holder could
  // read every masked value through ATTACH.

  it should "resolve the client's positional projection against the table and mask it" in {
    rw.rewrite(
      "SELECT #1, #2 FROM customer",
      StatementKind.Select,
      eff(tenantUser, List(maskEmail)),
      ctx
    ).unsafeRunSync() match
      case Rewritten(sql) =>
        sql should include("c_id")
        sql should include("'***' AS c_email")
        sql should not include "#"
      case other => fail(s"expected Rewritten, got $other")
    // A positional reference to an unmasked column alone: resolved, nothing to mask.
    rw.rewrite(
      "SELECT #1 FROM customer",
      StatementKind.Select,
      eff(tenantUser, List(maskEmail)),
      ctx
    ).unsafeRunSync() shouldBe Passthrough
  }

  it should "resolve positional references inside functions and WHERE (FROM-relative there too)" in {
    rw.rewrite(
      "SELECT count(#2) FROM customer WHERE #1 = 7",
      StatementKind.Select,
      eff(tenantUser, List(maskEmail)),
      ctx
    ).unsafeRunSync() match
      case Rewritten(sql) =>
        sql should include("count('***')")
        sql should include("c_id = 7")
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "deny positional references it cannot resolve safely" in {
    val e = eff(tenantUser, List(maskEmail))
    // Out of range.
    rw.rewrite("SELECT #9 FROM customer", StatementKind.Select, e, ctx)
      .unsafeRunSync() shouldBe a[Denied]
    // ORDER BY is projection-relative, a different rule: refused rather than guessed.
    rw.rewrite("SELECT c_id, c_email FROM customer ORDER BY #2", StatementKind.Select, e, ctx)
      .unsafeRunSync() shouldBe a[Denied]
    // Joins and derived tables number the combined column list: refused.
    rw.rewrite(
      "SELECT #1 FROM customer c JOIN customer d ON c.c_id = d.c_id",
      StatementKind.Select,
      e,
      ctx
    ).unsafeRunSync() shouldBe a[Denied]
    rw.rewrite(
      "SELECT #1 FROM (SELECT c_email, c_id FROM customer) s",
      StatementKind.Select,
      e,
      ctx
    ).unsafeRunSync() shouldBe a[Denied]
    // A table the catalog does not know cannot be numbered.
    rw.rewrite("SELECT #1 FROM unknown_table", StatementKind.Select, e, ctx)
      .unsafeRunSync() shouldBe a[Denied]
    // A batch.
    rw.rewrite("SELECT #1 FROM customer; SELECT #2 FROM customer", StatementKind.Select, e, ctx)
      .unsafeRunSync() shouldBe a[Denied]
  }

  it should "not mistake a string literal for a positional reference" in {
    rw.rewrite(
      "SELECT c_id FROM customer WHERE c_id = '#1'",
      StatementKind.Select,
      eff(tenantUser, List(maskEmail)),
      ctx
    ).unsafeRunSync() should not be a[Denied]
  }

  it should "not shortcut a batch or any other table function" in {
    rw.rewrite(
      "SELECT * FROM duckdb_tables(); SELECT c_email FROM customer",
      StatementKind.Select,
      eff(tenantUser, List(maskEmail)),
      ctx
    ).unsafeRunSync() should not be Passthrough
    rw.rewrite(
      "SELECT * FROM read_parquet('/data/x.parquet')",
      StatementKind.Select,
      eff(tenantUser, List(maskEmail)),
      ctx
    ).unsafeRunSync() should not be Passthrough
  }

  it should "rewrite a direct column reference in the projection to the transform" in {
    val out = rw
      .rewrite(
        "SELECT c_id, c_email FROM tpch1.customer",
        StatementKind.Select,
        eff(tenantUser, List(maskEmail)),
        ctx
      )
      .unsafeRunSync()
    out match
      case Rewritten(sql) =>
        sql should include("'***'")
        sql.toLowerCase should include("c_id")
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "preserve a user-supplied projection alias" in {
    val out = rw
      .rewrite(
        "SELECT c_email AS e FROM tpch1.customer",
        StatementKind.Select,
        eff(tenantUser, List(maskEmail)),
        ctx
      )
      .unsafeRunSync()
    out match
      case Rewritten(sql) =>
        sql should include("'***'")
        sql.toLowerCase should include(" e")
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "leave projections that don't touch covered columns alone" in {
    val out = rw
      .rewrite(
        "SELECT c_id FROM tpch1.customer",
        StatementKind.Select,
        eff(tenantUser, List(maskEmail)),
        ctx
      )
      .unsafeRunSync()
    // Either Passthrough (nothing changed) OR Rewritten with the same projection. Both are OK
    // for this case as long as the SQL doesn't contain the transform expression.
    out match
      case Passthrough    => succeed
      case Rewritten(sql) => sql should not include "'***'"
      case Denied(reason) => fail(s"unexpected deny: $reason")
  }

  // -------- nested SELECTs --------

  it should "rewrite the inner SELECT of a scalar subquery in the projection" in {
    val out = rw
      .rewrite(
        "SELECT (SELECT c_email FROM tpch1.customer LIMIT 1) AS e FROM tpch1.customer",
        StatementKind.Select,
        eff(tenantUser, List(maskEmail)),
        ctx
      )
      .unsafeRunSync()
    out match
      case Rewritten(sql) => sql should include("'***'")
      case other          => fail(s"expected Rewritten, got $other")
  }

  it should "rewrite a subquery used as a FROM item" in {
    val out = rw
      .rewrite(
        "SELECT c_email FROM (SELECT c_email FROM tpch1.customer) sub",
        StatementKind.Select,
        eff(tenantUser, List(maskEmail)),
        ctx
      )
      .unsafeRunSync()
    out match
      case Rewritten(sql) =>
        // Both the outer projection and the inner projection should now reference the mask.
        sql.split("'\\*\\*\\*'").length - 1 should be >= 2
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "rewrite each arm of a UNION" in {
    val out = rw
      .rewrite(
        "SELECT c_email FROM tpch1.customer UNION SELECT c_email FROM tpch1.customer",
        StatementKind.Select,
        eff(tenantUser, List(maskEmail)),
        ctx
      )
      .unsafeRunSync()
    out match
      case Rewritten(sql) =>
        sql.split("'\\*\\*\\*'").length - 1 should be >= 2
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "rewrite a CTE body" in {
    val out = rw
      .rewrite(
        "WITH x AS (SELECT c_email FROM tpch1.customer) SELECT c_email FROM x",
        StatementKind.Select,
        eff(tenantUser, List(maskEmail)),
        ctx
      )
      .unsafeRunSync()
    out match
      case Rewritten(sql) => sql should include("'***'") // at minimum the CTE body
      case other          => fail(s"expected Rewritten, got $other")
  }

  // -------- SELECT * expansion --------

  private def catWithCustomer(cols: List[String]): ColumnCatalog =
    new ColumnCatalog.MapCatalog(Map(("acme_tpch", "tpch1", "customer") -> cols))

  it should "expand SELECT * via the column catalog and mask covered columns" in {
    val r =
      new ColumnPolicyRewriter(catWithCustomer(List("c_id", "c_email", "c_phone")), enabled = true)
    val out = r
      .rewrite(
        "SELECT * FROM tpch1.customer",
        StatementKind.Select,
        eff(tenantUser, List(maskEmail)),
        ctx
      )
      .unsafeRunSync()
    out match
      case Rewritten(sql) =>
        sql.toLowerCase should include("c_id")
        sql should include("'***'")
        sql.toLowerCase should include("c_phone")
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "expand a qualified t.* against the catalog" in {
    val r   = new ColumnPolicyRewriter(catWithCustomer(List("c_id", "c_email")), enabled = true)
    val out = r
      .rewrite(
        "SELECT c.* FROM tpch1.customer c",
        StatementKind.Select,
        eff(tenantUser, List(maskEmail)),
        ctx
      )
      .unsafeRunSync()
    out match
      case Rewritten(sql) =>
        sql.toLowerCase should include("c_id")
        sql should include("'***'")
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "passthrough SELECT * when the catalog has no entry for the table" in {
    // With UnresolvedMode.Pass (the rewriter's default) plus an empty catalog, the inner
    // jsqltranspiler can't resolve `tpch1.customer` and falls into the LENIENT/parse-failed arm.
    // The rewriter surfaces this as PassthroughParseFailed (distinct from Passthrough since
    // Task 9 split the metric tag); both are routed the same way - original SQL forwarded.
    val r = new ColumnPolicyRewriter(new ColumnCatalog.MapCatalog(Map.empty), enabled = true)
    r.rewrite(
      "SELECT * FROM tpch1.customer",
      StatementKind.Select,
      eff(tenantUser, List(maskEmail)),
      ctx
    ).unsafeRunSync() shouldBe PassthroughParseFailed
  }

  // -------- deny semantics --------

  private val denySsn =
    RoleColumnPolicy("cp-2", "r-1", "*", "tpch1", "customer", "c_ssn", "deny", None)

  it should "deny SELECT c_ssn FROM customer when the policy is deny" in {
    val r   = new ColumnPolicyRewriter(catWithCustomer(List("c_id", "c_ssn")), enabled = true)
    val out = r
      .rewrite(
        "SELECT c_ssn FROM tpch1.customer",
        StatementKind.Select,
        eff(tenantUser, List(denySsn)),
        ctx
      )
      .unsafeRunSync()
    out match
      case Denied(reason) => reason.toLowerCase should include("c_ssn")
      case other          => fail(s"expected Denied, got $other")
  }

  it should "deny SELECT * when expansion uncovers a denied column" in {
    val r   = new ColumnPolicyRewriter(catWithCustomer(List("c_id", "c_ssn")), enabled = true)
    val out = r
      .rewrite(
        "SELECT * FROM tpch1.customer",
        StatementKind.Select,
        eff(tenantUser, List(denySsn)),
        ctx
      )
      .unsafeRunSync()
    out match
      case Denied(_) => succeed
      case other     => fail(s"expected Denied, got $other")
  }

  it should "rewrite a covered column inside a WHERE predicate" in {
    val r   = new ColumnPolicyRewriter(catWithCustomer(List("c_id", "c_email")), enabled = true)
    val out = r
      .rewrite(
        "SELECT c_id FROM tpch1.customer WHERE c_email LIKE '%@acme.com'",
        StatementKind.Select,
        eff(tenantUser, List(maskEmail)),
        ctx
      )
      .unsafeRunSync()
    out match
      case Rewritten(sql) => sql should include("'***'")
      case other          => fail(s"expected Rewritten, got $other")
  }

  it should "rewrite covered columns inside composite expressions in projection" in {
    val r   = new ColumnPolicyRewriter(catWithCustomer(List("c_id", "c_email")), enabled = true)
    val out = r
      .rewrite(
        "SELECT length(c_email) FROM tpch1.customer",
        StatementKind.Select,
        eff(tenantUser, List(maskEmail)),
        ctx
      )
      .unsafeRunSync()
    out match
      case Rewritten(sql) =>
        sql.toLowerCase should include("length")
        sql should include("'***'")
      case other => fail(s"expected Rewritten, got $other")
  }

  // -------- outcome refinement: unresolved-table deny vs regular deny --------

  it should "emit DeniedUnresolvedTable when the resolver can't find the table" in {
    // Empty catalog + Deny mode forces a TableNotFoundException / TableNotDeclaredException from
    // jsqltranspiler, whose message contains the "not found" / "not declared" heuristic marker.
    val r = new ColumnPolicyRewriter(
      catalog = new ColumnCatalog.MapCatalog(Map.empty),
      unresolvedMode = UnresolvedMode.Deny,
      enabled = true
    )
    val out = r
      .rewrite(
        "SELECT c_email FROM tpch1.unknown_table",
        StatementKind.Select,
        eff(tenantUser, List(maskEmail)),
        ctx
      )
      .unsafeRunSync()
    out shouldBe DeniedUnresolvedTable
  }

  it should "still emit Denied (not DeniedUnresolvedTable) for a policy-based deny" in {
    val r   = new ColumnPolicyRewriter(catWithCustomer(List("c_id", "c_ssn")), enabled = true)
    val out = r
      .rewrite(
        "SELECT c_ssn FROM tpch1.customer",
        StatementKind.Select,
        eff(tenantUser, List(denySsn)),
        ctx
      )
      .unsafeRunSync()
    out match
      case Denied(reason) => reason.toLowerCase should include("c_ssn")
      case other          => fail(s"expected Denied, got $other")
  }

  // -------- system-schema (information_schema / pg_catalog) resolution --------
  //
  // Metadata queries emitted by FlightSQL GetDbSchemas / catalog-browsing clients reference
  // information_schema and pg_catalog tables the tenant catalog does not know (it returns Nil),
  // which used to trip the resolver into a fail-closed deny for any principal carrying column
  // policies. SystemSchemaColumns now seeds the resolver with DuckDB's fixed system-catalog
  // shapes so these queries resolve; policies never target system schemas, so the rewrite is a
  // no-op for them. Unknown system tables stay absent and keep failing closed.

  private val maskPhone =
    RoleColumnPolicy("cp-p", "r-1", "*", "tpch1", "customer", "c_phone", "mask", Some("'***'"))

  private val getDbSchemasSql =
    "SELECT catalog_name, schema_name AS db_schema_name FROM information_schema.schemata " +
      "WHERE schema_name NOT IN ('information_schema','pg_catalog') ORDER BY 1,2"

  private def rwDeny: ColumnPolicyRewriter =
    new ColumnPolicyRewriter(defaultCat, unresolvedMode = UnresolvedMode.Deny, enabled = true)

  private def shouldNotDeny(out: Outcome): Unit = out match
    case Passthrough    => ()
    case Rewritten(sql) => sql should not include "'***'"
    case other          => fail(s"expected Passthrough/unmasked Rewritten, got $other")

  it should "let the FlightSQL GetDbSchemas schemata query through for a principal with column policies" in
    shouldNotDeny(
      rw.rewrite(getDbSchemasSql, StatementKind.Select, eff(tenantUser, List(maskPhone)), ctx)
        .unsafeRunSync()
    )

  it should "let the GetDbSchemas schemata query through in Deny (STRICT) unresolved mode too" in
    shouldNotDeny(
      rwDeny
        .rewrite(getDbSchemasSql, StatementKind.Select, eff(tenantUser, List(maskPhone)), ctx)
        .unsafeRunSync()
    )

  it should "let SELECT table_name FROM information_schema.tables through" in
    shouldNotDeny(
      rwDeny
        .rewrite(
          "SELECT table_name FROM information_schema.tables",
          StatementKind.Select,
          eff(tenantUser, List(maskPhone)),
          ctx
        )
        .unsafeRunSync()
    )

  it should "let SELECT * FROM information_schema.columns through (star expansion over the static shape)" in
    shouldNotDeny(
      rwDeny
        .rewrite(
          "SELECT * FROM information_schema.columns WHERE table_schema='tpch1'",
          StatementKind.Select,
          eff(tenantUser, List(maskPhone)),
          ctx
        )
        .unsafeRunSync()
    )

  it should "let SELECT * FROM pg_catalog.pg_tables through" in
    shouldNotDeny(
      rwDeny
        .rewrite(
          "SELECT * FROM pg_catalog.pg_tables",
          StatementKind.Select,
          eff(tenantUser, List(maskPhone)),
          ctx
        )
        .unsafeRunSync()
    )

  it should "keep failing closed for a system table NOT in the static set (Deny mode)" in {
    val out = rwDeny
      .rewrite(
        "SELECT * FROM information_schema.no_such_system_table",
        StatementKind.Select,
        eff(tenantUser, List(maskPhone)),
        ctx
      )
      .unsafeRunSync()
    out match
      case DeniedUnresolvedTable | Denied(_) | PassthroughParseFailed => succeed
      case other => fail(s"expected a fail-closed outcome, got $other")
  }

  // -------- no-bypass proofs: system-schema resolution must not weaken user-table masking --------

  it should "still mask c_phone on a plain user-table SELECT" in {
    val out = rwDeny
      .rewrite(
        "SELECT c_phone FROM tpch1.customer",
        StatementKind.Select,
        eff(tenantUser, List(maskPhone)),
        ctx
      )
      .unsafeRunSync()
    out match
      case Rewritten(sql) => sql should include("'***'")
      case other          => fail(s"expected Rewritten with mask, got $other")
  }

  it should "still mask the user column in a mixed system-table/user-table statement" in {
    val out = rwDeny
      .rewrite(
        "SELECT information_schema.tables.table_name, c.c_phone " +
          "FROM information_schema.tables, tpch1.customer c",
        StatementKind.Select,
        eff(tenantUser, List(maskPhone)),
        ctx
      )
      .unsafeRunSync()
    out match
      case Rewritten(sql) =>
        sql should include("'***'")
        sql.toLowerCase should include("table_name")
      case other => fail(s"expected Rewritten with mask, got $other")
  }

  it should "still mask (or deny) a user-table subquery hidden under an information_schema outer query" in {
    val out = rwDeny
      .rewrite(
        "SELECT * FROM information_schema.tables " +
          "WHERE table_name IN (SELECT c_phone FROM tpch1.customer)",
        StatementKind.Select,
        eff(tenantUser, List(maskPhone)),
        ctx
      )
      .unsafeRunSync()
    out match
      case Rewritten(sql)                                             => sql should include("'***'")
      case Denied(_) | DeniedUnresolvedTable | PassthroughParseFailed => succeed
      case other => fail(s"expected mask or deny, got $other")
  }

  it should "not open a table-function side door alongside information_schema" in {
    // query_table('tpch1.customer') is a table function; it is not indexed by the FROM walk and
    // must stay on its pre-fix path (deny / fail-closed at the router), never an unmasked pass.
    val out = rwDeny
      .rewrite(
        "SELECT it.table_name, q.c_phone " +
          "FROM information_schema.tables it, query_table('tpch1.customer') q",
        StatementKind.Select,
        eff(tenantUser, List(maskPhone)),
        ctx
      )
      .unsafeRunSync()
    out match
      case Denied(_) | DeniedUnresolvedTable | PassthroughParseFailed => succeed
      case Rewritten(sql)                                             => sql should include("'***'")
      case other => fail(s"expected deny/fail-closed or mask, got $other")
  }

  // -------- EXISTS / ANY / ALL / SOME subqueries must not leak covered columns --------
  //
  // Without visitor cases for ExistsExpression and AnyComparisonExpression the subquery is never
  // descended, so a covered column inside it is forwarded unmasked and the EXISTS/ANY predicate
  // acts as a true/false membership oracle on the masked values.

  it should "mask (or deny) a covered column inside an ANY subquery" in {
    val out = rwDeny
      .rewrite(
        "SELECT 1 FROM information_schema.tables " +
          "WHERE '555' = ANY(SELECT c_phone FROM tpch1.customer)",
        StatementKind.Select,
        eff(tenantUser, List(maskPhone)),
        ctx
      )
      .unsafeRunSync()
    out match
      case Rewritten(sql)                                             => sql should include("'***'")
      case Denied(_) | DeniedUnresolvedTable | PassthroughParseFailed => succeed
      case other => fail(s"expected mask or deny, got $other")
  }

  it should "mask (or deny) a covered column inside an ALL subquery" in {
    val out = rwDeny
      .rewrite(
        "SELECT 1 FROM information_schema.tables " +
          "WHERE '555' = ALL(SELECT c_phone FROM tpch1.customer)",
        StatementKind.Select,
        eff(tenantUser, List(maskPhone)),
        ctx
      )
      .unsafeRunSync()
    out match
      case Rewritten(sql)                                             => sql should include("'***'")
      case Denied(_) | DeniedUnresolvedTable | PassthroughParseFailed => succeed
      case other => fail(s"expected mask or deny, got $other")
  }

  it should "mask (or deny) a covered column in an EXISTS subquery WHERE clause" in {
    val out = rwDeny
      .rewrite(
        "SELECT 1 FROM information_schema.tables " +
          "WHERE EXISTS (SELECT 1 FROM tpch1.customer WHERE c_phone = '555')",
        StatementKind.Select,
        eff(tenantUser, List(maskPhone)),
        ctx
      )
      .unsafeRunSync()
    out match
      case Rewritten(sql)                                             => sql should include("'***'")
      case Denied(_) | DeniedUnresolvedTable | PassthroughParseFailed => succeed
      case other => fail(s"expected mask or deny, got $other")
  }

  it should "mask (or deny) a covered column in an EXISTS subquery projection" in {
    val out = rwDeny
      .rewrite(
        "SELECT 1 FROM information_schema.tables " +
          "WHERE EXISTS (SELECT c_phone FROM tpch1.customer)",
        StatementKind.Select,
        eff(tenantUser, List(maskPhone)),
        ctx
      )
      .unsafeRunSync()
    out match
      case Rewritten(sql)                                             => sql should include("'***'")
      case Denied(_) | DeniedUnresolvedTable | PassthroughParseFailed => succeed
      case other => fail(s"expected mask or deny, got $other")
  }

  it should "mask the covered column in both the outer projection and a nested EXISTS subquery" in {
    val out = rwDeny
      .rewrite(
        "SELECT c_phone FROM tpch1.customer " +
          "WHERE EXISTS (SELECT c_phone FROM tpch1.customer)",
        StatementKind.Select,
        eff(tenantUser, List(maskPhone)),
        ctx
      )
      .unsafeRunSync()
    out match
      case Rewritten(sql) =>
        sql.sliding("'***'".length).count(_ == "'***'") shouldBe 2
      case other => fail(s"expected Rewritten with both occurrences masked, got $other")
  }

  it should "leave an EXISTS/ANY subquery that touches no covered column untouched" in {
    val existsOut = rwDeny
      .rewrite(
        "SELECT 1 FROM information_schema.tables " +
          "WHERE EXISTS (SELECT c_id FROM tpch1.customer)",
        StatementKind.Select,
        eff(tenantUser, List(maskPhone)),
        ctx
      )
      .unsafeRunSync()
    existsOut shouldBe Passthrough
    val anyOut = rwDeny
      .rewrite(
        "SELECT 1 FROM information_schema.tables " +
          "WHERE '1' = ANY(SELECT c_id FROM tpch1.customer)",
        StatementKind.Select,
        eff(tenantUser, List(maskPhone)),
        ctx
      )
      .unsafeRunSync()
    anyOut shouldBe Passthrough
  }

  // -------- NOT-wrapped predicates must descend too --------
  //
  // jsqlparser parses a leading NOT as a NotExpression wrapper (NOT ExistsExpression.isNot);
  // without a visitor case for it the wrapped predicate is never descended and NOT EXISTS /
  // NOT (= ANY) leak the covered column unmasked. `x NOT IN (...)` stays an InExpression with
  // isNot=true, so it rides the existing InExpression case; pinned here as a guard anyway.

  it should "mask a covered column inside a NOT EXISTS subquery WHERE clause" in {
    val out = rwDeny
      .rewrite(
        "SELECT 1 FROM tpch1.customer " +
          "WHERE NOT EXISTS (SELECT 1 FROM tpch1.customer c2 WHERE c2.c_phone = '555')",
        StatementKind.Select,
        eff(tenantUser, List(maskPhone)),
        ctx
      )
      .unsafeRunSync()
    out match
      case Rewritten(sql) => sql should include("'***'")
      case other          => fail(s"expected Rewritten with mask, got $other")
  }

  it should "mask a covered column inside a NOT (= ANY) subquery" in {
    val out = rwDeny
      .rewrite(
        "SELECT 1 FROM information_schema.tables " +
          "WHERE NOT ('555' = ANY(SELECT c_phone FROM tpch1.customer))",
        StatementKind.Select,
        eff(tenantUser, List(maskPhone)),
        ctx
      )
      .unsafeRunSync()
    out match
      case Rewritten(sql) => sql should include("'***'")
      case other          => fail(s"expected Rewritten with mask, got $other")
  }

  it should "mask a covered column inside a NOT IN subquery" in {
    val out = rwDeny
      .rewrite(
        "SELECT 1 FROM tpch1.customer " +
          "WHERE '555' NOT IN (SELECT c_phone FROM tpch1.customer)",
        StatementKind.Select,
        eff(tenantUser, List(maskPhone)),
        ctx
      )
      .unsafeRunSync()
    out match
      case Rewritten(sql) => sql should include("'***'")
      case other          => fail(s"expected Rewritten with mask, got $other")
  }

  it should "mask a covered column in a NOT EXISTS subquery projection" in {
    val out = rwDeny
      .rewrite(
        "SELECT 1 FROM information_schema.tables " +
          "WHERE NOT EXISTS (SELECT c_phone FROM tpch1.customer)",
        StatementKind.Select,
        eff(tenantUser, List(maskPhone)),
        ctx
      )
      .unsafeRunSync()
    out match
      case Rewritten(sql) => sql should include("'***'")
      case other          => fail(s"expected Rewritten with mask, got $other")
  }

  it should "leave a NOT-wrapped predicate that touches no covered column untouched" in {
    val out = rwDeny
      .rewrite(
        "SELECT 1 FROM information_schema.tables " +
          "WHERE NOT EXISTS (SELECT c_id FROM tpch1.customer)",
        StatementKind.Select,
        eff(tenantUser, List(maskPhone)),
        ctx
      )
      .unsafeRunSync()
    out shouldBe Passthrough
  }

  // An unaliased aggregate over a masked column (`min(c_phone)`) stays unaliased: DuckDB names the
  // slot itself. It used to fail at the node through the FlightSQL prepare probe
  // `SELECT * FROM (<query>) AS _qod_probe LIMIT 0`: the resolver expanded the probe's star under
  // its own name for the slot (`_qod_probe.min`), which DuckDB does not bind. The probe's star is
  // now kept as written (JsqltranspilerRewriterSpec runs both shapes on DuckDB).
  it should "leave an unaliased aggregate projection unaliased after masking" in {
    val catWithPhone = new ColumnCatalog.MapCatalog(
      Map(("acme_tpch", "tpch1", "customer") -> List("c_mktsegment", "c_phone"))
    )
    val maskPhone =
      RoleColumnPolicy("cp-3", "r-1", "*", "tpch1", "customer", "c_phone", "mask", Some("'***'"))
    val r = new ColumnPolicyRewriter(catWithPhone, enabled = true)

    val unaliasedOut = r
      .rewrite(
        "SELECT c_mktsegment, min(c_phone) FROM tpch1.customer GROUP BY 1",
        StatementKind.Select,
        eff(tenantUser, List(maskPhone)),
        ctx
      )
      .unsafeRunSync()
    val aliasedOut = r
      .rewrite(
        "SELECT c_mktsegment, min(c_phone) AS phone FROM tpch1.customer GROUP BY 1",
        StatementKind.Select,
        eff(tenantUser, List(maskPhone)),
        ctx
      )
      .unsafeRunSync()

    val probedOut = r
      .rewrite(
        "SELECT * FROM (SELECT c_mktsegment, min(c_phone) FROM tpch1.customer GROUP BY 1) " +
          "AS _qod_probe LIMIT 0",
        StatementKind.Select,
        eff(tenantUser, List(maskPhone)),
        ctx
      )
      .unsafeRunSync()

    unaliasedOut match
      case Rewritten(sql) =>
        sql shouldBe "SELECT c_mktsegment, min('***') FROM tpch1.customer GROUP BY 1"
      case other => fail(s"expected Rewritten, got $other")

    probedOut match
      case Rewritten(sql) =>
        sql shouldBe "SELECT * FROM (SELECT c_mktsegment, min('***') FROM tpch1.customer " +
          "GROUP BY 1) AS _qod_probe LIMIT 0"
      case other => fail(s"expected Rewritten, got $other")

    aliasedOut match
      case Rewritten(sql) =>
        sql shouldBe "SELECT c_mktsegment, min('***') AS phone FROM tpch1.customer GROUP BY 1"
        sql should include("AS phone")
      case other => fail(s"expected Rewritten, got $other")
  }

  // ------------------------------------------------------------------
  // Closed CLS masking gaps (outer-scope aliases in correlated subqueries;
  // unresolved tables nested in subqueries failing open), pinned through the
  // full layer (catalog + schema-map collection + inner rewriter)
  // ------------------------------------------------------------------

  it should "mask an outer-aliased covered column inside a correlated EXISTS subquery" in {
    rw.rewrite(
      "SELECT c.c_id FROM customer c WHERE EXISTS " +
        "(SELECT 1 FROM customer c2 WHERE c2.c_id = c.c_id AND c.c_phone = '555')",
      StatementKind.Select,
      eff(tenantUser, List(maskPhone)),
      ctx
    ).unsafeRunSync() match
      case Rewritten(sql) =>
        sql should include("'***'")
        (sql should not).include("c.c_phone = '555'")
      case other => fail(s"expected Rewritten, got $other")
  }

  it should "deny an unresolvable table nested inside an EXISTS subquery in STRICT mode" in {
    // The FROM-walker used to skip expression subqueries entirely, so the nested table
    // never reached the schema map and the statement passed through unchecked.
    rwDeny
      .rewrite(
        "SELECT 1 FROM information_schema.tables WHERE EXISTS (SELECT 1 FROM tpch1.no_such_table)",
        StatementKind.Select,
        eff(tenantUser, List(maskPhone)),
        ctx
      )
      .unsafeRunSync() match
      case Denied(_) => succeed
      case other     => fail(s"expected Denied, got $other")
  }

  it should "deny a TABLE statement passed to ARRAY() or ANY, which reads every column raw" in
    List(
      "SELECT ARRAY(TABLE customer)",
      "SELECT unnest(ARRAY(TABLE tpch1.customer))",
      "SELECT 1 WHERE 'a@x' = ANY (TABLE customer)"
    ).foreach { sql =>
      withClue(sql) {
        rw.rewrite(sql, StatementKind.Select, eff(tenantUser, List(maskEmail)), ctx)
          .unsafeRunSync() shouldBe a[Denied]
      }
    }

  it should "deny the star expansion and whole-row forms that read a masked column raw" in
    List(
      "SELECT COLUMNS('c_e.*') FROM customer",
      "SELECT * LIKE 'c_e%' FROM customer",
      "SELECT concat(*COLUMNS(*)) FROM customer",
      "SELECT c_id FROM customer ORDER BY COLUMNS('c_e.*')",
      "SELECT c FROM customer c",
      "SELECT to_json(customer) FROM customer"
    ).foreach { sql =>
      withClue(sql) {
        rw.rewrite(sql, StatementKind.Select, eff(tenantUser, List(maskEmail)), ctx)
          .unsafeRunSync() shouldBe a[Denied]
      }
    }

  it should "expand a star modifier into a masked column list" in {
    rw.rewrite(
      "SELECT * EXCLUDE (c_ssn) FROM customer c",
      StatementKind.Select,
      eff(tenantUser, List(maskEmail)),
      ctx
    ).unsafeRunSync() shouldBe Rewritten(
      "SELECT c.c_id, '***' AS c_email, c.c_phone FROM customer c"
    )
  }

  it should "mask a method call on a masked column and deny a field read of one" in {
    val effSet = eff(tenantUser, List(maskEmail))
    rw.rewrite("SELECT c_email.lower() FROM customer", StatementKind.Select, effSet, ctx)
      .unsafeRunSync() shouldBe Rewritten("SELECT lower('***') FROM customer")
    rw.rewrite(
      "SELECT c_id FROM customer c WHERE c.c_email.contains('alice')",
      StatementKind.Select,
      effSet,
      ctx
    ).unsafeRunSync() shouldBe Rewritten(
      "SELECT c_id FROM customer c WHERE contains('***', 'alice')"
    )
    rw.rewrite("SELECT c_email.x FROM customer", StatementKind.Select, effSet, ctx)
      .unsafeRunSync() shouldBe a[Denied]
  }

  it should "deny a method call on a masked table's binding, which reads the whole row" in
    List(
      "SELECT c.to_json() FROM customer c",
      "SELECT customer.to_json() FROM customer",
      "SELECT tpch1.customer.to_json() FROM customer",
      "SELECT c.list() FROM customer c",
      "SELECT c.struct_extract('c_email') FROM customer c",
      "SELECT c_id FROM customer c WHERE c.to_json()::VARCHAR LIKE '%alice%'",
      "SELECT list_transform([1], x -> c.to_json()) FROM customer c"
    ).foreach { sql =>
      withClue(sql) {
        rw.rewrite(sql, StatementKind.Select, eff(tenantUser, List(maskEmail)), ctx)
          .unsafeRunSync() shouldBe a[Denied]
      }
    }

  it should "deny an alias column list on a masked table, which renames the column raw" in
    List(
      "SELECT e FROM customer AS c(a, b, e)",
      "SELECT e FROM customer c(a, b, e)",
      "SELECT c.e FROM customer AS c(a, b, e)",
      "SELECT a FROM customer AS c(a, b, e) WHERE e LIKE 'a%'",
      "SELECT c_id FROM customer c(c_id, n, zz) NATURAL JOIN (SELECT 'alice@x' AS zz) g"
    ).foreach { sql =>
      withClue(sql) {
        rw.rewrite(sql, StatementKind.Select, eff(tenantUser, List(maskEmail)), ctx)
          .unsafeRunSync() shouldBe a[Denied]
      }
    }

  it should "deny an alias column list on a parenthesized FROM item holding a masked table" in
    List(
      "SELECT e FROM (customer) AS c(a, b, e)",
      "SELECT e FROM ((customer)) AS c(a, b, e)",
      "SELECT e FROM (customer c) AS d(a, b, e)",
      "SELECT x.e FROM (customer AS c) x(a, b, e)",
      "SELECT e FROM (customer JOIN orders USING (c_id)) AS j(a, b, e)"
    ).foreach { sql =>
      withClue(sql) {
        rw.rewrite(sql, StatementKind.Select, eff(tenantUser, List(maskEmail)), ctx)
          .unsafeRunSync() shouldBe a[Denied]
      }
    }

  it should "admit an alias column list on a table without a column policy" in {
    val orders = new ColumnPolicyRewriter(
      new ColumnCatalog.MapCatalog(
        Map(
          ("acme_tpch", "tpch1", "customer") -> List("c_id", "c_email"),
          ("acme_tpch", "tpch1", "orders")   -> List("o_id", "c_id")
        )
      )
    )
    orders
      .rewrite(
        "SELECT a FROM orders AS o(a, b)",
        StatementKind.Select,
        eff(tenantUser, List(maskEmail)),
        ctx
      )
      .unsafeRunSync() shouldBe Passthrough
  }

  it should "keep the mask under a column list on a derived table or CTE" in
    List(
      "SELECT e FROM (SELECT c_email FROM customer) AS d(e)",
      "WITH d(e) AS (SELECT c_email FROM customer) SELECT e FROM d"
    ).foreach { sql =>
      withClue(sql) {
        rw.rewrite(sql, StatementKind.Select, eff(tenantUser, List(maskEmail)), ctx)
          .unsafeRunSync() match
          case Rewritten(out) =>
            out should include("'***'")
            out should not include "SELECT c_email"
          case Denied(_) | PassthroughParseFailed => succeed
          case other => fail(s"expected the mask or a denial, got $other")
      }
    }

  it should "mask the dbt self-named CTE, whose body reads the real table" in {
    // DuckDB binds the body's `customer` to the real table, the main query's to the CTE.
    rw.rewrite(
      "WITH customer AS (SELECT * FROM customer) SELECT c_email FROM customer",
      StatementKind.Select,
      eff(tenantUser, List(maskEmail)),
      ctx
    ).unsafeRunSync() match
      case Rewritten(out) =>
        out should include("'***'")
        out should not include "SELECT c_email"
      case other => fail(s"expected Rewritten, got $other")
    // a three-part name still reaches the catalog as (catalog, schema, table)
    rw.rewrite(
      "SELECT c_email FROM acme_tpch.tpch1.customer",
      StatementKind.Select,
      eff(tenantUser, List(maskEmail)),
      ctx
    ).unsafeRunSync() shouldBe Rewritten("SELECT '***' AS c_email FROM acme_tpch.tpch1.customer")
  }

  it should "deny a NATURAL join that compares a masked column against a guess list" in {
    rw.rewrite(
      "SELECT g.c_phone, c_id FROM (SELECT '555-' || lpad(unnest(range(10000))::VARCHAR, 4, '0') " +
        "AS c_phone) g NATURAL JOIN customer",
      StatementKind.Select,
      eff(tenantUser, List(maskPhone)),
      ctx
    ).unsafeRunSync() shouldBe a[Denied]
  }

  // DuckDB 2.0 reads a backtick pair as an identifier quote, and the ACL (getUnquotedName) strips
  // it: every match below used to strip only double quotes, so a backtick-quoted table or column
  // met no mask while the gate authorized the read (F07).
  private val backtickTwins = List(
    "SELECT c_id, c_email FROM `customer`" -> "SELECT c_id, c_email FROM \"customer\"",
    "SELECT c_id, c_email FROM `tpch1`.`customer`" -> "SELECT c_id, c_email FROM \"tpch1\".\"customer\"",
    "SELECT * FROM `customer`"                -> "SELECT * FROM \"customer\"",
    "SELECT `c_email` FROM customer"          -> "SELECT \"c_email\" FROM customer",
    "SELECT c.`c_email` FROM customer c"      -> "SELECT c.\"c_email\" FROM customer c",
    "SELECT `c`.c_email FROM customer AS `c`" -> "SELECT \"c\".c_email FROM customer AS \"c\"",
    "SELECT upper(`c_email`) FROM customer"   -> "SELECT upper(\"c_email\") FROM customer",
    "SELECT `c_email` || '' FROM customer"    -> "SELECT \"c_email\" || '' FROM customer",
    "SELECT CAST(`c_email` AS VARCHAR) FROM customer" -> "SELECT CAST(\"c_email\" AS VARCHAR) FROM customer",
    "SELECT c_id FROM customer WHERE `c_email` LIKE '%a%'" -> "SELECT c_id FROM customer WHERE \"c_email\" LIKE '%a%'",
    "SELECT c_id FROM customer ORDER BY `c_email`" -> "SELECT c_id FROM customer ORDER BY \"c_email\"",
    "SELECT `c` FROM customer c"          -> "SELECT \"c\" FROM customer c",
    "SELECT to_json(`c`) FROM customer c" -> "SELECT to_json(\"c\") FROM customer c",
    "SELECT (SELECT `c_email` FROM `customer` LIMIT 1)" -> "SELECT (SELECT \"c_email\" FROM \"customer\" LIMIT 1)",
    "SELECT x FROM (SELECT `c_email` AS x FROM `customer`) s" -> "SELECT x FROM (SELECT \"c_email\" AS x FROM \"customer\") s"
  )

  it should "treat a backtick-quoted table or column exactly like its double-quoted twin" in
    List(UnresolvedMode.Pass, UnresolvedMode.Deny).foreach { mode =>
      val r = new ColumnPolicyRewriter(defaultCat, unresolvedMode = mode, enabled = true)
      List(maskEmail, maskEmail.copy(action = RoleColumnPolicy.ActionDeny, transformSql = None))
        .foreach { p =>
          backtickTwins.foreach { (backtick, twin) =>
            withClue(s"$mode ${p.action}: $backtick") {
              val run = (sql: String) =>
                r.rewrite(sql, StatementKind.Select, eff(tenantUser, List(p)), ctx).unsafeRunSync()
              val out = run(backtick)
              out shouldBe run(twin)
              out should not be Passthrough
              out should not be PassthroughParseFailed
            }
          }
        }
    }

  it should "mask a backtick-quoted table and column, in a projection and in an expression" in {
    val effSet           = eff(tenantUser, List(maskEmail))
    def run(sql: String) = rw.rewrite(sql, StatementKind.Select, effSet, ctx).unsafeRunSync()
    run("SELECT c_id, c_email FROM `customer`") shouldBe
      Rewritten("SELECT c_id, '***' AS c_email FROM \"customer\"")
    run("SELECT `c_email` FROM customer") shouldBe
      Rewritten("SELECT '***' AS \"c_email\" FROM customer")
    run("SELECT upper(`c_email`) FROM customer") shouldBe
      Rewritten("SELECT upper('***') FROM customer")
    run("SELECT c_id FROM customer WHERE `c_email` LIKE '%a%'") should not be Passthrough
  }

  // ---- time travel --------------------------------------------------------------------------
  //
  // jsqlparser cannot parse DuckLake's `AT (VERSION => n)`, and takes `AT (TIMESTAMP => ...)` only
  // before an alias while DuckDB takes it only after one. The clause is stripped with the ACL
  // parser's scanner and put back on the same table reference, so a snapshot read by a column
  // policy holder is masked at that snapshot.

  private val ordersCat: ColumnCatalog =
    new ColumnCatalog.MapCatalog(
      Map(
        ("acme_tpch", "tpch1", "customer") -> List("c_id", "c_email", "c_phone", "c_ssn"),
        ("acme_tpch", "tpch1", "orders")   -> List("o_id", "o_cust", "o_note")
      )
    )

  private def masked(sql: String, rewriter: ColumnPolicyRewriter = rw): String =
    rewriter
      .rewrite(sql, StatementKind.Select, eff(tenantUser, List(maskEmail)), ctx)
      .unsafeRunSync() match
      case Rewritten(out) => out
      case other          => fail(s"expected Rewritten, got $other")

  "a time-travel read" should "mask the column and keep AT (VERSION => n) on the table" in {
    val sql = masked("SELECT c_id, c_email FROM tpch1.customer AT (VERSION => 7)")
    sql should include("'***' AS c_email")
    sql should include("FROM tpch1.customer AT (VERSION => 7)")
  }

  it should "render the clause after the alias, where DuckDB expects it" in {
    val sql = masked("SELECT c.c_email FROM customer AS c AT (VERSION => 7) WHERE c.c_id > 1")
    sql should include("'***'")
    sql should include("FROM customer AS c AT (VERSION => 7) WHERE")
  }

  it should "keep each table's own clause when several tables carry one" in {
    val sql = masked(
      "SELECT c.c_email, o.o_note FROM customer c AT (VERSION => 7) " +
        "JOIN orders o AT (VERSION => 9) ON c.c_id = o.o_cust",
      new ColumnPolicyRewriter(ordersCat, enabled = true)
    )
    sql should include("'***'")
    sql should include("customer c AT (VERSION => 7)")
    sql should include("orders o AT (VERSION => 9)")
  }

  it should "keep the clause on the base table inside a derived table, never on the derived table" in {
    val sql = masked("SELECT d.c_email FROM (SELECT c_email FROM customer AT (VERSION => 7)) d")
    sql should include("'***'")
    sql should include("FROM customer AT (VERSION => 7))")
    sql should not include ") d AT"
  }

  it should "keep the caller's spelling of the clause and an AT (TIMESTAMP => expr) form" in {
    masked("SELECT c_email FROM customer x at(version=>7)") should
      include("FROM customer x at(version=>7)")
    val ts = "AT (TIMESTAMP => TIMESTAMP '2026-01-01 10:00:00')"
    masked(s"SELECT c_email FROM customer x $ts") should include(s"FROM customer x $ts")
  }

  it should "resolve the client's positional projection under the clause" in {
    val sql = masked("SELECT #1, #2 FROM customer AT (VERSION => 7)")
    sql should include("'***' AS c_email")
    sql should include("FROM customer AT (VERSION => 7)")
  }

  it should "pass through, clause intact in the caller's text, when nothing is masked" in {
    rw.rewrite(
      "SELECT c_id FROM customer AT (VERSION => 7)",
      StatementKind.Select,
      eff(tenantUser, List(maskEmail)),
      ctx
    ).unsafeRunSync() shouldBe Passthrough
  }

  it should "fail closed when the clause follows a derived table instead of a table" in {
    rw.rewrite(
      "SELECT d.c_email FROM (SELECT c_email FROM customer) d AT (VERSION => 7)",
      StatementKind.Select,
      eff(tenantUser, List(maskEmail)),
      ctx
    ).unsafeRunSync() shouldBe PassthroughParseFailed
  }
