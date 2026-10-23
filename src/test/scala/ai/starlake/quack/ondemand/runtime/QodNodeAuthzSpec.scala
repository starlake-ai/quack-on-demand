package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.edge.adapter.*
import ai.starlake.quack.edge.authz.{NodeHandles, NodeKeys, NodePrincipal}
import cats.effect.unsafe.implicits.global
import org.apache.arrow.memory.RootAllocator
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

/** A real qod-node, reached through the manager's own native client: a statement runs only after a
  * session handle for this node, signed with its key, unused, for the connection's one principal.
  * Cancelled without a qod-node build.
  */
class QodNodeAuthzSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private val allocator         = new RootAllocator()
  private val client            = new QuackHttpClient(allocator)
  private var live: LiveQodNode = null
  private val Alice             =
    NodePrincipal("u-1", "alice", Some("acme"), superuser = false, system = false)

  override def beforeAll(): Unit =
    if QodNodeTestBinary.path.nonEmpty then
      live = LiveQodNode.start(
        Files.createTempDirectory("qod-node-authz"),
        "CREATE TABLE answers AS SELECT 42 AS v;"
      )

  override def afterAll(): Unit =
    if live != null then live.stop()
    allocator.close()

  private def node: LiveQodNode =
    assume(live != null, "no qod-node binary (QOD_NODE_BIN or a local build)")
    live

  private def refused(resp: QuackResponse, needle: String): Unit =
    LiveQodNode.lines(resp) match
      case Left(m) => m should include(needle)
      case other   => fail(s"expected a refusal mentioning '$needle', got $other")

  "a statement" should "be refused without a session handle" in
    refused(
      client.queryWithoutHandle(node.endpoint, node.token, "SELECT 1").unsafeRunSync(),
      NodeHandles.NoPrincipalMarker
    )

  it should "run after a session handle for this node" in {
    LiveQodNode.lines(
      client
        .query(node.endpoint, node.token, "SELECT v FROM answers", None, node.authorize(Alice))
        .unsafeRunSync()
    ) shouldBe Right(List("42"))
  }

  "a handle" should "not install twice" in {
    val once = node.keys.prelude(node.node, Alice)
    LiveQodNode.lines(
      client.query(node.endpoint, node.token, "SELECT 1", None, () => once).unsafeRunSync()
    ) shouldBe Right(List("1"))
    refused(
      client.query(node.endpoint, node.token, "SELECT 1", None, () => once).unsafeRunSync(),
      "already used"
    )
  }

  it should "not verify on another node, nor under another key" in {
    // Every node has its own key, so a handle minted for another node fails at the MAC, before the
    // node named in its payload is compared with this one's.
    val elsewhere = node.keys.prelude(node.node.copy(nodeId = "quack-elsewhere-1"), Alice)
    refused(
      client.query(node.endpoint, node.token, "SELECT 1", None, () => elsewhere).unsafeRunSync(),
      "does not verify"
    )
    val forged = NodeKeys.ephemeral().prelude(node.node, Alice)
    refused(
      client.query(node.endpoint, node.token, "SELECT 1", None, () => forged).unsafeRunSync(),
      "does not verify"
    )
  }

  "a connection" should "keep the principal its first handle installed" in {
    given cats.effect.unsafe.IORuntime = cats.effect.unsafe.IORuntime.global
    val protocol                       = new QuackProtocol(
      new QuackProtocol.JdkHttpTransport(java.net.http.HttpClient.newHttpClient()),
      allocator
    )
    val conn = protocol.open(node.endpoint, node.token).unsafeRunSync()
    try
      conn.executeDiscard(node.keys.prelude(node.node, Alice)).unsafeRunSync()
      val mallory = NodePrincipal("u-2", "mallory", Some("acme"), superuser = false, system = false)
      val e       = intercept[QuackWireError.Permanent](
        conn.executeDiscard(node.keys.prelude(node.node, mallory)).unsafeRunSync()
      )
      e.msg should include("another principal")
    finally conn.close().unsafeRunSync()
  }

  "the parse screen" should "not be replaceable from a session" in
    refused(
      client
        .query(
          node.endpoint,
          node.token,
          "SET GLOBAL quack_authorization_function = 'x'",
          None,
          node.authorize()
        )
        .unsafeRunSync(),
      "cannot be changed"
    )

  "the health probe" should "pass an enforcing node, as the system principal" in {
    new QuackHttpAdapter(client, new NodeLoadTracker, node.keys)
      .probe(node.node)
      .unsafeRunSync() shouldBe true
  }
