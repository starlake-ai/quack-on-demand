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

  // Regression: hand-rolled decoders must honor Scala 3 default values when
  // a field is omitted from the YAML. circe's deriveCodec generates strict
  // decoders that always require every field; we replaced them with
  // Decoder.instance bodies that call `c.getOrElse[T](name)(default)`.
  it should "decode a manifest that omits every defaulted field" in {
    val minimal =
      """apiVersion: quack-on-demand/v1
        |kind: ConfigManifest
        |exportedAt: '2026-06-05T12:00:00Z'
        |exportedFrom: { managerVersion: x, hostname: y }
        |""".stripMargin
    val parsed = parser.parse(minimal).flatMap(_.as[ConfigManifest]).fold(throw _, identity)
    parsed.tenants shouldBe empty
    parsed.roles   shouldBe empty
    parsed.groups  shouldBe empty
    parsed.users   shouldBe empty
  }

  it should "decode a tenant that omits authProvider / tenantDbs / pools / identities" in {
    val yaml =
      """apiVersion: quack-on-demand/v1
        |kind: ConfigManifest
        |exportedAt: '2026-06-05T12:00:00Z'
        |exportedFrom: { managerVersion: x, hostname: y }
        |tenants:
        |  - name: tpch
        |""".stripMargin
    val parsed = parser.parse(yaml).flatMap(_.as[ConfigManifest]).fold(throw _, identity)
    val tenant = parsed.tenants.head
    tenant.name         shouldBe "tpch"
    tenant.disabled     shouldBe false
    tenant.authProvider shouldBe "db"
    tenant.authConfig   shouldBe Map.empty[String, String]
    tenant.tenantDbs    shouldBe empty
    tenant.pools        shouldBe empty
    tenant.identities   shouldBe empty
  }

  it should "decode a user that omits role / enabled / roles / groups / poolGrants" in {
    val yaml =
      """apiVersion: quack-on-demand/v1
        |kind: ConfigManifest
        |exportedAt: '2026-06-05T12:00:00Z'
        |exportedFrom: { managerVersion: x, hostname: y }
        |users:
        |  - tenant: null
        |    username: admin
        |""".stripMargin
    val parsed = parser.parse(yaml).flatMap(_.as[ConfigManifest]).fold(throw _, identity)
    val user = parsed.users.head
    user.tenant     shouldBe None
    user.username   shouldBe "admin"
    user.password   shouldBe None
    user.role       shouldBe "user"
    user.enabled    shouldBe true
    user.roles      shouldBe empty
    user.groups     shouldBe empty
    user.poolGrants shouldBe empty
  }