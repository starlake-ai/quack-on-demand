package ai.starlake.acl

import ai.starlake.acl.api.SqlContext
import ai.starlake.acl.model.*
import ai.starlake.acl.policy.{AclLoader, ResolutionMode, ResourceLookupResult}
import cats.data.Validated
import io.circe.Json
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
  * Comprehensive end-to-end integration tests that exercise the full pipeline
  * through the DatabaseAccessControl public API: SQL parsing, view resolution,
  * ACL evaluation, and result generation.
  *
  * These tests cover all Phase 5 success criteria and validate that components
  * work together correctly through the public API facade.
  */
class IntegrationTest extends AnyFunSuite with Matchers {

  // ---------------------------------------------------------------------------
  // Test fixtures
  // ---------------------------------------------------------------------------

  /**
    * Realistic ACL policy with multiple grants covering:
    * - Table-level grants (alice on orders)
    * - Schema-level grants via group (analysts group on public schema)
    * - Authorized (opaque) view grant (bob on revenue_view)
    * - Regular (transparent) view grant (charlie on revenue_view)
    * - Base table grant (charlie on transactions)
    * - Database-level grant (eve on analytics)
    */
  private val policy: AclPolicy = AclPolicy(
    grants = List(
      // Alice has access to orders table
      Grant(GrantTarget.table("analytics", "public", "orders"), List(Principal.user("alice")), authorized = false),
      // Analysts group has access to public schema
      Grant(GrantTarget.schema("analytics", "public"), List(Principal.group("analysts")), authorized = false),
      // Bob has authorized (opaque) access to revenue_view
      Grant(GrantTarget.table("analytics", "reports", "revenue_view"), List(Principal.user("bob")), authorized = true),
      // Charlie has regular (transparent) access to revenue_view
      Grant(GrantTarget.table("analytics", "reports", "revenue_view"), List(Principal.user("charlie")), authorized = false),
      // Charlie has access to the underlying base table
      Grant(GrantTarget.table("analytics", "reports", "transactions"), List(Principal.user("charlie")), authorized = false),
      // Eve has database-level access
      Grant(GrantTarget.database("analytics"), List(Principal.user("eve")), authorized = false)
    ),
    mode = ResolutionMode.Strict
  )

  // Users for testing
  private val alice = UserIdentity("alice", Set("analysts"))
  private val bob = UserIdentity("bob", Set.empty)
  private val charlie = UserIdentity("charlie", Set.empty)
  private val eve = UserIdentity("eve", Set.empty)
  private val mallory = UserIdentity("mallory", Set.empty) // no grants at all

  // Default SQL context for integration tests
  private val sqlCtx = SqlContext(Some("analytics"), Some("public"))

  /**
    * View resolution callback that classifies table references:
    * - revenue_view -> View(SELECT * FROM transactions)
    * - cycle_a -> View(SELECT * FROM cycle_b) -- creates cycle
    * - cycle_b -> View(SELECT * FROM cycle_a) -- creates cycle
    * - Everything else -> BaseTable
    */
  private val lookup: TableRef => ResourceLookupResult = {
    case ref if ref.canonical == "analytics.reports.revenue_view" =>
      ResourceLookupResult.View("SELECT * FROM analytics.reports.transactions")
    case ref if ref.canonical == "analytics.reports.cycle_a" =>
      ResourceLookupResult.View("SELECT * FROM analytics.reports.cycle_b")
    case ref if ref.canonical == "analytics.reports.cycle_b" =>
      ResourceLookupResult.View("SELECT * FROM analytics.reports.cycle_a")
    case _ => ResourceLookupResult.BaseTable
  }

  /**
    * Lookup callback that returns Unknown for specific tables (for permissive/strict mode tests).
    */
  private def unknownLookup(unknownTable: String): TableRef => ResourceLookupResult = {
    case ref if ref.table == unknownTable => ResourceLookupResult.Unknown
    case _                                => ResourceLookupResult.BaseTable
  }

  // Build the main DatabaseAccessControl instance
  private val dac: DatabaseAccessControl = DatabaseAccessControl(policy)

