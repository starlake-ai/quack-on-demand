package ai.starlake.quack.edge.adapter

import java.nio.file.{Files, Path}
import java.util.Locale
import scala.util.Using

object QuackNativeBridge:
  @native def smokeAnswer(): Int

  /** The build marker compiled into the binary (`quackwire-build-info:protocol=..;duckdb=..;
    * duckdb_source=..;quack=..`): the Quack protocol generation, the DuckDB version and source id
    * it was built against and the duckdb-quack commit. Parsed by [[NativeBuildInfo]]. A native that
    * predates the marker lacks this export, so calling it raises `UnsatisfiedLinkError`.
    */
  @native def buildInfo(): String

  /** The libduckdb loaded into this process, as `duckdb=<version>;duckdb_source=<source id>`. */
  @native def linkedDuckDb(): String

  /** Serializes a `CONNECTION_REQUEST` message with the supplied auth token. The returned bytes are
    * the binary `QuackMessage` ready to POST to a Quack node's `/quack` endpoint.
    *
    * `heartbeatTimeoutSec` (Quack v3) is the connection lease the node grants: every request renews
    * it. The node checks the lease only when the connection's next message arrives; once lapsed,
    * that message fails and the node interrupts what still runs on the connection and drops it. A
    * lapse by itself interrupts nothing, so the lease does not bound work orphaned by a dead
    * manager.
    */
  @native def serializeConnectionRequest(token: String, heartbeatTimeoutSec: Long): Array[Byte]

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
    *
    * `batchIndex` (Quack v3) is the 1-based index of the batch asked for, counted after the batches
    * the PREPARE_RESPONSE carried inline; the request also acks every batch before it.
    */
  @native def serializeFetchRequest(
      connectionId: String,
      resultUuid: java.math.BigInteger,
      batchIndex: Long
  ): Array[Byte]

  /** Serializes a `HEARTBEAT_REQUEST` that renews the connection's lease (Quack v3). */
  @native def serializeHeartbeat(connectionId: String): Array[Byte]

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

  /** Frees the heap struct [[extractArrowStream]] returned, once the Arrow Java importer has moved
    * its contents into a struct it owns (the import marks this one released). A stream the import
    * never took is released first, so its chunks are freed with it. Call exactly once per pointer.
    */
  @native def freeStreamShell(ptr: Long): Unit

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

  // Referencing the module object forces its <clinit> (and therefore the
  // `loaded` init above, and the underlying System.load) to run right here,
  // instead of lazily at the first native call. Used by
  // QuackNativeSupport.requireLoaded, which QuackNativeSupport.bootCheck runs at boot.
  private[adapter] def forceInit(): Unit =
    val _ = QuackNativeBridge
    ()

