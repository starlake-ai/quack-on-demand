package ai.starlake.quack.it

import ai.starlake.quack.CatalogConfig
import ai.starlake.quack.edge.{FlightSqlRouter, SessionRegistry}
import ai.starlake.quack.edge.adapter.{NodeLoadTracker, QuackHttpAdapter, QuackHttpClient}
import ai.starlake.quack.edge.authz.NodeKeys
import ai.starlake.quack.edge.meta.MetadataFilterRewriter
import ai.starlake.quack.edge.policy.ProtectedWriteGuard
import ai.starlake.quack.model.{
  NodeSpec,
  PoolKey,
  RoleDistribution,
  RunningNode,
  Tenant,
  TenantDbKind
}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.{
  CatalogPreviewHandlers,
  CatalogRestoreHandlers,
  ErrorResponse,
  IdentityFixtures,
  RestoreRequest,
  RestoreResponse
}
import ai.starlake.quack.ondemand.auth.SessionScope
import ai.starlake.quack.ondemand.branch.TableCommitFence
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.runtime.{LiveQodNode, QodNodeTestBinary, QuackBackend}
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RbacUser}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.apache.arrow.memory.RootAllocator
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.model.StatusCode

import java.nio.file.{Files, Path}
import java.time.Instant
import scala.collection.concurrent.TrieMap

/** A table restore on a real qod-node.
  *
  * The restore handler runs unchanged, bound to a real [[FlightSqlRouter]] the way `Main` binds its
  * write executor (the caller's own principal, `recordExecution`, the caller's commit message); the
  * node is a real qod-node serving a DuckLake catalog. The fence is a recording stand-in: the real
  * one needs the catalog in Postgres, and its own cases are `BranchMergeLiveSpec`'s.
  *
  * A real table cannot be made to take a minute without a minute's data, so the slow case's write
  * executor appends `WHERE sleep_ms(61000) IS NULL` to the replace's `AT (VERSION => n)` read: the
  * node then spends that minute inside the replace, one row at a time, as it would copying a large
  * table. Before the restore stopped sending its replace as one `BEGIN; ...; COMMIT` request, such
  * a restore failed `restore_failed` at COMMIT's bind (`this connection has no verified
  * principal`), for good: the node binds each statement of a request under the one handle sent
  * before it, and a handle lives 60 s.
  *
  * Cancelled without a qod-node build (`QOD_NODE_BIN`).
  */
class CatalogRestoreLiveSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val nodeReady = QodNodeTestBinary.path.nonEmpty

  /** The node's DuckLake catalog is attached under the tenant-db's own name, as the manager does.
    */
  private val Catalog = "acme_lake"
  private val poolKey = PoolKey("acme", Catalog, "bi")

  /** What the slow replace takes, in ms: past the 60 s a handle lives. */
  private val SleepMs = 61000

  private val AtVersion = """AT \(VERSION => \d+\)""".r

  private val store               = new InMemoryControlPlaneStore()
  private val tracker             = new NodeLoadTracker
  private val keys                = NodeKeys.ephemeral()
  private val allocator           = new RootAllocator()
  private val client              = new QuackHttpClient(allocator)
  private var dir: Path           = null
  private var live: LiveQodNode   = null
  private var sup: PoolSupervisor = null
  private val root                =
    EffectiveSet(RbacUser("u-root", None, "root", "admin"), Nil, Nil, Nil, Nil)

  override def beforeAll(): Unit =
    if nodeReady then
      dir = Files.createTempDirectory("qod-restore-live")
      val init =
        s"""INSTALL ducklake;
           |LOAD ducklake;
           |ATTACH 'ducklake:$dir/meta.ducklake' AS $Catalog (DATA_PATH '$dir/data');
           |CREATE SCHEMA $Catalog.tpch1;
           |""".stripMargin
      val backend = new QuackBackend:
        private val n          = TrieMap.empty[String, RunningNode]
        def start(s: NodeSpec) = IO {
          live = LiveQodNode.start(dir, init, nodeId = s.nodeId, keys = keys)
          val r = RunningNode(
            s.nodeId,
            s.poolKey,
            s.role,
            "127.0.0.1",
            live.port,
            live.token,
            Some(1L),
            None,
            Instant.EPOCH,
            maxConcurrent = s.maxConcurrent
          )
          n.put(s.nodeId, r); r
        }
        def stop(key: PoolKey, id: String) = IO { n.remove(id); () }
        def isAlive(id: String)            = n.contains(id)
        def discoverExisting()             = IO.pure(n.values.toList)
        def cleanup()                      = IO(n.clear())
      val admin = new ai.starlake.quack.ondemand.state.DbAdmin:
        def createDatabase(name: String): Either[String, Unit]   = Right(())
        def dropDatabase(name: String): Either[String, Unit]     = Right(())
        def holdsAnything(name: String): Either[String, Boolean] = Right(false)
      sup = new PoolSupervisor(backend, tracker, store, dbAdmin = admin)
      sup.createTenant(Tenant("acme")).unsafeRunSync()
      sup
        .createTenantDb(
          "acme",
          "lake",
          TenantDbKind.DuckLake,
          Map(
            "pgHost"     -> "127.0.0.1",
            "pgPort"     -> "0",
            "pgUser"     -> "u",
            "pgPassword" -> "p",
            "dbName"     -> "ignored",
            "schemaName" -> "tpch1"
          ),
          s"$dir/data",
          writerTrusted = true
        )
        .unsafeRunSync()
      sup.createPool(poolKey, RoleDistribution(0, 0, 1)).unsafeRunSync()

  override def afterAll(): Unit =
    if live != null then live.stop()
    allocator.close()
    if dir != null then
      scala.util.Try {
        val tree = Files.walk(dir)
        try tree.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
        finally tree.close()
      }

  /** `sql` on the node as the superuser, the rows as lines. */
  private def query(sql: String): List[String] =
    live
      .query(client, sql)
      .fold(err => throw new IllegalStateException(s"$sql -> $err"), identity)

  private def routerWith(stampWrites: Boolean): FlightSqlRouter =
    new FlightSqlRouter(
      sup,
      new SessionRegistry,
      tracker,
      new QuackHttpAdapter(client, tracker, keys),
      stampWrites = stampWrites,
      metadataFilterRewriter = new MetadataFilterRewriter(enabled = true),
      protectedWriteGuard = ProtectedWriteGuard.disabled
    )

  /** The write executor of `Main`'s restore, bound to `router` for the root caller. With `sleepMs`
    * the replace is slowed as the class comment says; the statement the handler hands it is
    * otherwise sent as it is, whatever its shape.
    */
  private def writeExecutor(
      router: FlightSqlRouter,
      sleepMs: Option[Int]
  ): CatalogPreviewHandlers.PreviewExecutor =
    (caller, key, sql) =>
      router.execute(
        caller.connectionId,
        caller.identity,
        key,
        sleepMs.fold(sql)(ms =>
          AtVersion.replaceAllIn(
            sql,
            m =>
              java.util.regex.Matcher
                .quoteReplacement(s"${m.matched} WHERE sleep_ms($ms) IS NULL")
          )
        ),
        effectiveSet = Some(root),
        recordExecution = true,
        patId = caller.patId,
        adminDispatch = false,
        commitMessage = caller.commitMessage
      )

  /** What a restore answered, the commit message its fence was armed with and the seconds it took.
    */
  private final case class Restored(
      answer: Either[(StatusCode, ErrorResponse), RestoreResponse],
      message: String,
      seconds: Long
  )

  /** A table `name` of one row, then two, and a restore of it to its one-row version through
    * `router`, its replace slowed by `sleepMs` and the whole bounded by `boundSec`.
    */
  private def restoreOneRowTable(
      name: String,
      router: FlightSqlRouter,
      sleepMs: Option[Int],
      boundSec: Int
  ): Restored =
    query(s"CREATE TABLE $Catalog.tpch1.$name (id BIGINT, note VARCHAR)")
    query(s"INSERT INTO $Catalog.tpch1.$name VALUES (1, 'one')")
    val to = query(s"SELECT max(snapshot_id) FROM ducklake_snapshots('$Catalog')").head.toLong
    query(s"INSERT INTO $Catalog.tpch1.$name VALUES (2, 'two')")
    val head = query(s"SELECT max(snapshot_id) FROM ducklake_snapshots('$Catalog')").head.toLong
    query(s"SELECT count(*) FROM $Catalog.tpch1.$name") shouldBe List("2")

    val reader = new DuckLakeCatalogReader(null):
      override def snapshotExists(id: Long): Boolean = id >= 0 && id <= head
      override def maxSnapshotId(): Option[Long]     = Some(head)
      override def currentTableInfo(schema: String, table: String): Option[(Long, Long)] =
        Some((7L, head))
      override def latestTableSnapshot(schema: String, table: String): Option[Long] = Some(head)
      override def tableExistsAt(schema: String, table: String, snapshotId: Long): Boolean = true
      override def snapshotByCommitMessage(commitMessage: String): Option[Long]            =
        query(
          s"SELECT max(snapshot_id) FROM ducklake_snapshots('$Catalog') " +
            s"WHERE commit_message = '${commitMessage.replace("'", "''")}'"
        ).headOption.flatMap(_.toLongOption)

    val armed    = scala.collection.mutable.ListBuffer.empty[String]
    val disarmed = scala.collection.mutable.ListBuffer.empty[String]
    val fence    = new TableCommitFence:
      def arm(m: Map[String, String], message: String, base: Long, tableId: Long) =
        armed += message; Right(())
      def disarm(m: Map[String, String], message: String): Unit = disarmed += message

    val handlers = new CatalogRestoreHandlers(
      sup,
      store,
      (_, _, _) => IO.never,
      writeExecutor(router, sleepMs),
      (_, _) => reader,
      CatalogConfig(previewMaxRows = 100, previewTimeoutSec = 30, restoreTimeoutSec = boundSec),
      IdentityFixtures.restCaller(),
      fence,
      catalogAlias = (_, _) => Catalog
    )
    val superuser: SessionScope.Resolver = _ => SessionScope.Superuser
    val began                            = System.nanoTime()
    val out                              = handlers
      .restore(
        RestoreRequest("acme", Catalog, "tpch1", name, to.toString, None, None),
        Some(IdentityFixtures.RootSessionTok)
      )(superuser)
      .unsafeRunSync()
    val seconds = (System.nanoTime() - began) / 1000000000L
    armed.toList.size shouldBe 1
    disarmed.toList shouldBe armed.toList
    Restored(out, armed.head, seconds)

  "a table restore" should "land when its replace runs past what one session handle lives" in {
    assume(nodeReady, "no qod-node binary (QOD_NODE_BIN or a local build)")
    val r = restoreOneRowTable(
      "slow_orders",
      routerWith(stampWrites = true),
      sleepMs = Some(SleepMs),
      boundSec = 240
    )
    withClue(s"restore answered ${r.answer} after ${r.seconds}s: ") {
      r.answer.isRight shouldBe true
    }
    // The replace really ran past a handle's life.
    r.seconds should be >= 61L
    // Its rows are those of the snapshot it restored, and its commit is the one the fence held to.
    query(s"SELECT note FROM $Catalog.tpch1.slow_orders ORDER BY id") shouldBe List("one")
    query(
      s"SELECT author FROM ducklake_snapshots('$Catalog') WHERE commit_message = '${r.message}'"
    ) shouldBe List("tenant:acme/user:root")
  }

  it should "carry its own commit message whatever QOD_STAMP_WRITES says" in {
    assume(nodeReady, "no qod-node binary (QOD_NODE_BIN or a local build)")
    val r = restoreOneRowTable(
      "quick_orders",
      routerWith(stampWrites = false),
      sleepMs = None,
      boundSec = 240
    )
    withClue(s"restore answered ${r.answer}: ") {
      r.answer.isRight shouldBe true
    }
    query(s"SELECT note FROM $Catalog.tpch1.quick_orders ORDER BY id") shouldBe List("one")
    // The commit fence is keyed on the message and the catalog settles a lost answer by it: the
    // commit must carry it, with the knob that stamps client writes off.
    query(
      s"SELECT author FROM ducklake_snapshots('$Catalog') WHERE commit_message = '${r.message}'"
    ) shouldBe List("tenant:acme/user:root")
  }

  it should "roll back, not commit behind its timeout, when its replace outlives restoreTimeoutSec" in {
    assume(nodeReady, "no qod-node build (QOD_NODE_BIN or a local build)")
    // A replace of 5 s under a 2 s bound: the bound cancels the call, which is seen once the replace
    // answers, and the connection is closed without its COMMIT, so the open transaction rolls back.
    val r = restoreOneRowTable(
      "late_orders",
      routerWith(stampWrites = true),
      sleepMs = Some(5000),
      boundSec = 2
    )
    r.answer match
      case Left((status, error)) =>
        status shouldBe StatusCode.BadGateway
        error.error shouldBe "restore_failed"
        error.message should include("timed out after 2s")
      case Right(done) => fail(s"a restore past its bound reported success: $done")
    // The table is as it was, and no commit carries the restore's message.
    query(s"SELECT note FROM $Catalog.tpch1.late_orders ORDER BY id") shouldBe
      List("one", "two")
    query(
      s"SELECT count(*) FROM ducklake_snapshots('$Catalog') WHERE commit_message = '${r.message}'"
    ) shouldBe List("0")
  }
