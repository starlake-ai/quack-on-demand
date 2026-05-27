package ai.starlake.quack.edge

import ai.starlake.quack.model.PoolKey

final case class Session(
    connectionId: String,
    user: String,
    poolKey: PoolKey,
    pinnedNodeId: Option[String],
    txOpen: Boolean
)