/** Classpath probe and load gate for the bundled libquackwire native, the manager's only client to
  * its nodes. Deliberately separate from [[QuackNativeBridge]]: touching that object triggers the
  * JNI load at init, which must not happen on a platform with no bundled binary (one NativeLoader
  * does not know, or a jar assembled without that platform's binary).
  *
  * Main runs [[bootCheck]] early in boot, so a platform with no bundled binary, or one whose
  * bundled native cannot load (an old glibc, a missing MSVC runtime, libduckdb not found) or was
  * built for another DuckDB or Quack protocol than this jar pins (a stale native, see
  * [[NativeBuildInfo]]), refuses to boot with an actionable message instead of failing every query.
  * There is no fallback client.
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

  /** The `<os>-<arch>` name of a platform (`osx-aarch64`, `linux-x86_64`, `windows-x86_64`...), as
    * the bundled natives are laid out; None for one no native is built for.
    */
  def platform(
      osName: String = sys.props.getOrElse("os.name", "unknown"),
      osArch: String = sys.props.getOrElse("os.arch", "unknown")
  ): Option[String] =
    scala.util.Try(NativeLoader.platformDir(osName, osArch)).toOption

  /** The directory of the libduckdb this manager links: the one [[LibDuckDbPreload]] loaded, else
    * the first one the launchers provisioned. None when libduckdb comes from the loader path (an
    * image that installs it in /usr/local/lib).
    */
  def libDuckDbDir: Option[Path] =
    LibDuckDbPreload.lastLoaded
      .orElse(LibDuckDbPreload.bundledAbi.flatMap { abi =>
        LibDuckDbPreload
          .candidates(abi, sys.env, Path.of("").toAbsolutePath)
          .find(Files.isRegularFile(_))
      })
      .map(_.toAbsolutePath.getParent)

  /** The boot gate: Right when the native client can run here, else a message naming the platform,
    * the `libquackwire/binaries/<platform>` binary the jar should have bundled and the
    * `DUCKDB_CACHE_DIR` hint. Main turns a Left into a boot failure.
    *
    * `load` runs only when a native is bundled (touching the bridge on a platform without one only
    * adds a link error to the report); in production it is [[requireLoaded]], so the one real JNI
    * load happens here, at boot. Every parameter is injectable for tests.
    */
  def bootCheck(
      bundled: Boolean = availableForThisPlatform,
      load: () => Unit = () => requireLoaded(),
      osName: String = sys.props.getOrElse("os.name", "unknown"),
      osArch: String = sys.props.getOrElse("os.arch", "unknown"),
      preloaded: => Option[Path] = LibDuckDbPreload.lastLoaded
  ): Either[String, Unit] =
    val platform =
      s"os.name=$osName, os.arch=$osArch (libquackwire/binaries/${expectedBinary(osName, osArch)})"
    val cause: Option[String] =
      if !bundled then Some("no libquackwire native is bundled in this jar for the platform")
      else
        // A failed load can surface as a java.lang.Error (UnsatisfiedLinkError, or the
        // ExceptionInInitializerError wrapping it), which NonFatal does not cover.
        try
          load()
          None
        catch
          case t: Throwable =>
            val root = initCause(t)
            Some(Option(root.getMessage).getOrElse(root.toString))
    cause match
      case None         => Right(())
      case Some(reason) =>
        // libduckdb found and loaded means provisioning worked, and a stale native is not a
        // provisioning problem either: in both cases the cache hint would only mislead.
        val cacheHint =
          if reason.contains("DUCKDB_CACHE_DIR") || reason.contains(StaleNativeException.Marker) ||
            (bundled && preloaded.isDefined)
          then ""
          else
            " When the binary is present but cannot find libduckdb: " +
              LibDuckDbPreload.provisionHint(LibDuckDbPreload.bundledAbi.getOrElse("<abi>"))
        val because = reason.stripSuffix(".")
        Left(
          s"The native Quack client (libquackwire) cannot run on this host: $because. " +
            s"Platform: $platform. The manager has no fallback client to reach its nodes, so it " +
            "refuses to boot. Use a jar built with the libquackwire binary for this platform." +
            cacheHint
        )

  /** `<platform>/<library file>` under `libquackwire/binaries/`, derived from `os.name` / `os.arch`
    * the way [[NativeLoader]] resolves it. A platform it does not know is reported as
    * `<os.name>-<os.arch>`, since no build exists to name.
    */
  private def expectedBinary(osName: String, osArch: String): String =
    scala.util.Try(NativeLoader.platformDir(osName, osArch)).toOption match
      case Some(dir) => s"$dir/${NativeLoader.libraryFile(dir)}"
      case None      => s"$osName-$osArch (no libquackwire build exists for it)"

  /** Attempts `load` and returns None when it succeeds, else a NON-fatal exception carrying the
    * original cause. A failed [[QuackNativeBridge]] init surfaces as `ExceptionInInitializerError`
    * on the first touch and `NoClassDefFoundError` on every later one, both `LinkageError`s that
    * cats-effect treats as fatal: thrown inside an IO they kill the runtime and leave any
    * `unsafeRunSync` caller waiting forever. Unwrapping to the `UnsatisfiedLinkError` underneath
    * keeps the message actionable.
    *
    * A load that fails on a missing symbol found libduckdb but not a symbol the native imports: the
    * two are different DuckDB builds, reported as a [[StaleNativeException]] rather than as a
    * provisioning problem.
    */
  private[adapter] def probe(
      load: () => Unit,
      preloadMiss: => Option[String] = LibDuckDbPreload.lastMiss,
      preloaded: => Option[Path] = LibDuckDbPreload.lastLoaded
  ): Option[IllegalStateException] =
    try
      load()
      None
    catch
      case t: Throwable =>
        val root   = initCause(t)
        val detail = Option(root.getMessage).getOrElse(root.toString)
        if NativeBuildInfo.isMissingSymbol(detail) then
          Some(
            StaleNativeException(
              s"libquackwire native failed to load: $detail. libduckdb was found, but it lacks " +
                "a symbol the native imports, so the two were built from different DuckDB " +
                "releases: most likely the bundled native was built against another DuckDB than " +
                s"this jar pins (${NativeBuildInfo.stampText}), or a libduckdb other than the " +
                "pinned one is first on the loader path. " + NativeBuildInfo.RefreshHint,
              root
            )
          )
        else
          val abi  = LibDuckDbPreload.bundledAbi.getOrElse("<abi>")
          val hint = preloadMiss.getOrElse(
            preloaded match
              case Some(lib) =>
                s"libduckdb was preloaded from $lib, so it was provisioned: either that file is " +
                  s"not DuckDB $abi, or the bundled libquackwire does not match it or this host."
              case None =>
                s"It links the pinned libduckdb dynamically. ${LibDuckDbPreload.provisionHint(abi)}"
          )
          Some(
            new IllegalStateException(s"libquackwire native failed to load: $detail. $hint", root)
          )

  /** Calls the native's build-info exports and returns why the loaded native must not run, None
    * when its build marker matches `/native/VERSION` and the loaded libduckdb. A missing export
    * (`UnsatisfiedLinkError`) is a native that predates the marker, hence stale.
    */
  private[adapter] def verifyBuild(
      buildInfo: () => String = () => QuackNativeBridge.buildInfo(),
      linkedDuckDb: () => String = () => QuackNativeBridge.linkedDuckDb(),
      stamp: Option[String] = LibDuckDbPreload.bundledStamp
  ): Option[IllegalStateException] =
    try NativeBuildInfo.mismatch(buildInfo(), linkedDuckDb(), stamp).map(StaleNativeException(_))
    catch
      case t: Throwable =>
        val root = initCause(t)
        root match
          case e: UnsatisfiedLinkError =>
            Some(
              StaleNativeException(
                "the bundled libquackwire native has no build-info export " +
                  s"(${Option(e.getMessage).getOrElse("UnsatisfiedLinkError")}), so it predates " +
                  "build markers: a stale native (a DuckDB 1.5.x / Quack v1 build) that cannot " +
                  s"run with the pinned ${NativeBuildInfo.stampText}. " + NativeBuildInfo.RefreshHint,
                e
              )
            )
          case other =>
            Some(
              StaleNativeException(
                s"could not read the libquackwire build marker: ${other.getMessage}. " +
                  NativeBuildInfo.RefreshHint,
                other
              )
            )

  @scala.annotation.tailrec
  private def initCause(t: Throwable): Throwable = t match
    case _: ExceptionInInitializerError | _: NoClassDefFoundError if t.getCause != null =>
      initCause(t.getCause)
    case _ => t

  /** The JNI load and build check, attempted once per JVM and shared by every [[requireLoaded]]
    * caller.
    */
  private lazy val loadFailure: Option[IllegalStateException] =
    probe(() => QuackNativeBridge.forceInit()).orElse(verifyBuild())

  /** Throws a non-fatal `IllegalStateException` naming the root cause when the native cannot load
    * or is stale. Entry points call it inside their IO before the first native call, so a broken
    * native fails the statement instead of hanging the caller.
    */
  def requireLoaded(): Unit =
    loadFailure.foreach {
      case e: StaleNativeException => throw StaleNativeException(e.getMessage, e.getCause)
      case e                       => throw new IllegalStateException(e.getMessage, e.getCause)
    }