  // ---------------------------------------------------------------------------
  // 1. Simple allowed query
  // ---------------------------------------------------------------------------

  test("simple allowed query returns Right with isAllowed=true") {
    import DatabaseAccessControl.*

    val result = dac.authorize("SELECT * FROM orders", alice, lookup, sqlContext = sqlCtx)

    result.isRight shouldBe true
    result.isAllowed shouldBe true
    result.isDenied shouldBe false
    result.isError shouldBe false

    result.foreach { outcome =>
      outcome.result.tableAccesses should have size 1
      outcome.result.tableAccesses.head.table.canonical shouldBe "analytics.public.orders"
      outcome.result.tableAccesses.head.decision shouldBe Decision.Allowed
    }
  }

  // ---------------------------------------------------------------------------
  // 2. Denied query - no matching grant
  // ---------------------------------------------------------------------------

  test("denied query with no matching grant returns Denied with NoMatchingGrant reason") {
    import DatabaseAccessControl.*

    val result = dac.authorize("SELECT * FROM orders", mallory, lookup, sqlContext = sqlCtx)

    result.isRight shouldBe true
    result.isDenied shouldBe true
    result.isAllowed shouldBe false

    result.foreach { outcome =>
      outcome.result.deniedTables should contain(TableRef("analytics", "public", "orders"))

      val deniedAccess = outcome.result.tableAccesses.find(_.decision == Decision.Denied)
      deniedAccess shouldBe defined
      deniedAccess.get.denyReason shouldBe defined
      deniedAccess.get.denyReason.get shouldBe a[DenyReason.NoMatchingGrant]
    }
  }

  // ---------------------------------------------------------------------------
  // 3. Schema-level grant via group membership
  // ---------------------------------------------------------------------------

  test("query with schema-level grant via group membership is allowed") {
    import DatabaseAccessControl.*

    // Alice is in "analysts" group which has schema-level grant on analytics.public
    // Query a table not explicitly granted but covered by schema grant
    val result = dac.authorize("SELECT * FROM customers", alice, lookup, sqlContext = sqlCtx)

    result.isRight shouldBe true
    result.isAllowed shouldBe true

    result.foreach { outcome =>
      outcome.result.tableAccesses.head.table.canonical shouldBe "analytics.public.customers"
      outcome.result.tableAccesses.head.decision shouldBe Decision.Allowed
    }
  }

  // ---------------------------------------------------------------------------
  // 4. Database-level grant
  // ---------------------------------------------------------------------------

  test("query with database-level grant is allowed") {
    import DatabaseAccessControl.*

    // Eve has database-level grant on analytics
    val result = dac.authorize("SELECT * FROM analytics.reports.transactions", eve, lookup, sqlContext = sqlCtx)

    result.isRight shouldBe true
    result.isAllowed shouldBe true

    result.foreach { outcome =>
      outcome.result.tableAccesses.head.decision shouldBe Decision.Allowed
    }
  }

  // ---------------------------------------------------------------------------
  // 5. Authorized (opaque) view - base tables NOT checked
  // ---------------------------------------------------------------------------

  test("authorized view grant bypasses base table checks") {
    import DatabaseAccessControl.*

    // Bob has authorized (opaque) grant on revenue_view
    // The view references transactions, but bob doesn't have grant on transactions
    // With authorized=true, base tables are NOT checked
    val result = dac.authorize("SELECT * FROM analytics.reports.revenue_view", bob, lookup, sqlContext = sqlCtx)

    result.isRight shouldBe true
    result.isAllowed shouldBe true

    result.foreach { outcome =>
      // Should find the view access with Authorized grant type
      val viewAccess = outcome.result.tableAccesses.find(_.table.canonical == "analytics.reports.revenue_view")
      viewAccess shouldBe defined
      viewAccess.get.decision shouldBe Decision.Allowed
      viewAccess.get.grantType shouldBe Some(GrantType.Authorized)
    }
  }

  // ---------------------------------------------------------------------------
  // 6. Regular (transparent) view - base tables checked
  // ---------------------------------------------------------------------------

