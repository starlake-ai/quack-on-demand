package ai.starlake.quack.ondemand.branch

import ai.starlake.quack.BranchingConfig
import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{
  Branch,
  BranchMergeStatus,
  BranchStatus,
  NodeSpec,
  PoolKey,
  RoleDistribution,
  Tenant,
  TenantDb,
  TenantDbKind
}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.CatalogColumnEntry
import ai.starlake.quack.ondemand.catalog.{DuckLakeCatalogReader, PinnedSetResolver, TableVersion}
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{DbAdmin, InMemoryControlPlaneStore, RbacUser}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.collection.mutable

/** Lifecycle state machine and authorization of the branch service on the in-memory store with a
  * stub backend: no Postgres, no DuckDB. The clone, the merge node and the file purge are fakes
  * that record what they were asked to do.
  */
class BranchServiceSpec extends AnyFlatSpec with Matchers:

  /** A metadata reader over hand-built state, so change sets are deterministic. */
  private class FakeReader(
      var head: Long,
      var versions: List[TableVersion] = List(TableVersion(2L, "tpch1", "region", 1L, None)),
      var changes: List[(Long, String)] = Nil,
      var committed: mutable.Map[String, Long] = mutable.Map.empty
  ) extends DuckLakeCatalogReader(null):
    override def maxSnapshotId(): Option[Long]                          = Some(head)
    override def tableVersions(): List[TableVersion]                    = versions
    override def snapshotChangesSince(fork: Long): List[(Long, String)] =
      changes.filter(_._1 > fork)
    override def columnsOfTableAt(id: Long, n: Long): List[CatalogColumnEntry] =
      List(CatalogColumnEntry(0, "x", "INTEGER", true, false))
    override def snapshotByCommitMessage(msg: String): Option[Long] = committed.get(msg)
    override def close(): Unit                                      = ()

  private final class Fixture(
      cfg: BranchingConfig = BranchingConfig(),
      parentEncrypted: Boolean = false
  ):
    val store   = new InMemoryControlPlaneStore()
    val created = mutable.ListBuffer.empty[String]
    val dropped = mutable.ListBuffer.empty[String]
    val admin   = new DbAdmin:
      def createDatabase(name: String): Either[String, Unit] = { created += name; Right(()) }
      def dropDatabase(name: String): Either[String, Unit]   = { dropped += name; Right(()) }
    val backend = new StubQuackBackend()
    val sup     = new PoolSupervisor(backend, new NodeLoadTracker, store, dbAdmin = admin)
    val tenant  = sup.createTenant(Tenant("acme")).unsafeRunSync().toOption.get
    val parent  = sup
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
    val purged       = mutable.ListBuffer.empty[String]
    val service      = new BranchService(
      cfg = cfg,
      sup = sup,
      store = store,
      resolveReader = (_, db) => readers.getOrElseUpdate(db, branchReader),
      cloneCatalog = (_, parentDb, branchDb, path) =>
        cloneCalls += ((parentDb, branchDb, path))
        if cloneFails then Left("boom") else Right(CloneResult(10L, 30, 100L))
      ,
      mergeExecutor = new MergeExecutor:
        def run(spec: NodeSpec, batch: String): IO[Either[String, Unit]] =
          mergeBatches += batch
          mergeFails match
            case Some(err) => IO.pure(Left(err))
            case None      =>
              // The batch's message is the second statement's third literal; the fake parent
              // reader answers the locate query for it.
              val msg = batch.linesIterator.toList(1).split("'")(5)
              mainReader.committed.put(msg, mainReader.head + 1)
              mainReader.head += 1
              IO.pure(Right(())),
      counter = new ChangeCounter:
        def count(t: String, b: Branch, a: String, c: TableChange, f: Long, h: Long) =
          IO.pure(Right((3L, 1L, 2L)))
      ,
      purgeFiles = (path, _) => { purged += path; Right(()) },
      now = () => Instant.parse("2026-09-20T12:00:00Z")
    )

    val admin1 = BranchActor("root", isAdmin = true)
    val admin2 = BranchActor("boss", isAdmin = true)
    val alice  = BranchActor("alice", isAdmin = false)

    def grantConnect(username: String): Unit =
      val user =
        RbacUser(id = s"u-$username", tenant = Some(tenant.id), username = username, role = "user")
      store.upsertUserIdentity(user)
      sup.grantPoolPermission(tenant.id, None, Some(user.id), None).unsafeRunSync()

    def create(name: String, actor: BranchActor = admin1, ttl: Option[Int] = None) =
      service.create("acme", parent.name, name, ttl, None, actor, None).unsafeRunSync()

    /** Give the branch some work: a modified region table. */
    def touchBranch(): Unit =
      branchReader.head = 12L
      branchReader.changes = List((11L, "inlined_insert:2"), (12L, "inlined_delete:2"))

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
      f.service.propose("acme", f.parent.name, "x", f.admin1, None).unsafeRunSync().toOption.get
    b.status shouldBe BranchStatus.Proposed
    m.status shouldBe BranchMergeStatus.Proposed
    m.proposer shouldBe "root"
    m.mainSnapshotAtPropose shouldBe 10L
    cs.tables.map(t => (t.table, t.kind, t.inserted, t.deleted, t.updated)) shouldBe
      List(("region", ChangeKind.Modified, 3L, 1L, 2L))
    f.service
      .propose("acme", f.parent.name, "x", f.admin1, None)
      .unsafeRunSync()
      .left
      .map(_.code) shouldBe
      Left("already_proposed")
  }

  "merge" should "refuse the proposer, non-admins, unproposed branches and conflicts" in {
    val f = new Fixture
    f.grantConnect("alice")
    f.create("x", actor = f.alice)
    f.touchBranch()
    f.service
      .merge("acme", f.parent.name, "x", None, f.admin1, None)
      .unsafeRunSync()
      .left
      .map(_.code) shouldBe
      Left("not_proposed")
    f.service
      .propose("acme", f.parent.name, "x", f.alice, None)
      .unsafeRunSync()
      .isRight shouldBe true
    f.service
      .merge("acme", f.parent.name, "x", None, f.alice, None)
      .unsafeRunSync()
      .left
      .map(_.code) shouldBe
      Left("admin_required")
    f.service
      .merge("acme", f.parent.name, "x", None, BranchActor("alice", isAdmin = true), None)
      .unsafeRunSync()
      .left
      .map(_.code) shouldBe Left("self_merge_forbidden")
    // main touched region after the fork
    f.mainReader.changes = List((11L, "inlined_insert:2"))
    f.mainReader.head = 11L
    val conflict = f.service.merge("acme", f.parent.name, "x", None, f.admin1, None).unsafeRunSync()
    conflict.left.map(_.code) shouldBe Left("merge_conflict")
    conflict.left.map(_.message).left.toOption.get should include("tpch1.region")
    f.mergeBatches shouldBe empty
    f.service
      .merge("acme", f.parent.name, "x", Some(99L), f.admin1, None)
      .unsafeRunSync()
      .left
      .map(_.code) shouldBe
      Left("concurrent_write")
  }

  it should "fast-forward, tag the new snapshot and tear the branch down" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.service
      .propose("acme", f.parent.name, "x", f.admin1, None)
      .unsafeRunSync()
      .isRight shouldBe true
    val (b, m, _) = f.service
      .merge("acme", f.parent.name, "x", Some(10L), f.admin2, None)
      .unsafeRunSync()
      .toOption
      .get
    b.status shouldBe BranchStatus.Merged
    m.status shouldBe BranchMergeStatus.Merged
    m.approver shouldBe Some("boss")
    m.mainSnapshotAfter shouldBe Some(11L)
    m.tagName shouldBe Some(s"merge-x-${m.id.stripPrefix("mg-").take(8)}")
    f.store.findSnapshotTag("acme", f.parent.name, m.tagName.get).map(_.snapshotId) shouldBe Some(
      11L
    )
    f.mergeBatches.size shouldBe 1
    f.mergeBatches.head should include(
      "CALL ducklake_set_commit_message('acme_tpch', 'tenant:acme/user:boss'"
    )
    f.mergeBatches.head should include("\"acme_tpch\".\"tpch1\".\"region\"")
    // teardown: pool and catalog gone, files purged, history row kept
    f.sup.get(PoolKey("acme", b.tenantDbName, b.poolName)) shouldBe None
    f.sup.findTenantDb("acme", b.tenantDbName) shouldBe None
    f.dropped should contain(b.tenantDbName)
    f.purged shouldBe List(b.dataPath)
    f.store.getBranch(b.id).flatMap(_.purgedAt).isDefined shouldBe true
    f.store.findBranch(f.parent.id, "x") shouldBe None
    f.store.findBranch(f.parent.id, "x", liveOnly = false).map(_.status) shouldBe Some(
      BranchStatus.Merged
    )
  }

  it should "reopen the branch and record the failure when the merge loses a commit race" in {
    val f = new Fixture
    f.create("x")
    f.touchBranch()
    f.service.propose("acme", f.parent.name, "x", f.admin1, None).unsafeRunSync()
    f.mergeFails = Some("TransactionContext Error: write-write conflict on table region")
    val res = f.service.merge("acme", f.parent.name, "x", None, f.admin2, None).unsafeRunSync()
    res.left.map(_.code) shouldBe Left("concurrent_write")
    val b = f.store.findBranch(f.parent.id, "x").get
    b.status shouldBe BranchStatus.Open
    f.store.listBranchMerges(b.id).map(_.status) shouldBe List(BranchMergeStatus.Failed)
  }

  it should "refuse altered tables as not fast-forwardable" in {
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
    f.service
      .propose("acme", f.parent.name, "x", f.admin1, None)
      .unsafeRunSync()
      .isRight shouldBe true
    f.service
      .merge("acme", f.parent.name, "x", None, f.admin2, None)
      .unsafeRunSync()
      .left
      .map(_.code) shouldBe
      Left("merge_unsupported")
  }

  "discard" should "allow the owner or an admin and tear down" in {
    val f = new Fixture
    f.grantConnect("alice")
    f.grantConnect("bob")
    f.create("x", actor = f.alice)
    f.service
      .discard("acme", f.parent.name, "x", BranchActor("bob", isAdmin = false), None)
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
    val pins = new PinnedSetResolver(f.store, (_, _) => fail("no reader needed"))
    pins.pinnedSnapshots(f.tenant.id, f.parent.name) shouldBe Set(10L)
    f.service
      .discard("acme", f.parent.name, "x", f.admin1, None)
      .unsafeRunSync()
      .isRight shouldBe true
    pins.pinnedSnapshots(f.tenant.id, f.parent.name) shouldBe Set.empty
    f.sup.deleteTenantDb("acme", f.parent.name).unsafeRunSync().isRight shouldBe true
    b.status shouldBe BranchStatus.Open
  }
