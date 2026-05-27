package ai.starlake.acl.store

import ai.starlake.acl.AclError
import ai.starlake.acl.model.TenantId
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterEach, EitherValues}

import java.nio.file.{Files, Path}
import scala.jdk.StreamConverters.*

class BlobAclStoreTest extends AnyFunSuite with Matchers with BeforeAndAfterEach with EitherValues:

  private var tempDir: Path = scala.compiletime.uninitialized
  private var store: BlobAclStore = scala.compiletime.uninitialized

  override def beforeEach(): Unit =
    tempDir = Files.createTempDirectory("blob-acl-store-test")
    store = BlobAclStore.forLocalFs(tempDir)

  override def afterEach(): Unit =
    store.close()
    if Files.exists(tempDir) then
      Files
        .walk(tempDir)
        .toScala(List)
        .sortBy(-_.toString.length)
        .foreach(Files.deleteIfExists)

  private def tenant(name: String): TenantId =
    TenantId.parse(name).toOption.get

  // --- tenantExists ---

  test("tenantExists returns true for existing directory") {
    Files.createDirectory(tempDir.resolve("tenant-a"))
    store.tenantExists(tenant("tenant-a")) shouldBe true
  }

  test("tenantExists returns false for missing directory") {
    store.tenantExists(tenant("nonexistent")) shouldBe false
  }

  // --- listYamlFiles ---

  test("listYamlFiles returns empty for empty directory") {
    Files.createDirectory(tempDir.resolve("empty"))
    store.listYamlFiles(tenant("empty")).value shouldBe List.empty
  }

  test("listYamlFiles returns sorted .yaml and .yml files") {
    val tenantDir = Files.createDirectory(tempDir.resolve("tenant-b"))
    Files.writeString(tenantDir.resolve("z.yaml"), "z")
    Files.writeString(tenantDir.resolve("a.yml"), "a")
    Files.writeString(tenantDir.resolve("m.yaml"), "m")

    store.listYamlFiles(tenant("tenant-b")).value shouldBe
      List("a.yml", "m.yaml", "z.yaml")
  }

  test("listYamlFiles ignores non-YAML files") {
    val tenantDir = Files.createDirectory(tempDir.resolve("tenant-c"))
    Files.writeString(tenantDir.resolve("grants.yaml"), "yaml")
    Files.writeString(tenantDir.resolve("readme.md"), "md")
    Files.writeString(tenantDir.resolve("data.json"), "json")

    store.listYamlFiles(tenant("tenant-c")).value shouldBe List("grants.yaml")
  }

  test("listYamlFiles returns Left for nonexistent tenant") {
    val result = store.listYamlFiles(tenant("missing"))
    result.isLeft shouldBe true
  }

  // --- readFile ---

  test("readFile returns file content as string") {
    val tenantDir = Files.createDirectory(tempDir.resolve("tenant-d"))
    Files.writeString(tenantDir.resolve("acl.yaml"), "grants:\n  - target: db.schema.table")

    store.readFile(tenant("tenant-d"), "acl.yaml").value shouldBe
      "grants:\n  - target: db.schema.table"
  }

  test("readFile returns Left for missing file") {
    Files.createDirectory(tempDir.resolve("tenant-e"))
    val result = store.readFile(tenant("tenant-e"), "nonexistent.yaml")
    result.isLeft shouldBe true
  }

  // --- listTenants ---

  test("listTenants returns tenant directories sorted") {
    Files.createDirectory(tempDir.resolve("zeta"))
    Files.createDirectory(tempDir.resolve("alpha"))
    Files.createDirectory(tempDir.resolve("mu"))

    val tenants = store.listTenants().value
    tenants.map(_.canonical) shouldBe List("alpha", "mu", "zeta")
  }

  test("listTenants returns empty when no directories exist") {
    store.listTenants().value shouldBe List.empty
  }

  // --- listYamlFilesWithMetadata ---

  test("listYamlFilesWithMetadata returns entries with lastModified") {
    val tenantDir = Files.createDirectory(tempDir.resolve("tenant-f"))
    Files.writeString(tenantDir.resolve("acl.yaml"), "grants: []")

    val entries = store.listYamlFilesWithMetadata(tenant("tenant-f")).value
    entries should have size 1
    entries.head.name shouldBe "acl.yaml"
    entries.head.lastModified shouldBe defined
  }

  // --- close ---

  test("close is idempotent") {
    store.close()
    store.close()
  }
