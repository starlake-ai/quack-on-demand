package ai.starlake.quack.it.authz

import ai.starlake.quack.ondemand.runtime.QodNodeTestBinary
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import io.circe.Json
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{BufferedInputStream, BufferedOutputStream, DataInputStream, DataOutputStream}
import java.net.{InetSocketAddress, ServerSocket, Socket}
import java.nio.file.attribute.FileTime
import java.nio.file.{Files, Path}
import java.sql.DriverManager
import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

/** What one tenant's database may reach, on a real manager and real nodes, as a real tenant admin
  * and as a superuser: a database name composes for one tenant only, only a superuser names a
  * Postgres server, QoD's login reaches QoD's server only, a catalog name is fixed once made, a
  * create or a delete acts only on a database the row owns, and only a superuser names a data path.
  * Each case checks the owner's rows afterwards, through the owner's own pool. Manager on 20944
  * (REST) / 31364 (FlightSQL) / 29564 (front door), relay 20946, nodes 23360-23369, its own control
  * plane; every database is named after a per-run hex and dropped by exact name at the end.
  *
  * The listener records whether a connection or a password arrived and whether that password equals
  * a known value, as booleans: no password is kept, logged or printed.
  */
class TenantIsolationE2ESpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:
  import TenantIsolationE2ESpec.*

  private val run = java.lang.Long.toHexString(System.nanoTime() & 0xffffffL)

  /** What [[AuthzStack.boot]] drops of a killed earlier run: `isox`, a hex, then `_` or the end. */
  private val Prefix = "isox"

  private lazy val missing: Option[String] =
    if scala.util.Properties.isWin then Some("the bash spawn script")
    else if !TestPostgres.reachable then
      Some(s"test Postgres not reachable at ${TestPostgres.pgHost}:${TestPostgres.pgPort}")
    else if QodNodeTestBinary.path.isEmpty then Some("no qod-node build (QOD_NODE_BIN)")
    else None

  private lazy val root: Path = Files.createTempDirectory("qod-iso").toRealPath()

  /** Where QoD places a database that names no path: beside the default data path. */
  private lazy val lakes: Path = Files.createDirectories(root.resolve("lakes"))

  private var stack: AuthzStack = null

  /** Roles a case created on the test Postgres, dropped once their databases are. */
  private val roles = ListBuffer.empty[String]

  /** A superuser's session. */
  private lazy val su: String = stack.login(None, "admin", "admin")

  /** Every (tenant, tenantDb, pool) a case opened and did not close. */
  private val opened = ListBuffer.empty[(String, String, String)]

  private def obj(fields: (String, Json)*): String = Json.obj(fields*).noSpaces
  private def s(v: String): Json                   = Json.fromString(v)
  private def ready(): Unit                        = assume(missing.isEmpty, missing.getOrElse(""))

  override def beforeAll(): Unit =
    if missing.isEmpty then
      stack = new AuthzStack(
        "iso",
        20944,
        31364,
        29564,
        20946,
        23360,
        23369,
        filesRoot = root,
        stackPrefixes = List(Prefix),
        defaultDataPath = Some(lakes.resolve("default").toString)
      )
      stack.boot()

  override def afterAll(): Unit =
    try
      if stack != null then
        opened.foreach { (t, td, pool) =>
          Try(
            stack.postStatus(
              "/api/pool/delete",
              obj("tenant" -> s(t), "tenantDb" -> s(td), "pool" -> s(pool), "force" -> Json.True)
            )
          )
        }
        stack.stop()
        roles.foreach(r => Try(TestPostgres.psql("postgres", s"""DROP ROLE IF EXISTS "$r"""")))
    finally
      Try {
        val tree = Files.walk(root)
        try tree.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
        finally tree.close()
      }

  // ---------- helpers ----------

  private def manifest(tenants: Json*): String =
    Json
      .obj(
        "apiVersion"   -> s("quack-on-demand/v1"),
        "kind"         -> s("ConfigManifest"),
        "exportedAt"   -> s("1970-01-01T00:00:00Z"),
        "exportedFrom" -> Json.obj("managerVersion" -> s("test"), "hostname" -> s("test")),
        "tenants"      -> Json.arr(tenants*)
      )
      .noSpaces

  /** A tenant through REST, as a superuser. */
  private def tenant(id: String): Unit =
    stack.post(
      "/api/tenant/create",
      obj("id" -> s(id), "displayName" -> s(id), "metastore" -> Json.obj())
    )

  /** A tenant whose name holds an underscore: only a manifest import still makes one. */
  private def importedTenant(id: String): Unit =
    stack.post(
      "/api/manifest/import",
      manifest(Json.obj("name" -> s(id), "displayName" -> s(id)))
    )

  /** A tenant admin holding every table and pool: their session. */
  private def adminOf(tenantId: String, user: String, password: String): String =
    stack.post(
      "/api/user/create",
      obj(
        "tenant"   -> s(tenantId),
        "username" -> s(user),
        "password" -> s(password),
        "kind"     -> s("admin"),
        "roles"    -> Json.arr(s("qod_all_tables")),
        "groups"   -> Json.arr(s("qod_all_pools"))
      )
    )
    stack.login(Some(tenantId), user, password)

  private def errorOf(body: String): String =
    io.circe.parser
      .parse(body)
      .toOption
      .flatMap(_.hcursor.get[String]("error").toOption)
      .getOrElse("")

  private def dataDir(name: String): String =
    Files.createDirectories(root.resolve(name)).toString + "/"

  /** A `database/create`: (status, composed name when it answered one, body). */
  private def createDb(
      tenantId: String,
      suffix: String,
      token: String,
      fields: (String, Json)*
  ): (Int, String, String) =
    val hasKind      = fields.exists(_._1 == "kind")
    val (code, body) = stack.postStatus(
      "/api/database/create",
      obj(
        (Seq("tenant" -> s(tenantId), "name" -> s(suffix)) ++
          (if hasKind then Nil else Seq("kind" -> s("ducklake"))) ++ fields)*
      ),
      token
    )
    val name =
      io.circe.parser
        .parse(body)
        .toOption
        .flatMap(_.hcursor.get[String]("name").toOption)
        .getOrElse("")
    if name.nonEmpty then stack.catalogDatabases += name
    (code, name, body)

  private def updateDb(
      tenantId: String,
      name: String,
      token: String,
      metastore: (String, String)*
  ): (Int, String) =
    stack.postStatus(
      "/api/database/update",
      obj(
        "tenant"    -> s(tenantId),
        "name"      -> s(name),
        "metastore" -> Json.obj(metastore.map((k, v) => k -> s(v))*)
      ),
      token
    )

  private def deleteDb(tenantId: String, name: String, token: String): (Int, String) =
    stack.postStatus(
      "/api/database/delete",
      obj("tenant" -> s(tenantId), "name" -> s(name)),
      token
    )

  private def openPool(tenantId: String, td: String, name: String, token: String): Unit =
    val (code, body) = stack.postStatus(
      "/api/pool/create",
      s"""{"tenant":"$tenantId","tenantDb":"$td","pool":"$name","size":1,
         |"roleDistribution":{"writeonly":0,"readonly":0,"dual":1}}""".stripMargin,
      token
    )
    withClue(s"pool/create of $name: ${body.take(300)}")(code shouldBe 200)
    opened += ((tenantId, td, name))

  private def closePool(tenantId: String, td: String, name: String): Unit =
    stack.post(
      "/api/pool/delete",
      obj("tenant" -> s(tenantId), "tenantDb" -> s(td), "pool" -> s(name), "force" -> Json.True)
    )
    opened -= ((tenantId, td, name))

  private def serving(tenantId: String, pool: String, user: String, password: String): Unit =
    stack.await(s"pool $pool serving", 120.seconds) {
      stack.flight("SELECT 1", user, password, tenantId, pool).toOption
    }

  private def dbExists(name: String): Boolean =
    Using.resource(
      DriverManager.getConnection(TestPostgres.adminUrl, TestPostgres.pgUser, TestPostgres.pgPass)
    ) { c =>
      val ps = c.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")
      ps.setString(1, name)
      val rs = ps.executeQuery()
      try rs.next()
      finally
        rs.close()
        ps.close()
    }

  private def createEmptyDb(name: String): Unit =
    stack.catalogDatabases += name
    TestPostgres.psql("postgres", s"""CREATE DATABASE "$name"""")

  /** A database on the default server no row knows, holding one marker row. */
  private def foreignDb(name: String): Unit =
    createEmptyDb(name)
    Using.resource(
      DriverManager
        .getConnection(TestPostgres.dbUrl(name), TestPostgres.pgUser, TestPostgres.pgPass)
    ) { c =>
      Using.resource(c.createStatement()) { st =>
        st.execute("CREATE TABLE marker (v TEXT)")
        st.execute("INSERT INTO marker VALUES ('kept')")
      }
    }

  private def markerKept(name: String): Boolean =
    Using.resource(
      DriverManager
        .getConnection(TestPostgres.dbUrl(name), TestPostgres.pgUser, TestPostgres.pgPass)
    ) { c =>
      Using.resource(c.createStatement()) { st =>
        val rs = st.executeQuery("SELECT count(*) FROM marker WHERE v = 'kept'")
        rs.next() && rs.getInt(1) == 1
      }
    }

  /** A DuckLake database of `tenantId` with `rows` rows in `main.fact`, serving. */
  private def owned(
      tenantId: String,
      suffix: String,
      token: String,
      pool: String,
      user: String,
      password: String,
      rows: Int = 5
  ): String =
    val (code, name, body) = createDb(tenantId, suffix, token)
    withClue(body.take(300))(code shouldBe 200)
    openPool(tenantId, name, pool, token)
    serving(tenantId, pool, user, password)
    stack
      .flight(
        s"CREATE TABLE $name.main.fact (id BIGINT, note VARCHAR)",
        user,
        password,
        tenantId,
        pool
      )
      .isRight shouldBe true
    stack
      .flight(
        s"INSERT INTO $name.main.fact SELECT i, 'owner' FROM range($rows) r(i)",
        user,
        password,
        tenantId,
        pool
      )
      .isRight shouldBe true
    name

  private lazy val hasPsql: Boolean =
    Try(
      scala.sys.process
        .Process(Seq("psql", "--version"))
        .!!(scala.sys.process.ProcessLogger(_ => ()))
    ).isSuccess

  private def closedPort(): Int =
    val socket = new ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()

  /** Long enough for anything a refused request started to reach the listener. */
  private def settle(): Unit = Thread.sleep(3000)

  // ---------- two tenants, one name ----------

  "A new tenant's name" should "hold letters and digits only, for a superuser too" in {
    ready()
    val (code, body) = stack.postStatus(
      "/api/tenant/create",
      obj("id" -> s(s"${Prefix}a0${run}_corp"), "displayName" -> s("x"))
    )
    code shouldBe 400
    errorOf(body) shouldBe "invalid_id"
    body should include("letters and digits only")
  }

  "A database name another tenant's database holds" should
    "be refused, with the owner's rows intact and readable" in {
      ready()
      val base   = s"${Prefix}a1$run"
      val holder = s"${base}_corp"
      val other  = base
      importedTenant(holder)
      tenant(other)
      val holderToken = adminOf(holder, "ha", HolderPw)
      val otherToken  = adminOf(other, "oa", OtherPw)
      val name        = owned(holder, "sales", holderToken, "hpool", "ha", HolderPw)
      name shouldBe s"${holder}_sales"

      // The same composed name places it in the same folder: refused by that, naming no one.
      val (same, _, sameBody) = createDb(other, "corp_sales", otherToken)
      withClue(sameBody.take(300))(same shouldBe 409)
      errorOf(sameBody) shouldBe "data_path_taken"
      sameBody should not include lakes.toString
      // A database of the holder's that a superuser placed elsewhere: refused by its name.
      val elsewhere               = dataDir(s"a1w_$run")
      val (placed, _, placedBody) = {
        val (c, b) = stack.postStatus(
          "/api/database/create",
          obj(
            "tenant"   -> s(holder),
            "name"     -> s("named"),
            "kind"     -> s("ducklake"),
            "dataPath" -> s(elsewhere)
          )
        )
        stack.catalogDatabases += s"${holder}_named"
        (c, "", b)
      }
      withClue(placedBody.take(300))(placed shouldBe 200)
      val (taken, _, takenBody) = createDb(other, "corp_named", otherToken)
      taken shouldBe 409
      errorOf(takenBody) shouldBe "database_name_taken"
      // Only the name the caller's own request composes: no one, and no path.
      io.circe.parser
        .parse(takenBody)
        .toOption
        .flatMap(_.hcursor.get[String]("message").toOption) shouldBe
        Some(s"database name '${holder}_named' is taken on this server: choose another name")
      // Naming that path changes nothing: that is a superuser's.
      val (wrong, _, wrongBody) =
        createDb(other, "corp_named", otherToken, "dataPath" -> s(elsewhere))
      wrong shouldBe 403
      errorOf(wrongBody) shouldBe "superuser_required"
      wrongBody should not include elsewhere.stripSuffix("/")

      // No row came of it, so no delete can reach the database either.
      deleteDb(other, name, otherToken)._1 shouldBe 404
      dbExists(name) shouldBe true
      stack.flight(
        s"SELECT count(*) FROM $name.main.fact",
        "ha",
        HolderPw,
        holder,
        "hpool"
      ) shouldBe
        Right(List("5"))
      stack
        .flight(s"INSERT INTO $name.main.fact VALUES (6, 'owner')", "ha", HolderPw, holder, "hpool")
        .isRight shouldBe true
      closePool(holder, name, "hpool")
    }

  it should "be refused the other way round too" in {
    ready()
    val base   = s"${Prefix}a2$run"
    val holder = base
    val other  = s"${base}_data"
    tenant(holder)
    importedTenant(other)
    val holderToken     = adminOf(holder, "hb", HolderPw)
    val otherToken      = adminOf(other, "ob", OtherPw)
    val name            = owned(holder, "data_x", holderToken, "hpool2", "hb", HolderPw)
    val (code, _, body) = createDb(other, "x", otherToken)
    code shouldBe 409
    errorOf(body) shouldBe "data_path_taken"
    deleteDb(other, name, otherToken)._1 shouldBe 404
    dbExists(name) shouldBe true
    stack.flight(s"SELECT count(*) FROM $name.main.fact", "hb", HolderPw, holder, "hpool2") shouldBe
      Right(List("5"))
    closePool(holder, name, "hpool2")
  }

  // ---------- QoD's login goes only to QoD's server ----------

  "A database on another server with no login of its own" should
    "be refused on create, with nothing reaching that server" in {
      ready()
      val listener = new PgListener(TestPostgres.pgPass)
      try
        val t = s"${Prefix}b1$run"
        tenant(t)
        val port  = listener.port.toString
        val cases = List(
          "host and port"     -> List("pgHost" -> "127.0.0.1", "pgPort" -> port),
          "port alone"        -> List("pgPort" -> port),
          "own user only"     -> List("pgHost" -> "127.0.0.1", "pgPort" -> port, "pgUser" -> "own"),
          "own password only" -> List(
            "pgHost"     -> "127.0.0.1",
            "pgPort"     -> port,
            "pgPassword" -> OwnSentinel
          )
        )
        cases.zipWithIndex.foreach { case ((what, keys), i) =>
          val (code, name, body) = createDb(
            t,
            s"own$i",
            su,
            "metastore" -> Json.obj(keys.map((k, v) => k -> s(v))*)
          )
          withClue(s"$what: ${body.take(300)}") {
            code shouldBe 400
            errorOf(body) shouldBe "own_login_required"
            body should include("pgUser")
            name shouldBe ""
          }
        }
        settle()
        listener.connections shouldBe 0
      finally listener.stop()
    }

  it should "be refused on update, whatever the row held" in {
    ready()
    val listener = new PgListener(TestPostgres.pgPass)
    try
      val t = s"${Prefix}b2$run"
      tenant(t)
      val (code, name, _) = createDb(t, "lake", su)
      code shouldBe 200
      val (up, body) =
        updateDb(t, name, su, "pgHost" -> "127.0.0.1", "pgPort" -> listener.port.toString)
      up shouldBe 400
      errorOf(body) shouldBe "own_login_required"
      settle()
      listener.connections shouldBe 0
    finally listener.stop()
  }

  "A password stored for one server" should "never follow the row to another" in {
    ready()
    val first  = new PgListener(TestPostgres.pgPass, OwnSentinel)
    val second = new PgListener(TestPostgres.pgPass, OwnSentinel2)
    try
      val t = s"${Prefix}b3$run"
      tenant(t)
      // The control: its own user and password go to its own server, and the default login does not.
      val (code, name, created) = createDb(
        t,
        "byo",
        su,
        "metastore" -> Json.obj(
          "pgHost"     -> s("127.0.0.1"),
          "pgPort"     -> s(first.port.toString),
          "pgUser"     -> s(OwnUser),
          "pgPassword" -> s(OwnSentinel)
        )
      )
      withClue(created.take(300))(code shouldBe 200)
      first.await(1, 30.seconds)
      first.seen.exists(c => c.user == OwnUser && c.matchesOwn) shouldBe true
      first.seen.exists(_.matchesDefault) shouldBe false

      // Moved with the password left out: refused, nothing arrives there.
      val (moved, movedBody) = updateDb(
        t,
        name,
        su,
        "pgHost" -> "127.0.0.1",
        "pgPort" -> second.port.toString,
        "pgUser" -> OwnUser
      )
      moved shouldBe 400
      errorOf(movedBody) shouldBe "own_login_required"
      // Back to QoD's own server with the password left out: the same.
      val (home, homeBody) = updateDb(t, name, su, "schemaName" -> "main")
      home shouldBe 400
      errorOf(homeBody) shouldBe "own_login_required"
      settle()
      second.connections shouldBe 0

      // Sent again, it is the new server's: a node takes it there, and nothing else arrives.
      val (again, againBody) = updateDb(
        t,
        name,
        su,
        "pgHost"     -> "127.0.0.1",
        "pgPort"     -> second.port.toString,
        "pgUser"     -> OwnUser,
        "pgPassword" -> OwnSentinel2
      )
      withClue(againBody.take(300))(again shouldBe 200)
      openPool(t, name, "b3p", su)
      second.await(1, 60.seconds)
      second.seen.exists(c => c.user == OwnUser && c.matchesOwn) shouldBe true
      second.seen.exists(_.matchesDefault) shouldBe false
      closePool(t, name, "b3p")
    finally
      first.stop()
      second.stop()
  }

  "A user name that reads as more connection settings" should "be refused, reaching nothing" in {
    ready()
    val listener = new PgListener(TestPostgres.pgPass)
    try
      val t = s"${Prefix}b4$run"
      tenant(t)
      val user               = s"${TestPostgres.pgUser} host=127.0.0.1 port=${listener.port}"
      val (code, name, body) = createDb(
        t,
        "lake",
        su,
        "metastore" -> Json.obj("pgUser" -> s(user))
      )
      // What such a row would do, had it been stored.
      if code == 200 then
        openPool(t, name, "b4p", su)
        listener.await(1, 60.seconds)
        closePool(t, name, "b4p")
      listener.seen.exists(_.matchesDefault) shouldBe false
      code shouldBe 400
      errorOf(body) shouldBe "invalid_contract"
      listener.connections shouldBe 0
      body should include("pgUser")
    finally listener.stop()
  }

  "A row a manifest wrote with another server and no login" should
    "take no login of QoD's to that server" in {
      ready()
      val listener = new PgListener(TestPostgres.pgPass)
      try
        val t  = s"${Prefix}b5${run}_old"
        val td = s"${t}_lake"
        stack.post(
          "/api/manifest/import",
          manifest(
            Json.obj(
              "name"        -> s(t),
              "displayName" -> s(t),
              "tenantDbs"   -> Json.arr(
                Json.obj(
                  "name"      -> s(td),
                  "kind"      -> s("ducklake"),
                  "dataPath"  -> s(dataDir(s"b5_$run")),
                  "metastore" -> Json.obj(
                    "pgHost" -> s("127.0.0.1"),
                    "pgPort" -> s(listener.port.toString)
                  )
                )
              ),
              "pools" -> Json.arr(
                Json.obj(
                  "name"             -> s("b5p"),
                  "tenantDb"         -> s(td),
                  "roleDistribution" -> Json
                    .obj(
                      "writeonly" -> Json.fromInt(0),
                      "readonly"  -> Json.fromInt(0),
                      "dual"      -> Json.fromInt(1)
                    )
                )
              )
            )
          )
        )
        opened += ((t, td, "b5p"))
        // Its node tries, which is what makes this case worth anything, and fails to connect.
        listener.await(1, 60.seconds)
        listener.connections should be > 0
        listener.seen.exists(_.matchesDefault) shouldBe false
        // The catalog's own database only: no admin connection to create one there.
        listener.seen.map(_.database).toSet shouldBe Set(td)
        closePool(t, td, "b5p")
        dbExists(td) shouldBe false
      finally listener.stop()
    }

  "GET database/metastore-defaults" should "show a superuser no password, and anyone else nothing" in {
    ready()
    val (code, body) = stack.getStatus("/api/database/metastore-defaults", su)
    code shouldBe 200
    body.toLowerCase(java.util.Locale.ROOT).contains("password") shouldBe false
    body.contains(TestPostgres.pgPass) shouldBe false
    body should include("pgHost")

    val t = s"${Prefix}b6$run"
    tenant(t)
    val (refused, refusal) =
      stack.getStatus("/api/database/metastore-defaults", adminOf(t, "be", OtherPw))
    refused shouldBe 403
    errorOf(refusal) shouldBe "superuser_required"
    refusal should not include "pgHost"
  }

  // ---------- a catalog name is fixed ----------

  "database/update" should "refuse a tenant admin's change of dbName, the owner keeping its rows" in {
    ready()
    val holder = s"${Prefix}c1$run"
    val other  = s"${Prefix}c2$run"
    tenant(holder)
    tenant(other)
    val holderToken = adminOf(holder, "ca", HolderPw)
    val otherToken  = adminOf(other, "cb", OtherPw)
    val target      = owned(holder, "secret", holderToken, "cpool", "ca", HolderPw)

    val (code, mine, _) = createDb(other, "mine", otherToken)
    code shouldBe 200
    openPool(other, mine, "cpool2", otherToken)
    serving(other, "cpool2", "cb", OtherPw)

    val (up, body) = updateDb(other, mine, otherToken, "dbName" -> target)
    withClue(body.take(300))(up shouldBe 403)
    errorOf(body) shouldBe "superuser_required"
    // A throwaway database of the shape of a control plane: the same answer.
    val plain = s"${Prefix}c3$run"
    createEmptyDb(plain)
    val (up2, body2) = updateDb(other, mine, otherToken, "dbName" -> plain)
    up2 shouldBe 403
    errorOf(body2) shouldBe "superuser_required"

    // Its node stays on its own catalog, and the owner's rows are the owner's.
    settle()
    stack.flight("SELECT 1", "cb", OtherPw, other, "cpool2").isRight shouldBe true
    stack
      .flight(s"SELECT count(*) FROM $target.main.fact", "cb", OtherPw, other, "cpool2")
      .isLeft shouldBe
      true
    stack.flight(
      s"SELECT count(*) FROM $target.main.fact",
      "ca",
      HolderPw,
      holder,
      "cpool"
    ) shouldBe
      Right(List("5"))
    closePool(holder, target, "cpool")
    closePool(other, mine, "cpool2")

    // Sending the name it holds, or leaving it out, is no change.
    updateDb(other, mine, otherToken, "dbName" -> mine, "schemaName" -> "main")._1 shouldBe 200
    updateDb(other, mine, otherToken, "schemaName" -> "main")._1 shouldBe 200
    // A superuser may.
    updateDb(other, mine, su, "dbName" -> plain)._1 shouldBe 200
    updateDb(other, mine, su, "dbName" -> mine)._1 shouldBe 200
  }

  "A column policy naming another tenant's catalog" should "read nothing of that catalog" in {
    ready()
    val holder = s"${Prefix}c4$run"
    val other  = s"${Prefix}c5$run"
    tenant(holder)
    tenant(other)
    val holderToken = adminOf(holder, "cc", HolderPw)
    val otherToken  = adminOf(other, "cd", OtherPw)
    val target      = createDb(holder, "named", holderToken)._2
    openPool(holder, target, "cpool3", holderToken)
    serving(holder, "cpool3", "cc", HolderPw)
    stack
      .flight(
        s"CREATE TABLE $target.main.fact (id BIGINT, colofholder VARCHAR)",
        "cc",
        HolderPw,
        holder,
        "cpool3"
      )
      .isRight shouldBe true
    closePool(holder, target, "cpool3")

    // A database of its own, attached under the other's catalog name, the same table, a policy on it.
    val (code, mine, body) = createDb(
      other,
      "mine",
      otherToken,
      "metastore" -> Json.obj("catalogAlias" -> s(target))
    )
    withClue(body.take(300))(code shouldBe 200)
    openPool(other, mine, "cpool4", otherToken)
    serving(other, "cpool4", "cd", OtherPw)
    stack
      .flight(s"CREATE TABLE $target.main.fact (id BIGINT)", "cd", OtherPw, other, "cpool4")
      .isRight shouldBe true
    val role = stack
      .post("/api/role/create", obj("tenant" -> s(other), "name" -> s("masked")), otherToken)
      .hcursor
      .get[String]("id")
      .fold(e => throw e, identity)
    stack.post(
      "/api/role/permission/grant",
      obj(
        "roleId"  -> s(role),
        "catalog" -> s(target),
        "schema"  -> s("main"),
        "table"   -> s("fact"),
        "verb"    -> s("RO")
      ),
      otherToken
    )
    stack.post(
      "/api/role/column-policy/create",
      obj(
        "roleId"       -> s(role),
        "catalogName"  -> s(target),
        "schemaName"   -> s("main"),
        "tableName"    -> s("fact"),
        "columnName"   -> s("id"),
        "action"       -> s("mask"),
        "transformSql" -> s("0")
      ),
      otherToken
    )
    stack.post(
      "/api/user/create",
      obj(
        "tenant"   -> s(other),
        "username" -> s("ce"),
        "password" -> s(OtherPw),
        "kind"     -> s("user"),
        "roles"    -> Json.arr(s("masked")),
        "groups"   -> Json.arr(s("qod_all_pools"))
      ),
      otherToken
    )
    val out = stack.flight(s"SELECT * FROM $target.main.fact", "ce", OtherPw, other, "cpool4")
    withClue(s"the answer: $out") {
      out.left.getOrElse("").contains("colofholder") shouldBe false
      out.toOption.toList.flatten.mkString.contains("colofholder") shouldBe false
    }
    closePool(other, mine, "cpool4")
  }

  // ---------- create and drop act on what the row owns ----------

  "A create on the default server" should "refuse a database with anything in it, untouched" in {
    ready()
    val t = s"${Prefix}d1$run"
    tenant(t)
    val token  = adminOf(t, "da", OtherPw)
    val target = s"${t}_target"
    foreignDb(target)
    val (code, _, body) = createDb(t, "target", token)
    withClue(body.take(300))(code shouldBe 409)
    errorOf(body) shouldBe "database_name_taken"
    deleteDb(t, target, token)._1 shouldBe 404
    markerKept(target) shouldBe true
    Using.resource(
      DriverManager
        .getConnection(TestPostgres.dbUrl(target), TestPostgres.pgUser, TestPostgres.pgPass)
    ) { c =>
      Using.resource(c.createStatement()) { st =>
        val rs = st.executeQuery(
          "SELECT count(*) FROM information_schema.tables WHERE table_name LIKE 'ducklake%'"
        )
        rs.next() shouldBe true
        rs.getInt(1) shouldBe 0
      }
    }
  }

  it should "reuse an empty one: a crash between the create and the row write" in {
    ready()
    val t = s"${Prefix}d2$run"
    tenant(t)
    val token = adminOf(t, "db", OtherPw)
    createEmptyDb(s"${t}_retry")
    val (code, name, body) = createDb(t, "retry", token)
    withClue(body.take(300))(code shouldBe 200)
    name shouldBe s"${t}_retry"
  }

  it should "still adopt one for a superuser, who states it by being one" in {
    ready()
    val t = s"${Prefix}d3$run"
    tenant(t)
    val target = s"${t}_adopted"
    foreignDb(target)
    val (code, body) = stack.postStatus(
      "/api/database/create",
      obj(
        "tenant"   -> s(t),
        "name"     -> s("adopted"),
        "kind"     -> s("ducklake"),
        "dataPath" -> s(dataDir(s"d3_$run"))
      )
    )
    withClue(body.take(300))(code shouldBe 200)
    markerKept(target) shouldBe true
  }

  "A row on another server" should "neither create nor drop anything on the default one" in {
    ready()
    val t = s"${Prefix}d4$run"
    tenant(t)
    val token = adminOf(t, "dc", OtherPw)
    val decoy = closedPort().toString
    val login = Json.obj(
      "pgHost"     -> s("127.0.0.1"),
      "pgPort"     -> s(decoy),
      "pgUser"     -> s(OwnUser),
      "pgPassword" -> s(OwnSentinel)
    )
    // A name nothing holds stays free on the default server.
    val (fresh, freshName, freshBody) =
      createDb(t, "fresh", su, "metastore" -> login)
    withClue(freshBody.take(300))(fresh shouldBe 200)
    dbExists(freshName) shouldBe false
    deleteDb(t, freshName, token)._1 shouldBe 200

    // A same-named database on the default server outlives the row's delete.
    val target = s"${t}_target"
    foreignDb(target)
    val (code, name, body) =
      createDb(t, "target", su, "metastore" -> login)
    withClue(body.take(300))(code shouldBe 200)
    name shouldBe target
    deleteDb(t, target, token)._1 shouldBe 200
    markerKept(target) shouldBe true
  }

  it should "not come to the default server's database of its name by an update" in {
    ready()
    val t = s"${Prefix}d7$run"
    tenant(t)
    val token = adminOf(t, "df", OtherPw)
    val login = Json.obj(
      "pgHost"     -> s("127.0.0.1"),
      "pgPort"     -> s(closedPort().toString),
      "pgUser"     -> s(OwnUser),
      "pgPassword" -> s(OwnSentinel)
    )
    val target = s"${t}_target"
    foreignDb(target)
    createDb(t, "target", su, "metastore" -> login)._1 shouldBe 200
    // Leaving the host out and removing the password would make it a row of QoD's own server.
    val (code, body) = updateDb(t, target, token, "pgPassword" -> "")
    withClue(body.take(300))(code shouldBe 403)
    errorOf(body) shouldBe "superuser_required"
    markerKept(target) shouldBe true
  }

  it should "leave the default server's database alone when its tenant is deleted" in {
    ready()
    val t = s"${Prefix}d5$run"
    tenant(t)
    val login = Json.obj(
      "pgHost"     -> s("127.0.0.1"),
      "pgPort"     -> s(closedPort().toString),
      "pgUser"     -> s(OwnUser),
      "pgPassword" -> s(OwnSentinel)
    )
    val target = s"${t}_target"
    foreignDb(target)
    createDb(t, "target", su, "metastore" -> login)._1 shouldBe 200
    stack.postStatus("/api/tenant/delete", obj("name" -> s(t)))._1 shouldBe 200
    markerKept(target) shouldBe true
  }

  "A row with no catalog database" should "drop nothing on the default server" in {
    ready()
    val t = s"${Prefix}d6$run"
    tenant(t)
    val token = adminOf(t, "de", OtherPw)
    val mem   = s"${t}_mem"
    val file  = s"${t}_file"
    foreignDb(mem)
    foreignDb(file)
    createDb(t, "mem", token, "kind" -> s("memory"))._1 shouldBe 200
    deleteDb(t, mem, token)._1 shouldBe 200
    markerKept(mem) shouldBe true
    // A local file is a superuser's to name.
    createDb(
      t,
      "file",
      su,
      "kind"      -> s("duckdb-file"),
      "dataPath"  -> s(root.resolve(s"d6_$run.duckdb").toString),
      "metastore" -> Json.obj("dbName" -> s(file), "schemaName" -> s("main"))
    )._1 shouldBe 200
    stack.postStatus("/api/tenant/delete", obj("name" -> s(t)))._1 shouldBe 200
    markerKept(file) shouldBe true
  }

  // ---------- where a database's data lies ----------

  private def rowOf(tenantId: String, name: String, token: String): Option[Json] =
    val (_, body) = stack.getStatus(s"/api/database/list?tenant=$tenantId", token)
    io.circe.parser
      .parse(body)
      .toOption
      .flatMap(_.hcursor.downField("tenantDbs").values)
      .toList
      .flatten
      .find(_.hcursor.get[String]("name").contains(name))

  private def filesUnder(p: Path): List[Path] =
    if !Files.exists(p) then Nil
    else Using.resource(Files.walk(p))(_.iterator().asScala.filter(Files.isRegularFile(_)).toList)

  private val LongAgo = FileTime.from(Instant.now().minus(60, ChronoUnit.DAYS))

  private def oldFile(p: Path): Path =
    Files.createDirectories(p.getParent)
    Files.writeString(p, "not really a data file")
    Files.setLastModifiedTime(p, LongAgo)

  /** A maintenance run of orphan cleanup, as `token`, to its end. */
  private def sweep(tenantId: String, name: String, token: String): Unit =
    val (code, body) = stack.postStatus(
      "/api/maintenance/run",
      obj("tenant" -> s(tenantId), "tenantDb" -> s(name), "operations" -> s("orphans")),
      token
    )
    withClue(body.take(200))(code shouldBe 200)
    val id = io.circe.parser.parse(body).toOption.get.hcursor.get[Long]("id").toOption.get
    stack.await(s"maintenance run $id to end", 180.seconds) {
      stack
        .get(s"/api/maintenance/runs?tenant=$tenantId&tenantDb=$name")
        .asArray
        .toList
        .flatten
        .find(_.hcursor.get[Long]("id").contains(id))
        .filter(_.hcursor.get[String]("finishedAt").isRight)
    }
    ()

  "A tenant admin" should "name no data path of their own, with no row written" in {
    ready()
    val t = s"${Prefix}f1$run"
    tenant(t)
    val token = adminOf(t, "fa", OtherPw)
    val keys  = Json.obj("s3_access_key_id" -> s("AK"), "s3_secret_access_key" -> s("SK"))
    List(
      "a local path"                     -> List("dataPath" -> s(dataDir(s"f1_$run"))),
      "a remote path with no keys"       -> List("dataPath" -> s("s3://qod-iso-no-bucket/x/")),
      "a remote path with half the keys" -> List(
        "dataPath"    -> s("s3://qod-iso-no-bucket/x/"),
        "objectStore" -> Json.obj("s3_access_key_id" -> s("AK"))
      ),
      "a local path with keys" -> List("dataPath" -> s(dataDir(s"f2_$run")), "objectStore" -> keys),
      "the metastore's" -> List("metastore" -> Json.obj("dataPath" -> s(dataDir(s"f3_$run")))),
      "a local file"    -> List(
        "kind"      -> s("duckdb-file"),
        "dataPath"  -> s(root.resolve(s"f4_$run.duckdb").toString),
        "metastore" -> Json.obj("dbName" -> s(s"${t}_f"), "schemaName" -> s("main"))
      ),
      "a memory database's" -> List("kind" -> s("memory"), "dataPath" -> s(dataDir(s"f5_$run")))
    ).zipWithIndex.foreach { case ((what, fields), i) =>
      val (code, name, body) = createDb(t, s"named$i", token, fields*)
      withClue(s"$what: ${body.take(300)}") {
        code shouldBe 403
        errorOf(body) shouldBe "superuser_required"
        name shouldBe ""
        body should include("leave dataPath out")
      }
      dbExists(s"${t}_named$i") shouldBe false
    }
    rowOf(t, s"${t}_named0", token) shouldBe None
    stack.getStatus(s"/api/database/list?tenant=$t", token)._2 should not include "named"
  }

  it should "get a place of QoD's when naming none, which a sweep of that row stays inside" in {
    ready()
    val t = s"${Prefix}f2$run"
    tenant(t)
    val token  = adminOf(t, "fb", OtherPw)
    val name   = owned(t, "placed", token, "fpool", "fb", OtherPw)
    val folder = lakes.resolve(name)
    val row    = rowOf(t, name, token).get
    row.hcursor.get[String]("dataPath") shouldBe Right(folder.toString)
    row.hcursor.get[String]("effectiveDataPath") shouldBe Right(folder.toString)
    // The table's own files land under it, enough rows that none stays in the catalog.
    stack
      .flight(
        s"INSERT INTO $name.main.fact SELECT i, 'more' FROM range(100000) r(i)",
        "fb",
        OtherPw,
        t,
        "fpool"
      )
      .isRight shouldBe true
    val own = filesUnder(folder).filter(_.toString.endsWith(".parquet"))
    own should not be empty

    // Everything old: its own files stay (its catalog holds them), a stray of its folder goes, and
    // the folder beside it, whose files its catalog does not hold either, is not its to sweep.
    own.foreach(Files.setLastModifiedTime(_, LongAgo))
    val stray  = oldFile(folder.resolve("stray.parquet"))
    val beside = oldFile(lakes.resolve(s"${name}_beside").resolve("other.parquet"))
    val above  = oldFile(lakes.resolve("above.parquet"))
    sweep(t, name, token)
    Files.exists(stray) shouldBe false
    Files.exists(beside) shouldBe true
    Files.exists(above) shouldBe true
    own.forall(Files.exists(_)) shouldBe true
    stack.flight(
      s"SELECT count(*), sum(id) FROM $name.main.fact",
      "fb",
      OtherPw,
      t,
      "fpool"
    ) shouldBe Right(List("100005,4999950010"))
    closePool(t, name, "fpool")
  }

  it should "move no data path by an update, and keep every other update" in {
    ready()
    val t = s"${Prefix}f3$run"
    tenant(t)
    val token           = adminOf(t, "fc", OtherPw)
    val (code, name, _) = createDb(t, "lake", token)
    code shouldBe 200
    val (up, body) = updateDb(t, name, token, "dataPath" -> dataDir(s"f3_$run"))
    up shouldBe 403
    errorOf(body) shouldBe "superuser_required"
    rowOf(t, name, token).get.hcursor.downField("metastore").get[String]("dataPath").isLeft shouldBe
      true
    updateDb(t, name, token, "schemaName" -> "main")._1 shouldBe 200
    stack
      .postStatus(
        "/api/database/update",
        obj("tenant" -> s(t), "name" -> s(name), "initSql" -> s("SET threads = 2;")),
        token
      )
      ._1 shouldBe 200
  }

  it should "keep a remote path's own keys complete: the node would sign it with QoD's otherwise" in {
    ready()
    val t = s"${Prefix}f4$run"
    tenant(t)
    val token = adminOf(t, "fd", OtherPw)
    val keys  = Json.obj("s3_access_key_id" -> s("AK"), "s3_secret_access_key" -> s("SK"))
    // Only the row: this stack has no object store for a node to reach.
    val (code, name, body) =
      createDb(
        t,
        "remote",
        token,
        "dataPath"    -> s("s3://qod-iso-no-bucket/f4/"),
        "objectStore" -> keys
      )
    withClue(body.take(300))(code shouldBe 200)
    def updateKeys(fields: (String, String)*) = stack.postStatus(
      "/api/database/update",
      obj(
        "tenant"      -> s(t),
        "name"        -> s(name),
        "objectStore" -> Json.obj(fields.map((k, v) => k -> s(v))*)
      ),
      token
    )
    List(
      "cleared"        -> List.empty[(String, String)],
      "secret emptied" -> List("s3_access_key_id" -> "AK", "s3_secret_access_key" -> ""),
      "id blank"       -> List("s3_access_key_id" -> " ")
    ).foreach { (what, fields) =>
      val (c, b) = updateKeys(fields*)
      withClue(what) {
        c shouldBe 403
        errorOf(b) shouldBe "superuser_required"
      }
    }
    // Changed and complete, it stays: the secret left out is the stored one.
    updateKeys("s3_access_key_id" -> "AK2", "s3_region" -> "eu-west-1")._1 shouldBe 200
    updateKeys("s3_access_key_id" -> "AK3", "s3_secret_access_key" -> "SK3")._1 shouldBe 200
    // A superuser may clear them.
    stack
      .postStatus(
        "/api/database/update",
        obj(
          "tenant"      -> s(t),
          "name"        -> s(name),
          "objectStore" -> Json.obj("s3_secret_access_key" -> s(""))
        )
      )
      ._1 shouldBe 200
  }

  it should "reach no other tenant's database file, nor make a file at a path of their choosing" in {
    ready()
    val victim   = s"${Prefix}f7$run"
    val attacker = s"${Prefix}f8$run"
    tenant(victim)
    tenant(attacker)
    val token = adminOf(attacker, "ff", OtherPw)
    // The other tenant's file, its row a superuser's to write.
    val file = root.resolve(s"f7_$run.duckdb")
    Using.resource(DriverManager.getConnection(s"jdbc:duckdb:$file")) { c =>
      Using.resource(c.createStatement()) { st =>
        st.execute("CREATE TABLE vrows (id BIGINT)")
        st.execute("INSERT INTO vrows VALUES (1), (2)")
      }
    }
    val before = (Files.readAllBytes(file).toList, Files.getLastModifiedTime(file))
    createDb(
      victim,
      "vfile",
      su,
      "kind"      -> s("duckdb-file"),
      "dataPath"  -> s(file.toString),
      "metastore" -> Json.obj("dbName" -> s("viccat"), "schemaName" -> s("main"))
    )._1 shouldBe 200

    val absent = root.resolve(s"f8_$run.duckdb")
    List("steal" -> file, "fresh" -> absent).foreach { (suffix, path) =>
      val (code, name, body) = createDb(
        attacker,
        suffix,
        token,
        "kind"      -> s("duckdb-file"),
        "dataPath"  -> s(path.toString),
        "metastore" -> Json.obj("dbName" -> s(s"cat_$suffix"), "schemaName" -> s("main"))
      )
      withClue(s"$suffix: ${body.take(300)}") {
        code shouldBe 403
        errorOf(body) shouldBe "superuser_required"
        name shouldBe ""
      }
    }
    settle()
    stack.getStatus(s"/api/database/list?tenant=$attacker", token)._2 should not include "steal"
    stack.getStatus(s"/api/database/list?tenant=$attacker", token)._2 should not include "fresh"
    Files.exists(absent) shouldBe false
    (Files.readAllBytes(file).toList, Files.getLastModifiedTime(file)) shouldBe before
  }

  "A tenant admin's named remote path" should "take no folder another database holds" in {
    ready()
    val holder = s"${Prefix}e5$run"
    val t      = s"${Prefix}e6$run"
    tenant(holder)
    tenant(t)
    val holderToken = adminOf(holder, "ga", OtherPw)
    val token       = adminOf(t, "gb", OtherPw)
    val keys        = Json.obj("s3_access_key_id" -> s("AK"), "s3_secret_access_key" -> s("SK"))
    def create(owner: String, suffix: String, session: String, path: String) =
      createDb(owner, suffix, session, "dataPath" -> s(path), "objectStore" -> keys)
    val shared                     = s"s3://qod-iso-no-bucket/e5$run"
    val (held, heldName, heldBody) = create(holder, "held", holderToken, s"$shared/held/")
    withClue(heldBody.take(300))(held shouldBe 200)

    List(s"$shared/held/", s"$shared/held", s"$shared/held/below/", s"$shared/").foreach { path =>
      val (code, name, body) = create(t, "mine", token, path)
      withClue(s"$path: ${body.take(300)}") {
        code shouldBe 409
        errorOf(body) shouldBe "data_path_taken"
        name shouldBe ""
        body should not include "e5"
        body should not include heldName
      }
    }
    // A bucket's root is no one's to name but a superuser's.
    List("s3://qod-iso-no-bucket", "s3://qod-iso-no-bucket/").foreach { path =>
      val (code, _, body) = create(t, "mine", token, path)
      code shouldBe 403
      errorOf(body) shouldBe "superuser_required"
    }
    rowOf(t, s"${t}_mine", token) shouldBe None

    // Beside it, free. A superuser is not judged, and a folder the location rules still remember
    // stays taken a while after its database is gone.
    val (beside, mine, besideBody) = create(t, "mine", token, s"$shared/mine/")
    withClue(besideBody.take(300))(beside shouldBe 200)
    createDb(t, "su", su, "dataPath" -> s(s"$shared/held/su/"))._1 shouldBe 200
    // The next create reads the set, which then holds this folder.
    create(t, "next", token, s"$shared/next/")._1 shouldBe 200
    deleteDb(t, mine, token)._1 shouldBe 200
    val (again, _, againBody) = create(holder, "other", holderToken, s"$shared/mine/")
    withClue(againBody.take(300))(again shouldBe 409)
    errorOf(againBody) shouldBe "data_path_taken"
  }

  "A superuser" should "still name a data path" in {
    ready()
    val t = s"${Prefix}f5$run"
    tenant(t)
    val path         = dataDir(s"f5_$run")
    val (code, body) = stack.postStatus(
      "/api/database/create",
      obj("tenant" -> s(t), "name" -> s("named"), "kind" -> s("ducklake"), "dataPath" -> s(path))
    )
    withClue(body.take(300))(code shouldBe 200)
    stack.catalogDatabases += s"${t}_named"
    rowOf(t, s"${t}_named", su).get.hcursor
      .get[String]("dataPath") shouldBe Right(path)
  }

  "A row a manifest made" should "stay as it is, and take a tenant admin's other updates" in {
    ready()
    val t    = s"${Prefix}f6${run}_old"
    val td   = s"${t}_lake"
    val bare = s"${t}_bare"
    val path = dataDir(s"f6_$run")
    stack.catalogDatabases += td
    stack.catalogDatabases += bare
    stack.post(
      "/api/manifest/import",
      manifest(
        Json.obj(
          "name"        -> s(t),
          "displayName" -> s(t),
          "tenantDbs"   -> Json.arr(
            Json.obj("name" -> s(td), "kind"   -> s("ducklake"), "dataPath" -> s(path)),
            Json.obj("name" -> s(bare), "kind" -> s("ducklake"))
          )
        )
      )
    )
    val token = adminOf(t, "fe", OtherPw)
    rowOf(t, td, token).get.hcursor.get[String]("dataPath") shouldBe Right(path)
    stack
      .postStatus(
        "/api/database/update",
        obj("tenant" -> s(t), "name" -> s(td), "initSql" -> s("SET threads = 2;")),
        token
      )
      ._1 shouldBe 200
    rowOf(t, td, token).get.hcursor.get[String]("dataPath") shouldBe Right(path)
    // One with no path of its own keeps deriving it, and no tenant admin gives it one.
    val (up, body) = updateDb(t, bare, token, "dataPath" -> dataDir(s"f6b_$run"))
    up shouldBe 403
    errorOf(body) shouldBe "superuser_required"
  }

  // ---------- only a superuser names a Postgres server ----------

  "A tenant admin's create" should "name no part of a Postgres server, reaching nothing" in {
    ready()
    val listener = new PgListener(TestPostgres.pgPass)
    try
      val t = s"${Prefix}f9$run"
      tenant(t)
      val token = adminOf(t, "ga", OtherPw)
      val login = List(
        "pgHost"     -> "127.0.0.1",
        "pgPort"     -> listener.port.toString,
        "pgUser"     -> OwnUser,
        "pgPassword" -> OwnSentinel
      )
      val cases =
        login
          .map(List(_)) ++ List(List("dbName" -> s"${t}_other"), login, login :+ ("dbName" -> "x"))
      cases.zipWithIndex.foreach { case (keys, i) =>
        val (code, name, body) = createDb(
          t,
          s"own$i",
          token,
          "metastore" -> Json.obj(keys.map((k, v) => k -> s(v))*)
        )
        withClue(s"${keys.map(_._1)}: ${body.take(300)}") {
          code shouldBe 403
          errorOf(body) shouldBe "superuser_required"
          body should include("a superuser sets")
          name shouldBe ""
        }
        dbExists(s"${t}_own$i") shouldBe false
      }
      // No row, so nothing a pool could attach and no catalog of the tenant's making to read from.
      stack.getStatus(s"/api/database/list?tenant=$t", token)._2 should not include "own"
      val (pool, _) = stack.postStatus(
        "/api/pool/create",
        s"""{"tenant":"$t","tenantDb":"${t}_own5","pool":"g1p","size":1,
           |"roleDistribution":{"writeonly":0,"readonly":0,"dual":1}}""".stripMargin,
        token
      )
      pool should not be 200
      settle()
      listener.connections shouldBe 0
    finally listener.stop()
  }

  "A tenant admin's update" should "add no part of a Postgres server to a row, reaching nothing" in {
    ready()
    val listener = new PgListener(TestPostgres.pgPass)
    try
      val t = s"${Prefix}fa$run"
      tenant(t)
      val token           = adminOf(t, "gb", OtherPw)
      val (code, name, _) = createDb(t, "lake", token)
      code shouldBe 200
      openPool(t, name, "g2p", token)
      serving(t, "g2p", "gb", OtherPw)
      List(
        "pgHost"     -> "127.0.0.1",
        "pgPort"     -> listener.port.toString,
        "pgUser"     -> OwnUser,
        "pgPassword" -> OwnSentinel,
        "dbName"     -> s"${t}_other"
      ).foreach { (k, v) =>
        val (up, body) = updateDb(t, name, token, k -> v)
        withClue(k) {
          up shouldBe 403
          errorOf(body) shouldBe "superuser_required"
          body should include(k)
        }
      }
      settle()
      listener.connections shouldBe 0
      // Nothing moved, and its node stayed up.
      stack.flight("SELECT 1", "gb", OtherPw, t, "g2p").isRight shouldBe true
      closePool(t, name, "g2p")
    finally listener.stop()
  }

  it should "change or remove no part of a superuser's row on another server, its other fields free" in {
    ready()
    val server = new PgListener(TestPostgres.pgPass)
    val other  = new PgListener(TestPostgres.pgPass)
    try
      val t = s"${Prefix}fb$run"
      tenant(t)
      val token              = adminOf(t, "gc", OtherPw)
      val (c, name, created) = createDb(
        t,
        "byo",
        su,
        "metastore" -> Json.obj(
          "pgHost"     -> s("127.0.0.1"),
          "pgPort"     -> s(server.port.toString),
          "pgUser"     -> s(OwnUser),
          "pgPassword" -> s(OwnSentinel)
        )
      )
      withClue(created.take(300))(c shouldBe 200)
      // What the row shows anyone: everything but its password.
      val shown = Map(
        "pgHost" -> "127.0.0.1",
        "pgPort" -> server.port.toString,
        "pgUser" -> OwnUser,
        "dbName" -> name
      )
      def refused(what: String, map: Map[String, String]): Unit =
        val (up, body) = updateDb(t, name, token, map.toSeq*)
        withClue(what) {
          up shouldBe 403
          errorOf(body) shouldBe "superuser_required"
        }
      // Changed.
      refused("pgHost", shown + ("pgHost"             -> "127.0.0.2"))
      refused("pgPort", shown + ("pgPort"             -> other.port.toString))
      refused("pgUser", shown + ("pgUser"             -> "someone"))
      refused("dbName", shown + ("dbName"             -> s"${t}_elsewhere"))
      refused("a new password", shown + ("pgPassword" -> OwnSentinel2))
      // The stored password itself: what a right guess would be told, were it compared.
      refused("the same password", shown + ("pgPassword" -> OwnSentinel))
      // Removed.
      refused("no pgHost", shown - "pgHost")
      refused("no pgPort", shown - "pgPort")
      refused("no pgUser", shown - "pgUser")
      refused("no password", shown + ("pgPassword" -> ""))
      // All of them: the row would come to QoD's server under QoD's login.
      refused("none of them", Map("schemaName" -> "main"))
      settle()
      other.connections shouldBe 0

      // As stored, with the password left out: allowed, and the row stays where it was.
      updateDb(t, name, token, (shown + ("schemaName" -> "main")).toSeq*)._1 shouldBe 200
      stack
        .postStatus(
          "/api/database/update",
          obj("tenant" -> s(t), "name" -> s(name), "initSql" -> s("SET threads = 2;")),
          token
        )
        ._1 shouldBe 200
      val seen = server.seen.size
      openPool(t, name, "g3p", token)
      server.await(seen + 1, 60.seconds)
      val later = server.seen.drop(seen)
      later should not be empty
      later.forall(a => a.user == OwnUser && a.matchesOwn) shouldBe true
      server.seen.exists(_.matchesDefault) shouldBe false
      other.connections shouldBe 0
      closePool(t, name, "g3p")
    finally
      server.stop()
      other.stop()
  }

  it should "not aim a row's own dbName at QoD's server by dropping the rest" in {
    ready()
    val t = s"${Prefix}fc$run"
    tenant(t)
    val token = adminOf(t, "gd", OtherPw)
    val login = Map(
      "pgHost"     -> "127.0.0.1",
      "pgPort"     -> closedPort().toString,
      "pgUser"     -> OwnUser,
      "pgPassword" -> OwnSentinel
    )
    val (c, name, created) =
      createDb(t, "byo", su, "metastore" -> Json.obj(login.toSeq.map((k, v) => k -> s(v))*))
    withClue(created.take(300))(c shouldBe 200)
    // Another tenant's database, on QoD's server; a superuser's row naming it as its own.
    val foreign = s"${Prefix}fd$run"
    foreignDb(foreign)
    val own = (login - "pgPassword") + ("dbName" -> foreign)
    updateDb(t, name, su, own.toSeq*)._1 shouldBe 200

    List(
      Map("dbName" -> foreign),
      Map("dbName" -> foreign, "pgPassword" -> ""),
      own - "pgHost",
      own - "dbName"
    ).foreach { map =>
      val (up, body) = updateDb(t, name, token, map.toSeq*)
      withClue(map.keySet.toString) {
        up shouldBe 403
        errorOf(body) shouldBe "superuser_required"
      }
    }
    val row = rowOf(t, name, su).get.hcursor.downField("metastore")
    row.get[String]("pgHost") shouldBe Right("127.0.0.1")
    row.get[String]("dbName") shouldBe Right(foreign)
    markerKept(foreign) shouldBe true
  }

  "A superuser" should "name a server, with a login of its own, for a tenant's plain user" in {
    ready()
    val server = new RecordingPgRelay(TestPostgres.pgPort)
    try
      val t = s"${Prefix}fe$run"
      tenant(t)
      val token = adminOf(t, "ge", OtherPw)
      stack.post(
        "/api/user/create",
        obj(
          "tenant"   -> s(t),
          "username" -> s("gu"),
          "password" -> s(OtherPw),
          "kind"     -> s("user"),
          "roles"    -> Json.arr(s("qod_all_tables")),
          "groups"   -> Json.arr(s("qod_all_pools"))
        )
      )
      // That server's own login: a role of its own, owning the catalog's database.
      val role = s"${Prefix}role$run"
      roles += role
      TestPostgres.psql("postgres", s"""CREATE ROLE "$role" LOGIN PASSWORD '$OwnRolePw'""")
      val login = Json.obj(
        "pgHost"     -> s("127.0.0.1"),
        "pgPort"     -> s(server.port.toString),
        "pgUser"     -> s(role),
        "pgPassword" -> s(OwnRolePw)
      )
      val catalog = s"${t}_byo"

      // With no login of its own: refused, and nothing reaches it.
      val (bare, _, bareBody) = createDb(
        t,
        "bare",
        su,
        "metastore" -> Json.obj("pgHost" -> s("127.0.0.1"), "pgPort" -> s(server.port.toString))
      )
      withClue(bareBody.take(300))(bare shouldBe 400)
      errorOf(bareBody) shouldBe "own_login_required"
      settle()
      server.connections shouldBe 0

      // No catalog database on its server: refused, naming it, and nothing is created.
      val (missingDb, _, missingBody) = createDb(t, "byo", su, "metastore" -> login)
      withClue(missingBody.take(300))(missingDb shouldBe 400)
      errorOf(missingBody) shouldBe "catalog_database_missing"
      missingBody should include(catalog)
      dbExists(catalog) shouldBe false

      // Its owner makes it; it then serves a plain user end to end, reading and writing.
      stack.catalogDatabases += catalog
      TestPostgres.psql("postgres", s"""CREATE DATABASE "$catalog" OWNER "$role"""")
      val (code, name, body) = createDb(t, "byo", su, "metastore" -> login)
      withClue(body.take(300))(code shouldBe 200)
      openPool(t, name, "g6p", su)
      serving(t, "g6p", "gu", OtherPw)
      stack
        .flight(s"CREATE TABLE $name.main.fact (id BIGINT)", "gu", OtherPw, t, "g6p")
        .isRight shouldBe true
      stack
        .flight(s"INSERT INTO $name.main.fact VALUES (1), (2)", "gu", OtherPw, t, "g6p")
        .isRight shouldBe true
      stack.flight(s"SELECT count(*) FROM $name.main.fact", "gu", OtherPw, t, "g6p") shouldBe
        Right(List("2"))

      // Every login that server saw is the row's: the manager's reads and the node's alike.
      assume(
        server.encrypted == 0,
        "the test Postgres takes TLS: its startup messages cannot be read"
      )
      server.users should not be empty
      server.users.toSet shouldBe Set(role)

      // A branch needs a database QoD can create next to it.
      val (branch, branchBody) = stack.postStatus(
        "/api/branch/create",
        obj("tenant" -> s(t), "tenantDb" -> s(name), "name" -> s("feature")),
        token
      )
      branch shouldBe 400
      errorOf(branchBody) shouldBe "branch_own_server"
      dbExists(s"${name}__br_") shouldBe false

      // Its catalog is its owner's: a tenant admin's delete leaves it.
      closePool(t, name, "g6p")
      deleteDb(t, name, token)._1 shouldBe 200
      dbExists(catalog) shouldBe true
      Using.resource(
        DriverManager
          .getConnection(TestPostgres.dbUrl(catalog), TestPostgres.pgUser, TestPostgres.pgPass)
      ) { c =>
        Using.resource(c.createStatement()) { st =>
          st.executeQuery("SELECT count(*) FROM ducklake_table").next() shouldBe true
        }
      }
    finally server.stop()
  }

  // ---------- what must keep working ----------

  "A tenant admin on the default server" should "create, serve, delete and create again" in {
    ready()
    val t = s"${Prefix}e1$run"
    tenant(t)
    val token = adminOf(t, "ea", OtherPw)
    val name  = owned(t, "lake", token, "epool", "ea", OtherPw)
    stack.flight(s"SELECT count(*) FROM $name.main.fact", "ea", OtherPw, t, "epool") shouldBe
      Right(List("5"))
    closePool(t, name, "epool")
    deleteDb(t, name, token)._1 shouldBe 200
    dbExists(name) shouldBe false
    createDb(t, "lake", token)._1 shouldBe 200
    dbExists(name) shouldBe true
  }

  "A manifest import" should "make a missing database on QoD's own server, through its node" in {
    ready()
    assume(hasPsql, "no psql on PATH: the spawn script creates a missing database with it")
    val t  = s"${Prefix}e4${run}_imported"
    val td = s"${t}_lake"
    stack.catalogDatabases += td
    stack.post(
      "/api/manifest/import",
      manifest(
        Json.obj(
          "name"        -> s(t),
          "displayName" -> s(t),
          "tenantDbs"   -> Json.arr(
            Json.obj(
              "name"     -> s(td),
              "kind"     -> s("ducklake"),
              "dataPath" -> s(dataDir(s"e4_$run"))
            )
          ),
          "pools" -> Json.arr(
            Json.obj(
              "name"             -> s("e4p"),
              "tenantDb"         -> s(td),
              "roleDistribution" -> Json
                .obj(
                  "writeonly" -> Json.fromInt(0),
                  "readonly"  -> Json.fromInt(0),
                  "dual"      -> Json.fromInt(1)
                )
            )
          )
        )
      )
    )
    opened += ((t, td, "e4p"))
    adminOf(t, "ee", OtherPw)
    serving(t, "e4p", "ee", OtherPw)
    dbExists(td) shouldBe true
    stack
      .flight(s"CREATE TABLE $td.main.fact (id BIGINT)", "ee", OtherPw, t, "e4p")
      .isRight shouldBe
      true
    closePool(t, td, "e4p")
  }

  it should "bring a tenant holding an underscore and adopt its database" in {
    ready()
    val t  = s"${Prefix}e3${run}_restored"
    val td = s"${t}_lake"
    foreignDb(td)
    stack.post(
      "/api/manifest/import",
      manifest(
        Json.obj(
          "name"        -> s(t),
          "displayName" -> s(t),
          "tenantDbs"   -> Json.arr(
            Json.obj(
              "name"     -> s(td),
              "kind"     -> s("ducklake"),
              "dataPath" -> s(dataDir(s"e3_$run"))
            )
          ),
          "pools" -> Json.arr(
            Json.obj(
              "name"             -> s("e3p"),
              "tenantDb"         -> s(td),
              "roleDistribution" -> Json
                .obj(
                  "writeonly" -> Json.fromInt(0),
                  "readonly"  -> Json.fromInt(0),
                  "dual"      -> Json.fromInt(1)
                )
            )
          )
        )
      )
    )
    opened += ((t, td, "e3p"))
    adminOf(t, "ec", OtherPw)
    serving(t, "e3p", "ec", OtherPw)
    stack
      .flight(s"CREATE TABLE $td.main.fact (id BIGINT)", "ec", OtherPw, t, "e3p")
      .isRight shouldBe
      true
    markerKept(td) shouldBe true
    closePool(t, td, "e3p")
  }

object TenantIsolationE2ESpec:
  private val HolderPw     = "Holder-Pw-7731"
  private val OtherPw      = "Other-Pw-7731"
  private val OwnUser      = "own_user_7731"
  private val OwnSentinel  = "OWN-LOGIN-SENTINEL-7731"
  private val OwnSentinel2 = "OWN-LOGIN-SENTINEL-7732"
  private val OwnRolePw    = "own-role-sentinel-7733"

  /** What one connection brought: never the password itself. */
  final case class Arrival(
      user: String,
      database: String,
      passwordArrived: Boolean,
      matchesDefault: Boolean,
      matchesOwn: Boolean
  )

  /** A byte relay to a real Postgres, recording the user each connection's startup message names:
    * never a password, which that protocol does not send in the clear here.
    */
  final class RecordingPgRelay(targetPort: Int):
    private val server = new ServerSocket()
    server.setReuseAddress(true)
    server.bind(new InetSocketAddress("127.0.0.1", 0))

    val port: Int = server.getLocalPort

    private val accepted = new java.util.concurrent.atomic.AtomicInteger(0)
    private val tls      = new java.util.concurrent.atomic.AtomicInteger(0)
    private val named    = new java.util.concurrent.CopyOnWriteArrayList[String]()

    @volatile private var running = true
    private val acceptor          = new Thread(() => loop())
    acceptor.setDaemon(true)
    acceptor.start()

    def connections: Int = accepted.get()

    /** Connections that took TLS, whose user cannot be read. */
    def encrypted: Int = tls.get()

    def users: List[String] = named.asScala.toList

    def stop(): Unit =
      running = false
      Try(server.close())
      ()

    private def loop(): Unit =
      while running do
        Try(server.accept()).toOption.foreach { client =>
          accepted.incrementAndGet()
          val t = new Thread(() => {
            Try(relay(client))
            Try(client.close())
            ()
          })
          t.setDaemon(true)
          t.start()
        }

    private def relay(client: Socket): Unit =
      val upstream = new Socket("127.0.0.1", targetPort)
      try
        val fromClient = new DataInputStream(new BufferedInputStream(client.getInputStream))
        val toServer   = new DataOutputStream(new BufferedOutputStream(upstream.getOutputStream))
        val fromServer = upstream.getInputStream
        val toClient   = client.getOutputStream
        var len        = fromClient.readInt()
        var code       = fromClient.readInt()
        var clear      = true
        // An encryption request, answered with one byte: 'S' means TLS follows.
        while clear && (code == 80877103 || code == 80877104) do
          toServer.writeInt(len)
          toServer.writeInt(code)
          toServer.flush()
          val answer = fromServer.read()
          toClient.write(answer)
          toClient.flush()
          if answer == 'S' || answer == 'G' then
            tls.incrementAndGet()
            clear = false
          else
            len = fromClient.readInt()
            code = fromClient.readInt()
        if clear then
          val body = new Array[Byte](math.max(0, len - 8))
          fromClient.readFully(body)
          // 3.x's major version; anything else (a cancel request) names no user.
          if (code >> 16) == 3 then
            val pairs = new String(body, "UTF-8").split('\u0000').grouped(2).collect {
              case Array(k, v) => k -> v
            }
            pairs.toMap.get("user").foreach(named.add)
          toServer.writeInt(len)
          toServer.writeInt(code)
          toServer.write(body)
          toServer.flush()
        val back = new Thread(() => {
          Try(fromServer.transferTo(toClient))
          Try(client.close())
          ()
        })
        back.setDaemon(true)
        back.start()
        // The raw stream: a buffered one would hold what the client sent until it filled.
        fromClient.transferTo(upstream.getOutputStream)
      finally Try(upstream.close())

  /** A Postgres wire listener that declines TLS, asks for a cleartext password and records how each
    * connection went, then closes it. `defaultPassword` and `ownPassword` are only compared with.
    */
  final class PgListener(defaultPassword: String, ownPassword: String = OwnSentinel):
    private val server = new ServerSocket()
    server.setReuseAddress(true)
    server.bind(new InetSocketAddress("127.0.0.1", 0))

    val port: Int = server.getLocalPort

    private val accepted = new java.util.concurrent.atomic.AtomicInteger(0)
    private val arrivals = new java.util.concurrent.CopyOnWriteArrayList[Arrival]()

    @volatile private var running = true
    private val acceptor          = new Thread(() => loop())
    acceptor.setDaemon(true)
    acceptor.start()

    /** Connections accepted, a password or not. */
    def connections: Int = accepted.get()

    def seen: List[Arrival] = arrivals.asScala.toList

    def await(atLeast: Int, within: FiniteDuration): Unit =
      val deadline = System.nanoTime() + within.toNanos
      while arrivals.size < atLeast && System.nanoTime() < deadline do Thread.sleep(100)

    def stop(): Unit =
      running = false
      Try(server.close())
      ()

    private def loop(): Unit =
      while running do
        Try(server.accept()).toOption.foreach { socket =>
          accepted.incrementAndGet()
          val t = new Thread(() => {
            Try(handle(socket))
            ()
          })
          t.setDaemon(true)
          t.start()
        }

    private def handle(socket: Socket): Unit =
      val in  = new DataInputStream(new BufferedInputStream(socket.getInputStream))
      val out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream))
      try
        var len  = in.readInt()
        var code = in.readInt()
        while code == 80877103 || code == 80877104 do
          out.writeByte('N')
          out.flush()
          len = in.readInt()
          code = in.readInt()
        val body = new Array[Byte](math.max(0, len - 8))
        in.readFully(body)
        val pairs    = cStrings(body).grouped(2).collect { case List(k, v) => k -> v }.toMap
        val user     = pairs.getOrElse("user", "")
        val database = pairs.getOrElse("database", "")
        out.writeByte('R')
        out.writeInt(8)
        out.writeInt(3)
        out.flush()
        val outcome = Try {
          val tag    = in.readByte()
          val length = in.readInt()
          val bytes  = new Array[Byte](math.max(0, length - 4))
          in.readFully(bytes)
          val password = new String(bytes.takeWhile(_ != 0), "UTF-8")
          Arrival(
            user,
            database,
            tag == 'p' && password.nonEmpty,
            password == defaultPassword,
            password == ownPassword
          )
        }
        arrivals.add(outcome.getOrElse(Arrival(user, database, false, false, false)))
      finally Try(socket.close())

    private def cStrings(bytes: Array[Byte]): List[String] =
      val out     = ListBuffer.empty[String]
      val current = new StringBuilder
      bytes.foreach { b =>
        if b == 0 then
          out += current.toString
          current.clear()
        else current.append(b.toChar)
      }
      out.toList.filter(_.nonEmpty)