  test("transparent view with base table grant is allowed") {
    import DatabaseAccessControl.*

    // Charlie has regular (transparent) grant on revenue_view AND grant on transactions (base table)
    val result = dac.authorize("SELECT * FROM analytics.reports.revenue_view", charlie, lookup, sqlContext = sqlCtx)

    result.isRight shouldBe true
    result.isAllowed shouldBe true

    result.foreach { outcome =>
      // Should have both view and base table in accesses
      val tableRefs = outcome.result.tableAccesses.map(_.table.canonical)
      tableRefs should contain("analytics.reports.revenue_view")
      tableRefs should contain("analytics.reports.transactions")
    }
  }

  // ---------------------------------------------------------------------------
  // 7. Transparent view denied for base table
  // ---------------------------------------------------------------------------

  test("transparent view denied when user lacks base table grant") {
    import DatabaseAccessControl.*

    // Mallory has no grants at all
    val result = dac.authorize("SELECT * FROM analytics.reports.revenue_view", mallory, lookup, sqlContext = sqlCtx)

    result.isRight shouldBe true
    result.isDenied shouldBe true

    result.foreach { outcome =>
      outcome.result.deniedTables should not be empty
    }
  }

  // ---------------------------------------------------------------------------
  // 8. Circular view detection
  // ---------------------------------------------------------------------------

  test("circular view returns Denied with ViewResolutionCycle") {
    import DatabaseAccessControl.*

    // cycle_a -> cycle_b -> cycle_a
    val result = dac.authorize("SELECT * FROM analytics.reports.cycle_a", eve, lookup, sqlContext = sqlCtx)

    result.isRight shouldBe true
    result.isDenied shouldBe true

    result.foreach { outcome =>
      val cycleAccess = outcome.result.tableAccesses.find { ta =>
        ta.denyReason.exists(_.isInstanceOf[DenyReason.ViewResolutionCycle])
      }
      cycleAccess shouldBe defined
      cycleAccess.get.denyReason.get match {
        case DenyReason.ViewResolutionCycle(chain) =>
          chain should not be empty
        case other =>
          fail(s"Expected ViewResolutionCycle but got $other")
      }
    }
  }

  // ---------------------------------------------------------------------------
  // 9. Unparseable SQL
  // ---------------------------------------------------------------------------

  test("unparseable SQL returns Left(SqlParseError)") {
    import DatabaseAccessControl.*

    val result = dac.authorize("NOT VALID SQL AT ALL !!!!", alice, lookup, sqlContext = sqlCtx)

    result.isLeft shouldBe true
    result.isError shouldBe true
    result.isAllowed shouldBe false
    result.isDenied shouldBe false

    result match {
      case Left(AclError.SqlParseError(sql, detail, _)) =>
        sql shouldBe "NOT VALID SQL AT ALL !!!!"
        detail should not be empty
      case Left(other) =>
        fail(s"Expected SqlParseError but got $other")
      case Right(_) =>
        fail("Expected Left but got Right")
    }
  }

  // ---------------------------------------------------------------------------
  // 10. Non-SELECT statement
  // ---------------------------------------------------------------------------

  test("non-SELECT statement returns Left(SqlParseError) with Non-SELECT message") {
    import DatabaseAccessControl.*

    val result = dac.authorize("INSERT INTO orders VALUES (1)", alice, lookup, sqlContext = sqlCtx)

    result.isLeft shouldBe true
    result.isError shouldBe true

    result match {
      case Left(AclError.SqlParseError(_, detail, _)) =>
        detail should include("Non-SELECT")
      case Left(other) =>
        fail(s"Expected SqlParseError but got $other")
      case Right(_) =>
        fail("Expected Left but got Right")
    }
  }

  // ---------------------------------------------------------------------------
  // 11. Mixed allowed and denied tables
  // ---------------------------------------------------------------------------

