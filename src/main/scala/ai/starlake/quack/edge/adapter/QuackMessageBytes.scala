package ai.starlake.quack.edge.adapter

/** Opaque tag distinguishing serialized request bytes (manager -> node)
  * from response bytes (node -> manager). Keeps the wire direction
  * visible at call sites in [[QuackProtocol]] (Task 6) so a misuse
  * (e.g. trying to decode a request as a response) becomes a type error.
  */
opaque type QuackRequestBytes = Array[Byte]

opaque type QuackResponseBytes = Array[Byte]

object QuackRequestBytes:
  inline def apply(b: Array[Byte]): QuackRequestBytes = b
  extension (b: QuackRequestBytes) def bytes: Array[Byte] = b

object QuackResponseBytes:
  inline def apply(b: Array[Byte]): QuackResponseBytes = b
  extension (b: QuackResponseBytes) def bytes: Array[Byte] = b

/** Mirror of duckdb-quack's `MessageType` enum (see
  * `quack_message.hpp:9-21`). Ordinals are NOT sequential — the upstream
  * pins specific numeric tags for wire compatibility, notably
  * `FETCH_REQUEST=7` (skipping 5 and 6) and `ERROR_RESPONSE=100`. When
  * upstream changes the enum, this file is the source of truth on the
  * Scala side and must be updated to match.
  */
enum MessageType(val wireOrdinal: Int):
  case Invalid            extends MessageType(0)
  case ConnectionRequest  extends MessageType(1)
  case ConnectionResponse extends MessageType(2)
  case PrepareRequest     extends MessageType(3)
  case PrepareResponse    extends MessageType(4)
  case FetchRequest       extends MessageType(7)
  case FetchResponse      extends MessageType(8)
  case AppendRequest      extends MessageType(9)
  case SuccessResponse    extends MessageType(10)
  case DisconnectMessage  extends MessageType(11)
  case ErrorResponse      extends MessageType(100)

object MessageType:
  /** Resolve a wire ordinal back to a [[MessageType]]. Returns `None` for
    * unrecognised ordinals so callers can surface "unexpected message
    * type N" rather than crashing. Note: this is keyed on
    * [[MessageType.wireOrdinal]] (the upstream tag), not Scala 3's
    * synthetic `Enum.ordinal` (which is the case's declaration index).
    */
  def fromWireOrdinal(o: Int): Option[MessageType] = values.find(_.wireOrdinal == o)