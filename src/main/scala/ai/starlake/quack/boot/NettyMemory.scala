package ai.starlake.quack.boot

/** A full direct memory must not halt the manager. FlightSQL's Netty fails its own streams when its
  * buffers run out, but a socket read on the REST port or the front door takes a small temporary
  * direct buffer from the JDK, on a compute thread, and an `OutOfMemoryError` there is fatal. So
  * Netty must not spend the JDK's budget: with reflection allowed it allocates outside it, under a
  * limit of its own (`io.netty.maxDirectMemory`, which is the size of the JDK's by default). An
  * anonymous client filled that budget with stalled calls and halted the manager.
  */
object NettyMemory:
  private val Key = "io.netty.tryReflectionSetAccessible"

  /** Before any Netty class loads. An operator's own value stands. */
  def configure(set: (String, String) => Unit, get: String => Option[String]): Unit =
    if get(Key).isEmpty then set(Key, "true")

  def configure(): Unit =
    configure((k, v) => { System.setProperty(k, v); () }, k => Option(System.getProperty(k)))

  /** Whether Netty took the allocator of its own: it needs `Add-Opens java.base/java.nio` too,
    * which the jar's manifest carries and a `-cp` launch must pass.
    */
  def separate: Boolean = io.netty.util.internal.PlatformDependent.useDirectBufferNoCleaner()

  /** The text `Main.bootManager` prints on stderr when Netty did not take the allocator of its own
    * (the default log level is ERROR, so the logger would swallow it).
    */
  val SharedWarning: String =
    "WARNING: Netty shares the JDK's direct memory (it could not take an allocator of its own), so " +
      "a flood of FlightSQL calls can halt the manager. Start the JVM with " +
      "--add-opens=java.base/java.nio=ALL-UNNAMED (the jar's manifest carries it, so only a -cp " +
      "launch lacks it), and do not set io.netty.tryReflectionSetAccessible=false or " +
      "io.netty.maxDirectMemory=0."

  def warning(isSeparate: Boolean = separate): Option[String] =
    Option.when(!isSeparate)(SharedWarning)
