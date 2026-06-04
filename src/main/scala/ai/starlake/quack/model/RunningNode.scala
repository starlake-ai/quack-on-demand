package ai.starlake.quack.model

import java.time.Instant

/** What the runtime backend returns after a successful start. */
final case class RunningNode(
    nodeId: String,
    poolKey: PoolKey,
    role: Role,
    host: String,
    port: Int,
    token: String,
    pid: Option[Long],       // local mode only
    podName: Option[String], // k8s mode only
    startedAt: Instant,
    maxConcurrent: Int = 0,  // 0 = unlimited; mutable via /api/node/setMaxConcurrent
    lastSeen: Option[Instant] = None // refreshed by the health-probe; round-tripped to qodstate_node
)