package ai.starlake.quack.edge.adapter

import ai.starlake.quack.edge.authz.NodeHandles
import ai.starlake.quack.model.{PoolKey, Role, RunningNode}
import ai.starlake.quack.route.NodeUnhealthy
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/** What the pool listing says of an unhealthy node, from the probe's verdict: a fixed code and a
  * line the manager writes, never the node's own text (a tenant admin reads it).
  */
class ProbeVerdictCauseSpec extends AnyFlatSpec with Matchers:

  private val n = RunningNode(
    "quack--acme--db--bi--1",
    PoolKey("acme", "db", "bi"),
    Role.Dual,
    "127.0.0.1",
    23901,
    "tok",
    None,
    None,
    Instant.EPOCH
  )

  private def cause(v: ProbeVerdict) = ProbeVerdict.cause(n, v)

  "ProbeVerdict.cause" should "say nothing of a healthy node" in {
    cause(ProbeVerdict.Ok) shouldBe None
  }

  it should "name the node's address when nothing answers there" in {
    val c = cause(ProbeVerdict.Failed(QuackError.Transient("Connection refused", notSent = true)))
    c.map(_.code) shouldBe Some("unreachable")
    c.get.detail should include("127.0.0.1:23901")
  }

  it should "tell a lost probe from one the node refused, quoting neither error" in {
    val secret  = "password authentication failed for user qod_owner at pg.internal:5432"
    val lost    = cause(ProbeVerdict.Failed(QuackError.Transient(secret))).get
    val refused = cause(ProbeVerdict.Failed(QuackError.Permanent(secret))).get
    lost.code shouldBe "probe_lost"
    refused.code shouldBe "probe_refused"
    Seq(lost, refused).foreach { c =>
      c.detail should not include "pg.internal"
      c.detail should not include "qod_owner"
    }
  }

  it should "tell a key mismatch and a clock refusal from another handle refusal" in {
    def handle(text: String) = cause(ProbeVerdict.HandleRefused(text)).get
    handle(s"qod-node: handle ${NodeHandles.KeyMismatchRefusal}") shouldBe
      NodeUnhealthy.HandleKeyMismatch
    handle(s"qod-node: ${NodeHandles.ClockRefusals.head}") shouldBe NodeUnhealthy.HandleClock
    val other = handle("qod-node: nonce reused (node said 10.0.0.9)")
    other shouldBe NodeUnhealthy.HandleRefused
    other.detail should not include "10.0.0.9"
  }

  it should "carry the code of an unenforced node and of one with local files on" in {
    cause(ProbeVerdict.Unenforced).map(_.code) shouldBe Some("unenforced")
    cause(ProbeVerdict.LocalFilesNotOff("node x does not show local files off")) shouldBe
      Some(NodeUnhealthy.Cause("local_files_on", "node x does not show local files off"))
  }
