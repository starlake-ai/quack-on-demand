package ai.starlake.quack.model

final case class Tenant(
    name: String,
    metastore: Map[String, String]
)
