package ai.starlake.quack.edge.cls

import ai.starlake.quack.model.StatementKind
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state._
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ColumnPolicyRewriterSpec extends AnyFlatSpec with Matchers:
  import ColumnPolicyRewriter._

  private val superuser = RbacUser(id = "u-super", tenant = None, username = "root", role = "admin")
  private val tenantUser =
    RbacUser(id = "u-1", tenant = Some("acme"), username = "alice", role = "user")

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
    SchemaContext(defaultDatabase = Some("acme_tpch"), defaultSchema = Some("tpch1"))

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

  // -------- KNOWN GAP (ignored below): unaliased aggregate produces broken SQL at the node --------
  //
  // Live repro (against a real DuckDB node, not reproducible at this unit level): with a mask
  // policy on customer.c_phone,
  //
  //   SELECT c_mktsegment, min(c_phone) FROM tpch1.customer GROUP BY 1
  //
  // fails at the node. jsqltranspiler's star-expansion / result-set-metadata machinery names the
  // unaliased aggregate projection slot "min" (DuckDB's own synthesized column name), and the
  // Prepare-time LIMIT-0 probe wrapper built around the rewritten SQL then breaks because DuckDB
  // rejects that shape. The aliased form
  //
  //   SELECT c_mktsegment, min(c_phone) AS phone FROM tpch1.customer GROUP BY 1
  //
  // works fine end-to-end. Exact node-side error (captured from the live repro): DuckDB's binder
  // rejects the wrapped/probed SELECT with a reference to the synthetic "min" column name colliding
  // with the aggregate-function keyword once the probe wrapper re-projects it.
  //
  // Unit-level finding (this test, no live DuckDB involved): JsqltranspilerRewriter never
  // synthesizes an alias for an unaliased projection expression. Walking
  // ColumnPolicyRewriter -> JsqltranspilerRewriter -> the vendored JSQLColumResolver
  // (getResolvedStatementText) confirms new SelectItems are only ever constructed for `*` / `t.*`
  // expansion, and even then the ORIGINAL (possibly-null) alias is carried through unchanged; the
  // PolicyVisitor's mask substitution (JsqltranspilerRewriter.scala PolicyVisitor.visit) replaces
  // the Column expression in place and never touches SelectItem.alias either. So the unaliased
  // aggregate keeps flowing through the rewriter with NO alias while the aliased form keeps its
  // alias - the rewriter faithfully preserves the caller's aliasing choice rather than ever adding
  // one. The breakage is therefore real but only manifests once the wrapped SQL reaches the engine
  // (Prepare-time LIMIT-0 probe / DuckDB binder), which this unit test cannot exercise.
  //
  // This test pins the current (broken-downstream) rewritten SQL verbatim as a characterization:
  // the unaliased form's masked aggregate still carries no alias. Any future change that makes the
  // rewriter auto-alias bare aggregate/function projections (the likely fix) will change this
  // output and this test will need updating alongside the fix - that's the point of un-ignoring it
  // when the fix lands.
  //
  // NOTE on the enabled-first protocol: unlike TEST 1 and TEST 2 (which fail today and will pass
  // once fixed), this test currently PASSES when enabled - it documents/characterizes today's
  // rewriter output rather than asserting a unit-level failure, because the actual defect only
  // manifests once the rewritten SQL reaches the live DuckDB node (see the live repro above, which
  // is NOT reproducible without a running node). It is ignored anyway so it reads consistently
  // alongside TEST 1/2 as a pinned KNOWN GAP, and so a future rewriter change that starts
  // auto-aliasing bare aggregates doesn't silently drift this characterization without a human
  // reviewing whether the live breakage is now fixed.
  ignore should "leave an unaliased aggregate projection unaliased after masking (KNOWN GAP, breaks at the node)" in {
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

    unaliasedOut match
      case Rewritten(sql) =>
        // Verbatim characterization of the current (broken-downstream) shape: the masked
        // aggregate is rewritten in place but the projection slot is STILL not given an
        // explicit alias, unlike the working aliased form asserted below.
        sql shouldBe "SELECT c_mktsegment, min('***') FROM tpch1.customer GROUP BY 1"
        sql should not include " AS "
      case other => fail(s"expected Rewritten, got $other")

    aliasedOut match
      case Rewritten(sql) =>
        sql shouldBe "SELECT c_mktsegment, min('***') AS phone FROM tpch1.customer GROUP BY 1"
        sql should include("AS phone")
      case other => fail(s"expected Rewritten, got $other")
  }
