package ai.starlake.quack.ondemand.demo

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

class DemoHomeSpec extends AnyFlatSpec with Matchers:

  "DemoHome.create" should "make the home + subdirs and clean up" in {
    val base = Files.createTempDirectory("demo-home-spec")
    val home = DemoHome.create(Some(base.resolve("qod-demo").toString))
    Files.isDirectory(home.root) shouldBe true
    Files.isDirectory(home.pgDir) shouldBe true
    Files.isDirectory(home.dataPath) shouldBe true
    // `nativeDir` is still created for shape/symmetry, but (final-review Fix 1) DuckDB's JNI
    // native never actually unpacks there: `Files.createTempFile` resolves against the JVM's
    // startup snapshot of `java.io.tmpdir`, which a runtime `System.setProperty` cannot redirect,
    // so there is nothing left to assert a "pin" for here -- see the NOTE in DemoHome.scala.
    Files.isDirectory(home.nativeDir) shouldBe true

    home.deleteRecursively()
    Files.exists(home.root) shouldBe false
  }

  // A demo run killed before its teardown (SIGKILL, machine sleep, OOM) leaves a populated
  // `pg/pgdata` behind. The next run's `initdb` then refuses with "directory exists but is not
  // empty", surfacing only as zonky's opaque `IllegalStateException: Process [...initdb...]
  // failed`. `create` therefore starts every run from an empty tree.
  it should "clear the owned subdirs left behind by a crashed run" in {
    val base        = Files.createTempDirectory("demo-home-stale")
    val root        = base.resolve("qod-demo")
    val stalePgData = root.resolve("pg").resolve("pgdata")
    Files.createDirectories(stalePgData)
    Files.writeString(stalePgData.resolve("PG_VERSION"), "16")
    Files.createDirectories(root.resolve("ducklake"))
    Files.writeString(root.resolve("ducklake").resolve("leftover.parquet"), "x")
    // Anything the demo does not own is left alone -- `QOD_DEMO_HOME` may point at a directory
    // the caller also uses for other things, so the wipe is scoped to the three owned subdirs.
    Files.writeString(root.resolve("keep.txt"), "keep")

    val home = DemoHome.create(Some(root.toString))

    Files.exists(stalePgData) shouldBe false
    Files.exists(root.resolve("ducklake").resolve("leftover.parquet")) shouldBe false
    Files.isDirectory(home.pgDir) shouldBe true
    Files.isDirectory(home.dataPath) shouldBe true
    Files.exists(root.resolve("keep.txt")) shouldBe true

    home.deleteRecursively()
  }

  // The clean above is destructive, so it must not fire while another demo still holds the home.
  it should "refuse to clean a home whose postgres is still running" in {
    val base   = Files.createTempDirectory("demo-home-live")
    val root   = base.resolve("qod-demo")
    val pgData = root.resolve("pg").resolve("pgdata")
    Files.createDirectories(pgData)
    // This JVM stands in for the live postmaster: what the guard tests is pid liveness.
    Files.writeString(pgData.resolve("postmaster.pid"), s"${ProcessHandle.current().pid()}\n/tmp\n")

    val ex = intercept[RuntimeException](DemoHome.create(Some(root.toString)))
    ex.getMessage should include("a demo is already running")
    Files.exists(pgData.resolve("postmaster.pid")) shouldBe true // not wiped
  }

  // Both halves of the pair alive is a genuinely live demo: refuse, and name the manager pid so
  // the operator knows what to stop rather than being told only that something is in the way.
  it should "refuse, naming the manager, when both the manager and its postgres are alive" in {
    val base   = Files.createTempDirectory("demo-home-pair")
    val root   = base.resolve("qod-demo")
    val pgData = root.resolve("pg").resolve("pgdata")
    Files.createDirectories(pgData)
    val self = ProcessHandle.current().pid()
    Files.writeString(pgData.resolve("postmaster.pid"), s"$self\n/tmp\n")
    Files.writeString(root.resolve("manager.pid"), s"$self\n")

    val ex = intercept[RuntimeException](DemoHome.create(Some(root.toString)))
    ex.getMessage should include("a demo is already running")
    ex.getMessage should include(s"manager pid $self")
    Files.exists(pgData.resolve("postmaster.pid")) shouldBe true // not wiped
  }

  // The case this guard could not previously distinguish: postgres alive, manager gone. That is
  // not a live demo, it is our own orphan, and it blocked every later run on the default home
  // with no self-service recovery. Reclaim it - but only after proving the process is ours.
  it should "reclaim an orphaned postgres whose manager is gone" in {
    val base   = Files.createTempDirectory("demo-home-orphan")
    val root   = base.resolve("qod-demo")
    val pgData = root.resolve("pg").resolve("pgdata")
    Files.createDirectories(pgData)
    // A real, killable stand-in whose command line names THIS home's pgdata, which is how
    // `create` proves the process belongs to this demo. `sh -c <cmd> <arg0>` puts the extra
    // operand on the command line without changing what runs.
    val victim = new ProcessBuilder("/bin/sh", "-c", "sleep 600", pgData.toString).start()
    try
      Files.writeString(pgData.resolve("postmaster.pid"), s"${victim.pid()}\n/tmp\n")
      // No manager.pid: the manager that owned this postgres is gone.

      val home = DemoHome.create(Some(root.toString))

      victim.isAlive shouldBe false
      Files.exists(pgData.resolve("postmaster.pid")) shouldBe false // wiped after reclaim
      Files.isDirectory(home.pgDir) shouldBe true
      home.deleteRecursively()
    finally victim.destroyForcibly()
  }

  // Ownership is proven from the process, never assumed from a missing manager.pid: an unrelated
  // live process holding that pid must be refused, not killed. This JVM stands in for it.
  it should "refuse rather than kill a live process it cannot prove it owns" in {
    val base   = Files.createTempDirectory("demo-home-notours")
    val root   = base.resolve("qod-demo")
    val pgData = root.resolve("pg").resolve("pgdata")
    Files.createDirectories(pgData)
    Files.writeString(pgData.resolve("postmaster.pid"), s"${ProcessHandle.current().pid()}\n/tmp\n")

    val ex = intercept[RuntimeException](DemoHome.create(Some(root.toString)))
    ex.getMessage should include("a demo is already running")
    ProcessHandle.current().isAlive shouldBe true
  }

  it should "record its own pid so a later run can tell a live demo from an orphan" in {
    val base     = Files.createTempDirectory("demo-home-pidfile")
    val home     = DemoHome.create(Some(base.resolve("qod-demo").toString))
    val recorded = Files.readString(home.root.resolve("manager.pid")).trim.toLong
    recorded shouldBe ProcessHandle.current().pid()
    home.deleteRecursively()
  }

  it should "clean past a postmaster.pid whose process is gone" in {
    val base   = Files.createTempDirectory("demo-home-deadpid")
    val root   = base.resolve("qod-demo")
    val pgData = root.resolve("pg").resolve("pgdata")
    Files.createDirectories(pgData)
    Files.writeString(pgData.resolve("postmaster.pid"), "4194304\n/tmp\n") // above any live pid

    val home = DemoHome.create(Some(root.toString))

    Files.exists(pgData.resolve("postmaster.pid")) shouldBe false
    home.deleteRecursively()
  }
