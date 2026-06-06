// src/test/scala/ai/starlake/quack/ondemand/manifest/ManifestRoundTripSpec.scala
package ai.starlake.quack.ondemand.manifest

import ai.starlake.quack.model.{Pool, RoleDistribution, Tenant, TenantDb}
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RbacRole, RolePermission}
import at.favre.lib.crypto.bcrypt.BCrypt
import io.circe.syntax.*
import io.circe.yaml.v12.Printer
import io.circe.yaml.v12.parser
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class ManifestRoundTripSpec extends AnyFlatSpec with Matchers:

  private val Yaml = Printer.builder.withDropNullKeys(true).build()

  private val ExportedAt   = Instant.parse("2026-06-05T12:00:00Z")
  private val AdminVersion = "0.2.0"
  private val Hostname     = "test"

  private val adminHash = BCrypt.withDefaults().hashToString(12, "admin-secret".toCharArray)
  private val aliceHash = BCrypt.withDefaults().hashToString(12, "alice-secret".toCharArray)

  /** Build and populate the source store with a representative mix. Returns
    * the store plus the user ids for admin and alice so tests can look up
    * role edges. */
  private def buildSrc(): InMemoryControlPlaneStore =
    val s = new InMemoryControlPlaneStore()

    // Tenant
    s.upsertTenant(Tenant(id = "t-1", name = "tpch", displayName = "tpch", authProvider = "db"))

    // TenantDb
    s.upsertTenantDb(TenantDb(
      id          = "td-1",
      tenantId    = "t-1",
      name        = "tpch_tpch1",
      metastore   = Map.empty,
      dataPath    = "/tmp/data",
      objectStore = Map.empty
    ))

    // Pool
    s.upsertPool(Pool(
      id                   = "p-1",
      tenantId             = "t-1",
      tenantDbId           = "td-1",
      name                 = "sales",
      size                 = 3,
      distribution         = RoleDistribution(writeonly = 1, readonly = 1, dual = 1),
      maxConcurrentPerNode = 0,
      disabled             = false
    ))

    // Role with one table permission
    s.upsertRole(RbacRole(id = "r-1", tenantId = "t-1", name = "reader",
                          description = Some("read-only")))
    s.insertRolePermission(RolePermission(
      id          = "rp-1",
      roleId      = "r-1",
      catalogName = "tpch_tpch1",
      schemaName  = "tpch1",
      tableName   = "customer",
      verb        = "SELECT"
    ))

    // Superuser admin
    s.upsertUserWithHash(tenant = None, username = "admin", passwordHash = adminHash, role = "admin")

    // Tenant-scoped alice, bound to the reader role
    val aliceId = s.upsertUserWithHash(
      tenant = Some("tpch"), username = "alice", passwordHash = aliceHash, role = "user"
    )
    s.addUserRole(aliceId, "r-1")

    s

  // ------------------------------------------------------------------
  // Test 1: structural round-trip
  //
  // SnakeYAML's emitter under circe-yaml-v12 isn't deterministic on
  // map-key order between separate `Printer.pretty` calls, so a strict
  // byte-equality assertion is too brittle. The semantic invariant we
  // care about is that an export -> import -> export cycle yields the
  // same `ConfigManifest` value (which is what backup / restore depends
  // on).
  // ------------------------------------------------------------------

  "ManifestRoundTripSpec" should "round-trip a populated store to the same ConfigManifest value" in {
    val src = buildSrc()

    // Step 1-2: first export
    val manifest1 = ManifestExporter.build(src, ExportedAt, AdminVersion, Hostname)
    val yaml1     = Yaml.pretty(manifest1.asJson)

    // Step 3-4: parse the YAML back
    val parsed = parser.parse(yaml1).flatMap(_.as[ConfigManifest]).fold(throw _, identity)

    // Step 5: supply passwords so the importer has credentials for a fresh store
    val withPasswords = parsed.copy(
      users = parsed.users.map { u =>
        val hash = (u.tenant, u.username) match
          case (None,           "admin") => adminHash
          case (Some("tpch"),   "alice") => aliceHash
          case _                         => aliceHash   // safe fallback
        u.copy(password = Some(hash))
      }
    )

    // Step 6: import into a fresh store
    val dst = new InMemoryControlPlaneStore()
    ManifestImporter.apply(withPasswords, dst) shouldBe Right(())

    // Step 7: second export from the imported store
    val manifest2 = ManifestExporter.build(dst, ExportedAt, AdminVersion, Hostname)

    // Manifest1 still carries the synthetic passwords we attached above; the
    // exporter never emits passwords, so manifest2's users all have
    // `password = None`. Strip manifest1's password fields before comparing
    // so the round-trip equality is on the structure the store actually owns.
    val manifest1ForCompare =
      manifest1.copy(users = manifest1.users.map(_.copy(password = None)))

    manifest2 shouldBe manifest1ForCompare
  }

  // ------------------------------------------------------------------
  // Test 2: password preservation across a password-less second import
  // ------------------------------------------------------------------

  it should "preserve existing password hashes when a second import carries no password fields" in {
    val src = buildSrc()

    // Export
    val manifest1 = ManifestExporter.build(src, ExportedAt, AdminVersion, Hostname)
    val yaml1     = Yaml.pretty(manifest1.asJson)

    // Parse
    val parsed = parser.parse(yaml1).flatMap(_.as[ConfigManifest]).fold(throw _, identity)

    // First import: supply passwords so the fresh store gets credentials
    val withPasswords = parsed.copy(
      users = parsed.users.map { u =>
        val hash = (u.tenant, u.username) match
          case (None,           "admin") => adminHash
          case (Some("tpch"),   "alice") => aliceHash
          case _                         => aliceHash
        u.copy(password = Some(hash))
      }
    )
    val dst = new InMemoryControlPlaneStore()
    ManifestImporter.apply(withPasswords, dst) shouldBe Right(())

    // Verify passwords landed correctly after the first import
    dst.getPasswordHash(None, "admin").get shouldBe adminHash

    // Second import: use the original parsed manifest which has NO password fields
    ManifestImporter.apply(parsed, dst) shouldBe Right(())

    // Passwords must be unchanged after the password-less second import
    dst.getPasswordHash(None, "admin").get shouldBe adminHash
    dst.getPasswordHash(Some("tpch"), "alice").get shouldBe aliceHash
  }