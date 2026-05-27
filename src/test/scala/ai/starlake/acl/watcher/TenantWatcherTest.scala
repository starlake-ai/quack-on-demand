package ai.starlake.acl.watcher

import ai.starlake.acl.model.TenantId
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.nio.file.{Files, Path}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.collection.mutable.ListBuffer
import scala.compiletime.uninitialized
import scala.util.Using

class TenantWatcherTest extends AnyFunSuite with Matchers with BeforeAndAfterEach:

  private var tempDir: Path = uninitialized

  override def beforeEach(): Unit =
    tempDir = Files.createTempDirectory("watcher-test")

  override def afterEach(): Unit =
    deleteRecursively(tempDir)

  test("WatcherConfig has sensible defaults") {
    val config = WatcherConfig.default
    config.debounceMs shouldBe 500
    config.maxBackoffMs shouldBe 60000
    config.filePatterns shouldBe Set("*.yaml", "*.yml")
  }

  test("WatcherStatus.Healthy is initial status") {
    val latch = new CountDownLatch(1)
    val listener = new TestListener(latch)

    Using.resource(new TenantWatcher(tempDir, listener, WatcherConfig(debounceMs = 50))) { watcher =>
      watcher.status shouldBe WatcherStatus.Healthy
    }
  }

  test("detects new YAML file in tenant folder") {
    val latch = new CountDownLatch(1)
    val listener = new TestListener(latch)

    // Create tenant folder first
    val tenantDir = tempDir.resolve("acme")
    val _ = Files.createDirectory(tenantDir)

    Using.resource(new TenantWatcher(tempDir, listener, WatcherConfig(debounceMs = 50))) { watcher =>
      // Give watcher time to register directories
      Thread.sleep(200)

      // Create YAML file
      val _ = Files.writeString(tenantDir.resolve("acl.yaml"), "grants: []")

      // Wait for callback
      val received = latch.await(5, TimeUnit.SECONDS)
      received shouldBe true
      listener.invalidated should contain(TenantId.parse("acme").toOption.get)
    }
  }

  test("debounces rapid file changes") {
    val latch = new CountDownLatch(1)
    val listener = new TestListener(latch)

    val tenantDir = tempDir.resolve("rapid")
    val _ = Files.createDirectory(tenantDir)

    Using.resource(new TenantWatcher(tempDir, listener, WatcherConfig(debounceMs = 200))) { watcher =>
      // Give watcher time to register directories
      Thread.sleep(300)

      val aclFile = tenantDir.resolve("acl.yaml")
      // Rapid changes - multiple writes in quick succession
      for i <- 1 to 5 do
        val _ = Files.writeString(aclFile, s"# version $i\ngrants: []")
        Thread.sleep(30)

      // Wait for debounced callback (debounce is 200ms + some buffer)
      val received = latch.await(3, TimeUnit.SECONDS)
      received shouldBe true

      // After all rapid changes, should receive only one invalidation
      listener.invalidated.count(_ == TenantId.parse("rapid").toOption.get) shouldBe 1
    }
  }

  test("detects new tenant folder") {
    val latch = new CountDownLatch(1)
    val listener = new TestListener(latch, captureNewTenant = true)

    Using.resource(new TenantWatcher(tempDir, listener, WatcherConfig(debounceMs = 50))) { watcher =>
      Thread.sleep(200)

      // Create new tenant folder
      val _ = Files.createDirectory(tempDir.resolve("newtenant"))

      val received = latch.await(5, TimeUnit.SECONDS)
      received shouldBe true
      listener.newTenants should contain(TenantId.parse("newtenant").toOption.get)
    }
  }

  test("status returns Healthy after successful initialization") {
    val listener = new TestListener(new CountDownLatch(1))

    Using.resource(new TenantWatcher(tempDir, listener)) { watcher =>
      Thread.sleep(100)
      watcher.status shouldBe WatcherStatus.Healthy
    }
  }

  test("ignores non-YAML files") {
    val latch = new CountDownLatch(1)
    val listener = new TestListener(latch)

    val tenantDir = tempDir.resolve("ignore")
    val _ = Files.createDirectory(tenantDir)

    Using.resource(new TenantWatcher(tempDir, listener, WatcherConfig(debounceMs = 50))) { watcher =>
      Thread.sleep(200)

      // Create non-YAML file
      val _ = Files.writeString(tenantDir.resolve("readme.txt"), "ignore me")

      // Wait a bit - should NOT trigger callback
      Thread.sleep(300)
      listener.invalidated shouldBe empty
    }
  }

  test("detects .yml extension files") {
    val latch = new CountDownLatch(1)
    val listener = new TestListener(latch)

    val tenantDir = tempDir.resolve("ymltest")
    val _ = Files.createDirectory(tenantDir)

    Using.resource(new TenantWatcher(tempDir, listener, WatcherConfig(debounceMs = 50))) { watcher =>
      Thread.sleep(200)

      // Create .yml file
      val _ = Files.writeString(tenantDir.resolve("acl.yml"), "grants: []")

      val received = latch.await(5, TimeUnit.SECONDS)
      received shouldBe true
      listener.invalidated should contain(TenantId.parse("ymltest").toOption.get)
    }
  }

  private class TestListener(
      latch: CountDownLatch,
      captureNewTenant: Boolean = false
  ) extends TenantListener:
    val invalidated: ListBuffer[TenantId] = ListBuffer.empty
    val newTenants: ListBuffer[TenantId] = ListBuffer.empty
    val deleted: ListBuffer[TenantId] = ListBuffer.empty

    override def onInvalidate(tenantId: TenantId): Unit =
      invalidated += tenantId
      if !captureNewTenant then latch.countDown()

    override def onNewTenant(tenantId: TenantId): Unit =
      newTenants += tenantId
      if captureNewTenant then latch.countDown()

    override def onTenantDeleted(tenantId: TenantId): Unit =
      deleted += tenantId

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      if Files.isDirectory(path) then
        Files.list(path).forEach(deleteRecursively)
      val _ = Files.delete(path)
