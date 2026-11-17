package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{Tenant, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.{DefaultDatabaseReport, PoolSupervisor}
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.manifest.{ConfigManifest, ManifestExporter, ManifestImporter}
import ai.starlake.quack.ondemand.runtime.testkit.{LogCapture, StubQuackBackend}
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.unsafe.implicits.global
import ch.qos.logback.classic.Level
import io.circe.yaml.v12.parser
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}

/** A database's `defaultDatabase` may only name the catalog its nodes resolve unqualified names in
  * (its catalog alias, else its dbName, else its name; `memory` for kind=memory), the catalog the
  * policy rewriters qualify with (DefaultDatabaseResolutionSpec). `database/create` and
  * `database/update` refuse any other value with `400 default_database_mismatch`, naming the field
  * and never the value, writing nothing. A manifest import keeps the row and drops such a value,
  * with one ERROR per row, so a backup always restores. A row stored with one before is reported at
  * boot, once per row, left out of a manifest export, and otherwise ignored.
  */
class TenantDbDefaultDatabaseSpec extends AnyFlatSpec with Matchers with OptionValues:

  private val superuser: SessionScope.Resolver   = _ => SessionScope.Superuser
  private val tenantAdmin: SessionScope.Resolver = _ => SessionScope(false, Set("acme"))

  /** Another catalog's name, which no message may quote back. */
  private val other = "lake2"

  private val fileMeta = Map("dbName" -> "acme_lake", "schemaName" -> "main")

  private def fixture() =
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(new StubQuackBackend(), new NodeLoadTracker, store)
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    (store, sup, new TenantDbHandlers(sup, requireEncryption = false))

  private def createFile(
      h: TenantDbHandlers,
      defaultDatabase: Option[String],
      metastore: Map[String, String] = fileMeta
  ) =
    h.createTenantDb(
      TenantDbRequest(
        tenant = "acme",
        name = "lake",
        kind = "duckdb-file",
        metastore = metastore,
        dataPath = "/nonexistent/ddb/acme_lake.duckdb",
        defaultDatabase = defaultDatabase
      ),
      None
    )(superuser)
      .unsafeRunSync()

  private def createMemory(
      h: TenantDbHandlers,
      defaultDatabase: Option[String],
      scope: SessionScope.Resolver
  ) =
    h.createTenantDb(
      TenantDbRequest(
        tenant = "acme",
        name = "mem",
        kind = "memory",
        defaultDatabase = defaultDatabase
      ),
      None
    )(scope)
      .unsafeRunSync()

  private def update(
      h: TenantDbHandlers,
      req: UpdateTenantDbRequest,
      scope: SessionScope.Resolver
  ) =
    h.update(req, None)(scope).unsafeRunSync()

  private def assertMismatch(res: Either[(sttp.model.StatusCode, ErrorResponse), ?]): Unit =
    val (status, err) = res.left.toOption.value
    status.code shouldBe 400
    err.error shouldBe "default_database_mismatch"
    err.message should include("defaultDatabase")
    withClue("the refused value in the message: ")(err.message.contains(other) shouldBe false)

  private def stored(store: InMemoryControlPlaneStore, name: String) =
    store.snapshot().tenantDbs.find(_.name == name)

  // ---------- database/create ----------

  "database/create" should "refuse a defaultDatabase naming another catalog, writing nothing" in {
    val (store, sup, h) = fixture()
    assertMismatch(createFile(h, Some(other)))
    stored(store, "acme_lake") shouldBe None
    sup.findTenantDb("acme", "acme_lake") shouldBe None
  }

  it should "refuse it from a tenant admin too, on a memory database" in {
    val (store, _, h) = fixture()
    assertMismatch(createMemory(h, Some("fedpg"), tenantAdmin))
    stored(store, "acme_mem") shouldBe None
  }

  it should "accept the database's own catalog, in any case, or none" in {
    val (_, _, h) = fixture()
    createFile(h, Some("ACME_LAKE")).isRight shouldBe true
    val (_, _, h2) = fixture()
    createFile(h2, None).isRight shouldBe true
    val (_, _, h3) = fixture()
    createMemory(h3, Some("memory"), tenantAdmin).isRight shouldBe true
  }

  it should "follow a custom catalogAlias, not the dbName" in {
    val (_, _, h) = fixture()
    assertMismatch(
      createFile(h, Some(other), metastore = fileMeta + ("catalogAlias" -> "sales"))
    )
    val (_, _, h2) = fixture()
    createFile(
      h2,
      Some("acme_lake"),
      metastore = fileMeta + ("catalogAlias" -> "sales")
    ).isLeft shouldBe
      true
    val (_, _, h3) = fixture()
    createFile(
      h3,
      Some("sales"),
      metastore = fileMeta + ("catalogAlias" -> "sales")
    ).isRight shouldBe
      true
  }

  // ---------- database/update ----------

  "database/update" should "refuse a defaultDatabase naming another catalog, leaving the row" in {
    val (store, _, h) = fixture()
    createFile(h, None).isRight shouldBe true
    for scope <- List(superuser, tenantAdmin) do
      assertMismatch(
        update(h, UpdateTenantDbRequest("acme", "acme_lake", defaultDatabase = Some(other)), scope)
      )
    stored(store, "acme_lake").value.defaultDatabase shouldBe None
  }

  it should "accept the database's own catalog, and clear the value when sent empty" in {
    val (store, _, h) = fixture()
    createFile(h, None).isRight shouldBe true
    update(
      h,
      UpdateTenantDbRequest("acme", "acme_lake", defaultDatabase = Some("acme_lake")),
      tenantAdmin
    ).isRight shouldBe true
    stored(store, "acme_lake").value.defaultDatabase shouldBe Some("acme_lake")
    update(
      h,
      UpdateTenantDbRequest("acme", "acme_lake", defaultDatabase = Some("")),
      tenantAdmin
    ).isRight shouldBe true
    stored(store, "acme_lake").value.defaultDatabase shouldBe None
  }

  it should "refuse moving the catalog away from a stored defaultDatabase that named it" in {
    val (store, _, h) = fixture()
    createFile(h, Some("acme_lake")).isRight shouldBe true
    assertMismatch(
      update(
        h,
        UpdateTenantDbRequest(
          "acme",
          "acme_lake",
          metastore = Some(fileMeta + ("catalogAlias" -> "sales"))
        ),
        superuser
      )
    )
    stored(store, "acme_lake").value.metastore.get("catalogAlias") shouldBe None
  }

  it should "keep taking updates that leave a stored mismatched value alone" in {
    val (store, sup, h) = fixture()
    createFile(h, None).isRight shouldBe true
    store.upsertTenantDb(stored(store, "acme_lake").value.copy(defaultDatabase = Some(other)))
    sup.restore()
    update(
      h,
      UpdateTenantDbRequest("acme", "acme_lake", defaultSchema = Some("main")),
      superuser
    ).isRight shouldBe true
    // Sending it again is a write of it, refused; sending it empty is the fix.
    assertMismatch(
      update(
        h,
        UpdateTenantDbRequest("acme", "acme_lake", defaultDatabase = Some(other)),
        superuser
      )
    )
    update(
      h,
      UpdateTenantDbRequest("acme", "acme_lake", defaultDatabase = Some("")),
      superuser
    ).isRight shouldBe true
    stored(store, "acme_lake").value.defaultDatabase shouldBe None
  }

  // ---------- manifest import and export ----------

  private def manifestOf(defaultDatabase: String, kind: String = "duckdb-file"): String =
    s"""apiVersion: quack-on-demand/v1
       |kind: ConfigManifest
       |exportedAt: '2026-11-15T00:00:00Z'
       |exportedFrom: { managerVersion: test, hostname: host }
       |tenants:
       |  - name: acme
       |    tenantDbs:
       |      - name: acme_lake
       |        kind: $kind
       |        dataPath: /nonexistent/ddb/acme_lake.duckdb
       |        defaultDatabase: $defaultDatabase
       |        metastore:
       |          "dbName": "acme_lake"
       |          "schemaName": "main"
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

  "a manifest import" should "import the row without a mismatched defaultDatabase, logging it" in {
    val store            = new InMemoryControlPlaneStore()
    val (result, events) = LogCapture.logged(ManifestImporter.getClass.getName)(
      manifestHandlers(store).importYaml(manifestOf(other), None)(superuser).unsafeRunSync()
    )
    result.isRight shouldBe true
    val row = stored(store, "acme_lake").value
    row.defaultDatabase shouldBe None
    row.metastore shouldBe fileMeta
    // One ERROR naming the tenant and the database, never the value.
    val lines = events.filter(_.getLevel == Level.ERROR).map(_.getFormattedMessage)
    lines.count(_.contains("defaultDatabase")) shouldBe 1
    val line = lines.find(_.contains("defaultDatabase")).value
    line should include("'acme_lake'")
    line should include("'acme'")
    withClue("the value in the log: ")(lines.exists(_.contains(other)) shouldBe false)
  }

  it should "import the database's own catalog, logging nothing about it" in {
    val store            = new InMemoryControlPlaneStore()
    val (result, events) = LogCapture.logged(ManifestImporter.getClass.getName)(
      manifestHandlers(store).importYaml(manifestOf("ACME_LAKE"), None)(superuser).unsafeRunSync()
    )
    result.isRight shouldBe true
    stored(store, "acme_lake").value.defaultDatabase shouldBe Some("ACME_LAKE")
    events.map(_.getFormattedMessage).exists(_.contains("defaultDatabase")) shouldBe false
  }

  "ManifestImporter.validate" should "not refuse it, for the importers that call it directly" in {
    val m = parser.parse(manifestOf(other)).flatMap(_.as[ConfigManifest]).toOption.value
    ManifestImporter.validate(m, new InMemoryControlPlaneStore()).isRight shouldBe true
  }

  "a manifest export" should "leave out a stored mismatched defaultDatabase, log it, and import back" in {
    val (store, sup, h) = fixture()
    createFile(h, None).isRight shouldBe true
    store.upsertTenantDb(stored(store, "acme_lake").value.copy(defaultDatabase = Some(other)))
    sup.restore()
    val (exported, events) = LogCapture.logged(ManifestExporter.getClass.getName)(
      manifestHandlers(store).exportYaml(None)(superuser).unsafeRunSync()
    )
    val yaml = exported.toOption.value
    withClue("the value in the export: ")(yaml.contains(other) shouldBe false)
    events.map(_.getLevel) shouldBe List(Level.ERROR)
    val line = events.head.getFormattedMessage
    line should include("'acme'")
    line should include("'acme_lake'")
    line should include("defaultDatabase")
    withClue("the value in the log: ")(line.contains(other) shouldBe false)
    val replay = new InMemoryControlPlaneStore()
    manifestHandlers(replay).importYaml(yaml, None)(superuser).unsafeRunSync().isRight shouldBe true
    stored(replay, "acme_lake").value.defaultDatabase shouldBe None
  }

  // ---------- boot ----------

  private def row(name: String, kind: TenantDbKind, dd: Option[String], meta: Map[String, String]) =
    TenantDb(
      id = s"td-$name",
      tenantId = "acme",
      name = name,
      kind = kind,
      metastore = meta,
      dataPath = "",
      defaultDatabase = dd
    )

  "the boot report" should "warn once per stored row whose defaultDatabase names another catalog" in {
    val rows = List(
      row("acme_lake", TenantDbKind.DuckDbFile, Some(other), fileMeta),
      row("acme_ok", TenantDbKind.DuckDbFile, Some("ACME_OK"), Map("schemaName" -> "main")),
      row("acme_unset", TenantDbKind.DuckLake, None, Map.empty),
      row("acme_mem", TenantDbKind.InMemory, Some("fedpg"), Map.empty),
      row("acme_memok", TenantDbKind.InMemory, Some("memory"), Map.empty),
      row("acme_alias", TenantDbKind.DuckLake, Some("sales"), Map("catalogAlias" -> "sales"))
    )
    val lines = DefaultDatabaseReport.lines(rows)
    lines.size shouldBe 2
    lines.exists(l => l.contains("'acme'") && l.contains("'acme_lake'")) shouldBe true
    lines.exists(l => l.contains("'acme'") && l.contains("'acme_mem'")) shouldBe true
    lines.foreach { l =>
      l should startWith("WARN: ")
      l should include("is ignored")
      l should include("--default-database ''")
      withClue("a stored value in the line: ")(
        l.contains(other) || l.contains("fedpg") shouldBe false
      )
    }
  }

  it should "run at every boot, from the control plane's rows" in {
    // Main reads the snapshot before any pool starts; pinned on the source, as the call is wiring.
    val main = new String(
      Files.readAllBytes(Paths.get("src/main/scala/ai/starlake/quack/Main.scala")),
      java.nio.charset.StandardCharsets.UTF_8
    )
    main should include("DefaultDatabaseReport.report(snap.tenantDbs)")
  }
