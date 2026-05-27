package ai.starlake.quack.edge.auth

/** Result of successful authentication from any provider. */
case class AuthenticatedProfile(
    username: String,
    role: String,
    groups: Set[String],
    claims: Map[String, String],
    authMethod: String
)