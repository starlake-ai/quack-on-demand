package ai.starlake.quack.edge.adapter

enum QuackError:
  case Transient(message: String)   // retryable (5xx, connect refused, timeout)
  case Permanent(message: String)   // not retryable (4xx, auth, parse error)