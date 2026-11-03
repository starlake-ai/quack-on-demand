package ai.starlake.quack.it.authz

import ai.starlake.quack.ondemand.branch.BranchMergeSql
import ai.starlake.quack.ondemand.runtime.QodNodeTestBinary
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import io.circe.Json
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import java.sql.DriverManager
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*
import scala.util.{Try, Using}

/** The three-way merge on a real manager and real qod-node processes: proposal, conflicts, policy,
  * hold, encryption, and no cell value left behind in the control plane. Manager on 20975 (REST) /
  * 31375 (FlightSQL) / 29511 (front door), relay 20985, nodes 23280-23289.
  */
class BranchThreeWayE2ESpec
    extends AnyFlatSpec
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach:

  private val run  = java.lang.Long.toHexString(System.nanoTime() & 0xffffffL)
  private val Acme = s"acme_br3_$run"

  // Never written whole in a statement: a superuser's statement text is journaled, so any occurrence
  // in stored rows can only come from the merge.
  private val SentinelHead = "SENTINEL-CELL"
  private val SentinelTail = "-7731"
  private val Sentinel     = SentinelHead + SentinelTail

  private lazy val missing: Option[String] =
    if scala.util.Properties.isWin then Some("the bash spawn script")
    else if !TestPostgres.reachable then
      Some(s"test Postgres not reachable at ${TestPostgres.pgHost}:${TestPostgres.pgPort}")
    else if QodNodeTestBinary.path.isEmpty then Some("no qod-node build (QOD_NODE_BIN)")
    else None

  private lazy val root: Path = Files.createTempDirectory("qod-br3-files").toRealPath()

  private var stack: AuthzStack = null
  private var tessToken: String = ""

  /** The current case's databases and pools. */
  private val opened = ListBuffer.empty[(String, String)]

  private def obj(fields: (String, Json)*): String = Json.obj(fields*).noSpaces
  private def s(v: String): Json                   = Json.fromString(v)

  override def beforeAll(): Unit =
    if missing.isEmpty then
      stack = new AuthzStack(
        "br3",
        20975,
        31375,
        29511,
        20985,
        23280,
        23289,
        filesRoot = root,
        stackPrefixes = List("acme_br3_"),
        // The default, 60 s, is a third of what the maintenance case waits for its run.
        maintenanceTickSec = Some(2)
      )
      stack.boot()
      stack.post(
        "/api/tenant/create",
        obj("id" -> s(Acme), "displayName" -> s(Acme), "metastore" -> Json.obj())
      )
      // A tenant admin proposes; the superuser approves: the approver must differ.
      stack.post(
        "/api/user/create",
        obj(
          "tenant"   -> s(Acme),
          "username" -> s("tess"),
          "password" -> s("Tess-Pw-3391"),
          "kind"     -> s("admin")
        )
      )
      tessToken = stack.login(Some(Acme), "tess", "Tess-Pw-3391")

  override def afterAll(): Unit =
    try if stack != null then stack.stop()
    finally
      Try {
        val tree = Files.walk(root)
        try tree.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
        finally tree.close()
      }

  /** Ends a case's branch and nodes, so that the stack's ten node ports hold every case, and the
    * branch's catalog does not outlive the run. A branch that merged is gone already.
    */
  override def afterEach(): Unit =
    try
      opened.foreach { (td, pool) =>
        Try(stack.postStatus("/api/branch/discard", proposeBody(td, "feature")))
        Try(
          stack.postStatus(
            "/api/pool/delete",
            obj("tenant" -> s(Acme), "tenantDb" -> s(td), "pool" -> s(pool), "force" -> Json.True)
          )
        )
      }
      opened.clear()
    finally super.afterEach()

  private def ready(): Unit = assume(missing.isEmpty, missing.getOrElse(""))

  /** A new database with one pool named after it, serving. Answers its catalog name. */
  private def database(name: String, extra: (String, Json)*): String =
    val data = Files.createDirectories(root.resolve(s"$name-data"))
    val td   = stack
      .post(
        "/api/database/create",
        obj(
          (Seq(
            "tenant"   -> s(Acme),
            "name"     -> s(name),
            "kind"     -> s("ducklake"),
            "dataPath" -> s(s"$data/")
          ) ++ extra)*
        )
      )
      .hcursor
      .get[String]("name")
      .fold(e => throw e, identity)
    stack.catalogDatabases += td
    opened += ((td, name))
    stack.post(
      "/api/pool/create",
      s"""{"tenant":"$Acme","tenantDb":"$td","pool":"$name","size":1,
         |"roleDistribution":{"writeonly":0,"readonly":0,"dual":1}}""".stripMargin
    )
    stack.await(s"pool $name serving", 120.seconds) {
      stack.flight("SELECT 1", "admin", "admin", Acme, name, superuser = true).toOption
    }
    td

  private def sql(pool: String, statement: String, branch: Option[String] = None): List[String] =
    stack
      .flight(statement, "admin", "admin", Acme, pool, superuser = true, branch = branch)
      .fold(err => throw new IllegalStateException(s"$statement -> $err"), identity)

  /** A table of 10,000 rows, then a branch of it. */
  private def tableAndBranch(td: String, pool: String, branch: String): Unit =
    sql(pool, s"CREATE TABLE $td.main.fact (id BIGINT, note VARCHAR, qty INTEGER)")
    sql(pool, s"INSERT INTO $td.main.fact SELECT i, 'n', 0 FROM range(10000) r(i)")
    stack.post(
      "/api/branch/create",
      obj("tenant" -> s(Acme), "tenantDb" -> s(td), "name" -> s(branch))
    )
    branchServing(pool, branch)

  private def branchServing(pool: String, branch: String): Unit =
    stack.await(s"branch $branch of $pool serving", 120.seconds) {
      stack
        .flight("SELECT 1", "admin", "admin", Acme, pool, superuser = true, branch = Some(branch))
        .toOption
    }

  private def proposeBody(td: String, branch: String, extra: (String, Json)*): String =
    obj((Seq("tenant" -> s(Acme), "tenantDb" -> s(td), "branch" -> s(branch)) ++ extra)*)

  /** The proposal's id, read from propose's reply as an approver would. */
  private def idOf(proposed: Json): (String, Json) =
    "proposalId" -> s(proposed.hcursor.downField("merge").get[String]("id").toOption.get)

  private def merged(td: String, extraMerge: (String, Json)*): Json =
    val proposed = stack.post("/api/branch/propose", proposeBody(td, "feature"), tessToken)
    stack.post("/api/branch/merge", proposeBody(td, "feature", (idOf(proposed) +: extraMerge)*))

  private def checkCleanMerge(td: String, pool: String, extraMerge: (String, Json)*): Json =
    tableAndBranch(td, pool, "feature")
    val onBranch = Some("feature")
    sql(pool, s"UPDATE $td.main.fact SET note = 'b' WHERE id < 100", onBranch)
    sql(pool, s"DELETE FROM $td.main.fact WHERE id BETWEEN 1000 AND 1009", onBranch)
    sql(pool, s"INSERT INTO $td.main.fact VALUES (20000, 'bi', 1)", onBranch)
    sql(pool, s"UPDATE $td.main.fact SET qty = 9 WHERE id BETWEEN 50 AND 149")
    sql(pool, s"INSERT INTO $td.main.fact VALUES (30000, 'mi', 1)")
    val reply = merged(td, extraMerge*)
    sql(pool, s"SELECT note, qty FROM $td.main.fact WHERE id = 60") shouldBe List("b,9")
    sql(pool, s"SELECT note, qty FROM $td.main.fact WHERE id = 10") shouldBe List("b,0")
    sql(pool, s"SELECT note, qty FROM $td.main.fact WHERE id = 120") shouldBe List("n,9")
    sql(pool, s"SELECT count(*) FROM $td.main.fact WHERE id BETWEEN 1000 AND 1009") shouldBe
      List("0")
    sql(pool, s"SELECT note FROM $td.main.fact WHERE id >= 20000 ORDER BY id") shouldBe
      List("bi", "mi")
    reply.hcursor.downField("merge").get[String]("status") shouldBe Right("merged")
    reply.hcursor.downField("merge").get[Int]("attempts") shouldBe Right(1)
    reply

  "a merge" should "combine both sides of a table, through real nodes" in {
    ready()
    checkCleanMerge(database("plain"), "plain")
  }

  it should "work the same on an encrypted catalog" in {
    ready()
    checkCleanMerge(database("enc", "encrypted" -> Json.True), "enc")
  }

  it should "hold writers when asked, and leave no fence behind" in {
    ready()
    val td    = database("held")
    val reply = checkCleanMerge(td, "held", "holdWriters" -> Json.True)
    reply.hcursor.downField("merge").get[Boolean]("holdWriters") shouldBe Right(true)
    val left = Using.resource(
      DriverManager.getConnection(TestPostgres.dbUrl(td), TestPostgres.pgUser, TestPostgres.pgPass)
    ) { c =>
      val rs = c.createStatement().executeQuery("SELECT count(*) FROM qod_merge_fence.fence")
      rs.next()
      rs.getLong(1)
    }
    left shouldBe 0L
    // A writer commits at once afterwards.
    sql("held", s"INSERT INTO $td.main.fact VALUES (40000, 'after', 1)")
  }

  it should "refuse a conflict, then merge it under a policy, keeping no cell value anywhere" in {
    ready()
    val td = database("confl")
    tableAndBranch(td, "confl", "feature")
    val onBranch = Some("feature")
    sql(
      "confl",
      s"UPDATE $td.main.fact SET note = '$SentinelHead' || '$SentinelTail-B' WHERE id = 5",
      onBranch
    )
    sql(
      "confl",
      s"UPDATE $td.main.fact SET note = '$SentinelHead' || '$SentinelTail-M' WHERE id = 5"
    )

    // A proposal writes nothing to either catalog (design 5.1).
    def heads = (
      sql("confl", s"SELECT max(snapshot_id) FROM $td.snapshots()"),
      sql("confl", s"SELECT max(snapshot_id) FROM $td.snapshots()", onBranch)
    )
    val beforePropose = heads
    val proposed      = stack.post("/api/branch/propose", proposeBody(td, "feature"), tessToken)
    heads shouldBe beforePropose
    // tess may count the table, by the real node's verdict: the change set shows the numbers, the
    // merge row, written as the system principal, does not (design 8.2).
    val shownTable = proposed.hcursor.downField("changes").downField("tables").downArray
    shownTable.get[Boolean]("threeWay") shouldBe Right(true)
    shownTable.downField("rowConflicts").get[Long]("cellConflicts") shouldBe Right(1L)
    proposed.hcursor
      .downField("merge")
      .downField("rowConflicts")
      .downArray
      .get[Boolean]("countsUnavailable") shouldBe Right(true)
    val (code, body) =
      stack.postStatus("/api/branch/merge", proposeBody(td, "feature", idOf(proposed)))
    code shouldBe 409
    body should include("merge_conflict")
    body should include("main.fact")
    body should not include Sentinel
    sql("confl", s"SELECT note FROM $td.main.fact WHERE id = 5") shouldBe List(s"$Sentinel-M")

    val second = stack.post(
      "/api/branch/propose",
      proposeBody(td, "feature", "onConflict" -> s("branch")),
      tessToken
    )
    val done = stack.post("/api/branch/merge", proposeBody(td, "feature", idOf(second)))
    done.hcursor.downField("merge").get[String]("status") shouldBe Right("merged")
    sql("confl", s"SELECT note FROM $td.main.fact WHERE id = 5") shouldBe List(s"$Sentinel-B")

    // Numbers and rowids reach the superuser who reads the proposal; never a value, anywhere.
    val (showCode, shown) = stack.getStatus(
      s"/api/branch/tenant/$Acme/database/$td/branches/feature",
      stack.login(None, "admin", "admin")
    )
    showCode shouldBe 200 // a merged branch stays readable, with its merge history
    shown should include("cellConflicts")
    List(proposed.noSpaces, second.noSpaces, done.noSpaces, shown).foreach(
      _ should not include Sentinel
    )
    val stored = Using.resource(
      DriverManager.getConnection(
        TestPostgres.dbUrl(stack.dbName),
        TestPostgres.pgUser,
        TestPostgres.pgPass
      )
    ) { c =>
      val st = c.createStatement()
      List("qodstate_branch_merge", "qodstate_audit").flatMap { table =>
        val rs  = st.executeQuery(s"SELECT row_to_json(t)::text FROM $table t")
        val out = scala.collection.mutable.ListBuffer.empty[String]
        while rs.next() do out += rs.getString(1)
        out.toList
      }
    }
    stored should not be empty
    stored.foreach(_ should not include Sentinel)
  }

  /** A commit stamped by hand with a proposal's message: what a manager dying between the commit
    * and its record leaves behind.
    */
  private def landWith(td: String, pool: String, proposed: Json, id: Long): Unit =
    val merge   = proposed.hcursor.downField("merge")
    val message = BranchMergeSql
      .commitMessage(
        "feature",
        merge.get[String]("id").toOption.get,
        merge.get[String]("proposer").toOption.get
      )
      .replace("'", "''")
    sql(
      pool,
      s"BEGIN; CALL ducklake_set_commit_message('$td', 'x', '$message'); " +
        s"INSERT INTO $td.main.fact VALUES ($id, 'landed', 1); COMMIT"
    )

  it should "settle a commit that landed and never settled, doubling no row" in {
    ready()
    val td = database("landed")
    tableAndBranch(td, "landed", "feature")
    sql("landed", s"INSERT INTO $td.main.fact VALUES (20000, 'bi', 1)", Some("feature"))
    val proposed = stack.post("/api/branch/propose", proposeBody(td, "feature"), tessToken)
    landWith(td, "landed", proposed, 20000L)
    val done = stack.post("/api/branch/merge", proposeBody(td, "feature", idOf(proposed)))
    done.hcursor.downField("merge").get[String]("status") shouldBe Right("merged")
    sql("landed", s"SELECT count(*) FROM $td.main.fact WHERE id = 20000") shouldBe List("1")
  }

  it should "refuse propose and merge when a replaced proposal's commit is on main" in {
    ready()
    val td = database("replaced")
    tableAndBranch(td, "replaced", "feature")
    sql("replaced", s"INSERT INTO $td.main.fact VALUES (20000, 'bi', 1)", Some("feature"))
    val first  = stack.post("/api/branch/propose", proposeBody(td, "feature"), tessToken)
    val second = stack.post("/api/branch/propose", proposeBody(td, "feature"), tessToken)
    landWith(td, "replaced", first, 20000L)
    val (mergeCode, mergeBody) =
      stack.postStatus("/api/branch/merge", proposeBody(td, "feature", idOf(second)))
    mergeCode shouldBe 409
    mergeBody should include("proposal_unsettled")
    mergeBody should include("discard this branch")
    val (proposeCode, proposeBody2) =
      stack.postStatus("/api/branch/propose", proposeBody(td, "feature"))
    proposeCode shouldBe 409
    proposeBody2 should include("discard this branch")
  }

  it should "catch a double insert by its key" in {
    ready()
    val td = database("keyed")
    tableAndBranch(td, "keyed", "feature")
    sql("keyed", s"INSERT INTO $td.main.fact VALUES (50000, 'b', 1)", Some("feature"))
    sql("keyed", s"INSERT INTO $td.main.fact VALUES (50000, 'm', 1)")
    val keys    = "keys" -> Json.obj("main.fact" -> Json.arr(s("id")))
    val refused = stack.post("/api/branch/propose", proposeBody(td, "feature", keys), tessToken)
    val (code, body) =
      stack.postStatus("/api/branch/merge", proposeBody(td, "feature", idOf(refused)))
    code shouldBe 409
    body should include("merge_conflict")
    def underMain = stack.post(
      "/api/branch/propose",
      proposeBody(td, "feature", keys, "onConflict" -> s("main")),
      tessToken
    )
    // Main wins the branch's only row, so nothing is left to write: that is not a merge.
    val (emptyCode, emptyBody) =
      stack.postStatus("/api/branch/merge", proposeBody(td, "feature", idOf(underMain)))
    emptyCode shouldBe 422
    emptyBody should include("nothing_to_merge")
    // A row the key does not match merges, and the one it matches stays main's. The refusal thawed
    // the branch, whose node is back once it answers.
    branchServing("keyed", "feature")
    sql("keyed", s"INSERT INTO $td.main.fact VALUES (50001, 'own', 1)", Some("feature"))
    val done = stack.post("/api/branch/merge", proposeBody(td, "feature", idOf(underMain)))
    done.hcursor.downField("merge").get[String]("status") shouldBe Right("merged")
    sql("keyed", s"SELECT note FROM $td.main.fact WHERE id = 50000") shouldBe List("m")
    sql("keyed", s"SELECT note FROM $td.main.fact WHERE id = 50001") shouldBe List("own")
  }

  /** `(count, min, max)` of a catalog's snapshot ids. */
  private def snapshotIds(td: String, pool: String, branch: Option[String]): (Long, Long, Long) =
    sql(
      pool,
      s"SELECT count(*), min(snapshot_id), max(snapshot_id) FROM $td.snapshots()",
      branch
    ).head.split(",").map(_.toLong) match
      case Array(n, low, high) => (n, low, high)
      case other               => fail(s"unexpected row: ${other.mkString(",")}")

  it should "keep snapshot ids consecutive when commits race and retry, on both catalogs" in {
    ready()
    val td = database("racing")
    tableAndBranch(td, "racing", "feature")
    // Writers on one table commit over each other: DuckLake retries, and some give up. No id may be
    // burnt either way, or a merge would refuse history_gap for good (design 9.2).
    def race(branch: Option[String]): Unit =
      val writers = (0 until 4).map { w =>
        new Thread(() =>
          (0 until 5).foreach { i =>
            Try(
              sql(
                "racing",
                s"UPDATE $td.main.fact SET qty = ${w * 10 + i} WHERE id = ${w * 1000 + i}",
                branch
              )
            )
          }
        )
      }
      writers.foreach(_.start())
      writers.foreach(_.join())
    race(None)
    race(Some("feature"))
    List(None, Some("feature")).foreach { branch =>
      val (n, low, high) = snapshotIds(td, "racing", branch)
      withClue(s"on ${branch.getOrElse("main")}: ") {
        n shouldBe high - low + 1
        n should be > 2L
      }
    }
    // Both sides set the same values on the same rows: no conflict, and no gap.
    val reply = merged(td)
    reply.hcursor.downField("merge").get[String]("status") shouldBe Right("merged")
  }

  it should "keep main's history for a live branch through a real maintenance run" in {
    ready()
    val td = database("kept")
    tableAndBranch(td, "kept", "feature")
    sql("kept", s"UPDATE $td.main.fact SET qty = 1 WHERE id = 1")
    sql("kept", s"UPDATE $td.main.fact SET qty = 2 WHERE id = 2")
    sql("kept", s"UPDATE $td.main.fact SET note = 'b' WHERE id = 3", Some("feature"))
    // Every snapshot of main looks old enough to expire.
    Using.resource(
      DriverManager.getConnection(TestPostgres.dbUrl(td), TestPostgres.pgUser, TestPostgres.pgPass)
    ) { c =>
      c.createStatement()
        .executeUpdate(
          "UPDATE ducklake_snapshot SET snapshot_time = snapshot_time - interval '90 days'"
        )
    }
    val runId = stack
      .post(
        "/api/maintenance/run",
        obj("tenant" -> s(Acme), "tenantDb" -> s(td), "operations" -> s("expire"))
      )
      .hcursor
      .get[Long]("id")
      .fold(e => throw e, identity)
    val finished = stack.await(s"maintenance run $runId to end", 180.seconds) {
      stack
        .get(s"/api/maintenance/runs?tenant=$Acme&tenantDb=$td")
        .asArray
        .toList
        .flatten
        .find(_.hcursor.get[Long]("id").contains(runId))
        .filter(r => r.hcursor.get[String]("finishedAt").isRight)
    }
    withClue(finished.noSpaces) {
      // Below the fork can go; the fork and after are what the floor keeps.
      finished.hcursor.get[Int]("snapshotsExpired").map(_ > 0) shouldBe Right(true)
      finished.hcursor.get[Int]("snapshotsSkippedPinned").map(_ >= 2) shouldBe Right(true)
    }
    val reply = merged(td)
    reply.hcursor.downField("merge").get[String]("status") shouldBe Right("merged")
    sql("kept", s"SELECT note, qty FROM $td.main.fact WHERE id = 3") shouldBe List("b,0")
    sql("kept", s"SELECT note, qty FROM $td.main.fact WHERE id = 2") shouldBe List("n,2")
  }

  it should "refuse a history gap on either catalog, naming it" in {
    ready()
    def holeIn(td: String, pool: String, branch: Option[String]): Unit =
      val (_, _, high) = snapshotIds(td, pool, branch)
      sql(pool, s"CALL ducklake_expire_snapshots('$td', versions => [${high - 1}])", branch)
    val onMain = database("gapmain")
    tableAndBranch(onMain, "gapmain", "feature")
    sql("gapmain", s"UPDATE $onMain.main.fact SET note = 'b' WHERE id = 1", Some("feature"))
    sql("gapmain", s"UPDATE $onMain.main.fact SET qty = 1 WHERE id = 2")
    sql("gapmain", s"UPDATE $onMain.main.fact SET qty = 2 WHERE id = 3")
    holeIn(onMain, "gapmain", None)
    val (mainCode, mainBody) =
      stack.postStatus("/api/branch/propose", proposeBody(onMain, "feature"))
    mainCode shouldBe 409
    mainBody should include("history_gap")
    mainBody should include("main's snapshot")
    val onBranch = database("gapbranch")
    tableAndBranch(onBranch, "gapbranch", "feature")
    sql("gapbranch", s"UPDATE $onBranch.main.fact SET note = 'b' WHERE id = 1", Some("feature"))
    sql("gapbranch", s"UPDATE $onBranch.main.fact SET note = 'c' WHERE id = 2", Some("feature"))
    holeIn(onBranch, "gapbranch", Some("feature"))
    val (branchCode, branchBody) =
      stack.postStatus("/api/branch/propose", proposeBody(onBranch, "feature"))
    branchCode shouldBe 409
    branchBody should include("history_gap")
    branchBody should include("the branch's snapshot")
  }
