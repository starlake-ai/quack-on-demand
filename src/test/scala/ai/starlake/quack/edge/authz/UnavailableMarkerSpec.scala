package ai.starlake.quack.edge.authz

import ai.starlake.quack.model.{PoolKey, Role, RunningNode}
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.RbacUser
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}
import java.time.Instant
import scala.jdk.CollectionConverters.*

/** The "authorization unavailable" marker: the golden value qod-node's authz_test computes too
  * (native/qod-node/tests/golden/unavailable-marker-v1.txt), and what [[NodeKeys.marksUnavailable]]
  * honors.
  */
class UnavailableMarkerSpec extends AnyFlatSpec with Matchers:

  private def golden(key: String): String =
    Files
      .readAllLines(Paths.get("native/qod-node/tests/golden/unavailable-marker-v1.txt"))
      .asScala
      .collectFirst { case l if l.startsWith(s"$key=") => l.drop(key.length + 1) }
      .get

  private val Secret = "qod-golden-node-key-secret-0123456789"
  private val Node   = "quack-acme-db-bi-1"

  "NodeHandles.unavailableMarker" should "produce exactly the golden marker qod-node appends" in {
    NodeHandles.nodeKeyHex(Secret, Node) shouldBe golden("key")
    val marker = NodeHandles.unavailableMarker(NodeHandles.nodeKey(Secret, Node), golden("nonce"))
    marker shouldBe golden("marker")
    marker should startWith(s" (qod-retry ${golden("nonce")}.")
    NodeHandles.unavailableMarkers(s"x$marker y") shouldBe List(
      (
        golden("nonce"),
        NodeHandles.unavailableMac(NodeHandles.nodeKey(Secret, Node), golden("nonce"))
      )
    )
    NodeHandles.withoutUnavailableMarkers(s"QoD: authorization unavailable, retry$marker") shouldBe
      "QoD: authorization unavailable, retry"
  }

  it should "find only well-formed markers" in {
    val nonce = "0" * 32
    List(
      s"(qod-retry ${"0" * 31}.${"0" * 64})",
      s"(qod-retry $nonce.${"0" * 63})",
      s"(qod-retry ${"A" * 32}.${"0" * 64})",
      s"(qod-retry $nonce ${"0" * 64})",
      s"qod-retry $nonce.${"0" * 64}"
    ).foreach(m => withClue(m)(NodeHandles.unavailableMarkers(m) shouldBe Nil))
    NodeHandles
      .unavailableMarkers(s"(qod-retry $nonce.${"1" * 64})(qod-retry $nonce.${"2" * 64})")
      .map(_._2) shouldBe List("1" * 64, "2" * 64)
  }

  private val Pool = PoolKey("acme", "acme_lake", "bi")
  private val User =
    EffectiveSet(RbacUser("u-1", Some("acme"), "alice", "user"), Nil, Nil, Nil, Nil)

  private def node(id: String) =
    RunningNode(id, Pool, Role.Dual, "127.0.0.1", 21900, "tok", None, None, Instant.EPOCH)

  private def keys(minted: Option[MintedHandles]) =
    new NodeKeys(
      Secret,
      "http://127.0.0.1:20901",
      versions = PolicyVersions.zero,
      minted = minted,
      readOnlyCatalogs = ReadOnlyCatalogs.none
    )

  private val Text = "Permission Error: QoD: authorization unavailable, retry"

  "NodeKeys.marksUnavailable" should "honor only a marker minted for this statement under this node's key" in {
    val k         = keys(Some(new MintedHandles(1000L)))
    val statement = NodePrincipal.of(Some(User), Pool, DecidePurpose.Live)
    val n         = k.mint(node(Node), statement).nonce
    val marker    = NodeHandles.unavailableMarker(k.key(Node), n)
    k.marksUnavailable(Node, statement, Text + marker) shouldBe true
    // Anywhere in the message, among fakes.
    k.marksUnavailable(Node, statement, s"$Text (qod-retry $n.${"0" * 64})$marker") shouldBe true
    // No marker; a wrong MAC; another node's key; another node's id for the same marker.
    k.marksUnavailable(Node, statement, Text) shouldBe false
    k.marksUnavailable(Node, statement, s"$Text (qod-retry $n.${"0" * 64})") shouldBe false
    val other = NodeHandles.unavailableMarker(k.key("quack-acme-db-bi-2"), n)
    k.marksUnavailable(Node, statement, Text + other) shouldBe false
    k.marksUnavailable("quack-acme-db-bi-2", statement, Text + marker) shouldBe false
    // The next statement's principal: an equal context, not the same object, so this statement's
    // marker is not its, and its own marker is not this statement's.
    val next = NodePrincipal.of(Some(User), Pool, DecidePurpose.Live)
    next shouldBe statement
    val nextNonce = k.mint(node(Node), next).nonce
    k.marksUnavailable(Node, next, Text + marker) shouldBe false
    val nextMarker = NodeHandles.unavailableMarker(k.key(Node), nextNonce)
    k.marksUnavailable(Node, next, Text + nextMarker) shouldBe true
    k.marksUnavailable(Node, statement, Text + nextMarker) shouldBe false
    // A nonce never minted.
    val unknown = NodeHandles.unavailableMarker(k.key(Node), "f" * 32)
    k.marksUnavailable(Node, statement, Text + unknown) shouldBe false
  }

  it should "not depend on MintedHandles: no registry wired, or the context gone from it" in {
    // Keys wired without a registry still recognize their own statement's marker.
    val bare      = keys(None)
    val statement = NodePrincipal.of(Some(User), Pool, DecidePurpose.Live)
    val n         = bare.mint(node(Node), statement).nonce
    bare.marksUnavailable(
      Node,
      statement,
      Text + NodeHandles.unavailableMarker(bare.key(Node), n)
    ) shouldBe
      true
    // The context-expired 503: the replica no longer holds the handle's context (evicted, or past
    // its grace), which is exactly when the node answers "authorization context expired; retry".
    // That refusal stays this statement's, so it stays retryable.
    val now    = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
    val minted = new MintedHandles(1000L, clock = () => now.get())
    val k      = keys(Some(minted))
    val held   = NodePrincipal.of(Some(User), Pool, DecidePurpose.Live)
    val h      = k.mint(node(Node), held)
    minted.get(h.nonce).map(_.context) shouldBe held.context
    now.set(h.expiresAt + NodeHandles.DecideGraceMs + 1)
    minted.get(h.nonce) shouldBe None
    val expired = s"$Text: authorization context expired; retry" +
      NodeHandles.unavailableMarker(k.key(Node), h.nonce)
    k.marksUnavailable(Node, held, expired) shouldBe true
    // Every other rejection still holds there: a foreign key, a wrong MAC, unmarked.
    k.marksUnavailable(
      Node,
      held,
      Text + NodeHandles.unavailableMarker(k.key("quack-acme-db-bi-2"), h.nonce)
    ) shouldBe false
    k.marksUnavailable(Node, held, s"$Text (qod-retry ${h.nonce}.${"0" * 64})") shouldBe false
    k.marksUnavailable(Node, held, Text) shouldBe false
  }

  it should "honor nothing for the system principal, which has no context" in {
    val k = keys(Some(new MintedHandles(1000L)))
    val m = k.mint(node(Node), NodePrincipal.System).nonce
    k.marksUnavailable(
      Node,
      NodePrincipal.System,
      Text + NodeHandles.unavailableMarker(k.key(Node), m)
    ) shouldBe
      false
  }
