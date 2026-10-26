package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{Tenant, TenantDb, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.manifest.{ConfigManifest, ManifestExporter, ManifestImporter}
import ai.starlake.quack.ondemand.runtime.testkit.{LogCapture, StubQuackBackend}
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import cats.effect.unsafe.implicits.global
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import io.circe.yaml.v12.parser
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** SEC-1: a tenant-db's metastore is tenant-admin input, and its keys used to become environment
  * variables of every node's launcher. `database/create`, `database/update` and a manifest import
  * accept only [[TenantDb.KnownMetastoreKeys]] and refuse any other key with
  * `400 invalid_metastore_key`, naming it, before anything is written; `database/update` also takes
  * a stored secret-named key sent empty, the one way to remove it. A manifest export leaves out
  * every key an import would refuse.
  */
class TenantDbMetastoreKeysSpec extends AnyFlatSpec with Matchers with OptionValues:

  private val superuserScope: SessionScope.Resolver = _ => SessionScope.Superuser

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

  /** Named like an object-store secret: hidden from every response, and kept by an update that
    * leaves it out (`PoolSupervisor.mergeSecretKeys`).
    */
  private val secretNamed = (TenantDb.SecretKeys -- TenantDb.KnownMetastoreKeys).toList.sorted

  /** A value no response or message may carry back. */
  private val marker = "sec1-marker-value"

  private def fixture(): (InMemoryControlPlaneStore, PoolSupervisor, TenantDbHandlers) =
    val store = new InMemoryControlPlaneStore()
    val sup   = new PoolSupervisor(new StubQuackBackend(), new NodeLoadTracker, store)
    sup.createTenant(Tenant("acme")).unsafeRunSync()
    (store, sup, new TenantDbHandlers(sup, requireEncryption = false))

  private val fileMeta = Map("dbName" -> "acme_lake", "schemaName" -> "main")

  /** Every key a metastore may hold, with values its checks accept. */
  private val allKnown = Map(
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

  private def create(
      h: TenantDbHandlers,
      metastore: Map[String, String],
      encrypted: Boolean = false
  ) =
    h.createTenantDb(
      TenantDbRequest(
        tenant = "acme",
        name = "lake",
        kind = "duckdb-file",
        metastore = metastore,
        dataPath = "/nonexistent/sec1/acme_lake.duckdb",
        encrypted = encrypted
      ),
      None
    )(superuserScope)
      .unsafeRunSync()

  private def update(h: TenantDbHandlers, metastore: Map[String, String]) =
    h.update(UpdateTenantDbRequest("acme", "acme_lake", metastore = Some(metastore)), None)(
      superuserScope
    ).unsafeRunSync()

  /** Stores `metastore` on `acme_lake`, like a row written before the refusal existed. */
  private def storeRow(
      store: InMemoryControlPlaneStore,
      sup: PoolSupervisor,
      metastore: Map[String, String]
  ): Unit =
    store.upsertTenantDb(store.snapshot().tenantDbs.head.copy(metastore = metastore))
    sup.restore()

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

  it should "refuse a secret-named key even sent empty: a create has nothing to remove" in:
    secretNamed.foreach { key =>
      withClue(s"$key: ") {
        val (store, _, h) = fixture()
        assertRefused(create(h, fileMeta + (key -> "")), key)
        store.snapshot().tenantDbs shouldBe empty
      }
    }

  it should "accept every known key" in:
    val (store, _, h) = fixture()
    allKnown.keySet shouldBe TenantDb.KnownMetastoreKeys
    // An encryptionKey needs an encrypted database.
    create(h, allKnown, encrypted = true).isRight shouldBe true
    store.snapshot().tenantDbs.map(_.metastore) shouldBe List(allKnown)

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

  it should "refuse a stored unknown key sent back, and accept every known key without it" in:
    val (store, sup, h) = fixture()
    create(h, allKnown, encrypted = true).isRight shouldBe true
    // A row written before the refusal existed: the key stays stored until an update removes it.
    storeRow(store, sup, allKnown + ("applicationName" -> "qod"))
    assertRefused(update(h, allKnown + ("applicationName" -> "qod")), "applicationName")
    store.snapshot().tenantDbs.head.metastore.contains("applicationName") shouldBe true
    // Every known key, with new values but for the file's name and its encryption key.
    val edited = allKnown ++ Map(
      "pgHost"       -> "h2",
      "pgPort"       -> "5433",
      "pgUser"       -> "u2",
      "pgPassword"   -> "p2",
      "catalogAlias" -> "lake",
      "schemaName"   -> "other",
      "dataPath"     -> "/nonexistent/sec1/other.duckdb"
    )
    edited.keySet shouldBe TenantDb.KnownMetastoreKeys
    update(h, edited).isRight shouldBe true
    store.snapshot().tenantDbs.head.metastore shouldBe edited
    sup.findTenantDb("acme", "acme_lake").value.metastore shouldBe edited

  it should "remove a stored secret-named key sent empty, and refuse it sent with a value" in:
    secretNamed shouldBe List("azure_account_key", "gcs_hmac_secret", "s3_secret_access_key")
    secretNamed.foreach { key =>
      withClue(s"$key: ") {
        val (store, sup, h) = fixture()
        val stored          = fileMeta + (key -> marker)
        create(h, fileMeta).isRight shouldBe true
        storeRow(store, sup, stored)
        // Leaving it out keeps it: no response shows it, so none can send it back.
        update(h, fileMeta).isRight shouldBe true
        store.snapshot().tenantDbs.head.metastore shouldBe stored
        assertRefused(update(h, stored), key)
        store.snapshot().tenantDbs.head.metastore shouldBe stored
        sup.findTenantDb("acme", "acme_lake").value.metastore shouldBe stored
        update(h, fileMeta + (key -> "")).isRight shouldBe true
        store.snapshot().tenantDbs.head.metastore shouldBe fileMeta
        sup.findTenantDb("acme", "acme_lake").value.metastore shouldBe fileMeta
      }
    }

  it should "remove one spelled otherwise by its own name, and refuse any other key sent empty" in:
    val (store, sup, h) = fixture()
    val stored          = fileMeta + ("S3_SECRET_ACCESS_KEY" -> marker)
    create(h, fileMeta).isRight shouldBe true
    storeRow(store, sup, stored)
    // Names compare exactly: an empty value removes a key only under a secret-named key's own
    // name, which removes it in any spelling.
    List("S3_SECRET_ACCESS_KEY", "applicationName", "BASH_ENV").foreach { key =>
      withClue(s"$key: ")(assertRefused(update(h, fileMeta + (key -> "")), key))
    }
    store.snapshot().tenantDbs.head.metastore shouldBe stored
    update(h, fileMeta + ("s3_secret_access_key" -> "")).isRight shouldBe true
    store.snapshot().tenantDbs.head.metastore shouldBe fileMeta

  private def manifestOf(metastore: Map[String, String], encrypted: Boolean = false): String =
    val entries =
      metastore.toList.sorted.map { case (k, v) => s"""          "$k": "$v"""" }.mkString("\n")
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
       |        encrypted: $encrypted
       |        metastore:
       |$entries
       |""".stripMargin

  private def manifestYaml(key: String, value: String = marker): String =
    manifestOf(fileMeta + (key -> value))

  private def manifestHandlers(store: InMemoryControlPlaneStore): ManifestHandlers =
    val sup = new PoolSupervisor(StubQuackBackend.noop(), new NodeLoadTracker, store)
    new ManifestHandlers(
      store,
      sup,
      managerVersion = "test",
      hostname = "host",
      requireEncryption = false
    )

  /** `store`'s manifest export, and every line the exporter logs meanwhile at every level (the
    * level is part of what is pinned), kept off the console.
    */
  private def exportLogged(
      store: InMemoryControlPlaneStore
  ): (Either[(sttp.model.StatusCode, ErrorResponse), String], List[ILoggingEvent]) =
    LogCapture.logged(ManifestExporter.getClass.getName)(
      manifestHandlers(store).exportYaml(None)(superuserScope).unsafeRunSync()
    )

  "a manifest import" should "refuse a key outside KnownMetastoreKeys, writing nothing" in:
    refused.foreach { key =>
      withClue(s"$key: ") {
        val store = new InMemoryControlPlaneStore()
        val res   =
          manifestHandlers(store)
            .importYaml(manifestYaml(key), None)(superuserScope)
            .unsafeRunSync()
        assertRefused(res, key)
        store.listTenants() shouldBe empty
        store.snapshot().tenantDbs shouldBe empty
      }
    }

  it should "import a tenant-db holding every known key" in:
    val store = new InMemoryControlPlaneStore()
    val res   =
      manifestHandlers(store)
        .importYaml(manifestOf(allKnown, encrypted = true), None)(superuserScope)
        .unsafeRunSync()
    res.isRight shouldBe true
    store.snapshot().tenantDbs.map(_.metastore) shouldBe List(allKnown)

  "ManifestImporter.validate" should "refuse it too, for the importers that call it directly" in:
    val m    = parser.parse(manifestYaml("BASH_ENV")).flatMap(_.as[ConfigManifest]).toOption.value
    val errs = ManifestImporter.validate(m, new InMemoryControlPlaneStore()).left.toOption.value
    errs.exists(e => e.contains("acme_lake") && e.contains("'BASH_ENV'")) shouldBe true
    withClue("the refused value in an error: ")(errs.exists(_.contains(marker)) shouldBe false)

  // A row stored before the API refused such keys (the admin UI once suggested applicationName)
  // must not make its deployment's export unimportable.
  "a manifest export" should "leave out every key an import refuses, log each, and import back" in:
    val (store, sup, h) = fixture()
    create(h, fileMeta).isRight shouldBe true
    storeRow(store, sup, fileMeta + ("applicationName" -> marker))
    val filesMeta = Map("dbName" -> "acme_files", "schemaName" -> "main")
    store.upsertTenantDb(
      TenantDb(
        id = "td-files",
        tenantId = store.snapshot().tenantDbs.head.tenantId,
        name = "acme_files",
        kind = TenantDbKind.DuckDbFile,
        metastore = filesMeta + ("s3_secret_access_key" -> marker),
        dataPath = "/nonexistent/sec1/acme_files.duckdb"
      )
    )
    val (exported, events) = exportLogged(store)
    val yaml               = exported.toOption.value
    List("applicationName", "s3_secret_access_key", marker).foreach { s =>
      withClue(s"$s in the export: ")(yaml.contains(s) shouldBe false)
    }
    // One line per key left out, naming its tenant, database and name, never its value; at ERROR,
    // since the default root level (logback.xml) drops a WARN.
    events.map(_.getLevel).distinct shouldBe List(Level.ERROR)
    val lines = events.map(_.getFormattedMessage)
    lines.size shouldBe 2
    def names(db: String, key: String) =
      lines.exists(l => l.contains("'acme'") && l.contains(s"'$db'") && l.contains(s"'$key'"))
    names("acme_lake", "applicationName") shouldBe true
    names("acme_files", "s3_secret_access_key") shouldBe true
    withClue("a metastore value in the log: ")(lines.exists(_.contains(marker)) shouldBe false)
    // With how to clean the row (every export repeats the line until then), as a node's dropped-key
    // line says it: the database's full metastore without the key, or with a secret-named one
    // sent empty.
    def advises(key: String, advice: String) =
      lines.exists(l => l.contains(s"'$key'") && l.contains(advice))
    advises(
      "applicationName",
      "Remove it by sending the database's full metastore without it through database/update."
    ) shouldBe true
    advises(
      "s3_secret_access_key",
      "Remove it by sending the database's full metastore through database/update with " +
        "'s3_secret_access_key' set to an empty value"
    ) shouldBe true
    val replay = new InMemoryControlPlaneStore()
    manifestHandlers(replay)
      .importYaml(yaml, None)(superuserScope)
      .unsafeRunSync()
      .isRight shouldBe true
    replay.snapshot().tenantDbs.map(d => d.name -> d.metastore).toMap shouldBe
      Map("acme_lake" -> fileMeta, "acme_files" -> filesMeta)

  // A row stored before the API compared names exactly may hold its password as `PgPassword`. Its
  // nodes use it as the catalog password (NodeMetastore.secrets matches any spelling), so the line
  // says so and how to store it under its own name: removing it would cut the nodes off.
  it should "leave out a password spelled otherwise, saying how to store it as pgPassword" in:
    val (store, sup, h) = fixture()
    create(h, fileMeta).isRight shouldBe true
    storeRow(store, sup, fileMeta + ("PgPassword" -> marker))
    val (exported, events) = exportLogged(store)
    val yaml               = exported.toOption.value
    withClue("a spelling of the password in the export: ")(
      yaml.toLowerCase.contains("pgpassword") shouldBe false
    )
    withClue("the password in the export: ")(yaml.contains(marker) shouldBe false)
    events.map(_.getLevel) shouldBe List(Level.ERROR)
    val line = events.head.getFormattedMessage
    withClue("the password in the log: ")(line.contains(marker) shouldBe false)
    List(
      "tenant 'acme' database 'acme_lake': left out metastore key 'PgPassword'",
      "The database's nodes use it as the catalog password, so a replay of this export runs " +
        "without it.",
      "Fix the row: send the database's full metastore through database/update with " +
        "'pgPassword' set to the password (that replaces every spelling), then export again."
    ).foreach(s => withClue(s"the line says '$s': ")(line.contains(s) shouldBe true))
    withClue("the line advises removing the password: ")(line.contains("Remove it") shouldBe false)

  it should "carry the password once an update stores it as pgPassword, logging nothing" in:
    val (store, sup, h) = fixture()
    create(h, fileMeta).isRight shouldBe true
    storeRow(store, sup, fileMeta + ("PgPassword" -> marker))
    // What the line advises: the database's full metastore, with pgPassword set to the password.
    update(h, fileMeta + ("pgPassword" -> marker)).isRight shouldBe true
    store.snapshot().tenantDbs.head.metastore shouldBe fileMeta + ("pgPassword" -> marker)
    val (exported, events) = exportLogged(store)
    events.size shouldBe 0
    val m = parser.parse(exported.toOption.value).flatMap(_.as[ConfigManifest]).toOption.value
    m.tenants.flatMap(_.tenantDbs).map(d => d.name -> d.metastore) shouldBe
      List("acme_lake" -> (fileMeta + ("pgPassword" -> marker)))

  // An export never carries an encryption key, whatever its spelling, and says nothing about one:
  // the API offers no way to change an encryption key, so a line would have no remedy.
  it should "leave out an encryption key spelled otherwise, silently" in:
    val (store, sup, h) = fixture()
    create(h, fileMeta, encrypted = true).isRight shouldBe true
    storeRow(store, sup, fileMeta + ("EncryptionKey" -> marker))
    val (exported, events) = exportLogged(store)
    val yaml               = exported.toOption.value
    withClue("a spelling of the key in the export: ")(
      yaml.toLowerCase.contains("encryptionkey") shouldBe false
    )
    withClue("the key in the export: ")(yaml.contains(marker) shouldBe false)
    events.size shouldBe 0
