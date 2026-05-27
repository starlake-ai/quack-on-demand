package ai.starlake.acl.api

import ai.starlake.acl.AclError
import ai.starlake.acl.model.*
import ai.starlake.acl.policy.ResourceLookupResult
import ai.starlake.acl.store.LocalAclStore
import ai.starlake.acl.watcher.{WatcherConfig, WatcherStatus}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import scala.compiletime.uninitialized
import scala.util.Using

class AclSqlIntegrationTest extends AnyFunSuite with Matchers with BeforeAndAfterEach:

  private var tempDir: Path = uninitialized

  override def beforeEach(): Unit =
    tempDir = Files.createTempDirectory("aclsql-integration")

  override def afterEach(): Unit =
    deleteRecursively(tempDir)

  // =========================================================================
  // Multi-Tenant Access Tests (TNAPI-01, TNAPI-03)
  // =========================================================================

  test("checkAccess requires tenant - different tenants are isolated"):
    // Setup tenant A with grants
    val tenantADir = tempDir.resolve("tenant-a")
    Files.createDirectory(tenantADir)
    Files.writeString(
      tenantADir.resolve("acl.yaml"),
      """
        |grants:
        |  - target: "db.schema.orders"
        |    principals: ["user:alice"]
        |""".stripMargin
    )

    // Setup tenant B with different grants
    val tenantBDir = tempDir.resolve("tenant-b")
    Files.createDirectory(tenantBDir)
    Files.writeString(
      tenantBDir.resolve("acl.yaml"),
      """
        |grants:
        |  - target: "db.schema.products"
        |    principals: ["user:bob"]
        |""".stripMargin
    )

    val api = new AclSql(new LocalAclStore(tempDir), simpleViewResolver)
    val alice = testUser("alice")
    val bob = testUser("bob")

    // Alice can access orders in tenant-a
    val resultA = api.checkAccess(
      TenantId.parse("tenant-a").toOption.get,
      "SELECT * FROM db.schema.orders",
      alice
    )
    resultA.isRight shouldBe true
    resultA.toOption.get.isAllowed shouldBe true
    resultA.toOption.get.result.tenantId.map(_.canonical) shouldBe Some("tenant-a")

    // Alice cannot access orders in tenant-b (different grants)
    val resultB = api.checkAccess(
      TenantId.parse("tenant-b").toOption.get,
      "SELECT * FROM db.schema.orders",
      alice
    )
    resultB.isRight shouldBe true
    resultB.toOption.get.isDenied shouldBe true

    // Bob can access products in tenant-b
    val resultBob = api.checkAccess(
      TenantId.parse("tenant-b").toOption.get,
      "SELECT * FROM db.schema.products",
      bob
    )
    resultBob.isRight shouldBe true
    resultBob.toOption.get.isAllowed shouldBe true

  test("checkAccess returns TenantNotFound error for unknown tenant"):
    // No tenant folders created

    val api = new AclSql(new LocalAclStore(tempDir), simpleViewResolver)
    val user = testUser("alice")

    val result = api.checkAccess(
      TenantId.parse("nonexistent").toOption.get,
      "SELECT * FROM db.schema.orders",
      user
    )

    result.isLeft shouldBe true
    result.left.toOption.get match
      case AclError.TenantNotFound(tid) =>
        tid shouldBe "nonexistent"
      case other =>
        fail(s"Expected AclError.TenantNotFound, got $other")

  test("checkAccess with String tenant parameter works"):
    val tenantDir = tempDir.resolve("mycompany")
    Files.createDirectory(tenantDir)
    Files.writeString(
      tenantDir.resolve("acl.yaml"),
      """grants:
        |  - target: db.schema.t
        |    principals: [user:anyone]
        |""".stripMargin
    )

    val api = new AclSql(new LocalAclStore(tempDir), simpleViewResolver)

    val result =
      api.checkAccess("mycompany", "SELECT * FROM db.schema.t", testUser("anyone"), false, SqlContext.default)
    result.isRight shouldBe true
    result.toOption.get.isAllowed shouldBe true

  test("checkAccess with invalid String tenant returns error"):
    val api = new AclSql(new LocalAclStore(tempDir), simpleViewResolver)

    val result = api.checkAccess("", "SELECT * FROM t", testUser("alice"), false, SqlContext.default)
    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[AclError.ConfigError]

  // =========================================================================
  // View Resolver Tests (TNVUE-01, TNVUE-02)
  // =========================================================================

  test("view resolver receives tenant context"):
    var capturedTenant: Option[TenantId] = None

    val tenantAwareResolver: (TenantId, TableRef) => ResourceLookupResult = { (tenant, ref) =>
      capturedTenant = Some(tenant)
      if ref.table == "my_view" then ResourceLookupResult.View("SELECT * FROM db.schema.base_table")
      else ResourceLookupResult.BaseTable
    }

    val tenantDir = tempDir.resolve("viewtest")
    Files.createDirectory(tenantDir)
    Files.writeString(
      tenantDir.resolve("acl.yaml"),
      """
        |grants:
        |  - target: "db.schema.base_table"
        |    principals: ["user:alice"]
        |""".stripMargin
    )

    val api = new AclSql(new LocalAclStore(tempDir), tenantAwareResolver)
    val tenant = TenantId.parse("viewtest").toOption.get

    val result = api.checkAccess(tenant, "SELECT * FROM db.schema.my_view", testUser("alice"))

    capturedTenant shouldBe Some(tenant)
    result.isRight shouldBe true

  test("view definitions are isolated per tenant"):
    // Tenant A: view resolves to table_a
    // Tenant B: same view name resolves to table_b

    val viewResolver: (TenantId, TableRef) => ResourceLookupResult = { (tenant, ref) =>
      if ref.table == "shared_view" then
        tenant.canonical match
          case "tenant-a" => ResourceLookupResult.View("SELECT * FROM db.schema.table_a")
          case "tenant-b" => ResourceLookupResult.View("SELECT * FROM db.schema.table_b")
          case _          => ResourceLookupResult.Unknown
      else ResourceLookupResult.BaseTable
    }

    // Setup both tenants with grants for:
    // - The view (shared_view) - required for transparent view access
    // - The underlying table - required because view is transparent
    val tenantADir = tempDir.resolve("tenant-a")
    Files.createDirectory(tenantADir)
    Files.writeString(
      tenantADir.resolve("acl.yaml"),
      """grants:
        |  - target: db.schema.shared_view
        |    principals: [user:alice]
        |  - target: db.schema.table_a
        |    principals: [user:alice]
        |""".stripMargin
    )

    val tenantBDir = tempDir.resolve("tenant-b")
    Files.createDirectory(tenantBDir)
    Files.writeString(
      tenantBDir.resolve("acl.yaml"),
      """grants:
        |  - target: db.schema.shared_view
        |    principals: [user:alice]
        |  - target: db.schema.table_b
        |    principals: [user:alice]
        |""".stripMargin
    )

    val api = new AclSql(new LocalAclStore(tempDir), viewResolver)
    val alice = testUser("alice")

    // In tenant-a, view resolves to table_a (which alice can access)
    val resultA = api.checkAccess(
      TenantId.parse("tenant-a").toOption.get,
      "SELECT * FROM db.schema.shared_view",
      alice
    )
    resultA.isRight shouldBe true
    resultA.toOption.get.isAllowed shouldBe true

    // In tenant-b, view resolves to table_b (which alice can access)
    val resultB = api.checkAccess(
      TenantId.parse("tenant-b").toOption.get,
      "SELECT * FROM db.schema.shared_view",
      alice
    )
    resultB.isRight shouldBe true
    resultB.toOption.get.isAllowed shouldBe true

  // =========================================================================
  // Cache and Invalidation Tests
  // =========================================================================

  test("sequential access to different tenants works correctly"):
    for i <- 1 to 3 do
      val dir = tempDir.resolve(s"tenant-$i")
      Files.createDirectory(dir)
      Files.writeString(
        dir.resolve("acl.yaml"),
        s"""
           |grants:
           |  - target: "db.schema.table_$i"
           |    principals: ["user:user$i"]
           |""".stripMargin
      )

    val api = new AclSql(new LocalAclStore(tempDir), simpleViewResolver)

    for i <- 1 to 3 do
      val tenant = TenantId.parse(s"tenant-$i").toOption.get
      val user = testUser(s"user$i")
      val result = api.checkAccess(tenant, s"SELECT * FROM db.schema.table_$i", user)
      result.isRight shouldBe true
      result.toOption.get.isAllowed shouldBe true

  test("tenantStatus reflects cache state"):
    val tenantDir = tempDir.resolve("status-test")
    Files.createDirectory(tenantDir)
    // Empty folder = valid tenant with no grants (deny-all)

    val api = new AclSql(new LocalAclStore(tempDir), simpleViewResolver)
    val tenant = TenantId.parse("status-test").toOption.get

    // Before access
    api.tenantStatus(tenant) shouldBe TenantStatus.NotLoaded

    // After access (will load empty grants)
    val _ = api.checkAccess(tenant, "SELECT 1", testUser("test"))
    api.tenantStatus(tenant) shouldBe a[TenantStatus.Fresh]

    // After invalidation
    api.invalidateTenant(tenant)
    api.tenantStatus(tenant) shouldBe TenantStatus.NotLoaded

  test("invalidateAll clears all tenants"):
    for i <- 1 to 3 do
      val dir = tempDir.resolve(s"tenant-$i")
      Files.createDirectory(dir)
      // Empty folders = valid tenants with no grants

    val api = new AclSql(new LocalAclStore(tempDir), simpleViewResolver)

    // Access all tenants
    for i <- 1 to 3 do
      val tenant = TenantId.parse(s"tenant-$i").toOption.get
      val _ = api.checkAccess(tenant, "SELECT 1", testUser("test"))
      api.tenantStatus(tenant) shouldBe a[TenantStatus.Fresh]

    // Invalidate all
    api.invalidateAll()

    // All should be NotLoaded
    for i <- 1 to 3 do
      val tenant = TenantId.parse(s"tenant-$i").toOption.get
      api.tenantStatus(tenant) shouldBe TenantStatus.NotLoaded

  // =========================================================================
  // withWatcher Integration Tests
  // =========================================================================

  test("withWatcher creates API with automatic invalidation"):
    val tenantDir = tempDir.resolve("watched")
    Files.createDirectory(tenantDir)
    Files.writeString(
      tenantDir.resolve("acl.yaml"),
      """grants:
        |  - target: db.schema.orders
        |    principals: [user:alice]
        |""".stripMargin
    )

    val (api, watcher) = AclSql.withWatcher(
      tempDir,
      simpleViewResolver,
      watcherConfig = WatcherConfig(debounceMs = 50)
    )

    Using.resource(watcher) { _ =>
      Thread.sleep(200)

      val tenant = TenantId.parse("watched").toOption.get

      // Initial access - should be allowed
      val result1 = api.checkAccess(tenant, "SELECT * FROM db.schema.orders", testUser("alice"))
      result1.isRight shouldBe true
      result1.toOption.get.isAllowed shouldBe true
      api.tenantStatus(tenant) shouldBe a[TenantStatus.Fresh]

      // Modify ACL file to revoke access
      Files.writeString(
        tenantDir.resolve("acl.yaml"),
        """grants:
          |  - target: db.schema.products
          |    principals: [user:bob]
          |""".stripMargin
      )

      // Wait for watcher to detect change and debounce (longer for macOS kqueue)
      Thread.sleep(1000)

      // Status should reflect invalidation happened (might be NotLoaded or Fresh depending on timing)
      // The key test is that access check reflects the new ACL
      val result2 = api.checkAccess(tenant, "SELECT * FROM db.schema.orders", testUser("alice"))
      result2.isRight shouldBe true
      result2.toOption.get.isDenied shouldBe true // Alice no longer has access
    }

  test("watcher status is healthy after initialization"):
    val (_, watcher) = AclSql.withWatcher(tempDir, simpleViewResolver)

    Using.resource(watcher) { w =>
      Thread.sleep(100)
      w.status shouldBe WatcherStatus.Healthy
    }

  // =========================================================================
  // Result Fields Tests
  // =========================================================================

  test("result includes tenantId field"):
    val tenantDir = tempDir.resolve("fieldtest")
    Files.createDirectory(tenantDir)
    // Empty folder = valid tenant with deny-all

    val api = new AclSql(new LocalAclStore(tempDir), simpleViewResolver)
    val tenant = TenantId.parse("fieldtest").toOption.get

    val result = api.checkAccess(tenant, "SELECT * FROM db.schema.t", testUser("alice"))
    result.isRight shouldBe true
    result.toOption.get.result.tenantId shouldBe Some(tenant)

  test("result includes usedStaleGrants field"):
    val tenantDir = tempDir.resolve("staletest")
    Files.createDirectory(tenantDir)
    // Empty folder = valid tenant with deny-all

    val api = new AclSql(new LocalAclStore(tempDir), simpleViewResolver)
    val tenant = TenantId.parse("staletest").toOption.get

    val result = api.checkAccess(tenant, "SELECT * FROM db.schema.t", testUser("alice"))
    result.isRight shouldBe true
    result.toOption.get.result.usedStaleGrants shouldBe false

  // =========================================================================
  // Helpers
  // =========================================================================

  private def testUser(name: String): UserIdentity = UserIdentity(name, Set.empty)

  private val simpleViewResolver: (TenantId, TableRef) => ResourceLookupResult =
    (_, _) => ResourceLookupResult.BaseTable

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      if Files.isDirectory(path) then Files.list(path).forEach(deleteRecursively)
      Files.delete(path)
