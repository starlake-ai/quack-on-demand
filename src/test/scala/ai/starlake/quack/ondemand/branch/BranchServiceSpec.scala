package ai.starlake.quack.ondemand.branch

import ai.starlake.quack.BranchingConfig
import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{
  Branch,
  BranchMerge,
  BranchMergeStatus,
  BranchStatus,
  NodeSpec,
  PoolKey,
  RoleDistribution,
  Tenant,
  TenantAcl,
  TenantDb,
  TenantDbKind
}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.CatalogColumnEntry
import ai.starlake.quack.ondemand.catalog.{DuckLakeCatalogReader, PinnedSetResolver, TableVersion}
import ai.starlake.quack.ondemand.ha.PoolLocker
import ai.starlake.quack.ondemand.runtime.testkit.{LogCapture, StubQuackBackend}
import ai.starlake.quack.ondemand.state.{DbAdmin, InMemoryControlPlaneStore, RbacUser}
import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRecorder}
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import ch.qos.logback.classic.Level
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.collection.mutable
import scala.concurrent.duration.*

/** Lifecycle state machine and authorization of the branch service on the in-memory store with a
  * stub backend: no Postgres, no DuckDB. The clone, the merge node and the file purge are fakes
  * that record what they were asked to do.
  */
class BranchServiceSpec extends AnyFlatSpec with Matchers:

  /** Who a proposal's reply counts as; the fake counter answers the same whoever asks. */
  private val Proposer =
    ai.starlake.quack.ondemand.api.ExecCaller.unrestricted("propose-spec", "root", true)

  private val TimedOutSend = "timeout: the merge node did not answer within 600s"
  private val FenceRefused = "qod merge fence: a merged table moved past snapshot 10"
  private val ServiceLog   = classOf[BranchService].getName

  /** The snapshot every branch of this spec is cut at: what the fixture's clone answers. */
  private val Fork = 10L

  /** A metadata reader over hand-built state, so change sets are deterministic. */
  private class FakeReader(
      var head: Long,
      var versions: List[TableVersion] = List(TableVersion(2L, "tpch1", "region", 1L, None)),
      var changes: List[(Long, String)] = Nil,
      var committed: mutable.Map[String, Long] = mutable.Map.empty
  ) extends DuckLakeCatalogReader(null):
    override def maxSnapshotId(): Option[Long]       = Some(head)
    override def tableVersions(): List[TableVersion] = versions

    /** The fork's own snapshot row, which both real catalogs hold (the branch's is a copy of
      * main's): `changes` lists the snapshots since the fork. A test that expires the fork turns it
      * off.
      */
    var forkRow                                                         = true
    override def snapshotChangesSince(fork: Long): List[(Long, String)] =
      (if forkRow && fork < Fork then List((Fork, "")) else Nil) ++ changes.filter(_._1 > fork)
    override def columnsOfTableAt(id: Long, n: Long): List[CatalogColumnEntry] =
      List(CatalogColumnEntry(0, "x", "INTEGER", true, false))
    override def topLevelColumnsOfTableAt(id: Long, n: Long): List[CatalogColumnEntry] =
      columnsOfTableAt(id, n)
    override def droppedSchemasSince(fork: Long): Set[String] = Set.empty

    /** Every look for a commit fails while this is set, as on a stalled catalog. */
    var lookFails = false

    /** How many reads looked for a commit. */
    var looks                                                       = 0
    override def snapshotByCommitMessage(msg: String): Option[Long] =
      looks += 1
      if lookFails then throw new java.sql.SQLException("the catalog does not answer")
      else committed.get(msg)
    override def close(): Unit = ()

  private final class Fixture(
      cfg: BranchingConfig = BranchingConfig(),
      parentEncrypted: Boolean = false,
      tenantAcl: TenantAcl = TenantAcl(),
      attemptWait: FiniteDuration = Duration.Zero,
      holdSettle: FiniteDuration = Duration.Zero
  ):
    val store   = new InMemoryControlPlaneStore()
    val created = mutable.ListBuffer.empty[String]
    val dropped = mutable.ListBuffer.empty[String]
    val admin   = new DbAdmin:
      def createDatabase(name: String): Either[String, Unit] = { created += name; Right(()) }
      def dropDatabase(name: String): Either[String, Unit]   = { dropped += name; Right(()) }
    val backend = new StubQuackBackend()
    val sup     = new PoolSupervisor(
      backend,
      new NodeLoadTracker,
      store,
      dbAdmin = admin,
      locks = PoolLocker.inProcess()
    )
    val tenant = sup.createTenant(Tenant("acme", acl = tenantAcl)).unsafeRunSync().toOption.get
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
        "/tmp/qod-branch-spec/acme_tpch/",
        encrypted = parentEncrypted
      )
      .unsafeRunSync()
      .toOption
      .get
    val parentKey = PoolKey("acme", parent.name, "bi")
    sup.createPool(parentKey, RoleDistribution(0, 0, 1)).unsafeRunSync()

    val mainReader   = new FakeReader(head = 10L)
    val branchReader = new FakeReader(head = 10L)
    val readers      = mutable.Map[String, DuckLakeCatalogReader](parent.name -> mainReader)

    var cloneFails   = false
    val cloneCalls   = mutable.ListBuffer.empty[(String, String, String)]
    val mergeBatches = mutable.ListBuffer.empty[String]
    var mergeFails   = Option.empty[String]

    /** One error per failing Apply, in order. */
    var applyScript = List.empty[String]
    var beforeRun   = () => ()

    /** Runs once the merge node's stop has returned. */
    var afterNode = () => ()

    /** Runs on every arm of the fence. */
    var onArm      = () => ()
    var fenceFails = Option.empty[String]

    /** The counts row Decide reads: to update, delete, insert, cell, update-delete, delete-update,
      * key matches, min and max update rowid, main's rows the key matches, then conflicts per
      * column.
      */
    var countsRow: List[Long] = List(1L, 0L, 0L, 0L, 0L, 0L, 0L, 1L, 1L, 0L, 0L)
    val nodeBatches           = mutable.ListBuffer.empty[String]
    var nodesStarted          = 0

    /** Runs on every batch of Prepare or Decide. */
    var duringAnalysis: () => Unit = () => ()

    /** What every batch of Prepare or Decide answers. */
    var stageFails = Option.empty[String]

    /** Why the merge node does not come up. */
    var nodeFails    = Option.empty[String]
    val fenceArms    = mutable.ListBuffer.empty[(String, String, Long, Set[Long], Option[Int])]
    val fenceDisarms = mutable.ListBuffer.empty[String]
    val purged       = mutable.ListBuffer.empty[String]
    val auditStore   = new RecordingTelemetryStore

    /** Each change count asked for: who it ran as, and of which table. */
    val countedAs = mutable.ListBuffer.empty[(String, String)]
    val service   = new BranchService(
      cfg = cfg,
      sup = sup,
      store = store,
      resolveReader = (_, db) => readers.getOrElseUpdate(db, branchReader),
      cloneCatalog = (_, parentDb, branchDb, path) =>
        cloneCalls += ((parentDb, branchDb, path))
        if cloneFails then Left("boom") else Right(CloneResult(Fork, 30, 100L))
      ,
      mergeExecutor = new MergeExecutor:
        def withNode[A](spec: NodeSpec)(
            body: MergeNode => IO[Either[String, A]]
        ): IO[Either[String, A]] =
          nodesStarted += 1
          val run: IO[Either[String, A]] = nodeFails match
            case Some(err) => IO.pure(Left(err))
            case None      =>
              body(new MergeNode:
                def exec(statements: List[String]): IO[Either[String, Unit]] =
                  // One string per call: what these cases read is the list, whole and in order.
                  val batch = statements.mkString("", ";\n", ";")
                  nodeBatches += batch
                  if !batch.startsWith("BEGIN") then
                    duringAnalysis()
                    IO.pure(stageFails.toLeft(()))
                  else
                    mergeBatches += batch
                    beforeRun()
                    applyScript match
                      case err :: rest =>
                        applyScript = rest
                        IO.pure(Left(err))
                      case Nil =>
                        mergeFails match
                          case Some(err) => IO.pure(Left(err))
                          case None      =>
                            // The batch's message is the commit-message call's third literal; the
                            // fake parent reader answers the locate query for it.
                            val msg = batch.linesIterator
                              .find(_.startsWith("CALL ducklake_set_commit_message"))
                              .get
                              .split("'")(5)
                            mainReader.committed.put(msg, mainReader.head + 1)
                            mainReader.head += 1
                            IO.pure(Right(()))
                def rows(sql: String): IO[Either[String, List[List[io.circe.Json]]]] =
                  IO.pure(
                    Right(
                      if sql.contains("_need") then
                        List(List(io.circe.Json.fromLong(0L), io.circe.Json.fromLong(0L)))
                      else if sql.contains("AS kind") then Nil
                      else List(countsRow.map(io.circe.Json.fromLong))
                    )
                  ))
          // Where the real one stops the node.
          run.guarantee(IO(afterNode()))
      ,
      mergeFence = new MergeFence:
        def arm(
            meta: Map[String, String],
            message: String,
            base: Long,
            ids: Set[Long],
            hold: Option[Int]
        ) =
          fenceArms += ((meta("dbName"), message, base, ids, hold))
          onArm()
          fenceFails.toLeft(())
        def disarm(meta: Map[String, String], message: String) = fenceDisarms += message: Unit
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
          countedAs += ((caller.identity, c.table))
          IO.pure(Right((3L, 1L, 2L)))
      ,
      purgeFiles = (path, _) => { purged += path; Right(()) },
      audit = new AuditRecorder(auditStore, _ => None),
      now = () => Instant.parse("2026-09-20T12:00:00Z"),
      attemptWait = attemptWait,
      holdSettle = holdSettle
    )

    val admin1 = BranchActor.admin("root")
    val admin2 = BranchActor.admin("boss")
    val alice  = BranchActor.plain("alice", sees = (_, _) => true)

    def grantConnect(username: String): Unit =
      val user =
        RbacUser(id = s"u-$username", tenant = Some(tenant.id), username = username, kind = "user")
      store.upsertUserIdentity(user)
      sup.grantPoolPermission(tenant.id, None, Some(user.id), None).unsafeRunSync()

    def create(name: String, actor: BranchActor = admin1, ttl: Option[Int] = None) =
      service.create("acme", parent.name, name, ttl, None, actor, None).unsafeRunSync()

    /** Give the branch some work: a modified region table. */
    def touchBranch(): Unit =
      branchReader.head = 12L
      branchReader.changes = List((11L, "inlined_insert:2"), (12L, "inlined_delete:2"))

    def propose(
        name: String = "x",
        options: ProposeOptions = ProposeOptions(),
        actor: BranchActor = admin1
    ) =
      service.propose("acme", parent.name, name, options, actor, Proposer, None).unsafeRunSync()

    /** The open proposal's id, as an approver reads it. A branch with none names none. */
    def openProposal(name: String = "x"): String =
      store
        .findBranch(parent.id, name)
        .toList
        .flatMap(b => store.listBranchMerges(b.id))
        .find(_.status == BranchMergeStatus.Proposed)
        .fold("none")(_.id)

    /** The commit of the proposal `id` lands on main, as a batch nobody waits for any more would.
      */
    def land(id: String, snapshot: Long = 11L): Unit =
      mainReader.committed.put(BranchMergeSql.commitMessage("x", id, "root"), snapshot)
      mainReader.head = snapshot
      if !mainReader.changes.exists(_._1 == snapshot) then
        mainReader.changes = mainReader.changes :+ ((snapshot, "inlined_insert:2"))

    /** The branch also creates a table, which a merge's commit creates on main too. */
    def createdOnBranch(): Unit =
      branchReader.head = 13L
      branchReader.versions = branchReader.versions :+ TableVersion(3L, "tpch1", "extra", 13L, None)
      branchReader.changes = branchReader.changes :+ ((13L, """created_table:"tpch1"."extra""""))

    /** Main holds that table now: a merge's own commit did it, or main did. To the table checks
      * they read the same: "also created on main", which closes the proposal.
      */
    def createdOnMain(): Unit =
      mainReader.head = 11L
      mainReader.versions = mainReader.versions :+ TableVersion(3L, "tpch1", "extra", 11L, None)
      mainReader.changes = List((11L, """created_table:"tpch1"."extra""""))

    /** Runs `hook` when the merge reads the branch's head for the `nth` time, just before it reads
      * main's: 1 is the validation before the freeze, 2 the one on the frozen branch. Both come
      * after the merge's first look.
      */
    def onHeadRead(nth: Int)(hook: () => Unit): Unit =
      var reads = 0
      readers.put(
        store.findBranch(parent.id, "x").get.tenantDbName,
        new FakeReader(branchReader.head, branchReader.versions, branchReader.changes):
          override def maxSnapshotId(): Option[Long] =
            reads += 1
            if reads == nth then hook()
            Some(head)
      ): Unit

    /** The row of the proposal `id`, as stored. */
    def row(id: String): BranchMerge = store.getBranchMerge(id).get

    def status: BranchStatus = store.findBranch(parent.id, "x").get.status

    /** Two proposals: the first, replaced, and the second, open. */
    def replacedAndOpen(): (String, String) =
      create("x")
      touchBranch()
      propose().isRight shouldBe true
      val replaced = openProposal()
      propose().isRight shouldBe true
      mergeBatches.clear()
      (replaced, openProposal())

    def merge(
        name: String = "x",
        expected: Option[Long] = None,
        hold: Boolean = false,
        actor: BranchActor = admin2,
        proposal: Option[String] = None
    ) = service
      .merge(
        "acme",
        parent.name,
        name,
        proposal.getOrElse(openProposal(name)),
        expected,
        hold,
        actor,
        None
      )
      .unsafeRunSync()

  "create" should "clone at head, register the catalog under the parent alias and start one pool" in {
    val f = new Fixture(parentEncrypted = true)
    val b = f.create("feature-x", ttl = Some(24)).toOption.get
    b.status shouldBe BranchStatus.Open
    b.forkSnapshot shouldBe 10L
    b.ownerUser shouldBe "root"
    b.expiresAt shouldBe Some(Instant.parse("2026-09-21T12:00:00Z"))
    b.tenantDbName shouldBe BranchNames.tenantDbName(f.parent.name, b.id)
    b.poolName shouldBe BranchNames.poolName(b.id)
    b.dataPath shouldBe s"/tmp/qod-branch-spec/acme_tpch__br_${BranchNames.id8(b.id)}/"
    f.created.last shouldBe b.tenantDbName
    f.cloneCalls.map(_._2) shouldBe List(b.tenantDbName)
    val td = f.sup.findTenantDb("acme", b.tenantDbName).get
    td.branchOf shouldBe Some(f.parent.id)
    td.metastore(TenantDb.CatalogAliasKey) shouldBe f.parent.name
    td.metastore("dbName") shouldBe b.tenantDbName
    f.sup.get(PoolKey("acme", b.tenantDbName, b.poolName)).map(_.nodes.size) shouldBe Some(1)
    f.store.findBranch(f.parent.id, "feature-x").map(_.id) shouldBe Some(b.id)
    f.store
      .listTenantDbs(f.tenant.id)
      .find(_.branchOf.contains(f.parent.id))
      .get
      .encrypted shouldBe true
  }

  it should "refuse duplicates, bad names, branches of branches and the per-db cap" in {
    val f = new Fixture(BranchingConfig(maxPerDatabase = 2))
    f.create("a").isRight shouldBe true
    f.create("a").left.map(_.code) shouldBe Left("duplicate")
    f.create("Bad Name").left.map(_.code) shouldBe Left("invalid_name")
    val a = f.store.findBranch(f.parent.id, "a").get
    f.service
      .create("acme", a.tenantDbName, "nested", None, None, f.admin1, None)
      .unsafeRunSync()
      .left
      .map(_.code) shouldBe Left("branch_of_branch_unsupported")
    f.service
      .create("acme", f.parent.name, "d", None, Some(9L), f.admin1, None)
      .unsafeRunSync()
      .left
      .map(_.code) shouldBe Left("fork_snapshot_unsupported")
    f.create("b").isRight shouldBe true
    f.create("c").left.map(_.code) shouldBe Left("branch_limit")
  }

  it should "gate non-admins on the parent's connect permission" in {
    val f = new Fixture
    f.create("x", actor = f.alice).left.map(_.code) shouldBe Left("acl_denied")
    f.grantConnect("alice")
    f.create("x", actor = f.alice).map(_.ownerUser) shouldBe Right("alice")
  }

  it should "surface an unwired OPA tenant's outage as retryable, not a permission denial" in {
    // The parent tenant is opa-mode but no OpaPoolAccess is ever wired on this bare supervisor
    // (mirrors a manager that has not finished boot): PoolSupervisor.authorizeHandshakeDetailed
    // fails closed with Unavailable rather than falling back to grants, and mayUse must not
    // collapse that into the same "acl_denied" a real policy refusal gets.
    val f = new Fixture(tenantAcl = TenantAcl(Some("opa"), Some("http://opa")))
    f.grantConnect("alice")
    val err = f.create("x", actor = f.alice).left.toOption.get
    err.code shouldBe "authz_unavailable"
    err.status shouldBe 503
  }

  it should "bind a non-admin actor to the parent tenant's own row, never a same-named superuser row" in {
    val f     = new Fixture
    val carol = BranchActor.plain("carol", sees = (_, _) => true)
    // A superuser row named carol; the tenant holds no carol row yet.
    f.store.upsertUserIdentity(
      RbacUser(id = "u-su-carol", tenant = None, username = "carol", kind = "admin")
    )
    f.create("x", actor = carol).left.map(_.code) shouldBe Left("acl_denied")
    // The tenant's own row binds once it exists and holds the connect grant.
    f.grantConnect("carol")
    f.create("x", actor = carol).map(_.ownerUser) shouldBe Right("carol")
  }

  it should "roll back the database when the clone fails and leave no rows behind" in {
    val f = new Fixture
    f.cloneFails = true
    f.create("x").left.map(_.code) shouldBe Left("branch_create_failed")
    f.dropped shouldBe List(f.created.last)
    f.store.listBranches(f.parent.id) shouldBe Nil
    f.sup.listTenantDbsByTenant("acme").map(_.name) shouldBe List(f.parent.name)
  }

  "propose" should "record a merge request with the change set and move the branch to proposed" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    val (b, m, cs) =
      f.service
        .propose("acme", f.parent.name, "x", ProposeOptions(), f.admin1, Proposer, None)
        .unsafeRunSync()
        .toOption
        .get
    b.status shouldBe BranchStatus.Proposed
    m.status shouldBe BranchMergeStatus.Proposed
    m.proposer shouldBe "root"
    m.mainSnapshotAtPropose shouldBe 10L
    m.headSnapshotAtPropose shouldBe Some(12L)
    cs.tables.map(t => (t.table, t.kind, t.inserted, t.deleted, t.updated)) shouldBe
      List(("region", ChangeKind.Modified, 3L, 1L, 2L))
  }

  it should "run the analysis on a node and store its counts, writing nothing to either catalog" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.countsRow = List(5L, 2L, 1L, 3L, 0L, 0L, 0L, 1L, 9L, 0L, 3L)
    val (_, m, _) = f.service
      .propose("acme", f.parent.name, "x", ProposeOptions(), f.admin1, Proposer, None)
      .unsafeRunSync()
      .toOption
      .get
    f.nodesStarted shouldBe 1
    f.nodeBatches.exists(_.startsWith("BEGIN")) shouldBe false
    val stored = RowCounts.listFromJson(m.rowConflictsJson).toOption.get
    stored.map(c => (c.table, c.toUpdate, c.toDelete, c.toInsert, c.cellConflicts)) shouldBe
      List(("region", 5L, 2L, 1L, 3L))
    stored.head.columnConflicts shouldBe Map("x" -> 3L)
  }

  it should "start no node when nothing merges by rows" in {
    val f = new Fixture
    f.create("x") // no branch work
    f.service
      .propose("acme", f.parent.name, "x", ProposeOptions(), f.admin1, Proposer, None)
      .unsafeRunSync()
      .isRight shouldBe true
    f.nodesStarted shouldBe 0
  }

  it should "record conflicts rather than refuse them" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.countsRow = List(0L, 0L, 0L, 3L, 0L, 0L, 0L, 0L, 0L, 0L, 3L)
    f.service
      .propose("acme", f.parent.name, "x", ProposeOptions(), f.admin1, Proposer, None)
      .unsafeRunSync()
      .isRight shouldBe true
  }

  it should "store the policy and keys, and refuse a key the change set cannot use" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    val refused = f.service
      .propose(
        "acme",
        f.parent.name,
        "x",
        ProposeOptions(keys = Map("tpch1.region" -> List("nope"))),
        f.admin1,
        Proposer,
        None
      )
      .unsafeRunSync()
    refused.left.map(_.code) shouldBe Left("invalid_key")
    f.service
      .propose(
        "acme",
        f.parent.name,
        "x",
        ProposeOptions(keys = Map("tpch1.other" -> List("x"))),
        f.admin1,
        Proposer,
        None
      )
      .unsafeRunSync()
      .left
      .map(_.code) shouldBe Left("invalid_key")
    val (_, m, _) = f.service
      .propose(
        "acme",
        f.parent.name,
        "x",
        ProposeOptions(Some(ConflictPolicy.Branch), Map("tpch1.region" -> List("x"))),
        f.admin1,
        Proposer,
        None
      )
      .unsafeRunSync()
      .toOption
      .get
    m.onConflict shouldBe Some("branch")
    m.mergeKeysJson shouldBe """{"tpch1.region":["x"]}"""
  }

  it should "replace an open proposal and abandon the old one" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    val first = f.service
      .propose("acme", f.parent.name, "x", ProposeOptions(), f.admin1, Proposer, None)
      .unsafeRunSync()
      .toOption
      .get
      ._2
    val second = f.service
      .propose(
        "acme",
        f.parent.name,
        "x",
        ProposeOptions(Some(ConflictPolicy.Main)),
        f.admin2,
        Proposer,
        None
      )
      .unsafeRunSync()
      .toOption
      .get
      ._2
    val b = f.store.findBranch(f.parent.id, "x").get
    b.status shouldBe BranchStatus.Proposed
    val statuses = f.store.listBranchMerges(b.id).map(m => m.id -> m.status).toMap
    statuses(first.id) shouldBe BranchMergeStatus.Abandoned
    statuses(second.id) shouldBe BranchMergeStatus.Proposed
    second.proposer shouldBe "boss"
    f.store.getBranchMerge(first.id).flatMap(_.decidedAt) shouldBe
      Some(Instant.parse("2026-09-20T12:00:00Z"))
  }

  it should "refuse a gap in main's history since the fork, naming main" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.mainReader.head = 13L
    f.mainReader.changes = List((11L, ""), (13L, "inlined_insert:2"))
    val refused = f.propose().left.toOption.get
    (refused.status, refused.code) shouldBe (409, "history_gap")
    refused.message shouldBe "main's snapshot 12, at or after the fork, is gone, so its changes " +
      "cannot be read; discard this branch and create it again"
  }

  it should "refuse the fork itself gone from main, before any node" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.mainReader.forkRow = false
    val refused = f.propose().left.toOption.get
    refused.code shouldBe "history_gap"
    refused.message should startWith("main's snapshot 10, at or after the fork, is gone")
    f.nodesStarted shouldBe 0
  }

  it should "refuse a gap in the branch's history since the fork, naming the branch" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.branchReader.head = 13L
    f.branchReader.changes = List((11L, "inlined_insert:2"), (13L, "inlined_delete:2"))
    val refused = f.propose().left.toOption.get
    (refused.status, refused.code) shouldBe (409, "history_gap")
    refused.message shouldBe "the branch's snapshot 12, at or after the fork, is gone, so its " +
      "changes cannot be read; discard this branch and create it again"
    f.nodesStarted shouldBe 0
    f.store.listBranchMerges(f.store.findBranch(f.parent.id, "x").get.id) shouldBe Nil
  }

  it should "refuse the fork itself gone from the branch, before any node" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.branchReader.forkRow = false
    val refused = f.propose().left.toOption.get
    refused.code shouldBe "history_gap"
    refused.message should startWith("the branch's snapshot 10, at or after the fork, is gone")
    f.nodesStarted shouldBe 0
  }

  it should "refuse a table that only a snapshot gone from the branch touched, not leave it out" in {
    val f = new Fixture
    f.create("x")
    // The branch created `extra` at 11 and changed region at 12. Expiry took snapshot 11, the
    // only trace of `extra`: the change set lists region alone, and would merge without it.
    f.branchReader.head = 12L
    f.branchReader.versions =
      f.branchReader.versions :+ TableVersion(3L, "tpch1", "extra", 11L, None)
    f.branchReader.changes = List((12L, "inlined_insert:2"))
    f.propose().left.map(_.code) shouldBe Left("history_gap")
  }

  it should "refuse a gap when the branch only created a table and nothing merges by rows" in {
    val f = new Fixture
    f.create("x")
    // `extra` is all that is left on the branch: 11 and 12, which changed region, are gone.
    f.branchReader.head = 13L
    f.branchReader.versions =
      f.branchReader.versions :+ TableVersion(3L, "tpch1", "extra", 13L, None)
    f.branchReader.changes = List((13L, """created_table:"tpch1"."extra""""))
    f.propose().left.map(_.code) shouldBe Left("history_gap")
    f.nodesStarted shouldBe 0
  }

  it should "refuse a gap whatever else rules the merge out, recording nothing" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    // main dropped the table the branch changed, so the merge is ruled out anyway, and main's
    // history has a hole: the conflict it would record is read from a history that is not whole.
    f.mainReader.head = 13L
    f.mainReader.versions = List(TableVersion(2L, "tpch1", "region", 1L, Some(11L)))
    f.mainReader.changes = List((11L, "dropped_table:2"), (13L, ""))
    f.propose().left.map(_.code) shouldBe Left("history_gap")
    f.store.listBranchMerges(f.store.findBranch(f.parent.id, "x").get.id) shouldBe Nil
  }

  it should "refuse that gap whatever the branch changed, before any node" in {
    val f = new Fixture
    f.create("x") // no branch work: nothing merges by rows
    f.mainReader.head = 13L
    f.mainReader.changes = List((11L, ""), (13L, "inlined_insert:2"))
    f.service
      .propose("acme", f.parent.name, "x", ProposeOptions(), f.admin1, Proposer, None)
      .unsafeRunSync()
      .left
      .map(_.code) shouldBe Left("history_gap")
    f.nodesStarted shouldBe 0
  }

  it should "refuse when the branch ended during the analysis, recording nothing" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    val b = f.store.findBranch(f.parent.id, "x").get
    f.duringAnalysis = () => f.store.updateBranch(b.copy(status = BranchStatus.Discarded)): Unit
    f.service
      .propose("acme", f.parent.name, "x", ProposeOptions(), f.admin1, Proposer, None)
      .unsafeRunSync()
      .left
      .map(_.status) shouldBe Left(404)
    f.store.listBranchMerges(b.id) shouldBe Nil
  }

  it should "keep a quoted value out of a failed analysis's message" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.stageFails =
      Some("""Failed to execute query "INSERT INTO i VALUES ('SENTINEL')": ERROR: boom""")
    val res = f.service
      .propose("acme", f.parent.name, "x", ProposeOptions(), f.admin1, Proposer, None)
      .unsafeRunSync()
    res.left.map(_.code) shouldBe Left("merge_failed")
    res.left.toOption.get.message should not include "SENTINEL"
  }

  it should "report an analysis that failed, and record no proposal" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.nodeFails = Some("merge node spawn failed: no free port")
    f.service
      .propose("acme", f.parent.name, "x", ProposeOptions(), f.admin1, Proposer, None)
      .unsafeRunSync()
      .left
      .map(f => (f.status, f.code)) shouldBe Left((502, "merge_failed"))
    val b = f.store.findBranch(f.parent.id, "x").get
    b.status shouldBe BranchStatus.Open
    f.store.listBranchMerges(b.id) shouldBe Nil
  }

  it should "read the branch's side first, then main's, with the proposal's policy and keys" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.mainReader.head = 11L
    f.mainReader.changes = List((11L, "inlined_insert:2"))
    f.service
      .propose(
        "acme",
        f.parent.name,
        "x",
        ProposeOptions(Some(ConflictPolicy.Branch), Map("tpch1.region" -> List("x"))),
        f.admin1,
        Proposer,
        None
      )
      .unsafeRunSync()
      .isRight shouldBe true
    f.nodesStarted shouldBe 1
    val prepare = f.nodeBatches.head
    prepare should include("ducklake_table_insertions('qod_branch', 'tpch1', 'region', 11, 12)")
    prepare should include("\"acme_tpch\".\"tpch1\".\"region\" AT (VERSION => 10)")
    val decide = f.nodeBatches.tail.mkString("\n")
    decide should include("ducklake_table_insertions('acme_tpch', 'tpch1', 'region', 11, 11)")
    decide should include("action IN ('apply_delete', 'conflict_update_delete')")
    decide should include("t0_key_hits")
  }

  it should "send nothing after the first stage that fails" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.stageFails = Some("boom")
    f.service
      .propose("acme", f.parent.name, "x", ProposeOptions(), f.admin1, Proposer, None)
      .unsafeRunSync()
      .left
      .map(_.code) shouldBe Left("merge_failed")
    f.nodeBatches.size shouldBe 1
  }

  it should "start no node when a table conflict rules the merge out, and record the conflict" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    // main dropped the table the branch changed: nothing a node could read there.
    f.mainReader.head = 11L
    f.mainReader.versions = List(TableVersion(2L, "tpch1", "region", 1L, Some(11L)))
    f.mainReader.changes = List((11L, "dropped_table:2"))
    val (_, m, _) = f.service
      .propose("acme", f.parent.name, "x", ProposeOptions(), f.admin1, Proposer, None)
      .unsafeRunSync()
      .toOption
      .get
    f.nodesStarted shouldBe 0
    m.conflictsJson should include("dropped on main")
    m.rowConflictsJson shouldBe "[]"
  }

  it should "start no node for a table with a reserved column, whose plan would throw" in {
    val f = new Fixture
    val b = f.create("x").toOption.get
    f.readers.put(
      b.tenantDbName,
      new FakeReader(11L, f.branchReader.versions, List((11L, "inlined_insert:2"))):
        override def columnsOfTableAt(id: Long, n: Long): List[CatalogColumnEntry] =
          List(CatalogColumnEntry(0, "rowid", "BIGINT", true, false))
    )
    f.service
      .propose("acme", f.parent.name, "x", ProposeOptions(), f.admin1, Proposer, None)
      .unsafeRunSync()
      .isRight shouldBe true
    f.nodesStarted shouldBe 0
  }

  it should "refuse a snapshot mixing maintenance and data, before any node" in {
    val f = new Fixture
    f.create("x")
    f.branchReader.head = 12L
    f.branchReader.changes =
      List((11L, "merge_adjacent:2,inlined_insert:2"), (12L, "inlined_insert:2"))
    f.service
      .propose("acme", f.parent.name, "x", ProposeOptions(), f.admin1, Proposer, None)
      .unsafeRunSync()
      .left
      .map(f => (f.status, f.code)) shouldBe Left((422, "merge_unsupported"))
    f.nodesStarted shouldBe 0
  }

  it should "hold the merge lock through the analysis, and release it" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    val lock               = PoolKey("acme", f.parent.name, "__merge")
    def free               = f.sup.poolLocks.tryWithLock(lock)(IO.unit).unsafeRunSync().isDefined
    var freeDuringAnalysis = Option.empty[Boolean]
    f.duringAnalysis = () => freeDuringAnalysis = Some(free)
    f.service
      .propose("acme", f.parent.name, "x", ProposeOptions(), f.admin1, Proposer, None)
      .unsafeRunSync()
      .isRight shouldBe true
    freeDuringAnalysis shouldBe Some(false)
    free shouldBe true
  }

  it should "keep the open proposal when the next one fails" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    val first = f.service
      .propose("acme", f.parent.name, "x", ProposeOptions(), f.admin1, Proposer, None)
      .unsafeRunSync()
      .toOption
      .get
      ._2
    f.stageFails = Some("boom")
    f.service
      .propose("acme", f.parent.name, "x", ProposeOptions(), f.admin2, Proposer, None)
      .unsafeRunSync()
      .left
      .map(_.code) shouldBe Left("merge_failed")
    val b = f.store.findBranch(f.parent.id, "x").get
    b.status shouldBe BranchStatus.Proposed
    f.store.listBranchMerges(b.id).map(m => (m.id, m.status)) shouldBe
      List((first.id, BranchMergeStatus.Proposed))
  }

  "a non-admin" should "propose only a branch they own, and start nothing on anyone else's" in {
    val f = new Fixture
    f.grantConnect("alice")
    f.grantConnect("bob")
    val bob = BranchActor.plain("bob", sees = (_, _) => true)
    f.create("x", actor = f.alice).isRight shouldBe true
    f.touchBranch()
    val refused = f.propose(actor = bob).left.toOption.get
    (refused.status, refused.code) shouldBe (403, "not_owner")
    refused.message should include("alice")
    f.nodesStarted shouldBe 0
    f.openProposal() shouldBe "none"
    f.status shouldBe BranchStatus.Open
    f.propose(actor = f.alice).isRight shouldBe true
    // An admin may propose anyone's, and that replaces it.
    f.propose(actor = f.admin1).map(_._2.proposer) shouldBe Right("root")
  }

  /** The TTL refusal of a non-admin's create, with the words it says it in. */
  private def ttlRefusal(f: Fixture, name: String, ttl: Option[Int]) =
    f.create(name, actor = f.alice, ttl = ttl).left.map(e => (e.status, e.code, e.message))

  it should "ask for no forever: from one hour to the server's default, named in the refusal" in {
    val f = new Fixture(BranchingConfig(defaultTtlHours = 48))
    f.grantConnect("alice")
    List(Some(0), Some(-1), Some(49), Some(Int.MaxValue)).foreach { ttl =>
      withClue(s"ttlHours $ttl: ") {
        val refused = ttlRefusal(f, "x", ttl).left.toOption.get
        (refused._1, refused._2) shouldBe (400, "invalid_ttl")
        refused._3 should include("48")
      }
    }
    f.store.listBranches(f.parent.id) shouldBe Nil
    f.cloneCalls shouldBe empty
    val in1h = f.create("low", actor = f.alice, ttl = Some(1)).toOption.get
    in1h.expiresAt shouldBe Some(Instant.parse("2026-09-20T13:00:00Z"))
    val at = f.create("high", actor = f.alice, ttl = Some(48)).toOption.get
    at.expiresAt shouldBe Some(Instant.parse("2026-09-22T12:00:00Z"))
    // Asking for nothing takes the server's default, as for anyone.
    val byDefault = f.create("plain", actor = f.alice, ttl = None).toOption.get
    byDefault.expiresAt shouldBe Some(Instant.parse("2026-09-22T12:00:00Z"))
  }

  it should "take the operator's default of no TTL when asking for nothing, and still ask for no forever" in {
    val f = new Fixture(BranchingConfig(defaultTtlHours = 0))
    f.grantConnect("alice")
    // The operator's own choice: nothing asked, nothing set.
    f.create("plain", actor = f.alice, ttl = None).toOption.get.expiresAt shouldBe None
    val refused = ttlRefusal(f, "x", Some(0)).left.toOption.get
    (refused._1, refused._2) shouldBe (400, "invalid_ttl")
    refused._3 should include("at least 1")
    // With no default there is no upper limit to name.
    f.create("long", actor = f.alice, ttl = Some(100000)).isRight shouldBe true
  }

  it should "find fromSnapshot still reserved" in {
    val f = new Fixture
    f.grantConnect("alice")
    f.service
      .create("acme", f.parent.name, "x", Some(24), Some(9L), f.alice, None)
      .unsafeRunSync()
      .left
      .map(e => (e.status, e.code)) shouldBe Left((409, "fork_snapshot_unsupported"))
    f.store.listBranches(f.parent.id) shouldBe Nil
    f.dropped shouldBe List(f.created.last)
  }

  "an admin's create" should "ask for any TTL, none included, as before" in {
    val f = new Fixture(BranchingConfig(defaultTtlHours = 48))
    f.create("never", ttl = Some(0)).toOption.get.expiresAt shouldBe None
    f.create("long", ttl = Some(100000)).isRight shouldBe true
    f.create("bad", ttl = Some(-1)).left.map(e => (e.code, e.message)) shouldBe
      Left(("invalid_ttl", "ttlHours must be >= 0"))
  }

  "a table a non-admin does not see" should "never be counted as them, in changes or in a proposal's reply" in {
    val f = new Fixture
    f.grantConnect("alice")
    val blind = BranchActor.plain("alice", sees = (_, _) => false)
    f.create("x", actor = blind).isRight shouldBe true
    f.touchBranch()
    val asAlice = ai.starlake.quack.ondemand.api.ExecCaller.unrestricted("c", "alice", false)
    val (_, cs) = f.service
      .changes("acme", f.parent.name, "x", Some(asAlice), blind, None)
      .unsafeRunSync()
      .toOption
      .get
    cs.tables.map(t => (t.table, t.countsUnavailable)) shouldBe List(("region", true))
    f.countedAs shouldBe empty
    // The stored summary still counts as the system; the proposer's reply asks nothing as them.
    val (_, _, reply) = f.service
      .propose("acme", f.parent.name, "x", ProposeOptions(), blind, asAlice, None)
      .unsafeRunSync()
      .toOption
      .get
    reply.tables.map(t => (t.table, t.countsUnavailable)) shouldBe List(("region", true))
    f.countedAs.map(_._1).toList.distinct should not contain "alice"
    f.countedAs.map(_._2) should contain("region")
  }

  it should "still be counted for an actor who sees it" in {
    val f = new Fixture
    f.grantConnect("alice")
    f.create("x", actor = f.alice).isRight shouldBe true
    f.touchBranch()
    val asAlice = ai.starlake.quack.ondemand.api.ExecCaller.unrestricted("c", "alice", false)
    f.service
      .changes("acme", f.parent.name, "x", Some(asAlice), f.alice, None)
      .unsafeRunSync()
      .isRight shouldBe true
    f.countedAs.toList shouldBe List(("alice", "region"))
  }

  it should "answer a key on it as a table not in the change set, so its columns cannot be probed" in {
    val f = new Fixture
    f.grantConnect("alice")
    val blind = BranchActor.plain("alice", sees = (_, t) => t != "region")
    f.create("x", actor = blind).isRight shouldBe true
    f.touchBranch()
    def refusal(key: String, column: String, actor: BranchActor = blind) = f
      .propose(options = ProposeOptions(keys = Map(key -> List(column))), actor = actor)
      .left
      .map(e => (e.status, e.code, e.message))
    // A column the table has (`x`), one it lacks: the same words as a table not in the change set.
    val notChanged         = refusal("tpch1.other", "x")
    def asOther(t: String) = notChanged.left.map((s, c, m) => (s, c, m.replace("tpch1.other", t)))
    notChanged.left.map(_._2) shouldBe Left("invalid_key")
    refusal("tpch1.region", "x") shouldBe asOther("tpch1.region")
    refusal("tpch1.region", "nope") shouldBe asOther("tpch1.region")
    // An actor who sees it gets the column answer.
    refusal("tpch1.region", "nope", f.admin1).left.map(_._3) shouldBe
      Left("key on 'tpch1.region': no column named 'nope'")
    f.nodesStarted shouldBe 0
  }

  "merge" should "refuse the proposer, non-admins and unproposed branches" in {
    val f = new Fixture
    f.grantConnect("alice")
    f.create("x", actor = f.alice)
    f.touchBranch()
    f.merge(actor = f.admin1).left.map(_.code) shouldBe Left("not_proposed")
    f.propose(actor = f.alice).isRight shouldBe true
    f.merge(actor = f.alice).left.map(_.code) shouldBe Left("admin_required")
    f.merge(actor = BranchActor.admin("alice")).left.map(_.code) shouldBe
      Left("self_merge_forbidden")
  }

  it should "refuse a branch-only token's merge (it writes main), even for an admin" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val refused = f.merge(actor = BranchActor.admin("boss", branchOnly = true))
    refused.left.map(f => (f.status, f.code)) shouldBe Left((403, "branch_only_token"))
    f.nodesStarted shouldBe 1 // the proposal's analysis only
    f.merge().isRight shouldBe true
  }

  it should "merge, tag the new snapshot and tear the branch down" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val (b, m, _) = f.merge(expected = Some(10L)).toOption.get
    b.status shouldBe BranchStatus.Merged
    m.status shouldBe BranchMergeStatus.Merged
    m.approver shouldBe Some("boss")
    m.mainSnapshotAfter shouldBe Some(11L)
    m.attempts shouldBe 1
    m.tagName shouldBe Some(s"merge-x-${m.id.stripPrefix("mg-").take(8)}")
    f.store.findSnapshotTag("acme", f.parent.name, m.tagName.get).map(_.snapshotId) shouldBe
      Some(11L)
    f.mergeBatches.size shouldBe 1
    f.mergeBatches.head should startWith("BEGIN;")
    f.mergeBatches.head should include(
      "CALL ducklake_set_commit_message('acme_tpch', 'tenant:acme/user:boss'"
    )
    f.mergeBatches.head should include("\"acme_tpch\".\"tpch1\".\"region\"")
    // Fenced to the validated snapshot, on the merged tables, then unfenced; no hold unless asked.
    val message = BranchMergeSql.commitMessage("x", m.id, "root")
    f.fenceArms.map(a => (a._2, a._3, a._4, a._5)).toList shouldBe
      List((message, 10L, Set(2L), None))
    f.fenceDisarms.toList shouldBe List(message)
    // teardown: pool and catalog gone, files purged, history row kept
    f.sup.get(PoolKey("acme", b.tenantDbName, b.poolName)) shouldBe None
    f.sup.findTenantDb("acme", b.tenantDbName) shouldBe None
    f.dropped should contain(b.tenantDbName)
    f.purged shouldBe List(b.dataPath)
    f.store.getBranch(b.id).flatMap(_.purgedAt).isDefined shouldBe true
    f.store.findBranch(f.parent.id, "x") shouldBe None
    f.store.findBranch(f.parent.id, "x", liveOnly = false).map(_.status) shouldBe
      Some(BranchStatus.Merged)
  }

  it should "merge a table main changed too" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.mainReader.head = 11L
    f.mainReader.changes = List((11L, "inlined_insert:2"))
    f.propose().isRight shouldBe true
    f.merge().isRight shouldBe true
    f.nodeBatches.mkString("\n") should include("ducklake_table_insertions('acme_tpch'")
  }

  it should "prepare once, whatever the number of attempts" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    f.nodeBatches.clear()
    f.applyScript = List("qod merge fence: a merged table moved past snapshot 10")
    f.merge().isRight shouldBe true
    f.nodeBatches.count(_.contains("CREATE SCHEMA IF NOT EXISTS")) shouldBe 1
    f.mergeBatches.size shouldBe 2 // the failed Apply, and the one that committed
  }

  it should "retry when main moved, re-arming the fence at main's new head each time" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    f.mergeBatches.clear()
    // Main commits on another table while the first attempt runs.
    var moved = false
    f.beforeRun = () =>
      if !moved then
        moved = true
        f.mainReader.head = 11L
        f.mainReader.changes = List((11L, "inlined_insert:9"))
    f.applyScript = List("qod merge fence: a merged table moved past snapshot 10")
    val (_, m, _) = f.merge().toOption.get
    m.attempts shouldBe 2
    f.fenceArms.map(_._3).toList shouldBe List(10L, 11L)
    f.fenceDisarms.size shouldBe 2
  }

  it should "also retry on DuckLake's own table-level conflict" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    f.applyScript = List("Transaction conflict - attempting to delete from table with index \"2\"")
    f.merge().map(_._2.attempts) shouldBe Right(2)
  }

  it should "stop after the last attempt, leave the proposal open, and suggest the hold" in {
    val f = new Fixture(BranchingConfig(mergeMaxAttempts = 3))
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    f.applyScript = List.fill(3)("qod merge fence: a merged table moved past snapshot 10")
    val res = f.merge()
    res.left.map(_.code) shouldBe Left("concurrent_write")
    res.left.toOption.get.message should include("--hold-writers")
    val b = f.store.findBranch(f.parent.id, "x").get
    b.status shouldBe BranchStatus.Proposed
    val row = f.store.listBranchMerges(b.id).head
    row.status shouldBe BranchMergeStatus.Proposed
    row.attempts shouldBe 3
    f.sup.get(PoolKey("acme", b.tenantDbName, b.poolName)).map(_.disabled) shouldBe Some(false)
    // The approver can retry at once, with the hold, without a new proposal.
    val (_, merged, _) = f.merge(hold = true).toOption.get
    merged.holdWriters shouldBe true
    merged.error shouldBe None
    f.fenceArms.last._5 shouldBe Some(600)
  }

  it should "hold writers before reading main, then fence at what it read" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    // A commit already past the hold lands as it goes up: the fence must start after it.
    f.onArm = () =>
      if f.fenceArms.size == 1 then
        f.mainReader.head = 11L
        f.mainReader.changes = List((11L, "inlined_insert:9"))
    f.merge(hold = true).map(_._2.attempts) shouldBe Right(1)
    f.fenceArms.map(a => (a._3, a._5)).toList shouldBe List((10L, Some(600)), (11L, Some(600)))
    f.fenceDisarms.size shouldBe 1
  }

  it should "keep the hold up between attempts: each release lets in another stale writer" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    f.applyScript = List("qod merge fence: a merged table moved past snapshot 10")
    f.merge(hold = true).map(_._2.attempts) shouldBe Right(2)
    f.fenceArms.size shouldBe 3    // the hold, then one at main's head per attempt
    f.fenceDisarms.size shouldBe 1 // once, when the node is gone
  }

  it should "not merge when the hold cannot be set, leaving the proposal open" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    f.fenceFails = Some("cannot reach the catalog")
    f.merge(hold = true).left.map(_.code) shouldBe Left("merge_failed")
    f.fenceArms.size shouldBe 1
    f.mergeBatches shouldBe empty
    f.store.findBranch(f.parent.id, "x").get.status shouldBe BranchStatus.Proposed
  }

  it should "split the update after an out-of-memory failure, once" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    f.mergeBatches.clear()
    f.applyScript = List("Out of Memory Error: failed to allocate data of size 1.0 GiB")
    f.merge().map(_._2.attempts) shouldBe Right(2)
    f.mergeBatches.head should not include "t.rowid >= "
    f.mergeBatches(1) should include("t.rowid >= ")
  }

  it should "fail on a second out-of-memory, closing the proposal" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    f.applyScript = List.fill(2)("Out of Memory Error: failed to allocate data of size 1.0 GiB")
    val res = f.merge()
    res.left.map(_.code) shouldBe Left("merge_failed")
    res.left.toOption.get.message should include("needs more memory")
    f.store.findBranch(f.parent.id, "x").get.status shouldBe BranchStatus.Open
  }

  it should "say the node needs more memory when Prepare or Decide runs out, closing the proposal" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    f.stageFails = Some("Out of Memory Error: failed to allocate data of size 1.0 GiB")
    val res = f.merge()
    res.left.map(_.code) shouldBe Left("merge_failed")
    res.left.toOption.get.message should include("needs more memory")
    f.nodeBatches.count(_.startsWith("BEGIN")) shouldBe 0
    f.store.findBranch(f.parent.id, "x").get.status shouldBe BranchStatus.Open
  }

  it should "store no quoted value of a failed attempt" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val b = f.store.findBranch(f.parent.id, "x").get
    f.applyScript = List(
      """Failed to commit: Failed to execute query "INSERT INTO i VALUES ('SENTINEL')": boom"""
    )
    val res = f.merge()
    res.left.map(_.code) shouldBe Left("merge_failed")
    res.left.toOption.get.message should not include "SENTINEL"
    f.store.listBranchMerges(b.id).head.error.get should not include "SENTINEL"
  }

  it should "refuse a head that moves between the first validation and the freeze" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    var reads  = 0
    val moving = new FakeReader(12L, f.branchReader.versions, f.branchReader.changes):
      override def maxSnapshotId(): Option[Long] =
        reads += 1
        Some(if reads == 1 then 12L else 13L)
    f.readers.put(f.store.findBranch(f.parent.id, "x").get.tenantDbName, moving)
    val started = f.nodesStarted
    f.merge().left.map(_.code) shouldBe Left("proposal_stale")
    f.nodesStarted shouldBe started // refused on the frozen branch, before any node
    f.nodeBatches.count(_.startsWith("BEGIN")) shouldBe 0
  }

  it should "close the proposal when main's history has a gap at merge time" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    f.mainReader.head = 13L
    f.mainReader.changes = List((11L, ""), (13L, ""))
    f.merge().left.map(_.code) shouldBe Left("history_gap")
    f.store.findBranch(f.parent.id, "x").get.status shouldBe BranchStatus.Open
  }

  it should "close the proposal when the branch's history has a gap at merge time, writing nothing" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    // Snapshot 11 expires on the branch: region is still a modified table (12 touches it), so
    // nothing but the gap check can tell that the change set is not the branch's.
    f.branchReader.changes = List((12L, "inlined_delete:2"))
    val refused = f.merge().left.toOption.get
    refused.code shouldBe "history_gap"
    refused.message should startWith("the branch's snapshot 11, at or after the fork, is gone")
    f.mergeBatches shouldBe empty
    f.store.findBranch(f.parent.id, "x").get.status shouldBe BranchStatus.Open
  }

  it should "refuse, not merge, a branch that created a table once its other touch is gone" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.createdOnBranch() // region changed at 11 and 12, `extra` created at 13
    f.propose().isRight shouldBe true
    val stages = f.nodeBatches.size
    // 11 and 12 expire on the branch: what is left is `extra` alone, a change set with nothing to
    // read by rows, which would merge and drop the changes to region without a word.
    f.branchReader.changes = List((13L, """created_table:"tpch1"."extra""""))
    f.merge().left.map(_.code) shouldBe Left("history_gap")
    f.mergeBatches shouldBe empty
    f.nodeBatches.size shouldBe stages // refused before Prepare
    f.store.findBranch(f.parent.id, "x").get.status shouldBe BranchStatus.Open
  }

  it should "refuse at merge time the fork itself gone from either catalog" in {
    def refused(expire: Fixture => Unit): Unit =
      val f = new Fixture
      f.create("x")
      f.touchBranch()
      f.propose().isRight shouldBe true
      expire(f)
      f.merge().left.map(_.code) shouldBe Left("history_gap")
      f.mergeBatches shouldBe empty
    refused(f => f.mainReader.forkRow = false)
    refused(f => f.branchReader.forkRow = false)
  }

  it should "keep the proposal open when a snapshot mixes maintenance and data" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    f.mainReader.head = 11L
    f.mainReader.changes = List((11L, "rewrite_delete:2,inserted_into_table:2"))
    f.merge().left.map(_.code) shouldBe Left("merge_unsupported")
    f.store.findBranch(f.parent.id, "x").get.status shouldBe BranchStatus.Proposed
  }

  it should "read a proposal's keys, and refuse what it cannot read" in {
    BranchService.keysOf("""{"s.t":["k"]}""") shouldBe Right(Map("s.t" -> List("k")))
    BranchService.keysOf("not json").isLeft shouldBe true
  }

  it should "refuse row conflicts without a policy, closing the proposal" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.countsRow = List(1L, 0L, 0L, 3L, 0L, 0L, 0L, 1L, 1L, 0L, 3L)
    f.propose().isRight shouldBe true
    f.mergeBatches.clear()
    val res = f.merge()
    res.left.map(_.code) shouldBe Left("merge_conflict")
    res.left.toOption.get.message should include("tpch1.region")
    f.mergeBatches shouldBe empty
    val b = f.store.findBranch(f.parent.id, "x").get
    b.status shouldBe BranchStatus.Open
    f.store.listBranchMerges(b.id).map(_.status) shouldBe List(BranchMergeStatus.Failed)
  }

  it should "merge conflicts under a policy, up to what the proposal showed" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.countsRow = List(1L, 0L, 0L, 3L, 0L, 0L, 0L, 1L, 1L, 0L, 3L)
    f.propose(options = ProposeOptions(Some(ConflictPolicy.Branch))).isRight shouldBe true
    f.merge().isRight shouldBe true
  }

  it should "refuse more conflicts than the proposal showed, closing the proposal" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.countsRow = List(1L, 0L, 0L, 3L, 0L, 0L, 0L, 1L, 1L, 0L, 3L)
    f.propose(options = ProposeOptions(Some(ConflictPolicy.Main))).isRight shouldBe true
    f.countsRow = List(1L, 0L, 0L, 4L, 0L, 0L, 0L, 1L, 1L, 0L, 4L)
    f.merge().left.map(_.code) shouldBe Left("conflicts_changed")
    f.store.findBranch(f.parent.id, "x").get.status shouldBe BranchStatus.Open
  }

  it should "refuse a table conflict that appears after the proposal, closing it" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    f.mainReader.head = 11L
    f.mainReader.versions = List(TableVersion(2L, "tpch1", "region", 1L, Some(11L)))
    f.mainReader.changes = List((11L, "dropped_table:2"))
    val res = f.merge()
    res.left.map(_.code) shouldBe Left("merge_conflict")
    res.left.toOption.get.message should include("tpch1.region")
    f.mergeBatches shouldBe empty
    f.store.findBranch(f.parent.id, "x").get.status shouldBe BranchStatus.Open
  }

  it should "refuse a moved main under --expect-main, leaving the proposal open" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    f.merge(expected = Some(99L)).left.map(_.code) shouldBe Left("concurrent_write")
    f.store.findBranch(f.parent.id, "x").get.status shouldBe BranchStatus.Proposed
  }

  it should "refuse a head that moved since the proposal, closing it" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    f.branchReader.head = 13L
    f.branchReader.changes = f.branchReader.changes :+ ((13L, "inlined_insert:2"))
    f.merge().left.map(_.code) shouldBe Left("proposal_stale")
    f.nodeBatches.count(_.startsWith("BEGIN")) shouldBe 0
    f.store.findBranch(f.parent.id, "x").get.status shouldBe BranchStatus.Open
  }

  it should "treat a proposal from before this change as stale" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val b = f.store.findBranch(f.parent.id, "x").get
    val m = f.store.listBranchMerges(b.id).head
    f.store.createBranchMerge(m.copy(id = "mg-old", headSnapshotAtPropose = None))
    f.store.updateBranchMerge(m.copy(status = BranchMergeStatus.Abandoned))
    f.merge().left.map(_.code) shouldBe Left("proposal_stale")
  }

  it should "freeze the branch's pool while the merge runs" in {
    val f = new Fixture
    val b = f.create("x").toOption.get
    f.touchBranch()
    f.propose().isRight shouldBe true
    val key       = PoolKey("acme", b.tenantDbName, b.poolName)
    var duringRun = Option.empty[(Boolean, Boolean, Int)]
    f.beforeRun =
      () => duringRun = f.sup.get(key).map(st => (st.disabled, st.suspended, st.nodes.size))
    f.merge().isRight shouldBe true
    // Disabled (no new connection, no wake on a query) and suspended (no node left to write).
    duringRun shouldBe Some((true, true, 0))
  }

  it should "put the branch's pool back as it was when the merge does not complete" in {
    val f = new Fixture
    val b = f.create("x").toOption.get
    f.touchBranch()
    f.propose().isRight shouldBe true
    val key = PoolKey("acme", b.tenantDbName, b.poolName)
    f.mergeFails = Some("Binder Error: something else")
    f.merge().left.map(_.code) shouldBe Left("merge_failed")
    f.sup.get(key).map(st => (st.disabled, st.suspended, st.nodes.size)) shouldBe
      Some((false, false, 1))
    f.store.findBranch(f.parent.id, "x").get.status shouldBe BranchStatus.Open
  }

  it should "leave the branch's pool untouched when the merge is refused before it runs" in {
    val f = new Fixture
    val b = f.create("x").toOption.get
    f.propose().isRight shouldBe true
    val key    = PoolKey("acme", b.tenantDbName, b.poolName)
    val before = f.sup.get(key).map(_.nodes.map(_.nodeId))
    // No branch work: nothing_to_merge, decided before any freeze.
    f.merge().left.map(_.code) shouldBe Left("nothing_to_merge")
    f.sup.get(key).map(_.nodes.map(_.nodeId)) shouldBe before
    f.sup.get(key).map(st => (st.disabled, st.suspended)) shouldBe Some((false, false))
  }

  it should "say there is nothing to merge when every change is already on main" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.countsRow = List.fill(11)(0L)
    f.propose().isRight shouldBe true
    f.mergeBatches.clear()
    val refused = f.merge().left.toOption.get
    refused.code shouldBe "nothing_to_merge"
    // True under `main` too, where what is not written lost to main rather than being there already.
    refused.message shouldBe "nothing to write: every change on the branch is already on main, " +
      "or loses to main under the proposal's policy; discard it"
    f.mergeBatches shouldBe empty
    f.store.findBranch(f.parent.id, "x").get.status shouldBe BranchStatus.Proposed
  }

  it should "not send Apply when the merge fence cannot be armed" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    f.mergeBatches.clear()
    f.fenceFails = Some("cannot arm the merge fence: connection refused")
    f.merge().left.map(_.code) shouldBe Left("merge_failed")
    f.mergeBatches shouldBe empty
    f.store.findBranch(f.parent.id, "x").get.status shouldBe BranchStatus.Proposed
  }

  it should "finish a commit that landed and never settled, never applying it again" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val id      = f.store.listBranchMerges(f.store.findBranch(f.parent.id, "x").get.id).head.id
    val started = f.nodesStarted
    // The manager died between the commit and its record: the next merge finds it on main.
    f.mainReader.committed.put(BranchMergeSql.commitMessage("x", id, "root"), 11L)
    f.merge().map(_._2.mainSnapshotAfter) shouldBe Right(Some(11L))
    f.nodesStarted shouldBe started
    f.mergeBatches shouldBe empty
  }

  it should "take a commit that lands after the attempt's look, once the node is gone" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val id      = f.store.listBranchMerges(f.store.findBranch(f.parent.id, "x").get.id).head.id
    val message = BranchMergeSql.commitMessage("x", id, "root")
    // The send timed out and the batch went on committing until the node stopped.
    f.mergeFails = Some("timeout: the merge node did not answer within 600s")
    f.afterNode = () => f.mainReader.committed.put(message, 11L)
    f.merge().map(_._2.mainSnapshotAfter) shouldBe Right(Some(11L))
  }

  it should "leave a timed-out proposal open: only its own message can find a late commit" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    f.mergeFails = Some("timeout: the merge node did not answer within 600s")
    f.merge().left.map(_.code) shouldBe Left("merge_failed")
    val b   = f.store.findBranch(f.parent.id, "x").get
    val row = f.store.listBranchMerges(b.id).head
    b.status shouldBe BranchStatus.Proposed
    row.status shouldBe BranchMergeStatus.Proposed
    // The commit lands past the last look, on a node whose stop returned early: the retry settles it.
    f.mergeFails = None
    f.mergeBatches.clear()
    f.mainReader.committed.put(BranchMergeSql.commitMessage("x", row.id, "root"), 11L)
    f.merge().map(_._2.mainSnapshotAfter) shouldBe Right(Some(11L))
    f.mergeBatches shouldBe empty
  }

  it should "keep the proposal open and the fence up when the answer to COMMIT was lost" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val id = f.openProposal()
    f.mergeFails = Some(
      s"${MergeExecutor.TimedOut}the answer to COMMIT was lost, so it may have landed: connection reset"
    )
    val refused = f.merge().left.toOption.get
    refused.code shouldBe "merge_failed"
    refused.message should include("the answer to COMMIT was lost")
    f.row(id).status shouldBe BranchMergeStatus.Proposed
    f.row(id).error.get should startWith(MergeExecutor.TimedOut)
    f.status shouldBe BranchStatus.Proposed
    f.fenceDisarms shouldBe empty
    // A new proposal could not settle this commit, which may yet land.
    f.propose().left.map(_.code) shouldBe Left("proposal_unsettled")
    // It lands: the next merge settles it, and applies nothing.
    f.mergeFails = None
    f.mergeBatches.clear()
    f.land(id)
    f.merge().map(_._2.mainSnapshotAfter) shouldBe Right(Some(11L))
    f.row(id).status shouldBe BranchMergeStatus.Merged
    f.mergeBatches shouldBe empty
  }

  it should "close the proposal on a node failure that is not a timeout" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    // An engine's own text can hold the word, so only the exact prefix counts as timed out.
    f.mergeFails = Some("IO Error: connection timeout to the catalog")
    f.merge().left.map(_.code) shouldBe Left("merge_failed")
    f.store.findBranch(f.parent.id, "x").get.status shouldBe BranchStatus.Open
  }

  it should "disarm the fence only once the node is gone" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    var disarmsAtStop = -1
    f.afterNode = () => disarmsAtStop = f.fenceDisarms.size
    f.merge().isRight shouldBe true
    disarmsAtStop shouldBe 0
    f.fenceDisarms.size shouldBe 1
  }

  it should "leave the fence armed after a timed-out run, and after a retry of one: that batch can still commit" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    f.mergeFails = Some("timeout: the merge node did not answer within 600s")
    f.merge().left.map(_.code) shouldBe Left("merge_failed")
    f.fenceDisarms shouldBe empty
    f.mergeFails = None
    f.merge().isRight shouldBe true
    // That run's, the retry's, and the settle's, below the commit at 11.
    f.fenceArms.map(a => (a._3, a._5)).toList shouldBe List((10L, None), (10L, None), (10L, None))
    f.fenceDisarms shouldBe empty
  }

  it should "end the hold and keep the fence when a retry of a timed-out run ends" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    f.mergeFails = Some("timeout: the merge node did not answer within 600s")
    f.merge().left.map(_.code) shouldBe Left("merge_failed")
    f.mergeFails = None
    f.merge(hold = true).isRight shouldBe true
    f.fenceDisarms shouldBe empty
    f.fenceArms.last._5 shouldBe None
  }

  it should "refuse to merge a proposal other than the one the approver read, leaving it open" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val read = f.openProposal()
    f.propose(options = ProposeOptions(onConflict = Some(ConflictPolicy.Branch)))
      .isRight shouldBe true
    val open = f.openProposal()
    open should not be read
    val res = f.merge(proposal = Some(read))
    res.left.map(_.code) shouldBe Left("proposal_replaced")
    res.left.toOption.get.message should include(open)
    f.mergeBatches shouldBe empty
    f.openProposal() shouldBe open
    f.merge(proposal = Some(open)).isRight shouldBe true
  }

  it should "refuse a merge that waited behind the propose replacing its proposal" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val read = f.openProposal()
    // The merge starts while the next propose holds the lock: it passes its first look, then waits.
    var queued =
      Option.empty[
        scala.concurrent.Future[Either[BranchFailure, (Branch, BranchMerge, BranchChangeSet)]]
      ]
    f.duringAnalysis = () =>
      if queued.isEmpty then
        queued = Some(
          f.service
            .merge("acme", f.parent.name, "x", read, None, false, f.admin2, None)
            .unsafeToFuture()
        )
    f.propose().isRight shouldBe true
    val res = scala.concurrent.Await.result(queued.get, scala.concurrent.duration.Duration(30, "s"))
    res.left.map(_.code) shouldBe Left("proposal_replaced")
    f.mergeBatches shouldBe empty
  }

  it should "refuse to replace a proposal whose commit landed and never settled" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val row = f.store.listBranchMerges(f.store.findBranch(f.parent.id, "x").get.id).head
    f.mainReader.committed.put(BranchMergeSql.commitMessage("x", row.id, "root"), 11L)
    val started = f.nodesStarted
    val refused = f.propose()
    refused.left.map(_.code) shouldBe Left("proposal_unsettled")
    refused.left.toOption.get.message should include("merge again")
    f.nodesStarted shouldBe started // refused under the lock, before any analysis
    f.merge().map(_._2.mainSnapshotAfter) shouldBe Right(Some(11L))
  }

  it should "refuse to record a proposal when the open one's commit lands during its analysis" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val open = f.openProposal()
    f.duringAnalysis =
      () => f.mainReader.committed.put(BranchMergeSql.commitMessage("x", open, "root"), 11L): Unit
    f.propose().left.map(_.code) shouldBe Left("proposal_unsettled")
    f.openProposal() shouldBe open
    f.store.listBranchMerges(f.store.findBranch(f.parent.id, "x").get.id).size shouldBe 1
  }

  it should "refuse to replace a proposal whose send timed out, until a merge settles it" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    f.mergeFails = Some("timeout: the merge node did not answer within 600s")
    f.merge().left.map(_.code) shouldBe Left("merge_failed")
    f.propose().left.map(_.code) shouldBe Left("proposal_unsettled")
    f.mergeFails = None
    f.merge().isRight shouldBe true
  }

  it should "leave the proposal replaceable when Prepare timed out: nothing that can land was sent" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    f.stageFails = Some("timeout: the merge node did not answer within 600s")
    f.merge().left.map(_.code) shouldBe Left("merge_failed")
    val b = f.store.findBranch(f.parent.id, "x").get
    b.status shouldBe BranchStatus.Proposed
    f.store.listBranchMerges(b.id).head.error shouldBe None
    f.stageFails = None
    f.propose().isRight shouldBe true
  }

  it should "leave the proposal open and release everything when a bug raises" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    f.beforeRun = () => throw new IllegalStateException("a bug")
    an[IllegalStateException] should be thrownBy f.merge()
    f.fenceDisarms.size shouldBe f.fenceArms.size
    val b = f.store.findBranch(f.parent.id, "x").get
    f.store.listBranchMerges(b.id).head.status shouldBe BranchMergeStatus.Proposed
    val pool = f.sup.get(PoolKey("acme", b.tenantDbName, b.poolName)).get
    (pool.disabled, pool.suspended) shouldBe (false, false)
  }

  it should "take a commit the node never confirmed, when its message is on main" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val message =
      BranchMergeSql.commitMessage(
        "x",
        f.store.listBranchMerges(f.store.findBranch(f.parent.id, "x").get.id).head.id,
        "root"
      )
    f.beforeRun = () => f.mainReader.committed.put(message, 11L)
    f.mergeFails = Some("timeout: the merge node did not answer within 600s")
    f.merge().map(_._2.mainSnapshotAfter) shouldBe Right(Some(11L))
  }

  it should "refuse altered tables, leaving the proposal open" in {
    val f = new Fixture
    f.create("x")
    f.branchReader.head = 11L
    f.branchReader.changes = List((11L, "altered_table:2"))
    val altered = new FakeReader(11L, f.branchReader.versions, f.branchReader.changes):
      override def columnsOfTableAt(id: Long, n: Long): List[CatalogColumnEntry] =
        if n == 11L then
          List(
            CatalogColumnEntry(0, "x", "INTEGER", true, false),
            CatalogColumnEntry(1, "y", "INTEGER", true, false)
          )
        else List(CatalogColumnEntry(0, "x", "INTEGER", true, false))
    val b = f.store.findBranch(f.parent.id, "x").get
    f.readers.put(b.tenantDbName, altered)
    f.propose().isRight shouldBe true
    f.merge().left.map(_.code) shouldBe Left("merge_unsupported")
    f.store.findBranch(f.parent.id, "x").get.status shouldBe BranchStatus.Proposed
  }

  it should "take a commit on main whatever the send answered, and never send Apply again" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val message = BranchMergeSql.commitMessage("x", f.openProposal(), "root")
    f.mergeBatches.clear()
    // The answer reads as a lost race. With no key, a second Apply would insert the new rows again.
    f.beforeRun = () => f.mainReader.committed.put(message, 11L): Unit
    f.applyScript = List("qod merge fence: a merged table moved past snapshot 10")
    f.merge().map(_._2.mainSnapshotAfter) shouldBe Right(Some(11L))
    f.mergeBatches.size shouldBe 1
  }

  it should "hold --expect-main to the first attempt: a retry's main has moved by definition" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    var moved = false
    f.beforeRun = () =>
      if !moved then
        moved = true
        f.mainReader.head = 11L
        f.mainReader.changes = List((11L, "inlined_insert:9"))
    f.applyScript = List("qod merge fence: a merged table moved past snapshot 10")
    f.merge(expected = Some(10L)).map(_._2.attempts) shouldBe Right(2)
  }

  it should "judge --expect-main at the head the first attempt reads, behind the hold" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    f.onArm = () =>
      if f.fenceArms.size == 1 then
        f.mainReader.head = 11L
        f.mainReader.changes = List((11L, "inlined_insert:9"))
    f.merge(expected = Some(10L), hold = true).left.map(_.code) shouldBe Left("concurrent_write")
    f.mergeBatches shouldBe empty
    f.store.findBranch(f.parent.id, "x").get.status shouldBe BranchStatus.Proposed
  }

  it should "refuse a table conflict that appears between attempts, closing the proposal" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    f.mergeBatches.clear()
    // Main drops the table while the first attempt runs, and the fence refuses that attempt.
    f.beforeRun = () =>
      f.mainReader.head = 11L
      f.mainReader.versions = List(TableVersion(2L, "tpch1", "region", 1L, Some(11L)))
      f.mainReader.changes = List((11L, "dropped_table:2"))
    f.applyScript = List("qod merge fence: a merged table moved past snapshot 10")
    f.merge().left.map(_.code) shouldBe Left("merge_conflict")
    f.mergeBatches.size shouldBe 1
    f.store.findBranch(f.parent.id, "x").get.status shouldBe BranchStatus.Open
  }

  it should "read main's side ahead of the hold, so the hold never covers that read" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    f.nodeBatches.clear()
    var readAtHold = Option.empty[Boolean]
    f.onArm = () =>
      if readAtHold.isEmpty then readAtHold = Some(f.nodeBatches.exists(_.contains("t0_net_m")))
    f.merge(hold = true).isRight shouldBe true
    readAtHold shouldBe Some(true)
  }

  it should "wait after the hold goes up, before reading main's head" in {
    val f = new Fixture(holdSettle = 150.millis)
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    var armedAt = List.empty[Long]
    f.onArm = () => armedAt = armedAt :+ System.nanoTime()
    f.merge(hold = true).isRight shouldBe true
    (armedAt(1) - armedAt(0)) should be >= 150.millis.toNanos
  }

  it should "wait between attempts when main moved" in {
    val f = new Fixture(attemptWait = 150.millis)
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    f.applyScript = List("qod merge fence: a merged table moved past snapshot 10")
    var armedAt = List.empty[Long]
    f.onArm = () => armedAt = armedAt :+ System.nanoTime()
    f.merge().isRight shouldBe true
    (armedAt(1) - armedAt(0)) should be >= 150.millis.toNanos
  }

  it should "merge a table the branch created, though its rows are already on main" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.branchReader.head = 13L
    f.branchReader.versions = f.branchReader.versions :+
      TableVersion(3L, "tpch1", "extra", 13L, None)
    f.branchReader.changes = f.branchReader.changes :+
      ((13L, """created_table:"tpch1"."extra""""))
    f.countsRow = List.fill(11)(0L)
    f.propose().isRight shouldBe true
    f.merge().isRight shouldBe true
    f.mergeBatches.head should include("""CREATE TABLE "acme_tpch"."tpch1"."extra" AS SELECT""")
  }

  it should "stay split on the attempts after an out-of-memory failure" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    f.mergeBatches.clear()
    f.applyScript = List(
      "Out of Memory Error: failed to allocate data of size 1.0 GiB",
      "qod merge fence: a merged table moved past snapshot 10"
    )
    f.merge().map(_._2.attempts) shouldBe Right(3)
    f.mergeBatches.tail.foreach(_ should include("t.rowid >= "))
  }

  it should "split an update above the configured rows per statement" in {
    val f = new Fixture(BranchingConfig(mergeUpdateChunkRows = 1L))
    f.create("x")
    f.touchBranch()
    f.countsRow = List(2L, 0L, 0L, 0L, 0L, 0L, 0L, 1L, 2L, 0L, 0L)
    f.propose().isRight shouldBe true
    f.merge().isRight shouldBe true
    f.mergeBatches.head should include("t.rowid >= ")
  }

  it should "work out the rows with the proposal's policy and keys, not only judge by them" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.mainReader.head = 11L
    f.mainReader.changes = List((11L, "inlined_insert:2"))
    val options = ProposeOptions(Some(ConflictPolicy.Branch), Map("tpch1.region" -> List("x")))
    f.propose(options = options).isRight shouldBe true
    f.nodeBatches.clear()
    f.merge().isRight shouldBe true
    val sent = f.nodeBatches.mkString("\n")
    sent should include("action IN ('apply_update', 'conflict_cell')")
    sent should include("t0_key_hits")
  }

  it should "close a proposal whose stored keys or counts cannot be read, merging nothing" in
    List[BranchMerge => BranchMerge](
      _.copy(mergeKeysJson = "not json"),
      _.copy(rowConflictsJson = "not json")
    ).foreach { corrupt =>
      val f = new Fixture
      f.create("x")
      f.touchBranch()
      f.propose().isRight shouldBe true
      val b = f.store.findBranch(f.parent.id, "x").get
      val m = f.store.listBranchMerges(b.id).head
      f.store.createBranchMerge(corrupt(m.copy(id = "mg-unreadable")))
      f.store.updateBranchMerge(m.copy(status = BranchMergeStatus.Abandoned))
      f.merge().left.map(_.code) shouldBe Left("merge_failed")
      f.mergeBatches shouldBe empty
      f.store.findBranch(f.parent.id, "x").get.status shouldBe BranchStatus.Open
    }

  it should "record the hold, the attempts and the policy in the merge's audit detail" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose(options = ProposeOptions(Some(ConflictPolicy.Main))).isRight shouldBe true
    f.applyScript = List("qod merge fence: a merged table moved past snapshot 10")
    f.merge(hold = true).isRight shouldBe true
    val done = f.auditStore.events
      .filter(e => e.action == AuditActions.BranchMerge && e.outcome == "ok")
      .toList
    done.map(e => (e.detail("holdWriters"), e.detail("attempts"), e.detail("onConflict"))) shouldBe
      List(("true", "2", "main"))
  }

  // ---- a late landing: a commit nobody waits for any more ----------------------------------------

  it should "settle a commit that lands before the attempt reads main, never sending Apply" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val id = f.openProposal()
    f.mergeBatches.clear()
    // Past the merge's first look: node start and Prepare are a long window, and the fence starts
    // above this commit, so it would not see it. With no key, Apply would insert the rows again.
    f.duringAnalysis = () => f.land(id)
    f.merge().map(_._2.mainSnapshotAfter) shouldBe Right(Some(11L))
    f.mergeBatches shouldBe empty
  }

  it should "settle that commit ahead of the table checks, which it would fail" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.branchReader.head = 13L
    f.branchReader.versions = f.branchReader.versions :+
      TableVersion(3L, "tpch1", "extra", 13L, None)
    f.branchReader.changes = f.branchReader.changes :+
      ((13L, """created_table:"tpch1"."extra""""))
    f.propose().isRight shouldBe true
    val id = f.openProposal()
    f.mergeBatches.clear()
    // The merge's own commit created the table on main: "also created on main", a table conflict.
    f.duringAnalysis = () =>
      f.land(id)
      f.mainReader.versions = f.mainReader.versions :+
        TableVersion(3L, "tpch1", "extra", 11L, None)
      f.mainReader.changes = List((11L, """created_table:"tpch1"."extra""""))
    f.merge().map(_._2.mainSnapshotAfter) shouldBe Right(Some(11L))
    f.mergeBatches shouldBe empty
  }

  it should "keep a timed-out send's record when the look right after it fails" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val id = f.openProposal()
    f.mergeFails = Some(TimedOutSend)
    // A stalled catalog is a likely cause of the timeout, so the two come together.
    f.beforeRun = () => f.mainReader.lookFails = true
    val (res, logs) = LogCapture.logged(ServiceLog)(f.merge())
    res.left.map(_.code) shouldBe Left("merge_failed")
    logs.count(e =>
      e.getLevel == Level.ERROR && e.getFormattedMessage.contains("cannot look")
    ) shouldBe
      2
    f.fenceDisarms shouldBe empty
    f.status shouldBe BranchStatus.Proposed
    f.row(id).error shouldBe Some(TimedOutSend)
    f.mainReader.lookFails = false
    f.propose().left.map(_.code) shouldBe Left("proposal_unsettled")
  }

  it should "keep a timed-out send's record when only the last look fails" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val id = f.openProposal()
    f.mergeFails = Some(TimedOutSend)
    f.afterNode = () => f.mainReader.lookFails = true
    val (res, logs) = LogCapture.logged(ServiceLog)(f.merge())
    res.left.map(_.code) shouldBe Left("merge_failed")
    logs.count(e =>
      e.getLevel == Level.ERROR && e.getFormattedMessage.contains("cannot look")
    ) shouldBe
      1
    f.fenceDisarms shouldBe empty
    f.status shouldBe BranchStatus.Proposed
    f.row(id).error shouldBe Some(TimedOutSend)
    f.mainReader.lookFails = false
    f.propose().left.map(_.code) shouldBe Left("proposal_unsettled")
  }

  it should "raise on a look that fails after a send that answered, at either look" in {
    val committed = new Fixture
    committed.create("x")
    committed.touchBranch()
    committed.propose().isRight shouldBe true
    committed.beforeRun = () => committed.mainReader.lookFails = true
    an[java.sql.SQLException] should be thrownBy committed.merge()

    val failure = new Fixture
    failure.create("x")
    failure.touchBranch()
    failure.propose().isRight shouldBe true
    failure.mergeFails = Some("Binder Error: something else")
    failure.afterNode = () => failure.mainReader.lookFails = true
    an[java.sql.SQLException] should be thrownBy failure.merge()
  }

  it should "refuse a new proposal once a closed proposal's commit landed: a branch's lands once" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val first = f.openProposal()
    f.mergeFails = Some(TimedOutSend)
    f.merge().left.map(_.code) shouldBe Left("merge_failed")
    f.mergeFails = None
    // The thaw lets a user write on the branch, so the retry closes the proposal, batch running.
    f.branchReader.head = 13L
    f.branchReader.changes = f.branchReader.changes :+ ((13L, "inlined_insert:2"))
    f.merge().left.map(_.code) shouldBe Left("proposal_stale")
    f.status shouldBe BranchStatus.Open
    f.mergeBatches.clear()
    f.land(first)
    val started = f.nodesStarted
    val refused = f.propose()
    refused.left.map(_.code) shouldBe Left("proposal_unsettled")
    val message = refused.left.toOption.get.message
    message should include(first)
    message should include("snapshot 11")
    message should include("discard this branch")
    f.nodesStarted shouldBe started
    f.mergeBatches shouldBe empty
  }

  it should "refuse to merge a new proposal once an older one's commit landed, leaving it open" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val first = f.openProposal()
    f.mergeFails = Some(TimedOutSend)
    f.merge().left.map(_.code) shouldBe Left("merge_failed")
    f.mergeFails = None
    f.branchReader.head = 13L
    f.branchReader.changes = f.branchReader.changes :+ ((13L, "inlined_insert:2"))
    f.merge().left.map(_.code) shouldBe Left("proposal_stale")
    // Nothing has landed yet, so a new proposal goes through. Then the old batch lands.
    f.propose().isRight shouldBe true
    val second = f.openProposal()
    f.mergeBatches.clear()
    f.land(first)
    val refused = f.merge()
    refused.left.map(_.code) shouldBe Left("proposal_unsettled")
    refused.left.toOption.get.message should include(first)
    f.mergeBatches shouldBe empty
    f.openProposal() shouldBe second
    f.status shouldBe BranchStatus.Proposed
  }

  it should "settle this proposal's commit first, when another's is on main too" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val id = f.openProposal()
    // A newer row that is not open: the listing gives it first.
    f.store.createBranchMerge(
      f.row(id).copy(id = "mg-newer", status = BranchMergeStatus.Abandoned, createdAt = None)
    )
    f.store.listBranchMerges(f.row(id).branchId).head.id shouldBe "mg-newer"
    f.land(id)
    f.land("mg-newer", 12L)
    f.merge().map(_._2.mainSnapshotAfter) shouldBe Right(Some(11L))
  }

  it should "refuse when another proposal's commit lands before the attempt reads main" in {
    val f                = new Fixture
    val (replaced, open) = f.replacedAndOpen()
    f.duringAnalysis = () => f.land(replaced)
    val refused = f.merge()
    refused.left.map(_.code) shouldBe Left("proposal_unsettled")
    refused.left.toOption.get.message should include(replaced)
    f.mergeBatches shouldBe empty
    f.openProposal() shouldBe open
  }

  it should "refuse, sending no more, when another proposal's commit lands during an Apply that lost" in {
    val f                = new Fixture
    val (replaced, open) = f.replacedAndOpen()
    f.beforeRun = () => f.land(replaced)
    f.applyScript = List(FenceRefused)
    f.merge().left.map(_.code) shouldBe Left("proposal_unsettled")
    f.mergeBatches.size shouldBe 1
    f.openProposal() shouldBe open
  }

  it should "refuse on the last attempt too, not report that main kept moving" in {
    val f                = new Fixture(BranchingConfig(mergeMaxAttempts = 1))
    val (replaced, open) = f.replacedAndOpen()
    f.beforeRun = () => f.land(replaced)
    f.applyScript = List(FenceRefused)
    f.merge().left.map(_.code) shouldBe Left("proposal_unsettled")
    f.mergeBatches.size shouldBe 1
    f.openProposal() shouldBe open
  }

  it should "refuse, not close, when another proposal's commit shows at the last look" in {
    val f                = new Fixture
    val (replaced, open) = f.replacedAndOpen()
    f.mergeFails = Some("Binder Error: something else")
    f.afterNode = () => f.land(replaced)
    f.merge().left.map(_.code) shouldBe Left("proposal_unsettled")
    f.openProposal() shouldBe open
    f.status shouldBe BranchStatus.Proposed
  }

  it should "keep its own record after a timed-out send, whatever else landed: that batch can land too" in
    List[Fixture => (() => Unit) => Unit](
      f => hook => f.beforeRun = hook,
      f => hook => f.afterNode = hook
    ).foreach { when =>
      val f                = new Fixture
      val (replaced, open) = f.replacedAndOpen()
      f.mergeFails = Some(TimedOutSend)
      when(f)(() => f.land(replaced))
      f.merge().left.map(_.code) shouldBe Left("merge_failed")
      f.fenceDisarms shouldBe empty
      f.row(open).error shouldBe Some(TimedOutSend)
      f.status shouldBe BranchStatus.Proposed
      // The next merge answers the rule, and sends nothing.
      when(f)(() => ())
      f.mergeFails = None
      f.mergeBatches.clear()
      f.merge().left.map(_.code) shouldBe Left("proposal_unsettled")
      f.mergeBatches shouldBe empty
    }

  it should "keep a timed-out send's prefix through a later refusal that leaves the proposal open" in {
    val f = new Fixture(BranchingConfig(mergeMaxAttempts = 1))
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val id = f.openProposal()
    f.mergeFails = Some(TimedOutSend)
    f.merge().left.map(_.code) shouldBe Left("merge_failed")
    f.mergeFails = None
    f.applyScript = List(FenceRefused)
    f.merge().left.map(_.code) shouldBe Left("concurrent_write")
    f.row(id).error shouldBe Some(TimedOutSend)
    f.propose().left.map(_.code) shouldBe Left("proposal_unsettled")
    // The run after it still leaves the fence row, which holds that batch.
    f.merge().isRight shouldBe true
    f.fenceDisarms shouldBe empty
  }

  it should "replace an earlier refusal's text with the latest, when no send timed out" in {
    val f = new Fixture(BranchingConfig(mergeMaxAttempts = 1))
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val id = f.openProposal()
    f.applyScript = List(FenceRefused)
    f.merge().left.map(_.code) shouldBe Left("concurrent_write")
    f.countsRow = List.fill(11)(0L)
    f.merge().left.map(_.code) shouldBe Left("nothing_to_merge")
    f.row(id).error.get should include("already on main")
  }

  it should "mark the proposal before an Apply send, and clear the mark once it lands" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val id = f.openProposal()
    // As a dying manager would leave it: the row says an Apply went out with no answer.
    var atSend = Option.empty[String]
    f.beforeRun = () => atSend = f.row(id).error
    f.merge().isRight shouldBe true
    atSend.get should startWith(MergeExecutor.TimedOut)
    atSend.get should include("Apply")
    f.row(id).error shouldBe None
  }

  it should "mark no proposal when no Apply goes out" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val id = f.openProposal()
    f.countsRow = List.fill(11)(0L)
    f.merge().left.map(_.code) shouldBe Left("nothing_to_merge")
    f.row(id).error.get should not startWith MergeExecutor.TimedOut
    f.propose().isRight shouldBe true
  }

  it should "replace the mark when the send answers" in {
    val f = new Fixture(BranchingConfig(mergeMaxAttempts = 1))
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val id = f.openProposal()
    f.applyScript = List(FenceRefused)
    f.merge().left.map(_.code) shouldBe Left("concurrent_write")
    f.row(id).error.get should include("--hold-writers")
    f.propose().isRight shouldBe true
  }

  it should "read a raise mid-send as a send with no answer: propose refuses, the next merge keeps the fence" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val id = f.openProposal()
    f.beforeRun = () => throw new IllegalStateException("a bug")
    an[IllegalStateException] should be thrownBy f.merge()
    f.row(id).error.get should startWith(MergeExecutor.TimedOut)
    f.beforeRun = () => ()
    f.propose().left.map(_.code) shouldBe Left("proposal_unsettled")
    f.openProposal() shouldBe id
    val disarms = f.fenceDisarms.size
    f.merge().isRight shouldBe true
    f.fenceDisarms.size shouldBe disarms
    f.row(id).error shouldBe None
  }

  it should "say so when a settle finds the branch moved: the teardown removes those writes" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    f.land(f.openProposal())
    f.branchReader.head = 13L
    f.branchReader.changes = f.branchReader.changes :+ ((13L, "inlined_insert:2"))
    val (res, logs) = LogCapture.logged(ServiceLog)(f.merge())
    res.map(_._2.mainSnapshotAfter) shouldBe Right(Some(11L))
    // ERROR: the default level, and a settle that drops a user's writes must show.
    logs.count(e =>
      e.getLevel == Level.ERROR && e.getFormattedMessage.contains("removes what was written")
    ) shouldBe 1
    val done = f.auditStore.events
      .filter(e => e.action == AuditActions.BranchMerge && e.outcome == "ok")
      .toList
    done.map(e => (e.detail("branchHeadAtProposal"), e.detail("branchHeadAtSettle"))) shouldBe
      List(("12", "13"))
  }

  it should "say nothing of the kind when a settle finds the branch where it was proposed" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    f.land(f.openProposal())
    val (res, logs) = LogCapture.logged(ServiceLog)(f.merge())
    res.isRight shouldBe true
    logs.filter(_.getFormattedMessage.contains("removes what was written")) shouldBe empty
    val done = f.auditStore.events
      .filter(e => e.action == AuditActions.BranchMerge && e.outcome == "ok")
      .toList
    done.map(_.detail.contains("branchHeadAtSettle")) shouldBe List(false)
  }

  it should "close the proposal when the merge node never comes up" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val id = f.openProposal()
    f.nodeFails = Some("merge node spawn failed: no free port")
    val res = f.merge()
    res.left.map(_.code) shouldBe Left("merge_failed")
    res.left.toOption.get.message should include("no free port")
    f.row(id).status shouldBe BranchMergeStatus.Failed
    f.status shouldBe BranchStatus.Open
    f.mergeBatches shouldBe empty
    f.fenceArms shouldBe empty
  }

  /** The message of the proposal `id`, and what the latest arm of the fence saw: the row's status,
    * and whether the teardown had started.
    */
  private def watchingArms(f: Fixture, id: String) =
    var atArm = Option.empty[(BranchMergeStatus, Boolean)]
    f.onArm = () => atArm = Some((f.row(id).status, f.purged.nonEmpty))
    (BranchMergeSql.commitMessage("x", id, "root"), () => atArm)

  private val LostAnswer =
    s"${MergeExecutor.TimedOut}the answer to COMMIT was lost, so it may have landed: connection reset"

  it should "end the hold and keep the row when the next merge's first look settles, once the record is written" in {
    for
      reason <- List(TimedOutSend, LostAnswer)
      // The merge that settles it need not ask for the hold an earlier run armed.
      hold <- List(true, false)
    do
      val f = new Fixture
      f.create("x")
      f.touchBranch()
      f.propose().isRight shouldBe true
      val id = f.openProposal()
      f.mergeFails = Some(reason)
      f.merge(hold = true).left.map(_.code) shouldBe Left("merge_failed")
      f.fenceArms.last._5 shouldBe Some(600)
      val armed = f.fenceArms.size
      // It lands past that run's last look, with main past that run's base.
      f.land(id, 13L)
      val (message, atClear) = watchingArms(f, id)
      f.merge(hold = hold).map(_._2.mainSnapshotAfter) shouldBe Right(Some(13L))
      // Below the landed commit, which then refuses another send's late COMMIT.
      f.fenceArms.drop(armed).map(a => (a._2, a._3, a._4, a._5)).toList shouldBe
        List((message, 12L, Set(2L), None))
      f.fenceDisarms shouldBe empty
      // On record, and writers wait for no teardown.
      atClear() shouldBe Some((BranchMergeStatus.Merged, false))
  }

  it should "end the hold and keep the row when the look after a send settles, once the record is written" in
    List(TimedOutSend, LostAnswer).foreach { reason =>
      val f = new Fixture
      f.create("x")
      f.touchBranch()
      f.propose().isRight shouldBe true
      val id = f.openProposal()
      f.mergeFails = Some(reason)
      // It lands past the attempt's own look, as the node stops.
      f.afterNode = () => f.land(id, 13L)
      val (message, atClear) = watchingArms(f, id)
      f.merge(hold = true).map(_._2.mainSnapshotAfter) shouldBe Right(Some(13L))
      f.fenceArms.map(a => (a._3, a._5)).toList shouldBe
        List((10L, Some(600)), (10L, Some(600)), (12L, None))
      f.fenceArms.last._2 shouldBe message
      f.fenceArms.last._4 shouldBe Set(2L)
      f.fenceDisarms shouldBe empty
      atClear() shouldBe Some((BranchMergeStatus.Merged, false))
    }

  it should "answer a settle whatever the clear does, and log a clear that failed" in {
    for
      fails <- List[Fixture => Unit](
        _.fenceFails = Some("cannot reach the catalog"),
        _.onArm = () => throw new IllegalStateException("cannot reach the catalog")
      )
      before <- List[(Fixture, String) => Unit](
        (f, id) =>
          f.mergeFails = Some(TimedOutSend)
          f.merge(hold = true).left.map(_.code) shouldBe Left("merge_failed")
          f.mergeFails = None
          f.land(id)
          fails(f)
        ,
        (f, id) =>
          f.mergeFails = Some(TimedOutSend)
          f.afterNode = () =>
            f.land(id)
            fails(f)
      )
    do
      val f = new Fixture
      f.create("x")
      f.touchBranch()
      f.propose().isRight shouldBe true
      val id = f.openProposal()
      before(f, id)
      val (res, logs) = LogCapture.logged(ServiceLog)(f.merge(hold = true))
      res.map(_._2.mainSnapshotAfter) shouldBe Right(Some(11L))
      f.row(id).status shouldBe BranchMergeStatus.Merged
      f.store.findBranch(f.parent.id, "x") shouldBe None
      logs.count(e =>
        e.getLevel == Level.ERROR && e.getFormattedMessage.contains("cannot reach the catalog")
      ) shouldBe 1
  }

  it should "keep the row below the commit at every settle, when no run asked for a hold too" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val id = f.openProposal()
    f.mergeFails = Some(TimedOutSend)
    f.merge().left.map(_.code) shouldBe Left("merge_failed")
    f.mergeFails = None
    val armed = f.fenceArms.size
    f.land(id, 13L)
    f.merge().map(_._2.mainSnapshotAfter) shouldBe Right(Some(13L))
    f.fenceArms.drop(armed).map(a => (a._3, a._5)).toList shouldBe List((12L, None))
    f.fenceDisarms shouldBe empty

    // The look after a send.
    val g = new Fixture
    g.create("x")
    g.touchBranch()
    g.propose().isRight shouldBe true
    val sent = g.openProposal()
    g.mergeFails = Some(TimedOutSend)
    g.afterNode = () => g.land(sent, 13L)
    g.merge().map(_._2.mainSnapshotAfter) shouldBe Right(Some(13L))
    g.fenceArms.map(a => (a._3, a._5)).toList shouldBe List((10L, None), (12L, None))
    g.fenceDisarms shouldBe empty

    // The look inside an attempt.
    val h = new Fixture
    h.create("x")
    h.touchBranch()
    h.propose().isRight shouldBe true
    val inside = h.openProposal()
    h.mergeFails = Some(TimedOutSend)
    h.merge().left.map(_.code) shouldBe Left("merge_failed")
    h.mergeFails = None
    val armedInside = h.fenceArms.size
    h.duringAnalysis = () => if h.mainReader.committed.isEmpty then h.land(inside, 13L)
    h.merge().map(_._2.mainSnapshotAfter) shouldBe Right(Some(13L))
    h.fenceArms.drop(armedInside).map(a => (a._3, a._5)).toList shouldBe List((12L, None))
    h.fenceDisarms shouldBe empty
  }

  it should "end a hold at a settle, even when a later run's record says none was asked" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val id = f.openProposal()
    f.mergeFails = Some(LostAnswer)
    f.merge(hold = true).left.map(_.code) shouldBe Left("merge_failed")
    f.mergeFails = None
    f.fenceArms.last._5 shouldBe Some(600)
    val armed = f.fenceArms.size
    // A run with no hold, refused inside its attempt before it arms: it writes over the record.
    f.duringAnalysis = () =>
      f.mainReader.head = 11L
      f.mainReader.changes = List((11L, "inlined_insert:5"))
    f.merge(expected = Some(10L)).left.map(_.code) shouldBe Left("concurrent_write")
    f.fenceArms.size shouldBe armed
    f.row(id).holdWriters shouldBe false
    f.duringAnalysis = () => ()
    f.land(id, 13L)
    f.merge().map(_._2.mainSnapshotAfter) shouldBe Right(Some(13L))
    f.fenceArms.drop(armed).map(a => (a._3, a._5)).toList shouldBe List((12L, None))
  }

  it should "end an earlier run's hold when a no-hold retry finds its commit inside an attempt" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val id = f.openProposal()
    f.mergeFails = Some(LostAnswer)
    f.merge(hold = true).left.map(_.code) shouldBe Left("merge_failed")
    f.mergeFails = None
    f.fenceArms.last._5 shouldBe Some(600)
    val armed = f.fenceArms.size
    f.mergeBatches.clear()
    // Past the next merge's first look, and before its attempt's.
    f.duringAnalysis = () => if f.mainReader.committed.isEmpty then f.land(id, 13L)
    val (message, atClear) = watchingArms(f, id)
    f.merge().map(_._2.mainSnapshotAfter) shouldBe Right(Some(13L))
    f.mergeBatches shouldBe empty
    f.fenceArms.drop(armed).map(a => (a._2, a._3, a._4, a._5)).toList shouldBe
      List((message, 12L, Set(2L), None))
    f.fenceDisarms shouldBe empty
    atClear() shouldBe Some((BranchMergeStatus.Merged, false))
  }

  it should "settle at the look before a held retry arms, never leaving the base at the commit" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val id = f.openProposal()
    f.mergeFails = Some(TimedOutSend)
    f.merge().left.map(_.code) shouldBe Left("merge_failed")
    f.mergeFails = None
    f.row(id).attempts shouldBe 1
    val armed   = f.fenceArms.size
    val started = f.nodesStarted
    f.mergeBatches.clear()
    // It lands before the retry reads main's head on the frozen branch: that head holds the commit.
    f.onHeadRead(2)(() => f.land(id))
    val (message, atClear) = watchingArms(f, id)
    f.merge(hold = true).map(_._2.mainSnapshotAfter) shouldBe Right(Some(11L))
    f.fenceArms.drop(armed).map(a => (a._2, a._3, a._4, a._5)).toList shouldBe
      List((message, 10L, Set(2L), None))
    f.fenceDisarms shouldBe empty
    atClear() shouldBe Some((BranchMergeStatus.Merged, false))
    f.nodesStarted shouldBe started
    f.mergeBatches shouldBe empty
    f.row(id).attempts shouldBe 1
  }

  it should "fence a held retry below a commit that lands after that look" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val id = f.openProposal()
    f.mergeFails = Some(TimedOutSend)
    f.merge().left.map(_.code) shouldBe Left("merge_failed")
    f.mergeFails = None
    val armed = f.fenceArms.size
    f.mergeBatches.clear()
    // During Prepare: above the head the retry read, and before its hold's arm.
    f.duringAnalysis = () => if f.mainReader.committed.isEmpty then f.land(id)
    f.merge(hold = true).map(_._2.mainSnapshotAfter) shouldBe Right(Some(11L))
    f.fenceArms.drop(armed).map(a => (a._3, a._5)).toList shouldBe
      List((10L, Some(600)), (10L, None), (10L, None))
    f.fenceDisarms shouldBe empty
    f.mergeBatches shouldBe empty
  }

  it should "refuse at that look when it finds another proposal's commit, arming nothing" in {
    val f                = new Fixture
    val (replaced, open) = f.replacedAndOpen()
    val started          = f.nodesStarted
    f.onHeadRead(2)(() => f.land(replaced))
    f.merge(hold = true).left.map(_.code) shouldBe Left("proposal_unsettled")
    f.openProposal() shouldBe open
    f.fenceArms shouldBe empty
    f.nodesStarted shouldBe started
  }

  it should "arm nothing when that look fails, leaving the proposal open" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val id      = f.openProposal()
    val started = f.nodesStarted
    f.onHeadRead(2)(() => f.mainReader.lookFails = true)
    an[java.sql.SQLException] should be thrownBy f.merge(hold = true)
    f.fenceArms shouldBe empty
    f.nodesStarted shouldBe started
    f.row(id).status shouldBe BranchMergeStatus.Proposed
    f.status shouldBe BranchStatus.Proposed
  }

  it should "pay for that look with one catalog read per proposal, and only when holding writers" in {
    def looks(hold: Boolean): Int =
      val f = new Fixture
      f.create("x")
      f.touchBranch()
      f.propose().isRight shouldBe true
      f.merge(hold = hold).isRight shouldBe true
      f.mainReader.looks
    looks(hold = true) shouldBe looks(hold = false) + 1
  }

  it should "answer a settle inside an attempt whatever the clear does, and log a clear that failed" in {
    for fails <- List[Fixture => Unit](
        _.fenceFails = Some("cannot reach the catalog"),
        _.onArm = () => throw new IllegalStateException("cannot reach the catalog")
      )
    do
      val f = new Fixture
      f.create("x")
      f.touchBranch()
      f.propose().isRight shouldBe true
      val id = f.openProposal()
      f.mergeFails = Some(LostAnswer)
      f.merge(hold = true).left.map(_.code) shouldBe Left("merge_failed")
      f.mergeFails = None
      // A retry with no hold arms nothing before this settle.
      f.duringAnalysis = () => if f.mainReader.committed.isEmpty then f.land(id)
      fails(f)
      val (res, logs) = LogCapture.logged(ServiceLog)(f.merge())
      res.map(_._2.mainSnapshotAfter) shouldBe Right(Some(11L))
      f.row(id).status shouldBe BranchMergeStatus.Merged
      f.store.findBranch(f.parent.id, "x") shouldBe None
      logs.count(e =>
        e.getLevel == Level.ERROR && e.getFormattedMessage.contains("cannot reach the catalog")
      ) shouldBe 1
  }

  it should "leave another proposal's fence row alone: its commit on main is a refusal, not a settle" in {
    val f                = new Fixture
    val (replaced, open) = f.replacedAndOpen()
    f.land(replaced)
    f.merge(hold = true).left.map(_.code) shouldBe Left("proposal_unsettled")
    f.openProposal() shouldBe open
    f.fenceArms shouldBe empty
    f.fenceDisarms shouldBe empty
  }

  // ---- a proposal closes only after a look -----------------------------------------------------

  it should "settle a commit that lands ahead of a closing refusal: merged and tagged, not a conflict" in
    List(2, 1).foreach { nth =>
      val f = new Fixture
      f.create("x")
      f.touchBranch()
      f.createdOnBranch()
      f.propose().isRight shouldBe true
      val id      = f.openProposal()
      val started = f.nodesStarted
      f.mergeBatches.clear()
      f.onHeadRead(nth) { () =>
        f.land(id)
        f.createdOnMain()
      }
      withClue(s"at the head read $nth: ") {
        f.merge().map(_._2.mainSnapshotAfter) shouldBe Right(Some(11L))
        val m = f.row(id)
        m.status shouldBe BranchMergeStatus.Merged
        m.error shouldBe None
        f.store.findSnapshotTag("acme", f.parent.name, m.tagName.get).map(_.snapshotId) shouldBe
          Some(11L)
        f.store.findBranch(f.parent.id, "x") shouldBe None
        f.nodesStarted shouldBe started
        f.mergeBatches shouldBe empty
        // A merge, not a denial that also merged.
        f.auditStore.events
          .filter(_.action == AuditActions.BranchMerge)
          .map(_.outcome)
          .toList shouldBe
          List("ok")
      }
    }

  it should "settle a commit that lands ahead of a history gap: merged, not closed as a gap" in
    List(2, 1).foreach { nth =>
      def proposed: Fixture =
        val f = new Fixture
        f.create("x")
        f.touchBranch()
        f.propose().isRight shouldBe true
        f
      // Main's snapshot 12 is gone: no merge can read main's changes across it.
      def hole(f: Fixture): Unit = f.mainReader.changes = List((11L, ""), (13L, ""))

      withClue(s"at the head read $nth: ") {
        // With no commit on main, this hole closes the proposal.
        val closed = proposed
        val open   = closed.openProposal()
        closed.onHeadRead(nth) { () =>
          closed.mainReader.head = 13L
          hole(closed)
        }
        closed.merge().left.map(_.code) shouldBe Left("history_gap")
        closed.row(open).status shouldBe BranchMergeStatus.Failed

        // With the proposal's commit on main, the same hole settles it.
        val f  = proposed
        val id = f.openProposal()
        f.onHeadRead(nth) { () =>
          f.land(id, 13L)
          hole(f)
        }
        f.merge().map(_._2.mainSnapshotAfter) shouldBe Right(Some(13L))
        val m = f.row(id)
        m.status shouldBe BranchMergeStatus.Merged
        m.error shouldBe None
        m.mainSnapshotAfter shouldBe Some(13L)
        f.store.findSnapshotTag("acme", f.parent.name, m.tagName.get).map(_.snapshotId) shouldBe
          Some(13L)
        f.store.findBranch(f.parent.id, "x") shouldBe None
      }
    }

  it should "settle a commit that lands during Decide, ahead of Decide's refusal" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val id = f.openProposal()
    f.mergeBatches.clear()
    f.nodeBatches.clear()
    // Past the attempt's look: only Decide's own batch writes the merged rows.
    f.duringAnalysis = () => if f.nodeBatches.exists(_.contains("t0_merged")) then f.land(id)
    // Row conflicts with no policy: Decide refuses, and that closes the proposal.
    f.countsRow = List(1L, 0L, 0L, 3L, 0L, 0L, 0L, 1L, 1L, 0L, 3L)
    f.merge().map(_._2.mainSnapshotAfter) shouldBe Right(Some(11L))
    f.row(id).status shouldBe BranchMergeStatus.Merged
    f.row(id).attempts shouldBe 1
    f.mergeBatches shouldBe empty
  }

  it should "keep the attempts of the run that sent it, when a refusal before any attempt settles" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.createdOnBranch()
    f.propose().isRight shouldBe true
    val id = f.openProposal()
    f.mergeFails = Some(TimedOutSend)
    f.merge().left.map(_.code) shouldBe Left("merge_failed")
    f.row(id).attempts shouldBe 1
    f.mergeFails = None
    f.onHeadRead(1) { () =>
      f.land(id)
      f.createdOnMain()
    }
    f.merge().map(_._2.mainSnapshotAfter) shouldBe Right(Some(11L))
    f.row(id).attempts shouldBe 1
    // That run's fence row stays, as at every settle: another send can be alive.
    f.fenceDisarms shouldBe empty
  }

  it should "settle ahead of proposal_stale too, saying that the branch moved" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val id = f.openProposal()
    f.mergeFails = Some(TimedOutSend)
    f.merge().left.map(_.code) shouldBe Left("merge_failed")
    f.mergeFails = None
    // The thaw let a user write on the branch, and that batch lands past the next first look.
    f.branchReader.head = 13L
    f.branchReader.changes = f.branchReader.changes :+ ((13L, "inlined_insert:2"))
    f.onHeadRead(1)(() => f.land(id))
    val (res, logs) = LogCapture.logged(ServiceLog)(f.merge())
    res.map(_._2.mainSnapshotAfter) shouldBe Right(Some(11L))
    logs.count(e =>
      e.getLevel == Level.ERROR && e.getFormattedMessage.contains("removes what was written")
    ) shouldBe 1
  }

  it should "settle ahead of the other closing refusals: a history gap, a proposal it cannot read" in {
    val gap = new Fixture
    gap.create("x")
    gap.touchBranch()
    gap.propose().isRight shouldBe true
    val gapped = gap.openProposal()
    gap.onHeadRead(2) { () =>
      gap.land(gapped, 13L)
      gap.mainReader.changes = List((11L, ""), (13L, ""))
    }
    gap.merge().map(_._2.mainSnapshotAfter) shouldBe Right(Some(13L))
    gap.row(gapped).status shouldBe BranchMergeStatus.Merged

    val unreadable = new Fixture
    unreadable.create("x")
    unreadable.touchBranch()
    unreadable.propose().isRight shouldBe true
    val row = unreadable.row(unreadable.openProposal())
    unreadable.store.createBranchMerge(row.copy(id = "mg-unreadable", mergeKeysJson = "not json"))
    unreadable.store.updateBranchMerge(row.copy(status = BranchMergeStatus.Abandoned))
    unreadable.onHeadRead(2)(() => unreadable.land("mg-unreadable"))
    unreadable.merge().map(_._2.mainSnapshotAfter) shouldBe Right(Some(11L))
    unreadable.row("mg-unreadable").status shouldBe BranchMergeStatus.Merged
  }

  it should "still close on a closing refusal with no commit on main" in
    List(1, 2).foreach { nth =>
      val f = new Fixture
      f.create("x")
      f.touchBranch()
      f.createdOnBranch()
      f.propose().isRight shouldBe true
      val id = f.openProposal()
      f.onHeadRead(nth)(() => f.createdOnMain())
      withClue(s"at the head read $nth: ") {
        f.merge().left.map(_.code) shouldBe Left("merge_conflict")
        f.row(id).status shouldBe BranchMergeStatus.Failed
        f.row(id).tagName shouldBe None
        f.status shouldBe BranchStatus.Open
      }
    }

  it should "still close when the commit on main is another proposal's: only a discard exits then" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.createdOnBranch()
    f.propose().isRight shouldBe true
    val replaced = f.openProposal()
    f.propose().isRight shouldBe true
    val open = f.openProposal()
    f.onHeadRead(1) { () =>
      f.land(replaced)
      f.createdOnMain()
    }
    f.merge().left.map(_.code) shouldBe Left("merge_conflict")
    f.row(open).status shouldBe BranchMergeStatus.Failed
    f.status shouldBe BranchStatus.Open
    f.propose().left.map(_.code) shouldBe Left("proposal_unsettled")
  }

  it should "raise and leave the proposal open when the look before closing fails" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.createdOnBranch()
    f.propose().isRight shouldBe true
    val id = f.openProposal()
    f.onHeadRead(1) { () =>
      f.createdOnMain()
      f.mainReader.lookFails = true
    }
    an[java.sql.SQLException] should be thrownBy f.merge()
    f.row(id).status shouldBe BranchMergeStatus.Proposed
    f.status shouldBe BranchStatus.Proposed
  }

  it should "not look on a refusal that leaves the proposal open: the next first look settles" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.propose().isRight shouldBe true
    val id = f.openProposal()
    f.onHeadRead(1)(() => f.land(id))
    f.merge(expected = Some(10L)).left.map(_.code) shouldBe Left("concurrent_write")
    f.row(id).status shouldBe BranchMergeStatus.Proposed
    f.merge().map(_._2.mainSnapshotAfter) shouldBe Right(Some(11L))
  }

  "fencedIds" should "take the tables' ids, and refuse a changed table with no id" in {
    BranchService.fencedIds(
      List(
        TableChange("s", "a", ChangeKind.Modified, tableId = Some(2L)),
        TableChange("s", "b", ChangeKind.Created)
      )
    ) shouldBe Set(2L)
    an[IllegalArgumentException] should be thrownBy
      BranchService.fencedIds(List(TableChange("s", "a", ChangeKind.Dropped)))
  }

  "discard" should "allow the owner or an admin and tear down" in {
    val f = new Fixture
    f.grantConnect("alice")
    f.grantConnect("bob")
    f.create("x", actor = f.alice)
    f.service
      .discard("acme", f.parent.name, "x", BranchActor.plain("bob", sees = (_, _) => true), None)
      .unsafeRunSync()
      .left
      .map(_.code) shouldBe Left("not_owner")
    val done =
      f.service.discard("acme", f.parent.name, "x", f.alice, None).unsafeRunSync().toOption.get
    done.status shouldBe BranchStatus.Discarded
    f.sup.findTenantDb("acme", done.tenantDbName) shouldBe None
    f.purged shouldBe List(done.dataPath)
    // the name is free again
    f.create("x", actor = f.alice).isRight shouldBe true
  }

  "expireDue" should "discard live branches past their TTL only" in {
    val f = new Fixture
    f.create("soon", ttl = Some(0)) // never expires (0)
    f.create("later", ttl = Some(48))
    val expired = f.store.findBranch(f.parent.id, "later").get
    f.store.updateBranch(expired.copy(expiresAt = Some(Instant.parse("2026-09-20T11:00:00Z"))))
    f.service.expireDue().unsafeRunSync().map(_.name) shouldBe List("later")
    f.store.findBranch(f.parent.id, "later", liveOnly = false).map(_.status) shouldBe Some(
      BranchStatus.Expired
    )
    f.store.findBranch(f.parent.id, "soon").map(_.status) shouldBe Some(BranchStatus.Open)
  }

  "the parent" should "refuse deletion while a live branch exists, and pin the fork snapshot" in {
    val f = new Fixture
    val b = f.create("x").toOption.get
    f.sup.stopPool(f.parentKey, force = true).unsafeRunSync()
    f.sup.deletePool(f.parentKey, force = true).unsafeRunSync()
    val refused = f.sup.deleteTenantDb("acme", f.parent.name).unsafeRunSync()
    refused.left.map(_.message).left.toOption.get should include("live branch")
    val pins = new PinnedSetResolver(f.store)
    pins.pinnedSnapshots(f.tenant.id, f.parent.name) shouldBe Set(10L)
    f.service
      .discard("acme", f.parent.name, "x", f.admin1, None)
      .unsafeRunSync()
      .isRight shouldBe true
    pins.pinnedSnapshots(f.tenant.id, f.parent.name) shouldBe Set.empty
    f.sup.deleteTenantDb("acme", f.parent.name).unsafeRunSync().isRight shouldBe true
    b.status shouldBe BranchStatus.Open
  }