  test("query with mixed allowed and denied tables returns Denied with correct per-table reasons") {
    import DatabaseAccessControl.*

    // Alice has grant on orders but not on hidden_table in secret schema
    val result = dac.authorize(
      "SELECT * FROM orders JOIN analytics.secret.hidden_table ON 1=1",
      alice,
      lookup,
      sqlContext = sqlCtx
    )

    result.isRight shouldBe true
    result.isDenied shouldBe true

    result.foreach { outcome =>
      outcome.result.tableAccesses.size shouldBe 2

      val ordersAccess = outcome.result.tableAccesses.find(_.table.canonical == "analytics.public.orders")
      ordersAccess shouldBe defined
      ordersAccess.get.decision shouldBe Decision.Allowed

      val hiddenAccess = outcome.result.tableAccesses.find(_.table.canonical == "analytics.secret.hidden_table")
      hiddenAccess shouldBe defined
      hiddenAccess.get.decision shouldBe Decision.Denied
      hiddenAccess.get.denyReason shouldBe defined
    }
  }

  // ---------------------------------------------------------------------------
  // 12. Multi-statement via authorizeAll
  // ---------------------------------------------------------------------------

  test("authorizeAll returns per-statement results") {
    val results = dac.authorizeAll(
      "SELECT * FROM orders; SELECT * FROM analytics.secret.nope",
      alice,
      lookup,
      sqlContext = sqlCtx
    )

    results should have size 2

    // First: SELECT from orders - allowed for alice
    results(0).isRight shouldBe true
    results(0) match {
      case Right(outcome) => outcome.isAllowed shouldBe true
      case Left(_)        => fail("First statement should be Right")
    }

    // Second: SELECT from secret.nope - denied for alice
    results(1).isRight shouldBe true
    results(1) match {
      case Right(outcome) => outcome.isDenied shouldBe true
      case Left(_)        => fail("Second statement should be Right (denied, not error)")
    }
  }

  // ---------------------------------------------------------------------------
  // 13. Multi-statement with parse error
  // ---------------------------------------------------------------------------

  test("authorizeAll with parse error returns mixed results") {
    val results = dac.authorizeAll(
      "SELECT * FROM orders; GARBAGE SQL!!!",
      alice,
      lookup,
      sqlContext = sqlCtx
    )

    results should have size 2

    // First: valid SELECT - allowed
    results(0).isRight shouldBe true

    // Second: garbage SQL - parse error
    results(1).isLeft shouldBe true
    results(1) match {
      case Left(AclError.SqlParseError(_, _, _)) => succeed
      case Left(other)                           => fail(s"Expected SqlParseError but got $other")
      case Right(_)                              => fail("Expected Left but got Right")
    }
  }

  // ---------------------------------------------------------------------------
  // 14. Permissive mode allows unknown tables
  // ---------------------------------------------------------------------------

  test("permissive mode allows unknown tables with UnknownButAllowed") {
    import DatabaseAccessControl.*

    // Build a permissive-mode policy
    val permissivePolicy = AclPolicy(
      grants = List(
        Grant(GrantTarget.database("analytics"), List(Principal.user("alice")), authorized = false)
      ),
      mode = ResolutionMode.Permissive
    )

    val permissiveDac = DatabaseAccessControl(permissivePolicy)

    // Use a lookup that returns Unknown for the mystery_table
    val unknownTableLookup = unknownLookup("mystery_table")

    val result = permissiveDac.authorize("SELECT * FROM mystery_table", alice, unknownTableLookup, sqlContext = sqlCtx)

    result.isRight shouldBe true
    result.isAllowed shouldBe true

    result.foreach { outcome =>
      val mysteryAccess = outcome.result.tableAccesses.find(_.table.table == "mystery_table")
      mysteryAccess shouldBe defined
      mysteryAccess.get.grantType shouldBe Some(GrantType.UnknownButAllowed)
    }
  }

  // ---------------------------------------------------------------------------
  // 15. Strict mode denies unknown tables
  // ---------------------------------------------------------------------------

  test("strict mode denies unknown tables with UnknownView reason") {
    import DatabaseAccessControl.*

    // Default policy is strict mode
    val strictPolicy = AclPolicy(
      grants = List(
        Grant(GrantTarget.database("analytics"), List(Principal.user("alice")), authorized = false)
      ),
      mode = ResolutionMode.Strict
    )

    val strictDac = DatabaseAccessControl(strictPolicy)

    // Use a lookup that returns Unknown for the mystery_table
    val unknownTableLookup = unknownLookup("mystery_table")

    val result = strictDac.authorize("SELECT * FROM mystery_table", alice, unknownTableLookup, sqlContext = sqlCtx)

    result.isRight shouldBe true
    result.isDenied shouldBe true

    result.foreach { outcome =>
      val mysteryAccess = outcome.result.tableAccesses.find(_.table.table == "mystery_table")
      mysteryAccess shouldBe defined
      mysteryAccess.get.denyReason shouldBe defined
      mysteryAccess.get.denyReason.get shouldBe a[DenyReason.UnknownView]
    }
  }

