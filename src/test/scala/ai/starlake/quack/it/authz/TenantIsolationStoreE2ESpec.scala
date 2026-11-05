package ai.starlake.quack.it.authz

import ai.starlake.quack.ondemand.runtime.QodNodeTestBinary
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import io.circe.Json
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.{InetSocketAddress, Socket}
import java.nio.file.{Files, Path}
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*
import scala.util.{Try, Using}

/** What a tenant admin may change of a database's `objectStore`, on a real manager, real nodes and
  * a real object store: a database QoD placed under a remote default path and one of managed
  * storage are signed with QoD's keys, and neither takes a change from a tenant admin.
  *
  * Cancelled unless `QOD_S3_ACCESS_KEY_ID`, `QOD_S3_SECRET_ACCESS_KEY` and `QOD_S3_ENDPOINT` name a
  * reachable store holding the bucket `warehouse`: `scripts/fixtures/iceberg-fixture.yml`, with
  * `QOD_S3_URL_STYLE=path` and `QOD_S3_USE_SSL=false`, which nodes read from this JVM's
  * environment. Manager on 20941 (REST) / 31361 (FlightSQL) / 29561 (front door), nodes
  * 23390-23399, its own control plane; every object and database is named after a per-run hex, and
  * every database is dropped by exact name at the end.
  */
