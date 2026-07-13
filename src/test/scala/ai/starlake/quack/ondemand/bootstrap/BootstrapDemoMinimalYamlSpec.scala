package ai.starlake.quack.ondemand.bootstrap

import ai.starlake.quack.ondemand.manifest.ConfigManifest
import io.circe.yaml.v12.parser
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.io.Source
import scala.util.Using

/** Structural assertions for the bundled `bootstrap-demo-minimal.yaml` (DEMO=minimal profile):
  * one tenant, one pool, one dual node, lean RBAC. Mirrors [[BootstrapDemoYamlSpec]] so hand
  * edits that drift the profile's contract fail fast.
  */
class BootstrapDemoMinimalYamlSpec extends AnyFlatSpec with Matchers:

  private def bundledYaml: String =
    val stream = getClass.getClassLoader.getResourceAsStream("bootstrap-demo-minimal.yaml")
    if stream == null then fail("bootstrap-demo-minimal.yaml not on classpath")
    Using.resource(Source.fromInputStream(stream, "UTF-8"))(_.mkString)

  private def manifest: ConfigManifest =
    parser.parse(bundledYaml).flatMap(_.as[ConfigManifest]) match
      case Right(m)  => m
      case Left(err) => fail(s"failed to parse bundled YAML: ${err.getMessage}")

  "bootstrap-demo-minimal.yaml" should "decode as a valid ConfigManifest" in {
    manifest.apiVersion shouldBe ConfigManifest.ApiVersion
    manifest.kind shouldBe ConfigManifest.Kind
  }

  it should "declare only the acme tenant with the acme_tpch tenant-db" in {
    manifest.tenants.map(_.name) shouldBe List("acme")
    manifest.tenants.head.tenantDbs.map(_.name) shouldBe List("acme_tpch")
  }

  it should "declare a single pool with exactly one dual node" in {
    val pools = manifest.tenants.head.pools
    pools.map(_.name) shouldBe List("bi")
    pools.head.tenantDb shouldBe "acme_tpch"
    pools.head.roleDistribution.writeonly shouldBe 0
    pools.head.roleDistribution.readonly shouldBe 0
    pools.head.roleDistribution.dual shouldBe 1
  }

  it should "carry no federation demo" in {
    manifest.tenants.flatMap(_.tenantDbs).flatMap(_.federatedSources) shouldBe Nil
  }

  it should "declare the lean role set with the RLS/CLS demo intact" in {
    manifest.roles.map(r => (r.tenant, r.name)).toSet shouldBe
      Set(("acme", "analyst"), ("acme", "tenant_admin"))
    val analyst = manifest.roles.find(_.name == "analyst").get
    analyst.columnPolicies.map(_.column) shouldBe List("c_phone")
    analyst.rowPolicies.map(_.predicateSql) shouldBe List("c_mktsegment = 'BUILDING'")
  }

  it should "declare the lean users, all pool grants on bi" in {
    manifest.users.map(_.username).toSet shouldBe Set("root", "admin", "alice", "acme-admin")
    val grants = manifest.users.flatMap(_.poolGrants).flatMap(_.pool).toSet
    grants shouldBe Set("bi")
  }
