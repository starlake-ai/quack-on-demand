package ai.starlake.quack.edge.authz

import ai.starlake.quack.edge.adapter.{
  NodeLoadTracker,
  QuackError,
  QuackHttpAdapter,
  QuackHttpClient,
  QuackResponse
}
import ai.starlake.quack.model.{PoolKey, Role, RunningNode}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.apache.arrow.memory.RootAllocator
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/** The system-principal handles the record route admits (review I1): its own bound, apart from
  * [[IssuedHandles]], filled only by a call site's explicit flag, never by a statement's text
  * (security review M3), with the node, pool and expiry recorded as minted.
  */
class SearchSystemHandlesSpec extends AnyFlatSpec with Matchers:

  private val Node =
    RunningNode(
      "quack-acme-lake-bi-1",
      PoolKey("acme", "lake", "bi"),
      Role.Dual,
      "127.0.0.1",
      21900,
      "tok",
      None,
      None,
      Instant.EPOCH
    )
  private val Pool = NodeHandles.pool(Node.poolKey)

  "SearchSystemHandles" should "keep the node, pool and expiry until DecideGraceMs past it" in {
    val clock = new AtomicLong(1000L)
    val r     = new SearchSystemHandles(clock = () => clock.get())
    r.put("n-1", "node-1", Pool, expiresAt = 61_000L)
    r.get("n-1") shouldBe Some(SearchSystemHandles.Recorded("node-1", Pool, 61_000L))
    clock.set(61_000L + NodeHandles.DecideGraceMs)
    r.get("n-1").isDefined shouldBe true
    clock.set(61_000L + NodeHandles.DecideGraceMs + 1)
    r.get("n-1") shouldBe None
    r.get("unknown") shouldBe None
  }

  it should "stay within its bound, 4096 by default" in {
    val r = new SearchSystemHandles()
    (1 to 6000).foreach(i => r.put(s"n-$i", "node-1", Pool, Long.MaxValue / 2))
    r.size should be <= 4096L
    val small = new SearchSystemHandles(maxEntries = 2)
    (1 to 5).foreach(i => small.put(s"n-$i", "node-1", Pool, Long.MaxValue / 2))
    small.size should be <= 2L
  }

  /** A client that answers every statement Failed after minting its handles, recording nothing of
    * the statement: what matters is which handles the adapter recorded.
    */
  private final class MintingClient extends QuackHttpClient(new RootAllocator()):
    override def query(
        endpoint: String,
        token: String,
        sql: String,
        session: Option[String],
        authorize: () => String
    ): IO[QuackResponse] =
      IO { authorize(); QuackResponse.Failed(QuackError.Permanent("canned")) }

  private def adapter(sys: SearchSystemHandles) =
    new QuackHttpAdapter(new MintingClient, new NodeLoadTracker, NodeKeys.ephemeral(), Some(sys))

  "the adapter" should "record a system handle for a call its call site flags, with node and pool" in {
    val sys = new SearchSystemHandles()
    adapter(sys)
      .send(
        Node,
        "SELECT * FROM qod_search_build_segment((SELECT 1), 't', 'p', 1, 1)",
        NodePrincipal.System,
        None,
        recordLoad = false,
        searchSystemCall = true
      )
      .unsafeRunSync()
    sys.size shouldBe 1L
  }

  it should "not record a system statement whose text merely holds qod_search(" in {
    val sys = new SearchSystemHandles()
    val a   = adapter(sys)
    List(
      "SELECT * FROM qod_search((SELECT rowid FROM t), 't', ['body'], 'x')",
      "SELECT 'qod_search_build_segment(' AS name",
      "SELECT table_name FROM duckdb_tables() WHERE table_name = 'qod_search(x)'"
    ).foreach { sql =>
      a.send(Node, sql, NodePrincipal.System, None, recordLoad = false).unsafeRunSync()
    }
    sys.size shouldBe 0L
  }

  it should "record no other principal's handle, even on a flagged call" in {
    val sys  = new SearchSystemHandles()
    val root = NodePrincipal("u-root", "root", None, superuser = true, system = false)
    adapter(sys)
      .send(Node, "SELECT 1", root, None, recordLoad = false, searchSystemCall = true)
      .unsafeRunSync()
    sys.size shouldBe 0L
  }

  it should "record nothing with no registry (None)" in {
    val m = NodeKeys.ephemeral().mint(Node, NodePrincipal.System)
    QuackHttpAdapter.recordSearchSystem(None, Node, NodePrincipal.System, searchSystemCall = true)(
      m
    )
    succeed
  }

  "a burst of 100,000 probe handles" should "leave IssuedHandles and SearchSystemHandles empty" in {
    val issued = new IssuedHandles(maxEntries = 200_000L)
    val sys    = new SearchSystemHandles()
    val keys   = new NodeKeys(
      NodeKeys.randomSecret(),
      "http://127.0.0.1:20901",
      versions = PolicyVersions.zero,
      issued = Some(issued),
      readOnlyCatalogs = ReadOnlyCatalogs.none
    )
    val before = issued.size
    (1 to 100_000).foreach { _ =>
      val m = keys.mint(Node, NodePrincipal.System)
      QuackHttpAdapter.recordSearchSystem(Some(sys), Node, NodePrincipal.System, false)(m)
    }
    issued.size shouldBe before
    sys.size shouldBe 0L
  }
