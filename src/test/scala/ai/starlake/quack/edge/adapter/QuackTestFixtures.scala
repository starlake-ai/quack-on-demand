package ai.starlake.quack.edge.adapter

/** Test-only JNI fixture entry points. These mirror the
  * `QuackNativeBridge` object's loader pattern (its companion class init
  * runs `System.load` on libquackwire) and add the symbols defined in
  * `quackwire.cpp` under the
  * `Java_ai_starlake_quack_edge_adapter_QuackTestFixtures_00024_*`
  * names. The native lib is loaded exactly once at the JVM level, so
  * referencing `QuackNativeBridge` here is enough to guarantee the
  * symbols resolve.
  */
object QuackTestFixtures:
  // Force libquackwire to load. The `_` discard pins the initializer
  // anchor without ever calling it; the side effect of resolving the
  // `loaded` val in QuackNativeBridge is sufficient.
  private val _bridgeLoaded: Int = QuackNativeBridge.smokeAnswer()

  /** Returns the serialized bytes of a CONNECTION_RESPONSE message with
    * the supplied connection id.
    */
  @native def serializeSampleConnectionResponse(connectionId: String): Array[Byte]

  /** Returns the serialized bytes of an ERROR_RESPONSE message carrying
    * the supplied human-readable message.
    */
  @native def serializeSampleErrorResponse(message: String): Array[Byte]

  /** Returns the serialized bytes of a PREPARE_RESPONSE message. When
    * `withOneRowOneColChunk` is true, the message carries a single
    * `INTEGER` column with one row of value `42`. Otherwise the result
    * set is empty (no chunks).
    *
    * `columnName` controls the column name on the wire. Pass `null` (or
    * empty) for the default `"column_0"`. Threaded into the new
    * `extractColumnNames` Task 6 test and into the column-name
    * propagation check in `QuackProtocolSpec`.
    */
  @native def serializeSamplePrepareResponse(
      resultUuid: java.math.BigInteger,
      needsMoreFetch: Boolean,
      withOneRowOneColChunk: Boolean,
      columnName: String
  ): Array[Byte]

  /** Convenience overload preserving the older Task 4/5 call shape.
    * Routes through the same JNI entry with a null column name (which
    * the C++ side defaults to `"column_0"`).
    */
  def serializeSamplePrepareResponse(
      resultUuid: java.math.BigInteger,
      needsMoreFetch: Boolean,
      withOneRowOneColChunk: Boolean
  ): Array[Byte] =
    serializeSamplePrepareResponse(resultUuid, needsMoreFetch, withOneRowOneColChunk, null)

  /** Returns the serialized bytes of a FETCH_RESPONSE message. When
    * `withOneRowOneColChunk` is true, the message carries a single
    * `INTEGER` column with one row of value `42`. Otherwise the result
    * set is empty (no chunks).
    */
  @native def serializeSampleFetchResponse(withOneRowOneColChunk: Boolean): Array[Byte]