/** The bundled libquackwire native was built for another DuckDB or Quack protocol than this jar
  * pins (or than the libduckdb it loaded). Not a provisioning problem: the fix is a native refresh.
  */
final class StaleNativeException private (message: String, cause: Throwable)
    extends IllegalStateException(s"${StaleNativeException.Marker}: $message", cause)

object StaleNativeException:
  /** Leads every message, so [[QuackNativeSupport.bootCheck]] can tell this cause apart. */
  val Marker = "stale libquackwire native"

  /** `message` may already carry the [[Marker]] (a rethrow); it is not repeated. */
  def apply(message: String, cause: Throwable = null): StaleNativeException =
    new StaleNativeException(message.stripPrefix(s"$Marker: "), cause)

/** The build marker compiled into libquackwire (`kQuackwireBuildInfo` in quackwire.cpp, defined by
  * native/quackwire/CMakeLists.txt) and the checks [[QuackNativeSupport]] runs on it. A vendored
  * binary is only as trustworthy as this marker: `/native/VERSION` is stamped for the whole set, so
  * a binary left over from an earlier DuckDB passes every check that reads the stamp alone.
  * scripts/release/quackwire-build-info.sh reads the same marker from the file at release time.
  */
private[adapter] object NativeBuildInfo:

  /** The Quack protocol generation the manager's node client speaks. */
  val ProtocolVersion: Long = 3L

  val Prefix = "quackwire-build-info:"

  val RefreshHint: String =
    "Use a jar whose libquackwire/binaries/ were rebuilt against the pinned DuckDB " +
      "(scripts/release/refresh-quackwire-binaries.sh, or QOD_VERSION=BUILD ./scripts/run-jar.sh " +
      "for the host platform)."

  /** `<duckdb>-<quack sha12>-<rev>`, e.g. `2.0.0-974927a394b1-1`. */
  private val Stamp = "(\\d+\\.\\d+\\.\\d+)-([0-9a-f]+)-.*".r

  /** `v2.0.0-alpha46057` and `v2.0.0` both name release 2.0.0. */
  private val Release = "v?(\\d+\\.\\d+\\.\\d+).*".r

  def stampText: String =
    LibDuckDbPreload.bundledStamp.fold("no /native/VERSION stamp")(s => s"libquackwire $s")

  /** The `key=value;...` fields of a build marker (or of [[QuackNativeBridge.linkedDuckDb]]). */
  def fields(raw: String): Map[String, String] =
    raw.trim
      .stripPrefix(Prefix)
      .split(';')
      .toList
      .flatMap { kv =>
        kv.split("=", 2) match
          case Array(k, v) if k.nonEmpty => Some(k -> v)
          case _                         => None
      }
      .toMap

  /** Why a native with build marker `built`, running against libduckdb `linked` (both as the native
    * reports them), must not run under the `/native/VERSION` `stamp`; None when everything agrees.
    */
  def mismatch(built: String, linked: String, stamp: Option[String]): Option[String] =
    val b = fields(built)
    val l = fields(linked)
    (
      get(b, "protocol"),
      get(b, "duckdb"),
      get(b, "duckdb_source"),
      get(b, "quack"),
      stamp.map(_.trim)
    ) match
      case (
            Some(protocol),
            Some(duckdb),
            Some(source),
            Some(quack),
            Some(pin @ Stamp(abi, quack12))
          ) =>
        val release = duckdb match
          case Release(r) => r
          case other      => other
        if protocol != ProtocolVersion.toString then
          Some(
            s"the bundled native speaks Quack protocol $protocol, the manager needs " +
              s"$ProtocolVersion. $RefreshHint"
          )
        else if release != abi || !quack.startsWith(quack12) then
          Some(
            s"the bundled native was built against DuckDB $duckdb with duckdb-quack " +
              s"${quack.take(12)}, but this jar pins libquackwire $pin (DuckDB $abi, " +
              s"duckdb-quack $quack12). $RefreshHint"
          )
        else if !get(l, "duckdb").contains(duckdb) || !get(l, "duckdb_source").contains(source)
        then
          Some(
            s"the bundled native was built against DuckDB $duckdb ($source) but the process " +
              s"loaded libduckdb ${get(l, "duckdb").getOrElse("?")} " +
              s"(${get(l, "duckdb_source").getOrElse("?")}); their C++ ABIs differ. Put the " +
              s"pinned libduckdb $abi first on the loader path (.duckdb/$abi/lib or " +
              "DUCKDB_CACHE_DIR), or rebuild the native against the loaded one."
          )
        else None
      case (_, _, _, _, None) =>
        Some("no /native/VERSION stamp is bundled next to the native; the jar is malformed.")
      case (_, _, _, _, Some(s)) if !Stamp.matches(s) =>
        Some(s"/native/VERSION ($s) is not a <duckdb>-<quack sha>-<rev> stamp.")
      case _ =>
        Some(s"the bundled native's build marker is incomplete ($built). $RefreshHint")

  private def get(fields: Map[String, String], key: String): Option[String] =
    fields.get(key).filter(_.nonEmpty)

  /** True for a dynamic-loader message about a symbol the image imports and no loaded library
    * exports: dyld (macOS), glibc (Linux, under `-z now` at load), Windows `ERROR_PROC_NOT_FOUND`.
    */
  def isMissingSymbol(message: String): Boolean =
    message.contains("Symbol not found") || message.contains("undefined symbol") ||
      message.contains("specified procedure could not be found")

