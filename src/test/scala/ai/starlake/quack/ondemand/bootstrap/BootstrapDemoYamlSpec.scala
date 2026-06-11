package ai.starlake.quack.ondemand.bootstrap

import ai.starlake.quack.ondemand.manifest.ConfigManifest
import io.circe.yaml.v12.parser
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.io.Source
import scala.util.Using

/** Structural assertions for the bundled `bootstrap-demo.yaml`. Catches
  * accidental schema drift the next time someone edits the file by hand.
  * The semantic ACL behavior of the file lives in
  * [[BootstrapDemoEffectiveSpec]].
  */
class BootstrapDemoYamlSpec extends AnyFlatSpec with Matchers:

  private def bundledYaml: String =
    val stream = getClass.getClassLoader.getResourceAsStream("bootstrap-demo.yaml")
    if stream == null then fail("bootstrap-demo.yaml not on classpath")
    Using.resource(Source.fromInputStream(stream, "UTF-8"))(_.mkString)

  private def manifest: ConfigManifest =
    parser.parse(bundledYaml).flatMap(_.as[ConfigManifest]) match
      case Right(m)  => m
      case Left(err) => fail(s"failed to parse bundled YAML: ${err.getMessage}")

  "bootstrap-demo.yaml" should "decode as a valid ConfigManifest" in {
    manifest.apiVersion shouldBe ConfigManifest.ApiVersion
    manifest.kind shouldBe ConfigManifest.Kind
  }

  it should "declare both demo tenants" in {
    manifest.tenants.map(_.name).toSet shouldBe Set("acme", "globex")
  }

  it should "register the per-tenant tenant-dbs" in {
    val byName = manifest.tenants.map(t => t.name -> t.tenantDbs.map(_.name).toSet).toMap
    byName.get("acme")   shouldBe Some(Set("acme_tpch"))
    byName.get("globex") shouldBe Some(Set("globex_tpcds"))
  }

  it should "declare the documented roles per tenant" in {
    val byTenant = manifest.roles.groupBy(_.tenant).view.mapValues(_.map(_.name).toSet).toMap
    byTenant("acme")   shouldBe Set("analyst", "etl", "dba", "tenant_admin")
    byTenant("globex") shouldBe Set("analyst", "etl", "tenant_admin", "cross_tenant_analyst")
  }

  it should "register the federation demo: acme_pg under globex_tpcds" in {
    val globexDb = manifest.tenants.find(_.name == "globex").get.tenantDbs.head
    globexDb.federatedSources.map(_.alias) shouldBe List("acme_pg")
    val acmePg = globexDb.federatedSources.head
    acmePg.setupSql should include ("INSTALL postgres")
    acmePg.setupSql should include ("dbname=acme_tpch")
    acmePg.secrets.map(_.name).toSet shouldBe Set("PG_HOST", "PG_PORT", "PG_USER", "PG_PASSWORD")
    // All four secrets must use externalRef (env:) so the demo works in both
    // native (localhost) and docker (postgres service) modes without a YAML edit.
    acmePg.secrets.flatMap(_.externalRef).forall(_.startsWith("env:")) shouldBe true
  }

  it should "declare the documented groups" in {
    val byTenant = manifest.groups.groupBy(_.tenant).view.mapValues(_.map(_.name).toSet).toMap
    byTenant("acme")   shouldBe Set("analysts", "data-eng")
    byTenant("globex") shouldBe Set("analysts")
  }

  it should "declare seven users including a superuser" in {
    manifest.users.size shouldBe 7
    manifest.users.find(_.username == "root").flatMap(_.tenant) shouldBe None
    manifest.users.count(_.tenant.contains("acme"))   shouldBe 4
    manifest.users.count(_.tenant.contains("globex")) shouldBe 2
  }

  it should "only use canonical RBAC verbs" in {
    val verbs = manifest.roles.flatMap(_.permissions).map(_.verb).toSet
    verbs.subsetOf(Set("RO", "RW", "DDL", "ALL")) shouldBe true
  }