  // ---------------------------------------------------------------------------
  // 16. YAML-loaded policy integration
  // ---------------------------------------------------------------------------

  test("YAML-loaded policy works through full pipeline") {
    import DatabaseAccessControl.*

    val yaml = """
      |grants:
      |  - target: mydb.public.products
      |    principals:
      |      - user:alice
      |  - target: mydb.analytics
      |    principals:
      |      - group:analysts
      |mode: strict
      |""".stripMargin

    val policyResult = AclLoader.load(yaml)

    policyResult match {
      case Validated.Valid(loadedPolicy) =>
        val yamlDac = DatabaseAccessControl(loadedPolicy)
        val yamlCtx = SqlContext(Some("mydb"), Some("public"))

        val result = yamlDac.authorize("SELECT * FROM products", alice, _ => ResourceLookupResult.BaseTable, sqlContext = yamlCtx)

        result.isRight shouldBe true
        result.isAllowed shouldBe true

        result.foreach { outcome =>
          outcome.result.tableAccesses.head.table.canonical shouldBe "mydb.public.products"
        }

      case Validated.Invalid(errors) =>
        fail(s"Failed to load YAML policy: ${errors.toList.map(_.message).mkString(", ")}")
    }
  }

  // ---------------------------------------------------------------------------
  // 17. Timing is measured
  // ---------------------------------------------------------------------------

  test("timing is measured with non-zero values") {
    val result = dac.authorize("SELECT * FROM orders", alice, lookup, sqlContext = sqlCtx)

    result.foreach { outcome =>
      outcome.timing.parseNanos should be > 0L
      outcome.timing.totalNanos should be > 0L
      outcome.timing.parseDuration.toNanos should be > 0L
    }
  }

  // ---------------------------------------------------------------------------
  // 18. Summary and explain produce output
  // ---------------------------------------------------------------------------

  test("summary and explain produce non-empty output") {
    val allowed = dac.authorize("SELECT * FROM orders", alice, lookup, sqlContext = sqlCtx)
    val denied = dac.authorize("SELECT * FROM orders", mallory, lookup, sqlContext = sqlCtx)

    allowed.foreach { outcome =>
      outcome.summary should not be empty
      outcome.summary should startWith("ALLOWED:")

      outcome.explain should not be empty
      outcome.explain should include("Authorization Result")
      outcome.explain should include("Decision: ALLOWED")
    }

    denied.foreach { outcome =>
      outcome.summary should not be empty
      outcome.summary should startWith("DENIED:")

      outcome.explain should not be empty
      outcome.explain should include("Decision: DENIED")
    }
  }

  // ---------------------------------------------------------------------------
  // 19. toJson produces valid JSON
  // ---------------------------------------------------------------------------

  test("toJson produces valid JSON with expected fields") {
    val result = dac.authorize("SELECT * FROM orders", alice, lookup, sqlContext = sqlCtx)

    result.foreach { outcome =>
      val json = outcome.toJson

      json.hcursor.downField("decision").as[String] shouldBe Right("allowed")
      json.hcursor.downField("sql").as[String].isRight shouldBe true
      json.hcursor.downField("timing").downField("totalMs").as[Double].isRight shouldBe true
      json.hcursor.downField("result").as[Json].isRight shouldBe true
      json.hcursor.downField("user").downField("name").as[String] shouldBe Right("alice")
    }
  }

  // ---------------------------------------------------------------------------
  // 20. Extension methods on Either
  // ---------------------------------------------------------------------------

