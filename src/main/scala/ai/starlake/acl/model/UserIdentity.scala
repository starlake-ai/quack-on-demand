package ai.starlake.acl.model

final case class UserIdentity private (
    name: String,
    groups: Set[String],
    originalName: String
) {

  def display: String = originalName

  override def equals(obj: Any): Boolean = obj match {
    case that: UserIdentity =>
      this.name == that.name &&
      this.groups == that.groups
    case _ => false
  }

  override def hashCode(): Int = (name, groups).hashCode()
}

object UserIdentity {

  def apply(name: String, groups: Set[String]): UserIdentity =
    new UserIdentity(
      name = name.toLowerCase,
      groups = groups.map(_.toLowerCase),
      originalName = name
    )
}
