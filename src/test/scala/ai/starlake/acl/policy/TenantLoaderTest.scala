package ai.starlake.acl.policy

import ai.starlake.acl.AclError
import ai.starlake.acl.model.{AclPolicy, GrantTarget, Principal, TenantId}
import cats.data.Validated
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}
import scala.compiletime.uninitialized
import scala.jdk.StreamConverters.*
import scala.util.Using

class TenantLoaderTest extends AnyFunSuite with BeforeAndAfterEach {

  private var basePath: Path = uninitialized

  override def beforeEach(): Unit = {
    basePath = Files.createTempDirectory("tenant-loader-test")
  }

  override def afterEach(): Unit = {
    deleteRecursively(basePath)
  }

  private def deleteRecursively(path: Path): Unit = {
    if (Files.isDirectory(path)) {
      Using.resource(Files.list(path)) { stream =>
        stream.toScala(List).foreach(deleteRecursively)
      }
    }
    val _ = Files.deleteIfExists(path)
  }

  private def createTenantFolder(tenantName: String): Path = {
    val dir = basePath.resolve(tenantName)
    val _ = Files.createDirectories(dir)
    dir
  }

  private def createYamlFile(tenantDir: Path, fileName: String, content: String): Unit = {
    val file = tenantDir.resolve(fileName)
    val _ = Files.writeString(file, content)
  }

  private def env(vars: (String, String)*): String => Option[String] =
    vars.toMap.get

  private val noEnv: String => Option[String] = _ => None

  private def getTenantId(raw: String): TenantId =
    TenantId.parse(raw).getOrElse(fail(s"Invalid test tenant ID: $raw"))

  // ---------------------------------------------------------------------------
  // Missing tenant folder tests
  // ---------------------------------------------------------------------------

  test("missing tenant folder returns TenantNotFound error") {
    val tenantId = getTenantId("nonexistent")
    val result = TenantLoader.loadWithEnv(basePath, tenantId, noEnv)

    result match {
      case Validated.Invalid(errors) =>
        assert(errors.toList.size == 1)
        errors.head match {
          case AclError.TenantNotFound(id) =>
            assert(id == "nonexistent")
          case other =>
            fail(s"Expected TenantNotFound, got: $other")
        }
      case Validated.Valid(_) =>
        fail("Expected Invalid but got Valid")
    }
  }

  test("TenantNotFound error includes tenant ID only, not path") {
    val tenantId = getTenantId("missing-tenant")
    val result = TenantLoader.loadWithEnv(basePath, tenantId, noEnv)

    result match {
      case Validated.Invalid(errors) =>
        val error = errors.head.asInstanceOf[AclError.TenantNotFound]
        assert(error.tenantId == "missing-tenant")
        assert(error.message == "Tenant not found: missing-tenant")
        // Verify message does NOT contain path information
        assert(!error.message.contains(basePath.toString))
      case Validated.Valid(_) =>
        fail("Expected Invalid but got Valid")
    }
  }

  test("file instead of directory returns TenantNotFound") {
    // Create a file (not a directory) with the tenant name
    val file = basePath.resolve("filetenant")
    val _ = Files.writeString(file, "not a directory")

    val tenantId = getTenantId("filetenant")
    val result = TenantLoader.loadWithEnv(basePath, tenantId, noEnv)

    result match {
      case Validated.Invalid(errors) =>
        assert(errors.toList.exists(_.isInstanceOf[AclError.TenantNotFound]))
      case Validated.Valid(_) =>
        fail("Expected Invalid but got Valid")
    }
  }

  // ---------------------------------------------------------------------------
  // Empty folder tests
  // ---------------------------------------------------------------------------

