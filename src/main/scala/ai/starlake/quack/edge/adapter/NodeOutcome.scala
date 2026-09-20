package ai.starlake.quack.edge.adapter

import org.apache.arrow.vector.ipc.ArrowReader

/** Transport-neutral outcome of one node call, so the routing pipeline can run around any wire: the
  * Arrow path (`QuackHttpAdapter.send`, value = a streaming reader) and the native Quack relay
  * (value = the node's raw response bytes plus the connection that produced them).
  *
  *   - `Ok`: the node answered; `close` releases whatever `value` holds (reader, node connection).
  *   - `Transient`: retryable (5xx, refused, timeout); the router tries one other node.
  *   - `Permanent`: the node rejected the statement; the router classifies the message.
  */
enum NodeOutcome[+A]:
  case Ok(value: A, latencyMs: Long, close: () => Unit)
  case Transient(message: String, latencyMs: Long)
  case Permanent(message: String, latencyMs: Long)

  def latency: Long = this match
    case Ok(_, l, _)     => l
    case Transient(_, l) => l
    case Permanent(_, l) => l

object NodeOutcome:
  def fromQuackResponse(r: QuackResponse): NodeOutcome[ArrowReader] = r match
    case QuackResponse.Ok(rows, latency, close)                 => Ok(rows, latency, close)
    case QuackResponse.Failed(QuackError.Transient(m), latency) => Transient(m, latency)
    case QuackResponse.Failed(QuackError.Permanent(m), latency) => Permanent(m, latency)
