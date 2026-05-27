package ai.starlake.acl

import ai.starlake.acl.api.SqlContext
import ai.starlake.acl.model.*
import ai.starlake.acl.policy.{ResolutionMode, ResourceLookupResult}
import io.circe.Json
import org.scalatest.funsuite.AnyFunSuite

class DatabaseAccessControlTest extends AnyFunSuite {

  // ---------------------------------------------------------------------------
  // Test helpers
  // ---------------------------------------------------------------------------

  private def simplePolicy(
      db: String = "mydb",
      schema: String = "public",
      table: String = "orders",
      user: String = "alice"
  ): AclPolicy = {
    val grant = Grant(
      target = GrantTarget.table(db, schema, table),
      principals = List(Principal.user(user))
    )
    AclPolicy(List(grant), ResolutionMode.Strict)
  }

  private def multiTablePolicy: AclPolicy = {
    val grants = List(
      Grant(GrantTarget.table("mydb", "public", "orders"), List(Principal.user("alice"))),
      Grant(GrantTarget.table("mydb", "public", "users"), List(Principal.user("alice"))),
      Grant(GrantTarget.schema("mydb", "analytics"), List(Principal.group("analysts")))
    )
    AclPolicy(grants, ResolutionMode.Strict)
  }

  private val baseTableLookup: TableRef => ResourceLookupResult = _ => ResourceLookupResult.BaseTable

  private val alice = UserIdentity("alice", Set.empty)
  private val bob = UserIdentity("bob", Set.empty)

  // Default SQL context used by most tests
  private val sqlCtx = SqlContext(Some("mydb"), Some("public"))

  // ---------------------------------------------------------------------------
  // Builder tests
  // ---------------------------------------------------------------------------

  test("Builder with one policy builds successfully") {
    val result = DatabaseAccessControl.builder()
      .policy(simplePolicy())
      .build()

    assert(result.isRight)
  }

  test("Builder with no policy returns Left(ConfigError)") {
    val result = DatabaseAccessControl.builder()
      .build()

    assert(result.isLeft)
    result match {
      case Left(AclError.ConfigError(detail, _)) =>
        assert(detail.contains("At least one policy is required"))
      case _ =>
        fail(s"Expected ConfigError but got $result")
    }
  }

  test("Builder with multiple policies merges grants") {
    val policy1 = AclPolicy(
      List(Grant(GrantTarget.table("db1", "public", "t1"), List(Principal.user("alice")))),
      ResolutionMode.Strict
    )
    val policy2 = AclPolicy(
      List(Grant(GrantTarget.table("db2", "public", "t2"), List(Principal.user("bob")))),
      ResolutionMode.Permissive
    )

    val result = DatabaseAccessControl.builder()
      .policy(policy1)
      .policy(policy2)
      .build()

    assert(result.isRight)
    result.foreach { dac =>
      // Merged policy should have grants from both
      assert(dac.policy.grants.size == 2)
      // Mode from first policy (unless overridden)
      assert(dac.policy.mode == ResolutionMode.Strict)
    }
  }

  test("DuckDB dialect produces correct behavior via SqlContext") {
    val dac = DatabaseAccessControl.builder()
      .policy(simplePolicy())
      .build()
      .getOrElse(fail("Failed to build"))

    val duckCtx = SqlContext(Some("mydb"), Some("public"), "duckdb")
    val result = dac.authorize("SELECT * FROM orders", alice, baseTableLookup, sqlContext = duckCtx)

    assert(result.isRight)
    result.foreach { outcome =>
      assert(outcome.isAllowed)
    }
  }

  test("Generic dialect produces correct behavior via SqlContext") {
    val dac = DatabaseAccessControl.builder()
      .policy(simplePolicy())
      .build()
      .getOrElse(fail("Failed to build"))

    val genericCtx = SqlContext(Some("mydb"), Some("public"), "ansi")
    val result = dac.authorize("SELECT * FROM orders", alice, baseTableLookup, sqlContext = genericCtx)

    assert(result.isRight)
    result.foreach { outcome =>
      assert(outcome.isAllowed)
    }
  }