  test("extension methods work correctly on both Right and Left") {
    import DatabaseAccessControl.*

    val allowed = dac.authorize("SELECT * FROM orders", alice, lookup, sqlContext = sqlCtx)
    val denied = dac.authorize("SELECT * FROM orders", mallory, lookup, sqlContext = sqlCtx)
    val error = dac.authorize("NOT VALID SQL", alice, lookup, sqlContext = sqlCtx)

    // Right(Allowed)
    allowed.isAllowed shouldBe true
    allowed.isDenied shouldBe false
    allowed.isError shouldBe false
    allowed.outcome shouldBe defined
    allowed.error shouldBe empty

    // Right(Denied)
    denied.isAllowed shouldBe false
    denied.isDenied shouldBe true
    denied.isError shouldBe false
    denied.outcome shouldBe defined
    denied.error shouldBe empty

    // Left(error)
    error.isAllowed shouldBe false
    error.isDenied shouldBe false
    error.isError shouldBe true
    error.outcome shouldBe empty
    error.error shouldBe defined
  }

  // ---------------------------------------------------------------------------
  // 21. SqlContext variation produces different results
  // ---------------------------------------------------------------------------

  test("different SqlContext values produce different authorization outcomes") {
    // With analytics.public defaults, orders resolves correctly
    val ctx1 = SqlContext(Some("analytics"), Some("public"), "duckdb")
    val result1 = dac.authorize("SELECT * FROM orders", alice, lookup, sqlContext = ctx1)
    result1.isRight shouldBe true
    result1.foreach(o => o.isAllowed shouldBe true)

    // With different defaults, unqualified 'orders' resolves to newdb.newschema.orders -> denied
    val ctx2 = SqlContext(Some("newdb"), Some("newschema"), "duckdb")
    val result2 = dac.authorize("SELECT * FROM orders", alice, lookup, sqlContext = ctx2)
    result2.isRight shouldBe true
    result2.foreach(o => o.isDenied shouldBe true)
  }

  // ---------------------------------------------------------------------------
  // 22. withPolicy returns new instance
  // ---------------------------------------------------------------------------

  test("withPolicy returns new instance with different policy") {
    val newPolicy = AclPolicy(
      grants = List(
        Grant(GrantTarget.table("analytics", "public", "new_table"), List(Principal.user("frank")))
      ),
      mode = ResolutionMode.Permissive
    )

    val newDac = dac.withPolicy(newPolicy)

    newDac.policy.grants should have size 1
    newDac.policy.grants.head.principals.head shouldBe Principal.user("frank")
  }

  // ---------------------------------------------------------------------------
  // 23. Multiple tables in single query all allowed
  // ---------------------------------------------------------------------------

  test("multiple tables in single query all allowed") {
    import DatabaseAccessControl.*

    // Alice has schema-level access via analysts group
    val result = dac.authorize(
      "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id",
      alice,
      lookup,
      sqlContext = sqlCtx
    )

    result.isRight shouldBe true
    result.isAllowed shouldBe true

    result.foreach { outcome =>
      outcome.result.tableAccesses.size shouldBe 2
      outcome.result.tableAccesses.forall(_.decision == Decision.Allowed) shouldBe true
    }
  }

  // ---------------------------------------------------------------------------
  // 24. Trace information when requested
  // ---------------------------------------------------------------------------

  test("trace information is included when trace=true") {
    val result = dac.authorize("SELECT * FROM analytics.reports.revenue_view", charlie, lookup, trace = true, sqlContext = sqlCtx)

    result.foreach { outcome =>
      outcome.trace shouldBe defined
      outcome.trace.foreach { traceInfo =>
        traceInfo.tableAccesses should not be empty
      }

      // JSON should include trace
      val json = outcome.toJson
      json.hcursor.downField("trace").as[Json].isRight shouldBe true
    }
  }

  // ---------------------------------------------------------------------------
  // 25. Empty input returns error
  // ---------------------------------------------------------------------------

  test("empty SQL input returns error") {
    import DatabaseAccessControl.*

    val result = dac.authorize("", alice, lookup, sqlContext = sqlCtx)

    result.isLeft shouldBe true
    result.isError shouldBe true
  }

  // ---------------------------------------------------------------------------
  // 26. Subquery table references extracted
  // ---------------------------------------------------------------------------

