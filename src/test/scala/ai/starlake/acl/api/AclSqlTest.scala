package ai.starlake.acl.api

import ai.starlake.acl.AclError
import ai.starlake.acl.model.{DenyReason, TableRef, TenantId, UserIdentity}
import ai.starlake.acl.policy.ResourceLookupResult
import ai.starlake.acl.store.LocalAclStore
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class AclSqlTest extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var tempDir: Path = scala.compiletime.uninitialized
  private var basePath: Path = scala.compiletime.uninitialized

  override def beforeEach(): Unit = {
    tempDir = Files.createTempDirectory("aclsql-test")
    basePath = tempDir
  }

  override def afterEach(): Unit = {
    // Clean up temp directory
    if Files.exists(tempDir) then
      Files.walk(tempDir).toList.asScala.sortBy(-_.toString.length).foreach(Files.deleteIfExists)
  }

  private def createTenantWithGrant(
      tenantName: String,
      target: String,
      principals: List[String]
  ): TenantId = {
    val tenantDir = basePath.resolve(tenantName.toLowerCase)
    Files.createDirectories(tenantDir)

    val yaml =
      s"""grants:
         |  - target: "$target"
         |    principals:
         |${principals.map(p => s"""      - "$p"""").mkString("\n")}
         |""".stripMargin

    Files.writeString(tenantDir.resolve("acl.yaml"), yaml)
    TenantId.parse(tenantName).toOption.get
  }

  private val baseTableLookup: (TenantId, TableRef) => ResourceLookupResult =
    (_, _) => ResourceLookupResult.BaseTable

  private def userOf(name: String): UserIdentity = UserIdentity(name, Set.empty)

  // ---------------------------------------------------------------------------
  // Basic checkAccess
  // ---------------------------------------------------------------------------

  "AclSql.checkAccess" should "return allowed for valid tenant and authorized user" in {
    val tenant = createTenantWithGrant("acme", "db.sch.orders", List("user:alice"))
    val aclSql = AclSql(new LocalAclStore(basePath), baseTableLookup)

    val result = aclSql.checkAccess(tenant, "SELECT * FROM db.sch.orders", userOf("alice"))

    result.isRight shouldBe true
    result.toOption.get.isAllowed shouldBe true
  }

  it should "return denied for valid tenant and unauthorized user" in {
    val tenant = createTenantWithGrant("acme", "db.sch.orders", List("user:alice"))
    val aclSql = AclSql(new LocalAclStore(basePath), baseTableLookup)

    val result = aclSql.checkAccess(tenant, "SELECT * FROM db.sch.orders", userOf("bob"))

    result.isRight shouldBe true
    result.toOption.get.isDenied shouldBe true
    result.toOption.get.result.tableAccesses.head.denyReason shouldBe Some(
      DenyReason.NoMatchingGrant(
        TableRef("db", "sch", "orders"),
        userOf("bob")
      )
    )
  }

  it should "return TenantNotFound error for unknown tenant" in {
    val unknownTenant = TenantId.parse("nonexistent").toOption.get
    val aclSql = AclSql(new LocalAclStore(basePath), baseTableLookup)

    val result = aclSql.checkAccess(unknownTenant, "SELECT * FROM t", userOf("alice"))

    result.isLeft shouldBe true
    result.left.toOption.get match {
      case AclError.TenantNotFound(tid) =>
        tid shouldBe "nonexistent"
      case other =>
        fail(s"Expected AclError.TenantNotFound, got $other")
    }
  }

  // ---------------------------------------------------------------------------
  // String tenant parameter
  // ---------------------------------------------------------------------------

  it should "accept String tenant parameter for valid tenant ID" in {
    val _ = createTenantWithGrant("acme", "db.sch.orders", List("user:alice"))
    val aclSql = AclSql(new LocalAclStore(basePath), baseTableLookup)

    val result = aclSql.checkAccess("acme", "SELECT * FROM db.sch.orders", userOf("alice"), false, SqlContext.default)

    result.isRight shouldBe true
    result.toOption.get.isAllowed shouldBe true
  }

  it should "return ConfigError for invalid tenant ID string" in {
    val aclSql = AclSql(new LocalAclStore(basePath), baseTableLookup)

    val result = aclSql.checkAccess("invalid.tenant", "SELECT * FROM t", userOf("alice"), false, SqlContext.default)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[AclError.ConfigError]
    result.left.toOption.get.message should include("Invalid tenant ID")
  }

  // ---------------------------------------------------------------------------
  // Sequential multi-tenant access
  // ---------------------------------------------------------------------------

  it should "allow sequential access to different tenants" in {
    val tenant1 = createTenantWithGrant("acme", "db.sch.orders", List("user:alice"))
    val tenant2 = createTenantWithGrant("globex", "db.sch.products", List("user:bob"))
    val aclSql = AclSql(new LocalAclStore(basePath), baseTableLookup)

    // First tenant
    val result1 = aclSql.checkAccess(tenant1, "SELECT * FROM db.sch.orders", userOf("alice"))
    result1.toOption.get.isAllowed shouldBe true

    // Second tenant
    val result2 = aclSql.checkAccess(tenant2, "SELECT * FROM db.sch.products", userOf("bob"))
    result2.toOption.get.isAllowed shouldBe true

    // Cross-tenant access denied
    val result3 = aclSql.checkAccess(tenant1, "SELECT * FROM db.sch.orders", userOf("bob"))
    result3.toOption.get.isDenied shouldBe true
  }

  // ---------------------------------------------------------------------------
  // Cache invalidation
  // ---------------------------------------------------------------------------

  "AclSql.invalidateTenant" should "clear cache for specific tenant" in {
    val tenant = createTenantWithGrant("acme", "db.sch.orders", List("user:alice"))
    val aclSql = AclSql(new LocalAclStore(basePath), baseTableLookup)

    // Warm the cache
    val _ = aclSql.checkAccess(tenant, "SELECT * FROM db.sch.orders", userOf("alice"))
    aclSql.tenantStatus(tenant) shouldBe a[TenantStatus.Fresh]

    // Invalidate
    aclSql.invalidateTenant(tenant)
    aclSql.tenantStatus(tenant) shouldBe TenantStatus.NotLoaded
  }

  "AclSql.invalidateAll" should "clear all cached tenants" in {
    val tenant1 = createTenantWithGrant("acme", "db.sch.orders", List("user:alice"))
    val tenant2 = createTenantWithGrant("globex", "db.sch.products", List("user:bob"))
    val aclSql = AclSql(new LocalAclStore(basePath), baseTableLookup)

    // Warm the cache
    val _ = aclSql.checkAccess(tenant1, "SELECT * FROM db.sch.orders", userOf("alice"))
    val _ = aclSql.checkAccess(tenant2, "SELECT * FROM db.sch.products", userOf("bob"))

    aclSql.tenantStatus(tenant1) shouldBe a[TenantStatus.Fresh]
    aclSql.tenantStatus(tenant2) shouldBe a[TenantStatus.Fresh]

    // Invalidate all
    aclSql.invalidateAll()
    aclSql.tenantStatus(tenant1) shouldBe TenantStatus.NotLoaded
    aclSql.tenantStatus(tenant2) shouldBe TenantStatus.NotLoaded
  }

  // ---------------------------------------------------------------------------
  // Tenant status
  // ---------------------------------------------------------------------------

  "AclSql.tenantStatus" should "return NotLoaded for never-accessed tenant" in {
    val aclSql = AclSql(new LocalAclStore(basePath), baseTableLookup)
    val tenant = TenantId.parse("fresh").toOption.get

    aclSql.tenantStatus(tenant) shouldBe TenantStatus.NotLoaded
  }

  it should "return Fresh after successful load" in {
    val tenant = createTenantWithGrant("acme", "db.sch.orders", List("user:alice"))
    val aclSql = AclSql(new LocalAclStore(basePath), baseTableLookup)

    val _ = aclSql.checkAccess(tenant, "SELECT * FROM db.sch.orders", userOf("alice"))

    aclSql.tenantStatus(tenant) shouldBe a[TenantStatus.Fresh]
  }

  // ---------------------------------------------------------------------------
  // Result includes tenantId
  // ---------------------------------------------------------------------------

  "AccessResult" should "include tenantId field" in {
    val tenant = createTenantWithGrant("acme", "db.sch.orders", List("user:alice"))
    val aclSql = AclSql(new LocalAclStore(basePath), baseTableLookup)

    val result = aclSql.checkAccess(tenant, "SELECT * FROM db.sch.orders", userOf("alice"))

    result.toOption.get.result.tenantId shouldBe Some(tenant)
  }

  // ---------------------------------------------------------------------------
  // View resolver receives tenant context
  // ---------------------------------------------------------------------------

  "View resolver callback" should "receive tenant context" in {
    val tenant = createTenantWithGrant("acme", "db.sch.view1", List("user:alice"))
    var receivedTenant: Option[TenantId] = None

    val tenantAwareLookup: (TenantId, TableRef) => ResourceLookupResult = { (tid, ref) =>
      receivedTenant = Some(tid)
      if ref.table == "view1" then ResourceLookupResult.View("SELECT * FROM db.sch.base")
      else ResourceLookupResult.BaseTable
    }

    // Need grant on base table too
    Files.writeString(
      basePath.resolve("acme").resolve("base.yaml"),
      """grants:
        |  - target: "db.sch.base"
        |    principals:
        |      - "user:alice"
        |""".stripMargin
    )

    val aclSql = new AclSql(new LocalAclStore(basePath), tenantAwareLookup)
    val _ = aclSql.checkAccess(tenant, "SELECT * FROM db.sch.view1", userOf("alice"))

    receivedTenant shouldBe Some(tenant)
  }

  // ---------------------------------------------------------------------------
  // checkAccessAll
  // ---------------------------------------------------------------------------

  "AclSql.checkAccessAll" should "return results for multiple statements" in {
    val tenant = createTenantWithGrant("acme", "db.sch.orders", List("user:alice"))
    val aclSql = AclSql(new LocalAclStore(basePath), baseTableLookup)

    val results = aclSql.checkAccessAll(
      tenant,
      "SELECT * FROM db.sch.orders; SELECT * FROM db.sch.secret",
      userOf("alice")
    )

    results should have size 2
    results.head.toOption.get.isAllowed shouldBe true
    results(1).toOption.get.isDenied shouldBe true
  }

  // ---------------------------------------------------------------------------
  // Config options
  // ---------------------------------------------------------------------------

  it should "use per-call SqlContext for table qualification" in {
    val tenant = createTenantWithGrant("acme", "mydb.public.orders", List("user:alice"))
    val aclSql = AclSql(new LocalAclStore(basePath), baseTableLookup)
    val ctx = SqlContext(defaultDatabase = Some("mydb"), defaultSchema = Some("public"))

    // With SqlContext defaults, unqualified table should resolve correctly
    val result = aclSql.checkAccess(tenant, "SELECT * FROM orders", userOf("alice"), sqlContext = ctx)

    result.toOption.get.isAllowed shouldBe true
  }

  // ---------------------------------------------------------------------------
  // Empty tenant folder
  // ---------------------------------------------------------------------------

  it should "return denied for empty tenant folder (deny-all)" in {
    // Create empty tenant folder
    val tenantDir = basePath.resolve("emptytenant")
    Files.createDirectories(tenantDir)
    val tenant = TenantId.parse("emptytenant").toOption.get

    val aclSql = AclSql(new LocalAclStore(basePath), baseTableLookup)
    val result = aclSql.checkAccess(tenant, "SELECT * FROM db.sch.orders", userOf("alice"))

    result.isRight shouldBe true
    result.toOption.get.isDenied shouldBe true
  }

  // ---------------------------------------------------------------------------
  // Transparent view: authorized=false must check underlying tables
  // ---------------------------------------------------------------------------

  it should "deny access to transparent view when underlying tables lack grants" in {
    // Setup: grant on view (authorized=false) and on lineitem, but NOT on nation
    val tenantDir = basePath.resolve("tpch2tenant")
    Files.createDirectories(tenantDir)

    val yaml =
      """mode: strict
        |grants:
        |  - target: "tpch2.main.lineitem"
        |    principals:
        |      - "group:admin"
        |  - target: "tpch2.main.revenue_per_nation"
        |    principals:
        |      - "group:admin"
        |    authorized: false
        |""".stripMargin

    Files.writeString(tenantDir.resolve("acl.yaml"), yaml)
    val tenant = TenantId.parse("tpch2tenant").toOption.get

    // View resolver: revenue_per_nation is a view referencing lineitem and nation
    val viewLookup: (TenantId, TableRef) => ResourceLookupResult = { (_, ref) =>
      if ref.table == "revenue_per_nation" then
        ResourceLookupResult.View("SELECT n_name, sum(l_extendedprice) FROM tpch2.main.lineitem JOIN tpch2.main.nation ON l_nationkey = n_nationkey GROUP BY n_name")
      else
        ResourceLookupResult.BaseTable
    }

    val adminUser = UserIdentity("admin-user", Set("admin"))
    val aclSql = new AclSql(new LocalAclStore(basePath), viewLookup)
    val result = aclSql.checkAccess(
      tenant,
      "SELECT * FROM tpch2.main.revenue_per_nation",
      adminUser
    )

    // Should be DENIED because nation has no grant (transparent view checks base tables)
    result.isRight shouldBe true
    result.toOption.get.isDenied shouldBe true
  }

  it should "deny access when view SQL is unparseable (e.g. CREATE VIEW not stripped)" in {
    // Bug: DuckLake returns view_definition as "CREATE VIEW ... AS SELECT ..."
    // If stripCreateViewPrefix fails, the full CREATE VIEW SQL is passed to SqlParser
    // which parses it as NonSelect → 0 tables extracted → view treated as having no dependencies
    // → ALLOWED without checking underlying tables
    val tenantDir = basePath.resolve("parsebugtenant")
    Files.createDirectories(tenantDir)

    val yaml =
      """mode: strict
        |grants:
        |  - target: "tpch2.main.revenue_per_nation"
        |    principals:
        |      - "group:admin"
        |    authorized: false
        |""".stripMargin

    Files.writeString(tenantDir.resolve("acl.yaml"), yaml)
    val tenant = TenantId.parse("parsebugtenant").toOption.get

    // View resolver returns the full CREATE VIEW SQL (simulating stripCreateViewPrefix failure)
    val viewLookup: (TenantId, TableRef) => ResourceLookupResult = { (_, ref) =>
      if ref.table == "revenue_per_nation" then
        ResourceLookupResult.View(
          "CREATE VIEW revenue_per_nation AS SELECT n_name, SUM(l_extendedprice) FROM tpch2.main.lineitem JOIN tpch2.main.nation ON l_nationkey = n_nationkey GROUP BY n_name"
        )
      else
        ResourceLookupResult.BaseTable
    }

    val adminUser = UserIdentity("admin-user", Set("admin"))
    val aclSql = new AclSql(new LocalAclStore(basePath), viewLookup)
    val result = aclSql.checkAccess(
      tenant,
      "SELECT * FROM tpch2.main.revenue_per_nation",
      adminUser
    )

    // Must be DENIED: unparseable view SQL should not silently allow access
    result.isRight shouldBe true
    result.toOption.get.isDenied shouldBe true
  }

  it should "allow access to transparent view when all underlying tables are granted" in {
    val tenantDir = basePath.resolve("tpch2full")
    Files.createDirectories(tenantDir)

    val yaml =
      """mode: strict
        |grants:
        |  - target: "tpch2.main.lineitem"
        |    principals:
        |      - "group:admin"
        |  - target: "tpch2.main.nation"
        |    principals:
        |      - "group:admin"
        |  - target: "tpch2.main.revenue_per_nation"
        |    principals:
        |      - "group:admin"
        |    authorized: false
        |""".stripMargin

    Files.writeString(tenantDir.resolve("acl.yaml"), yaml)
    val tenant = TenantId.parse("tpch2full").toOption.get

    val viewLookup: (TenantId, TableRef) => ResourceLookupResult = { (_, ref) =>
      if ref.table == "revenue_per_nation" then
        ResourceLookupResult.View("SELECT n_name, sum(l_extendedprice) FROM tpch2.main.lineitem JOIN tpch2.main.nation ON l_nationkey = n_nationkey GROUP BY n_name")
      else
        ResourceLookupResult.BaseTable
    }

    val adminUser = UserIdentity("admin-user", Set("admin"))
    val aclSql = new AclSql(new LocalAclStore(basePath), viewLookup)
    val result = aclSql.checkAccess(
      tenant,
      "SELECT * FROM tpch2.main.revenue_per_nation",
      adminUser
    )

    // Should be ALLOWED because all underlying tables have grants
    result.isRight shouldBe true
    result.toOption.get.isAllowed shouldBe true
  }
}
