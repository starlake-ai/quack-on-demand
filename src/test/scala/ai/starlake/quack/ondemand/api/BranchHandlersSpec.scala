package ai.starlake.quack.ondemand.api

import ai.starlake.quack.{BranchingConfig, CatalogConfig}
import ai.starlake.quack.edge.RouterFailure
import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{
  Branch,
  BranchMerge,
  BranchMergeStatus,
  BranchStatus,
  Tenant,
  TenantDbKind
}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.branch.{
  BranchActor,
  BranchChangeSet,
  BranchConflict,
  BranchService,
  ChangeCounter,
  ChangeKind,
  MergeFence,
  RowCounts,
  TableChange
}
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{
  DbAdmin,
  InMemoryControlPlaneStore,
  RbacUser,
  RolePermission
}
import ai.starlake.quack.ondemand.telemetry.AuditRecorder
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

import scala.collection.mutable

/** Who a branch mutation acts as. Regression: a present token that resolved as neither a session
  * nor a PAT (an expired session racing the guard, for one) became the superuser admin actor, and
  * could approve a merge. It must be refused with 401 before the branch service is reached.
  */
class BranchHandlersSpec extends AnyFlatSpec with Matchers:

  import IdentityFixtures.*

  private val SuperuserScope: SessionScope.Resolver = _ => SessionScope.Superuser

  private final class Fixture:
    val store = new InMemoryControlPlaneStore()
    val admin = new DbAdmin:
      def createDatabase(name: String): Either[String, Unit] = Right(())
      def dropDatabase(name: String): Either[String, Unit]   = Right(())
      def holdsAnything(name: String): Either[String, Boolean] = Right(false)
    val sup =
      new PoolSupervisor(new StubQuackBackend(), new NodeLoadTracker, store, dbAdmin = admin)
    val _      = sup.createTenant(Tenant("acme")).unsafeRunSync()
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
        "/tmp/qod-branch-handlers-spec/acme_tpch/",
        writerTrusted = true
      )
      .unsafeRunSync()
      .toOption
      .get

    // Everything the service does on a mutation (denied or not) is audited through THIS recorder,
    // separate from the handlers' own: an event here means the service was reached.
    val serviceAudit = new RecordingTelemetryStore
    val cloneCalls   = mutable.ListBuffer.empty[String]
    val service      = new BranchService(
      cfg = BranchingConfig(),
      sup = sup,
      store = store,
      resolveReader = (_, _) => new DuckLakeCatalogReader(null),
      cloneCatalog = (_, _, branchDb, _) => { cloneCalls += branchDb; Left("not in this spec") },
      mergeExecutor = ai.starlake.quack.ondemand.branch.ZeroRowsMergeExecutor,
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
      counter = new ChangeCounter:
        def count(
            caller: ai.starlake.quack.ondemand.api.ExecCaller,
            t: String,
            b: Branch,
            a: String,
            c: TableChange,
            f: Long,
            h: Long
        ) =
          IO.pure(Right((0L, 0L, 0L)))
      ,
      purgeFiles = (_, _) => Right(()),
      audit = new AuditRecorder(serviceAudit, _ => None)
    )
    val callers = restCaller(sessionOf, patOf)
    val preview = new CatalogPreviewHandlers(
      sup,
      store,
      callers,
      (_, _, _) => IO.pure(Left(RouterFailure.Unavailable("unused"))),
      (_, _) => new DuckLakeCatalogReader(null),
      CatalogConfig()
    )
    val handlers = new BranchHandlers(sup, service, preview, callers)

    def merge(key: Option[String]) =
      handlers
        .merge(BranchMergeRequest("acme", parent.name, "feature", "mg-any"), key)(SuperuserScope)
        .unsafeRunSync()

  private def unauthorized[A](r: Either[(StatusCode, ErrorResponse), A]) =
    r.left.toOption.map(e => (e._1, e._2.error)) shouldBe
      Some((StatusCode.Unauthorized, "unauthorized"))

  "a branch mutation" should "refuse an absent or unresolvable credential with 401 before the service" in {
    val f = new Fixture
    unauthorized(f.merge(None))
    unauthorized(f.merge(Some("qod_pat_unknown")))
    unauthorized(
      f.handlers
        .create(BranchCreateRequest("acme", f.parent.name, "feature"), Some("expired-jwt"))(
          SuperuserScope
        )
        .unsafeRunSync()
    )
    unauthorized(
      f.handlers
        .propose(BranchProposeRequest("acme", f.parent.name, "feature"), Some("garbage"))(
          SuperuserScope
        )
        .unsafeRunSync()
    )
    unauthorized(
      f.handlers
        .discard(BranchOpRequest("acme", f.parent.name, "feature"), Some("garbage"))(SuperuserScope)
        .unsafeRunSync()
    )
    unauthorized(
      f.handlers
        .changes("acme", f.parent.name, "feature", None, Some("garbage"))(SuperuserScope)
        .unsafeRunSync()
    )
    f.serviceAudit.events shouldBe empty
    f.cloneCalls shouldBe empty
  }

  it should "reach the service for a superuser session, a session and a PAT" in
    List(Some(RootSessionTok), Some(SessionTok), Some(PatTok)).foreach { key =>
      val f   = new Fixture
      val out = f.merge(key)
      withClue(s"key=$key: ") {
        out.left.toOption.map(_._1) should not be Some(StatusCode.Unauthorized)
        f.serviceAudit.events should not be empty
      }
    }

  "BranchHandlers.adminOf" should "be decided for the tenant in hand, and no other" in {
    BranchHandlers.adminOf(SessionScope.Superuser, "acme") shouldBe true
    BranchHandlers.adminOf(SessionScope(false, Set("acme")), "acme") shouldBe true
    BranchHandlers.adminOf(SessionScope(false, Set("globex")), "acme") shouldBe false
    BranchHandlers.adminOf(SessionScope.NoAccess, "acme") shouldBe false
  }

  "BranchHandlers.seenBy" should "see a table on a read-covering grant alone, as filtered metadata lists it" in {
    def set(grants: RolePermission*) =
      EffectiveSet(RbacUser("u", Some("acme"), "bob", "user"), Nil, Nil, grants.toList, Nil)
    def perm(catalog: String, schema: String, table: String, verb: String) =
      RolePermission("p", "r", catalog, schema, table, verb)
    def sees(grants: RolePermission*) = BranchHandlers.seenBy(set(grants*), "cat")("main", "fact")
    // Every verb a grant can hold: a new one has to take a side here.
    val seen = Map("RO" -> true, "RW" -> true, "ALL" -> true, "DDL" -> false)
    seen.keySet shouldBe RolePermission.ValidVerbs
    seen.foreach { (verb, expected) =>
      withClue(verb)(sees(perm("cat", "main", "fact", verb)) shouldBe expected)
      withClue(s"$verb on the whole schema")(sees(perm("cat", "main", "*", verb)) shouldBe expected)
    }
    // A DDL grant takes nothing away from a read grant beside it.
    sees(perm("cat", "main", "*", "DDL"), perm("cat", "main", "fact", "RO")) shouldBe true
    sees(perm("*", "*", "*", "RO")) shouldBe true
    sees(perm("*", "*", "*", "DDL")) shouldBe false
    sees(perm("CAT", "MAIN", "FACT", "RO")) shouldBe true
    sees(perm("cat", "main", "*", "RO")) shouldBe true
    sees(perm("cat", "main", "other", "ALL")) shouldBe false
    sees(perm("cat", "other", "fact", "ALL")) shouldBe false
    sees(perm("other", "main", "fact", "ALL")) shouldBe false
    // An effective set with nothing resolved shows nothing: fail closed.
    sees() shouldBe false
  }

  // ---------- what a reply shows, by viewer ----------

  /** Sees `main.seen` and nothing else, as a user holding one grant does. */
  private val Plain = BranchActor.plain("bob", sees = (s, t) => (s, t) == ("main", "seen"))
  private val Admin = BranchActor.admin("alice")

  private val aBranch = Branch(
    id = "br-1",
    tenant = "acme",
    parentDbId = "td-1",
    parentDbName = "tpch",
    name = "feature",
    tenantDbId = "td-2",
    tenantDbName = "tpch__br_1",
    poolName = "__br_1",
    dataPath = "/tmp/x/",
    forkSnapshot = 10L,
    ownerUser = "bob",
    status = BranchStatus.Open,
    expiresAt = None
  )

  private val aChangeSet = BranchChangeSet(
    forkSnapshot = 10L,
    headSnapshot = 12L,
    mainSnapshot = 11L,
    tables = List(
      TableChange("main", "seen", ChangeKind.Modified, inserted = 7L),
      TableChange("main", "hidden", ChangeKind.Modified, inserted = 5L),
      TableChange("main", "gone", ChangeKind.Dropped)
    ),
    conflicts = List(
      BranchConflict("main", "seen", "changed on main since the fork"),
      BranchConflict("main", "gone", "dropped on main since the fork")
    ),
    unsupported = List("""created_view:"main"."v_secret"""")
  )

  /** Every counter differs, so that a number left in a reply shows. */
  private val someRows = List("seen", "hidden").map(t =>
    RowCounts(
      "main",
      t,
      toUpdate = 1L,
      toDelete = 2L,
      toInsert = 3L,
      cellConflicts = 4L,
      keyMatches = 5L,
      mainRowsByKey = 6L,
      columnConflicts = Map("x" -> 4L),
      samples = Map("cell" -> List(70L))
    )
  )

  private val aProposal = BranchMerge(
    id = "mg-1",
    branchId = "br-1",
    proposer = "bob",
    approver = None,
    status = BranchMergeStatus.Proposed,
    summaryJson = BranchService.summaryJson(aChangeSet).noSpaces,
    conflictsJson = BranchService.conflictsJson(aChangeSet).noSpaces,
    mainSnapshotAtPropose = 11L,
    error = Some("a refusal naming main.hidden"),
    mergeKeysJson = """{"main.seen":["k"],"main.hidden":["secret_column"]}""",
    rowConflictsJson = RowCounts.listToJson(someRows)
  )

  private def tablesOf(summary: Json): List[String] =
    summary.hcursor
      .downField("tables")
      .values
      .toList
      .flatten
      .flatMap(_.hcursor.get[String]("table").toOption)

  "a change set" should "show an admin every table, and a non-admin only those they see, counting the rest" in {
    val asAdmin = BranchHandlers.changesResponse(aBranch, aChangeSet, None, Admin, _ => true)
    asAdmin.tables.map(_.table) shouldBe List("seen", "hidden", "gone")
    asAdmin.conflicts.map(_.table) shouldBe List("seen", "gone")
    asAdmin.unsupported shouldBe aChangeSet.unsupported
    asAdmin.otherTables shouldBe 0

    val asPlain = BranchHandlers.changesResponse(aBranch, aChangeSet, None, Plain, _ => true)
    asPlain.tables.map(_.table) shouldBe List("seen")
    asPlain.conflicts.map(_.table) shouldBe List("seen")
    // A raw verb can name a view or a macro.
    asPlain.unsupported shouldBe Nil
    // hidden and gone (a table in both lists counts once), and the unsupported verb.
    asPlain.otherTables shouldBe 3
    // The whole branch's: it cannot merge, whatever this viewer is shown.
    asPlain.mergeable shouldBe false
    asPlain.mergeable shouldBe asAdmin.mergeable
  }

  "a table's row numbers" should "show a viewer who may count it, whoever they are" in {
    def numbers(actor: BranchActor) = BranchHandlers
      .changesResponse(aBranch, aChangeSet, Some(aProposal), actor, _ => true)
      .tables
      .find(_.table == "seen")
      .flatMap(_.rowConflicts)
      .map(c => (c.cellConflicts, c.keyMatches, c.mainRowsByKey, c.samples, c.countsUnavailable))
    numbers(Plain) shouldBe Some((4L, 5L, 6L, Map("cell" -> List(70L)), false))
    numbers(Admin) shouldBe Some((4L, 5L, 6L, Map("cell" -> List(70L)), false))
  }

  they should "show an admin who may not count it the flag, and a non-admin nothing at all" in {
    // `hasConflicts` alone is an equality oracle for a non-admin under a mask or a row policy: a key
    // on the masked column, or an UPDATE of a cell main also changed, compared as the system.
    def row(actor: BranchActor) = BranchHandlers
      .changesResponse(aBranch, aChangeSet, Some(aProposal), actor, _ => false)
      .tables
      .find(_.table == "seen")
      .get
      .rowConflicts
    row(Admin) shouldBe
      Some(BranchRowConflicts("main", "seen", hasConflicts = true, countsUnavailable = true))
    row(Plain) shouldBe None
  }

  "a merge row" should "show a non-admin no row numbers, and only the tables they see" in {
    val entry = BranchHandlers.mergeEntry(aProposal, systemCounts = false, Plain)
    entry.rowConflicts shouldBe Nil
    tablesOf(entry.summary) shouldBe List("seen")
    entry.summary.hcursor.get[List[String]]("unsupported") shouldBe Right(Nil)
    // The system's counts stay zeroed, as for an admin.
    entry.summary.hcursor.downField("tables").downN(0).get[Long]("inserted") shouldBe Right(0L)
    entry.summary.hcursor.downField("tables").downN(0).get[Boolean]("countsUnavailable") shouldBe
      Right(true)
    entry.conflicts.asArray.toList.flatten.flatMap(_.hcursor.get[String]("table").toOption) shouldBe
      List("seen")
    entry.keys shouldBe Map("main.seen" -> List("k"))
    // Recorded as is: refusal text can name a table of the change set (a known residual).
    entry.error shouldBe Some("a refusal naming main.hidden")
  }

  it should "show an admin every table and the flags, and a superuser the numbers" in {
    val admin = BranchHandlers.mergeEntry(aProposal, systemCounts = false, Admin)
    tablesOf(admin.summary) shouldBe List("seen", "hidden", "gone")
    admin.summary.hcursor.get[List[String]]("unsupported") shouldBe Right(aChangeSet.unsupported)
    admin.keys.keySet shouldBe Set("main.seen", "main.hidden")
    admin.rowConflicts.map(c => (c.table, c.cellConflicts, c.countsUnavailable)) shouldBe
      List(("seen", 0L, true), ("hidden", 0L, true))
    val superuser = BranchHandlers.mergeEntry(aProposal, systemCounts = true, Admin)
    superuser.rowConflicts.map(c => (c.table, c.cellConflicts, c.countsUnavailable)) shouldBe
      List(("seen", 4L, false), ("hidden", 4L, false))
  }

  it should "fail closed on a stored row it cannot read, and read a schema holding a dot" in {
    val unreadable = BranchHandlers.mergeEntry(
      aProposal.copy(summaryJson = "not json", conflictsJson = """[{"reason":"no names"}, 3]"""),
      systemCounts = false,
      Plain
    )
    tablesOf(unreadable.summary) shouldBe Nil
    unreadable.conflicts shouldBe Json.arr()
    // With no summary to place a key's table, no key shows.
    unreadable.keys shouldBe Map.empty

    val dotted = BranchActor.plain("bob", sees = (s, t) => (s, t) == ("a.b", "c"))
    val set    = aChangeSet.copy(
      tables = List(
        TableChange("a.b", "c", ChangeKind.Modified),
        TableChange("a", "b.c", ChangeKind.Modified)
      ),
      conflicts = Nil
    )
    val entry = BranchHandlers.mergeEntry(
      aProposal.copy(
        summaryJson = BranchService.summaryJson(set).noSpaces,
        mergeKeysJson = """{"a.b.c":["k"]}"""
      ),
      systemCounts = false,
      dotted
    )
    entry.keys shouldBe Map("a.b.c" -> List("k"))
    val other = BranchActor.plain("bob", sees = (s, t) => (s, t) == ("z", "z"))
    BranchHandlers
      .mergeEntry(
        aProposal.copy(
          summaryJson = BranchService.summaryJson(set).noSpaces,
          mergeKeysJson = """{"a.b.c":["k"]}"""
        ),
        systemCounts = false,
        other
      )
      .keys shouldBe Map.empty
  }
