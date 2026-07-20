package ai.starlake.quack.edge.adapter

import scala.util.Using

object QuackNativeBridge:
  @native def smokeAnswer(): Int

  /** Serializes a `CONNECTION_REQUEST` message with the supplied auth token. The returned bytes are
    * the binary `QuackMessage` ready to POST to a Quack node's `/quack` endpoint.
    */
  @native def serializeConnectionRequest(token: String): Array[Byte]

  /** Serializes a `PREPARE_REQUEST` message binding `sql` to an existing connection identified by
    * `connectionId`.
    */
  @native def serializePrepareRequest(connectionId: String, sql: String): Array[Byte]

  /** Serializes a `FETCH_REQUEST` message that asks the node to deliver the next batch for the
    * prepared result identified by `resultUuid`.
    *
    * `resultUuid` is the 128-bit identifier threaded through from the `PREPARE_RESPONSE`; pass it
    * as a `java.math.BigInteger` so the JNI layer can unpack it into the native
    * `hugeint_t {upper:int64, lower:uint64}` pair without sign-mangling on the boundary.
    */
  @native def serializeFetchRequest(
      connectionId: String,
      resultUuid: java.math.BigInteger
  ): Array[Byte]

  /** Serializes a `DISCONNECT_MESSAGE` that releases server-side state for the supplied connection.
    */
  @native def serializeDisconnect(connectionId: String): Array[Byte]

  /** Parses the serialized `QuackMessage` in `bytes` and returns the ordinal of its `MessageType`.
    * Matches [[MessageType.ordinal]] for the upstream `duckdb::MessageType` enum (non-sequential --
    * `FETCH_REQUEST` is 7, `ERROR_RESPONSE` is 100, etc).
    *
    * Raises `RuntimeException` if `bytes` is null or cannot be parsed as a `QuackMessage`.
    */
  @native def parseMessageType(bytes: Array[Byte]): Int

  /** Parses `bytes` as a `CONNECTION_RESPONSE` and returns the server-issued connection id. Raises
    * `RuntimeException` if the message is a different type.
    */
  @native def extractConnectionId(bytes: Array[Byte]): String

  /** Parses `bytes` as an `ERROR_RESPONSE` and returns the server's human-readable error message.
    * Raises `RuntimeException` if the message is a different type.
    */
  @native def extractErrorMessage(bytes: Array[Byte]): String

  /** Parses `bytes` as a `PREPARE_RESPONSE` and returns its `needs_more_fetch` flag. Only
    * `PREPARE_RESPONSE` carries this flag in the current submodule pin; passing a `FETCH_RESPONSE`
    * raises a `RuntimeException`.
    */
  @native def needsMoreFetch(bytes: Array[Byte]): Boolean

  /** Parses `bytes` as a `PREPARE_RESPONSE` and returns its 128-bit `result_uuid` as a
    * `java.math.BigInteger`. The returned BigInteger is always non-negative -- the JNI layer
    * prepends a 0x00 sign byte before invoking `BigInteger(byte[])` so the upper byte's high bit
    * does not flip the sign. Pair with [[serializeFetchRequest]] to thread the UUID into subsequent
    * FETCH requests.
    *
    * Raises `RuntimeException` if the message is a different type.
    */
  @native def extractResultUuid(bytes: Array[Byte]): java.math.BigInteger

  /** Parses `bytes` as a `PREPARE_RESPONSE` or `FETCH_RESPONSE`, transfers the embedded
    * `DataChunkWrapper`s into a heap-allocated Arrow C-data `ArrowArrayStream*`, and returns the
    * raw pointer as a `Long`. Ownership transfers to the caller: import it with
    * [[QuackArrowImport.importStream]], which delegates to
    * `org.apache.arrow.c.Data.importArrayStream`. The Arrow Java importer invokes the stream's
    * `release` callback when the returned `ArrowReader` is closed, freeing the underlying chunks.
    *
    * Raises `RuntimeException` for any non-response message type or a malformed buffer. Returns `0`
    * if a Java exception is pending (the caller must check `ExceptionCheck` semantics handled for
    * you by the JVM throwing on return).
    */
  @native def extractArrowStream(bytes: Array[Byte]): Long

  /** Parses `bytes` as a `FETCH_RESPONSE` and returns the number of `DataChunkWrapper` entries it
    * carries. The driver uses `0` as the end-of-FETCH-loop signal, matching the upstream
    * Quack-extension scan code (`duckdb-quack/src/quack_scan.cpp:331`, which guards the loop with
    * `if (fetch_response->MutableResults().empty()) { ... return; }`).
    *
    * Raises `RuntimeException` if the message is a different type (e.g. PREPARE_RESPONSE, which is
    * handled by [[needsMoreFetch]] and the initial Arrow stream).
    */
  @native def fetchResponseChunkCount(bytes: Array[Byte]): Int

  /** Parses `bytes` as a `PREPARE_RESPONSE` and returns the column names declared by the server.
    * Mirrors `duckdb::PrepareResponseMessage::Names()` (upstream `quack_message.hpp:157-159`,
    * backing field `result_names: vector<string>` at line 181). FETCH_RESPONSE carries no schema
    * and therefore no names; the driver pulls names once from the PREPARE_RESPONSE and reuses them
    * for every subsequent FETCH on the same connection.
    *
    * Note: [[extractArrowStream]] already threads the same names into the Arrow C-data schema
    * returned for a PREPARE_RESPONSE -- this method is exposed as a standalone primitive so callers
    * (logging, tests, future column-pruning) can peek at the names without going through Arrow.
    *
    * Raises `RuntimeException` if the message is a different type.
    */
  @native def extractColumnNames(bytes: Array[Byte]): Array[String]

  // Side-effecting init anchor: forces NativeLoader.loadFromResources to run
  // exactly once at object init. The Boolean is never read by callers.
  @scala.annotation.unused
  private val loaded: Boolean =
    val osArch  = NativeLoader.platformDir()
    val libName = System.mapLibraryName("quackwire")
    NativeLoader.loadFromResources(s"/native/$osArch/$libName")
    true

