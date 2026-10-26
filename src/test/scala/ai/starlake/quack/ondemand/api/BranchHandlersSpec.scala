package ai.starlake.quack.ondemand.api

import ai.starlake.quack.{BranchingConfig, CatalogConfig}
import ai.starlake.quack.edge.RouterFailure
import ai.starlake.quack.edge.adapter.NodeLoadTracker
import ai.starlake.quack.model.{Branch, NodeSpec, Tenant, TenantDbKind}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.branch.{
  BranchService,
  ChangeCounter,
  MergeExecutor,
  MergeFence,
  TableChange
}
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{DbAdmin, InMemoryControlPlaneStore}
import ai.starlake.quack.ondemand.telemetry.AuditRecorder
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
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
        "/tmp/qod-branch-handlers-spec/acme_tpch/"
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
      mergeExecutor = new MergeExecutor:
        def run(spec: NodeSpec, batch: String): IO[Either[String, Unit]] = IO.pure(Right(()))
      ,
      mergeFence = new MergeFence:
        def arm(meta: Map[String, String], message: String, base: Long) = Right(())
        def disarm(meta: Map[String, String], message: String)          = ()
      ,
      counter = new ChangeCounter:
        def count(t: String, b: Branch, a: String, c: TableChange, f: Long, h: Long) =
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
    val handlers = new BranchHandlers(
      sup,
      service,
      preview,
      BranchHandlers.actorResolver(callers)
    )

    def merge(key: Option[String]) =
      handlers
        .merge(BranchMergeRequest("acme", parent.name, "feature"), key)(SuperuserScope)
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
        .propose(BranchOpRequest("acme", f.parent.name, "feature"), Some("garbage"))(SuperuserScope)
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

  "BranchHandlers.actorResolver" should "map each credential to its actor, and refuse the rest" in {
    val resolve = BranchHandlers.actorResolver(restCaller(sessionOf, patOf))
    resolve(Some(RootSessionTok)).map(a => (a.identity, a.isAdmin)) shouldBe
      Right((RootUsername, true))
    resolve(Some(SessionTok)).map(a => (a.identity, a.isAdmin)) shouldBe Right(("alice", true))
    resolve(Some(PatTok)).map(a => (a.identity, a.isAdmin)) shouldBe Right(("alice", true))
    resolve(None).left.toOption.map(_._1) shouldBe Some(StatusCode.Unauthorized)
    resolve(Some("qod_pat_unknown")).left.toOption.map(_._1) shouldBe
      Some(StatusCode.Unauthorized)
    resolve(Some("static-key-1")).left.toOption.map(_._1) shouldBe Some(StatusCode.Unauthorized)
  }

  it should "carry a PAT's branchOnly restriction into the actor" in {
    val branchOnlyPat = "qod_pat_branch_only"
    val patOf2: String => Option[ai.starlake.quack.ondemand.auth.PatPrincipal] = t =>
      if t == branchOnlyPat then
        patOf(PatTok).map(p => p.copy(restriction = p.restriction.copy(branchOnly = true)))
      else patOf(t)
    val resolve = BranchHandlers.actorResolver(restCaller(sessionOf, patOf2))
    resolve(Some(branchOnlyPat)).map(_.branchOnly) shouldBe Right(true)
    resolve(Some(PatTok)).map(_.branchOnly) shouldBe Right(false)
    resolve(Some(SessionTok)).map(_.branchOnly) shouldBe Right(false)
    resolve(Some(RootSessionTok)).map(_.branchOnly) shouldBe Right(false)
  }