private object NativeLoader:
  def platformDir(
      osName: String = sys.props("os.name"),
      osArch: String = sys.props("os.arch")
  ): String =
    val os    = osName.toLowerCase(Locale.ROOT)
    val arch  = osArch.toLowerCase(Locale.ROOT)
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

  /** The library file name the binaries for `platformDir` carry (`System.mapLibraryName` for that
    * OS rather than the running one).
    */
  def libraryFile(platformDir: String): String =
    if platformDir.startsWith("windows") then "quackwire.dll"
    else if platformDir.startsWith("osx") then "libquackwire.dylib"
    else "libquackwire.so"

  def loadFromResources(resourcePath: String): Unit =
    // Resolved before the preload: a platform with no bundled native has nothing to preload
    // libduckdb for.
    val resource = Option(getClass.getResourceAsStream(resourcePath))
      .getOrElse(sys.error(s"libquackwire resource not found: $resourcePath"))
    // libquackwire links libduckdb dynamically. Loading the pinned one first lets the dynamic
    // loader satisfy that dependency from the image already in the process, instead of from the
    // library path baked in at build time (see LibDuckDbPreload).
    LibDuckDbPreload.preload()
    val tmp =
      java.nio.file.Files.createTempFile("libquackwire-", System.mapLibraryName("quackwire"))
    Using.resource(resource) { in =>
      Using.resource(java.nio.file.Files.newOutputStream(tmp)) { fos =>
        in.transferTo(fos)
      }
    }
    tmp.toFile.deleteOnExit()
    System.load(tmp.toAbsolutePath.toString)

