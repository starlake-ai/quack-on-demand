package ai.starlake.acl.model

enum Principal {
  case User(name: String)
  case Group(name: String)
  case AllUsers
}

object Principal {

  def user(name: String): Principal = User(name.toLowerCase)

  def group(name: String): Principal = Group(name.toLowerCase)
}
