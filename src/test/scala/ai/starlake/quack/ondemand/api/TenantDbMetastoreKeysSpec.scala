package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{Tenant, TenantDb}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.manifest.{ConfigManifest, ManifestImporter}
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.unsafe.implicits.global
import io.circe.yaml.v12.parser
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** SEC-1: a tenant-db's metastore is tenant-admin input, and its keys used to become environment
  * variables of every node's launcher. `database/create`, `database/update` and a manifest import
  * accept only [[TenantDb.KnownMetastoreKeys]] and refuse any other key with
  * `400 invalid_metastore_key`, naming it, before anything is written.
  */
class TenantDbMetastoreKeysSpec extends AnyFlatSpec with Matchers with OptionValues:

  private val noScope: String => Option[SessionScope] = _ => None

  /** Names bash, the dynamic loader or the launcher act on, a manager-owned variable, the manager's
    * own encryption stamp, a secret spelled otherwise, and a name nothing reads.
    */
  private val refused =
    List(
      "BASH_ENV",
      "PATH",
      "LD_PRELOAD",
      "QOD_NODE_BIN",
      "lockdownSql",
      "encrypted",
      "PGPASSWORD",
      "applicationName"
    )

  /** A value no response or message may carry back. */
  private val marker = "sec1-marker-value"

  private def fixture(): (InMemoryControlPlaneStore, PoolSupervisor, TenantDbHandlers) =
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(new StubQuackBackend(), new NodeLoadTracker, store)
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    (store, sup, new TenantDbHandlers(sup, requireEncryption = false))

  private val fileMeta = Map("dbName" -> "acme_lake", "schemaName" -> "main")

  private def create(h: TenantDbHandlers, metastore: Map[String, String]) =
    h.createTenantDb(
      TenantDbRequest(
        tenant = "acme",
        name = "lake",
        kind = "duckdb-file",
        metastore = metastore,
        dataPath = "/nonexistent/sec1/acme_lake.duckdb"
      ),
      None
    )(noScope)
      .unsafeRunSync()

  private def update(h: TenantDbHandlers, metastore: Map[String, String]) =
    h.update(UpdateTenantDbRequest("acme", "acme_lake", metastore = Some(metastore)), None)(
      noScope
    ).unsafeRunSync()

  private def assertRefused(
      res: Either[(sttp.model.StatusCode, ErrorResponse), ?],
      key: String
  ): Unit =
    val (status, err) = res.left.toOption.value
    status.code shouldBe 400
    err.error shouldBe "invalid_metastore_key"
    err.message should include(s"'$key'")
    withClue("the refused value in the message: ")(err.message.contains(marker) shouldBe false)

  "database/create" should "refuse a key outside KnownMetastoreKeys, writing nothing" in:
    refused.foreach { key =>
      withClue(s"$key: ") {
        val (store, sup, h) = fixture()
        assertRefused(create(h, fileMeta + (key -> marker)), key)
        store.snapshot().tenantDbs shouldBe empty
        sup.findTenantDb("acme", "acme_lake") shouldBe None
      }
    }

  it should "name every refused key" in:
    val (_, _, h) = fixture()
    val res       = create(h, fileMeta ++ Map("PATH" -> marker, "BASH_ENV" -> marker))
    assertRefused(res, "BASH_ENV")
    assertRefused(res, "PATH")

  it should "accept every known key" in:
    val (store, _, h) = fixture()
    val all           = Map(
      "pgHost"        -> "h",
      "pgPort"        -> "5432",
      "pgUser"        -> "u",
      "pgPassword"    -> "p",
      "dbName"        -> "acme_lake",
      "catalogAlias"  -> "acme_lake",
      "schemaName"    -> "main",
      "dataPath"      -> "/nonexistent/sec1/acme_lake.duckdb",
      "encryptionKey" -> "k3y"
    )
    all.keySet shouldBe TenantDb.KnownMetastoreKeys
    val res = h
      .createTenantDb(
        TenantDbRequest(
          tenant = "acme",
          name = "lake",
          kind = "duckdb-file",
          metastore = all,
          dataPath = "/nonexistent/sec1/acme_lake.duckdb",
          encrypted = true
        ),
        None
      )(noScope)
      .unsafeRunSync()
    res.isRight shouldBe true
    store.snapshot().tenantDbs.map(_.metastore.keySet) shouldBe List(TenantDb.KnownMetastoreKeys)

  "database/update" should "refuse a key outside KnownMetastoreKeys, writing nothing" in:
    refused.foreach { key =>
      withClue(s"$key: ") {
        val (store, sup, h) = fixture()
        create(h, fileMeta).isRight shouldBe true
        assertRefused(update(h, fileMeta + (key -> marker)), key)
        store.snapshot().tenantDbs.map(_.metastore) shouldBe List(fileMeta)
        sup.findTenantDb("acme", "acme_lake").value.metastore shouldBe fileMeta
      }
    }

  it should "refuse a stored unknown key sent back, and drop it once left out" in:
    val (store, sup, h) = fixture()
    create(h, fileMeta).isRight shouldBe true
    // A row written before the refusal existed: the key stays stored until an update removes it.
    val td = store.snapshot().tenantDbs.head
    store.upsertTenantDb(td.copy(metastore = td.metastore + ("applicationName" -> "qod")))
    sup.restore()
    assertRefused(update(h, fileMeta + ("applicationName" -> "qod")), "applicationName")
    store.snapshot().tenantDbs.head.metastore.contains("applicationName") shouldBe true
    update(h, fileMeta + ("catalogAlias" -> "lake")).isRight shouldBe true
    store.snapshot().tenantDbs.head.metastore shouldBe fileMeta + ("catalogAlias" -> "lake")

  private def manifestYaml(key: String, value: String = marker): String =
    s"""apiVersion: quack-on-demand/v1
       |kind: ConfigManifest
       |exportedAt: '2026-10-24T00:00:00Z'
       |exportedFrom: { managerVersion: test, hostname: host }
       |tenants:
       |  - name: acme
       |    tenantDbs:
       |      - name: acme_lake
       |        kind: duckdb-file
       |        dataPath: /nonexistent/sec1/acme_lake.duckdb
       |        metastore:
       |          dbName: acme_lake
       |          schemaName: main
       |          "$key": $value
       |""".stripMargin

  private def manifestHandlers(store: InMemoryControlPlaneStore): ManifestHandlers =
    val sup = new PoolSupervisor(StubQuackBackend.noop(), new NodeLoadTracker, store)
    new ManifestHandlers(
      store,
      sup,
      managerVersion = "test",
      hostname = "host",
      requireEncryption = false
    )

  "a manifest import" should "refuse a key outside KnownMetastoreKeys, writing nothing" in:
    refused.foreach { key =>
      withClue(s"$key: ") {
        val store = new InMemoryControlPlaneStore()
        val res   =
          manifestHandlers(store).importYaml(manifestYaml(key), None)(noScope).unsafeRunSync()
        assertRefused(res, key)
        store.listTenants() shouldBe empty
        store.snapshot().tenantDbs shouldBe empty
      }
    }

  it should "import a tenant-db holding only known keys" in:
    val store = new InMemoryControlPlaneStore()
    val res   =
      manifestHandlers(store)
        .importYaml(manifestYaml("catalogAlias", "lake"), None)(noScope)
        .unsafeRunSync()
    res.isRight shouldBe true
    store.snapshot().tenantDbs.map(_.metastore) shouldBe List(fileMeta + ("catalogAlias" -> "lake"))

  "ManifestImporter.validate" should "refuse it too, for the importers that call it directly" in:
    val m    = parser.parse(manifestYaml("BASH_ENV")).flatMap(_.as[ConfigManifest]).toOption.value
    val errs = ManifestImporter.validate(m, new InMemoryControlPlaneStore()).left.toOption.value
    errs.exists(e => e.contains("acme_lake") && e.contains("'BASH_ENV'")) shouldBe true
    withClue("the refused value in an error: ")(errs.exists(_.contains(marker)) shouldBe false)
