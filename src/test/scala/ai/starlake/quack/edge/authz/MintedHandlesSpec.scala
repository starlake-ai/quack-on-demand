package ai.starlake.quack.edge.authz

import ai.starlake.quack.model.{PoolKey, Role, RunningNode}
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import ai.starlake.quack.ondemand.state.RbacUser
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

class MintedHandlesSpec extends AnyFlatSpec with Matchers:

  private val Pool = PoolKey("acme", "acme_lake", "bi")
  private val Ctx  = DecideContext(
    EffectiveSet(RbacUser("u-1", Some("acme"), "alice", "user"), Nil, Nil, Nil, Nil),
    Pool,
    None
  )

  "MintedHandles" should "keep a context until DecideGraceMs past its handle's expiry" in {
    val clock = new AtomicLong(1000L)
    val m     = new MintedHandles(clock = () => clock.get())
    m.put("n-1", Ctx, expiresAt = 61_000L)
    m.get("n-1") shouldBe Some(Ctx)
    clock.set(61_000L + NodeHandles.DecideGraceMs)
    m.get("n-1") shouldBe Some(Ctx)
    clock.set(61_000L + NodeHandles.DecideGraceMs + 1)
    m.get("n-1") shouldBe None
    m.get("unknown") shouldBe None
  }

  it should "stay within its bound" in {
    val m = new MintedHandles(maxEntries = 2)
    (1 to 5).foreach(i => m.put(s"n-$i", Ctx, Long.MaxValue / 2))
    m.size should be <= 2L
  }

  "NodeKeys.handle" should "register the context of a principal that carries one, by nonce" in {
    val m    = new MintedHandles()
    val keys = new NodeKeys("k" * 32, "http://127.0.0.1:20900", minted = Some(m))
    val node =
      RunningNode(
        "quack-acme-lake-bi-1",
        Pool,
        Role.Dual,
        "127.0.0.1",
        21900,
        "tok",
        None,
        None,
        Instant.EPOCH
      )
    val text = keys.handle(node, NodePrincipal.of(Some(Ctx.effective), Pool))
    val h = NodeHandles.verify(text, keys.key(node.nodeId), node.nodeId, System.currentTimeMillis())
    m.get(h.toOption.get.nonce) shouldBe Some(Ctx)
    val sys = keys.handle(node, NodePrincipal.System)
    m.get(
      NodeHandles
        .verify(sys, keys.key(node.nodeId), node.nodeId, System.currentTimeMillis())
        .toOption
        .get
        .nonce
    ) shouldBe None
  }

  "NodePrincipal.of with a pool" should "carry the session context, with the set's token restriction" in {
    val r   = ai.starlake.quack.ondemand.auth.TokenRestriction.Unrestricted.copy(branchOnly = true)
    val eff = Ctx.effective.copy(restriction = Some(r))
    NodePrincipal.of(Some(eff), Pool).context shouldBe Some(DecideContext(eff, Pool, Some(r)))
    NodePrincipal.of(Some(eff.copy(system = true)), Pool) shouldBe NodePrincipal.System
    NodePrincipal.of(None, Pool) shouldBe NodePrincipal.Unbound
  }
