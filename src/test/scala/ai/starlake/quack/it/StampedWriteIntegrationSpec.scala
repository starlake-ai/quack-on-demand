package ai.starlake.quack.it

import ai.starlake.quack.edge.adapter.{QuackHttpClient, QuackResponse}
import ai.starlake.quack.ondemand.runtime.LiveQodNode.rows
import ai.starlake.quack.ondemand.runtime.{LiveQodNode, QodNodeTestBinary}
import cats.effect.unsafe.implicits.global
import org.apache.arrow.memory.RootAllocator
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}

/** EPIC P1 end-to-end: the stamped-write bracket against a REAL qod-node + ducklake node, reached
  * the way the manager reaches it: the native client, each PREPARE (the stamp, the statement, the
  * COMMIT) after its own session handle. Starts its own node on a scratch port; skips (assume) when
  * there is no qod-node build.
  */
class StampedWriteIntegrationSpec extends AnyFunSpec with Matchers with BeforeAndAfterAll:

  private val token             = "it-stamp-token"
  private var dir: Path         = null
  private var live: LiveQodNode = null

  private def endpoint: String = live.endpoint

  // Shared allocator: one instance for the whole suite, closed in afterAll.
  private val allocator = new RootAllocator()

  override def beforeAll(): Unit =
    if QodNodeTestBinary.path.nonEmpty then
      dir = Files.createTempDirectory("qod-stamp-it")
      val init =
        s"""INSTALL ducklake; LOAD ducklake;
           |ATTACH 'ducklake:$dir/meta.ducklake' AS lake (DATA_PATH '$dir/data');
           |USE lake;
           |CREATE TABLE t(i INT);
           |""".stripMargin
      live = LiveQodNode.start(dir, init, token = token)

  override def afterAll(): Unit =
    // SIGKILL: the node leaves nothing behind.
    if live != null then live.stop()
    allocator.close()
    if dir != null then
      // Give the node a moment to release file handles, then delete recursively.
      Thread.sleep(200)
      try
        Files
          .walk(dir)
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(p => Files.delete(p))
      catch
        case e: Exception =>
          System.err.println(
            s"[StampedWriteIntegrationSpec] afterAll: could not fully delete $dir: $e"
          )

  private def client() =
    new QuackHttpClient(allocator)

  private def latestSnapshot(c: QuackHttpClient): (Any, Any) =
    val r = rows(
      c.query(
        endpoint,
        token,
        "SELECT author, commit_message FROM ducklake_snapshots('lake') ORDER BY snapshot_id DESC LIMIT 1",
        None,
        live.authorize()
      ).unsafeRunSync()
    )
    (r.head.head, r.head(1))

  private val prelude =
    "BEGIN; CALL ducklake_set_commit_message('lake', 'tenant:acme/user:alice', 'flightsql insert')"

  it("preserves the DML Count and stamps the snapshot"):
    assume(QodNodeTestBinary.path.nonEmpty, "no qod-node binary (QOD_NODE_BIN or a local build)")
    val c = client()
    val r = rows(
      c.queryStamped(
        endpoint,
        token,
        live.authorize(),
        prelude,
        "USE lake.main; INSERT INTO lake.t VALUES (1)"
      ).unsafeRunSync()
    )
    r.head.head.toString shouldBe "1" // exact Count
    val (author, msg) = latestSnapshot(c)
    author.toString shouldBe "tenant:acme/user:alice"
    msg.toString shouldBe "flightsql insert"

  it("leaves author null on a plain unstamped write"):
    assume(QodNodeTestBinary.path.nonEmpty, "no qod-node binary (QOD_NODE_BIN or a local build)")
    val c = client()
    rows(
      c.query(
        endpoint,
        token,
        "USE lake.main; INSERT INTO lake.t VALUES (2)",
        None,
        live.authorize()
      ).unsafeRunSync()
    )
    val (author, _) = latestSnapshot(c)
    author shouldBe (null: Any)

  it("fail-open: a broken prelude still lands the write, unstamped"):
    assume(QodNodeTestBinary.path.nonEmpty, "no qod-node binary (QOD_NODE_BIN or a local build)")
    val c   = client()
    val bad = "BEGIN; CALL ducklake_set_commit_message('no_such_catalog', 'a', 'm')"
    val r   = rows(
      c.queryStamped(
        endpoint,
        token,
        live.authorize(),
        bad,
        "USE lake.main; INSERT INTO lake.t VALUES (3)"
      ).unsafeRunSync()
    )
    r.head.head.toString shouldBe "1"
    val (author, _) = latestSnapshot(c)
    author shouldBe (null: Any)

  it("commits even when the caller closes without draining"):
    assume(QodNodeTestBinary.path.nonEmpty, "no qod-node binary (QOD_NODE_BIN or a local build)")
    val c    = client()
    val resp = c
      .queryStamped(
        endpoint,
        token,
        live.authorize(),
        prelude,
        "USE lake.main; INSERT INTO lake.t VALUES (4)"
      )
      .unsafeRunSync()
    resp match
      case ok: QuackResponse.Ok => ok.close() // no drain
      case failed               => fail(s"the stamped write was refused: $failed")
    val r = rows(
      c.query(
        endpoint,
        token,
        "SELECT count(*) FROM lake.t WHERE i = 4",
        None,
        live.authorize()
      ).unsafeRunSync()
    )
    r.head.head.toString shouldBe "1"

  // The node admits a handle once, and the client mints one per PREPARE: a stamped write is three
  // (the stamp, the statement, the COMMIT), each a fresh handle the node admitted.
  it("mints one fresh handle per PREPARE of a stamped write, and every one is admitted"):
    assume(QodNodeTestBinary.path.nonEmpty, "no qod-node binary (QOD_NODE_BIN or a local build)")
    val c                      = client()
    val fresh                  = live.authorize()
    val mints                  = new java.util.concurrent.atomic.AtomicInteger(0)
    val counting: () => String = () =>
      mints.incrementAndGet()
      fresh()
    val r = rows(
      c.queryStamped(
        endpoint,
        token,
        counting,
        prelude,
        "USE lake.main; INSERT INTO lake.t VALUES (5)"
      ).unsafeRunSync()
    )
    r.head.head.toString shouldBe "1"
    mints.get() shouldBe 3
    rows(
      c.query(
        endpoint,
        token,
        "SELECT count(*) FROM lake.t WHERE i = 5",
        None,
        live.authorize()
      ).unsafeRunSync()
    ).head.head.toString shouldBe "1"

  it("refuses a single-use handle at the statement's PREPARE, and commits nothing"):
    assume(QodNodeTestBinary.path.nonEmpty, "no qod-node binary (QOD_NODE_BIN or a local build)")
    val c    = client()
    val once = live.keys.prelude(live.node, LiveQodNode.Superuser)
    // The stamp installs it; the statement's PREPARE then replays it.
    LiveQodNode.lines(
      c.queryStamped(
        endpoint,
        token,
        () => once,
        prelude,
        "USE lake.main; INSERT INTO lake.t VALUES (6)"
      ).unsafeRunSync()
    ) match
      case Left(refusal) => refusal should include("already used")
      case other         => fail(s"expected the replayed handle to be refused, got $other")
    rows(
      c.query(
        endpoint,
        token,
        "SELECT count(*) FROM lake.t WHERE i = 6",
        None,
        live.authorize()
      ).unsafeRunSync()
    ).head.head.toString shouldBe "0"
