package ai.starlake.quack

import ai.starlake.quack.ondemand.ha.HaPreconditions
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The runtime names `runtimeType` accepts, in any case, and the one predicate per backend every
  * site goes through: an alias (`k8s`) must reach every check, the decide-URL boot check included.
  */
class RuntimeTypeSpec extends AnyFlatSpec with Matchers:

  "RuntimeType" should "accept each backend's names in any case" in {
    for rt <- List("local", "LOCAL", "Local") do withClue(rt)(RuntimeType.isLocal(rt) shouldBe true)
    for rt <- List("kubernetes", "KUBERNETES", "Kubernetes", "k8s", "K8S", "K8s") do
      withClue(rt)(RuntimeType.isKubernetes(rt) shouldBe true)
    for rt <- List("fleet", "FLEET", "Fleet") do withClue(rt)(RuntimeType.isFleet(rt) shouldBe true)
  }

  it should "keep the backends apart and know none of the unknown names" in {
    for rt <- List("local", "kubernetes", "k8s", "fleet", "docker", "", "kube", "k8") do
      withClue(rt) {
        val matches = List(
          RuntimeType.isLocal(rt),
          RuntimeType.isKubernetes(rt),
          RuntimeType.isFleet(rt)
        ).count(identity)
        matches shouldBe (if Set("local", "kubernetes", "k8s", "fleet")(rt) then 1 else 0)
      }
  }

  it should "name Kubernetes and fleet, under every alias, as the multi-manager backends" in {
    for rt <- List("kubernetes", "k8s", "K8S", "fleet", "FLEET") do
      withClue(rt) {
        RuntimeType.isMultiManager(rt) shouldBe true
        HaPreconditions.validate(haEnabled = true, rt) shouldBe Right(())
      }
    RuntimeType.isMultiManager("local") shouldBe false
    HaPreconditions.validate(haEnabled = true, "local").isLeft shouldBe true
  }

  "FleetConfig.isFleet" should "be the shared predicate" in {
    for rt <- List("fleet", "FLEET", "local", "k8s") do
      withClue(rt)(FleetConfig.isFleet(rt) shouldBe RuntimeType.isFleet(rt))
  }
