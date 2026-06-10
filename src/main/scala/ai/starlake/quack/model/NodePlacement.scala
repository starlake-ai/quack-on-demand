package ai.starlake.quack.model

/** A K8s pod-scheduling hint applied to every node in a [[PoolCohort]].
  *
  * `nodeSelector` maps directly to `PodSpec.nodeSelector` (hard label-match requirement);
  * `tolerations` map to `PodSpec.tolerations`. Both are optional -- an empty placement is
  * equivalent to "schedule anywhere" (the K8s default scheduler picks any matching node). The local
  * backend ignores placements entirely.
  */
final case class NodePlacement(
    nodeSelector: Map[String, String] = Map.empty,
    tolerations: List[NodeToleration] = Nil
):
  def isEmpty: Boolean = nodeSelector.isEmpty && tolerations.isEmpty

object NodePlacement:
  val empty: NodePlacement = NodePlacement()

/** Subset of K8s `Toleration` we accept on the wire. `operator` is one of `Equal` (default,
  * requires `value`) or `Exists` (matches any value). `effect` is one of
  * `NoSchedule | PreferNoSchedule | NoExecute`; None matches any effect.
  */
final case class NodeToleration(
    key: String,
    operator: String = "Equal",
    value: Option[String] = None,
    effect: Option[String] = None
)