/** Loads the pinned libduckdb into the process before libquackwire, from the `<root>/<abi>/lib`
  * cache the launchers provision.
  *
  * libquackwire depends on `@rpath/libduckdb.dylib` (`libduckdb.so`, `duckdb.dll`), and the only
  * rpath baked into a vendored binary is the build machine's cache directory, which exists nowhere
  * else. The loader path cannot be relied on to cover that: System Integrity Protection strips
  * `DYLD_*` whenever a protected binary runs, so a JVM that is itself `/usr/bin/java`, or is
  * started through `/bin/bash` (exactly how `sbt test` forks), never sees `DYLD_LIBRARY_PATH`, and
  * Windows has no loader variable at all. So every launcher hands this preload an absolute location
  * instead: the qod CLI (`qod start`, `qod serve`, the demo) exports `DUCKDB_CACHE_DIR` at its
  * versioned `<user cache>/qod/duckdb`, `run-jar.sh` provisions `<repo>/.duckdb/<abi>`, and
  * build.sbt points forked tests at the main checkout's. An image already loaded under the same
  * install name (macOS), soname (Linux) or module name (Windows) satisfies the dependency, so a
  * preload makes the baked rpath irrelevant.
  *
  * Best effort by design: finding nothing, or failing to load what it found, leaves resolution to
  * the loader path exactly as before, and [[QuackNativeSupport.bootCheck]] still decides whether
  * the manager can boot.
  */