class TenantIsolationStoreE2ESpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val run = java.lang.Long.toHexString(System.nanoTime() & 0xffffffL)

  /** What [[AuthzStack.boot]] drops of a killed earlier run: `isoy`, a hex, then `_` or the end. */
  private val Prefix = "isoy"
  private val Bucket = "warehouse"
  private val Pw     = "Iso-Store-Sentinel-0"

  private val endpoint: String =
    sys.env.getOrElse("QOD_S3_ENDPOINT", "").stripPrefix("http://").stripPrefix("https://")

  private lazy val missing: Option[String] =
    if scala.util.Properties.isWin then Some("the bash spawn script")
    else if !TestPostgres.reachable then
      Some(s"test Postgres not reachable at ${TestPostgres.pgHost}:${TestPostgres.pgPort}")
    else if QodNodeTestBinary.path.isEmpty then Some("no qod-node build (QOD_NODE_BIN)")
    else if sys.env.get("QOD_S3_ACCESS_KEY_ID").forall(_.isEmpty) || endpoint.isEmpty then
      Some("QOD_S3_ACCESS_KEY_ID and QOD_S3_ENDPOINT name no object store")
    else if !storeAnswers then Some(s"no object store at $endpoint")
    else None

  private def storeAnswers: Boolean =
    Try {
      val (host, port) = endpoint.splitAt(endpoint.lastIndexOf(':'))
      Using.resource(new Socket()) { socket =>
        socket.connect(new InetSocketAddress(host, port.drop(1).toInt), 1000)
      }
    }.isSuccess

  private lazy val root: Path = Files.createTempDirectory("qod-iso-store").toRealPath()

  private var stack: AuthzStack = null

  /** A superuser's session. */
  private lazy val su: String = stack.login(None, "admin", "admin")

  /** Every (tenant, tenantDb, pool) a case opened. */
  private val opened = ListBuffer.empty[(String, String, String)]

  private def obj(fields: (String, Json)*): String = Json.obj(fields*).noSpaces
  private def s(v: String): Json                   = Json.fromString(v)
  private def ready(): Unit                        = assume(missing.isEmpty, missing.getOrElse(""))

  override def beforeAll(): Unit =
    if missing.isEmpty then
      stack = new AuthzStack(
        "isos",
        20941,
        31361,
        29561,
        20943,
        23390,
        23399,
        filesRoot = root,
        relayed = false,
        stackPrefixes = List(Prefix),
        defaultDataPath = Some(s"s3://$Bucket/$Prefix$run/default/"),
        userS3Endpoints = s"http://$endpoint",
        extraManagerConfig = s"""
          |managedObjectStore {
          |  enabled = true
          |  endpoint = "http://$endpoint"
          |  bucket = "$Bucket"
          |  urlStyle = "path"
          |  accessKeyId = "${sys.env("QOD_S3_ACCESS_KEY_ID")}"
          |  secretAccessKey = "${sys.env.getOrElse("QOD_S3_SECRET_ACCESS_KEY", "")}"
          |}""".stripMargin
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
    finally
      Try {
        val tree = Files.walk(root)
        try tree.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
        finally tree.close()
      }

  // ---------- helpers ----------

  /** A tenant whose admin holds every table and pool: the admin's session. */
  private def tenantWithAdmin(id: String, user: String): String =
    stack.post(
      "/api/tenant/create",
      obj("id" -> s(id), "displayName" -> s(id), "metastore" -> Json.obj())
    )
    stack.post(
      "/api/user/create",
      obj(
        "tenant"   -> s(id),
        "username" -> s(user),
        "password" -> s(Pw),
        "kind"     -> s("admin"),
        "roles"    -> Json.arr(s("qod_all_tables")),
        "groups"   -> Json.arr(s("qod_all_pools"))
      )
    )
    stack.login(Some(id), user, Pw)

  private def field(body: String, name: String): String =
    io.circe.parser
      .parse(body)
      .toOption
      .flatMap(_.hcursor.get[String](name).toOption)
      .getOrElse("")

  /** A `database/create`: its composed name. */
  private def createDb(t: String, suffix: String, token: String, fields: (String, Json)*): String =
    val (code, body) = stack.postStatus(
      "/api/database/create",
      obj((Seq("tenant" -> s(t), "name" -> s(suffix), "kind" -> s("ducklake")) ++ fields)*),
      token
    )
    withClue(body.take(300))(code shouldBe 200)
    val name = field(body, "name")
    stack.catalogDatabases += name
    name

  private def rowOf(t: String, name: String, token: String): Json =
    val (_, body) = stack.getStatus(s"/api/database/list?tenant=$t", token)
    io.circe.parser
      .parse(body)
      .toOption
      .flatMap(_.hcursor.downField("tenantDbs").values)
      .toList
      .flatten
      .find(_.hcursor.get[String]("name").contains(name))
      .getOrElse(fail(s"no row $name"))

  private def objectStoreOf(t: String, name: String, token: String): Map[String, String] =
    rowOf(t, name, token).hcursor
      .get[Map[String, String]]("objectStore")
      .getOrElse(fail("no objectStore on the row"))

  private def updateStore(t: String, name: String, token: String, store: Map[String, String]) =
    stack.postStatus(
      "/api/database/update",
      obj(
        "tenant"      -> s(t),
        "name"        -> s(name),
        "objectStore" -> Json.obj(store.toList.map((k, v) => k -> s(v))*)
      ),
      token
    )

  private def openPool(t: String, td: String, pool: String, token: String): Unit =
    val (code, body) = stack.postStatus(
      "/api/pool/create",
      s"""{"tenant":"$t","tenantDb":"$td","pool":"$pool","size":1,
         |"roleDistribution":{"writeonly":0,"readonly":0,"dual":1}}""".stripMargin,
      token
    )
    withClue(s"pool/create of $pool: ${body.take(300)}")(code shouldBe 200)
    opened += ((t, td, pool))

  private def serving(t: String, pool: String, user: String): Unit =
    stack.await(s"pool $pool serving", 150.seconds) {
      stack.flight("SELECT 1", user, Pw, t, pool).toOption
    }

  private def asSuperuser(sql: String, t: String, pool: String): List[String] =
    stack
      .flight(sql, "admin", "admin", t, pool, superuser = true)
      .fold(e => fail(s"as a superuser: $e"), identity)

  /** A node's secrets as a superuser reads them: name, type, scope and endpoint, no key. */
  private def secrets(t: String, pool: String): List[String] =
    asSuperuser(
      "SELECT name, type, scope, regexp_extract(secret_string, 'endpoint=([^;]*)', 1) " +
        "FROM duckdb_secrets() ORDER BY name",
      t,
      pool
    )

  /** Rows that reach the store, with whatever signs its data path. */
  private def writeRows(t: String, db: String, pool: String, user: String, path: String): Unit =
    stack.flight(s"CREATE TABLE $db.main.t (id BIGINT, v VARCHAR)", user, Pw, t, pool) shouldBe
      Right(List.empty)
    stack
      .flight(s"INSERT INTO $db.main.t SELECT i, 'x' FROM range(20000) r(i)", user, Pw, t, pool)
      .isRight shouldBe true
    asSuperuser(s"CALL ducklake_flush_inlined_data('$db')", t, pool)
    val files =
      asSuperuser(s"SELECT count(*) FROM glob('${path.stripSuffix("/")}/**/*.parquet')", t, pool)
    withClue(s"parquet files under the data path: $files")(files.head.toLong should be > 0L)

  private def rowsRead(t: String, db: String, pool: String, user: String) =
    stack.flight(s"SELECT count(*) FROM $db.main.t", user, Pw, t, pool)

  private def refused(out: (Int, String)): Unit =
    withClue(out._2.take(300)) {
      out._1 shouldBe 403
      field(out._2, "error") shouldBe "superuser_required"
    }

  private val Elsewhere = Map("s3_endpoint" -> "http://127.0.0.1:1", "s3_url_style" -> "path")

  /** What a statement reads when its location lies in a data folder that counts against its user.
    */
  private val LocationDenied = "is a database data folder"
  private val MadeUp         =
    Map("s3_access_key_id" -> "MADEUPID", "s3_secret_access_key" -> "made-up-secret-sentinel")

  // ---------- cases ----------

  "A database QoD placed under a remote default path" should
    "keep QoD's secret and store, whatever a tenant admin sends" in {
      ready()
      val t     = s"${Prefix}a1$run"
      val token = tenantWithAdmin(t, "pa")
      val name  = createDb(t, "placed", token)
      val row   = rowOf(t, name, token)
      val path  = row.hcursor.get[String]("effectiveDataPath").getOrElse(fail("no path"))
      path should startWith(s"s3://$Bucket/$Prefix$run/")
      objectStoreOf(t, name, token) shouldBe Map.empty
      openPool(t, name, "pa", token)
      serving(t, "pa", "pa")
      writeRows(t, name, "pa", "pa", path)
      val before = secrets(t, "pa")
      withClue(before.toString) {
        before.exists(l => l.startsWith("qod_data_s3") && l.endsWith(endpoint)) shouldBe true
        before.exists(_.startsWith("qod_db_store")) shouldBe false
      }

      List(Elsewhere, Elsewhere ++ MadeUp, MadeUp).foreach { store =>
        withClue(store.keySet.toString) {
          refused(updateStore(t, name, token, store))
          objectStoreOf(t, name, token) shouldBe Map.empty
        }
      }
      secrets(t, "pa") shouldBe before

      // An edit not touching it goes through, and a fresh node signs as before.
      val (code, body) = stack.postStatus(
        "/api/database/update",
        obj("tenant" -> s(t), "name" -> s(name), "initSql" -> s("SET threads = 2;")),
        token
      )
      withClue(body.take(300))(code shouldBe 200)
      serving(t, "pa", "pa")
      secrets(t, "pa") shouldBe before
      rowsRead(t, name, "pa", "pa") shouldBe Right(List("20000"))

      // What these reads tell apart: a superuser's same update does change the node's secrets.
      updateStore(t, name, su, Elsewhere)._1 shouldBe 200
      serving(t, "pa", "pa")
      val after = secrets(t, "pa")
      withClue(after.toString) {
        after.exists(_.startsWith("qod_db_store")) shouldBe true
        after.exists(_.startsWith("qod_data_s3")) shouldBe false
      }
    }

  "A superuser's database holding its own keys" should
    "keep them for a tenant admin who does not send the secret" in {
      ready()
      val t     = s"${Prefix}a3$run"
      val token = tenantWithAdmin(t, "pc")
      val path  = s"s3://$Bucket/$Prefix$run/own/$t/"
      val keys  = Map(
        "s3_access_key_id"     -> sys.env("QOD_S3_ACCESS_KEY_ID"),
        "s3_secret_access_key" -> sys.env.getOrElse("QOD_S3_SECRET_ACCESS_KEY", ""),
        "s3_endpoint"          -> s"http://$endpoint",
        "s3_url_style"         -> "path",
        "s3_region"            -> "us-east-1"
      )
      val name = createDb(
        t,
        "owned",
        su,
        "dataPath"    -> s(path),
        "objectStore" -> Json.obj(keys.toList.map((k, v) => k -> s(v))*)
      )
      openPool(t, name, "pc", su)
      serving(t, "pc", "pc")
      writeRows(t, name, "pc", "pc", path)
      val before = secrets(t, "pc")
      withClue(before.toString) {
        before.exists(l => l.startsWith("qod_db_store") && l.endsWith(endpoint)) shouldBe true
      }
      val shown = objectStoreOf(t, name, token)
      shown.keySet should not contain "s3_secret_access_key"

      val moved = shown.updated("s3_endpoint", "http://127.0.0.1:1")
      refused(updateStore(t, name, token, moved))
      objectStoreOf(t, name, token) shouldBe shown
      secrets(t, "pc") shouldBe before

      // What it was shown, sent back, is no change, and an edit not touching it goes through.
      updateStore(t, name, token, shown)._1 shouldBe 200
      val (code, body) = stack.postStatus(
        "/api/database/update",
        obj("tenant" -> s(t), "name" -> s(name), "initSql" -> s("SET threads = 2;")),
        token
      )
      withClue(body.take(300))(code shouldBe 200)
      serving(t, "pc", "pc")
      secrets(t, "pc") shouldBe before
      rowsRead(t, name, "pc", "pc") shouldBe Right(List("20000"))

      // What these reads tell apart: with the secret sent, the same change goes through.
      val (own, ownBody) =
        updateStore(t, name, token, moved + ("s3_secret_access_key" -> "own-secret-sentinel"))
      withClue(ownBody.take(300))(own shouldBe 200)
      serving(t, "pc", "pc")
      val after = secrets(t, "pc")
      withClue(after.toString) {
        after.exists(l => l.startsWith("qod_db_store") && l.endsWith("127.0.0.1:1")) shouldBe true
      }
    }

  "A database of managed storage" should
    "keep QoD's keys, whatever a tenant admin sends, and stay editable otherwise" in {
      ready()
      val t     = s"${Prefix}a2$run"
      val token = tenantWithAdmin(t, "pb")
      val name  = createDb(t, "managed", token, "managedStorage" -> Json.True)
      val row   = rowOf(t, name, token)
      val path  = row.hcursor.get[String]("effectiveDataPath").getOrElse(fail("no path"))
      path should startWith(s"s3://$Bucket/$t")
      val shown = objectStoreOf(t, name, token)
      shown.keySet should contain("s3_endpoint")
      shown.keySet should not contain "s3_secret_access_key"
      openPool(t, name, "pb", token)
      serving(t, "pb", "pb")
      writeRows(t, name, "pb", "pb", path)
      val before = secrets(t, "pb")
      withClue(before.toString)(before.exists(_.startsWith("qod_db_store")) shouldBe true)

      List(
        shown.updated("s3_endpoint", "http://127.0.0.1:1"),
        shown ++ MadeUp,
        shown - "s3_endpoint",
        MadeUp,
        Map.empty[String, String]
      ).foreach { store =>
        withClue(store.keySet.toString)(refused(updateStore(t, name, token, store)))
      }
      objectStoreOf(t, name, token) shouldBe shown
      secrets(t, "pb") shouldBe before

      // What it was shown, sent back, is no change, and an edit not touching it goes through.
      updateStore(t, name, token, shown)._1 shouldBe 200
      val (code, body) = stack.postStatus(
        "/api/database/update",
        obj("tenant" -> s(t), "name" -> s(name), "initSql" -> s("SET threads = 2;")),
        token
      )
      withClue(body.take(300))(code shouldBe 200)
      serving(t, "pb", "pb")
      secrets(t, "pb") shouldBe before
      rowsRead(t, name, "pb", "pb") shouldBe Right(List("20000"))
      objectStoreOf(t, name, token) shouldBe shown
    }

  // ---------- a user's own keys and a folder holding its own ----------

  private def addScope(token: String, scope: String): (Int, String) =
    // Registering a scope again replaces it: safe to send again on a connection lost under load.
    def send() =
      stack.postStatus(
        "/api/profile/s3/add",
        obj(
          "scope"           -> s(scope),
          "accessKeyId"     -> s(sys.env("QOD_S3_ACCESS_KEY_ID")),
          "secretAccessKey" -> s(sys.env.getOrElse("QOD_S3_SECRET_ACCESS_KEY", "")),
          "region"          -> s("us-east-1"),
          "endpoint"        -> s(s"http://$endpoint"),
          "urlStyle"        -> s("path")
        ),
        token
      )
    try send()
    catch case _: java.io.IOException => send()

  private def inDataFolder(out: (Int, String)): Unit =
    withClue(out._2.take(300)) {
      out._1 shouldBe 400
      field(out._2, "error") shouldBe "scope_in_data_folder"
    }

  private def added(out: (Int, String)): Unit =
    withClue(out._2.take(300))(out._1 shouldBe 200)

  private def effectivePath(t: String, name: String, token: String): String =
    rowOf(t, name, token).hcursor.get[String]("effectiveDataPath").getOrElse(fail("no path"))

  "A folder holding its own keys" should "count against the users of its own tenant alone" in {
    ready()
    val ta     = s"${Prefix}b1$run"
    val tb     = s"${Prefix}b2$run"
    val tokenA = tenantWithAdmin(ta, "ra")
    val tokenB = tenantWithAdmin(tb, "rb")
    val shared = s"s3://$Bucket/$Prefix$run/shared"
    val lake   = s"$shared/lake/"
    val zone   = s"${lake}zone/"
    val homeB  = createDb(tb, "home", tokenB)
    openPool(tb, homeB, "rb", tokenB)
    serving(tb, "rb", "rb")
    def asB(sql: String)                = stack.flight(sql, "rb", Pw, tb, "rb")
    def write(file: String, value: Int) =
      asB(s"COPY (SELECT $value AS v) TO '$file' (FORMAT parquet)")
    def read(file: String)                       = asB(s"SELECT sum(v) FROM read_parquet('$file')")
    def wrote(out: Either[String, List[String]]) =
      withClue(out.toString.take(300))(out.isRight shouldBe true)
    def isRefused(out: Either[String, List[String]], why: String) =
      withClue(out.toString.take(300)) {
        out.isLeft shouldBe true
        out.left.toOption.get should include(why)
      }

    // Before A's database: the scope registers, reads and writes.
    added(addScope(tokenB, zone))
    wrote(write(s"${zone}f1.parquet", 41))
    read(s"${zone}f1.parquet") shouldBe Right(List("41"))

    // A's database at a parent of it, on keys that open nothing.
    val lakeA = createDb(
      ta,
      "lake",
      tokenA,
      "dataPath"    -> s(lake),
      "objectStore" -> Json.obj(MadeUp.toList.map((k, v) => k -> s(v))*)
    )

    // After it: the same, for B's user.
    added(addScope(tokenB, s"${lake}zone2/"))
    read(s"${zone}f1.parquet") shouldBe Right(List("41"))
    wrote(write(s"${zone}f2.parquet", 42))
    read(s"${zone}f2.parquet") shouldBe Right(List("42"))
    wrote(write(s"${lake}zone2/f3.parquet", 43))

    // A's own user is refused there: their scope above it covers the URL, and their node signs it.
    inDataFolder(addScope(tokenA, zone))
    added(addScope(tokenA, s"$shared/"))
    openPool(ta, lakeA, "ra", tokenA)
    serving(ta, "ra", "ra")
    isRefused(
      stack.flight(s"SELECT sum(v) FROM read_parquet('${zone}f1.parquet')", "ra", Pw, ta, "ra"),
      "would be signed with this node's own S3 keys"
    )

    // A node of B holds no secret of A's row, and a node of A holds it.
    val ofB = secrets(tb, "rb")
    withClue(ofB.toString)(ofB.exists(_.contains(lake)) shouldBe false)
    val ofA = secrets(ta, "ra")
    withClue(ofA.toString)(
      ofA.exists(l => l.startsWith("qod_db_store") && l.contains(lake)) shouldBe true
    )

    // B's user holds a scope above everything from here on, so only the folder refuses.
    added(addScope(tokenB, s"s3://$Bucket/"))

    // A folder QoD placed for A, and one of managed storage, count against B's user.
    val placedA = createDb(ta, "placed", tokenA)
    val placed  = effectivePath(ta, placedA, tokenA).stripSuffix("/") + "/"
    inDataFolder(addScope(tokenB, s"${placed}x/"))
    isRefused(read(s"${placed}x.parquet"), LocationDenied)
    val managedA = createDb(ta, "managed", tokenA, "managedStorage" -> Json.True)
    val managed  = effectivePath(ta, managedA, tokenA).stripSuffix("/") + "/"
    inDataFolder(addScope(tokenB, s"${managed}x/"))
    isRefused(read(s"${managed}x.parquet"), LocationDenied)

    // A folder of B's own database holding its own keys counts against B's user.
    val bown = s"$shared/bown/"
    createDb(
      tb,
      "bown",
      tokenB,
      "dataPath"    -> s(bown),
      "objectStore" -> Json.obj(MadeUp.toList.map((k, v) => k -> s(v))*)
    )
    inDataFolder(addScope(tokenB, s"${bown}x/"))
    isRefused(read(s"${bown}x.parquet"), LocationDenied)

    // A tenant admin naming a path at A's folder: the whole set, whoever holds it.
    val (takenCode, takenBody) = stack.postStatus(
      "/api/database/create",
      obj(
        "tenant"      -> s(tb),
        "name"        -> s("taken"),
        "kind"        -> s("ducklake"),
        "dataPath"    -> s(lake),
        "objectStore" -> Json.obj(MadeUp.toList.map((k, v) => k -> s(v))*)
      ),
      tokenB
    )
    withClue(takenBody.take(300)) {
      takenCode shouldBe 409
      field(takenBody, "error") shouldBe "data_path_taken"
    }

    // A superuser clears its keys: B's user is refused on the next statement.
    read(s"${zone}f1.parquet") shouldBe Right(List("41"))
    val (cleared, clearedBody) =
      updateStore(ta, lakeA, su, Map("s3_access_key_id" -> "", "s3_secret_access_key" -> ""))
    withClue(clearedBody.take(300))(cleared shouldBe 200)
    isRefused(read(s"${zone}f1.parquet"), LocationDenied)
    isRefused(write(s"${zone}f4.parquet", 44), LocationDenied)
    inDataFolder(addScope(tokenB, s"${lake}zone3/"))
  }
