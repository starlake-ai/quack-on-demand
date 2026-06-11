// src/test/scala/ai/starlake/quack/ondemand/manifest/ManifestRoundTripSpec.scala
package ai.starlake.quack.ondemand.manifest

import ai.starlake.quack.model.{FederatedSecret, FederatedSource, Pool, RoleDistribution, Tenant, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, InMemoryFederatedSourceStore, RbacRole, RolePermission}
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
      kind        = TenantDbKind.DuckLake,
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
      verb        = "RO"
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

  // ------------------------------------------------------------------
  // Test 3: federation round-trip with redact-and-reuse semantics
  //
  // Uses InMemoryFederatedSourceStore so no Postgres is required.
  // Verifies:
  //   - a source + secret survive export -> import (first pass: real value)
  //   - the value is redacted on export ("***REDACTED***")
  //   - re-importing the redacted YAML over the already-seeded store
  //     reuses the existing value correctly (second pass)
  //   - a third export produces the same structure as the first
  // ------------------------------------------------------------------

  it should "round-trip federated sources and reuse redacted secret values" in {
    val cp = buildSrc()

    // Seed one federated source with one value-backed secret in the source store.
    val srcFed = new InMemoryFederatedSourceStore()
    srcFed.upsertSource(FederatedSource(
      id          = "fs-1",
      tenantDbId  = "td-1",
      alias       = "pg_ext",
      setupSql    = "ATTACH 'dbname=prod' AS pg_ext (TYPE POSTGRES);",
      description = Some("prod postgres"),
      disabled    = false
    ))
    srcFed.upsertSecret(FederatedSecret(
      id                = "fsec-1",
      federatedSourceId = "fs-1",
      name              = "PG_PASSWORD",
      value             = Some("super-secret"),
      externalRef       = None
    ))

    // Step 1: export from the populated source store.
    val manifest1 = ManifestExporter.build(cp, ExportedAt, AdminVersion, Hostname, Some(srcFed))
    val yaml1     = Yaml.pretty(manifest1.asJson)

    // The raw value must NOT appear in the YAML; the redaction sentinel must.
    yaml1 should not include "super-secret"
    yaml1 should     include("***REDACTED***")
    yaml1 should     include("pg_ext")

    // Verify the in-memory manifest carries the redacted value.
    val tdb = manifest1.tenants.head.tenantDbs.head
    tdb.federatedSources should have size 1
    tdb.federatedSources.head.alias shouldBe "pg_ext"
    tdb.federatedSources.head.secrets.head.value shouldBe Some("***REDACTED***")

    // Step 2: first import into a fresh control-plane store, supplying the
    // real value explicitly (not redacted) so the importer can store it.
    val manifest1WithReal = manifest1.copy(
      tenants = manifest1.tenants.map { mt =>
        mt.copy(tenantDbs = mt.tenantDbs.map { mtd =>
          mtd.copy(federatedSources = mtd.federatedSources.map { ms =>
            ms.copy(secrets = ms.secrets.map(_.copy(value = Some("super-secret"))))
          })
        })
      },
      users = manifest1.users.map { u =>
        val hash = (u.tenant, u.username) match
          case (None,           "admin") => adminHash
          case (Some("tpch"),   "alice") => aliceHash
          case _                         => aliceHash
        u.copy(password = Some(hash))
      }
    )

    val dstCp  = new InMemoryControlPlaneStore()
    val dstFed = new InMemoryFederatedSourceStore()
    ManifestImporter.apply(manifest1WithReal, dstCp, Some(dstFed)) shouldBe Right(())

    // Verify the real value landed.
    val tdId    = dstCp.listTenants().flatMap(t => dstCp.listTenantDbs(t.id)).head.id
    val srcList = dstFed.listSources(tdId)
    srcList should have size 1
    srcList.head.alias shouldBe "pg_ext"
    dstFed.listSecrets(srcList.head.id).head.value shouldBe Some("super-secret")

    // Step 3: parse yaml1 (redacted) and re-import over the already-seeded dstFed.
    // The importer must reuse the existing value for the "***REDACTED***" sentinel.
    val parsedRedacted = parser.parse(yaml1).flatMap(_.as[ConfigManifest]).fold(throw _, identity)
    val parsedWithPasswords = parsedRedacted.copy(
      users = parsedRedacted.users.map { u =>
        val hash = (u.tenant, u.username) match
          case (None,           "admin") => adminHash
          case (Some("tpch"),   "alice") => aliceHash
          case _                         => aliceHash
        u.copy(password = Some(hash))
      }
    )
    ManifestImporter.apply(parsedWithPasswords, dstCp, Some(dstFed)) shouldBe Right(())

    // The secret value must have been reused.
    val srcList2 = dstFed.listSources(tdId)
    dstFed.listSecrets(srcList2.head.id).head.value shouldBe Some("super-secret")

    // Step 4: third export from the destination stores must produce the same
    // federated-source structure as the first export (both redact the value).
    val manifest3 = ManifestExporter.build(dstCp, ExportedAt, AdminVersion, Hostname, Some(dstFed))

    manifest3.tenants.head.tenantDbs.head.federatedSources shouldBe
      manifest1.tenants.head.tenantDbs.head.federatedSources
  }