package ai.starlake.quack.model

/** What the manager wants the runtime backend to start. */
final case class NodeSpec(
    poolKey: PoolKey,
    nodeId: String,
    role: Role,
    metastore: Map[String, String], // pgHost, pgPort, ... -- opaque to manager
    s3: Map[String, String],        // optional keys; empty when local FS
    maxConcurrent: Int = 0          // 0 = unlimited; hard cap on in-flight queries
)