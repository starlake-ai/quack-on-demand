package ai.starlake.quack.ondemand.api

import ai.starlake.quack.{BranchingConfig, CatalogConfig}
import ai.starlake.quack.boot.{BranchWiring, RoutedExecutor}
import ai.starlake.quack.edge.{QueryResult, RouterFailure}
import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.edge.authz.NodePrincipal
import ai.starlake.quack.mcp.{McpBranchTools, McpPrincipal}
import ai.starlake.quack.model.{Branch, PoolKey, RoleDistribution, Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.branch.{BranchActor, BranchService, CloneResult, MergeFence}
import ai.starlake.quack.ondemand.catalog.{DuckLakeCatalogReader, TableVersion}
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{DbAdmin, InMemoryControlPlaneStore, RbacUser}
import ai.starlake.quack.route.StatementClassifier
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.{Json, JsonObject}
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.{BigIntVector, VarCharVector, VectorSchemaRoot}
import org.apache.arrow.vector.ipc.{ArrowStreamReader, ArrowStreamWriter}
import org.apache.arrow.vector.types.pojo.{ArrowType, Field, Schema}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import scala.collection.mutable
import scala.concurrent.duration.*

/** Who the branch change counts run as. Regression: the counter always ran as the system caller, so
  * anyone allowed to use a branch read per-table insert / delete / update counts of tables they
  * hold no grant on. Counts requested over REST or MCP now run as the requester (the routed
  * executor's handshake, then the node's verdict on that principal); a refused table keeps the zero
  * counts a failed count has always shown. The lifecycle (propose's summary) still counts as the
  * system caller.
  *
  * The service, the REST handler, the MCP tool, the counter (`BranchWiring.changeCounter`) and the
  * routed executor are the real ones over the in-memory store; the router leg stands in for the
  * node: it records the principal each count statement would run as and answers with the node's
  * verdict, here "alice may count region, not nation", any superuser and the system principal
  * everything.
  */
class BranchChangeCountsSpec extends AnyFlatSpec with Matchers:

  import IdentityFixtures.*

  private val AliceScope: SessionScope.Resolver = _ => SessionScope(false, Set("acme"))
  private val RootScope: SessionScope.Resolver  = _ => SessionScope.Superuser

  private class FakeReader(head: Long, changes: List[(Long, String)])
      extends DuckLakeCatalogReader(null):
    override def maxSnapshotId(): Option[Long]       = Some(head)
    override def tableVersions(): List[TableVersion] = List(
      TableVersion(2L, "main", "region", 1L, None),
      TableVersion(3L, "main", "nation", 1L, None)
    )
    override def snapshotChangesSince(fork: Long): List[(Long, String)] =
      changes.filter(_._1 > fork)
    override def columnsOfTableAt(id: Long, n: Long): List[CatalogColumnEntry] =
      List(CatalogColumnEntry(0, "x", "INTEGER", true, false))
    override def topLevelColumnsOfTableAt(id: Long, n: Long): List[CatalogColumnEntry] =
      columnsOfTableAt(id, n)
    override def droppedSchemasSince(fork: Long): Set[String] = Set.empty
    override def close(): Unit                                = ()

  /** `(change_type, n)` rows, the shape the counter folds. */
  private def summary(inserted: Long): org.apache.arrow.vector.ipc.ArrowReader =
    val allocator = new RootAllocator()
    val schema    = new Schema(
      java.util.List.of(
        Field.nullable("change_type", new ArrowType.Utf8()),
        Field.nullable("n", new ArrowType.Int(64, true))
      )
    )
    val root   = VectorSchemaRoot.create(schema, allocator)
    val out    = new ByteArrayOutputStream()
    val writer = new ArrowStreamWriter(root, null, out)
    writer.start()
    root.allocateNew()
    root.getVector("change_type").asInstanceOf[VarCharVector].setSafe(0, "insert".getBytes("UTF-8"))
    root.getVector("n").asInstanceOf[BigIntVector].setSafe(0, inserted)
    root.setRowCount(1)
    writer.writeBatch()
    writer.end()
    writer.close()
    root.close()
    new ArrowStreamReader(new ByteArrayInputStream(out.toByteArray), allocator)

  private final class Fixture(
      node: ai.starlake.quack.ondemand.branch.MergeNode =
        ai.starlake.quack.ondemand.branch.ZeroRowsMergeNode
  ):
    val store = new InMemoryControlPlaneStore()
    val admin = new DbAdmin:
      def createDatabase(name: String): Either[String, Unit] = Right(())
      def dropDatabase(name: String): Either[String, Unit]   = Right(())
    val sup =
      new PoolSupervisor(new StubQuackBackend(), new NodeLoadTracker, store, dbAdmin = admin)
    sup.createTenant(Tenant(id = "acme", displayName = "acme", authProvider = "db")).unsafeRunSync()
    val parent = sup
      .createTenantDb(
        "acme",
        "tpch",
        TenantDbKind.DuckLake,
        Map(
          "pgHost"     -> "127.0.0.1",
          "pgPort"     -> "5432",
          "pgUser"     -> "u",
          "pgPassword" -> "p",
          "dbName"     -> "ignored",
          "schemaName" -> "main"
        ),
        "/tmp/qod-branch-counts-spec/acme_tpch/"
      )
      .unsafeRunSync()
      .toOption
      .get
    sup.createPool(PoolKey("acme", parent.name, "bi"), RoleDistribution(0, 0, 1)).unsafeRunSync()
    // alice: a tenant user of acme who may connect to every pool (the branch's included).
    private val aliceRow =
      RbacUser(id = "u-alice", tenant = Some("acme"), username = "alice", kind = "admin")
    store.upsertUserIdentity(aliceRow)
    // root: a superuser (no tenant), whose own counts the REST propose replies with.
    store.upsertUserIdentity(
      RbacUser(id = "u-root", tenant = None, username = "root", kind = "admin")
    )
    sup.grantPoolPermission("acme", None, Some(aliceRow.id), None).unsafeRunSync()

    private val mainReader   = new FakeReader(10L, Nil)
    private val branchReader =
      new FakeReader(12L, List((11L, "inlined_insert:2"), (12L, "inlined_insert:3")))

    /** What each count statement reached the node as: (table, principal, executor caller). */
    val counted = mutable.ListBuffer.empty[(String, NodePrincipal, ExecCaller)]

    private val route: RoutedExecutor.Run = (caller, _, sql, eff, _) =>
      IO {
        val table     = if sql.contains("'region'") then "region" else "nation"
        val principal = NodePrincipal.of(eff)
        counted += ((table, principal, caller))
        val allowed =
          principal == NodePrincipal.System || principal.superuser ||
            (principal.name == "alice" && table == "region")
        if allowed then
          Right(QueryResult(summary(if table == "region" then 7L else 5L), () => (), "n1", 1L))
        else Left(RouterFailure.AccessDenied(s"QoD: access denied: no grant on $table"))
      }
    private val executor =
      RoutedExecutor(sup, StatementClassifier.default, route)(recordExecution = false)

    val service = new BranchService(
      cfg = BranchingConfig(),
      sup = sup,
      store = store,
      resolveReader = (_, db) => if db == parent.name then mainReader else branchReader,
      cloneCatalog = (_, _, _, _) => Right(CloneResult(10L, 2, 10L)),
      mergeExecutor = new ai.starlake.quack.ondemand.branch.MergeExecutor:
        def withNode[A](spec: ai.starlake.quack.model.NodeSpec)(
            body: ai.starlake.quack.ondemand.branch.MergeNode => IO[Either[String, A]]
        ): IO[Either[String, A]] = body(node)
      ,
      mergeFence = new MergeFence:
        def arm(
            m: Map[String, String],
            msg: String,
            base: Long,
            ids: Set[Long],
            hold: Option[Int]
        ) =
          Right(())
        def disarm(meta: Map[String, String], message: String) = ()
      ,
      counter = BranchWiring.changeCounter(
        executor,
        (b: Branch) => PoolKey("acme", b.tenantDbName, b.poolName),
        5.seconds
      ),
      purgeFiles = (_, _) => Right(())
    )
    // Database-scoped PATs of alice (review 5 R2): one on the branch's parent, one elsewhere.
    private def scopedPat(dbs: Set[String]) =
      ai.starlake.quack.ondemand.auth.PatPrincipal(
        user = RbacUser(id = "u-alice", tenant = Some("acme"), username = "alice", kind = "admin"),
        patId = "pat-scoped",
        scope = SessionScope(false, Set("acme")),
        isAdmin = true,
        restriction =
          ai.starlake.quack.ondemand.auth.TokenRestriction.Unrestricted.copy(databases = Some(dbs))
      )
    val ParentScopedTok = "qod_pat_parent_scoped"
    val OtherScopedTok  = "qod_pat_other_scoped"
    val callers         = restCaller(
      sessionOf,
      t =>
        patOf(t)
          .orElse(Option.when(t == ParentScopedTok)(scopedPat(Set(parent.name))))
          .orElse(Option.when(t == OtherScopedTok)(scopedPat(Set("acme_other"))))
    )
    val preview = new CatalogPreviewHandlers(
      sup,
      store,
      callers,
      executor,
      // Every snapshot and table resolves: the diff reaches its token check and its statements.
      (_, _) =>
        new DuckLakeCatalogReader(null):
          override def snapshotExists(id: Long): Boolean                     = true
          override def tableExistsAt(s: String, t: String, n: Long): Boolean = true
      ,
      CatalogConfig()
    )
    val handlers =
      new BranchHandlers(sup, service, preview, BranchHandlers.actorResolver(callers), callers)
    val feature = service
      .create("acme", parent.name, "feature", None, None, BranchActor("root", true), None)
      .unsafeRunSync()
      .fold(f => fail(s"branch create: $f"), identity)

    def overRest(token: String, scope: SessionScope.Resolver): Map[String, Long] =
      restTables(token, scope).map(t => t.table -> t.inserted).toMap

    def restTables(token: String, scope: SessionScope.Resolver): List[BranchTableChange] =
      handlers
        .changes("acme", parent.name, "feature", Some(true), Some(token))(scope)
        .unsafeRunSync()
        .fold(e => fail(s"changes: $e"), identity)
        .tables

    def overMcp(): Map[String, Long] =
      mcpTables()
        .map(t =>
          t.hcursor
            .get[String]("table")
            .toOption
            .get -> t.hcursor.get[Long]("inserted").toOption.get
        )
        .toMap

    def mcpTables(): List[Json] =
      val pat  = patOf(PatTok).get
      val tool = new McpBranchTools(handlers, AliceScope).tools.find(_.name == "branch_changes").get
      val out  = tool
        .run(
          new McpPrincipal.Pat(pat, PatTok),
          JsonObject(
            "database" -> Json.fromString(parent.name),
            "branch"   -> Json.fromString("feature")
          )
        )
        .unsafeRunSync()
        .fold(e => fail(s"branch_changes: $e"), identity)
      out.hcursor.downField("tables").values.toList.flatten

  "branch change counts over REST" should "run as the requester: no count of a table the node refuses them" in {
    val f = new Fixture
    f.overRest(SessionTok, AliceScope) shouldBe Map("region" -> 7L, "nation" -> 0L)
    f.counted.map(_._1).toSet shouldBe Set("region", "nation")
    f.counted.foreach { case (_, principal, caller) =>
      caller.system shouldBe false
      caller.identity shouldBe "alice"
      principal.name shouldBe "alice"
      principal should not be NodePrincipal.System
    }
  }

  "a count the node refuses" should "be reported unavailable, not as a real zero" in {
    val f    = new Fixture
    val rest = f.restTables(SessionTok, AliceScope).map(t => t.table -> t.countsUnavailable).toMap
    rest shouldBe Map("region" -> false, "nation" -> true)
    f.mcpTables()
      .map(t =>
        t.hcursor.get[String]("table").toOption.get ->
          t.hcursor.get[Boolean]("countsUnavailable").toOption.get
      )
      .toMap shouldBe Map("region" -> false, "nation" -> true)
  }

  "branch change counts over MCP" should "run as the PAT's owner, with the PAT, never as system" in {
    val f = new Fixture
    f.overMcp() shouldBe Map("region" -> 7L, "nation" -> 0L)
    f.counted should not be empty
    f.counted.foreach { case (_, principal, caller) =>
      caller.system shouldBe false
      caller.patId shouldBe Some(PatId)
      principal.name shouldBe "alice"
    }
  }

  "a token scoped to the branch's parent database" should "count and diff its branches (review 5 R2)" in {
    val f = new Fixture
    f.overRest(f.ParentScopedTok, AliceScope) shouldBe Map("region" -> 7L, "nation" -> 0L)
    // The branch diff checks the branch tenant-db (`<parent>__br_<id8>`) as its parent: never a
    // token_scope refusal for a token scoped to the parent.
    val diff = f.handlers
      .diff(
        "acme",
        f.parent.name,
        "feature",
        "main",
        "region",
        None,
        None,
        None,
        Some(f.ParentScopedTok)
      )(
        AliceScope
      )
      .attempt
      .unsafeRunSync()
    diff match
      case Right(Left((code, e))) =>
        withClue(e)(e.error should not be "token_scope")
      case _ => succeed
  }

  "a token scoped to another database" should "neither create, list, count nor propose a branch (review 5 R2)" in {
    val f                                                                  = new Fixture
    val tok                                                                = Some(f.OtherScopedTok)
    def refused[A](out: Either[(sttp.model.StatusCode, ErrorResponse), A]) =
      out match
        case Left((code, e)) =>
          code shouldBe sttp.model.StatusCode.Forbidden
          e.error shouldBe "token_scope"
        case Right(v) => fail(s"a token scoped elsewhere got $v")
    refused(
      f.handlers
        .create(BranchCreateRequest("acme", f.parent.name, "other"), tok)(AliceScope)
        .unsafeRunSync()
    )
    refused(f.handlers.list("acme", f.parent.name, None, tok)(AliceScope).unsafeRunSync())
    refused(
      f.handlers
        .changes("acme", f.parent.name, "feature", Some(true), tok)(AliceScope)
        .unsafeRunSync()
    )
    refused(
      f.handlers
        .propose(BranchProposeRequest("acme", f.parent.name, "feature"), tok)(AliceScope)
        .unsafeRunSync()
    )
    // Nothing was counted for it.
    f.counted shouldBe empty
  }

  "TokenScope" should "judge a branch by its parent, and let a branch-only token write on a branch pool (review 5 R2, R5)" in {
    val f          = new Fixture
    val b          = f.feature
    val branchOnly = ExecCaller(
      "c",
      "alice",
      ai.starlake.quack.ondemand.auth.TokenRestriction.Unrestricted
        .copy(databases = Some(Set(f.parent.name)), branchOnly = true),
      superuserAdmissible = false
    )
    TokenScope.databaseOf(f.sup, "acme", b.tenantDbName) shouldBe f.parent.name
    val onBranch = PoolKey("acme", b.tenantDbName, b.poolName)
    TokenScope.refusal(
      f.sup,
      "acme",
      branchOnly,
      b.tenantDbName,
      Some(onBranch),
      writes = true
    ) shouldBe None
    TokenScope
      .refusal(
        f.sup,
        "acme",
        branchOnly,
        f.parent.name,
        Some(PoolKey("acme", f.parent.name, "bi")),
        writes = true
      )
      .map(_._2.error) shouldBe Some("write_requires_branch")
  }

  "propose" should "store the system's counts and reply with the superuser proposer's own" in {
    // Review M3: through the REST handler as a superuser session, so the reply is counted as that
    // superuser (their own row through the handshake), the stored summary as the system.
    val f     = new Fixture
    val reply = f.handlers
      .propose(BranchProposeRequest("acme", f.parent.name, "feature"), Some(RootSessionTok))(
        RootScope
      )
      .unsafeRunSync()
      .fold(e => fail(s"propose: $e"), identity)
    reply.changes.tables.map(t => t.table -> t.inserted).toMap shouldBe
      Map("region" -> 7L, "nation" -> 5L)
    nationIn(tablesOf(reply.merge.summary)) shouldBe (5L, false)
    val byCaller = f.counted.toList.map(c => (c._1, c._3.system, c._2.name))
    byCaller.filter(_._2).map(_._1).toSet shouldBe Set("region", "nation")
    byCaller.filterNot(_._2).map(c => (c._1, c._3)).toSet shouldBe
      Set(("region", "root"), ("nation", "root"))
  }

  /** nation's counts as a summary or change-set JSON carries them: (inserted, countsUnavailable).
    */
  private def nationIn(tables: List[Json]): (Long, Boolean) =
    tables
      .find(_.hcursor.get[String]("table").toOption.contains("nation"))
      .map(t =>
        (
          t.hcursor.get[Long]("inserted").getOrElse(-1L),
          t.hcursor.get[Boolean]("countsUnavailable").getOrElse(false)
        )
      )
      .getOrElse(fail("no nation entry"))

  private def tablesOf(j: Json): List[Json] = j.hcursor.downField("tables").values.toList.flatten

  "a proposal" should "never hand the proposer the system's counts of a table they may not count, over REST" in {
    val f     = new Fixture
    val reply = f.handlers
      .propose(BranchProposeRequest("acme", f.parent.name, "feature"), Some(SessionTok))(AliceScope)
      .unsafeRunSync()
      .fold(e => fail(s"propose: $e"), identity)
    val changes = reply.changes.tables.map(t => t.table -> (t.inserted, t.countsUnavailable)).toMap
    changes shouldBe Map("region" -> (7L, false), "nation" -> (0L, true))
    nationIn(tablesOf(reply.merge.summary)) shouldBe (0L, true)
    // Every later read of the proposal, by the same tenant admin.
    val detail = f.handlers
      .get("acme", f.parent.name, "feature", Some(SessionTok))(AliceScope)
      .unsafeRunSync()
      .fold(e => fail(s"get: $e"), identity)
    detail.merges.map(m => nationIn(tablesOf(m.summary))) shouldBe List((0L, true))
    // The stored summary keeps the system's counts, which a superuser still reads.
    val asRoot = f.handlers
      .get("acme", f.parent.name, "feature", Some(RootSessionTok))(RootScope)
      .unsafeRunSync()
      .fold(e => fail(s"get: $e"), identity)
    asRoot.merges.map(m => nationIn(tablesOf(m.summary))) shouldBe List((5L, false))
  }

  it should "never hand them to the proposer over MCP either" in {
    val f     = new Fixture
    val pat   = patOf(PatTok).get
    val tool  = new McpBranchTools(f.handlers, AliceScope).tools.find(_.name == "propose_merge").get
    val reply = tool
      .run(
        new McpPrincipal.Pat(pat, PatTok),
        JsonObject(
          "database" -> Json.fromString(f.parent.name),
          "branch"   -> Json.fromString("feature")
        )
      )
      .unsafeRunSync()
      .fold(e => fail(s"propose_merge: $e"), identity)
    nationIn(tablesOf(reply.hcursor.downField("changes").focus.get)) shouldBe (0L, true)
    nationIn(tablesOf(reply.hcursor.downField("merge").downField("summary").focus.get)) shouldBe
      (0L, true)
    val detail = f.handlers
      .get("acme", f.parent.name, "feature", Some(PatTok))(AliceScope)
      .unsafeRunSync()
      .fold(e => fail(s"get: $e"), identity)
    detail.merges.map(m => nationIn(tablesOf(m.summary))) shouldBe List((0L, true))
  }

  /** A node whose Decide finds 2 conflicting cells in column `x`, on every table. */
  private object ConflictingNode extends ai.starlake.quack.ondemand.branch.MergeNode:
    def exec(batch: String) = IO.pure(Right(()))
    def rows(sql: String)   = IO.pure(
      Right(
        if sql.contains("_need") then List(List(Json.fromLong(0L), Json.fromLong(0L)))
        else if sql.contains("AS kind") then List(List(Json.fromString("cell"), Json.fromLong(70L)))
        else List(List(1L, 0L, 0L, 2L, 0L, 0L, 0L, 1L, 1L, 5L, 2L).map(Json.fromLong))
      )
    )

  "row conflicts" should "show their numbers only for tables the requester may count" in {
    val f     = new Fixture(ConflictingNode)
    val reply = f.handlers
      .propose(BranchProposeRequest("acme", f.parent.name, "feature"), Some(SessionTok))(AliceScope)
      .unsafeRunSync()
      .fold(e => fail(s"propose: $e"), identity)
    val byTable = reply.changes.tables.map(t => t.table -> t.rowConflicts).toMap
    val region  = byTable("region").get
    (region.cellConflicts, region.hasConflicts, region.countsUnavailable) shouldBe (2L, true, false)
    region.samples shouldBe Map("cell" -> List(70L))
    region.mainRowsByKey shouldBe 5L
    // alice holds no grant on nation: she learns it has conflicts, not how many.
    val nation = byTable("nation").get
    (nation.cellConflicts, nation.hasConflicts, nation.countsUnavailable) shouldBe (0L, true, true)
    nation.samples shouldBe Map.empty
    nation.mainRowsByKey shouldBe 0L
    // The merge row a tenant admin reads carries no numbers either; a superuser's does.
    reply.merge.rowConflicts
      .map(c => (c.table, c.cellConflicts, c.countsUnavailable))
      .toSet shouldBe
      Set(("region", 0L, true), ("nation", 0L, true))
    val asRoot = f.handlers
      .get("acme", f.parent.name, "feature", Some(RootSessionTok))(RootScope)
      .unsafeRunSync()
      .fold(e => fail(s"get: $e"), identity)
    asRoot.merges.flatMap(_.rowConflicts).map(c => (c.table, c.cellConflicts)).toSet shouldBe
      Set(("region", 2L), ("nation", 2L))
  }

  it should "reach `changes` from the open proposal, under the same rule" in {
    val f = new Fixture(ConflictingNode)
    f.handlers
      .propose(BranchProposeRequest("acme", f.parent.name, "feature"), Some(SessionTok))(AliceScope)
      .unsafeRunSync()
      .isRight shouldBe true
    val tables = f.restTables(SessionTok, AliceScope).map(t => t.table -> t).toMap
    tables("region").rowConflicts.map(_.cellConflicts) shouldBe Some(2L)
    tables("nation").rowConflicts.map(_.countsUnavailable) shouldBe Some(true)
    // No counts asked for: nothing to judge by, so no numbers.
    val noCounts = f.handlers
      .changes("acme", f.parent.name, "feature", Some(false), Some(SessionTok))(AliceScope)
      .unsafeRunSync()
      .fold(e => fail(s"changes: $e"), identity)
    noCounts.tables.flatMap(_.rowConflicts).map(_.cellConflicts) shouldBe List(0L, 0L)
    // A superuser sees every number, counts asked for or not.
    val asRoot = f.handlers
      .changes("acme", f.parent.name, "feature", Some(false), Some(RootSessionTok))(RootScope)
      .unsafeRunSync()
      .fold(e => fail(s"changes: $e"), identity)
    asRoot.tables.flatMap(_.rowConflicts).map(_.cellConflicts) shouldBe List(2L, 2L)
  }

  it should "say so when a proposal's counts cannot be read, rather than show none" in {
    val f     = new Fixture(ConflictingNode)
    val reply = f.handlers
      .propose(BranchProposeRequest("acme", f.parent.name, "feature"), Some(SessionTok))(AliceScope)
      .unsafeRunSync()
      .fold(e => fail(s"propose: $e"), identity)
    val stored = f.store.getBranchMerge(reply.merge.id).get
    f.store.createBranchMerge(stored.copy(rowConflictsJson = "not json"))
    val entry = f.handlers
      .get("acme", f.parent.name, "feature", Some(RootSessionTok))(RootScope)
      .unsafeRunSync()
      .fold(e => fail(s"get: $e"), identity)
      .merges
      .find(_.id == reply.merge.id)
      .get
    entry.rowConflicts shouldBe Nil
    entry.error shouldBe Some("the proposal's counts cannot be read; propose again")
  }

  "propose" should "refuse a bad policy and a bad key before doing anything" in {
    val f                               = new Fixture
    def code(req: BranchProposeRequest) = f.handlers
      .propose(req, Some(RootSessionTok))(RootScope)
      .unsafeRunSync()
      .left
      .map(_._2.error)
    code(BranchProposeRequest("acme", f.parent.name, "feature", onConflict = Some("ours"))) shouldBe
      Left("invalid_on_conflict")
    code(
      BranchProposeRequest("acme", f.parent.name, "feature", keys = Map("nodot" -> List("k")))
    ) shouldBe
      Left("invalid_key")
    f.counted shouldBe empty
  }

  it should "take the policy and keys over MCP" in {
    val f    = new Fixture
    val pat  = patOf(PatTok).get
    val tool = new McpBranchTools(f.handlers, AliceScope).tools.find(_.name == "propose_merge").get
    val out  = tool
      .run(
        new McpPrincipal.Pat(pat, PatTok),
        JsonObject(
          "database"   -> Json.fromString(f.parent.name),
          "branch"     -> Json.fromString("feature"),
          "onConflict" -> Json.fromString("main"),
          "keys"       -> Json.obj("main.region" -> Json.arr(Json.fromString("x")))
        )
      )
      .unsafeRunSync()
      .fold(e => fail(s"propose_merge: $e"), identity)
    out.hcursor.downField("merge").get[String]("onConflict") shouldBe Right("main")
    out.hcursor.downField("merge").downField("keys").get[List[String]]("main.region") shouldBe
      Right(List("x"))
  }

  it should "refuse a malformed keys argument over MCP" in {
    val f    = new Fixture
    val pat  = patOf(PatTok).get
    val tool = new McpBranchTools(f.handlers, AliceScope).tools.find(_.name == "propose_merge").get
    tool
      .run(
        new McpPrincipal.Pat(pat, PatTok),
        JsonObject(
          "database" -> Json.fromString(f.parent.name),
          "branch"   -> Json.fromString("feature"),
          "keys"     -> Json.fromString("main.region=x")
        )
      )
      .unsafeRunSync()
      .isLeft shouldBe true
  }
