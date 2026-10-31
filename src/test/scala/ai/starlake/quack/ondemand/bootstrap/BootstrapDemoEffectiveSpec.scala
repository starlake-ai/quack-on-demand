package ai.starlake.quack.ondemand.bootstrap

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.acl.parser.Verb
import ai.starlake.quack.edge.sql.GrantMatch
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.manifest.{ConfigManifest, ManifestImporter}
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import io.circe.yaml.v12.parser
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.io.Source
import scala.util.Using

/** End-to-end ACL behavior of the bundled `bootstrap-demo.yaml`. Each row of the decision matrix in
  * the design spec is one test. The matrix is the contract the YAML must keep producing; if a yaml
  * edit breaks a row, this spec fires before the docs go stale.
  *
  * Note: ManifestImporter stores RbacUser.tenant as the tenant surrogate id (resolved from the YAML
  * tenant name), which is what the decision pipeline keys catalogs by. The tenantCatalogs function
  * therefore matches on the id resolved from the imported store.
  *
  * Each row is judged by the node decision pipeline's grant match (`GrantMatch.covers`, as
  * `AuthzDecider` applies it to every object the node describes), over the tables the statement of
  * the row names.
  */
class BootstrapDemoEffectiveSpec extends AnyFlatSpec with Matchers:

  // ---- one-time setup -----------------------------------------------

  private def stubBackend: QuackBackend = StubQuackBackend.noop()

  private val (store, sup) =
    val s      = new InMemoryControlPlaneStore()
    val stream = Option(getClass.getClassLoader.getResourceAsStream("bootstrap-demo.yaml"))
      .getOrElse(fail("bootstrap-demo.yaml not found on classpath"))
    val yaml     = Using.resource(Source.fromInputStream(stream, "UTF-8"))(_.mkString)
    val manifest = parser
      .parse(yaml)
      .flatMap(_.as[ConfigManifest])
      .toOption
      .getOrElse(fail("bootstrap-demo.yaml failed to parse"))
    ManifestImporter.apply(manifest, s, requireEncryption = false) match
      case Left(errs) => fail(s"importer rejected the demo YAML: ${errs.mkString("; ")}")
      case Right(())  => ()
    val supervisor = new PoolSupervisor(stubBackend, new NodeLoadTracker, s)
    supervisor.restore()
    (s, supervisor)

  // The store keys users (and the validator keys catalogs) by tenant id.
  // Post slug-id refactor the tenant id IS the slug ("acme"/"globex"); the
  // display name is decoupled ("Acme Corporation"), so the ids are the slugs.
  private val acmeId   = "acme"
  private val globexId = "globex"

  private def tenantCatalogs(eff: EffectiveSet): Set[String] = eff.user.tenant match
    case Some(`acmeId`)   => Set("acme_tpch")
    case Some(`globexId`) => Set("globex_tpcds")
    case _                => Set.empty

  private def effective(username: String, tenant: Option[String]): EffectiveSet =
    // Callers pass the tenant slug, which post-refactor IS the tenant id.
    val tenantId = tenant
    val user     = store
      .findUser(tenantId, username)
      .getOrElse(
        fail(s"user $username (tenant=$tenant) missing from store")
      )
    sup
      .effectiveSetForUser(user.id)
      .getOrElse(
        fail(s"EffectiveSet missing for $username")
      )

  /** Whether `eff` may access `catalog.schema.table` with `verb`: a superuser always, anyone else
    * on a covering role permission, a wildcard catalog covering the tenant's own tenant-dbs only.
    */
  private def allowed(
      eff: EffectiveSet,
      catalog: String,
      schema: String,
      table: String,
      verb: Verb
  ): Boolean =
    eff.user.tenant.isEmpty ||
      GrantMatch.covers(eff.permissions, tenantCatalogs(eff), catalog, schema, table, verb)

  // ---- the decision matrix (one row per test) -----------------------

  // alice holds [analyst, tenant_admin] in the demo YAML: analyst for the RO/RLS
  // demo, tenant_admin so she can sign in to the admin UI at /ui/?tenant=acme.
  // Her effective grants are the union, so tenant_admin's *.*.* ALL covers Write.
  "alice (analyst + tenant_admin)" should "SELECT a covered table" in {
    allowed(effective("alice", Some("acme")), "acme_tpch", "tpch1", "customer", Verb.Read) shouldBe
      true
  }

  it should "be allowed INSERT via her tenant_admin role (ALL covers Write)" in {
    allowed(effective("alice", Some("acme")), "acme_tpch", "tpch1", "customer", Verb.Write) shouldBe
      true
  }

  it should "be denied cross-tenant SELECT against globex" in {
    allowed(
      effective("alice", Some("acme")),
      "globex_tpcds",
      "tpcds1",
      "store_sales",
      Verb.Read
    ) shouldBe false
  }

  "bob (data-eng group: etl + dba)" should "INSERT into lineitem (RW via etl role)" in {
    allowed(effective("bob", Some("acme")), "acme_tpch", "tpch1", "lineitem", Verb.Write) shouldBe
      true
  }

  it should "CREATE TABLE in tpch1 (DDL via dba role)" in {
    allowed(effective("bob", Some("acme")), "acme_tpch", "tpch1", "x", Verb.Ddl) shouldBe true
  }

  "dave (dba only)" should "be denied SELECT (DDL does not cover Read)" in {
    allowed(effective("dave", Some("acme")), "acme_tpch", "tpch1", "customer", Verb.Read) shouldBe
      false
  }

  "acme-admin (tenant_admin)" should "SELECT inside acme" in {
    allowed(
      effective("acme-admin", Some("acme")),
      "acme_tpch",
      "tpch1",
      "customer",
      Verb.Read
    ) shouldBe true
  }

  it should "be denied SELECT against globex (catalog wildcard tenant-scoped)" in {
    allowed(
      effective("acme-admin", Some("acme")),
      "globex_tpcds",
      "tpcds1",
      "store_sales",
      Verb.Read
    ) shouldBe false
  }

  "carol (globex analyst via group)" should "SELECT store_sales" in {
    allowed(
      effective("carol", Some("globex")),
      "globex_tpcds",
      "tpcds1",
      "store_sales",
      Verb.Read
    ) shouldBe true
  }

  // RO does not cover Write: carol's globex `analyst` role is read-only, and
  // she holds no write grant on globex, so an INSERT is denied. (This row used
  // to be demonstrated by alice, before she gained the tenant_admin role.)
  it should "be denied INSERT into store_sales (RO does not cover Write)" in {
    allowed(
      effective("carol", Some("globex")),
      "globex_tpcds",
      "tpcds1",
      "store_sales",
      Verb.Write
    ) shouldBe false
  }

  it should "SELECT from the federated acme_pg via cross_tenant_analyst role" in {
    allowed(effective("carol", Some("globex")), "acme_pg", "tpch1", "customer", Verb.Read) shouldBe
      true
  }

  it should "be denied SELECT on a federated table she has no grant for" in {
    allowed(effective("carol", Some("globex")), "acme_pg", "tpch1", "lineitem", Verb.Read) shouldBe
      false
  }

  "globex-admin (tenant_admin)" should "be denied federated SELECT (wildcard not in tenantCatalogs)" in {
    // tenantCatalogs returns only the tenant's own tenant-dbs, not the
    // federated catalog aliases registered on those tenant-dbs. So
    // *.*.* ALL does NOT reach `acme_pg` without an explicit named grant.
    allowed(
      effective("globex-admin", Some("globex")),
      "acme_pg",
      "tpch1",
      "customer",
      Verb.Read
    ) shouldBe false
  }

  "root (superuser)" should "SELECT in any catalog" in {
    allowed(effective("root", None), "globex_tpcds", "tpcds1", "store_sales", Verb.Read) shouldBe
      true
  }
