package ai.starlake.acl.policy

import ai.starlake.acl.model.*
import cats.data.Validated
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class AclEvaluatorTest extends AnyFunSuite with Matchers {

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private def policy(grants: Grant*): AclPolicy = AclPolicy(grants.toList)

  private def tableRef(db: String, schema: String, table: String): TableRef =
    TableRef(db, schema, table)

  private def userOf(name: String, groups: String*): UserIdentity =
    UserIdentity(name, groups.toSet)

  private def grant(target: GrantTarget, principals: Principal*): Grant =
    Grant(target, principals.toList)

  private val sql = "SELECT * FROM t"

  // ---------------------------------------------------------------------------
  // Direct user grants
  // ---------------------------------------------------------------------------

  test("user with exact table-level grant is Allowed") {
    val p = policy(grant(GrantTarget.table("mydb", "public", "orders"), Principal.user("alice")))
    val result = AclEvaluator.evaluate(Set(tableRef("mydb", "public", "orders")), userOf("alice"), p, sql)
    result.decision shouldBe Decision.Allowed
    result.isAllowed shouldBe true
    result.tableAccesses should have size 1
    result.tableAccesses.head.decision shouldBe Decision.Allowed
    result.tableAccesses.head.denyReason shouldBe None
  }

  test("user with schema-level grant accessing table in that schema is Allowed") {
    val p = policy(grant(GrantTarget.schema("mydb", "public"), Principal.user("bob")))
    val result = AclEvaluator.evaluate(Set(tableRef("mydb", "public", "orders")), userOf("bob"), p, sql)
    result.decision shouldBe Decision.Allowed
  }

  test("user with database-level grant accessing any table is Allowed") {
    val p = policy(grant(GrantTarget.database("mydb"), Principal.user("carol")))
    val result = AclEvaluator.evaluate(Set(tableRef("mydb", "public", "orders")), userOf("carol"), p, sql)
    result.decision shouldBe Decision.Allowed
  }

  test("user with no matching grant is Denied with NoMatchingGrant reason") {
    val p = policy(grant(GrantTarget.table("mydb", "public", "orders"), Principal.user("alice")))
    val table = tableRef("mydb", "public", "orders")
    val user = userOf("bob")
    val result = AclEvaluator.evaluate(Set(table), user, p, sql)
    result.decision shouldBe Decision.Denied
    result.isAllowed shouldBe false
    result.tableAccesses.head.decision shouldBe Decision.Denied
    result.tableAccesses.head.denyReason shouldBe Some(DenyReason.NoMatchingGrant(table, user))
  }

  test("user with grant on different table is Denied") {
    val p = policy(grant(GrantTarget.table("mydb", "public", "users"), Principal.user("alice")))
    val result = AclEvaluator.evaluate(Set(tableRef("mydb", "public", "orders")), userOf("alice"), p, sql)
    result.decision shouldBe Decision.Denied
  }

  test("user with grant on different schema is Denied") {
    val p = policy(grant(GrantTarget.schema("mydb", "private"), Principal.user("alice")))
    val result = AclEvaluator.evaluate(Set(tableRef("mydb", "public", "orders")), userOf("alice"), p, sql)
    result.decision shouldBe Decision.Denied
  }

  test("user with grant on different database is Denied") {
    val p = policy(grant(GrantTarget.database("otherdb"), Principal.user("alice")))
    val result = AclEvaluator.evaluate(Set(tableRef("mydb", "public", "orders")), userOf("alice"), p, sql)
    result.decision shouldBe Decision.Denied
  }

  // ---------------------------------------------------------------------------
  // Group grants
  // ---------------------------------------------------------------------------

  test("user in group that has table-level grant is Allowed") {
    val p = policy(grant(GrantTarget.table("mydb", "public", "orders"), Principal.group("analysts")))
    val result = AclEvaluator.evaluate(Set(tableRef("mydb", "public", "orders")), userOf("alice", "analysts"), p, sql)
    result.decision shouldBe Decision.Allowed
  }

  test("user in group that has schema-level grant is Allowed") {
    val p = policy(grant(GrantTarget.schema("mydb", "public"), Principal.group("analysts")))
    val result = AclEvaluator.evaluate(Set(tableRef("mydb", "public", "orders")), userOf("alice", "analysts"), p, sql)
    result.decision shouldBe Decision.Allowed
  }

  test("user in group that has database-level grant is Allowed") {
    val p = policy(grant(GrantTarget.database("mydb"), Principal.group("admins")))
    val result = AclEvaluator.evaluate(Set(tableRef("mydb", "public", "orders")), userOf("alice", "admins"), p, sql)
    result.decision shouldBe Decision.Allowed
  }

  test("user not in any granted group is Denied") {
    val p = policy(grant(GrantTarget.table("mydb", "public", "orders"), Principal.group("analysts")))
    val result = AclEvaluator.evaluate(Set(tableRef("mydb", "public", "orders")), userOf("bob", "devs"), p, sql)
    result.decision shouldBe Decision.Denied
  }

  // ---------------------------------------------------------------------------
  // Multiple groups (union semantics)
  // ---------------------------------------------------------------------------

  test("user in group A (grants table X) and group B (grants table Y) -- both Allowed") {
    val p = policy(
      grant(GrantTarget.table("mydb", "public", "orders"), Principal.group("sales")),
      grant(GrantTarget.table("mydb", "public", "products"), Principal.group("inventory"))
    )
    val result = AclEvaluator.evaluate(
      Set(tableRef("mydb", "public", "orders"), tableRef("mydb", "public", "products")),
      userOf("alice", "sales", "inventory"),
      p,
      sql
    )
    result.decision shouldBe Decision.Allowed
    result.allowedTables should have size 2
  }

  test("user with direct grant on X and group grant on Y -- both Allowed") {
    val p = policy(
      grant(GrantTarget.table("mydb", "public", "orders"), Principal.user("alice")),
      grant(GrantTarget.table("mydb", "public", "products"), Principal.group("inventory"))
    )
    val result = AclEvaluator.evaluate(
      Set(tableRef("mydb", "public", "orders"), tableRef("mydb", "public", "products")),
      userOf("alice", "inventory"),
      p,
      sql
    )
    result.decision shouldBe Decision.Allowed
  }

  test("user in group with grant + direct grant on same table -- Allowed (no conflict)") {
    val p = policy(
      grant(GrantTarget.table("mydb", "public", "orders"), Principal.user("alice"), Principal.group("analysts"))
    )
    val result = AclEvaluator.evaluate(
      Set(tableRef("mydb", "public", "orders")),
      userOf("alice", "analysts"),
      p,
      sql
    )
    result.decision shouldBe Decision.Allowed
  }

  // ---------------------------------------------------------------------------
  // Hierarchy cascading
  // ---------------------------------------------------------------------------

  test("database grant covers all schemas: mydb grant allows mydb.any_schema.any_table") {
    val p = policy(grant(GrantTarget.database("mydb"), Principal.user("alice")))
    val result = AclEvaluator.evaluate(
      Set(
        tableRef("mydb", "public", "orders"),
        tableRef("mydb", "private", "secrets"),
        tableRef("mydb", "analytics", "events")
      ),
      userOf("alice"),
      p,
      sql
    )
    result.decision shouldBe Decision.Allowed
    result.allowedTables should have size 3
  }

  test("schema grant covers all tables: mydb.public grant allows mydb.public.any_table") {
    val p = policy(grant(GrantTarget.schema("mydb", "public"), Principal.user("alice")))
    val result = AclEvaluator.evaluate(
      Set(tableRef("mydb", "public", "orders"), tableRef("mydb", "public", "users")),
      userOf("alice"),
      p,
      sql
    )
    result.decision shouldBe Decision.Allowed
    result.allowedTables should have size 2
  }

  test("schema grant does NOT cover different schema") {
    val p = policy(grant(GrantTarget.schema("mydb", "public"), Principal.user("alice")))
    val result = AclEvaluator.evaluate(
      Set(tableRef("mydb", "private", "orders")),
      userOf("alice"),
      p,
      sql
    )
    result.decision shouldBe Decision.Denied
  }

  test("database grant does NOT cover different database") {
    val p = policy(grant(GrantTarget.database("mydb"), Principal.user("alice")))
    val result = AclEvaluator.evaluate(
      Set(tableRef("otherdb", "public", "orders")),
      userOf("alice"),
      p,
      sql
    )
    result.decision shouldBe Decision.Denied
  }

  // ---------------------------------------------------------------------------
  // Case insensitivity
  // ---------------------------------------------------------------------------

  test("grant on lowercase matches TableRef with mixed case (both normalize)") {
    val p = policy(grant(GrantTarget.table("mydb", "public", "orders"), Principal.user("alice")))
    val result = AclEvaluator.evaluate(
      Set(tableRef("MyDB", "Public", "ORDERS")),
      userOf("Alice"),
      p,
      sql
    )
    result.decision shouldBe Decision.Allowed
  }

  test("group names are case-insensitive") {
    val p = policy(grant(GrantTarget.table("mydb", "public", "orders"), Principal.group("analysts")))
    val result = AclEvaluator.evaluate(
      Set(tableRef("mydb", "public", "orders")),
      userOf("alice", "Analysts"),
      p,
      sql
    )
    result.decision shouldBe Decision.Allowed
  }

  // ---------------------------------------------------------------------------
  // Multiple tables evaluation
  // ---------------------------------------------------------------------------

  test("all tables allowed -> overall Allowed, all TableAccess entries are Allowed") {
    val p = policy(
      grant(GrantTarget.table("mydb", "public", "orders"), Principal.user("alice")),
      grant(GrantTarget.table("mydb", "public", "users"), Principal.user("alice"))
    )
    val result = AclEvaluator.evaluate(
      Set(tableRef("mydb", "public", "orders"), tableRef("mydb", "public", "users")),
      userOf("alice"),
      p,
      sql
    )
    result.decision shouldBe Decision.Allowed
    result.tableAccesses.foreach(_.decision shouldBe Decision.Allowed)
    result.deniedTables shouldBe empty
    result.allowedTables should have size 2
  }

  test("one table denied -> overall Denied, denied table has NoMatchingGrant, allowed tables still Allowed") {
    val p = policy(
      grant(GrantTarget.table("mydb", "public", "orders"), Principal.user("alice"))
      // No grant for mydb.public.users
    )
    val deniedTable = tableRef("mydb", "public", "users")
    val user = userOf("alice")
    val result = AclEvaluator.evaluate(
      Set(tableRef("mydb", "public", "orders"), deniedTable),
      user,
      p,
      sql
    )
    result.decision shouldBe Decision.Denied
    result.allowedTables should have size 1
    result.deniedTables should have size 1
    result.deniedTables.head shouldBe deniedTable
    val deniedAccess = result.tableAccesses.find(_.table == deniedTable).get
    deniedAccess.denyReason shouldBe Some(DenyReason.NoMatchingGrant(deniedTable, user))
  }

  test("all tables denied -> overall Denied, each has its own NoMatchingGrant reason") {
    val p = policy(grant(GrantTarget.table("mydb", "public", "other"), Principal.user("nobody")))
    val t1 = tableRef("mydb", "public", "orders")
    val t2 = tableRef("mydb", "public", "users")
    val user = userOf("alice")
    val result = AclEvaluator.evaluate(Set(t1, t2), user, p, sql)
    result.decision shouldBe Decision.Denied
    result.deniedTables should have size 2
    result.tableAccesses.foreach { ta =>
      ta.decision shouldBe Decision.Denied
      ta.denyReason shouldBe Some(DenyReason.NoMatchingGrant(ta.table, user))
    }
  }

  test("empty table set -> overall Allowed (vacuously true), empty tableAccesses") {
    val p = policy(grant(GrantTarget.database("mydb"), Principal.user("alice")))
    val result = AclEvaluator.evaluate(Set.empty, userOf("alice"), p, sql)
    result.decision shouldBe Decision.Allowed
    result.tableAccesses shouldBe empty
    result.isAllowed shouldBe true
  }

  // ---------------------------------------------------------------------------
  // AccessResult properties
  // ---------------------------------------------------------------------------

  test("isAllowed returns true when Allowed, false when Denied") {
    val p = policy(grant(GrantTarget.table("mydb", "public", "orders"), Principal.user("alice")))
    val allowed = AclEvaluator.evaluate(Set(tableRef("mydb", "public", "orders")), userOf("alice"), p, sql)
    allowed.isAllowed shouldBe true

    val denied = AclEvaluator.evaluate(Set(tableRef("mydb", "public", "orders")), userOf("bob"), p, sql)
    denied.isAllowed shouldBe false
  }

  test("deniedTables returns only denied tables") {
    val p = policy(
      grant(GrantTarget.table("mydb", "public", "orders"), Principal.user("alice"))
    )
    val result = AclEvaluator.evaluate(
      Set(tableRef("mydb", "public", "orders"), tableRef("mydb", "public", "users")),
      userOf("alice"),
      p,
      sql
    )
    result.deniedTables should have size 1
    result.deniedTables.head shouldBe tableRef("mydb", "public", "users")
  }

  test("allowedTables returns only allowed tables") {
    val p = policy(
      grant(GrantTarget.table("mydb", "public", "orders"), Principal.user("alice"))
    )
    val result = AclEvaluator.evaluate(
      Set(tableRef("mydb", "public", "orders"), tableRef("mydb", "public", "users")),
      userOf("alice"),
      p,
      sql
    )
    result.allowedTables should have size 1
    result.allowedTables.head shouldBe tableRef("mydb", "public", "orders")
  }

  test("allTables returns all tables regardless of decision") {
    val p = policy(
      grant(GrantTarget.table("mydb", "public", "orders"), Principal.user("alice"))
    )
    val result = AclEvaluator.evaluate(
      Set(tableRef("mydb", "public", "orders"), tableRef("mydb", "public", "users")),
      userOf("alice"),
      p,
      sql
    )
    result.allTables should have size 2
  }

  test("viewResolutions is empty (Phase 4 concern)") {
    val p = policy(grant(GrantTarget.table("mydb", "public", "orders"), Principal.user("alice")))
    val result = AclEvaluator.evaluate(Set(tableRef("mydb", "public", "orders")), userOf("alice"), p, sql)
    result.viewResolutions shouldBe empty
  }

  test("sql contains the original SQL string passed in") {
    val originalSql = "SELECT id, name FROM mydb.public.orders WHERE id > 10"
    val p = policy(grant(GrantTarget.table("mydb", "public", "orders"), Principal.user("alice")))
    val result = AclEvaluator.evaluate(Set(tableRef("mydb", "public", "orders")), userOf("alice"), p, originalSql)
    result.sql shouldBe originalSql
  }

  test("user contains the UserIdentity passed in") {
    val user = userOf("alice", "analysts", "admins")
    val p = policy(grant(GrantTarget.table("mydb", "public", "orders"), Principal.user("alice")))
    val result = AclEvaluator.evaluate(Set(tableRef("mydb", "public", "orders")), user, p, sql)
    result.user shouldBe user
  }

  // ---------------------------------------------------------------------------
  // user:X and group:X are distinct principals
  // ---------------------------------------------------------------------------

  test("user:alice and group:alice are distinct principals") {
    val p = policy(grant(GrantTarget.table("mydb", "public", "orders"), Principal.group("alice")))
    // User alice (without group alice) should be denied
    val result = AclEvaluator.evaluate(
      Set(tableRef("mydb", "public", "orders")),
      userOf("alice"),
      p,
      sql
    )
    result.decision shouldBe Decision.Denied
  }

  test("user:alice and group:alice are distinct -- user in group alice is Allowed") {
    val p = policy(grant(GrantTarget.table("mydb", "public", "orders"), Principal.group("alice")))
    // User bob who is in group alice should be allowed
    val result = AclEvaluator.evaluate(
      Set(tableRef("mydb", "public", "orders")),
      userOf("bob", "alice"),
      p,
      sql
    )
    result.decision shouldBe Decision.Allowed
  }

  // ---------------------------------------------------------------------------
  // Integration-style tests (YAML -> AclLoader -> AclEvaluator)
  // ---------------------------------------------------------------------------

  private val noEnv: String => Option[String] = _ => None

  test("integration: YAML -> load -> evaluate -> AccessResult (allowed)") {
    val yaml =
      """grants:
        |  - target: mydb.public.orders
        |    principals: [user:alice, group:analysts]
        |""".stripMargin

    val policyResult = AclLoader.loadWithEnv(yaml, noEnv)
    policyResult shouldBe a[Validated.Valid[?]]

    val p = policyResult.getOrElse(fail("Expected valid policy"))
    val result = AclEvaluator.evaluate(
      Set(tableRef("mydb", "public", "orders")),
      userOf("alice"),
      p,
      "SELECT * FROM mydb.public.orders"
    )
    result.decision shouldBe Decision.Allowed
    result.isAllowed shouldBe true
  }

  test("integration: YAML -> load -> evaluate -> AccessResult (denied)") {
    val yaml =
      """grants:
        |  - target: mydb.public.orders
        |    principals: [user:alice]
        |""".stripMargin

    val p = AclLoader.loadWithEnv(yaml, noEnv).getOrElse(fail("Expected valid policy"))
    val result = AclEvaluator.evaluate(
      Set(tableRef("mydb", "public", "orders")),
      userOf("bob"),
      p,
      "SELECT * FROM mydb.public.orders"
    )
    result.decision shouldBe Decision.Denied
  }

  test("integration: realistic multi-grant YAML with database, schema, and table levels") {
    val yaml =
      """grants:
        |  - target: analytics
        |    principals: [group:admins]
        |  - target: analytics.reporting
        |    principals: [group:data_team, user:carol]
        |  - target: analytics.raw.events
        |    principals: [user:dave]
        |  - target: sales.public.orders
        |    principals: [group:sales_team]
        |""".stripMargin

    val p = AclLoader.loadWithEnv(yaml, noEnv).getOrElse(fail("Expected valid policy"))

    // Admin user (group:admins) can access anything in analytics database
    val adminResult = AclEvaluator.evaluate(
      Set(tableRef("analytics", "raw", "events"), tableRef("analytics", "reporting", "dashboard")),
      userOf("admin_user", "admins"),
      p,
      sql
    )
    adminResult.decision shouldBe Decision.Allowed
    adminResult.allowedTables should have size 2

    // Carol (direct user grant on analytics.reporting) can access reporting tables
    val carolResult = AclEvaluator.evaluate(
      Set(tableRef("analytics", "reporting", "dashboard")),
      userOf("carol"),
      p,
      sql
    )
    carolResult.decision shouldBe Decision.Allowed

    // Carol cannot access analytics.raw (no schema/db grant, only reporting schema)
    val carolDenied = AclEvaluator.evaluate(
      Set(tableRef("analytics", "raw", "events")),
      userOf("carol"),
      p,
      sql
    )
    carolDenied.decision shouldBe Decision.Denied

    // Dave has table-level grant on analytics.raw.events only
    val daveResult = AclEvaluator.evaluate(
      Set(tableRef("analytics", "raw", "events")),
      userOf("dave"),
      p,
      sql
    )
    daveResult.decision shouldBe Decision.Allowed

    // Dave cannot access analytics.raw.other_table
    val daveDenied = AclEvaluator.evaluate(
      Set(tableRef("analytics", "raw", "other_table")),
      userOf("dave"),
      p,
      sql
    )
    daveDenied.decision shouldBe Decision.Denied

    // Sales team member can access sales.public.orders
    val salesResult = AclEvaluator.evaluate(
      Set(tableRef("sales", "public", "orders")),
      userOf("sales_person", "sales_team"),
      p,
      sql
    )
    salesResult.decision shouldBe Decision.Allowed

    // Sales team member cannot access analytics (different database, no grant)
    val salesCrossDenied = AclEvaluator.evaluate(
      Set(tableRef("analytics", "reporting", "dashboard")),
      userOf("sales_person", "sales_team"),
      p,
      sql
    )
    salesCrossDenied.decision shouldBe Decision.Denied
  }

  test("integration: mixed allowed/denied across databases from YAML") {
    val yaml =
      """grants:
        |  - target: db1.public.orders
        |    principals: [user:alice]
        |  - target: db2.public.users
        |    principals: [group:team]
        |""".stripMargin

    val p = AclLoader.loadWithEnv(yaml, noEnv).getOrElse(fail("Expected valid policy"))

    // Alice has direct grant on db1, is also in team (db2 grant)
    val result = AclEvaluator.evaluate(
      Set(tableRef("db1", "public", "orders"), tableRef("db2", "public", "users")),
      userOf("alice", "team"),
      p,
      sql
    )
    result.decision shouldBe Decision.Allowed
    result.allowedTables should have size 2

    // Alice without group team: only db1 allowed, db2 denied
    val partialResult = AclEvaluator.evaluate(
      Set(tableRef("db1", "public", "orders"), tableRef("db2", "public", "users")),
      userOf("alice"),
      p,
      sql
    )
    partialResult.decision shouldBe Decision.Denied
    partialResult.allowedTables should have size 1
    partialResult.deniedTables should have size 1
  }

  // ---------------------------------------------------------------------------
  // View-aware evaluation (evaluateWithViews)
  // ---------------------------------------------------------------------------

  private val testConfig = Config.forGeneric("testdb", "testsch")

  private def viewGrant(db: String, schema: String, table: String, authorized: Boolean, principals: Principal*): Grant =
    Grant(GrantTarget.table(db, schema, table), principals.toList, authorized = authorized)

  private def regularGrant(db: String, schema: String, table: String, principals: Principal*): Grant =
    Grant(GrantTarget.table(db, schema, table), principals.toList)

  private def schemaGrant(db: String, schema: String, authorized: Boolean, principals: Principal*): Grant =
    Grant(GrantTarget.schema(db, schema), principals.toList, authorized = authorized)

  private def policyWithMode(mode: ResolutionMode, grants: Grant*): AclPolicy =
    AclPolicy(grants.toList, mode)

  test("view-aware: simple view with regular grants on view and base tables") {
    val t1 = tableRef("testdb", "testsch", "t1")
    val t2 = tableRef("testdb", "testsch", "t2")
    val v1 = tableRef("testdb", "testsch", "v1")

    val lookup: TableRef => ResourceLookupResult = {
      case ref if ref == v1 => ResourceLookupResult.View("SELECT * FROM testdb.testsch.t1 JOIN testdb.testsch.t2 ON t1.id = t2.id")
      case ref if ref == t1 => ResourceLookupResult.BaseTable
      case ref if ref == t2 => ResourceLookupResult.BaseTable
      case _                => ResourceLookupResult.Unknown
    }

    val p = policyWithMode(
      ResolutionMode.Strict,
      regularGrant("testdb", "testsch", "v1", Principal.user("alice")),
      regularGrant("testdb", "testsch", "t1", Principal.user("alice")),
      regularGrant("testdb", "testsch", "t2", Principal.user("alice"))
    )

    val result = AclEvaluator.evaluateWithViews(Set(v1), userOf("alice"), p, sql, testConfig, lookup)
    result.decision shouldBe Decision.Allowed
    result.tableAccesses should have size 3
    result.tableAccesses.map(_.table).toSet shouldBe Set(v1, t1, t2)
  }

  test("view-aware: view denied -- no grant on view itself") {
    val t1 = tableRef("testdb", "testsch", "t1")
    val v1 = tableRef("testdb", "testsch", "v1")

    val lookup: TableRef => ResourceLookupResult = {
      case ref if ref == v1 => ResourceLookupResult.View("SELECT * FROM testdb.testsch.t1")
      case ref if ref == t1 => ResourceLookupResult.BaseTable
      case _                => ResourceLookupResult.Unknown
    }

    // Grant on t1 but NOT on v1
    val p = policyWithMode(ResolutionMode.Strict, regularGrant("testdb", "testsch", "t1", Principal.user("alice")))

    val result = AclEvaluator.evaluateWithViews(Set(v1), userOf("alice"), p, sql, testConfig, lookup)
    result.decision shouldBe Decision.Denied
    // Only the view should appear (base tables not checked when view is denied)
    val viewAccess = result.tableAccesses.find(_.table == v1).get
    viewAccess.decision shouldBe Decision.Denied
    viewAccess.denyReason shouldBe Some(DenyReason.NoMatchingGrant(v1, userOf("alice")))
  }

  test("view-aware: view denied -- no grant on base table") {
    val t1 = tableRef("testdb", "testsch", "t1")
    val t2 = tableRef("testdb", "testsch", "t2")
    val v1 = tableRef("testdb", "testsch", "v1")

    val lookup: TableRef => ResourceLookupResult = {
      case ref if ref == v1 => ResourceLookupResult.View("SELECT * FROM testdb.testsch.t1 JOIN testdb.testsch.t2 ON t1.id = t2.id")
      case ref if ref == t1 => ResourceLookupResult.BaseTable
      case ref if ref == t2 => ResourceLookupResult.BaseTable
      case _                => ResourceLookupResult.Unknown
    }

    // Grant on v1 and t1 but NOT on t2
    val p = policyWithMode(
      ResolutionMode.Strict,
      regularGrant("testdb", "testsch", "v1", Principal.user("alice")),
      regularGrant("testdb", "testsch", "t1", Principal.user("alice"))
    )

    val result = AclEvaluator.evaluateWithViews(Set(v1), userOf("alice"), p, sql, testConfig, lookup)
    result.decision shouldBe Decision.Denied
    val t2Access = result.tableAccesses.find(_.table == t2).get
    t2Access.decision shouldBe Decision.Denied
    t2Access.denyReason shouldBe Some(DenyReason.NoMatchingGrant(t2, userOf("alice")))
  }

  test("view-aware: authorized view -- base tables bypassed") {
    val t1 = tableRef("testdb", "testsch", "t1")
    val t2 = tableRef("testdb", "testsch", "t2")
    val v1 = tableRef("testdb", "testsch", "v1")

    val lookup: TableRef => ResourceLookupResult = {
      case ref if ref == v1 => ResourceLookupResult.View("SELECT * FROM testdb.testsch.t1 JOIN testdb.testsch.t2 ON t1.id = t2.id")
      case ref if ref == t1 => ResourceLookupResult.BaseTable
      case ref if ref == t2 => ResourceLookupResult.BaseTable
      case _                => ResourceLookupResult.Unknown
    }

    // Authorized grant on v1, NO grants on t1, t2
    val p = policyWithMode(
      ResolutionMode.Strict,
      viewGrant("testdb", "testsch", "v1", authorized = true, Principal.user("alice"))
    )

    val result = AclEvaluator.evaluateWithViews(Set(v1), userOf("alice"), p, sql, testConfig, lookup)
    result.decision shouldBe Decision.Allowed
    // Only v1 should appear in accesses (base tables bypassed)
    result.tableAccesses should have size 1
    val viewAccess = result.tableAccesses.head
    viewAccess.table shouldBe v1
    viewAccess.grantType shouldBe Some(GrantType.Authorized)
    viewAccess.isView shouldBe true
  }

  test("view-aware: authorized per-grant -- same view, different users") {
    val t1 = tableRef("testdb", "testsch", "t1")
    val v1 = tableRef("testdb", "testsch", "v1")

    val lookup: TableRef => ResourceLookupResult = {
      case ref if ref == v1 => ResourceLookupResult.View("SELECT * FROM testdb.testsch.t1")
      case ref if ref == t1 => ResourceLookupResult.BaseTable
      case _                => ResourceLookupResult.Unknown
    }

    // User A has authorized grant on v1 (opaque)
    // User B has regular grant on v1 (transparent)
    val p = policyWithMode(
      ResolutionMode.Strict,
      viewGrant("testdb", "testsch", "v1", authorized = true, Principal.user("usera")),
      viewGrant("testdb", "testsch", "v1", authorized = false, Principal.user("userb")),
      regularGrant("testdb", "testsch", "t1", Principal.user("userb"))
    )

    // User A: authorized grant -> opaque, no t1 grant needed
    val resultA = AclEvaluator.evaluateWithViews(Set(v1), userOf("usera"), p, sql, testConfig, lookup)
    resultA.decision shouldBe Decision.Allowed
    resultA.tableAccesses should have size 1

    // User B without t1 grant: denied
    val pNoT1 = policyWithMode(
      ResolutionMode.Strict,
      viewGrant("testdb", "testsch", "v1", authorized = true, Principal.user("usera")),
      viewGrant("testdb", "testsch", "v1", authorized = false, Principal.user("userb"))
    )
    val resultBDenied = AclEvaluator.evaluateWithViews(Set(v1), userOf("userb"), pNoT1, sql, testConfig, lookup)
    resultBDenied.decision shouldBe Decision.Denied

    // User B with t1 grant: allowed
    val resultB = AclEvaluator.evaluateWithViews(Set(v1), userOf("userb"), p, sql, testConfig, lookup)
    resultB.decision shouldBe Decision.Allowed
    resultB.tableAccesses should have size 2
  }

  test("view-aware: schema-level grant is always transparent regardless of authorized flag") {
    val t1 = tableRef("testdb", "testsch", "t1")
    val v1 = tableRef("testdb", "testsch", "v1")

    val lookup: TableRef => ResourceLookupResult = {
      case ref if ref == v1 => ResourceLookupResult.View("SELECT * FROM testdb.testsch.t1")
      case ref if ref == t1 => ResourceLookupResult.BaseTable
      case _                => ResourceLookupResult.Unknown
    }

    // Schema-level grant with authorized=true -- should still be transparent
    val p = policyWithMode(
      ResolutionMode.Strict,
      schemaGrant("testdb", "testsch", authorized = true, Principal.user("alice"))
    )

    val result = AclEvaluator.evaluateWithViews(Set(v1), userOf("alice"), p, sql, testConfig, lookup)
    // Schema grant covers both v1 and t1, but is always regular (transparent)
    result.decision shouldBe Decision.Allowed
    val viewAccess = result.tableAccesses.find(_.table == v1).get
    viewAccess.grantType shouldBe Some(GrantType.Regular) // NOT Authorized
    viewAccess.isView shouldBe true
    // t1 should also be checked (transparent view)
    result.tableAccesses should have size 2
  }

  test("view-aware: strict mode -- unknown table denies query") {
    val t1 = tableRef("testdb", "testsch", "t1")

    val lookup: TableRef => ResourceLookupResult = _ => ResourceLookupResult.Unknown

    val p = policyWithMode(
      ResolutionMode.Strict,
      regularGrant("testdb", "testsch", "t1", Principal.user("alice"))
    )

    val result = AclEvaluator.evaluateWithViews(Set(t1), userOf("alice"), p, sql, testConfig, lookup)
    result.decision shouldBe Decision.Denied
    result.tableAccesses.head.denyReason shouldBe Some(DenyReason.UnknownView(t1))
  }

  test("view-aware: permissive mode -- unknown table allowed but flagged") {
    val t1 = tableRef("testdb", "testsch", "t1")

    val lookup: TableRef => ResourceLookupResult = _ => ResourceLookupResult.Unknown

    val p = policyWithMode(
      ResolutionMode.Permissive,
      regularGrant("testdb", "testsch", "t1", Principal.user("alice"))
    )

    val result = AclEvaluator.evaluateWithViews(Set(t1), userOf("alice"), p, sql, testConfig, lookup)
    result.decision shouldBe Decision.Allowed
    result.tableAccesses.head.grantType shouldBe Some(GrantType.UnknownButAllowed)
    result.tableAccesses.head.warnings should not be empty
  }

  test("view-aware: mode override takes precedence over policy mode") {
    val t1 = tableRef("testdb", "testsch", "t1")

    val lookup: TableRef => ResourceLookupResult = _ => ResourceLookupResult.Unknown

    // Policy has Strict, but we override to Permissive
    val p = policyWithMode(ResolutionMode.Strict)

    val result = AclEvaluator.evaluateWithViews(
      Set(t1), userOf("alice"), p, sql, testConfig, lookup,
      Some(ResolutionMode.Permissive), java.time.Instant.now(), 50
    )
    result.decision shouldBe Decision.Allowed
    result.tableAccesses.head.grantType shouldBe Some(GrantType.UnknownButAllowed)
  }

  test("view-aware: cycle detection through evaluator") {
    val v1 = tableRef("testdb", "testsch", "v1")
    val v2 = tableRef("testdb", "testsch", "v2")

    val lookup: TableRef => ResourceLookupResult = {
      case ref if ref == v1 => ResourceLookupResult.View("SELECT * FROM testdb.testsch.v2")
      case ref if ref == v2 => ResourceLookupResult.View("SELECT * FROM testdb.testsch.v1")
      case _                => ResourceLookupResult.Unknown
    }

    val p = policyWithMode(
      ResolutionMode.Strict,
      regularGrant("testdb", "testsch", "v1", Principal.user("alice")),
      regularGrant("testdb", "testsch", "v2", Principal.user("alice"))
    )

    val result = AclEvaluator.evaluateWithViews(Set(v1), userOf("alice"), p, sql, testConfig, lookup)
    result.decision shouldBe Decision.Denied
    result.tableAccesses.exists(ta =>
      ta.denyReason.exists(_.isInstanceOf[DenyReason.ViewResolutionCycle])
    ) shouldBe true
  }

  test("view-aware: view SQL parse error") {
    val v1 = tableRef("testdb", "testsch", "v1")

    val lookup: TableRef => ResourceLookupResult = {
      case ref if ref == v1 => ResourceLookupResult.View("THIS IS NOT VALID SQL AT ALL @@@ !!!")
      case _                => ResourceLookupResult.Unknown
    }

    val p = policyWithMode(
      ResolutionMode.Strict,
      regularGrant("testdb", "testsch", "v1", Principal.user("alice"))
    )

    val result = AclEvaluator.evaluateWithViews(Set(v1), userOf("alice"), p, sql, testConfig, lookup)
    result.decision shouldBe Decision.Denied
    result.tableAccesses.head.denyReason shouldBe a[Some[DenyReason.ViewParseError]]
    result.tableAccesses.head.isView shouldBe true
  }

  test("view-aware: callback exception in strict mode") {
    val t1 = tableRef("testdb", "testsch", "t1")

    val lookup: TableRef => ResourceLookupResult = _ => throw new RuntimeException("connection timeout")

    val p = policyWithMode(ResolutionMode.Strict)

    val result = AclEvaluator.evaluateWithViews(Set(t1), userOf("alice"), p, sql, testConfig, lookup)
    result.decision shouldBe Decision.Denied
    result.tableAccesses.head.denyReason shouldBe a[Some[DenyReason.CallbackError]]
  }

  test("view-aware: nested authorized view creates opaque boundary") {
    val t1 = tableRef("testdb", "testsch", "t1")
    val t2 = tableRef("testdb", "testsch", "t2")
    val v1 = tableRef("testdb", "testsch", "v1")
    val v2 = tableRef("testdb", "testsch", "v2")

    // v1 (regular) -> v2 (authorized) -> t1, t2
    val lookup: TableRef => ResourceLookupResult = {
      case ref if ref == v1 => ResourceLookupResult.View("SELECT * FROM testdb.testsch.v2")
      case ref if ref == v2 => ResourceLookupResult.View("SELECT * FROM testdb.testsch.t1 JOIN testdb.testsch.t2 ON t1.id = t2.id")
      case ref if ref == t1 => ResourceLookupResult.BaseTable
      case ref if ref == t2 => ResourceLookupResult.BaseTable
      case _                => ResourceLookupResult.Unknown
    }

    // Regular grant on v1, authorized grant on v2, NO grants on t1/t2
    val p = policyWithMode(
      ResolutionMode.Strict,
      viewGrant("testdb", "testsch", "v1", authorized = false, Principal.user("alice")),
      viewGrant("testdb", "testsch", "v2", authorized = true, Principal.user("alice"))
    )

    val result = AclEvaluator.evaluateWithViews(Set(v1), userOf("alice"), p, sql, testConfig, lookup)
    result.decision shouldBe Decision.Allowed
    // v1 (regular, transparent) -> v2 (authorized, opaque -> stops)
    // So we see v1 and v2, but NOT t1, t2
    result.tableAccesses should have size 2
    val v1Access = result.tableAccesses.find(_.table == v1).get
    v1Access.grantType shouldBe Some(GrantType.Regular)
    val v2Access = result.tableAccesses.find(_.table == v2).get
    v2Access.grantType shouldBe Some(GrantType.Authorized)
  }

  test("view-aware: authorized on base table produces warning") {
    val t1 = tableRef("testdb", "testsch", "t1")

    val lookup: TableRef => ResourceLookupResult = {
      case ref if ref == t1 => ResourceLookupResult.BaseTable
      case _                => ResourceLookupResult.Unknown
    }

    // Grant on t1 with authorized=true (meaningless on base table)
    val p = policyWithMode(
      ResolutionMode.Strict,
      viewGrant("testdb", "testsch", "t1", authorized = true, Principal.user("alice"))
    )

    val result = AclEvaluator.evaluateWithViews(Set(t1), userOf("alice"), p, sql, testConfig, lookup)
    result.decision shouldBe Decision.Allowed
    val access = result.tableAccesses.head
    access.grantType shouldBe Some(GrantType.Regular) // Treated as regular
    access.warnings should not be empty
    access.warnings.head should include("authorized=true which has no effect on base tables")
  }

  test("view-aware: resolutionMap populated for resolved views") {
    val t1 = tableRef("testdb", "testsch", "t1")
    val t2 = tableRef("testdb", "testsch", "t2")
    val v1 = tableRef("testdb", "testsch", "v1")

    val lookup: TableRef => ResourceLookupResult = {
      case ref if ref == v1 => ResourceLookupResult.View("SELECT * FROM testdb.testsch.t1 JOIN testdb.testsch.t2 ON t1.id = t2.id")
      case ref if ref == t1 => ResourceLookupResult.BaseTable
      case ref if ref == t2 => ResourceLookupResult.BaseTable
      case _                => ResourceLookupResult.Unknown
    }

    val p = policyWithMode(
      ResolutionMode.Strict,
      regularGrant("testdb", "testsch", "v1", Principal.user("alice")),
      regularGrant("testdb", "testsch", "t1", Principal.user("alice")),
      regularGrant("testdb", "testsch", "t2", Principal.user("alice"))
    )

    val result = AclEvaluator.evaluateWithViews(Set(v1), userOf("alice"), p, sql, testConfig, lookup)
    result.resolutionMap should contain key v1
    result.resolutionMap(v1) shouldBe Set(t1, t2)
  }

  test("view-aware: backward compatibility -- existing evaluate() still works") {
    val p = policy(grant(GrantTarget.table("mydb", "public", "orders"), Principal.user("alice")))
    val result = AclEvaluator.evaluate(Set(tableRef("mydb", "public", "orders")), userOf("alice"), p, sql)
    result.decision shouldBe Decision.Allowed
    result.isAllowed shouldBe true
    result.resolutionMap shouldBe empty
    result.viewResolutions shouldBe empty
    // New fields should have defaults
    result.tableAccesses.head.grantType shouldBe None
    result.tableAccesses.head.isView shouldBe false
    result.tableAccesses.head.warnings shouldBe empty
  }

  test("view-aware: empty tables with evaluateWithViews") {
    val lookup: TableRef => ResourceLookupResult = _ => ResourceLookupResult.Unknown
    val p = policyWithMode(ResolutionMode.Strict)

    val result = AclEvaluator.evaluateWithViews(Set.empty, userOf("alice"), p, sql, testConfig, lookup)
    result.decision shouldBe Decision.Allowed
    result.tableAccesses shouldBe empty
    result.resolutionMap shouldBe empty
  }

  test("view-aware: mixed -- base tables, views, and unknown in one query") {
    val t1 = tableRef("testdb", "testsch", "t1")
    val v1 = tableRef("testdb", "testsch", "v1")
    val t2 = tableRef("testdb", "testsch", "t2")
    val unknown = tableRef("testdb", "testsch", "mystery")

    val lookup: TableRef => ResourceLookupResult = {
      case ref if ref == t1      => ResourceLookupResult.BaseTable
      case ref if ref == v1      => ResourceLookupResult.View("SELECT * FROM testdb.testsch.t2")
      case ref if ref == t2      => ResourceLookupResult.BaseTable
      case ref if ref == unknown => ResourceLookupResult.Unknown
      case _                     => ResourceLookupResult.Unknown
    }

    val p = policyWithMode(
      ResolutionMode.Permissive,
      regularGrant("testdb", "testsch", "t1", Principal.user("alice")),
      regularGrant("testdb", "testsch", "v1", Principal.user("alice")),
      regularGrant("testdb", "testsch", "t2", Principal.user("alice"))
    )

    val result = AclEvaluator.evaluateWithViews(Set(t1, v1, unknown), userOf("alice"), p, sql, testConfig, lookup)
    result.decision shouldBe Decision.Allowed

    // t1: base, allowed
    val t1Access = result.tableAccesses.find(_.table == t1).get
    t1Access.grantType shouldBe Some(GrantType.Regular)
    t1Access.isView shouldBe false

    // v1: view (regular), allowed + t2 base table checked
    val v1Access = result.tableAccesses.find(_.table == v1).get
    v1Access.grantType shouldBe Some(GrantType.Regular)
    v1Access.isView shouldBe true

    // unknown: allowed in permissive mode
    val unknownAccess = result.tableAccesses.find(_.table == unknown).get
    unknownAccess.grantType shouldBe Some(GrantType.UnknownButAllowed)
  }

  // ---------------------------------------------------------------------------
  // Grant expiration
  // ---------------------------------------------------------------------------

  private val pastInstant = java.time.Instant.parse("2025-01-01T00:00:00Z")
  private val futureInstant = java.time.Instant.parse("2099-12-31T23:59:59Z")
  private val nowInstant = java.time.Instant.parse("2026-06-15T12:00:00Z")

  test("expired table-level grant is Denied with ExpiredGrant reason") {
    val t1 = tableRef("db", "sch", "t1")
    val expiredGrant = Grant(GrantTarget.table("db", "sch", "t1"), List(Principal.user("alice")), expires = Some(pastInstant))
    val p = policy(expiredGrant)

    val result = AclEvaluator.evaluate(Set(t1), userOf("alice"), p, sql, nowInstant)
    result.decision shouldBe Decision.Denied
    result.tableAccesses.head.denyReason shouldBe Some(DenyReason.ExpiredGrant(t1, userOf("alice"), pastInstant))
  }

  test("non-expired table-level grant is Allowed") {
    val t1 = tableRef("db", "sch", "t1")
    val futureGrant = Grant(GrantTarget.table("db", "sch", "t1"), List(Principal.user("alice")), expires = Some(futureInstant))
    val p = policy(futureGrant)

    val result = AclEvaluator.evaluate(Set(t1), userOf("alice"), p, sql, nowInstant)
    result.decision shouldBe Decision.Allowed
  }

  test("grant without expires never expires") {
    val t1 = tableRef("db", "sch", "t1")
    val noExpiryGrant = Grant(GrantTarget.table("db", "sch", "t1"), List(Principal.user("alice")))
    val p = policy(noExpiryGrant)

    val result = AclEvaluator.evaluate(Set(t1), userOf("alice"), p, sql, nowInstant)
    result.decision shouldBe Decision.Allowed
  }

  test("expired table-level grant with active schema-level grant: schema grant allows access") {
    val t1 = tableRef("db", "sch", "t1")
    val expiredTableGrant = Grant(GrantTarget.table("db", "sch", "t1"), List(Principal.user("alice")), expires = Some(pastInstant))
    val activeSchemaGrant = Grant(GrantTarget.schema("db", "sch"), List(Principal.user("alice")))
    val p = policy(expiredTableGrant, activeSchemaGrant)

    val result = AclEvaluator.evaluate(Set(t1), userOf("alice"), p, sql, nowInstant)
    result.decision shouldBe Decision.Allowed
  }

  test("expired schema-level grant with no other grant returns ExpiredGrant") {
    val t1 = tableRef("db", "sch", "t1")
    val expiredSchemaGrant = Grant(GrantTarget.schema("db", "sch"), List(Principal.user("alice")), expires = Some(pastInstant))
    val p = policy(expiredSchemaGrant)

    val result = AclEvaluator.evaluate(Set(t1), userOf("alice"), p, sql, nowInstant)
    result.decision shouldBe Decision.Denied
    result.tableAccesses.head.denyReason shouldBe Some(DenyReason.ExpiredGrant(t1, userOf("alice"), pastInstant))
  }

  test("expired database-level grant returns ExpiredGrant") {
    val t1 = tableRef("db", "sch", "t1")
    val expiredDbGrant = Grant(GrantTarget.database("db"), List(Principal.user("alice")), expires = Some(pastInstant))
    val p = policy(expiredDbGrant)

    val result = AclEvaluator.evaluate(Set(t1), userOf("alice"), p, sql, nowInstant)
    result.decision shouldBe Decision.Denied
    result.tableAccesses.head.denyReason shouldBe Some(DenyReason.ExpiredGrant(t1, userOf("alice"), pastInstant))
  }

  test("view-aware: expired grant on view returns ExpiredGrant") {
    val v1 = tableRef("testdb", "testsch", "v1")

    val lookup: TableRef => ResourceLookupResult = {
      case ref if ref == v1 => ResourceLookupResult.View("SELECT * FROM testdb.testsch.t1")
      case _                => ResourceLookupResult.BaseTable
    }

    val expiredViewGrant = Grant(GrantTarget.table("testdb", "testsch", "v1"), List(Principal.user("alice")), expires = Some(pastInstant))
    val activeBaseGrant = regularGrant("testdb", "testsch", "t1", Principal.user("alice"))
    val p = policy(expiredViewGrant, activeBaseGrant)

    val result = AclEvaluator.evaluateWithViews(Set(v1), userOf("alice"), p, sql, testConfig, lookup, None, nowInstant, 50)
    result.decision shouldBe Decision.Denied
    result.tableAccesses.head.denyReason shouldBe Some(DenyReason.ExpiredGrant(v1, userOf("alice"), pastInstant))
  }

  test("no grant at all returns NoMatchingGrant, not ExpiredGrant") {
    val t1 = tableRef("db", "sch", "t1")
    val emptyPolicy = AclPolicy(Nil)

    val result = AclEvaluator.evaluate(Set(t1), userOf("alice"), emptyPolicy, sql, nowInstant)
    result.decision shouldBe Decision.Denied
    result.tableAccesses.head.denyReason shouldBe Some(DenyReason.NoMatchingGrant(t1, userOf("alice")))
  }
}