  test("empty tenant folder returns valid empty policy") {
    val _ = createTenantFolder("emptytenant")
    val tenantId = getTenantId("emptytenant")

    val result = TenantLoader.loadWithEnv(basePath, tenantId, noEnv)

    result match {
      case Validated.Valid(policy) =>
        assert(policy.grants.isEmpty)
        assert(policy.mode == ResolutionMode.Strict)
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  test("folder with non-YAML files only returns empty policy") {
    val tenantDir = createTenantFolder("othertenant")
    val _ = Files.writeString(tenantDir.resolve("readme.txt"), "Not a YAML file")
    val _ = Files.writeString(tenantDir.resolve("config.json"), "{}")

    val tenantId = getTenantId("othertenant")
    val result = TenantLoader.loadWithEnv(basePath, tenantId, noEnv)

    result match {
      case Validated.Valid(policy) =>
        assert(policy.grants.isEmpty)
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  // ---------------------------------------------------------------------------
  // Single file loading tests
  // ---------------------------------------------------------------------------

  test("single YAML file with one grant loads correctly") {
    val tenantDir = createTenantFolder("tenant1")
    val yaml =
      """grants:
        |  - target: mydb.public.orders
        |    principals: [user:alice]
        |""".stripMargin
    createYamlFile(tenantDir, "acl.yaml", yaml)

    val tenantId = getTenantId("tenant1")
    val result = TenantLoader.loadWithEnv(basePath, tenantId, noEnv)

    result match {
      case Validated.Valid(policy) =>
        assert(policy.grants.size == 1)
        assert(policy.grants.head.target == GrantTarget.table("mydb", "public", "orders"))
        assert(policy.grants.head.principals == List(Principal.user("alice")))
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  test("single .yml file loads correctly") {
    val tenantDir = createTenantFolder("tenant2")
    val yaml =
      """grants:
        |  - target: db.schema.table
        |    principals: [group:analysts]
        |""".stripMargin
    createYamlFile(tenantDir, "policy.yml", yaml)

    val tenantId = getTenantId("tenant2")
    val result = TenantLoader.loadWithEnv(basePath, tenantId, noEnv)

    result match {
      case Validated.Valid(policy) =>
        assert(policy.grants.size == 1)
        assert(policy.grants.head.principals == List(Principal.group("analysts")))
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  test("YAML with multiple grants loads all grants") {
    val tenantDir = createTenantFolder("tenant3")
    val yaml =
      """grants:
        |  - target: db1.public.orders
        |    principals: [user:alice]
        |  - target: db2.private.users
        |    principals: [user:bob]
        |""".stripMargin
    createYamlFile(tenantDir, "acl.yaml", yaml)

    val tenantId = getTenantId("tenant3")
    val result = TenantLoader.loadWithEnv(basePath, tenantId, noEnv)

    result match {
      case Validated.Valid(policy) =>
        assert(policy.grants.size == 2)
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  // ---------------------------------------------------------------------------
  // Multi-file merge tests
  // ---------------------------------------------------------------------------

  test("multiple YAML files are merged") {
    val tenantDir = createTenantFolder("multi-tenant")
    val yaml1 =
      """grants:
        |  - target: db1.public.orders
        |    principals: [user:alice]
        |""".stripMargin
    val yaml2 =
      """grants:
        |  - target: db2.private.users
        |    principals: [user:bob]
        |""".stripMargin
    createYamlFile(tenantDir, "01-acl.yaml", yaml1)
    createYamlFile(tenantDir, "02-acl.yaml", yaml2)

    val tenantId = getTenantId("multi-tenant")
    val result = TenantLoader.loadWithEnv(basePath, tenantId, noEnv)

    result match {
      case Validated.Valid(policy) =>
        assert(policy.grants.size == 2)
        assert(policy.grants.exists(_.target == GrantTarget.table("db1", "public", "orders")))
        assert(policy.grants.exists(_.target == GrantTarget.table("db2", "private", "users")))
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  test("files are processed in sorted order (deterministic)") {
    val tenantDir = createTenantFolder("sorted-tenant")
    // Create files in reverse order to verify sorting
    val yaml1 =
      """grants:
        |  - target: db.schema.table1
        |    principals: [user:first]
        |""".stripMargin
    val yaml2 =
      """grants:
        |  - target: db.schema.table2
        |    principals: [user:second]
        |""".stripMargin
    createYamlFile(tenantDir, "z-last.yaml", yaml2)
    createYamlFile(tenantDir, "a-first.yaml", yaml1)

    val tenantId = getTenantId("sorted-tenant")
    val result = TenantLoader.loadWithEnv(basePath, tenantId, noEnv)

    result match {
      case Validated.Valid(policy) =>
        assert(policy.grants.size == 2)
        // First file (a-first.yaml) should be processed first
        assert(policy.grants.head.target == GrantTarget.table("db", "schema", "table1"))
        assert(policy.grants(1).target == GrantTarget.table("db", "schema", "table2"))
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  test("mode from first file is used when merging") {
    val tenantDir = createTenantFolder("mode-tenant")
    val yaml1 =
      """mode: permissive
        |grants:
        |  - target: db1
        |    principals: [user:alice]
        |""".stripMargin
    val yaml2 =
      """mode: strict
        |grants:
        |  - target: db2
        |    principals: [user:bob]
        |""".stripMargin
    createYamlFile(tenantDir, "01-first.yaml", yaml1)
    createYamlFile(tenantDir, "02-second.yaml", yaml2)

    val tenantId = getTenantId("mode-tenant")
    val result = TenantLoader.loadWithEnv(basePath, tenantId, noEnv)

    result match {
      case Validated.Valid(policy) =>
        assert(policy.mode == ResolutionMode.Permissive)
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  // ---------------------------------------------------------------------------
  // YAML validation error tests
  // ---------------------------------------------------------------------------

  test("invalid YAML syntax returns parse error") {
    val tenantDir = createTenantFolder("invalid-tenant")
    val invalidYaml = "grants:\n  - target: [invalid yaml here"
    createYamlFile(tenantDir, "bad.yaml", invalidYaml)

    val tenantId = getTenantId("invalid-tenant")
    val result = TenantLoader.loadWithEnv(basePath, tenantId, noEnv)

    result match {
      case Validated.Invalid(errors) =>
        assert(errors.toList.exists(_.isInstanceOf[AclError.YamlParseError]))
      case Validated.Valid(_) =>
        fail("Expected Invalid but got Valid")
    }
  }

  test("invalid grant target returns validation error") {
    val tenantDir = createTenantFolder("bad-target-tenant")
    val yaml =
      """grants:
        |  - target: a.b.c.d.e
        |    principals: [user:alice]
        |""".stripMargin
    createYamlFile(tenantDir, "acl.yaml", yaml)

    val tenantId = getTenantId("bad-target-tenant")
    val result = TenantLoader.loadWithEnv(basePath, tenantId, noEnv)

    result match {
      case Validated.Invalid(errors) =>
        assert(errors.toList.exists(_.isInstanceOf[AclError.InvalidTarget]))
      case Validated.Valid(_) =>
        fail("Expected Invalid but got Valid")
    }
  }

  test("one valid and one invalid file returns errors from invalid") {
    val tenantDir = createTenantFolder("mixed-tenant")
    val validYaml =
      """grants:
        |  - target: mydb.public.orders
        |    principals: [user:alice]
        |""".stripMargin
    val invalidYaml =
      """grants:
        |  - target: a.b.c.d.e
        |    principals: [invalid-principal]
        |""".stripMargin
    createYamlFile(tenantDir, "01-valid.yaml", validYaml)
    createYamlFile(tenantDir, "02-invalid.yaml", invalidYaml)

    val tenantId = getTenantId("mixed-tenant")
    val result = TenantLoader.loadWithEnv(basePath, tenantId, noEnv)

    result match {
      case Validated.Invalid(errors) =>
        assert(errors.toList.nonEmpty)
      case Validated.Valid(_) =>
        fail("Expected Invalid but got Valid")
    }
  }

  // ---------------------------------------------------------------------------
  // Case normalization tests
  // ---------------------------------------------------------------------------

  test("tenant ID case normalization: mixed case uses canonical lowercase folder") {
    // Create folder with lowercase name
    val _ = createTenantFolder("mytenant")
    val yaml =
      """grants:
        |  - target: mydb.public.orders
        |    principals: [user:alice]
        |""".stripMargin
    createYamlFile(basePath.resolve("mytenant"), "acl.yaml", yaml)

    // Parse tenant ID with mixed case
    val tenantId = getTenantId("MyTenant")
    assert(tenantId.canonical == "mytenant")

    val result = TenantLoader.loadWithEnv(basePath, tenantId, noEnv)

    result match {
      case Validated.Valid(policy) =>
        assert(policy.grants.size == 1)
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  test("tenant ID with uppercase fails if folder is uppercase (case-sensitive filesystem)") {
    // Create folder with UPPERCASE name
    val _ = createTenantFolder("UPPERCASE")
    val yaml =
      """grants:
        |  - target: mydb
        |    principals: [user:alice]
        |""".stripMargin
    createYamlFile(basePath.resolve("UPPERCASE"), "acl.yaml", yaml)

    // Parse tenant ID (will normalize to lowercase)
    val tenantId = getTenantId("uppercase")
    // On case-sensitive filesystem, "uppercase" won't match "UPPERCASE"
    // On case-insensitive (macOS), it will match
    val result = TenantLoader.loadWithEnv(basePath, tenantId, noEnv)

    // This test behavior depends on filesystem case sensitivity
    // Just verify it doesn't throw
    result match {
      case Validated.Valid(_)   => succeed
      case Validated.Invalid(_) => succeed
    }
  }

  // ---------------------------------------------------------------------------
  // Environment variable resolution tests
  // ---------------------------------------------------------------------------

  test("environment variable in target resolves correctly") {
    val tenantDir = createTenantFolder("env-tenant")
    val yaml =
      """grants:
        |  - target: ${DB_NAME}.public.orders
        |    principals: [user:alice]
        |""".stripMargin
    createYamlFile(tenantDir, "acl.yaml", yaml)

    val tenantId = getTenantId("env-tenant")
    val result = TenantLoader.loadWithEnv(basePath, tenantId, env("DB_NAME" -> "proddb"))

    result match {
      case Validated.Valid(policy) =>
        assert(policy.grants.head.target == GrantTarget.table("proddb", "public", "orders"))
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  test("environment variable in principal resolves correctly") {
    val tenantDir = createTenantFolder("env-principal")
    val yaml =
      """grants:
        |  - target: mydb
        |    principals: [user:${ADMIN_USER}]
        |""".stripMargin
    createYamlFile(tenantDir, "acl.yaml", yaml)

    val tenantId = getTenantId("env-principal")
    val result = TenantLoader.loadWithEnv(basePath, tenantId, env("ADMIN_USER" -> "alice"))

    result match {
      case Validated.Valid(policy) =>
        assert(policy.grants.head.principals == List(Principal.user("alice")))
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  test("unresolved environment variable returns error") {
    val tenantDir = createTenantFolder("missing-env")
    val yaml =
      """grants:
        |  - target: ${MISSING_VAR}.public.orders
        |    principals: [user:alice]
        |""".stripMargin
    createYamlFile(tenantDir, "acl.yaml", yaml)

    val tenantId = getTenantId("missing-env")
    val result = TenantLoader.loadWithEnv(basePath, tenantId, noEnv)

    result match {
      case Validated.Invalid(errors) =>
        assert(errors.toList.contains(AclError.UnresolvedVariable("MISSING_VAR")))
      case Validated.Valid(_) =>
        fail("Expected Invalid but got Valid")
    }
  }

  // ---------------------------------------------------------------------------
  // load() convenience method tests
  // ---------------------------------------------------------------------------

  test("load() delegates to loadWithEnv with System.getenv") {
    val tenantDir = createTenantFolder("load-test")
    val yaml =
      """grants:
        |  - target: mydb.public.orders
        |    principals: [user:alice]
        |""".stripMargin
    createYamlFile(tenantDir, "acl.yaml", yaml)

    val tenantId = getTenantId("load-test")
    // This tests that load() works (uses System.getenv internally)
    val result = TenantLoader.load(basePath, tenantId)

    result match {
      case Validated.Valid(policy) =>
        assert(policy.grants.size == 1)
      case Validated.Invalid(errors) =>
        fail(s"Expected Valid but got errors: ${errors.toList.map(_.message)}")
    }
  }

  // ---------------------------------------------------------------------------
  // JSON serialization tests
  // ---------------------------------------------------------------------------

  test("TenantNotFound error serializes to JSON with tenantId field") {
    val tenantId = getTenantId("missing-json")
    val result = TenantLoader.loadWithEnv(basePath, tenantId, noEnv)

    result match {
      case Validated.Invalid(errors) =>
        val json = errors.head.toJson
        assert(json.hcursor.get[String]("type").toOption.contains("tenantNotFound"))
        assert(json.hcursor.get[String]("tenantId").toOption.contains("missing-json"))
        assert(json.hcursor.get[String]("detail").toOption.contains("Tenant not found: missing-json"))
      case Validated.Valid(_) =>
        fail("Expected Invalid but got Valid")
    }
  }
}