  test("Builder mode override takes precedence over policy mode") {
    val result = DatabaseAccessControl.builder()
      .policy(AclPolicy(List(Grant(GrantTarget.database("mydb"), List(Principal.user("alice")))), ResolutionMode.Strict))
      .mode(ResolutionMode.Permissive)
      .build()

    assert(result.isRight)
    result.foreach { dac =>
      // modeOverride is stored, not merged into policy.mode directly
      assert(dac.modeOverride.contains(ResolutionMode.Permissive))
    }
  }

  // ---------------------------------------------------------------------------
  // authorize() tests
  // ---------------------------------------------------------------------------

  test("Simple SELECT on allowed table returns Right with isAllowed=true") {
    val dac = DatabaseAccessControl.builder()
      .policy(simplePolicy())
      .build()
      .getOrElse(fail("Failed to build"))

    val result = dac.authorize("SELECT * FROM orders", alice, baseTableLookup, sqlContext = sqlCtx)

    assert(result.isRight)
    result.foreach { outcome =>
      assert(outcome.isAllowed)
      assert(!outcome.isDenied)
    }
  }

  test("SELECT on denied table returns Right with isDenied") {
    val dac = DatabaseAccessControl.builder()
      .policy(simplePolicy())
      .build()
      .getOrElse(fail("Failed to build"))

    val result = dac.authorize("SELECT * FROM orders", bob, baseTableLookup, sqlContext = sqlCtx)

    assert(result.isRight)
    result.foreach { outcome =>
      assert(outcome.isDenied)
      assert(!outcome.isAllowed)
      assert(outcome.result.deniedTables.nonEmpty)
    }
  }

  test("Unparseable SQL returns Left(SqlParseError)") {
    val dac = DatabaseAccessControl.builder()
      .policy(simplePolicy())
      .build()
      .getOrElse(fail("Failed to build"))

    val result = dac.authorize("SELECT * FROM", alice, baseTableLookup, sqlContext = sqlCtx)

    assert(result.isLeft)
    result match {
      case Left(AclError.SqlParseError(_, detail, _)) =>
        assert(detail.nonEmpty)
      case _ =>
        fail(s"Expected SqlParseError but got $result")
    }
  }

  test("Non-SELECT (INSERT) returns Left(SqlParseError)") {
    val dac = DatabaseAccessControl.builder()
      .policy(simplePolicy())
      .build()
      .getOrElse(fail("Failed to build"))

    val result = dac.authorize("INSERT INTO orders (id) VALUES (1)", alice, baseTableLookup, sqlContext = sqlCtx)

    assert(result.isLeft)
    result match {
      case Left(AclError.SqlParseError(_, detail, _)) =>
        assert(detail.contains("Non-SELECT"))
      case _ =>
        fail(s"Expected SqlParseError but got $result")
    }
  }

  test("Query with views resolves correctly") {
    val viewSql = "SELECT * FROM mydb.public.base_table"
    val viewLookup: TableRef => ResourceLookupResult = { ref =>
      if ref.table == "orders_view" then ResourceLookupResult.View(viewSql)
      else ResourceLookupResult.BaseTable
    }

    // Policy grants on both view and base table
    val policy = AclPolicy(
      List(
        Grant(GrantTarget.table("mydb", "public", "orders_view"), List(Principal.user("alice"))),
        Grant(GrantTarget.table("mydb", "public", "base_table"), List(Principal.user("alice")))
      ),
      ResolutionMode.Strict
    )

    val dac = DatabaseAccessControl.builder()
      .policy(policy)
      .build()
      .getOrElse(fail("Failed to build"))

    val result = dac.authorize("SELECT * FROM orders_view", alice, viewLookup, sqlContext = sqlCtx)

    assert(result.isRight)
    result.foreach { outcome =>
      assert(outcome.isAllowed)
      // View resolution should include the base table
      assert(outcome.result.viewResolutions.nonEmpty || outcome.result.tableAccesses.exists(_.table.table == "base_table"))
    }
  }

  // ---------------------------------------------------------------------------
  // authorizeAll() tests
  // ---------------------------------------------------------------------------

