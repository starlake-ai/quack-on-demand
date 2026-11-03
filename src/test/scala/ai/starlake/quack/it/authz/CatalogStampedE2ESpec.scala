package ai.starlake.quack.it.authz

import ai.starlake.quack.ondemand.runtime.QodNodeTestBinary
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import io.circe.Json
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import scala.concurrent.duration.*
import scala.util.Try

/** The writes that name their own commit message (a table restore, an undrop) through the manager's
  * own executors on a real manager and a real qod-node, with `QOD_STAMP_WRITES` off: the knob that
  * stamps client writes does not apply to them, so each commit must still carry its message (the
  * restore's commit fence matches it, and both settle a lost answer by it) and the caller's author.
  * They run as a tenant admin, so the node judges the stamp's own `CALL` for that principal too.
  * The fence itself is exercised on the real catalog: a write landing in the restored table while
  * the replace runs still refuses the restore, atomically with its COMMIT, which now goes out as a
  * statement of its own. Manager on 20977 (REST) / 31377 (FlightSQL) / 29513 (front door), relay
  * 20987, nodes 23310-23314.
  */
class CatalogStampedE2ESpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val run    = java.lang.Long.toHexString(System.nanoTime() & 0xffffffL)
  private val Acme   = s"acme_cstamp_$run"
  private val TessPw = "Tess-Pw-3392"
  private val Pool   = "lake"

  private lazy val missing: Option[String] =
    if scala.util.Properties.isWin then Some("the bash spawn script")
    else if !TestPostgres.reachable then
      Some(s"test Postgres not reachable at ${TestPostgres.pgHost}:${TestPostgres.pgPort}")
    else if QodNodeTestBinary.path.isEmpty then Some("no qod-node build (QOD_NODE_BIN)")
    else None

  private lazy val root: Path = Files.createTempDirectory("qod-cstamp-files").toRealPath()

  private var stack: AuthzStack = null
  private var tessToken: String = ""

  /** The database's catalog name. */
  private var td: String = ""

  private def obj(fields: (String, Json)*): String = Json.obj(fields*).noSpaces
  private def s(v: String): Json                   = Json.fromString(v)

  override def beforeAll(): Unit =
    if missing.isEmpty then
      stack = new AuthzStack(
        "cstamp",
        20977,
        31377,
        29513,
        20987,
        23310,
        23314,
        filesRoot = root,
        stackPrefixes = List("acme_cstamp_"),
        stampWrites = false
      )
      stack.boot()
      stack.post(
        "/api/tenant/create",
        obj("id" -> s(Acme), "displayName" -> s(Acme), "metastore" -> Json.obj())
      )
      stack.post(
        "/api/user/create",
        obj(
          "tenant"   -> s(Acme),
          "username" -> s("tess"),
          "password" -> s(TessPw),
          "kind"     -> s("admin")
        )
      )
      tessToken = stack.login(Some(Acme), "tess", TessPw)
      val data = Files.createDirectories(root.resolve("lake-data"))
      td = stack
        .post(
          "/api/database/create",
          obj(
            "tenant"   -> s(Acme),
            "name"     -> s("lake"),
            "kind"     -> s("ducklake"),
            "dataPath" -> s(s"$data/")
          )
        )
        .hcursor
        .get[String]("name")
        .fold(e => throw e, identity)
      stack.catalogDatabases += td
      stack.post(
        "/api/pool/create",
        s"""{"tenant":"$Acme","tenantDb":"$td","pool":"$Pool","size":1,
           |"roleDistribution":{"writeonly":0,"readonly":0,"dual":1}}""".stripMargin
      )
      stack.await(s"pool $Pool serving", 120.seconds) {
        stack.flight("SELECT 1", "admin", "admin", Acme, Pool, superuser = true).toOption
      }

  override def afterAll(): Unit =
    try if stack != null then stack.stop()
    finally
      Try {
        val tree = Files.walk(root)
        try tree.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
        finally tree.close()
      }

  private def ready(): Unit = assume(missing.isEmpty, missing.getOrElse(""))

  /** `statement` as the seeded superuser, every row as `a,b`. */
  private def sql(statement: String): List[String] =
    stack
      .flight(statement, "admin", "admin", Acme, Pool, superuser = true)
      .fold(err => throw new IllegalStateException(s"$statement -> $err"), identity)

  /** The authors of the commits whose message matches `like`: how many landed, and as whom. */
  private def authorsOf(like: String): List[String] =
    sql(s"SELECT author FROM $td.snapshots() WHERE commit_message LIKE '$like'")

  "a table restore" should "carry its own commit message and its caller's author, with the stamp knob off" in {
    ready()
    sql(s"CREATE TABLE $td.main.rt (id BIGINT, note VARCHAR)")
    sql(s"INSERT INTO $td.main.rt VALUES (1, 'one')")
    val to = sql(s"SELECT max(snapshot_id) FROM $td.snapshots()").head.toLong
    sql(s"INSERT INTO $td.main.rt VALUES (2, 'two')")
    val (code, body) = stack.postStatus(
      "/api/catalog/restore",
      obj(
        "tenant"   -> s(Acme),
        "tenantDb" -> s(td),
        "schema"   -> s("main"),
        "table"    -> s("rt"),
        "to"       -> s(to.toString)
      ),
      tessToken
    )
    withClue(body)(code shouldBe 200)
    sql(s"SELECT note FROM $td.main.rt ORDER BY id") shouldBe List("one")
    authorsOf(s"restore main.rt to snapshot $to [%") shouldBe List(s"tenant:$Acme/user:tess")
  }

  "an undrop" should "carry its own commit message and its caller's author, with the stamp knob off" in {
    ready()
    sql(s"CREATE TABLE $td.main.ut (id BIGINT)")
    sql(s"INSERT INTO $td.main.ut VALUES (7)")
    sql(s"DROP TABLE $td.main.ut")
    val (code, body) = stack.postStatus(
      "/api/catalog/undrop",
      obj(
        "tenant"   -> s(Acme),
        "tenantDb" -> s(td),
        "schema"   -> s("main"),
        "table"    -> s("ut")
      ),
      tessToken
    )
    withClue(body)(code shouldBe 200)
    sql(s"SELECT id FROM $td.main.ut") shouldBe List("7")
    authorsOf("undrop main.ut as main.ut from snapshot %") shouldBe List(s"tenant:$Acme/user:tess")
  }

  "UNDROP TABLE" should "carry its own commit message and its caller's author, with the stamp knob off" in {
    ready()
    sql(s"CREATE TABLE $td.main.vt (id BIGINT)")
    sql(s"INSERT INTO $td.main.vt VALUES (9)")
    sql(s"DROP TABLE $td.main.vt")
    stack.flight("UNDROP TABLE main.vt AS vt_back", "tess", TessPw, Acme, Pool) match
      case Right(rows) => rows.mkString should include("restored as main.vt_back")
      case Left(err)   => fail(err)
    sql(s"SELECT id FROM $td.main.vt_back") shouldBe List("9")
    authorsOf("undrop main.vt as main.vt_back from snapshot %") shouldBe
      List(s"tenant:$Acme/user:tess")
  }

  "a table restore beside writers" should "be refused, with no write lost, when one lands in its table first" in {
    ready()
    // Heavy compression makes the replace take seconds, in which writers commit into its table: the
    // commit fence refuses the restore at its COMMIT, and nothing the writers landed is erased.
    sql(s"CALL $td.set_option('parquet_compression', 'zstd')")
    sql(s"CALL $td.set_option('parquet_compression_level', '22')")
    sql(s"CREATE TABLE $td.main.ft (id BIGINT, note VARCHAR, qty INTEGER)")
    sql(
      s"INSERT INTO $td.main.ft SELECT i, 'n' || (i % 1000), (i % 97)::INTEGER " +
        "FROM range(6000000) r(i)"
    )
    val to = sql(s"SELECT max(snapshot_id) FROM $td.snapshots()").head.toLong
    sql(s"INSERT INTO $td.main.ft VALUES (-1, 'after', 0)")
    val stop    = new AtomicBoolean(false)
    val landed  = new AtomicLong(0L)
    val writers = (0 until 3).map { w =>
      new Thread(() =>
        var i = 0L
        while !stop.get() do
          stack.flight(
            s"INSERT INTO $td.main.ft VALUES (${-1000L - w * 1000000L - i}, 'w', 1)",
            "admin",
            "admin",
            Acme,
            Pool,
            superuser = true
          ) match
            case Right(_) => landed.incrementAndGet()
            case Left(_)  => ()
          i += 1
      )
    }
    writers.foreach(_.start())
    val began        = System.nanoTime()
    val (code, body) =
      try
        stack.await("a writer's first commit", 30.seconds)(Option.when(landed.get() > 0)(()))
        stack.postStatus(
          "/api/catalog/restore",
          obj(
            "tenant"   -> s(Acme),
            "tenantDb" -> s(td),
            "schema"   -> s("main"),
            "table"    -> s("ft"),
            "to"       -> s(to.toString)
          ),
          tessToken,
          timeoutSec = 180
        )
      finally
        stop.set(true)
        writers.foreach(_.join(60000))
    val seconds = (System.nanoTime() - began) / 1000000000L
    info(s"restore answered $code after ${seconds}s, with ${landed.get()} concurrent writes landed")
    withClue(s"restore answered $code after ${seconds}s: $body; ${landed.get()} writes landed: ") {
      code shouldBe 409
      body should include("concurrent_write")
    }
    // The table was not replaced: every write the writers were told landed is there, beside the
    // row the restore would have undone.
    sql(s"SELECT count(*) FROM $td.main.ft WHERE id <= -1000") shouldBe List(landed.get().toString)
    sql(s"SELECT count(*) FROM $td.main.ft WHERE id = -1") shouldBe List("1")
  }