/** Classpath probe for the bundled libquackwire native. Deliberately separate from
  * [[QuackNativeBridge]]: touching that object triggers the JNI load at init, which is exactly what
  * must NOT happen on a platform with no bundled binary (Windows on ARM64 - quackwire.dll is built
  * x86_64-only). Main consults [[effectiveNativeClient]] before constructing the client so such
  * platforms degrade to the embedded HTTP path instead of crashing.
  */
object QuackNativeSupport extends com.typesafe.scalalogging.LazyLogging:

  def available(platform: String, libFile: String = System.mapLibraryName("quackwire")): Boolean =
    val stream = getClass.getResourceAsStream(s"/native/$platform/$libFile")
    if stream == null then false
    else
      stream.close()
      true

  /** True when a native for the RUNNING platform is bundled. False also for platforms
    * [[NativeLoader.platformDir]] does not know about.
    */
  def availableForThisPlatform: Boolean =
    scala.util.Try(NativeLoader.platformDir()).toOption.exists(available(_))

  /** The native-client setting Main should actually use: the configured value, forced to `false`
    * (with a warning) when no native is bundled for this platform.
    */
  def effectiveNativeClient(
      configured: Boolean,
      nativeBundled: Boolean = availableForThisPlatform
  ): Boolean =
    if configured && !nativeBundled then
      logger.warn(
        "nativeClient=true but no libquackwire native is bundled for this platform " +
          "(e.g. Windows on ARM64, where quackwire.dll is x86_64-only); " +
          "falling back to the embedded HTTP client."
      )
      false
    else configured

private object NativeLoader:
  def platformDir(): String =
    val os    = sys.props("os.name").toLowerCase
    val arch  = sys.props("os.arch").toLowerCase
    val osTag =
      if os.contains("mac") then "osx"
      else if os.contains("linux") then "linux"
      else if os.contains("win") then "windows"
      else sys.error(s"unsupported OS for libquackwire: $os")
    val archTag = arch match
      case "x86_64" | "amd64"  => "x86_64"
      case "aarch64" | "arm64" => "aarch64"
      case other               => sys.error(s"unsupported arch for libquackwire: $other")
    s"$osTag-$archTag"

  def loadFromResources(resourcePath: String): Unit =
    val tmp =
      java.nio.file.Files.createTempFile("libquackwire-", System.mapLibraryName("quackwire"))
    Using.resource(
      Option(getClass.getResourceAsStream(resourcePath))
        .getOrElse(sys.error(s"libquackwire resource not found: $resourcePath"))
    ) { in =>
      Using.resource(java.nio.file.Files.newOutputStream(tmp)) { fos =>
        in.transferTo(fos)
      }
    }
    tmp.toFile.deleteOnExit()
    System.load(tmp.toAbsolutePath.toString)