  test("Multi-statement with mixed results returns per-statement Either list") {
    val dac = DatabaseAccessControl.builder()
      .policy(simplePolicy())
      .build()
      .getOrElse(fail("Failed to build"))

    val sql = "SELECT * FROM orders; INSERT INTO orders VALUES (1); SELECT * FROM secret"
    val results = dac.authorizeAll(sql, alice, baseTableLookup, sqlContext = sqlCtx)

    assert(results.size == 3)
    // First: SELECT from allowed table
    assert(results(0).isRight)
    // Second: INSERT is non-SELECT
    assert(results(1).isLeft)
    results(1) match {
      case Left(AclError.SqlParseError(_, detail, _)) =>
        assert(detail.contains("Non-SELECT"))
      case _ =>
        fail("Expected SqlParseError for INSERT")
    }
    // Third: SELECT from denied table (alice doesn't have access to 'secret')
    assert(results(2).isRight)
    results(2).foreach { outcome =>
      assert(outcome.isDenied)
    }
  }

  test("Single statement returns list of one") {
    val dac = DatabaseAccessControl.builder()
      .policy(simplePolicy())
      .build()
      .getOrElse(fail("Failed to build"))

    val results = dac.authorizeAll("SELECT * FROM orders", alice, baseTableLookup, sqlContext = sqlCtx)

    assert(results.size == 1)
    assert(results.head.isRight)
  }

  // ---------------------------------------------------------------------------
  // Extension method tests
  // ---------------------------------------------------------------------------

  test("Right(Allowed).isAllowed == true") {
    import DatabaseAccessControl.*

    val dac = DatabaseAccessControl.builder()
      .policy(simplePolicy())
      .build()
      .getOrElse(fail("Failed to build"))

    val result = dac.authorize("SELECT * FROM orders", alice, baseTableLookup, sqlContext = sqlCtx)

    assert(result.isAllowed)
    assert(!result.isDenied)
    assert(!result.isError)
    assert(result.outcome.isDefined)
    assert(result.error.isEmpty)
  }

  test("Right(Denied).isDenied == true") {
    import DatabaseAccessControl.*

    val dac = DatabaseAccessControl.builder()
      .policy(simplePolicy())
      .build()
      .getOrElse(fail("Failed to build"))

    val result = dac.authorize("SELECT * FROM orders", bob, baseTableLookup, sqlContext = sqlCtx)

    assert(result.isDenied)
    assert(!result.isAllowed)
    assert(!result.isError)
    assert(result.outcome.isDefined)
    assert(result.error.isEmpty)
  }

  test("Left(error).isError == true") {
    import DatabaseAccessControl.*

    val dac = DatabaseAccessControl.builder()
      .policy(simplePolicy())
      .build()
      .getOrElse(fail("Failed to build"))

    val result = dac.authorize("SELECT * FROM", alice, baseTableLookup, sqlContext = sqlCtx)

    assert(result.isError)
    assert(!result.isAllowed)
    assert(!result.isDenied)
    assert(result.outcome.isEmpty)
    assert(result.error.isDefined)
  }

  test("Left(error).isAllowed == false") {
    import DatabaseAccessControl.*

    val result: Either[AclError, AuthorizationOutcome] = Left(AclError.ConfigError("test"))

    assert(!result.isAllowed)
    assert(!result.isDenied)
    assert(result.isError)
  }

  // ---------------------------------------------------------------------------
  // Timing tests
  // ---------------------------------------------------------------------------

  test("AuthorizationOutcome has non-zero timing values") {
    val dac = DatabaseAccessControl.builder()
      .policy(simplePolicy())
      .build()
      .getOrElse(fail("Failed to build"))

    val result = dac.authorize("SELECT * FROM orders", alice, baseTableLookup, sqlContext = sqlCtx)

    assert(result.isRight)
    result.foreach { outcome =>
      assert(outcome.timing.parseNanos > 0)
      assert(outcome.timing.evaluateAndResolveNanos >= 0)
      assert(outcome.timing.totalNanos > 0)
      assert(outcome.timing.parseDuration.toNanos > 0)
      assert(outcome.timing.totalDuration.toNanos > 0)
    }
  }