  test("subquery table references are extracted and checked") {
    import DatabaseAccessControl.*

    val result = dac.authorize(
      "SELECT * FROM orders WHERE customer_id IN (SELECT id FROM customers)",
      alice,
      lookup,
      sqlContext = sqlCtx
    )

    result.isRight shouldBe true
    result.isAllowed shouldBe true

    result.foreach { outcome =>
      val tables = outcome.result.tableAccesses.map(_.table.table)
      tables should contain("orders")
      tables should contain("customers")
    }
  }

  // ---------------------------------------------------------------------------
  // 27. CTE table references extracted
  // ---------------------------------------------------------------------------

  test("CTE table references are extracted and checked") {
    import DatabaseAccessControl.*

    val result = dac.authorize(
      """WITH order_summary AS (SELECT * FROM orders)
        |SELECT * FROM order_summary""".stripMargin,
      alice,
      lookup,
      sqlContext = sqlCtx
    )

    result.isRight shouldBe true
    result.isAllowed shouldBe true

    result.foreach { outcome =>
      // Should see the underlying orders table
      val tables = outcome.result.tableAccesses.map(_.table.table)
      tables should contain("orders")
    }
  }

  // ---------------------------------------------------------------------------
  // 28. UNION query extracts tables from all branches
  // ---------------------------------------------------------------------------

  test("UNION query extracts tables from all branches") {
    import DatabaseAccessControl.*

    val result = dac.authorize(
      "SELECT id FROM orders UNION SELECT id FROM customers",
      alice,
      lookup,
      sqlContext = sqlCtx
    )

    result.isRight shouldBe true
    result.isAllowed shouldBe true

    result.foreach { outcome =>
      val tables = outcome.result.tableAccesses.map(_.table.table)
      tables should contain("orders")
      tables should contain("customers")
    }
  }

  // ---------------------------------------------------------------------------
  // 29. View parse error handling
  // ---------------------------------------------------------------------------

  test("view with invalid SQL returns denied with ViewParseError") {
    import DatabaseAccessControl.*

    val badViewLookup: TableRef => ResourceLookupResult = {
      case ref if ref.table == "bad_view" =>
        ResourceLookupResult.View("THIS IS NOT VALID SQL!!!")
      case _ => ResourceLookupResult.BaseTable
    }

    val result = dac.authorize("SELECT * FROM bad_view", eve, badViewLookup, sqlContext = sqlCtx)

    result.isRight shouldBe true
    result.isDenied shouldBe true

    result.foreach { outcome =>
      val badAccess = outcome.result.tableAccesses.find { ta =>
        ta.denyReason.exists(_.isInstanceOf[DenyReason.ViewParseError])
      }
      badAccess shouldBe defined
    }
  }

  // ---------------------------------------------------------------------------
  // 30. AccessResult helper methods
  // ---------------------------------------------------------------------------

  test("AccessResult helper methods work correctly") {
    val result = dac.authorize(
      "SELECT * FROM orders JOIN analytics.secret.hidden ON 1=1",
      alice,
      lookup,
      sqlContext = sqlCtx
    )

    result.foreach { outcome =>
      val accessResult = outcome.result

      accessResult.isAllowed shouldBe false
      accessResult.allowedTables.map(_.table) should contain("orders")
      accessResult.deniedTables.map(_.table) should contain("hidden")
      accessResult.allTables should have size 2
    }
  }

  // ---------------------------------------------------------------------------
  // 31. Multiple YAML files merged
  // ---------------------------------------------------------------------------

  test("multiple YAML policies can be loaded and merged") {
    val yaml1 = """
      |grants:
      |  - target: mydb.public.table1
      |    principals:
      |      - user:alice
      |mode: strict
      |""".stripMargin

    val yaml2 = """
      |grants:
      |  - target: mydb.public.table2
      |    principals:
      |      - user:bob
      |mode: permissive
      |""".stripMargin

    val mergedResult = AclLoader.loadAll(List(yaml1, yaml2))

    mergedResult match {
      case Validated.Valid(mergedPolicy) =>
        mergedPolicy.grants should have size 2
        // Mode from first document
        mergedPolicy.mode shouldBe ResolutionMode.Strict

      case Validated.Invalid(errors) =>
        fail(s"Failed to merge policies: ${errors.toList.map(_.message).mkString(", ")}")
    }
  }

