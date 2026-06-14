// src/test/scala/ai/starlake/quack/ondemand/manifest/ManifestColumnPolicyRoundTripSpec.scala
package ai.starlake.quack.ondemand.manifest

import ai.starlake.quack.ondemand.manifest.ConfigManifest.given
import io.circe.syntax.*
import io.circe.parser.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ManifestColumnPolicyRoundTripSpec extends AnyFlatSpec with Matchers:

  "ManifestRoleColumnPolicy" should "round-trip a mask policy through circe" in {
    val original = ManifestRoleColumnPolicy(
      catalog      = "*",
      schema       = "tpch1",
      table        = "customer",
      column       = "c_email",
      action       = "mask",
      transformSql = Some("'***'")
    )
    val json = original.asJson.noSpaces
    decode[ManifestRoleColumnPolicy](json) shouldBe Right(original)
  }

  it should "round-trip a deny policy with transformSql=None" in {
    val original = ManifestRoleColumnPolicy(
      catalog      = "*",
      schema       = "tpch1",
      table        = "customer",
      column       = "c_ssn",
      action       = "deny",
      transformSql = None
    )
    val json = original.asJson.noSpaces
    decode[ManifestRoleColumnPolicy](json) shouldBe Right(original)
  }

  it should "be reachable through ManifestRole.columnPolicies" in {
    val policy = ManifestRoleColumnPolicy(
      catalog      = "*",
      schema       = "tpch1",
      table        = "customer",
      column       = "c_phone",
      action       = "mask",
      transformSql = Some("'***'")
    )
    val role = ManifestRole(
      tenant         = "acme",
      name           = "analyst",
      description    = None,
      permissions    = Nil,
      columnPolicies = List(policy)
    )
    val json = role.asJson.noSpaces
    decode[ManifestRole](json).map(_.columnPolicies.size) shouldBe Right(1)
    decode[ManifestRole](json).map(_.columnPolicies.head) shouldBe Right(policy)
  }