  test("Timing methods return correct FiniteDuration values") {
    val timing = Timing(1_000_000L, 2_000_000L)

    assert(timing.totalNanos == 3_000_000L)
    assert(timing.parseDuration.toNanos == 1_000_000L)
    assert(timing.evaluateAndResolveDuration.toNanos == 2_000_000L)
    assert(timing.totalDuration.toNanos == 3_000_000L)
  }

  // ---------------------------------------------------------------------------
  // JSON tests
  // ---------------------------------------------------------------------------

  test("AuthorizationOutcome.toJson produces valid JSON with expected fields") {
    val dac = DatabaseAccessControl.builder()
      .policy(simplePolicy())
      .build()
      .getOrElse(fail("Failed to build"))

    val result = dac.authorize("SELECT * FROM orders", alice, baseTableLookup, sqlContext = sqlCtx)

    assert(result.isRight)
    result.foreach { outcome =>
      val json = outcome.toJson

      // Check required fields exist
      assert(json.hcursor.downField("decision").as[String].isRight)
      assert(json.hcursor.downField("sql").as[String].isRight)
      assert(json.hcursor.downField("user").downField("name").as[String].isRight)
      assert(json.hcursor.downField("timing").downField("parseMs").as[Double].isRight)
      assert(json.hcursor.downField("timing").downField("totalMs").as[Double].isRight)
      assert(json.hcursor.downField("result").as[Json].isRight)

      // Check decision value
      val decision = json.hcursor.downField("decision").as[String].getOrElse("")
      assert(decision == "allowed" || decision == "denied")
    }
  }

  test("AclError.toJson produces valid JSON with type and detail fields") {
    val errors = List(
      AclError.YamlParseError("test yaml error"),
      AclError.InvalidTarget("a.b.c.d", "too many parts", 0),
      AclError.InvalidPrincipal("badprincipal", "no prefix", 0),
      AclError.UnresolvedVariable("MISSING"),
      AclError.EmptyPrincipals(0),
      AclError.EmptyPolicy(),
      AclError.InvalidMode("banana"),
      AclError.SqlParseError("SELECT *", "incomplete"),
      AclError.ConfigError("no policy"),
      AclError.ResolutionError("view not found"),
      AclError.InternalError("unexpected")
    )

    errors.foreach { err =>
      val json = err.toJson

      // Check required fields exist
      assert(json.hcursor.downField("type").as[String].isRight, s"Missing type for ${err.getClass.getSimpleName}")
      assert(json.hcursor.downField("detail").as[String].isRight, s"Missing detail for ${err.getClass.getSimpleName}")

      // Type should be camelCase
      val typeStr = json.hcursor.downField("type").as[String].getOrElse("")
      assert(typeStr.nonEmpty && typeStr.head.isLower, s"Type should be camelCase: $typeStr")
    }
  }

  test("AuthorizationOutcome with trace includes trace in JSON") {
    val dac = DatabaseAccessControl.builder()
      .policy(simplePolicy())
      .build()
      .getOrElse(fail("Failed to build"))

    val result = dac.authorize("SELECT * FROM orders", alice, baseTableLookup, trace = true, sqlContext = sqlCtx)

    assert(result.isRight)
    result.foreach { outcome =>
      assert(outcome.trace.isDefined)
      val json = outcome.toJson
      assert(json.hcursor.downField("trace").as[Json].isRight)
    }
  }

  // ---------------------------------------------------------------------------
  // Summary and explain tests
  // ---------------------------------------------------------------------------

  test("AuthorizationOutcome.summary produces one-line output") {
    val dac = DatabaseAccessControl.builder()
      .policy(simplePolicy())
      .build()
      .getOrElse(fail("Failed to build"))

    val allowed = dac.authorize("SELECT * FROM orders", alice, baseTableLookup, sqlContext = sqlCtx)
    val denied = dac.authorize("SELECT * FROM orders", bob, baseTableLookup, sqlContext = sqlCtx)

    allowed.foreach { outcome =>
      val summary = outcome.summary
      assert(summary.startsWith("ALLOWED:"))
      assert(summary.contains("mydb.public.orders"))
      assert(summary.contains("ms"))
    }

    denied.foreach { outcome =>
      val summary = outcome.summary
      assert(summary.startsWith("DENIED:"))
      assert(summary.contains("denied"))
    }
  }

