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

  it should "compare after normalizing case, a trailing dot, spaces, brackets and leading zeros" in {
    on("pgHost" -> "PG.Internal") shouldBe true
    on("pgHost" -> "pg.internal.") shouldBe true
    on("pgHost" -> " pg.internal ") shouldBe true
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

  it should "fail for a blank host or port: the launchers would fall back on their own" in {
    on("pgHost" -> "") shouldBe false
    on("pgPort" -> "") shouldBe false
  }

  it should "fail for a host or a port carrying more than one value" in {
    on("pgHost" -> "pg.internal port=1") shouldBe false
    on("pgHost" -> "pg.internal,pg.other") shouldBe false
    on("pgPort" -> "5432 host=pg.other") shouldBe false
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
  }

  "TenantDb.KnownMetastoreKeys" should "leave the manager's stamps to the manager" in {
    TenantDb.KnownMetastoreKeys should not contain TenantDb.EncryptedKey
    TenantDb.KnownMetastoreKeys should not contain TenantDb.CreateDatabaseKey
    TenantDb.metastoreKeyError(Map(TenantDb.CreateDatabaseKey -> "true")).get should include(
      TenantDb.CreateDatabaseKey
    )
  }
