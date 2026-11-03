package ai.starlake.quack.ondemand.api

import ai.starlake.quack.{BranchingConfig, CatalogConfig}
import ai.starlake.quack.boot.{BranchWiring, RoutedExecutor}
import ai.starlake.quack.edge.{QueryResult, RouterFailure}
import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.edge.auth.AuthenticatedProfile
import ai.starlake.quack.edge.authz.NodePrincipal
import ai.starlake.quack.mcp.{McpBranchTools, McpPrincipal}
import ai.starlake.quack.model.{
  Branch,
  BranchMerge,
  BranchMergeStatus,
  PoolKey,
  RoleDistribution,
  Tenant,
  TenantAcl,
  TenantDb,
  TenantDbKind
}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.{PatPrincipal, SessionScope, TokenRestriction}
import ai.starlake.quack.ondemand.branch.{
  BranchActor,
  BranchMergeSql,
  BranchService,
  CloneResult,
  MergeFence
}
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
import java.time.Instant
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
  * verdict, here "alice and bob may count region, not nation", carol nothing, as a policy holder
  * may not, any superuser and the system principal everything.
  *
  * It holds the rules of who gets in too, with plain principals on real credentials: bob and carol
  * may connect and hold a grant on region alone, dave holds no pool grant.
  */
class BranchChangeCountsSpec extends AnyFlatSpec with Matchers:

  import IdentityFixtures.*

  private val AliceScope: SessionScope.Resolver = _ => SessionScope(false, Set("acme"))
  private val RootScope: SessionScope.Resolver  = _ => SessionScope.Superuser

  /** The snapshot every branch of this spec is cut at: what the fixture's clone answers. */
  private val Fork = 10L

  private class FakeReader(var head: Long, var changes: List[(Long, String)])
      extends DuckLakeCatalogReader(null):
    /** The commit messages found on this catalog, each with its snapshot: what a merge looks for.
      */
    val committed = mutable.Map.empty[String, Long]

    /** The tables, each with the snapshot it began at and the one it ended at. */
    var versions = List(
      TableVersion(2L, "main", "region", 1L, None),
      TableVersion(3L, "main", "nation", 1L, None)
    )
    override def snapshotByCommitMessage(msg: String): Option[Long] = committed.get(msg)
    override def maxSnapshotId(): Option[Long]                      = Some(head)
    override def tableVersions(): List[TableVersion]                = versions
    // The fork's own row too, which both real catalogs hold: `changes` lists the snapshots since.
    override def snapshotChangesSince(fork: Long): List[(Long, String)] =
      (if fork < Fork then List((Fork, "")) else Nil) ++ changes.filter(_._1 > fork)
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

  // Plain principals: no admin grant anywhere, unless the name says so.
  private val BobSession        = "bob-session"
  private val BobPat            = "qod_pat_bob"
  private val CarolSession      = "carol-session"
  private val DaveSession       = "dave-session"
  private val DavePat           = "qod_pat_dave"
  private val AdminElsewhere    = "erin-session" // a plain member of acme, an admin of globex
  private val AdminElsewherePat = "qod_pat_erin"
  private val DroppedAdminPat = "qod_pat_alice_dropped" // alice's --drop-admin token
  private val RootDroppedPat  = "qod_pat_root_dropped"  // a superuser's --drop-admin token
  private val SameNameElsewhere = "bob-of-globex" // another user, in another tenant, also named bob
  private val NoTenantSession  = "system-realm-session" // no tenant, and not a superuser
  private val RootNamedSession = "root-of-acme"         // a plain session naming a superuser's row

  private final class Fixture(
      node: ai.starlake.quack.ondemand.branch.MergeNode =
        ai.starlake.quack.ondemand.branch.ZeroRowsMergeNode,
      tenantAcl: TenantAcl = TenantAcl()
  ):
    val store = new InMemoryControlPlaneStore()
    val admin = new DbAdmin:
      def createDatabase(name: String): Either[String, Unit] = Right(())
      def dropDatabase(name: String): Either[String, Unit]   = Right(())
    val sup =
      new PoolSupervisor(new StubQuackBackend(), new NodeLoadTracker, store, dbAdmin = admin)
    sup
      .createTenant(Tenant(id = "acme", displayName = "acme", authProvider = "db", acl = tenantAcl))
      .unsafeRunSync()
    sup
      .createTenant(Tenant(id = "globex", displayName = "globex", authProvider = "db"))
      .unsafeRunSync()
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

    val alias: String =
      TenantDb.catalogAlias(sup.effectiveMetastoreFor("acme", parent.name), parent.name)

    // What a plain user holds: a role with one grant, on region.
    private val regionRole = sup
      .createRole("acme", "region_rw")
      .unsafeRunSync()
      .fold(e => fail(s"role: $e"), identity)
    sup.grantRolePermission(regionRole.id, alias, "main", "region", "RW").unsafeRunSync()

    /** A plain member of acme: on the pools if `connects`, with the grant on region if `granted`.
      */
    private def member(name: String, connects: Boolean = true, granted: Boolean = true): RbacUser =
      val row = RbacUser(id = s"u-$name", tenant = Some("acme"), username = name, kind = "user")
      store.upsertUserIdentity(row)
      if connects then sup.grantPoolPermission("acme", None, Some(row.id), None).unsafeRunSync()
      if granted then sup.addUserRole(row.id, regionRole.id).unsafeRunSync()
      row

    private val bobRow   = member("bob")
    private val carolRow = member("carol")
    private val daveRow  = member("dave", connects = false)
    private val erinRow  = member("erin")

    private def session(name: String, tenant: Option[String], scope: SessionScope) =
      val role = if scope.superuser || scope.manageableTenants.nonEmpty then "admin" else "user"
      SessionTokenStore.Session(
        AuthenticatedProfile(name, role, Set.empty, Map.empty, "db", tenant),
        scope,
        Instant.now()
      )

    /** A plain member's PAT: `SessionScope.NoAccess`, as `PatAuthenticator` scopes one. */
    private def plainPat(
        row: RbacUser,
        id: String,
        r: TokenRestriction = TokenRestriction.Unrestricted
    ) =
      PatPrincipal(row, id, SessionScope.NoAccess, isAdmin = false, r)

    private val sessions = Map(
      BobSession        -> session("bob", Some("acme"), SessionScope.NoAccess),
      CarolSession      -> session("carol", Some("acme"), SessionScope.NoAccess),
      DaveSession       -> session("dave", Some("acme"), SessionScope.NoAccess),
      AdminElsewhere    -> session("erin", Some("acme"), SessionScope(false, Set("globex"))),
      SameNameElsewhere -> session("bob", Some("globex"), SessionScope.NoAccess),
      NoTenantSession   -> session("nobody", None, SessionScope.NoAccess),
      RootNamedSession  -> session("root", Some("acme"), SessionScope.NoAccess)
    )

    /** Tokens a case adds: name -> what it resolves to. */
    val extraPats = mutable.Map.empty[String, PatPrincipal]

    /** bob's token under `r`, by a name of its own. */
    def bobToken(r: TokenRestriction): String =
      val name = s"qod_pat_bob_${extraPats.size}"
      extraPats.put(name, plainPat(bobRow, name, r)): Unit
      name

    private val pats = Map(
      BobPat            -> plainPat(bobRow, "pat-bob"),
      DavePat           -> plainPat(daveRow, "pat-dave"),
      AdminElsewherePat ->
        PatPrincipal(
          erinRow,
          "pat-erin",
          SessionScope(false, Set("globex")),
          isAdmin = true,
          TokenRestriction.Unrestricted
        ),
      // PatAuthenticator: a dropAdmin token collapses to NoAccess, whatever its owner may do.
      DroppedAdminPat ->
        PatPrincipal(
          aliceRow,
          "pat-dropped",
          SessionScope.NoAccess,
          isAdmin = false,
          TokenRestriction.Unrestricted.copy(dropAdmin = true)
        ),
      RootDroppedPat ->
        PatPrincipal(
          RbacUser(id = "u-root", tenant = None, username = "root", kind = "admin"),
          "pat-root-dropped",
          SessionScope.NoAccess,
          isAdmin = false,
          TokenRestriction.Unrestricted.copy(dropAdmin = true)
        )
    )

    private val mainReader   = new FakeReader(10L, Nil)
    private val branchReader =
      new FakeReader(12L, List((11L, "inlined_insert:2"), (12L, "inlined_insert:3")))

    /** What each count statement reached the node as: (table, principal, executor caller). */
    val counted = mutable.ListBuffer.empty[(String, NodePrincipal, ExecCaller)]

    /** Tables whose count fails for everyone, as on a node that does not answer. */
    val downTables = mutable.Set.empty[String]

    /** Main changed region's rows too, after the branch forked: a table both sides changed. */
    def mainTouchesRegion(): Unit =
      mainReader.head = 11L
      mainReader.changes = List((11L, "inlined_insert:2"))

    /** The branch drops nation after the proposal: nothing freezes a proposed branch. */
    def branchDropsNation(): Unit =
      branchReader.versions =
        branchReader.versions.map(v => if v.name == "nation" then v.copy(end = Some(13L)) else v)
      branchReader.head = 13L
      branchReader.changes = branchReader.changes :+ ((13L, "dropped_table:3"))

    /** The commit of a proposal is on main, as if its batch had landed: a merge then settles it. */
    def land(m: BranchMerge): Unit =
      mainReader.committed.put(BranchMergeSql.commitMessage("feature", m.id, m.proposer), 11L): Unit

    private val route: RoutedExecutor.Run = (caller, _, sql, eff, _) =>
      IO {
        val table     = if sql.contains("'region'") then "region" else "nation"
        val principal = NodePrincipal.of(eff)
        counted += ((table, principal, caller))
        val allowed =
          principal == NodePrincipal.System || principal.superuser ||
            (Set("alice", "bob", "erin")(principal.name) && table == "region")
        if downTables(table) then Left(RouterFailure.Unavailable(s"no node answers for $table"))
        else if allowed then
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
      cloneCatalog = (_, _, _, _) => Right(CloneResult(Fork, 2, 10L)),
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
      t => sessionOf(t).orElse(sessions.get(t)),
      t =>
        patOf(t)
          .orElse(Option.when(t == ParentScopedTok)(scopedPat(Set(parent.name))))
          .orElse(Option.when(t == OtherScopedTok)(scopedPat(Set("acme_other"))))
          .orElse(pats.get(t))
          .orElse(extraPats.get(t))
    )

    /** What a credential's scope is, as the manager resolves it: from the credential alone. */
    val scopes: SessionScope.Resolver = key =>
      callers.resolve(key).toOption match
        case Some(RestCaller.Principal.Session(s)) => s.scope
        case Some(RestCaller.Principal.Pat(p))     => p.scope
        case None                                  => SessionScope.NoAccess

    val preview = new CatalogPreviewHandlers(
      sup,
      store,
      callers,
      executor,
      // Every snapshot resolves, and the tables the branch has: the diff reaches its token check
      // and its statements.
      (_, _) =>
        new DuckLakeCatalogReader(null):
          override def snapshotExists(id: Long): Boolean                     = true
          override def tableExistsAt(s: String, t: String, n: Long): Boolean =
            s == "main" && Set("region", "nation")(t)
          override def schemaDiff(s: String, t: String, from: Long, to: Long) =
            ai.starlake.quack.ondemand.catalog.SchemaDiffResult(Nil, Nil, Nil, Nil)
      ,
      CatalogConfig()
    )
    val handlers = new BranchHandlers(sup, service, preview, callers)
    val feature  = service
      .create("acme", parent.name, "feature", None, None, BranchActor.admin("root"), None)
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

  it should "flag in the stored summary a table the system could not count, not store a real zero" in {
    // On a real node: a proposal right after a refused merge finds the thawed branch's node
    // unroutable until its next health probe, so the system's count fails.
    val f = new Fixture
    f.downTables += "nation"
    val reply = f.handlers
      .propose(BranchProposeRequest("acme", f.parent.name, "feature"), Some(RootSessionTok))(
        RootScope
      )
      .unsafeRunSync()
      .fold(e => fail(s"propose: $e"), identity)
    nationIn(tablesOf(reply.merge.summary)) shouldBe (0L, true)
    tablesOf(reply.merge.summary)
      .find(_.hcursor.get[String]("table").toOption.contains("region"))
      .map(t =>
        (t.hcursor.get[Long]("inserted"), t.hcursor.get[Boolean]("countsUnavailable"))
      ) shouldBe
      Some((Right(7L), Right(false)))
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
    def exec(statements: List[String]) = IO.pure(Right(()))
    def rows(sql: String)              = IO.pure(
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
          "database"    -> Json.fromString(f.parent.name),
          "branch"      -> Json.fromString("feature"),
          "on_conflict" -> Json.fromString("main"),
          "keys"        -> Json.obj("main.region" -> Json.arr(Json.fromString("x")))
        )
      )
      .unsafeRunSync()
      .fold(e => fail(s"propose_merge: $e"), identity)
    // The argument is snake_case like its siblings'; the reply is the REST entry, which says onConflict.
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

  /** A node whose Decide finds a different number for every count and a sample of every kind, so
    * that a field filled from the wrong count, or left out, shows.
    */
  private object DistinctNode extends ai.starlake.quack.ondemand.branch.MergeNode:
    def exec(statements: List[String]) = IO.pure(Right(()))
    def rows(sql: String)              = IO.pure(
      Right(
        if sql.contains("_need") then List(List(Json.fromLong(0L), Json.fromLong(0L)))
        else if sql.contains("AS kind") then
          List("cell" -> 70L, "update_delete" -> 71L, "delete_update" -> 72L, "key" -> 73L)
            .map((kind, id) => List(Json.fromString(kind), Json.fromLong(id)))
        else List(List(1L, 2L, 3L, 4L, 5L, 6L, 7L, 1L, 1L, 8L, 9L).map(Json.fromLong))
      )
    )

  "row numbers" should "each reach their own field: all of a table the requester may count, none of one they may not" in {
    val f     = new Fixture(DistinctNode)
    val reply = f.handlers
      .propose(BranchProposeRequest("acme", f.parent.name, "feature"), Some(SessionTok))(AliceScope)
      .unsafeRunSync()
      .fold(e => fail(s"propose: $e"), identity)
    val byTable        = reply.changes.tables.map(t => t.table -> t.rowConflicts.get).toMap
    val expectedRegion = BranchRowConflicts(
      "main",
      "region",
      hasConflicts = true,
      toUpdate = 1L,
      toDelete = 2L,
      toInsert = 3L,
      cellConflicts = 4L,
      updateDeleteConflicts = 5L,
      deleteUpdateConflicts = 6L,
      keyMatches = 7L,
      mainRowsByKey = 8L,
      columnConflicts = Map("x" -> 9L),
      samples = Map(
        "cell"          -> List(70L),
        "update_delete" -> List(71L),
        "delete_update" -> List(72L),
        "key"           -> List(73L)
      )
    )
    val expectedNation =
      BranchRowConflicts("main", "nation", hasConflicts = true, countsUnavailable = true)
    (byTable("region"), byTable("nation")) shouldBe ((expectedRegion, expectedNation))
  }

  it should "say a table has no conflicts when it has none" in {
    val f     = new Fixture
    val reply = f.handlers
      .propose(BranchProposeRequest("acme", f.parent.name, "feature"), Some(SessionTok))(AliceScope)
      .unsafeRunSync()
      .fold(e => fail(s"propose: $e"), identity)
    reply.changes.tables
      .flatMap(_.rowConflicts)
      .map(c => (c.table, c.hasConflicts, c.countsUnavailable))
      .toSet shouldBe Set(("region", false, false), ("nation", false, true))
  }

  "propose_merge" should "advertise the policy and keys it reads" in {
    val f    = new Fixture
    val tool = new McpBranchTools(f.handlers, AliceScope).tools.find(_.name == "propose_merge").get
    val advertised = tool.inputSchema.hcursor.downField("properties").keys.toList.flatten
    advertised should contain allOf ("on_conflict", "keys")
  }

  /** `proposerTok` proposes `feature`, its commit is on main already, `approverTok` merges it: the
    * merge reply, with no node run.
    */
  private def mergeReply(
      proposerTok: String,
      proposerScope: SessionScope.Resolver,
      approverTok: String,
      approverScope: SessionScope.Resolver
  ): BranchMergeResponse =
    val f        = new Fixture(ConflictingNode)
    val proposed = f.handlers
      .propose(BranchProposeRequest("acme", f.parent.name, "feature"), Some(proposerTok))(
        proposerScope
      )
      .unsafeRunSync()
      .fold(e => fail(s"propose: $e"), identity)
    f.land(f.store.getBranchMerge(proposed.merge.id).get)
    f.handlers
      .merge(
        BranchMergeRequest("acme", f.parent.name, "feature", proposed.merge.id, holdWriters = true),
        Some(approverTok)
      )(approverScope)
      .unsafeRunSync()
      .fold(e => fail(s"merge: $e"), identity)

  "a merge's reply" should "show row numbers the way a proposal's does: all to a superuser, a flag to a tenant admin" in {
    def numbers(r: BranchMergeResponse) =
      (
        r.merge.rowConflicts.map(c => (c.table, c.cellConflicts, c.countsUnavailable)).toSet,
        r.changes.tables
          .flatMap(_.rowConflicts)
          .map(c => (c.table, c.cellConflicts, c.countsUnavailable))
          .toSet
      )
    val flagged = Set(("region", 0L, true), ("nation", 0L, true))
    val shown   = Set(("region", 2L, false), ("nation", 2L, false))
    val byAlice = mergeReply(RootSessionTok, RootScope, SessionTok, AliceScope)
    byAlice.merge.status shouldBe "merged"
    numbers(byAlice) shouldBe (flagged, flagged)
    nationIn(tablesOf(byAlice.merge.summary)) shouldBe (0L, true)
    val byRoot = mergeReply(SessionTok, AliceScope, RootSessionTok, RootScope)
    byRoot.merge.status shouldBe "merged"
    numbers(byRoot) shouldBe (shown, shown)
    nationIn(tablesOf(byRoot.merge.summary)) shouldBe (5L, false)
  }

  it should "carry the hold the approver asked for" in {
    mergeReply(RootSessionTok, RootScope, SessionTok, AliceScope).merge.holdWriters shouldBe true
  }

  "a merge row" should "show what it records, and an earlier timeout beside unreadable counts" in {
    val f     = new Fixture(ConflictingNode)
    val reply = f.handlers
      .propose(BranchProposeRequest("acme", f.parent.name, "feature"), Some(RootSessionTok))(
        RootScope
      )
      .unsafeRunSync()
      .fold(e => fail(s"propose: $e"), identity)
    val timedOut = "timeout: an Apply was sent and has not answered"
    f.store.createBranchMerge(
      f.store
        .getBranchMerge(reply.merge.id)
        .get
        .copy(
          onConflict = Some("branch"),
          mergeKeysJson = """{"main.region":["x"]}""",
          holdWriters = true,
          attempts = 3,
          error = Some(timedOut),
          rowConflictsJson = "not json"
        )
    )
    val entry = f.handlers
      .get("acme", f.parent.name, "feature", Some(RootSessionTok))(RootScope)
      .unsafeRunSync()
      .fold(e => fail(s"get: $e"), identity)
      .merges
      .find(_.id == reply.merge.id)
      .get
    // The counts' own error must not hide the recorded one: it says a send has no answer.
    (entry.onConflict, entry.keys, entry.holdWriters, entry.attempts, entry.error) shouldBe
      (Some("branch"), Map("main.region" -> List("x")), true, 3, Some(timedOut))
  }

  "a superuser" should "see every row number, even where their own count of a table failed" in {
    val f = new Fixture(ConflictingNode)
    f.downTables += "nation"
    val reply = f.handlers
      .propose(BranchProposeRequest("acme", f.parent.name, "feature"), Some(RootSessionTok))(
        RootScope
      )
      .unsafeRunSync()
      .fold(e => fail(s"propose: $e"), identity)
    reply.changes.tables.map(t => t.table -> t.countsUnavailable).toMap shouldBe
      Map("region" -> false, "nation" -> true)
    reply.changes.tables
      .flatMap(_.rowConflicts)
      .map(c => (c.table, c.cellConflicts, c.countsUnavailable))
      .toSet shouldBe Set(("region", 2L, false), ("nation", 2L, false))
  }

  "a change set" should "flag a table main changed too, and only that one" in {
    val f = new Fixture
    f.mainTouchesRegion()
    f.restTables(SessionTok, AliceScope).map(t => t.table -> t.threeWay).toMap shouldBe
      Map("region" -> true, "nation" -> false)
  }

  it should "take its row numbers from an open proposal only" in {
    val f     = new Fixture(ConflictingNode)
    val reply = f.handlers
      .propose(BranchProposeRequest("acme", f.parent.name, "feature"), Some(RootSessionTok))(
        RootScope
      )
      .unsafeRunSync()
      .fold(e => fail(s"propose: $e"), identity)
    f.restTables(RootSessionTok, RootScope).flatMap(_.rowConflicts).map(_.cellConflicts) shouldBe
      List(2L, 2L)
    // Closed, as a refused merge does: nothing is open any more, and the numbers are as stale as it.
    f.store.updateBranchMerge(
      f.store.getBranchMerge(reply.merge.id).get.copy(status = BranchMergeStatus.Failed)
    )
    f.restTables(RootSessionTok, RootScope).flatMap(_.rowConflicts) shouldBe empty
  }

  it should "be refused for a branch that does not exist" in {
    val f = new Fixture
    f.handlers
      .changes("acme", f.parent.name, "nope", Some(false), Some(SessionTok))(AliceScope)
      .unsafeRunSync()
      .left
      .map(_._2.error) shouldBe Left("not_found")
  }

  "a table the branch dropped after the proposal" should "show a viewer who never counted it a flag, not the stored numbers" in {
    val f = new Fixture(ConflictingNode)
    // The numbers stored with the proposal are the system's. Nothing freezes a proposed branch.
    f.handlers
      .propose(BranchProposeRequest("acme", f.parent.name, "feature"), Some(RootSessionTok))(
        RootScope
      )
      .unsafeRunSync()
      .isRight shouldBe true
    f.branchDropsNation()
    val asAlice = f.restTables(SessionTok, AliceScope).map(t => t.table -> t).toMap
    // Only a modified table is counted. For the dropped one the counts were asked for and none
    // failed, which must not read as a count that succeeded: nothing says alice may read it.
    (asAlice("nation").kind, asAlice("nation").countsUnavailable) shouldBe ("dropped", false)
    asAlice("nation").rowConflicts shouldBe
      Some(BranchRowConflicts("main", "nation", hasConflicts = true, countsUnavailable = true))
    asAlice("region").rowConflicts.map(_.cellConflicts) shouldBe Some(2L)
    // A superuser still gets the numbers.
    f.restTables(RootSessionTok, RootScope)
      .find(_.table == "nation")
      .flatMap(_.rowConflicts)
      .map(c => (c.cellConflicts, c.countsUnavailable)) shouldBe Some((2L, false))
  }

  // ---------- who gets in ----------

  private type Verdict = Either[(sttp.model.StatusCode, String), String]

  private def verdict[A](out: Either[(sttp.model.StatusCode, ErrorResponse), A]): Verdict =
    out.map(_ => "admitted").left.map(e => (e._1, e._2.error))

  /** Every endpoint a plain user may reach, as `tok`, on `feature` of the parent, under `tenant`.
    */
  private def everyEndpoint(
      f: Fixture,
      tok: String,
      tenant: String = "acme"
  ): List[(String, Verdict)] =
    val key = Some(tok)
    val db  = f.parent.name
    val h   = f.handlers
    List(
      "create" -> verdict(
        h.create(BranchCreateRequest(tenant, db, "other", ttlHours = Some(24)), key)(f.scopes)
          .unsafeRunSync()
      ),
      "list"    -> verdict(h.list(tenant, db, None, key)(f.scopes).unsafeRunSync()),
      "get"     -> verdict(h.get(tenant, db, "feature", key)(f.scopes).unsafeRunSync()),
      "changes" -> verdict(
        h.changes(tenant, db, "feature", Some(true), key)(f.scopes).unsafeRunSync()
      ),
      "diff" -> verdict(
        h.diff(tenant, db, "feature", "main", "region", None, None, None, key)(f.scopes)
          .unsafeRunSync()
      ),
      "schemaDiff" -> verdict(
        h.schemaDiff(tenant, db, "feature", "main", "region", key)(f.scopes).unsafeRunSync()
      ),
      "propose" -> verdict(
        h.propose(BranchProposeRequest(tenant, db, "feature"), key)(f.scopes).unsafeRunSync()
      ),
      "discard" -> verdict(
        h.discard(BranchOpRequest(tenant, db, "feature"), key)(f.scopes).unsafeRunSync()
      )
    )

  private val EveryEndpoint =
    List("create", "list", "get", "changes", "diff", "schemaDiff", "propose", "discard")

  private def asMerge(f: Fixture, tok: String, proposal: String = "mg-any"): Verdict =
    verdict(
      f.handlers
        .merge(BranchMergeRequest("acme", f.parent.name, "feature", proposal), Some(tok))(f.scopes)
        .unsafeRunSync()
    )

  private val Forbidden = sttp.model.StatusCode.Forbidden

  "a plain user who may connect" should "reach every endpoint but merge, by session and by PAT" in
    List(BobSession, BobPat).foreach { tok =>
      withClue(s"$tok: ") {
        val f       = new Fixture
        val key     = Some(tok)
        val db      = f.parent.name
        val created = f.handlers
          .create(BranchCreateRequest("acme", db, "mine", ttlHours = Some(24)), key)(f.scopes)
          .unsafeRunSync()
          .fold(e => fail(s"create: $e"), identity)
        created.owner shouldBe "bob"
        f.handlers
          .list("acme", db, None, key)(f.scopes)
          .unsafeRunSync()
          .map(_.branches.map(_.name).toSet) shouldBe Right(Set("feature", "mine"))
        // Someone else's branch reads too; it is theirs to propose and discard.
        f.handlers
          .get("acme", db, "feature", key)(f.scopes)
          .unsafeRunSync()
          .map(_.branch.owner) shouldBe Right("root")
        val changes = f.handlers
          .changes("acme", db, "mine", Some(true), key)(f.scopes)
          .unsafeRunSync()
          .fold(e => fail(s"changes: $e"), identity)
        changes.tables.map(t => (t.table, t.inserted, t.countsUnavailable)) shouldBe
          List(("region", 7L, false))
        // The counts and the data diff run as bob, through the node's verdict.
        val beforeDiff = f.counted.size
        f.handlers
          .diff("acme", db, "mine", "main", "region", None, None, None, key)(f.scopes)
          .unsafeRunSync()
        f.counted.drop(beforeDiff).map(c => (c._1, c._2.name)).toList.distinct shouldBe
          List(("region", "bob"))
        f.counted.foreach(c => c._3.system shouldBe false)
        f.handlers
          .schemaDiff("acme", db, "mine", "main", "region", key)(f.scopes)
          .unsafeRunSync()
          .isRight shouldBe true
        val proposed = f.handlers
          .propose(BranchProposeRequest("acme", db, "mine"), key)(f.scopes)
          .unsafeRunSync()
          .fold(e => fail(s"propose: $e"), identity)
        proposed.merge.proposer shouldBe "bob"
        f.handlers
          .merge(BranchMergeRequest("acme", db, "mine", proposed.merge.id), key)(f.scopes)
          .unsafeRunSync()
          .left
          .map(e => (e._1, e._2.error)) shouldBe Left((Forbidden, "admin_required"))
        f.handlers
          .discard(BranchOpRequest("acme", db, "mine"), key)(f.scopes)
          .unsafeRunSync()
          .map(_.status) shouldBe Right("discarded")
      }
    }

  it should "propose and discard only what they own, and ask for no forever" in {
    val f   = new Fixture
    val key = Some(BobSession)
    val db  = f.parent.name
    f.handlers
      .propose(BranchProposeRequest("acme", db, "feature"), key)(f.scopes)
      .unsafeRunSync()
      .left
      .map(e => (e._1, e._2.error)) shouldBe Left((Forbidden, "not_owner"))
    f.handlers
      .discard(BranchOpRequest("acme", db, "feature"), key)(f.scopes)
      .unsafeRunSync()
      .left
      .map(e => (e._1, e._2.error)) shouldBe Left((Forbidden, "not_owner"))
    val refused = f.handlers
      .create(BranchCreateRequest("acme", db, "forever", ttlHours = Some(0)), key)(f.scopes)
      .unsafeRunSync()
    refused.left.map(e => (e._1, e._2.error)) shouldBe
      Left((sttp.model.StatusCode.BadRequest, "invalid_ttl"))
    refused.left.toOption.get._2.message should include("168")
    f.store.findBranch(f.parent.id, "forever") shouldBe None
  }

  "a plain user who may connect to no pool of the database" should "be refused by every endpoint, with no why, before anything is read" in
    List(DaveSession, DavePat).foreach { tok =>
      withClue(s"$tok: ") {
        val f        = new Fixture(ConflictingNode)
        val proposed = f.handlers
          .propose(BranchProposeRequest("acme", f.parent.name, "feature"), Some(RootSessionTok))(
            RootScope
          )
          .unsafeRunSync()
          .fold(e => fail(s"propose: $e"), identity)
        val countedBefore = f.counted.size
        val refused       = Left((Forbidden, "no_pool_access"))
        everyEndpoint(f, tok) shouldBe EveryEndpoint.map(_ -> refused)
        asMerge(f, tok, proposed.merge.id) shouldBe refused
        f.counted.size shouldBe countedBefore
        f.store.listBranches(f.parent.id, Set.empty).map(_.name) shouldBe List("feature")
        f.store.listBranchMerges(f.feature.id).size shouldBe 1
      }
    }

  it should "read the same for a database that does not exist" in {
    val f                 = new Fixture
    def asked(db: String) =
      f.handlers.list("acme", db, None, Some(DaveSession))(f.scopes).unsafeRunSync()
    val absent = asked("no_such_db").left.map(_._2.copy(message = "")).left.map(_.error)
    absent shouldBe asked(f.parent.name).left.map(_._2.error)
    absent shouldBe Left("no_pool_access")
    // Bob, who may connect to the parent, is told the same of a database that does not exist.
    f.handlers
      .list("acme", "no_such_db", None, Some(BobSession))(f.scopes)
      .unsafeRunSync()
      .left
      .map(_._2.error) shouldBe Left("no_pool_access")
    // Not a word of why, whichever gate of the handshake said no.
    asked(f.parent.name).left.toOption.get._2.message should not include "grant"
  }

  "a credential with no tenant of its own, or another" should "be refused every tenant it does not belong to" in {
    val f       = new Fixture
    val refused = Left((Forbidden, "tenant_forbidden"))
    // Another user of another tenant, named as a member here: a name resolves per tenant.
    everyEndpoint(f, SameNameElsewhere) shouldBe EveryEndpoint.map(_ -> refused)
    asMerge(f, SameNameElsewhere) shouldBe refused
    // A member of acme on another tenant's path, and on one that does not exist: the same answer.
    everyEndpoint(f, BobSession, tenant = "globex") shouldBe EveryEndpoint.map(_ -> refused)
    everyEndpoint(f, BobSession, tenant = "nosuchtenant") shouldBe EveryEndpoint.map(_ -> refused)
    everyEndpoint(f, BobPat, tenant = "globex") shouldBe EveryEndpoint.map(_ -> refused)
    // No tenant of its own: a system-realm login that is no superuser, a superuser's --drop-admin.
    everyEndpoint(f, NoTenantSession) shouldBe EveryEndpoint.map(_ -> refused)
    everyEndpoint(f, RootDroppedPat) shouldBe EveryEndpoint.map(_ -> refused)
    f.store.listBranches(f.parent.id, Set.empty).map(_.name) shouldBe List("feature")
    // A tenant is told apart by its id or its display name alike, for a member.
    f.handlers
      .list("acme", f.parent.name, None, Some(BobSession))(f.scopes)
      .unsafeRunSync()
      .isRight shouldBe true
  }

  "admins" should "pass as before: no pool grant needed, every table named" in {
    val f = new Fixture
    // A tenant admin with no pool grant of its own.
    val noGrant = "qod_pat_admin_nogrant"
    f.extraPats.put(
      noGrant,
      PatPrincipal(
        RbacUser(id = "u-admin2", tenant = Some("acme"), username = "admin2", kind = "admin"),
        "pat-admin2",
        SessionScope(false, Set("acme")),
        isAdmin = true,
        TokenRestriction.Unrestricted
      )
    )
    val listed =
      f.handlers.list("acme", f.parent.name, None, Some(noGrant))(f.scopes).unsafeRunSync()
    listed.map(_.branches.map(_.name)) shouldBe Right(List("feature"))
    val asAdmin = f.handlers
      .changes("acme", f.parent.name, "feature", Some(false), Some(SessionTok))(f.scopes)
      .unsafeRunSync()
      .fold(e => fail(s"changes: $e"), identity)
    asAdmin.tables.map(_.table).toSet shouldBe Set("region", "nation")
    asAdmin.otherTables shouldBe 0
    // An unknown tenant is a 404 to someone who manages one, as everywhere else.
    f.handlers
      .list("nosuchtenant", f.parent.name, None, Some(SessionTok))(f.scopes)
      .unsafeRunSync()
      .left
      .map(_._2.error) shouldBe Left("not_found")
    f.handlers
      .list("acme", "no_such_db", None, Some(SessionTok))(f.scopes)
      .unsafeRunSync()
      .left
      .map(_._2.error) shouldBe Left("not_found")
  }

  "an admin of another tenant who is a plain member here" should "not be an admin here" in
    List(AdminElsewhere, AdminElsewherePat).foreach { tok =>
      withClue(s"$tok: ") {
        val f   = new Fixture(ConflictingNode)
        val key = Some(tok)
        val db  = f.parent.name
        // Merge is for an admin of this tenant.
        val proposed = f.handlers
          .propose(BranchProposeRequest("acme", db, "feature"), Some(RootSessionTok))(RootScope)
          .unsafeRunSync()
          .fold(e => fail(s"propose: $e"), identity)
        asMerge(f, tok, proposed.merge.id) shouldBe Left((Forbidden, "admin_required"))
        // Nobody else's branch to propose or discard, and no forever.
        verdict(
          f.handlers
            .propose(BranchProposeRequest("acme", db, "feature"), key)(f.scopes)
            .unsafeRunSync()
        ) shouldBe Left((Forbidden, "not_owner"))
        verdict(
          f.handlers.discard(BranchOpRequest("acme", db, "feature"), key)(f.scopes).unsafeRunSync()
        ) shouldBe Left((Forbidden, "not_owner"))
        verdict(
          f.handlers
            .create(BranchCreateRequest("acme", db, "forever", ttlHours = Some(0)), key)(f.scopes)
            .unsafeRunSync()
        ) shouldBe Left((sttp.model.StatusCode.BadRequest, "invalid_ttl"))
        // And a member's replies: the table they hold a grant on, no row of the merge row's numbers.
        val changes = f.handlers
          .changes("acme", db, "feature", Some(true), key)(f.scopes)
          .unsafeRunSync()
          .fold(e => fail(s"changes: $e"), identity)
        changes.tables.map(_.table) shouldBe List("region")
        changes.otherTables shouldBe 1
        val detail = f.handlers
          .get("acme", db, "feature", key)(f.scopes)
          .unsafeRunSync()
          .fold(e => fail(s"get: $e"), identity)
        detail.merges.flatMap(_.rowConflicts) shouldBe Nil
        // Their own tenant is still theirs: the gate lets them by, to a database that is not there.
        f.handlers
          .list("globex", "no_such_db", None, key)(f.scopes)
          .unsafeRunSync()
          .left
          .map(_._2.error) shouldBe Left("not_found")
        // And a superuser's session passes everywhere, as an admin of the tenant does.
        asMerge(f, RootSessionTok, proposed.merge.id) should not be Left(
          (Forbidden, "admin_required")
        )
      }
    }

  "a --drop-admin token" should "reach these endpoints as the plain user it is" in {
    val f  = new Fixture(ConflictingNode)
    val db = f.parent.name
    // alice's, a tenant admin's: she holds a pool grant and no table grant.
    val changes = f.handlers
      .changes("acme", db, "feature", Some(true), Some(DroppedAdminPat))(f.scopes)
      .unsafeRunSync()
      .fold(e => fail(s"changes: $e"), identity)
    changes.tables shouldBe Nil
    changes.otherTables shouldBe 2
    val proposed = f.handlers
      .propose(BranchProposeRequest("acme", db, "feature"), Some(RootSessionTok))(RootScope)
      .unsafeRunSync()
      .fold(e => fail(s"propose: $e"), identity)
    asMerge(f, DroppedAdminPat, proposed.merge.id) shouldBe Left((Forbidden, "admin_required"))
    verdict(
      f.handlers
        .create(BranchCreateRequest("acme", db, "x", ttlHours = Some(0)), Some(DroppedAdminPat))(
          f.scopes
        )
        .unsafeRunSync()
    ) shouldBe Left((sttp.model.StatusCode.BadRequest, "invalid_ttl"))
  }

  "the handshake" should "be the routed executor's: the token's axes, and the caller's own realm" in {
    val f                 = new Fixture
    val db                = f.parent.name
    def list(tok: String) =
      verdict(f.handlers.list("acme", db, None, Some(tok))(f.scopes).unsafeRunSync())
    val noPools = f.bobToken(TokenRestriction.Unrestricted.copy(pools = Some(Set("elsewhere"))))
    list(noPools) shouldBe Left((Forbidden, "no_pool_access"))
    val onBi = f.bobToken(TokenRestriction.Unrestricted.copy(pools = Some(Set("bi"))))
    list(onBi) shouldBe Right("admitted")
    val otherDb = f.bobToken(TokenRestriction.Unrestricted.copy(databases = Some(Set("elsewhere"))))
    list(otherDb) shouldBe Left((Forbidden, "token_scope"))
    val onParent = f.bobToken(TokenRestriction.Unrestricted.copy(databases = Some(Set(db))))
    list(onParent) shouldBe Right("admitted")
    // A session of acme naming only a superuser's row binds nothing (`superuserAdmissible` false).
    list(RootNamedSession) shouldBe Left((Forbidden, "no_pool_access"))
  }

  it should "bind a member's own row, never a same-named superuser row" in {
    val f   = new Fixture
    val out = verdict(
      f.handlers.list("acme", f.parent.name, None, Some(RootNamedSession))(f.scopes).unsafeRunSync()
    )
    out shouldBe Left((Forbidden, "no_pool_access"))
    // The tenant's own row binds once it exists and holds the connect grant.
    val row = RbacUser(id = "u-acme-root", tenant = Some("acme"), username = "root", kind = "user")
    f.store.upsertUserIdentity(row)
    f.sup.grantPoolPermission("acme", None, Some(row.id), None).unsafeRunSync()
    verdict(
      f.handlers.list("acme", f.parent.name, None, Some(RootNamedSession))(f.scopes).unsafeRunSync()
    ) shouldBe Right("admitted")
  }

  it should "answer 503 when it cannot decide, never a refusal" in
    List(BobSession, BobPat).foreach { tok =>
      // An opa-mode tenant with no OPA wired: the handshake fails closed as unavailable.
      val f = new Fixture(tenantAcl = TenantAcl(Some("opa"), Some("http://opa")))
      withClue(s"$tok: ") {
        val down = Left((sttp.model.StatusCode.ServiceUnavailable, "authz_unavailable"))
        everyEndpoint(f, tok) shouldBe EveryEndpoint.map(_ -> down)
      }
    }

  "what a plain user is shown" should "narrow names with the token that asks, not only its owner" in {
    val f                     = new Fixture
    def tablesAs(tok: String) = f.handlers
      .changes("acme", f.parent.name, "feature", Some(false), Some(tok))(f.scopes)
      .unsafeRunSync()
      .fold(e => fail(s"changes: $e"), identity)
    val full = tablesAs(BobPat)
    (full.tables.map(_.table), full.otherTables) shouldBe (List("region"), 1)
    // A token keeping none of bob's roles lists what its own SHOW TABLES would: nothing.
    val noRoles = f.bobToken(TokenRestriction.Unrestricted.copy(roles = Some(Set.empty)))
    val narrow  = tablesAs(noRoles)
    (narrow.tables, narrow.otherTables) shouldBe (Nil, 2)
    // A verb ceiling leaves the grant standing: any verb shows the name.
    val readOnly = f.bobToken(TokenRestriction.Unrestricted.copy(verbCeiling = Some("RO")))
    tablesAs(readOnly).tables.map(_.table) shouldBe List("region")
  }

  it should "read no count of a table they do not see" in {
    val f = new Fixture
    f.handlers
      .changes("acme", f.parent.name, "feature", Some(true), Some(BobSession))(f.scopes)
      .unsafeRunSync()
      .isRight shouldBe true
    f.counted.map(_._1).toList shouldBe List("region")
  }

  it should "answer a diff of a table they do not see as of a table that does not exist" in {
    val f   = new Fixture
    val key = Some(BobSession)
    val db  = f.parent.name
    def bare[A](out: Either[(sttp.model.StatusCode, ErrorResponse), A], table: String) =
      out.left.map((code, e) => (code, e.copy(message = e.message.replace(table, "<t>"))))
    def schemaDiff(t: String) =
      f.handlers.schemaDiff("acme", db, "feature", "main", t, key)(f.scopes).unsafeRunSync()
    def dataDiff(t: String, changeType: Option[String] = None) =
      f.handlers
        .diff("acme", db, "feature", "main", t, None, None, changeType, key)(f.scopes)
        .unsafeRunSync()
    schemaDiff("ghost").left.map(_._2.error) shouldBe Left("not_found")
    bare(schemaDiff("nation"), "nation") shouldBe bare(schemaDiff("ghost"), "ghost")
    dataDiff("ghost").left.map(_._2.error) shouldBe Left("not_found")
    bare(dataDiff("nation"), "nation") shouldBe bare(dataDiff("ghost"), "ghost")
    // A bad filter first, for both: nothing else in the request tells them apart.
    bare(dataDiff("nation", Some("nope")), "nation") shouldBe bare(
      dataDiff("ghost", Some("nope")),
      "ghost"
    )
    f.counted.map(_._1) should not contain "nation"
    // An admin gets the real thing.
    f.handlers
      .schemaDiff("acme", db, "feature", "main", "nation", Some(SessionTok))(f.scopes)
      .unsafeRunSync()
      .isRight shouldBe true
  }

  it should "answer a key on a table they do not see as on one not in the change set" in {
    val f   = new Fixture
    val key = Some(BobSession)
    val db  = f.parent.name
    f.handlers
      .create(BranchCreateRequest("acme", db, "mine", ttlHours = Some(24)), key)(f.scopes)
      .unsafeRunSync()
      .isRight shouldBe true
    def refusal(table: String, column: String) = f.handlers
      .propose(BranchProposeRequest("acme", db, "mine", keys = Map(table -> List(column))), key)(
        f.scopes
      )
      .unsafeRunSync()
      .left
      .map((code, e) => (code, e.error, e.message.replace(table, "<t>")))
    val notChanged = refusal("main.other", "x")
    notChanged.left.map(_._2) shouldBe Left("invalid_key")
    refusal("main.nation", "x") shouldBe notChanged
    refusal("main.nation", "no_such_column") shouldBe notChanged
    // A table they see answers about its columns.
    refusal("main.region", "no_such_column").left.map(_._3) should not be notChanged.left.map(_._3)
  }

  "a viewer who may not count a table" should "learn nothing of it from a proposal: no row, no number, no flag" in {
    // carol may connect and holds a grant on region; the node refuses all her counts, as it does a
    // policy holder's. Whatever Decide found, she reads the same: this is the oracle's case.
    def repliesOver(node: ai.starlake.quack.ondemand.branch.MergeNode) =
      val f   = new Fixture(node)
      val key = Some(CarolSession)
      val db  = f.parent.name
      f.handlers
        .create(BranchCreateRequest("acme", db, "guess", ttlHours = Some(24)), key)(f.scopes)
        .unsafeRunSync()
        .isRight shouldBe true
      val proposed = f.handlers
        .propose(
          BranchProposeRequest("acme", db, "guess", keys = Map("main.region" -> List("x"))),
          key
        )(f.scopes)
        .unsafeRunSync()
        .fold(e => fail(s"propose: $e"), identity)
      val changes = f.handlers
        .changes("acme", db, "guess", Some(true), key)(f.scopes)
        .unsafeRunSync()
        .fold(e => fail(s"changes: $e"), identity)
      val detail = f.handlers
        .get("acme", db, "guess", key)(f.scopes)
        .unsafeRunSync()
        .fold(e => fail(s"get: $e"), identity)
      // What cannot be the same twice: the ids and the clock.
      def plain(m: BranchMergeEntry) = m.copy(id = "", createdAt = None, decidedAt = None)
      (proposed.changes, plain(proposed.merge), changes, detail.merges.map(plain))
    val matches = repliesOver(ai.starlake.quack.ondemand.branch.ZeroRowsMergeNode)
    val differs = repliesOver(ConflictingNode)
    matches shouldBe differs
    val (reply, merge, read, merges) = differs
    List(reply, read).foreach { c =>
      c.tables.map(_.table) shouldBe List("region")
      c.tables.foreach { t =>
        t.rowConflicts shouldBe None
        (t.inserted, t.deleted, t.updated, t.countsUnavailable) shouldBe (0L, 0L, 0L, true)
      }
    }
    (merge :: merges).foreach { m =>
      m.rowConflicts shouldBe Nil
      m.summary.noSpaces should not include "cellConflicts"
    }
    // A superuser reading the same proposals tells them apart: the case has teeth.
    def asRoot(node: ai.starlake.quack.ondemand.branch.MergeNode) =
      val f = new Fixture(node)
      f.handlers
        .propose(BranchProposeRequest("acme", f.parent.name, "feature"), Some(RootSessionTok))(
          RootScope
        )
        .unsafeRunSync()
        .fold(e => fail(s"propose: $e"), identity)
        .changes
        .tables
        .flatMap(_.rowConflicts)
        .map(_.hasConflicts)
    asRoot(ai.starlake.quack.ondemand.branch.ZeroRowsMergeNode) should not be asRoot(
      ConflictingNode
    )
  }

  "a plain user who may count a table" should "see its numbers, and the merge row none" in {
    val f   = new Fixture(ConflictingNode)
    val key = Some(BobSession)
    val db  = f.parent.name
    f.handlers
      .create(BranchCreateRequest("acme", db, "mine", ttlHours = Some(24)), key)(f.scopes)
      .unsafeRunSync()
      .isRight shouldBe true
    val proposed = f.handlers
      .propose(BranchProposeRequest("acme", db, "mine"), key)(f.scopes)
      .unsafeRunSync()
      .fold(e => fail(s"propose: $e"), identity)
    proposed.changes.tables.map(t => (t.table, t.rowConflicts.map(_.cellConflicts))) shouldBe
      List(("region", Some(2L)))
    proposed.changes.otherTables shouldBe 1
    proposed.merge.rowConflicts shouldBe Nil
    val read = f.handlers
      .changes("acme", db, "mine", Some(true), key)(f.scopes)
      .unsafeRunSync()
      .fold(e => fail(s"changes: $e"), identity)
    read.tables.flatMap(_.rowConflicts).map(_.cellConflicts) shouldBe List(2L)
    // No counts asked for: nothing to judge by, so no numbers, and no row at all.
    f.handlers
      .changes("acme", db, "mine", Some(false), key)(f.scopes)
      .unsafeRunSync()
      .fold(e => fail(s"changes: $e"), identity)
      .tables
      .flatMap(_.rowConflicts) shouldBe Nil
  }

  "the branch tools over MCP" should "serve a plain user's token, and hold the same rules" in {
    val f   = new Fixture
    val pat =
      f.callers.resolve(Some(BobPat)).toOption.collect { case RestCaller.Principal.Pat(p) => p }.get
    val tool = new McpBranchTools(f.handlers, f.scopes).tools.map(t => t.name -> t).toMap
    def call(name: String, args: (String, Json)*) = tool(name)
      .run(new McpPrincipal.Pat(pat, BobPat), JsonObject.fromIterable(args))
      .unsafeRunSync()
    val db      = "database" -> Json.fromString(f.parent.name)
    val created = call(
      "create_branch",
      db,
      "name"      -> Json.fromString("mine"),
      "ttl_hours" -> Json.fromInt(24)
    ).fold(e => fail(s"create_branch: $e"), identity)
    created.hcursor.get[String]("owner") shouldBe Right("bob")
    val branch  = "branch" -> Json.fromString("mine")
    val changes =
      call("branch_changes", db, branch).fold(e => fail(s"branch_changes: $e"), identity)
    changes.hcursor.get[Int]("otherTables") shouldBe Right(1)
    changes.hcursor.downField("tables").downN(0).get[String]("table") shouldBe Right("region")
    val proposed = call("propose_merge", db, branch).fold(e => fail(s"propose_merge: $e"), identity)
    proposed.hcursor.downField("merge").get[String]("proposer") shouldBe Right("bob")
    // The same refusals, with their codes in front.
    call("propose_merge", db, "branch" -> Json.fromString("feature")).left.toOption.get should
      startWith("not_owner")
    call(
      "create_branch",
      db,
      "name"      -> Json.fromString("x"),
      "ttl_hours" -> Json.fromInt(0)
    ).left.toOption.get should
      startWith("invalid_ttl")
    tool.keySet should not contain "merge"
    // A token of someone with no pool grant.
    val dave = f.callers
      .resolve(Some(DavePat))
      .toOption
      .collect { case RestCaller.Principal.Pat(p) => p }
      .get
    tool("list_branches")
      .run(new McpPrincipal.Pat(dave, DavePat), JsonObject(db))
      .unsafeRunSync()
      .left
      .toOption
      .get should startWith("no_pool_access")
  }
