package ai.starlake.quack.route

enum RoutingDecision:
  case Use(nodeId: String)
  case Unavailable(reason: String)
  case PinnedNodeGone(nodeId: String)