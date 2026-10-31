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
    None,
    DecidePurpose.Live
  )

  "MintedHandles" should "keep a context until DecideGraceMs past its handle's expiry" in {
    val clock  = new AtomicLong(1000L)
    val m      = new MintedHandles(maxEntries = 200_000L, clock = () => clock.get())
    val digest = NodeHandles.contextDigest(Some(Ctx))
    m.put("n-1", Ctx, digest, expiresAt = 61_000L)
    m.get("n-1") shouldBe Some(MintedHandles.Minted(Ctx, digest))
    clock.set(61_000L + NodeHandles.DecideGraceMs)
    m.get("n-1") shouldBe Some(MintedHandles.Minted(Ctx, digest))
    clock.set(61_000L + NodeHandles.DecideGraceMs + 1)
    m.get("n-1") shouldBe None
    m.get("unknown") shouldBe None
  }

  it should "stay within its bound" in {
    val m = new MintedHandles(maxEntries = 2)
    (1 to 5).foreach(i => m.put(s"n-$i", Ctx, "0" * 64, Long.MaxValue / 2))
    m.size should be <= 2L
  }

  "NodeKeys.handle" should "register the context of a principal that carries one, by nonce" in {
    val m    = new MintedHandles(maxEntries = 200_000L)
    val keys = new NodeKeys(
      "k" * 32,
      "http://127.0.0.1:20900",
      minted = Some(m),
      readOnlyCatalogs = ReadOnlyCatalogs.none,
      versions = ai.starlake.quack.edge.authz.PolicyVersions.zero
    )
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
    val text = keys.handle(node, NodePrincipal.of(Some(Ctx.effective), Pool, DecidePurpose.Live))
    val h = NodeHandles.verify(text, keys.key(node.nodeId), node.nodeId, System.currentTimeMillis())
    // With the digest the handle carries, computed once at mint.
    m.get(h.toOption.get.nonce) shouldBe Some(MintedHandles.Minted(Ctx, h.toOption.get.ctx))
    h.toOption.get.ctx shouldBe NodeHandles.contextDigest(Some(Ctx))
    val sys = keys.handle(node, NodePrincipal.System)
    m.get(
      NodeHandles
        .verify(sys, keys.key(node.nodeId), node.nodeId, System.currentTimeMillis())
        .toOption
        .get
        .nonce
    ) shouldBe None
    // A superuser needs no context: none is registered, though the handle digests it.
    val root = Ctx.effective.copy(user = Ctx.effective.user.copy(tenant = None))
    val su   = keys.handle(node, NodePrincipal.of(Some(root), Pool, DecidePurpose.Live))
    val suh = NodeHandles.verify(su, keys.key(node.nodeId), node.nodeId, System.currentTimeMillis())
    suh.toOption.get.principal.superuser shouldBe true
    suh.toOption.get.ctx shouldBe NodeHandles.contextDigest(
      Some(DecideContext(root, Pool, None, DecidePurpose.Live))
    )
    m.get(suh.toOption.get.nonce) shouldBe None
    m.size shouldBe 1L
  }

  "NodePrincipal.of with a pool" should "carry the session context, with the set's token restriction" in {
    val r   = ai.starlake.quack.ondemand.auth.TokenRestriction.Unrestricted.copy(branchOnly = true)
    val eff = Ctx.effective.copy(restriction = Some(r))
    NodePrincipal.of(Some(eff), Pool, DecidePurpose.Live).context shouldBe
      Some(DecideContext(eff, Pool, Some(r), DecidePurpose.Live))
    NodePrincipal.of(Some(eff), Pool, DecidePurpose.DryRun).context.map(_.purpose) shouldBe
      Some(DecidePurpose.DryRun)
    NodePrincipal.of(Some(eff.copy(system = true)), Pool, DecidePurpose.DryRun) shouldBe
      NodePrincipal.System
    NodePrincipal.of(None, Pool, DecidePurpose.Live) shouldBe NodePrincipal.Unbound
  }

  "NodeKeys.mint" should "be the handle, with the nonce its context is registered under" in {
    val m    = new MintedHandles(maxEntries = 200_000L)
    val keys = new NodeKeys(
      "s" * 40,
      "http://127.0.0.1:20900",
      minted = Some(m),
      readOnlyCatalogs = ReadOnlyCatalogs.none,
      versions = ai.starlake.quack.edge.authz.PolicyVersions.zero
    )
    val node = RunningNode(
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
    val minted = keys.mint(node, NodePrincipal.of(Some(Ctx.effective), Pool, DecidePurpose.DryRun))
    val h      =
      NodeHandles.verify(
        minted.text,
        keys.key(node.nodeId),
        node.nodeId,
        System.currentTimeMillis()
      )
    h.map(_.nonce) shouldBe Right(minted.nonce)
    m.get(minted.nonce).map(_.context.purpose) shouldBe Some(DecidePurpose.DryRun)
  }
