package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.model.{NodeSpec, PoolKey, Role}
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LocalQuackBackendSpec extends AnyFlatSpec with Matchers:
  import LocalQuackBackendSpec.{isWindows, sleepCmd, envDumpCmd}

  "LocalQuackBackend" should "start, lease a port, and register the node" in:
    val backend = new LocalQuackBackend(
      min = 23000, max = 23010,
      commandFor = (_, _, _) => sleepCmd(30)
    )
    val spec = NodeSpec(PoolKey("t", "t_default", "p"), "node-1", Role.Dual, Map.empty, Map.empty)
    val node = backend.start(spec).unsafeRunSync()
    try
      node.host shouldBe "127.0.0.1"
      node.port should (be >= 23000 and be <= 23010)
      node.pid.isDefined shouldBe true
      backend.isAlive("node-1") shouldBe true
    finally backend.stop("node-1").unsafeRunSync()

  it should "release the port on stop" in:
    val backend = new LocalQuackBackend(23020, 23021,
      commandFor = (_, _, _) => sleepCmd(30))
    val spec = NodeSpec(PoolKey("t", "t_default", "p"), "node-2", Role.Dual, Map.empty, Map.empty)
    backend.start(spec).unsafeRunSync()
    backend.stop("node-2").unsafeRunSync()
    backend.isAlive("node-2") shouldBe false

  it should "carry NodeSpec.maxConcurrent through to RunningNode" in:
    val backend = new LocalQuackBackend(23030, 23031,
      commandFor = (_, _, _) => sleepCmd(30))
    val spec = NodeSpec(PoolKey("t", "t_default", "p"), "node-3", Role.Dual, Map.empty, Map.empty, maxConcurrent = 5)
    val node = backend.start(spec).unsafeRunSync()
    try node.maxConcurrent shouldBe 5
    finally backend.stop("node-3").unsafeRunSync()

  it should "propagate defaultMetastore + NodeSpec.metastore as env vars (spec overrides default)" in:
    val capture = java.io.File.createTempFile("env-capture-", ".txt")
    capture.deleteOnExit()
    val backend = new LocalQuackBackend(
      min = 23040, max = 23041,
      defaultMetastore = Map("pgHost" -> "default-host", "pgUser" -> "default-user"),
      commandFor = (_, _, _) => envDumpCmd(capture.getAbsolutePath, 5)
    )
    val spec = NodeSpec(
      PoolKey("t", "t_default", "p"), "node-env", Role.Dual,
      metastore = Map("pgHost" -> "override-host", "dbName" -> "specdb"),
      s3 = Map.empty
    )
    backend.start(spec).unsafeRunSync()
    try
      // Give the shell a moment to write the env dump
      val deadline = System.currentTimeMillis() + 3000
      while capture.length() == 0 && System.currentTimeMillis() < deadline do
        Thread.sleep(50)
      val out = java.nio.file.Files.readString(capture.toPath)
      out should include ("pgHost=override-host")  // spec wins
      out should include ("pgUser=default-user")    // default fills gap
      out should include ("dbName=specdb")           // spec-only key
    finally backend.stop("node-env").unsafeRunSync()

  // Regression for issue #2 (Graceful JVM shutdown hook): cleanup()
  // must SIGTERM every tracked child, wait for them to exit (falling
  // back to SIGKILL on stragglers), then clear its registry maps so a
  // second cleanup() is a no-op.
  it should "cleanup(): graceful SIGTERM every tracked child then clear maps" in:
    val backend = new LocalQuackBackend(23060, 23069,
      // sleep 30 reacts cleanly to SIGTERM (the shell exits 143
      // immediately), so the cleanup wait should never need the
      // 5 s SIGKILL fallback. (Windows console apps ignore the graceful
      // taskkill, so the timing assertion below is Unix-only.)
      commandFor = (_, _, _) => sleepCmd(30))
    val a = NodeSpec(PoolKey("t", "t_default", "p"), "node-cu-a", Role.Dual, Map.empty, Map.empty)
    val b = NodeSpec(PoolKey("t", "t_default", "p"), "node-cu-b", Role.Dual, Map.empty, Map.empty)
    backend.start(a).unsafeRunSync()
    backend.start(b).unsafeRunSync()
    backend.isAlive("node-cu-a") shouldBe true
    backend.isAlive("node-cu-b") shouldBe true

    val t0 = System.currentTimeMillis()
    backend.cleanup().unsafeRunSync()
    val elapsedMs = System.currentTimeMillis() - t0

    // Children honour SIGTERM, so we should NOT need the 5 s fallback
    // per process. 8 s leaves headroom for slow CI. On Windows the graceful
    // `taskkill /T` is ignored by console processes, so cleanup always waits
    // out the grace period before force-killing - skip the promptness bound.
    if !isWindows then elapsedMs should be < 8000L
    backend.isAlive("node-cu-a") shouldBe false
    backend.isAlive("node-cu-b") shouldBe false

    // Idempotent: a second cleanup is a no-op against an empty map.
    backend.cleanup().unsafeRunSync()

object LocalQuackBackendSpec:
  val isWindows: Boolean = sys.props.getOrElse("os.name", "").toLowerCase.contains("win")

  /** A long-running child that the backend can start / observe / terminate,
    * spelled for the host OS: `sh -c "sleep N"` on Unix, a PowerShell
    * `Start-Sleep` on Windows.
    */
  def sleepCmd(seconds: Int): List[String] =
    if isWindows then
      List("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", s"Start-Sleep -Seconds $seconds")
    else List("sh", "-c", s"sleep $seconds")

  /** Dump the child's environment as `KEY=VALUE` lines to `file`, then linger
    * `seconds` so the parent can read it before stop().
    */
  def envDumpCmd(file: String, seconds: Int): List[String] =
    if isWindows then
      List(
        "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
        s"""Get-ChildItem Env: | ForEach-Object { "$$($$_.Name)=$$($$_.Value)" } | """ +
          s"""Set-Content -LiteralPath '$file' -Encoding utf8; Start-Sleep -Seconds $seconds"""
      )
    else List("sh", "-c", s"env > '$file'; sleep $seconds")