  test("AuthorizationOutcome.explain produces multi-line output") {
    val dac = DatabaseAccessControl.builder()
      .policy(simplePolicy())
      .build()
      .getOrElse(fail("Failed to build"))

    val result = dac.authorize("SELECT * FROM orders", alice, baseTableLookup, sqlContext = sqlCtx)

    result.foreach { outcome =>
      val explain = outcome.explain
      assert(explain.contains("Authorization Result"))
      assert(explain.contains("Decision:"))
      assert(explain.contains("SQL:"))
      assert(explain.contains("User:"))
      assert(explain.contains("Timing"))
    }
  }

  // ---------------------------------------------------------------------------
  // withPolicy and SqlContext variation tests
  // ---------------------------------------------------------------------------

  test("authorize with different SqlContext values produces different results") {
    val dac = DatabaseAccessControl.builder()
      .policy(simplePolicy())
      .build()
      .getOrElse(fail("Failed to build"))

    // With matching defaults -> allowed
    val ctx1 = SqlContext(Some("mydb"), Some("public"), "duckdb")
    val result1 = dac.authorize("SELECT * FROM orders", alice, baseTableLookup, sqlContext = ctx1)
    assert(result1.isRight)
    result1.foreach(o => assert(o.isAllowed))

    // With different database -> denied (unqualified 'orders' resolves to newdb.newschema.orders)
    val ctx2 = SqlContext(Some("newdb"), Some("newschema"), "duckdb")
    val result2 = dac.authorize("SELECT * FROM orders", alice, baseTableLookup, sqlContext = ctx2)
    assert(result2.isRight)
    result2.foreach(o => assert(o.isDenied))
  }

  test("withPolicy returns new instance with replaced policy") {
    val dac = DatabaseAccessControl.builder()
      .policy(simplePolicy())
      .build()
      .getOrElse(fail("Failed to build"))

    val newPolicy = multiTablePolicy
    val newDac = dac.withPolicy(newPolicy)

    assert(newDac.policy.grants.size == 3)
  }

  // ---------------------------------------------------------------------------
  // Direct constructor test
  // ---------------------------------------------------------------------------

  test("DatabaseAccessControl.apply direct constructor works") {
    val policy = simplePolicy()

    val dac = DatabaseAccessControl(policy)

    assert(dac.policy.grants.size == 1)
    assert(dac.modeOverride.isEmpty)

    // Verify it works with SqlContext
    val result = dac.authorize("SELECT * FROM orders", alice, baseTableLookup, sqlContext = sqlCtx)
    assert(result.isRight)
    result.foreach(o => assert(o.isAllowed))
  }

  // ---------------------------------------------------------------------------
  // Edge cases
  // ---------------------------------------------------------------------------

  test("Empty SQL returns error") {
    val dac = DatabaseAccessControl.builder()
      .policy(simplePolicy())
      .build()
      .getOrElse(fail("Failed to build"))

    val result = dac.authorize("", alice, baseTableLookup, sqlContext = sqlCtx)

    assert(result.isLeft)
  }

  test("Whitespace-only SQL returns error") {
    val dac = DatabaseAccessControl.builder()
      .policy(simplePolicy())
      .build()
      .getOrElse(fail("Failed to build"))

    val result = dac.authorize("   \n\t  ", alice, baseTableLookup, sqlContext = sqlCtx)

    assert(result.isLeft)
  }

  test("Schema-level grant allows table access") {
    val policy = AclPolicy(
      List(Grant(GrantTarget.schema("mydb", "public"), List(Principal.user("alice")))),
      ResolutionMode.Strict
    )

    val dac = DatabaseAccessControl.builder()
      .policy(policy)
      .build()
      .getOrElse(fail("Failed to build"))

    val result = dac.authorize("SELECT * FROM orders", alice, baseTableLookup, sqlContext = sqlCtx)

    assert(result.isRight)
    result.foreach { outcome =>
      assert(outcome.isAllowed)
    }
  }
}
