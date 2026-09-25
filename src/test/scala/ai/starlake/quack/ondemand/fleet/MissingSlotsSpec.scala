package ai.starlake.quack.ondemand.fleet

import ai.starlake.quack.model.{PoolKey, Role, RoleDistribution, RunningNode}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant

class MissingSlotsSpec extends AnyFlatSpec with Matchers:
  private def node(id: String, role: Role) =
    RunningNode(id, PoolKey("a", "d", "p"), role, "h", 1, "t", None, None, Instant.EPOCH)

  "compute" should "return every slot for an empty pool in spawn order" in {
    MissingSlots.compute(RoleDistribution(writeonly = 1, readonly = 2, dual = 0), Nil) shouldBe
      List((1, Role.WriteOnly), (2, Role.ReadOnly), (3, Role.ReadOnly))
  }
  it should "return only the per-role deficit, indexed above the highest present index" in {
    val present = List(node("quack-a-d-p-1", Role.WriteOnly), node("quack-a-d-p-3", Role.ReadOnly))
    MissingSlots.compute(RoleDistribution(1, 2, 0), present) shouldBe List((4, Role.ReadOnly))
  }
  it should "return nothing at or above target" in {
    val present = List(node("quack-a-d-p-1", Role.Dual), node("quack-a-d-p-2", Role.Dual))
    MissingSlots.compute(RoleDistribution(0, 0, 1), present) shouldBe Nil
  }
