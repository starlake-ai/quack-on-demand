package ai.starlake.quack.ondemand.runtime

import ai.starlake.quack.model.{NodeSpec, PoolKey, Role}
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LocalQuackBackendSpec extends AnyFlatSpec with Matchers:
  import LocalQuackBackendSpec.{envDumpCmd, isWindows, sleepCmd}

  "LocalQuackBackend" should "start, lease a port, and register the node" in:
    val backend = new LocalQuackBackend(
      min = 23000,
      max = 23010,
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
    val backend = new LocalQuackBackend(23020, 23021, commandFor = (_, _, _) => sleepCmd(30))
    val spec = NodeSpec(PoolKey("t", "t_default", "p"), "node-2", Role.Dual, Map.empty, Map.empty)
    backend.start(spec).unsafeRunSync()
    backend.stop("node-2").unsafeRunSync()
    backend.isAlive("node-2") shouldBe false

  it should "carry NodeSpec.maxConcurrent through to RunningNode" in:
    val backend = new LocalQuackBackend(23030, 23031, commandFor = (_, _, _) => sleepCmd(30))
    val spec    = NodeSpec(
      PoolKey("t", "t_default", "p"),
      "node-3",
      Role.Dual,
      Map.empty,
      Map.empty,
      maxConcurrent = 5
    )
    val node = backend.start(spec).unsafeRunSync()
    try node.maxConcurrent shouldBe 5
    finally backend.stop("node-3").unsafeRunSync()

  it should "propagate defaultMetastore + NodeSpec.metastore as env vars (spec overrides default)" in:
    val capture = java.io.File.createTempFile("env-capture-", ".txt")
    capture.deleteOnExit()
    val backend = new LocalQuackBackend(
      min = 23040,
      max = 23041,
      defaultMetastore = Map("pgHost" -> "default-host", "pgUser" -> "default-user"),
      commandFor = (_, _, _) => envDumpCmd(capture.getAbsolutePath, 5)
    )
    val spec = NodeSpec(
      PoolKey("t", "t_default", "p"),
      "node-env",
      Role.Dual,
      metastore = Map("pgHost" -> "override-host", "dbName" -> "specdb"),
      s3 = Map.empty
    )
    backend.start(spec).unsafeRunSync()
    try
      // Give the shell a moment to write the env dump
      val deadline = System.currentTimeMillis() + 3000
      while capture.length() == 0 && System.currentTimeMillis() < deadline do Thread.sleep(50)
      val out = java.nio.file.Files.readString(capture.toPath)
      out should include("pgHost=override-host") // spec wins
      out should include("pgUser=default-user")  // default fills gap
      out should include("dbName=specdb")        // spec-only key
    finally backend.stop("node-env").unsafeRunSync()

  it should "emit objectStoreSql as an env var when the spec carries one, and omit it when empty" in:
    val capture = java.io.File.createTempFile("env-capture-objsql-", ".txt")
    capture.deleteOnExit()
    val backend = new LocalQuackBackend(
      min = 23090,
      max = 23091,
      commandFor = (_, _, _) => envDumpCmd(capture.getAbsolutePath, 5)
    )
    val spec = NodeSpec(
      PoolKey("t", "t_default", "p"),
      "node-objsql",
      Role.Dual,
      metastore = Map.empty,
      s3 = Map.empty,
      objectStoreSql = "CREATE OR REPLACE SECRET qod_db_store (TYPE s3, SCOPE 's3://bucket/db');"
    )
    backend.start(spec).unsafeRunSync()
    try
      val deadline = System.currentTimeMillis() + 3000
      while capture.length() == 0 && System.currentTimeMillis() < deadline do Thread.sleep(50)
      val out = java.nio.file.Files.readString(capture.toPath)
      out should include("objectStoreSql=CREATE OR REPLACE SECRET qod_db_store")
    finally backend.stop("node-objsql").unsafeRunSync()

    val capture2 = java.io.File.createTempFile("env-capture-objsql-empty-", ".txt")
    capture2.deleteOnExit()
    val backend2 = new LocalQuackBackend(
      min = 23092,
      max = 23093,
      commandFor = (_, _, _) => envDumpCmd(capture2.getAbsolutePath, 5)
    )
    val emptySpec =
      NodeSpec(PoolKey("t", "t_default", "p"), "node-noobjsql", Role.Dual, Map.empty, Map.empty)
    backend2.start(emptySpec).unsafeRunSync()
    try
      val deadline2 = System.currentTimeMillis() + 3000
      while capture2.length() == 0 && System.currentTimeMillis() < deadline2 do Thread.sleep(50)
      val out2 = java.nio.file.Files.readString(capture2.toPath)
      out2 should not include "objectStoreSql="
    finally backend2.stop("node-noobjsql").unsafeRunSync()

  // Regression for issue #2 (Graceful JVM shutdown hook): cleanup()
  // must SIGTERM every tracked child, wait for them to exit (falling
  // back to SIGKILL on stragglers), then clear its registry maps so a
  // second cleanup() is a no-op.
  it should "cleanup(): graceful SIGTERM every tracked child then clear maps" in:
    val backend = new LocalQuackBackend(
      23060,
      23069,
      // sleep 30 reacts cleanly to SIGTERM (the shell exits 143
      // immediately), so the cleanup wait should never need the
      // 5 s SIGKILL fallback. (Windows console apps ignore the graceful
      // taskkill, so the timing assertion below is Unix-only.)
      commandFor = (_, _, _) => sleepCmd(30)
    )
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

  // Regression for the shutdown-orphan investigation (2026-07-18): when the
  // wrapper ignores SIGTERM (or is mid-trap) and cleanup escalates to
  // SIGKILL, the kill must take the wrapper's WHOLE tree. SIGKILL bypasses
  // the wrapper's trap, which is what normally forwards TERM to the duckdb
  // child, so without an explicit descendant sweep the grandchild orphans.
  it should "cleanup(): force-kill takes descendants when the wrapper ignores SIGTERM" in:
    assume(!isWindows)
    val backend = new LocalQuackBackend(
      23070,
      23073,
      commandFor = (_, _, _) => List("bash", "-c", "trap '' TERM; sleep 30 & wait")
    )
    val spec =
      NodeSpec(PoolKey("t", "t_default", "p"), "node-tree", Role.Dual, Map.empty, Map.empty)
    val node    = backend.start(spec).unsafeRunSync()
    val wrapper = java.lang.ProcessHandle.of(node.pid.get).get()
    // Wait for the sleep child to appear under the wrapper.
    val deadline = System.currentTimeMillis() + 3000
    while wrapper.children().count() == 0 && System.currentTimeMillis() < deadline do
      Thread.sleep(50)
    val kids = {
      import scala.jdk.CollectionConverters.*
      wrapper.descendants().iterator().asScala.toList
    }
    kids should not be empty

    backend.cleanup().unsafeRunSync()
    Thread.sleep(200)
    wrapper.isAlive shouldBe false
    withClue(s"descendants survived cleanup: ${kids.filter(_.isAlive).map(_.pid())}") {
      kids.exists(_.isAlive) shouldBe false
    }

  // Regression for the same investigation: cleanup() previously cleared
  // `adoptedPids` without terminating them, so nodes adopted from a previous
  // manager generation survived SIGTERM deterministically.
  it should "cleanup(): terminate adopt-only nodes tracked by pid" in:
    assume(!isWindows)
    val orphan  = new java.lang.ProcessBuilder("sh", "-c", "sleep 30").start()
    val backend = new LocalQuackBackend(23080, 23081, commandFor = (_, _, _) => sleepCmd(30))
    backend
      .adopt(
        ai.starlake.quack.model.RunningNode(
          nodeId = "adopted-1",
          poolKey = PoolKey("t", "t_default", "p"),
          role = Role.Dual,
          host = "127.0.0.1",
          port = 23080,
          token = "tok",
          pid = Some(orphan.pid()),
          podName = None,
          startedAt = java.time.Instant.now(),
          maxConcurrent = 4
        )
      )
      .unsafeRunSync()

    backend.cleanup().unsafeRunSync()
    orphan.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
    orphan.isAlive shouldBe false

object LocalQuackBackendSpec:
  val isWindows: Boolean = sys.props.getOrElse("os.name", "").toLowerCase.contains("win")

  /** A long-running child that the backend can start / observe / terminate, spelled for the host
    * OS: `sh -c "sleep N"` on Unix, a PowerShell `Start-Sleep` on Windows.
    */
  def sleepCmd(seconds: Int): List[String] =
    if isWindows then
      List(
        "powershell.exe",
        "-NoProfile",
        "-NonInteractive",
        "-Command",
        s"Start-Sleep -Seconds $seconds"
      )
    else List("sh", "-c", s"sleep $seconds")

  /** Dump the child's environment as `KEY=VALUE` lines to `file`, then linger `seconds` so the
    * parent can read it before stop().
    */
  def envDumpCmd(file: String, seconds: Int): List[String] =
    if isWindows then
      List(
        "powershell.exe",
        "-NoProfile",
        "-NonInteractive",
        "-Command",
        s"""Get-ChildItem Env: | ForEach-Object { "$$($$_.Name)=$$($$_.Value)" } | """ +
          s"""Set-Content -LiteralPath '$file' -Encoding utf8; Start-Sleep -Seconds $seconds"""
      )
    else List("sh", "-c", s"env > '$file'; sleep $seconds")
