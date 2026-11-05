package ai.starlake.quack.model

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** QoD fills in its own user and password only when a database uses QoD's own server, host and port
  * both, compared after normalizing. Every value here is made up.
  */
class TenantDbServerSpec extends AnyFlatSpec with Matchers:

  private val defaults = Map(
    "pgHost"     -> "pg.internal",
    "pgPort"     -> "5432",
    "pgUser"     -> "qod",
    "pgPassword" -> "default-password",
    "dbName"     -> "qod",
    "schemaName" -> "main"
  )

  private def on(metastore: (String, String)*): Boolean =
    TenantDb.onDefaultServer(metastore.toMap, defaults)

  "TenantDb.onDefaultServer" should "hold for a metastore naming no server" in {
    on() shouldBe true
    on("schemaName" -> "main") shouldBe true
  }

  it should "hold for the default's own host and port" in {
    on("pgHost" -> "pg.internal", "pgPort" -> "5432") shouldBe true
    on("pgHost" -> "pg.internal") shouldBe true
    on("pgPort" -> "5432") shouldBe true
  }

  it should "compare after normalizing case, a trailing dot, brackets and leading zeros" in {
    on("pgHost" -> "PG.Internal") shouldBe true
    on("pgHost" -> "pg.internal.") shouldBe true
    on("pgPort" -> "05432") shouldBe true
    TenantDb.onDefaultServer(Map("pgHost" -> "[::1]"), Map("pgHost" -> "::1")) shouldBe true
  }

  it should "fail for another host or another port" in {
    on("pgHost" -> "pg.other") shouldBe false
    on("pgPort" -> "5433") shouldBe false
    on("pgHost" -> "pg.internal", "pgPort" -> "5433") shouldBe false
    on("pgHost" -> "pg.other", "pgPort" -> "5432") shouldBe false
  }

  it should "resolve nothing: another spelling of the same address is another server" in {
    val local = Map("pgHost" -> "localhost", "pgPort" -> "5432")
    TenantDb.onDefaultServer(Map("pgHost" -> "127.0.0.1"), local) shouldBe false
    TenantDb.onDefaultServer(Map("pgHost" -> "::1"), local) shouldBe false
    TenantDb.onDefaultServer(Map("pgHost" -> "localhost"), local) shouldBe true
  }

  it should "fold ASCII only" in {
    // The Kelvin sign lowercases to an ASCII k.
    TenantDb.onDefaultServer(Map("pgHost" -> "Kube"), Map("pgHost" -> "kube")) shouldBe false
  }

  it should "fail for a blank host or port" in {
    on("pgHost" -> "") shouldBe false
    on("pgPort" -> "") shouldBe false
  }

  it should "fail for a host or a port carrying more than one value" in {
    on("pgHost" -> "pg.internal port=1") shouldBe false
    on("pgHost" -> "pg.internal,pg.other") shouldBe false
    on("pgPort" -> "5432 host=pg.other") shouldBe false
  }

  it should "fail for any value holding whitespace, even at its edge" in {
    on("pgHost" -> " pg.internal ") shouldBe false
    on("pgHost" -> "pg.internal\t") shouldBe false
    on("pgPort" -> " 5432") shouldBe false
    on("pgPort" -> "5432\n") shouldBe false
    on("schemaName" -> "main host=pg.other") shouldBe false
    on("schemaName" -> "main") shouldBe true
  }

  it should "fail for a user or a database name that reads as more connection settings" in {
    on("pgUser" -> "qod host=pg.other") shouldBe false
    on("pgUser" -> "qod\thost=pg.other") shouldBe false
    on("pgUser" -> "qod host=pg.other") shouldBe false
    on("dbName" -> "x host=pg.other") shouldBe false
    on("pgUser" -> "own_user") shouldBe true
  }

  it should "fall back as the spawn scripts do when the defaults name no server" in {
    TenantDb.onDefaultServer(Map.empty, Map.empty) shouldBe true
    TenantDb.onDefaultServer(Map("pgHost" -> "localhost", "pgPort" -> "5432"), Map.empty) shouldBe
      true
    TenantDb.onDefaultServer(Map("pgHost" -> "h"), Map.empty) shouldBe false
  }

  "TenantDb.withDefaults" should "fill a row of QoD's server, the login included" in {
    TenantDb.withDefaults(Map("schemaName" -> "s"), defaults) shouldBe
      defaults.updated("schemaName", "s")
  }

  it should "keep a row's own login on QoD's server" in {
    val own = Map("pgUser" -> "me", "pgPassword" -> "mine")
    TenantDb.withDefaults(own, defaults) shouldBe defaults ++ own
  }

  it should "copy no login onto another server, every other default still filling in" in {
    val filled = TenantDb.withDefaults(Map("pgHost" -> "pg.other"), defaults)
    filled.contains("pgUser") shouldBe false
    filled.contains("pgPassword") shouldBe false
    filled("pgPort") shouldBe "5432"
    filled("schemaName") shouldBe "main"
    val port = TenantDb.withDefaults(Map("pgPort" -> "6000"), defaults)
    port.contains("pgUser") shouldBe false
    port.contains("pgPassword") shouldBe false
  }

  it should "keep the row's own login on another server" in {
    val own = Map("pgHost" -> "pg.other", "pgUser" -> "me", "pgPassword" -> "mine")
    val out = TenantDb.withDefaults(own, defaults)
    out("pgUser") shouldBe "me"
    out("pgPassword") shouldBe "mine"
  }

  it should "copy no login onto a row whose user reads as more settings" in {
    TenantDb
      .withDefaults(Map("pgUser" -> "x host=pg.other"), defaults)
      .contains("pgPassword") shouldBe
      false
  }

  "TenantDb.ownLoginError" should "pass QoD's server, and another with its own login" in {
    TenantDb.ownLoginError(Map.empty, defaults) shouldBe None
    TenantDb.ownLoginError(Map("pgUser" -> "me"), defaults) shouldBe None
    TenantDb.ownLoginError(
      Map("pgHost" -> "pg.other", "pgUser" -> "me", "pgPassword" -> "mine"),
      defaults
    ) shouldBe None
  }

  it should "refuse another server without both, or with a blank one" in
    List(
      Map("pgHost" -> "pg.other"),
      Map("pgPort" -> "6000"),
      Map("pgHost" -> "pg.other", "pgUser"     -> "me"),
      Map("pgHost" -> "pg.other", "pgPassword" -> "mine"),
      Map("pgHost" -> "pg.other", "pgUser"     -> " ", "pgPassword"  -> "mine"),
      Map("pgHost" -> "pg.other", "pgUser"     -> "me", "pgPassword" -> "")
    ).foreach { m =>
      val err = TenantDb.ownLoginError(m, defaults)
      withClue(m.keySet.toString)(err.isDefined shouldBe true)
      err.get should include("pgUser")
      err.get should include("pgPassword")
      err.get should not include "mine"
      err.get should not include "default-password"
    }

  "TenantDb.movedPasswordError" should "refuse a stored password following the row" in {
    val stored = Map("pgHost" -> "pg.a", "pgUser" -> "me", "pgPassword" -> "mine")
    val moved  = stored.updated("pgHost", "pg.b")
    TenantDb
      .movedPasswordError(stored, moved, moved - "pgPassword", defaults)
      .get should include("pgPassword")
    // Back to QoD's own server with the row's password carried: the same.
    val home = Map("pgPassword" -> "mine")
    TenantDb.movedPasswordError(stored, home, Map.empty, defaults).isDefined shouldBe true
    // A password stored under another spelling is carried too.
    val spelled = Map("pgHost" -> "pg.a", "pgUser" -> "me", "PgPassword" -> "mine")
    TenantDb
      .movedPasswordError(
        spelled,
        spelled.updated("pgHost", "pg.b"),
        Map("pgHost" -> "pg.b"),
        defaults
      )
      .isDefined shouldBe true
  }

  it should "pass the password sent again, sent empty, or a row that stays" in {
    val stored = Map("pgHost" -> "pg.a", "pgUser" -> "me", "pgPassword" -> "mine")
    val sent   = stored ++ Map("pgHost" -> "pg.b", "pgPassword" -> "again")
    TenantDb.movedPasswordError(stored, sent, sent, defaults) shouldBe None
    // An empty value removes it: nothing is carried.
    TenantDb.movedPasswordError(
      stored,
      Map("pgUser"     -> "me"),
      Map("pgPassword" -> ""),
      defaults
    ) shouldBe
      None
    TenantDb.movedPasswordError(stored, stored, stored - "pgPassword", defaults) shouldBe None
    // The same server spelled otherwise has not moved.
    val spelled = stored.updated("pgHost", "PG.A.")
    TenantDb.movedPasswordError(stored, spelled, spelled - "pgPassword", defaults) shouldBe None
    // No password stored, nothing to carry: another check judges the new server.
    TenantDb.movedPasswordError(
      Map.empty,
      Map("pgHost" -> "pg.b"),
      Map("pgHost" -> "pg.b"),
      defaults
    ) shouldBe
      None
  }

  private val lake = TenantDb(
    id = "td-1",
    tenantId = "acme",
    name = "acme_lake",
    kind = TenantDbKind.DuckLake,
    metastore = Map("dbName" -> "acme_lake"),
    dataPath = "/d"
  )

  "TenantDb.dbNameChangeError" should "refuse another name" in {
    TenantDb.dbNameChangeError(lake, Map("dbName" -> "globex_lake")).get should include("acme_lake")
    TenantDb.dbNameChangeError(lake, Map("dbName" -> "ACME_LAKE")).isDefined shouldBe true
  }

  it should "pass the name it holds, or one left out" in {
    TenantDb.dbNameChangeError(lake, Map("dbName" -> "acme_lake")) shouldBe None
    TenantDb.dbNameChangeError(lake, Map.empty) shouldBe None
    TenantDb.dbNameChangeError(
      lake.copy(metastore = Map.empty),
      Map("dbName" -> "acme_lake")
    ) shouldBe
      None
  }

  it should "read a name left out as the row's own, against a name the row holds otherwise" in {
    val pinned = lake.copy(metastore = Map("dbName" -> "somewhere_else"))
    TenantDb.dbNameChangeError(pinned, Map.empty).isDefined shouldBe true
    TenantDb.dbNameChangeError(pinned, Map("dbName" -> "somewhere_else")) shouldBe None
  }

  it should "leave a database with no catalog database alone" in {
    val file = lake.copy(kind = TenantDbKind.DuckDbFile)
    TenantDb.dbNameChangeError(file, Map("dbName" -> "other")) shouldBe None
  }

  "TenantDb.validate" should "not let the defaults stand in for a login on another server" in {
    val row = lake.copy(metastore = Map("pgHost" -> "pg.other", "dbName" -> "acme_lake"))
    val err = TenantDb.validate(row, defaults).get
    err should include("pgUser")
    err should include("pgPassword")
    TenantDb.validate(lake, defaults) shouldBe None
  }

  "TenantDb.validateSafety" should "refuse a host or user that reads as more settings" in {
    def errorOf(metastore: (String, String)*) =
      TenantDb.validateSafety(lake.copy(metastore = metastore.toMap))
    errorOf("pgUser" -> "me host=pg.other").get should include("pgUser")
    errorOf("pgUser" -> "me\thost=pg.other").get should include("pgUser")
    errorOf("pgHost" -> "pg.a port=1").get should include("pgHost")
    errorOf("pgHost" -> " pg.a").get should include("pgHost")
    errorOf("pgHost" -> "pg.a/db?sslmode=disable").get should include("pgHost")
    errorOf("pgHost" -> "pg.a#x").get should include("pgHost")
    errorOf("pgHost" -> "pg.a&x").get should include("pgHost")
    errorOf("pgHost" -> "a=b").get should include("pgHost")
    errorOf("pgHost" -> "pg-a.example.com", "pgUser" -> "me_1") shouldBe None
    errorOf("pgHost" -> "[::1]", "pgUser" -> "me@corp.example") shouldBe None
    errorOf("pgHost" -> "pg.a,pg.b") shouldBe None
    errorOf("pgPassword" -> "a password with spaces") shouldBe None
    errorOf("pgPort" -> "5432 host=pg.other").get should include("pgPort")
    errorOf("pgPort" -> " 5432").get should include("pgPort")
    errorOf("dbName" -> "x host=pg.other").get should include("dbName")
    errorOf("schemaName" -> "main host=pg.other").get should include("schemaName")
  }

  "TenantDb.holdsOwnKeys" should "take explicit keys, complete for a remote path's scheme" in {
    val s3    = Map("s3_access_key_id" -> "k", "s3_secret_access_key" -> "s")
    val gcs   = Map("gcs_hmac_key_id" -> "k", "gcs_hmac_secret" -> "s")
    val azure = Map("azure_account" -> "a", "azure_account_key" -> "k")
    List("s3://b/x", "s3a://b/x", "r2://b/x").foreach(TenantDb.holdsOwnKeys(_, s3) shouldBe true)
    TenantDb.holdsOwnKeys("gs://b/x", gcs) shouldBe true
    List("az://c/x", "azure://c/x", "abfss://c@a/x").foreach(
      TenantDb.holdsOwnKeys(_, azure) shouldBe true
    )
  }

  it should "refuse half the keys, blank ones, another scheme's, and any other path" in {
    val s3 = Map("s3_access_key_id" -> "k", "s3_secret_access_key" -> "s")
    TenantDb.holdsOwnKeys("s3://b/x", Map.empty) shouldBe false
    TenantDb.holdsOwnKeys("s3://b/x", s3 - "s3_access_key_id") shouldBe false
    TenantDb.holdsOwnKeys("s3://b/x", s3 - "s3_secret_access_key") shouldBe false
    TenantDb.holdsOwnKeys("s3://b/x", s3.updated("s3_secret_access_key", " ")) shouldBe false
    TenantDb.holdsOwnKeys(
      "s3://b/x",
      Map("s3_region" -> "us-east-1", "s3_endpoint" -> "h")
    ) shouldBe false
    TenantDb.holdsOwnKeys("gs://b/x", s3) shouldBe false
    List("/data/x", "./x", "file:///x", "http://h/x", "gcs://b/x", "S3://b/x", "").foreach {
      TenantDb.holdsOwnKeys(_, s3) shouldBe false
    }
  }

  private val remoteRow = TenantDb(
    "td",
    "acme",
    "acme_x",
    TenantDbKind.DuckLake,
    Map("dbName" -> "acme_x"),
    "s3://b/x",
    objectStore = Map("s3_access_key_id" -> "k", "s3_secret_access_key" -> "s")
  )

  private val qodServer = Map(
    "pgHost"     -> "pg.internal",
    "pgPort"     -> "5432",
    "pgUser"     -> "qod",
    "pgPassword" -> "x",
    "dbName"     -> "qod",
    "schemaName" -> "main",
    "dataPath"   -> "/data/ducklake"
  )

  "TenantDb.metastoreShownToTenant" should "leave out what the defaults filled in, and QoD's server" in {
    def shown(effective: Map[String, String], stored: Map[String, String]) =
      TenantDb.metastoreShownToTenant(effective, stored, qodServer)
    // A sparse row, as the supervisor resolves it.
    shown(
      Map(
        "pgHost"       -> "pg.internal",
        "pgPort"       -> "5432",
        "pgUser"       -> "qod",
        "dbName"       -> "acme_x",
        "schemaName"   -> "main",
        "dataPath"     -> "/data/acme_x",
        "catalogAlias" -> "own"
      ),
      Map("catalogAlias" -> "own")
    ) shouldBe Map("dbName" -> "acme_x", "dataPath" -> "/data/acme_x", "catalogAlias" -> "own")
    // A row holding them itself, on QoD's server, in any spelling.
    val held =
      Map("pgHost" -> "PG.internal.", "pgPort" -> "05432", "pgUser" -> "qod", "PgUser" -> "qod")
    shown(held + ("schemaName" -> "s2"), held + ("schemaName" -> "s2")) shouldBe Map(
      "schemaName" -> "s2"
    )
    // A row on another server shows its own, and not what the defaults filled in.
    val other = Map("pgHost" -> "pg.other", "pgUser" -> "me")
    shown(other + ("pgPort" -> "5432"), other) shouldBe other
    shown(
      other + ("pgPort" -> "5432"),
      other + ("pgPort" -> "5432")
    ) shouldBe other + ("pgPort" -> "5432")
    // Its own schema, equal to the default's, is its own.
    shown(Map("schemaName" -> "main"), Map("schemaName" -> "main")) shouldBe Map(
      "schemaName" -> "main"
    )
  }

  "TenantDb.textShownToTenant" should "withhold a text naming QoD's host, port or user" in {
    def read(text: String) = TenantDb.textShownToTenant(text, qodServer)
    List(
      "Connection to pg.internal:5432 refused. Check that the hostname and port are correct",
      "connection to server at \"pg.internal\" (10.0.0.5), port 5432 failed: FATAL",
      "connection to server at \"10.0.0.5\", port 5432 failed",
      "FATAL: password authentication failed for user \"qod\"",
      "FATAL: role \"qod\" does not exist",
      "ducklake:postgres:host=pg.internal port=5432 dbname=x user=qod",
      "could not translate host name \"pg.internal\" to address",
      "java.net.UnknownHostException: pg.internal",
      "no pg_hba.conf entry for host \"10.0.0.5\", user \"qod\", database \"x\"",
      "jdbc:postgresql://pg.internal/qod",
      "at host=pg.internal",
      "user=qod",
      "port 5432 failed"
    ).foreach(t => withClue(t)(read(t) shouldBe TenantDb.ServerTextWithheld))
    // What names none of them stays, the words that only look alike included.
    List(
      "a superuser sets a database's Postgres server",
      "setupSql is for sourceType 'sql', not 'qod'",
      "not.pg.internal.example refused",
      "port 54321 is taken",
      "user \"qodder\" holds no grant",
      // A name the caller sent, quoted back: the answer says nothing on whether it is QoD's.
      "pool pg.internal is taken",
      "user qod already exists",
      "database qod has pools"
    ).foreach(t => withClue(t)(read(t) shouldBe t))
    // Nothing to name: nothing withheld.
    TenantDb.textShownToTenant(
      "user \"qod\" at h:5432",
      Map.empty
    ) shouldBe "user \"qod\" at h:5432"
  }

  "TenantDb.superuserChoiceError" should "take a create naming no path, or a remote one with keys" in {
    def on(
        path: String,
        store: Map[String, String] = Map.empty,
        meta: Map[String, String] = Map.empty
    ) =
      TenantDb.superuserChoiceError(
        None,
        TenantDbKind.DuckLake,
        path,
        meta,
        store,
        meta,
        store,
        false
      )
    on("") shouldBe None
    on("s3://b/x", remoteRow.objectStore) shouldBe None
    on("s3://b/x").isDefined shouldBe true
    on("/data/x").isDefined shouldBe true
    on("/data/x", remoteRow.objectStore).isDefined shouldBe true
    on(" ").isDefined shouldBe true
    on("", meta = Map("dataPath" -> "/data/x")).isDefined shouldBe true
    on("", meta = Map("dataPath" -> "")).isDefined shouldBe true
    on("s3://b", remoteRow.objectStore).isDefined shouldBe true
    on("s3://b/", remoteRow.objectStore).isDefined shouldBe true
    on("s3://b//", remoteRow.objectStore).isDefined shouldBe true
    on("s3://b/x/..", remoteRow.objectStore).isDefined shouldBe true
    on("s3://b/*", remoteRow.objectStore).isDefined shouldBe true
    on("s3://b/x%2f", remoteRow.objectStore).isDefined shouldBe true
    on("s3://b/x", remoteRow.objectStore) shouldBe None
    on("s3://b/x", remoteRow.objectStore, Map("dataPath" -> "s3://b/x")).isDefined shouldBe true
  }

  it should "read a blank stored password as none when a row moves to another server" in {
    val defaults                = Map("pgHost" -> "pg", "pgPort" -> "5432")
    def moved(password: String) =
      TenantDb.movedPasswordError(
        Map("pgHost" -> "a", "pgPassword" -> password),
        Map("pgHost" -> "b", "pgPassword" -> password),
        Map("pgHost" -> "b"),
        defaults
      )
    moved("  ") shouldBe None
    moved("held").isDefined shouldBe true
  }

  it should "judge an update by what the row would hold" in {
    def on(td: TenantDb, meta: Map[String, String], store: Map[String, String]) =
      TenantDb.superuserChoiceError(Some(td), td.kind, td.dataPath, meta, store, meta, store, false)
    val keys = remoteRow.objectStore
    on(remoteRow, remoteRow.metastore, keys) shouldBe None
    on(remoteRow, remoteRow.metastore, keys.updated("s3_access_key_id", "k2")) shouldBe None
    on(remoteRow, remoteRow.metastore, Map.empty).isDefined shouldBe true
    on(remoteRow, remoteRow.metastore, keys - "s3_secret_access_key").isDefined shouldBe true
    on(remoteRow, remoteRow.metastore + ("dataPath" -> "/x"), keys).isDefined shouldBe true
    // No own keys: left as it is it is not judged, and it takes no change.
    val plain = remoteRow.copy(objectStore = Map.empty)
    on(plain, plain.metastore, Map.empty) shouldBe None
    on(plain, plain.metastore, Map("s3_region" -> "r")).isDefined shouldBe true
    // A path held in the metastore is judged the same, and sent back as it is, no change.
    val inMeta = remoteRow.copy(dataPath = "", metastore = Map("dataPath" -> "s3://b/y"))
    on(inMeta, inMeta.metastore, keys) shouldBe None
    on(inMeta, inMeta.metastore, Map.empty).isDefined shouldBe true
    on(inMeta, Map.empty, keys).isDefined shouldBe true
  }

  it should "judge a tenant admin's objectStore: its own keys, complete, or nothing" in {
    val keys                                             = remoteRow.objectStore
    def create(path: String, store: Map[String, String]) =
      TenantDb.superuserChoiceError(
        None,
        TenantDbKind.DuckLake,
        path,
        Map.empty,
        store,
        Map.empty,
        store,
        false
      )
    create("", Map.empty) shouldBe None
    create("", keys).isDefined shouldBe true
    create("", Map("s3_endpoint" -> "h")).isDefined shouldBe true
    create("s3://b/x", Map("s3_endpoint" -> "h")).isDefined shouldBe true
    create("s3://b/x", keys + ("s3_endpoint" -> "h")) shouldBe None

    def update(
        td: TenantDb,
        store: Map[String, String],
        managed: Boolean = false,
        sentStore: Map[String, String] = Map.empty
    ) =
      TenantDb.superuserChoiceError(
        Some(td),
        td.kind,
        td.dataPath,
        td.metastore,
        store,
        Map.empty,
        sentStore,
        managed
      )
    // A row holding no complete own keys takes no change, whatever is sent.
    val bare  = remoteRow.copy(objectStore = Map.empty)
    val local = bare.copy(dataPath = "/data/x")
    List(bare, local).foreach { row =>
      update(row, Map.empty) shouldBe None
      update(row, keys).isDefined shouldBe true
      update(row, keys + ("s3_endpoint" -> "h")).isDefined shouldBe true
      update(row, Map("s3_endpoint" -> "h")).isDefined shouldBe true
    }
    List(local, bare).map(_.copy(objectStore = Map("s3_endpoint" -> "h"))).foreach { old =>
      update(old, old.objectStore) shouldBe None
      update(old, Map.empty).isDefined shouldBe true
      update(old, keys).isDefined shouldBe true
      update(old, Map("s3_endpoint" -> "h2")).isDefined shouldBe true
      // A secret sent is a change whatever it says, in any spelling.
      update(
        old,
        old.objectStore,
        sentStore = Map("S3_Secret_Access_Key" -> "x")
      ).isDefined shouldBe true
      update(old, old.objectStore, sentStore = Map("s3_secret_access_key" -> "")) shouldBe None
    }
    // A row holding them may change them with the secret sent, and they stay complete.
    update(remoteRow, keys + ("s3_endpoint" -> "h")).isDefined shouldBe true
    update(remoteRow, keys.updated("s3_access_key_id", "k2")).isDefined shouldBe true
    update(remoteRow, keys + ("s3_endpoint" -> "h"), sentStore = keys) shouldBe None
    update(remoteRow, keys, sentStore = keys) shouldBe None
    update(remoteRow, keys.updated("s3_secret_access_key", "s2"), sentStore = keys) shouldBe None
    // Blank, or in another spelling, it is not sent.
    update(
      remoteRow,
      keys + ("s3_endpoint" -> "h"),
      sentStore = Map("s3_secret_access_key" -> " ")
    ).isDefined shouldBe true
    update(
      remoteRow,
      keys + ("s3_endpoint" -> "h"),
      sentStore = Map("S3_Secret_Access_Key" -> "s2")
    ).isDefined shouldBe true
    update(remoteRow, keys - "s3_secret_access_key").isDefined shouldBe true
    update(remoteRow, Map.empty).isDefined shouldBe true
    // A managed row's are QoD's: nothing changes them, complete or not.
    update(remoteRow, keys, managed = true) shouldBe None
    update(remoteRow, keys, managed = true, sentStore = keys).isDefined shouldBe true
    update(remoteRow, keys + ("s3_endpoint" -> "h"), managed = true).isDefined shouldBe true
    update(remoteRow, Map.empty, managed = true).isDefined shouldBe true
  }

  it should "judge a create's server keys, in any spelling of the password" in {
    def on(kind: TenantDbKind, meta: (String, String)*) =
      TenantDb.superuserChoiceError(
        None,
        kind,
        "",
        meta.toMap,
        Map.empty,
        meta.toMap,
        Map.empty,
        false
      )
    on(TenantDbKind.DuckLake) shouldBe None
    on(TenantDbKind.DuckLake, "schemaName" -> "main", "catalogAlias" -> "x") shouldBe None
    TenantDb.ServerKeys.foreach { k =>
      on(TenantDbKind.DuckLake, k -> "v").get should include(k)
      on(TenantDbKind.DuckLake, k -> "").get should include(k)
      on(TenantDbKind.InMemory, k -> "v").get should include(k)
    }
    on(TenantDbKind.DuckLake, "PGPASSWORD" -> "v").get should include("pgPassword")
    TenantDb.ServerKeys.filterNot(_ == "dbName").foreach { k =>
      on(TenantDbKind.DuckDbFile, k -> "v").get should include(k)
    }
    on(TenantDbKind.DuckDbFile, "dbName" -> "cat") shouldBe None
  }

  it should "judge an update's server keys by what the row would hold" in {
    val row = TenantDb(
      "td",
      "acme",
      "acme_x",
      TenantDbKind.DuckLake,
      Map(
        "pgHost"     -> "h",
        "pgPort"     -> "1",
        "pgUser"     -> "u",
        "pgPassword" -> "p",
        "dbName"     -> "acme_x"
      ),
      ""
    )
    def on(result: Map[String, String], sent: Map[String, String]) =
      TenantDb.superuserChoiceError(
        Some(row),
        row.kind,
        "",
        result,
        Map.empty,
        sent,
        Map.empty,
        false
      )
    val same = row.metastore
    val back = same - "pgPassword"
    on(same, back) shouldBe None
    on(same + ("schemaName" -> "main"), back + ("schemaName" -> "main")) shouldBe None
    on(same - "dbName", back - "dbName") shouldBe None
    on(same.updated("pgHost", "h2"), back).get should include("pgHost")
    on(same - "pgHost", back - "pgHost").get should include("pgHost")
    on(same - "pgPort", back - "pgPort").get should include("pgPort")
    on(same - "pgUser", back - "pgUser").get should include("pgUser")
    on(same - "pgPassword", back + ("pgPassword" -> "")).get should include("pgPassword")
    on(same.updated("dbName", "other"), back).get should include("dbName")
    // A password sent is a change, the stored one too.
    on(same, back + ("pgPassword" -> "p")).get should include("pgPassword")
    on(same.updated("pgPassword", "q"), back + ("pgPassword" -> "q")).get should include(
      "pgPassword"
    )
    // An own dbName left out comes back to the row's name.
    val own = row.copy(metastore = same.updated("dbName", "other"))
    TenantDb
      .superuserChoiceError(
        Some(own),
        own.kind,
        "",
        same - "dbName",
        Map.empty,
        back - "dbName",
        Map.empty,
        false
      )
      .get should include("dbName")
    // None of it applies to a duckdb-file's dbName.
    val file = row.copy(kind = TenantDbKind.DuckDbFile, metastore = Map("dbName" -> "cat"))
    TenantDb.superuserChoiceError(
      Some(file),
      file.kind,
      "",
      Map("dbName" -> "cat2"),
      Map.empty,
      Map("dbName" -> "cat2"),
      Map.empty,
      false
    ) shouldBe None
  }

  "TenantDb.dbNameChangeError" should "read a change as the effective metastore resolves it" in {
    val row = TenantDb("td", "acme", "acme_x", TenantDbKind.DuckLake, Map("dbName" -> "acme_x"), "")
    TenantDb.dbNameChangeError(row, Map("dbName" -> "acme_x")) shouldBe None
    TenantDb.dbNameChangeError(row, Map.empty) shouldBe None
    TenantDb.dbNameChangeError(row, Map("dbName" -> "other")).isDefined shouldBe true
    val bare = row.copy(metastore = Map.empty)
    TenantDb.dbNameChangeError(bare, Map("dbName" -> "acme_x")) shouldBe None
    TenantDb.dbNameChangeError(bare, Map("dbName" -> "other")).isDefined shouldBe true
    val own = row.copy(metastore = Map("dbName" -> "other"))
    TenantDb.dbNameChangeError(own, Map("dbName" -> "other")) shouldBe None
    TenantDb.dbNameChangeError(own, Map.empty).isDefined shouldBe true
    // A DuckDB file's names a catalog in the file.
    val file = row.copy(kind = TenantDbKind.DuckDbFile)
    TenantDb.dbNameChangeError(file, Map("dbName" -> "other")) shouldBe None
  }

  "TenantDb.KnownMetastoreKeys" should "leave the manager's stamps to the manager" in {
    TenantDb.KnownMetastoreKeys should not contain TenantDb.EncryptedKey
    TenantDb.KnownMetastoreKeys should not contain TenantDb.CreateDatabaseKey
    TenantDb.metastoreKeyError(Map(TenantDb.CreateDatabaseKey -> "true")).get should include(
      TenantDb.CreateDatabaseKey
    )
  }
