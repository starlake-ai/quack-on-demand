package ai.starlake.quack.model

final case class RoleDistribution(writeonly: Int, readonly: Int, dual: Int):
  def total: Int                     = writeonly + readonly + dual
  def isValidFor(size: Int): Boolean = total == size

  /** Count requested for one role, addressed by name. Prefer this over indexing into
    * [[asRoleList]]: that list is an ordered flattening, and any positional slice of it
    * (`drop`/`take`) silently couples to the spawn order.
    */
  def countFor(role: Role): Int = role match
    case Role.WriteOnly => writeonly
    case Role.ReadOnly  => readonly
    case Role.Dual      => dual

  /** Flattened roles in stable spawn order. Safe to map over wholesale; never slice it by position -
    * use [[countFor]] when you need a per-role count.
    */
  def asRoleList: List[Role] =
    RoleDistribution.spawnOrder.flatMap(role => List.fill(countFor(role))(role))

object RoleDistribution:
  /** Canonical order in which roles are materialized into nodes. The only place role ordering is
    * defined; [[RoleDistribution.asRoleList]] derives from it.
    */
  val spawnOrder: List[Role] = List(Role.WriteOnly, Role.ReadOnly, Role.Dual)
