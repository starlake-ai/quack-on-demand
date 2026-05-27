package ai.starlake.quack.edge

import ai.starlake.quack.model.{PoolKey, StatementKind}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SessionRegistrySpec extends AnyFlatSpec with Matchers:

  private val key: PoolKey = PoolKey("acme", "sales")

  "SessionRegistry" should "create sessions with no pin and no transaction" in:
    val reg = new SessionRegistry
    val s = reg.open("conn-1", "alice", key)
    s.user           shouldBe "alice"
    s.poolKey        shouldBe key
    s.pinnedNodeId   shouldBe None
    s.txOpen         shouldBe false

  it should "pin on BEGIN and unpin on COMMIT" in:
    val reg = new SessionRegistry
    reg.open("conn-1", "alice", key)
    reg.onStatement("conn-1", StatementKind.Begin, executedOn = "wo1")
    reg.get("conn-1").get.pinnedNodeId shouldBe Some("wo1")
    reg.get("conn-1").get.txOpen       shouldBe true

    reg.onStatement("conn-1", StatementKind.Commit, executedOn = "wo1")
    reg.get("conn-1").get.pinnedNodeId shouldBe None
    reg.get("conn-1").get.txOpen       shouldBe false

  it should "unpin on ROLLBACK" in:
    val reg = new SessionRegistry
    reg.open("conn-1", "alice", key)
    reg.onStatement("conn-1", StatementKind.Begin, "wo1")
    reg.onStatement("conn-1", StatementKind.Rollback, "wo1")
    reg.get("conn-1").get.pinnedNodeId shouldBe None
    reg.get("conn-1").get.txOpen       shouldBe false

  it should "not pin for standalone DML/SELECT" in:
    val reg = new SessionRegistry
    reg.open("conn-1", "alice", key)
    reg.onStatement("conn-1", StatementKind.Select, "ro1")
    reg.onStatement("conn-1", StatementKind.Dml,    "wo1")
    reg.get("conn-1").get.pinnedNodeId shouldBe None

  it should "allow explicit invalidation of pinned node" in:
    val reg = new SessionRegistry
    reg.open("conn-1", "alice", key)
    reg.onStatement("conn-1", StatementKind.Begin, "wo1")
    reg.invalidatePin("conn-1")
    reg.get("conn-1").get.pinnedNodeId shouldBe None
    reg.get("conn-1").get.txOpen       shouldBe false

  it should "close and forget sessions" in:
    val reg = new SessionRegistry
    reg.open("conn-1", "alice", key)
    reg.close("conn-1")
    reg.get("conn-1") shouldBe None