private[adapter] object LibDuckDbPreload extends com.typesafe.scalalogging.LazyLogging:

  /** `libquackwire/binaries/VERSION`, bundled by build.sbt next to the natives. */
  private val VersionResource = "/native/VERSION"

  private val DuckDbVersion = "(\\d+\\.\\d+\\.\\d+)(?:-.*)?".r

  /** The DuckDB release a libquackwire version stamp (`<abi>-<quack sha>-<rev>`) links against. */
  def abiVersion(stamp: String): Option[String] =
    stamp.trim match
      case DuckDbVersion(v) => Some(v)
      case _                => None

  /** The whole `/native/VERSION` stamp, trimmed. */
  def bundledStamp: Option[String] =
    Option(getClass.getResourceAsStream(VersionResource)).map { in =>
      scala.util.Using.resource(in)(s => new String(s.readAllBytes(), "UTF-8").trim)
    }

  def bundledAbi: Option[String] = bundledStamp.flatMap(abiVersion)

  /** Where the launchers put libduckdb, in the order they are tried: `$DUCKDB_CACHE_DIR/<abi>/lib`
    * (the qod CLI's cache, which it always exports, and run-jar.sh's air-gap layout), then
    * `<cwd>/.duckdb/<abi>/lib` (run-jar.sh's default, and a repo-root `sbt test`).
    */
  def candidates(abi: String, env: Map[String, String], cwd: Path): List[Path] =
    val lib = System.mapLibraryName("duckdb")
    (env.get("DUCKDB_CACHE_DIR").map(_.trim).filter(_.nonEmpty).map(Path.of(_)).toList :+
      cwd.resolve(".duckdb")).map(_.resolve(abi).resolve("lib").resolve(lib)).distinct

  /** The [[notFoundMessage]] of the last [[preload]] that found no candidate, None after one that
    * did. Only logged at DEBUG there: a miss is the normal case wherever libduckdb comes from the
    * loader path (the Docker image installs it in `/usr/local/lib`), so the paths are named only
    * when the libquackwire load really fails ([[QuackNativeSupport.probe]]).
    */
  @volatile private[adapter] var lastMiss: Option[String] = None

  /** The libduckdb the last [[preload]] loaded, None when it loaded nothing. Lets a later
    * libquackwire failure say that provisioning worked rather than blame it.
    */
  @volatile private[adapter] var lastLoaded: Option[Path] = None

  /** How each launcher provisions libduckdb `abi`, for the preload miss and the boot refusal. */
  def provisionHint(abi: String): String =
    s"The qod CLI (qod start, qod serve, the demo) provisions <user cache>/qod/duckdb/$abi/lib and " +
      s"exports DUCKDB_CACHE_DIR at it unless DUCKDB_VERSION names another release; otherwise " +
      s"set DUCKDB_CACHE_DIR to a directory holding $abi/lib/${System.mapLibraryName("duckdb")}, " +
      s"or provision .duckdb/$abi/ (scripts/run-jar.sh populates it)."

  /** What [[preload]] records when an ABI is known but no candidate holds libduckdb. */
  def notFoundMessage(abi: String, tried: List[Path]): String =
    s"no libduckdb $abi to preload; tried ${tried.mkString(", ")}. libquackwire falls back to " +
      s"the dynamic loader path, which only finds a libduckdb an image installed. " +
      provisionHint(abi)

  /** Loads the first existing candidate and returns it; None when there was nothing to load or the
    * load failed. Never throws: an `UnsatisfiedLinkError` is a `java.lang.Error`, hence Throwable.
    */
  def preload(
      abi: Option[String] = bundledAbi,
      env: Map[String, String] = sys.env,
      cwd: Path = Path.of("").toAbsolutePath,
      load: String => Unit = System.load
  ): Option[Path] =
    val found = abi.flatMap { a =>
      val tried = candidates(a, env, cwd)
      val hit   = tried.find(Files.isRegularFile(_))
      lastMiss = if hit.isEmpty then Some(notFoundMessage(a, tried)) else None
      lastMiss.foreach(m => logger.debug(m))
      hit
    }
    val loaded = found.flatMap { lib =>
      try
        load(lib.toAbsolutePath.toString)
        logger.debug(s"preloaded libduckdb from $lib")
        Some(lib)
      catch
        case t: Throwable =>
          logger.warn(
            s"could not preload libduckdb from $lib (${t.getMessage}); libquackwire falls back " +
              "to the dynamic loader path"
          )
          None
    }
    lastLoaded = loaded
    loaded
