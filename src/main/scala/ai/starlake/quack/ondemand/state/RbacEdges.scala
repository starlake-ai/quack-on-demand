package ai.starlake.quack.ondemand.state

/** Membership edge rows. Tiny by design -- the FK pair is the full
  * primary key, so there is no surrogate id and no metadata. */

final case class UserRoleEdge(userId: String, roleId: String)

final case class UserGroupEdge(userId: String, groupId: String)

final case class GroupRoleEdge(groupId: String, roleId: String)