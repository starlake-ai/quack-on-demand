package ai.starlake.quack.edge.authz

import ai.starlake.quack.model.{PoolKey, Role, RunningNode}
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.RbacUser
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Paths}
import java.time.Instant
import scala.jdk.CollectionConverters.*

class NodeHandlesSpec extends AnyFlatSpec with Matchers:

  private val GoldenSecret = "qod-golden-node-key-secret-0123456789"
  private val GoldenNode   = "quack-acme-db-bi-1"
  private val Alice        =
    NodePrincipal("u-1", "alice", Some("acme"), superuser = false, system = false)

  private def golden(key: String): String =
    Files
      .readAllLines(Paths.get("native/qod-node/tests/golden/handle-v1.txt"))
      .asScala
      .collectFirst { case l if l.startsWith(s"$key=") => l.drop(key.length + 1) }
      .get

  "mint" should "produce exactly the golden handle qod-node's handle_test verifies" in {
    NodeHandles.nodeKeyHex(GoldenSecret, GoldenNode) shouldBe golden("key")
    NodeHandles.mint(
      GoldenSecret,
      GoldenNode,
      "http://10.0.0.1:20900",
      Alice,
      "acme/acme_db/bi",
      policyVersion = 42L,
      nowMs = 1761200000000L,
      nonce = "0123456789abcdef0123456789abcdef"
    ) shouldBe golden("handle")
  }

  it should "expire the handle LifetimeMs after minting and use a fresh nonce each time" in {
    val a = HandleInspector.payload(NodeHandles.mint("s" * 32, "n", "r", Alice, "p", 0L, 1000L))
    val b = HandleInspector.payload(NodeHandles.mint("s" * 32, "n", "r", Alice, "p", 0L, 1000L))
    a.hcursor.get[Long]("exp") shouldBe Right(1000L + NodeHandles.LifetimeMs)
    a.hcursor.get[String]("nonce") should not be b.hcursor.get[String]("nonce")
    a.hcursor.get[String]("nonce").map(_.matches("[0-9a-f]{32}")) shouldBe Right(true)
  }

  it should "refuse to vouch for no principal" in {
    val e = intercept[IllegalArgumentException](
      NodeHandles.mint("s" * 32, "n", "r", NodePrincipal.Unbound, "p", 0L, 1000L)
    )
    e.getMessage should include("no principal is bound to this statement")
  }

  "a node key" should "differ per node and per secret" in {
    NodeHandles.nodeKeyHex("a" * 32, "n1") should not be NodeHandles.nodeKeyHex("a" * 32, "n2")
    NodeHandles.nodeKeyHex("a" * 32, "n1") should not be NodeHandles.nodeKeyHex("b" * 32, "n1")
    NodeHandles.nodeKeyHex("a" * 32, "n1") should fullyMatch regex "[0-9a-f]{64}"
  }

  "prelude" should "be the exact statement qod-node's parse screen admits" in {
    NodeHandles.prelude("abc.0f") shouldBe "CALL system.main.qod_session('abc.0f')"
    // The golden handle's prelude, which handle_test reads the handle back out of
    // (qod::PreludeHandle): a spelling change on either side breaks one of the two.
    NodeHandles.prelude(golden("handle")) shouldBe golden("prelude")
  }

  "refusalText" should "drop DuckDB's excerpt of the statement, which would carry the handle" in {
    val refusal = "Permission Error: qod-node: session handle refused: handle MAC does not verify"
    val excerpt = "\n\nLINE 1: CALL system.main.qod_session('eyJ2IjoxfQ.0123abcd')\n" +
      "                                         ^"
    NodeHandles.refusalText(refusal + excerpt) shouldBe refusal
    NodeHandles.refusalText(s"$refusal\n\nLINE 12: SELECT 1") shouldBe refusal
    // Flattened onto one line, it still goes.
    NodeHandles.refusalText(s"$refusal LINE 3: CALL x('y')") shouldBe refusal
  }

  it should "keep a text with no excerpt as it is" in {
    val refusal = "qod-node: session handle refused: handle expired"
    NodeHandles.refusalText(refusal) shouldBe refusal
    NodeHandles.refusalText("Binder Error: no PIPELINE 2: here") shouldBe
      "Binder Error: no PIPELINE 2: here"
  }

  "NodeKeys" should "mint for the node's own id and pool, at its clock" in {
    val keys = new NodeKeys("k" * 32, "http://127.0.0.1:20900", () => 5000L)
    val node = RunningNode(
      "quack-acme-db-bi-2",
      PoolKey("acme", "acme_db", "bi"),
      Role.Dual,
      "127.0.0.1",
      21900,
      "tok",
      None,
      None,
      Instant.EPOCH
    )
    val p = HandleInspector.payload(keys.prelude(node, Alice))
    p.hcursor.get[String]("node") shouldBe Right("quack-acme-db-bi-2")
    p.hcursor.get[String]("pool") shouldBe Right("acme/acme_db/bi")
    p.hcursor.get[String]("replica") shouldBe Right("http://127.0.0.1:20900")
    p.hcursor.get[Long]("exp") shouldBe Right(5000L + NodeHandles.LifetimeMs)
    p.hcursor.downField("principal").get[String]("id") shouldBe Right("u-1")
    keys.keyHex(node.nodeId) shouldBe NodeHandles.nodeKeyHex("k" * 32, node.nodeId)
  }

  "NodePrincipal.of" should "name the user, flag a superuser, and map the system caller" in {
    def eff(tenant: Option[String], system: Boolean = false) =
      Some(
        EffectiveSet(RbacUser("u-9", tenant, "bob", "user"), Nil, Nil, Nil, Nil, system = system)
      )
    NodePrincipal.of(eff(Some("acme"))) shouldBe
      NodePrincipal("u-9", "bob", Some("acme"), superuser = false, system = false)
    NodePrincipal.of(eff(None)) shouldBe
      NodePrincipal("u-9", "bob", None, superuser = true, system = false)
    NodePrincipal.of(eff(None, system = true)) shouldBe NodePrincipal.System
    NodePrincipal.of(None) shouldBe NodePrincipal.Unbound
  }

  "NoPrincipalMarker" should "be part of qod-node's refusal" in {
    Files.readString(Paths.get("native/qod-node/src/authz.hpp")) should include(
      NodeHandles.NoPrincipalMarker
    )
  }

  "the refusals the health warning names a cause for" should "be qod-node's own words" in {
    val verify = Files.readString(Paths.get("native/qod-node/src/handle.cpp"))
    (NodeHandles.KeyMismatchRefusal :: NodeHandles.ClockRefusals).foreach(m =>
      verify should include(m)
    )
  }
