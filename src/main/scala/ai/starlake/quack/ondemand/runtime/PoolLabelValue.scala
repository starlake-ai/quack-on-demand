package ai.starlake.quack.ondemand.runtime

/** How a tenant, database or pool name reads as the value of a Kubernetes label. An API server
  * answers 422 to a value [[Rule]] refuses, and a name can be one: a name made before `Names`' rule
  * may start or end with `_`, and the reserved pools `__merge`, `__maint` and `__br_<id8>` do. A
  * name the rule accepts stays as it is, so every running pod and its selector stay too; any other
  * is wrapped, `qod.<name>.qod`. A slug holds no `.`, so no real name can be mistaken for a wrapped
  * one. An encoded name past 63 characters still fails at the API, as a long name always has.
  */
object PoolLabelValue:

  private val Rule    = "(([A-Za-z0-9][-A-Za-z0-9_.]*)?[A-Za-z0-9])?".r
  private val Wrapped = """qod\.(.+)\.qod""".r

  def encode(name: String): String =
    if Rule.matches(name) then name else s"qod.$name.qod"

  def decode(label: String): String = label match
    case Wrapped(name) => name
    case _             => label
