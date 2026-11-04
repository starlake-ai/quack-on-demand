package ai.starlake.quack.ondemand.runtime

/** How a tenant, database or pool name reads as the value of a Kubernetes label. An API server
  * answers 422 to a value [[Rule]] refuses, and a name can be one: a name made before `Names`' rule
  * may start or end with `_`, and the reserved pools `__merge`, `__maint` and `__br_<id8>` do. A
  * name the rule accepts stays as it is, so every running pod and its selector stay too; any other
  * is wrapped, `qod.<name>.qod`. The wrap cures an end the rule refuses and no more: a wrapped name
  * past 63 characters, or holding anything else a label value cannot hold (a space or a `/`, in a
  * name made before `Names`' rule), still fails at the API. A slug holds no `.`, so a name made
  * under that rule cannot be mistaken for a wrapped one. A stored name holding `.` can: `qod.x.qod`
  * reads back as `x`, which only the count `discoverExisting` logs and its boot sweep of old-named
  * Secrets read.
  */
object PoolLabelValue:

  private val Rule    = "(([A-Za-z0-9][-A-Za-z0-9_.]*)?[A-Za-z0-9])?".r
  private val Wrapped = """qod\.(.+)\.qod""".r

  def encode(name: String): String =
    if Rule.matches(name) then name else s"qod.$name.qod"

  def decode(label: String): String = label match
    case Wrapped(name) => name
    case _             => label
