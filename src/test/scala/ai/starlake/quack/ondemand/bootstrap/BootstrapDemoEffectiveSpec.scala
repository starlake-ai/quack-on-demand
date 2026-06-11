package ai.starlake.quack.ondemand.bootstrap

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.edge.sql.{Allowed, Denied, PostgresAclValidator, ValidationContext}
import ai.starlake.quack.model.{NodeSpec, RunningNode}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.manifest.{ConfigManifest, ManifestImporter}
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.IO
import io.circe.yaml.v12.parser
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.io.Source
import scala.util.Using

/** End-to-end ACL behavior of the bundled `bootstrap-demo.yaml`. Each
  * row of the decision matrix in the design spec is one test. The matrix
  * is the contract the YAML must keep producing; if a yaml edit breaks
  * a row, this spec fires before the docs go stale.
  *
  * Note: ManifestImporter stores RbacUser.tenant as the raw YAML tenant
  * name ("acme", "globex"), NOT the surrogate id. The tenantCatalogs
  * function therefore matches on the name string.
  */
class BootstrapDemoEffectiveSpec extends AnyFlatSpec with Matchers:

  // ---- one-time setup -----------------------------------------------

  private def stubBackend: QuackBackend = new QuackBackend:
    def start(s: NodeSpec): IO[RunningNode] = IO.pure(RunningNode(
      s.nodeId, s.poolKey, s.role, "127.0.0.1", 21000, "tok",
      Some(1L), None, Instant.EPOCH, maxConcurrent = s.maxConcurrent))
    def stop(id: String)    = IO.unit
    def isAlive(id: String) = true
    def discoverExisting()  = IO.pure(Nil)
    def cleanup()           = IO.unit

  private val (store, sup) =
    val s        = new InMemoryControlPlaneStore()
    val stream = Option(getClass.getClassLoader.getResourceAsStream("bootstrap-demo.yaml"))
      .getOrElse(fail("bootstrap-demo.yaml not found on classpath"))
    val yaml = Using.resource(Source.fromInputStream(stream, "UTF-8"))(_.mkString)
    val manifest = parser.parse(yaml).flatMap(_.as[ConfigManifest])
      .toOption.getOrElse(fail("bootstrap-demo.yaml failed to parse"))
    ManifestImporter.apply(manifest, s) match
      case Left(errs) => fail(s"importer rejected the demo YAML: ${errs.mkString("; ")}")
      case Right(())  => ()
    val supervisor = new PoolSupervisor(stubBackend, new NodeLoadTracker, s)
    supervisor.restore()
    (s, supervisor)

  // ManifestImporter stores RbacUser.tenant using the raw YAML tenant name
  // ("acme" / "globex"), so tenantCatalogs must key on those strings.
  private val validator = new PostgresAclValidator(
    defaultDatabase = "acme_tpch",
    defaultSchema   = "tpch1",
    tenantCatalogs  = {
      case "acme"   => Set("acme_tpch")
      case "globex" => Set("globex_tpcds")
      case _        => Set.empty
    }
  )

  private def effective(username: String, tenant: Option[String]): EffectiveSet =
    val user = store.findUser(tenant, username).getOrElse(
      fail(s"user $username (tenant=$tenant) missing from store")
    )
    sup.effectiveSetForUser(user.id).getOrElse(
      fail(s"EffectiveSet missing for $username")
    )

  private def ctx(
      sql:            String,
      eff:            EffectiveSet,
      defaultCatalog: String = "acme_tpch",
      defaultSchema:  String = "tpch1"
  ): ValidationContext = ValidationContext(
    username        = eff.user.username,
    database        = s"${eff.user.tenant.getOrElse("?")}/${defaultCatalog}/bi",
    statement       = sql,
    peer            = "spec",
    defaultDatabase = Some(defaultCatalog),
    defaultSchema   = Some(defaultSchema),
    effectiveSet    = Some(eff)
  )

  // ---- the decision matrix (one row per test) -----------------------

  "alice (analyst)" should "SELECT a covered table" in {
    validator.validate(
      ctx("SELECT * FROM customer", effective("alice", Some("acme")))
    ) shouldBe Allowed
  }

  it should "be denied INSERT (RO does not cover Write)" in {
    validator.validate(
      ctx("INSERT INTO customer VALUES (1)", effective("alice", Some("acme")))
    ) shouldBe a [Denied]
  }

  it should "be denied cross-tenant SELECT against globex" in {
    validator.validate(
      ctx(
        "SELECT * FROM globex_tpcds.tpcds1.store_sales",
        effective("alice", Some("acme"))
      )
    ) shouldBe a [Denied]
  }

  "bob (data-eng group: etl + dba)" should "INSERT into lineitem (RW via etl role)" in {
    validator.validate(
      ctx("INSERT INTO lineitem VALUES (1)", effective("bob", Some("acme")))
    ) shouldBe Allowed
  }

  it should "CREATE TABLE in tpch1 (DDL via dba role)" in {
    validator.validate(
      ctx("CREATE TABLE x (id INT)", effective("bob", Some("acme")))
    ) shouldBe Allowed
  }

  "dave (dba only)" should "be denied SELECT (DDL does not cover Read)" in {
    validator.validate(
      ctx("SELECT * FROM customer", effective("dave", Some("acme")))
    ) shouldBe a [Denied]
  }

  "acme-admin (tenant_admin)" should "SELECT inside acme" in {
    validator.validate(
      ctx("SELECT * FROM customer", effective("acme-admin", Some("acme")))
    ) shouldBe Allowed
  }

  it should "be denied SELECT against globex (catalog wildcard tenant-scoped)" in {
    validator.validate(
      ctx(
        "SELECT * FROM globex_tpcds.tpcds1.store_sales",
        effective("acme-admin", Some("acme"))
      )
    ) shouldBe a [Denied]
  }

  "carol (globex analyst via group)" should "SELECT store_sales" in {
    validator.validate(
      ctx(
        "SELECT * FROM store_sales",
        effective("carol", Some("globex")),
        defaultCatalog = "globex_tpcds",
        defaultSchema  = "tpcds1"
      )
    ) shouldBe Allowed
  }

  it should "SELECT from the federated acme_pg via cross_tenant_analyst role" in {
    validator.validate(
      ctx(
        "SELECT * FROM acme_pg.tpch1.customer",
        effective("carol", Some("globex")),
        defaultCatalog = "globex_tpcds",
        defaultSchema  = "tpcds1"
      )
    ) shouldBe Allowed
  }

  it should "be denied SELECT on a federated table she has no grant for" in {
    validator.validate(
      ctx(
        "SELECT * FROM acme_pg.tpch1.lineitem",
        effective("carol", Some("globex")),
        defaultCatalog = "globex_tpcds",
        defaultSchema  = "tpcds1"
      )
    ) shouldBe a [Denied]
  }

  "globex-admin (tenant_admin)" should "be denied federated SELECT (wildcard not in tenantCatalogs)" in {
    // tenantCatalogs returns only the tenant's own tenant-dbs, not the
    // federated catalog aliases registered on those tenant-dbs. So
    // *.*.* ALL does NOT reach `acme_pg` without an explicit named grant.
    validator.validate(
      ctx(
        "SELECT * FROM acme_pg.tpch1.customer",
        effective("globex-admin", Some("globex")),
        defaultCatalog = "globex_tpcds",
        defaultSchema  = "tpcds1"
      )
    ) shouldBe a [Denied]
  }

  "root (superuser)" should "SELECT in any catalog" in {
    validator.validate(
      ctx("SELECT * FROM globex_tpcds.tpcds1.store_sales", effective("root", None))
    ) shouldBe Allowed
  }
