package ai.starlake.quack.model

final case class RoleDistribution(writeonly: Int, readonly: Int, dual: Int):
  def total: Int = writeonly + readonly + dual
  def isValidFor(size: Int): Boolean = total == size
  def asRoleList: List[Role] =
    List.fill(writeonly)(Role.WriteOnly) ++
      List.fill(readonly)(Role.ReadOnly) ++
      List.fill(dual)(Role.Dual)