  // ---------------------------------------------------------------------------
  // 32. Builder pattern with multiple policies
  // ---------------------------------------------------------------------------

  test("builder with multiple policies merges grants correctly") {
    val policy1 = AclPolicy(
      List(Grant(GrantTarget.table("db", "s1", "t1"), List(Principal.user("user1")))),
      ResolutionMode.Strict
    )
    val policy2 = AclPolicy(
      List(Grant(GrantTarget.table("db", "s2", "t2"), List(Principal.user("user2")))),
      ResolutionMode.Permissive
    )

    val builderResult = DatabaseAccessControl.builder()
      .policy(policy1)
      .policy(policy2)
      .build()

    builderResult.isRight shouldBe true
    builderResult.foreach { dac =>
      dac.policy.grants should have size 2
    }
  }

  // ---------------------------------------------------------------------------
  // 33. Error JSON serialization
  // ---------------------------------------------------------------------------

  test("AclError toJson produces valid JSON") {
    val errors = List(
      AclError.SqlParseError("SELECT *", "incomplete query"),
      AclError.ConfigError("missing database"),
      AclError.InternalError("unexpected state")
    )

    errors.foreach { err =>
      val json = err.toJson
      json.hcursor.downField("type").as[String].isRight shouldBe true
      json.hcursor.downField("detail").as[String].isRight shouldBe true
    }
  }

  // ---------------------------------------------------------------------------
  // 34. Full qualified table names work
  // ---------------------------------------------------------------------------

  test("fully qualified table names bypass default resolution") {
    import DatabaseAccessControl.*

    // Even with different defaults, fully qualified name works
    val result = dac.authorize(
      "SELECT * FROM analytics.public.orders",
      alice,
      lookup,
      sqlContext = sqlCtx
    )

    result.isRight shouldBe true
    result.isAllowed shouldBe true

    result.foreach { outcome =>
      outcome.result.tableAccesses.head.table.canonical shouldBe "analytics.public.orders"
    }
  }

  // ---------------------------------------------------------------------------
  // 35. Group membership transitive
  // ---------------------------------------------------------------------------

  test("group membership provides access through schema grant") {
    import DatabaseAccessControl.*

    // Alice is in "analysts" group
    // Policy has schema-level grant for "analysts" on analytics.public
    // Any table in that schema should be allowed
    val result = dac.authorize("SELECT * FROM any_table_in_public", alice, lookup, sqlContext = sqlCtx)

    result.isRight shouldBe true
    result.isAllowed shouldBe true
  }

  // ---------------------------------------------------------------------------
  // 36. DenyReason types are distinguishable
  // ---------------------------------------------------------------------------

  test("different deny reasons are correctly identified") {
    // NoMatchingGrant
    val noGrant = dac.authorize("SELECT * FROM orders", mallory, lookup, sqlContext = sqlCtx)
    noGrant.foreach { outcome =>
      outcome.result.tableAccesses.head.denyReason.get shouldBe a[DenyReason.NoMatchingGrant]
    }

    // ViewResolutionCycle
    val cycle = dac.authorize("SELECT * FROM analytics.reports.cycle_a", eve, lookup, sqlContext = sqlCtx)
    cycle.foreach { outcome =>
      val cycleReason = outcome.result.tableAccesses.flatMap(_.denyReason).find(_.isInstanceOf[DenyReason.ViewResolutionCycle])
      cycleReason shouldBe defined
    }

    // UnknownView (strict mode)
    val unknownTableLookup = unknownLookup("unknown_table")
    val unknown = dac.authorize("SELECT * FROM unknown_table", alice, unknownTableLookup, sqlContext = sqlCtx)
    unknown.foreach { outcome =>
      val unknownReason = outcome.result.tableAccesses.flatMap(_.denyReason).find(_.isInstanceOf[DenyReason.UnknownView])
      unknownReason shouldBe defined
    }
  }
}
