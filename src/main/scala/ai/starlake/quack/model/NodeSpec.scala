package ai.starlake.quack.model

/** What the manager wants the runtime backend to start. */
final case class NodeSpec(
    poolKey: PoolKey,
    nodeId: String,
    role: Role,
    metastore: Map[String, String], // pgHost, pgPort, ... -- opaque to manager
    s3: Map[String, String],        // optional keys; empty when local FS
    maxConcurrent: Int = 0,         // 0 = unlimited; hard cap on in-flight queries
    kindWire: String = "ducklake",  // passed to spawn script as `kind` env var
    extraSetupSql: String = "",     // resolved federation blob
    // K8s scheduling hint inherited from the node's cohort. None / empty
    // = no placement constraint (default scheduler decides). Backends
    // other than KubernetesQuackBackend ignore this field.
    placement: NodePlacement = NodePlacement.empty
)
