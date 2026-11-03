package ai.starlake.quack.it.authz

import ai.starlake.quack.edge.adapter.{NodeLoadTracker, QuackHttpAdapter, QuackHttpClient}
import ai.starlake.quack.edge.authz.{NodeKeys, NodePrincipal, PolicyVersions, ReadOnlyCatalogs}
import ai.starlake.quack.model.{PoolKey, Role, RunningNode}
import ai.starlake.quack.ondemand.branch.{BranchMergeSql, ThreeWayMergeScaleBench}
import ai.starlake.quack.ondemand.runtime.QodNodeTestBinary
import ai.starlake.quack.ondemand.state.testkit.TestPostgres
import cats.effect.unsafe.implicits.global
import io.circe.{ACursor, Json}
import org.apache.arrow.memory.RootAllocator
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try, Using}

/** The three-way merge on a real manager and real qod-node processes: proposal, conflicts, policy,
  * hold, encryption, a stage past one session handle's life, and no cell value left behind in the
  * control plane. Manager on 20975 (REST) / 31375 (FlightSQL) / 29511 (front door), relay 20985,
  * nodes 23280-23289.
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

  private val Other   = s"other_br3_$run"
  private val BobPw   = "Bob-Pw-3391"
  private val CarolPw = "Carol-Pw-3391"
  private val DavePw  = "Dave-Pw-3391"

  /** bob's and carol's role ids. */
  private val roleIds = scala.collection.mutable.Map.empty[String, String]

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
      // Plain members, each with a role of their own: what it grants is each case's own.
      List("bob" -> BobPw, "carol" -> CarolPw).foreach { (user, password) =>
        val role = s"${user}_role"
        roleIds(user) = stack
          .post("/api/role/create", obj("tenant" -> s(Acme), "name" -> s(role)))
          .hcursor
          .get[String]("id")
          .fold(e => throw e, identity)
        stack.post(
          "/api/user/create",
          obj(
            "tenant"   -> s(Acme),
            "username" -> s(user),
            "password" -> s(password),
            "kind"     -> s("user"),
            "roles"    -> Json.arr(s(role)),
            "groups"   -> Json.arr(s("qod_all_pools"))
          )
        )
      }
      // Every table, and no pool: only the pool grant is missing.
      stack.post(
        "/api/user/create",
        obj(
          "tenant"   -> s(Acme),
          "username" -> s("dave"),
          "password" -> s(DavePw),
          "kind"     -> s("user"),
          "roles"    -> Json.arr(s("qod_all_tables")),
          "groups"   -> Json.arr(s("qod_no_pools"))
        )
      )
      // Another tenant, whose bob is another user, with every grant there is, there.
      stack.post(
        "/api/tenant/create",
        obj("id" -> s(Other), "displayName" -> s(Other), "metastore" -> Json.obj())
      )
      stack.post(
        "/api/user/create",
        obj(
          "tenant"   -> s(Other),
          "username" -> s("bob"),
          "password" -> s(BobPw),
          "kind"     -> s("user")
        )
      )

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
        // A merged branch is gone already: only the live ones are listed.
        Try(stack.get(branchesOf(td))).toOption
          .flatMap(_.hcursor.downField("branches").values)
          .toList
          .flatten
          .flatMap(_.hcursor.get[String]("name").toOption)
          .foreach(b => cleanUp(td, "/api/branch/discard", proposeBody(td, b), Set(200, 404)))
        cleanUp(
          td,
          "/api/pool/delete",
          obj("tenant" -> s(Acme), "tenantDb" -> s(td), "pool" -> s(pool), "force" -> Json.True),
          Set(200)
        )
      }
      opened.clear()
    finally super.afterEach()

  /** One step of [[afterEach]]. A step that fails is printed: dropped, it would show only as `no
    * free port` in a later case. It fails no case of its own.
    */
  private def cleanUp(td: String, path: String, body: String, fine: Set[Int]): Unit =
    Try(stack.postStatus(path, body)) match
      case Success((code, _)) if fine(code) => ()
      case Success((code, answer))          =>
        println(s"cleanup of $td: $path answered $code: ${answer.take(300)}")
      case Failure(e) => println(s"cleanup of $td: $path failed: ${e.getMessage}")

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

  /** A table of `rows` rows, then a branch of it. */
  private def tableAndBranch(td: String, pool: String, branch: String, rows: Long = 10000L): Unit =
    sql(pool, s"CREATE TABLE $td.main.fact (id BIGINT, note VARCHAR, qty INTEGER)")
    sql(pool, s"INSERT INTO $td.main.fact SELECT i, 'n', 0 FROM range($rows) r(i)")
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

  private def branchesOf(td: String): String = s"/api/branch/tenant/$Acme/database/$td/branches"

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

  it should "land at once beside writers when it holds them, and lose no write" in {
    ready()
    val td = database("beside")
    // Enough rows for the hold to outlast several of the writers' commits.
    tableAndBranch(td, "beside", "feature", rows = 16000000L)
    sql("beside", s"UPDATE $td.main.fact SET note = 'b' WHERE id < 4000000", Some("feature"))
    val proposed = stack.post("/api/branch/propose", proposeBody(td, "feature"), tessToken)
    // On rows the branch changed too, in a column it did not.
    val stop    = new AtomicBoolean(false)
    val written = new AtomicLong(0L)
    val refused = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()
    val writers = (0 until 3).map { w =>
      new Thread(() =>
        var i = 0L
        while !stop.get() do
          stack.flight(
            s"UPDATE $td.main.fact SET qty = qty + 1 WHERE id = ${i % 20}",
            "admin",
            "admin",
            Acme,
            "beside",
            superuser = true
          ) match
            case Right(_) => written.incrementAndGet()
            case Left(e)  => refused.add(e)
          i += 1
      )
    }
    writers.foreach(_.start())
    val reply =
      try
        // Writers at full speed before the merge starts: this is what a merge has to get past.
        stack.await("a writer's first commit", 30.seconds)(Option.when(written.get() > 0)(()))
        stack.post(
          "/api/branch/merge",
          proposeBody(td, "feature", idOf(proposed), "holdWriters" -> Json.True)
        )
      finally
        stop.set(true)
        writers.foreach(_.join(30000))
    val merge = reply.hcursor.downField("merge")
    merge.get[String]("status") shouldBe Right("merged")
    merge.get[Int]("attempts") shouldBe Right(1)
    merge.get[Boolean]("holdWriters") shouldBe Right(true)
    withClue(s"${written.get()} written; refused: $refused ") {
      refused.isEmpty shouldBe false
      refused.forEach(_ should include("concurrent write conflict"))
      // Every write that was told it landed is there, beside the branch's cells.
      sql("beside", s"SELECT sum(qty) FROM $td.main.fact WHERE id < 20") shouldBe
        List(written.get().toString)
    }
    sql("beside", s"SELECT count(*) FROM $td.main.fact WHERE note = 'b'") shouldBe List("4000000")
    sql("beside", s"SELECT count(*) FROM $td.main.fact WHERE id < 20 AND note = 'b'") shouldBe
      List("20")
  }

  it should "commit a list past what one session handle lives, through the production client" in {
    ready()
    val td = database("longlist")
    sql("longlist", s"CREATE TABLE $td.main.fact (id BIGINT, note VARCHAR, qty INTEGER)")
    val (node, secret) = Using.resource(
      DriverManager.getConnection(
        TestPostgres.dbUrl(stack.dbName),
        TestPostgres.pgUser,
        TestPostgres.pgPass
      )
    ) { c =>
      val st = c.prepareStatement(
        "SELECT n.node_id, n.host, n.port, n.token FROM qodstate_node n " +
          "JOIN qodstate_pool p ON p.id = n.pool_id WHERE p.name = 'longlist'"
      )
      val rs = st.executeQuery()
      rs.next() shouldBe true
      val running = RunningNode(
        rs.getString(1),
        PoolKey(Acme, td, "longlist"),
        Role.Dual,
        rs.getString(2),
        rs.getInt(3),
        rs.getString(4),
        None,
        None,
        Instant.now()
      )
      val key = c.createStatement().executeQuery("SELECT key_secret FROM qodstate_node_authz")
      key.next() shouldBe true
      (running, key.getString(1))
    }
    val message   = "a list past one handle"
    val allocator = new RootAllocator()
    try
      val adapter = new QuackHttpAdapter(
        new QuackHttpClient(allocator),
        new NodeLoadTracker,
        new NodeKeys(
          secret,
          "http://127.0.0.1:1",
          versions = PolicyVersions.zero,
          readOnlyCatalogs = ReadOnlyCatalogs.none
        )
      )
      val began  = System.nanoTime()
      val answer = adapter
        .sendAll(
          node,
          List(
            "BEGIN",
            s"CALL ducklake_set_commit_message('$td', 'system', '$message')",
            s"INSERT INTO $td.main.fact VALUES (1, 'late', 1)",
            "SELECT sleep_ms(61000)",
            "COMMIT"
          ),
          NodePrincipal.System
        )
        .unsafeRunSync()
      answer shouldBe Right(())
      // COMMIT binds after the first handle of this list has expired.
      ((System.nanoTime() - began) / 1000000000L) should be >= 61L
    finally allocator.close()
    sql(
      "longlist",
      s"SELECT count(*) FROM $td.snapshots() WHERE commit_message = '$message'"
    ) shouldBe List("1")
    sql("longlist", s"SELECT count(*) FROM $td.main.fact WHERE note = 'late'") shouldBe List("1")
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
    emptyBody should include("loses to main under the proposal's policy")
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

  // ---------- any principal who may connect may branch ----------

  private def parsed(body: String): Json =
    io.circe.parser.parse(body).fold(e => fail(s"not JSON: $body: $e"), identity)

  /** A GET that must succeed, as `token`. */
  private def read(path: String, token: String): Json =
    val (code, body) = stack.getStatus(path, token)
    withClue(s"GET $path answered $body: ")(code shouldBe 200)
    parsed(body)

  /** The answer must be this refusal. */
  private def refused(code: Int, error: String)(answer: (Int, String)): Unit =
    withClue(s"answered ${answer._2}: ") {
      answer._1 shouldBe code
      parsed(answer._2).hcursor.get[String]("error") shouldBe Right(error)
    }

  private def tableNames(changes: Json): List[String] =
    changes.hcursor
      .downField("tables")
      .values
      .toList
      .flatten
      .flatMap(_.hcursor.get[String]("table").toOption)

  private def grant(user: String, td: String, table: String, verb: String): Unit =
    stack.post(
      "/api/role/permission/grant",
      obj(
        "roleId"  -> s(roleIds(user)),
        "catalog" -> s(td),
        "schema"  -> s("main"),
        "table"   -> s(table),
        "verb"    -> s(verb)
      )
    )

  /** A plain user's statement on `pool`, or on their branch: every row, or a failure of the case.
    */
  private def as(user: String, password: String, pool: String, branch: Option[String])(
      statement: String
  ): List[String] =
    stack
      .flight(statement, user, password, Acme, pool, branch = branch)
      .fold(err => fail(s"$user: $statement -> $err"), identity)

  /** `user` creates a branch and waits for it to serve them. */
  private def branchAs(
      user: String,
      password: String,
      token: String,
      td: String,
      pool: String,
      branch: String
  ): Unit =
    stack.post(
      "/api/branch/create",
      obj(
        "tenant"   -> s(Acme),
        "tenantDb" -> s(td),
        "name"     -> s(branch),
        "ttlHours" -> Json.fromInt(24)
      ),
      token
    )
    stack.await(s"branch $branch of $pool serving $user", 120.seconds) {
      stack.flight("SELECT 1", user, password, Acme, pool, branch = Some(branch)).toOption
    }

  private def plainTable(td: String, pool: String, table: String = "fact"): Unit =
    sql(pool, s"CREATE TABLE $td.main.$table (id BIGINT, note VARCHAR, qty INTEGER)")
    sql(pool, s"INSERT INTO $td.main.$table SELECT i, 'n', 0 FROM range(10000) r(i)")

  "a plain user" should "branch, write, read their numbers and propose; a tenant admin merges it" in {
    ready()
    val td = database("bobs")
    plainTable(td, "bobs")
    sql("bobs", s"CREATE TABLE $td.main.secret (id BIGINT, code VARCHAR)")
    sql("bobs", s"INSERT INTO $td.main.secret VALUES (1, 'x'), (2, 'y')")
    grant("bob", td, "fact", "RW")
    val bob = stack.login(Some(Acme), "bob", BobPw)
    branchAs("bob", BobPw, bob, td, "bobs", "feature")
    val onBranch = Some("feature")
    val asBob    = as("bob", BobPw, "bobs", onBranch)
    asBob(s"UPDATE $td.main.fact SET note = 'b' WHERE id < 100")
    asBob(s"INSERT INTO $td.main.fact VALUES (20000, 'bi', 1)")
    // Written by the superuser, on bob's branch: he holds no grant on it, and it merges with it.
    sql("bobs", s"UPDATE $td.main.secret SET code = 'z' WHERE id = 1", onBranch)
    // Main moves too: rows of both sides, in other columns.
    sql("bobs", s"UPDATE $td.main.fact SET qty = 9 WHERE id BETWEEN 50 AND 149")
    val base = branchesOf(td)

    // The list and the branch itself read to him, owner included.
    read(base, bob).hcursor.downField("branches").downN(0).get[String]("owner") shouldBe Right(
      "bob"
    )
    val (detailCode, detailBody) = stack.getStatus(s"$base/feature", bob)
    detailCode shouldBe 200
    detailBody should not include "secret"

    // His numbers, by the node's own verdict on him; secret is not named, only counted.
    val (changesCode, changesBody) = stack.getStatus(s"$base/feature/changes", bob)
    withClue(changesBody)(changesCode shouldBe 200)
    changesBody should not include "secret"
    val changes = parsed(changesBody)
    tableNames(changes) shouldBe List("fact")
    val fact = changes.hcursor.downField("tables").downN(0)
    (fact.get[Long]("updated"), fact.get[Long]("inserted")) shouldBe (Right(100L), Right(1L))
    fact.get[Boolean]("countsUnavailable") shouldBe Right(false)
    fact.get[Boolean]("threeWay") shouldBe Right(true)
    changes.hcursor.get[Int]("otherTables") shouldBe Right(1)
    changes.hcursor.get[List[String]]("unsupported") shouldBe Right(Nil)

    // The diffs of a table he may read, and of one he may not, which reads as no such table.
    read(s"$base/feature/schema-diff?schema=main&table=fact", bob)
    read(s"$base/feature/diff?schema=main&table=fact&limit=5", bob).hcursor
      .downField("summary")
      .get[Long]("updated") shouldBe Right(100L)
    List("schema-diff", "diff").foreach { diff =>
      val (hiddenCode, hidden) =
        stack.getStatus(s"$base/feature/$diff?schema=main&table=secret", bob)
      val (absentCode, absent) =
        stack.getStatus(s"$base/feature/$diff?schema=main&table=ghost", bob)
      withClue(s"$diff: $hidden: ") {
        hiddenCode shouldBe 404
        (hiddenCode, hidden.replace("secret", "<t>")) shouldBe
          (absentCode, absent.replace("ghost", "<t>"))
      }
    }
    // A bad filter is refused first, for both.
    val (badCode, bad) =
      stack.getStatus(s"$base/feature/diff?schema=main&table=secret&changeType=nope", bob)
    val (ghostCode, ghost) =
      stack.getStatus(s"$base/feature/diff?schema=main&table=ghost&changeType=nope", bob)
    (badCode, bad) shouldBe (ghostCode, ghost)

    // A key on a table he does not see reads as one not in the change set, whatever its columns.
    def keyedOn(table: String, column: String) = stack.postStatus(
      "/api/branch/propose",
      proposeBody(td, "feature", "keys" -> Json.obj(s"main.$table" -> Json.arr(s(column)))),
      bob
    )
    val noSuchTable = keyedOn("ghost", "id")
    refused(400, "invalid_key")(noSuchTable)
    List(keyedOn("secret", "id"), keyedOn("secret", "no_such_column")).foreach { (code, body) =>
      (code, body.replace("secret", "ghost")) shouldBe noSuchTable
    }

    // His proposal: the numbers of the table he may count, none on the merge row.
    val proposed = stack.post("/api/branch/propose", proposeBody(td, "feature"), bob)
    proposed.noSpaces should not include "secret"
    val shown = proposed.hcursor.downField("changes").downField("tables").downN(0)
    shown.get[String]("table") shouldBe Right("fact")
    val rows = shown.downField("rowConflicts")
    (rows.get[Long]("toUpdate"), rows.get[Long]("toInsert")) shouldBe (Right(100L), Right(1L))
    rows.get[Boolean]("hasConflicts") shouldBe Right(false)
    val merge = proposed.hcursor.downField("merge")
    merge.get[String]("proposer") shouldBe Right("bob")
    merge.get[List[Json]]("rowConflicts") shouldBe Right(Nil)
    tableNames(merge.downField("summary").focus.get) shouldBe List("fact")

    // He may not merge it: the guard says so, on a session that may do all of the above.
    refused(403, "admin_required")(
      stack.postStatus("/api/branch/merge", proposeBody(td, "feature", idOf(proposed)), bob)
    )

    // The tenant admin sees it all, secret included, and merges bob's proposal.
    val asTess = read(s"$base/feature/changes", tessToken)
    tableNames(asTess).toSet shouldBe Set("fact", "secret")
    asTess.hcursor.get[Int]("otherTables") shouldBe Right(0)
    val done =
      stack.post("/api/branch/merge", proposeBody(td, "feature", idOf(proposed)), tessToken)
    done.hcursor.downField("merge").get[String]("status") shouldBe Right("merged")
    done.hcursor.downField("merge").get[String]("approver") shouldBe Right("tess")
    sql("bobs", s"SELECT note, qty FROM $td.main.fact WHERE id = 60") shouldBe List("b,9")
    sql("bobs", s"SELECT note FROM $td.main.fact WHERE id = 20000") shouldBe List("bi")
    sql("bobs", s"SELECT code FROM $td.main.secret WHERE id = 1") shouldBe List("z")
  }

  it should "learn nothing of a masked cell from a proposal, right guess or wrong" in {
    ready()
    val td = database("masked")
    plainTable(td, "masked")
    // A table with no policy on it: a policy holder's counts are refused there too.
    plainTable(td, "masked", "beside")
    grant("carol", td, "fact", "RW")
    grant("carol", td, "beside", "RW")
    stack.post(
      "/api/role/column-policy/create",
      obj(
        "roleId"       -> s(roleIds("carol")),
        "catalogName"  -> s(td),
        "schemaName"   -> s("main"),
        "tableName"    -> s("fact"),
        "columnName"   -> s("note"),
        "action"       -> s("mask"),
        "transformSql" -> s("'***'")
      )
    )
    val carol = stack.login(Some(Acme), "carol", CarolPw)
    val base  = branchesOf(td)
    val root  = stack.login(None, "admin", "admin")
    List("cell-right", "cell-wrong", "key-right", "key-wrong").foreach(
      branchAs("carol", CarolPw, carol, td, "masked", _)
    )
    // What main wrote since the fork, and she must not learn: the mask holds on a plain read.
    sql("masked", s"UPDATE $td.main.fact SET note = 'top-secret' WHERE id = 5")
    sql("masked", s"INSERT INTO $td.main.fact VALUES (50000, 'also-secret', 1)")
    as("carol", CarolPw, "masked", None)(s"SELECT note FROM $td.main.fact WHERE id = 5") shouldBe
      List("***")
    def on(branch: String) = as("carol", CarolPw, "masked", Some(branch))
    // On her own branches: an UPDATE of the masked cell, and a row keyed on the masked column.
    List("cell-right" -> "top-secret", "cell-wrong" -> "not-it").foreach { (branch, guess) =>
      on(branch)(s"UPDATE $td.main.fact SET note = '$guess' WHERE id = 5")
      on(branch)(s"UPDATE $td.main.beside SET qty = 1 WHERE id = 5")
    }
    List("key-right" -> "also-secret", "key-wrong" -> "not-it").foreach { (branch, guess) =>
      on(branch)(s"INSERT INTO $td.main.fact VALUES (60000, '$guess', 1)")
      on(branch)(s"UPDATE $td.main.beside SET qty = 1 WHERE id = 5")
    }

    /** What cannot be the same twice: the ids, the clock, and the branch's own names. */
    def bare(j: Json): Json =
      j.mapObject(
        _.remove("id")
          .remove("branch")
          .remove("name")
          .remove("pool")
          .remove("catalogDb")
          .remove("createdAt")
          .remove("updatedAt")
          .remove("decidedAt")
          .remove("expiresAt")
          .mapValues(bare)
      ).mapArray(_.map(bare))

    /** Her propose reply, then her read of the branch, then its row in the history, as she sees
      * them.
      */
    def replies(branch: String, keys: Map[String, List[String]]): List[Json] =
      val body = proposeBody(
        td,
        branch,
        "keys" -> Json.fromFields(
          keys.map((t, cols) => t -> Json.arr(cols.map(s)*))
        )
      )
      val proposed = stack.post("/api/branch/propose", body, carol)
      val changes  = read(s"$base/$branch/changes", carol)
      val detail   = read(s"$base/$branch", carol)
      List(proposed, changes, detail)

    /** What the system's Decide found, as a superuser reads it. */
    def found(branch: String): (Boolean, Long, Long) =
      val row = read(s"$base/$branch", root).hcursor
        .downField("merges")
        .downN(0)
        .downField("rowConflicts")
        .values
        .toList
        .flatten
        .find(_.hcursor.get[String]("table").contains("fact"))
        .getOrElse(fail(s"no row numbers of fact on $branch"))
        .hcursor
      (
        row.get[Boolean]("hasConflicts").toOption.get,
        row.get[Long]("cellConflicts").toOption.get,
        row.get[Long]("keyMatches").toOption.get
      )

    List(
      ("cell", Map.empty[String, List[String]], (false, 0L, 0L), (true, 1L, 0L)),
      ("key", Map("main.fact" -> List("note")), (true, 0L, 1L), (false, 0L, 0L))
    ).foreach { (kind, keys, onRight, onWrong) =>
      withClue(s"through a $kind: ") {
        val right = replies(s"$kind-right", keys)
        val wrong = replies(s"$kind-wrong", keys)
        // The oracle's bit is there to read: the two proposals differ, to a superuser.
        found(s"$kind-right") shouldBe onRight
        found(s"$kind-wrong") shouldBe onWrong
        // And not to her: the same replies, whichever she guessed.
        right.map(bare) shouldBe wrong.map(bare)
        right.foreach(_.noSpaces should not include "secret")
        // A table she can see, with no entry, and no number; beside, under no policy, the same.
        val (reply, changes) = (right.head.hcursor.downField("changes").focus.get, right(1))
        List(reply, changes).foreach { c =>
          tableNames(c).toSet shouldBe Set("fact", "beside")
          c.hcursor.get[Int]("otherTables") shouldBe Right(0)
          c.hcursor.downField("tables").values.toList.flatten.foreach { t =>
            t.hcursor.downField("rowConflicts").focus.forall(_.isNull) shouldBe true
            t.hcursor.get[Boolean]("countsUnavailable") shouldBe Right(true)
            List("inserted", "deleted", "updated")
              .foreach(n => t.hcursor.get[Long](n) shouldBe Right(0L))
          }
        }
        right.head.hcursor.downField("merge").get[List[Json]]("rowConflicts") shouldBe Right(Nil)
        right(2).hcursor.downField("merges").downN(0).get[List[Json]]("rowConflicts") shouldBe
          Right(Nil)
      }
    }
  }

  it should "propose only what they own, and ask for no forever" in {
    ready()
    val td = database("owned")
    plainTable(td, "owned")
    grant("bob", td, "fact", "RW")
    grant("carol", td, "fact", "RW")
    val carol = stack.login(Some(Acme), "carol", CarolPw)
    val bob   = stack.login(Some(Acme), "bob", BobPw)
    branchAs("carol", CarolPw, carol, td, "owned", "carols")
    refused(403, "not_owner")(
      stack.postStatus("/api/branch/propose", proposeBody(td, "carols"), bob)
    )
    refused(403, "not_owner")(
      stack.postStatus("/api/branch/discard", proposeBody(td, "carols"), bob)
    )
    val asked = (ttl: Int) =>
      stack.postStatus(
        "/api/branch/create",
        obj(
          "tenant"   -> s(Acme),
          "tenantDb" -> s(td),
          "name"     -> s("forever"),
          "ttlHours" -> Json.fromInt(ttl)
        ),
        bob
      )
    refused(400, "invalid_ttl")(asked(0))
    refused(400, "invalid_ttl")(asked(100000))
    asked(0)._2 should include("168")
    read(branchesOf(td), bob).hcursor.downField("branches").values.toList.flatten.size shouldBe 1
    // The owner discards her own.
    stack.post("/api/branch/discard", proposeBody(td, "carols"), carol)
    read(branchesOf(td), bob).hcursor.downField("branches").values.toList.flatten shouldBe Nil
  }

  it should "be refused every endpoint with no pool of the database, and every tenant but their own" in {
    ready()
    val td = database("sealed")
    plainTable(td, "sealed")
    stack.post(
      "/api/branch/create",
      obj("tenant" -> s(Acme), "tenantDb" -> s(td), "name" -> s("feature"))
    )
    val base = branchesOf(td)
    val body = proposeBody(td, "feature")

    /** Every branch route, as `token`, on tenant `tenant`'s path or body. */
    def everyRoute(token: String, tenant: String): List[(Int, String)] =
      val path = s"/api/branch/tenant/$tenant/database/$td/branches"
      val op   = obj("tenant" -> s(tenant), "tenantDb" -> s(td), "branch" -> s("feature"))
      List(
        stack.getStatus(path, token),
        stack.getStatus(s"$path/feature", token),
        stack.getStatus(s"$path/feature/changes", token),
        stack.getStatus(s"$path/feature/diff?schema=main&table=fact", token),
        stack.getStatus(s"$path/feature/schema-diff?schema=main&table=fact", token),
        stack.postStatus(
          "/api/branch/create",
          obj(
            "tenant"   -> s(tenant),
            "tenantDb" -> s(td),
            "name"     -> s("nope"),
            "ttlHours" -> Json.fromInt(24)
          ),
          token
        ),
        stack.postStatus("/api/branch/propose", op, token),
        stack.postStatus("/api/branch/discard", op, token)
      )

    // dave holds every table and no pool: 403 on every endpoint, whichever credential.
    val dave    = stack.login(Some(Acme), "dave", DavePw)
    val davePat = stack
      .post(
        "/api/auth/pat/create",
        obj("name" -> s(s"dave-$run"), "dropAdmin" -> Json.False),
        token = dave
      )
      .hcursor
      .get[String]("token")
      .fold(e => throw e, identity)
    List(dave, davePat).foreach { token =>
      everyRoute(token, Acme).foreach(refused(403, "no_pool_access"))
      refused(403, "admin_required")(
        stack.postStatus(
          "/api/branch/merge",
          proposeBody(td, "feature", "proposalId" -> s("m")),
          token
        )
      )
    }

    // Another tenant's bob, by name a member here, and with every grant over there.
    val stranger = stack.login(Some(Other), "bob", BobPw)
    everyRoute(stranger, Acme).foreach(refused(403, "tenant_forbidden"))
    // Our bob, who may connect here, on the other tenant's path and on one that does not exist.
    grant("bob", td, "fact", "RW")
    val bob = stack.login(Some(Acme), "bob", BobPw)
    read(base, bob)
    everyRoute(bob, Other).foreach(refused(403, "tenant_forbidden"))
    everyRoute(bob, "no_such_tenant").foreach(refused(403, "tenant_forbidden"))
    // Nothing got through: the branch is as it was, and nothing else was made.
    read(base, tessToken).hcursor.downField("branches").values.toList.flatten.size shouldBe 1
    read(s"$base/feature", tessToken).hcursor.get[List[Json]]("merges") shouldBe Right(Nil)
  }

  it should "work over MCP with their own token, within its axes" in {
    ready()
    val td = database("agent")
    plainTable(td, "agent")
    sql("agent", s"CREATE TABLE $td.main.secret (id BIGINT, code VARCHAR)")
    grant("bob", td, "fact", "RW")
    val bob                                        = stack.login(Some(Acme), "bob", BobPw)
    def pat(name: String, fields: (String, Json)*) = stack
      .post(
        "/api/auth/pat/create",
        obj((List("name" -> s(s"$name-$run"), "dropAdmin" -> Json.False) ++ fields)*),
        token = bob
      )
      .hcursor
      .get[String]("token")
      .fold(e => throw e, identity)
    // A token that writes only on a branch: agents never write main, as a hard rule.
    val agent                                     = pat("agent", "branchOnly" -> Json.True)
    def tool(name: String, args: (String, Json)*) =
      stack.mcpTool(agent, name, Json.obj((("database" -> s(td)) +: args)*))
    def ok(answer: Either[String, String]): Json =
      answer.fold(err => fail(s"the tool refused: $err"), parsed)
    val created = ok(
      tool("create_branch", "name" -> s("feature"), "ttl_hours" -> Json.fromInt(24))
    )
    created.hcursor.get[String]("owner") shouldBe Right("bob")
    stack.await("the branch serving over MCP", 120.seconds) {
      tool("run_sql", "sql" -> s("SELECT 1"), "branch" -> s("feature")).toOption
    }
    // Its write on the branch lands, and main refuses it.
    ok(
      tool(
        "run_sql",
        "sql"    -> s(s"UPDATE $td.main.fact SET note = 'agent' WHERE id < 10"),
        "branch" -> s("feature")
      )
    )
    tool("run_sql", "sql" -> s(s"UPDATE $td.main.fact SET note = 'agent' WHERE id < 10")) match
      case Left(err) => err should include("write_requires_branch")
      case Right(t)  => fail(s"the branch-only token wrote on main: $t")
    sql("agent", s"SELECT count(*) FROM $td.main.fact WHERE note = 'agent'") shouldBe List("0")
    sql("agent", s"INSERT INTO $td.main.secret VALUES (1, 'x')", Some("feature"))

    val changes = ok(tool("branch_changes", "branch" -> s("feature")))
    tableNames(changes) shouldBe List("fact")
    changes.hcursor.get[Int]("otherTables") shouldBe Right(1)
    changes.noSpaces should not include "secret"
    changes.hcursor.downField("tables").downN(0).get[Long]("updated") shouldBe Right(10L)
    val proposed = ok(tool("propose_merge", "branch" -> s("feature")))
    proposed.hcursor.downField("merge").get[String]("proposer") shouldBe Right("bob")
    proposed.hcursor.downField("merge").get[List[Json]]("rowConflicts") shouldBe Right(Nil)
    proposed.noSpaces should not include "secret"
    // No merge tool, and no forever.
    tool("merge", "branch" -> s("feature")).isLeft shouldBe true
    tool("create_branch", "name" -> s("forever"), "ttl_hours" -> Json.fromInt(0)) match
      case Left(err) => err should include("invalid_ttl")
      case Right(t)  => fail(s"a plain token asked for no TTL: $t")

    // A token scoped to another database sees nothing here.
    val elsewhere = pat("elsewhere", "databases" -> Json.arr(s("no_such_db")))
    stack.mcpTool(elsewhere, "list_branches", Json.obj("database" -> s(td))) match
      case Left(err) => err should include("token_scope")
      case Right(t)  => fail(s"a token scoped elsewhere listed: $t")

    // A tenant admin's --drop-admin token reaches these endpoints as the plain user it is: tess is
    // an admin, whose token may not merge, nor ask for no TTL, nor read her numbers as an admin's.
    val dropped = stack
      .post(
        "/api/auth/pat/create",
        obj("name" -> s(s"tess-$run"), "dropAdmin" -> Json.True),
        token = tessToken
      )
      .hcursor
      .get[String]("token")
      .fold(e => throw e, identity)
    stack.mcpTool(
      dropped,
      "create_branch",
      Json.obj("database" -> s(td), "name" -> s("forever"), "ttl_hours" -> Json.fromInt(0))
    ) match
      case Left(err) => err should include("invalid_ttl")
      case Right(t)  => fail(s"a --drop-admin token asked for no TTL: $t")
    refused(403, "admin_required")(
      stack.postStatus(
        "/api/branch/merge",
        proposeBody(td, "feature", idOf(proposed)),
        dropped
      )
    )
    // tess herself merges what the agent proposed.
    val done =
      stack.post("/api/branch/merge", proposeBody(td, "feature", idOf(proposed)), tessToken)
    done.hcursor.downField("merge").get[String]("status") shouldBe Right("merged")
    sql("agent", s"SELECT count(*) FROM $td.main.fact WHERE note = 'agent'") shouldBe List("10")
  }

  /** What `rowConflicts` said, without the sampled rowids. */
  private def rowCounts(conflicts: ACursor): String =
    def bare(j: Json) = j.mapObject(_.remove("samples")).noSpaces
    conflicts.focus.fold("none")(j => j.asArray.fold(bare(j))(_.map(bare).mkString(" ")))

  /** `ThreeWayMergeScaleBench`'s table and writes on the real stack, for what its embedded engine
    * never meets: session handles, a node's screens and settings, the production client. Not part
    * of `sbt test`: it runs only at a size the environment names. Prints what each step took.
    */
  it should "merge the benchmark's change set through a real node, at the size it is given" in {
    def setting(name: String) = sys.env.get(name).map(_.trim).filter(_.nonEmpty)
    val rows                  = setting("QOD_BENCH_NODE_ROWS").map(_.toLong)
    val changed               = setting("QOD_BENCH_NODE_CHANGES").map(_.toLong)
    val memory                = setting("QOD_BENCH_NODE_MEMORY")
    assume(
      rows.nonEmpty && changed.nonEmpty,
      "QOD_BENCH_NODE_ROWS and QOD_BENCH_NODE_CHANGES are not set"
    )
    ready()
    val (n, k) = (rows.get, changed.get)
    val m      = n / k
    // The writes' predicates are residues 0 to 6 of m: only past 6 do they name separate rows, and
    // the numbers below count on that.
    require(m > 6, "QOD_BENCH_NODE_CHANGES must be at most a seventh of QOD_BENCH_NODE_ROWS")

    def timed[A](what: String)(body: => A): A =
      val began = System.nanoTime()
      val out   = body
      println(f"node bench: $what: ${(System.nanoTime() - began) / 1e9}%.1f s")
      out

    val td = database("large")
    // The merge node starts from the same script and this pool's init SQL, so it runs with these,
    // unless the size comes with a limit of its own (below).
    val settings = "current_setting('memory_limit'), current_setting('threads'), " +
      "current_setting('temp_directory')"
    val poolNode = sql("large", s"SELECT $settings")
    println(s"node bench: memory_limit, threads, temp_directory: $poolNode")
    timed("the table") {
      sql("large", s"CREATE SCHEMA $td.s")
      sql("large", ThreeWayMergeScaleBench.createTable(td, n))
    }
    timed("the branch") {
      stack.post(
        "/api/branch/create",
        obj("tenant" -> s(Acme), "tenantDb" -> s(td), "name" -> s("feature")),
        timeoutSec = 3600
      )
      branchServing("large", "feature")
    }
    timed("main's writes") {
      ThreeWayMergeScaleBench.changes(td, "main", n, k).foreach(sql("large", _))
    }
    timed("the branch's writes") {
      ThreeWayMergeScaleBench.changes(td, "branch", n, k).foreach(sql("large", _, Some("feature")))
    }

    // A limit for the nodes that start from here on: a running node keeps its setup, and the writes
    // above can need more (a single UPDATE of 105M rows fails at 16GB). Each of those nodes writes
    // down what it runs with, in its own init statements, so that the merge node itself is read,
    // and not what its spec should have copied from a serving pool.
    val nodeSaw = root.resolve("large-node-settings.csv")
    memory.foreach { limit =>
      stack.post(
        "/api/pool/setInitSql",
        obj(
          "tenant"   -> s(Acme),
          "tenantDb" -> s(td),
          "pool"     -> s("large"),
          "initSql"  -> s(
            s"SET memory_limit = '$limit';\n" +
              s"COPY (SELECT $settings) TO '$nodeSaw' (FORMAT csv, HEADER false);"
          )
        )
      )
    }

    // The writes make conflicts on purpose, so the proposal names a winner.
    val proposed = timed("propose") {
      stack.post(
        "/api/branch/propose",
        proposeBody(td, "feature", "onConflict" -> s("main")),
        tessToken,
        timeoutSec = 3600
      )
    }
    println(
      s"node bench: propose: attempts ${proposed.hcursor.downField("merge").get[Int]("attempts")}, " +
        "the counts tess sees: " +
        rowCounts(
          proposed.hcursor
            .downField("changes")
            .downField("tables")
            .downArray
            .downField("rowConflicts")
        )
    )

    // The proposal's analysis node wrote this file too: from here on only the merge node can.
    Files.deleteIfExists(nodeSaw)

    // The proposal's row is marked just before an Apply goes out. So this clock runs from there to
    // the reply: Apply, the look for its commit, the merge node's stop, the branch's teardown.
    val proposalId = proposed.hcursor.downField("merge").get[String]("id").toOption.get
    val applySent  = new AtomicLong(0L)
    val watching   = new AtomicBoolean(true)
    val watcher    = new Thread(() =>
      Using.resource(
        DriverManager.getConnection(
          TestPostgres.dbUrl(stack.dbName),
          TestPostgres.pgUser,
          TestPostgres.pgPass
        )
      ) { c =>
        val st = c.prepareStatement("SELECT error FROM qodstate_branch_merge WHERE id = ?")
        st.setString(1, proposalId)
        while watching.get() && applySent.get() == 0L do
          val rs = st.executeQuery()
          if rs.next() && rs.getString(1) != null then applySent.set(System.nanoTime())
          else Thread.sleep(200)
      }
    )
    watcher.start()
    val reply =
      try
        timed("merge") {
          stack.post(
            "/api/branch/merge",
            proposeBody(td, "feature", idOf(proposed)),
            timeoutSec = 3600
          )
        }
      finally watching.set(false)
    val ended = System.nanoTime()
    watcher.join(10000)
    val merge = reply.hcursor.downField("merge")
    println(
      "node bench: Apply and settle: " +
        Option(applySent.get())
          .filter(_ > 0L)
          .fold("not seen")(at => f"${(ended - at) / 1e9}%.1f s")
    )
    println(
      s"node bench: merge: attempts ${merge.get[Int]("attempts")}, " +
        s"the counts the superuser sees: ${rowCounts(merge.downField("rowConflicts"))}"
    )
    merge.get[String]("status") shouldBe Right("merged")
    // Under a limit of its own, a merge may run out of memory once and retry, as the design says.
    if memory.isEmpty then merge.get[Int]("attempts") shouldBe Right(1)
    memory.foreach { _ =>
      val saw = Files.readString(nodeSaw).trim
      println(s"node bench: memory_limit, threads, temp_directory on the merge node: $saw")
      saw.split(",").headOption should not be poolNode.head.split(",").headOption
    }

    // What the writes must have left, from their predicates alone. Main wins the conflicts on amount
    // (residue 2 of 20m), and its update of a row keeps the row from the branch's delete (residue 6
    // of 100m). Each list is, in order: the rows, the sum of their ids (a row lost and another of its
    // class doubled agree on everything else), those carrying main's amount, the branch's status and
    // the branch's qty, main's and the branch's inserts, those carrying the branch's amount, and the
    // rows that should be gone or hold any other cell than these predicates and the inserts say.
    val amountByMain         = s"h % $m = 0 OR h % ${m * 20} IN (2, 3) OR h % ${m * 100} = 6"
    val statusByBranch       = s"h % $m = 1 OR h % ${m * 20} = 3"
    val qtyByBranch          = s"h % $m = 1"
    val deleted              = s"h % ${m * 10} IN (4, 5)"
    val inserted             = k / 10
    val mainFirst            = n + 1000000000L
    val branchFirst          = n + 2000000000L
    def idsFrom(first: Long) = BigInt(inserted) * first + BigInt(inserted) * (inserted - 1) / 2
    val expected             = sql(
      "large",
      s"SELECT $n - count(*) FILTER ($deleted) + ${2 * inserted}, " +
        s"(sum(i::HUGEINT) FILTER (NOT ($deleted)) + ${idsFrom(mainFirst) + idsFrom(branchFirst)})" +
        "::VARCHAR, " +
        s"count(*) FILTER ($amountByMain), count(*) FILTER ($statusByBranch), " +
        s"count(*) FILTER ($qtyByBranch), $inserted, $inserted, 0, 0 " +
        s"FROM (SELECT i, hash(i) AS h FROM range($n) r(i))"
    )
    // One side's inserted rows, each cell as its INSERT wrote it.
    def insertedFrom(first: Long, cells: String*) =
      val same = ThreeWayMergeScaleBench.Columns.tail
        .zip(cells)
        .map((column, value) => s"$column IS NOT DISTINCT FROM $value")
        .mkString(" AND ")
      s"(id >= $first AND id < ${first + inserted} AND $same)"
    val fromMain = insertedFrom(
      mainFirst,
      "1",
      "1",
      "'NEW'",
      "1.00",
      "1",
      "TIMESTAMP '2030-01-01'",
      "'main-ins'",
      "0.5",
      "true"
    )
    val fromBranch = insertedFrom(
      branchFirst,
      "2",
      "2",
      "'NEW'",
      "2.00",
      "2",
      "TIMESTAMP '2031-01-01'",
      "'br-ins'",
      "0.5",
      "false"
    )
    val made      = ThreeWayMergeScaleBench.created("id").toMap
    val wrongCell = ThreeWayMergeScaleBench
      .created("id")
      .map { (column, value) =>
        val should = column match
          case "amount" => s"$value + CASE WHEN $amountByMain THEN 1 ELSE 0 END"
          case "status" => s"CASE WHEN $statusByBranch THEN 'EDITED' ELSE $value END"
          case "qty"    => s"$value + CASE WHEN $qtyByBranch THEN 1 ELSE 0 END"
          case _        => value
        s"$column IS DISTINCT FROM ($should)"
      }
      .mkString(" OR ")
    val found = timed("the read-back") {
      sql(
        "large",
        "SELECT count(*), sum(id::HUGEINT)::VARCHAR, " +
          s"count(*) FILTER (id < $n AND amount = ${made("amount")} + 1), " +
          s"count(*) FILTER (id < $n AND status = 'EDITED'), " +
          s"count(*) FILTER (id < $n AND qty = ${made("qty")} + 1), " +
          "count(*) FILTER (note = 'main-ins'), count(*) FILTER (note = 'br-ins'), " +
          s"count(*) FILTER (id < $n AND amount = ${made("amount")} + 2), " +
          s"count(*) FILTER (id IS NULL OR (id < $n AND ($deleted OR $wrongCell)) " +
          s"OR (id >= $n AND NOT ($fromMain OR $fromBranch))) " +
          s"FROM (SELECT *, hash(id) AS h FROM $td.s.fact)"
      )
    }
    println(s"node bench: read back $found, expected $expected")
    found shouldBe expected
  }
