// src/test/scala/ai/starlake/quack/ondemand/manifest/ConfigManifestSpec.scala
package ai.starlake.quack.ondemand.manifest

import io.circe.syntax.*
import io.circe.yaml.v12.Printer
import io.circe.yaml.v12.parser
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class ConfigManifestSpec extends AnyFlatSpec with Matchers:

  private val sample = ConfigManifest(
    apiVersion = "quack-on-demand/v1",
    kind       = "ConfigManifest",
    exportedAt = Instant.parse("2026-06-05T12:00:00Z"),
    exportedFrom = ExportedFrom(managerVersion = "0.2.0", hostname = "qod-test"),
    tenants = List(
      ManifestTenant(
        name         = "tpch",
        disabled     = false,
        authProvider = "db",
        authConfig   = Map.empty,
        tenantDbs    = List(ManifestTenantDb("tpch_tpch1", Map.empty, Map.empty)),
        pools        = List(ManifestPool(
          name = "sales", tenantDb = "tpch_tpch1",
          roleDistribution = ManifestRoleDistribution(1, 1, 1),
          maxConcurrentPerNode = 0, disabled = false
        )),
        identities = Nil
      )
    ),
    roles = List(
      ManifestRole("tpch", "reader", Some("read-only"), List(
        ManifestTablePermission("tpch_tpch1", "tpch1", "customer", "SELECT")
      ))
    ),
    groups = Nil,
    users  = List(
      ManifestUser(
        tenant = Some("tpch"), username = "alice", password = None,
        role = "user", enabled = true,
        roles = List("reader"), groups = Nil,
        poolGrants = List(ManifestPoolGrant(pool = Some("sales")))
      )
    )
  )

  private val printer = Printer.builder.withDropNullKeys(true).build()

  "ConfigManifest" should "round-trip via circe-yaml" in {
    val yaml   = printer.pretty(sample.asJson)
    val parsed = parser.parse(yaml).flatMap(_.as[ConfigManifest]).fold(throw _, identity)
    parsed shouldBe sample
  }

  it should "omit users[].password when None" in {
    val yaml = printer.pretty(sample.asJson)
    yaml should not include "password"
  }