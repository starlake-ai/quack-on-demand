package ai.starlake.acl.store

import ai.starlake.acl.model.TenantId
import ai.starlake.acl.watcher.{TenantListener, WatcherStatus}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.{CopyOnWriteArrayList, CountDownLatch, TimeUnit}

class PollingChangeDetectorTest extends AnyFunSuite with Matchers:

  private def tenant(name: String): TenantId =
    TenantId.parse(name).toOption.get

  /** Listener that records callbacks for assertion. */
  private class RecordingListener extends TenantListener:
    val invalidated = new CopyOnWriteArrayList[String]()
    val newTenants = new CopyOnWriteArrayList[String]()
    val deletedTenants = new CopyOnWriteArrayList[String]()
    var latch: CountDownLatch = new CountDownLatch(0)

    override def onInvalidate(tenantId: TenantId): Unit =
      invalidated.add(tenantId.canonical)
      latch.countDown()

    override def onNewTenant(tenantId: TenantId): Unit =
      newTenants.add(tenantId.canonical)
      latch.countDown()

    override def onTenantDeleted(tenantId: TenantId): Unit =
      deletedTenants.add(tenantId.canonical)
      latch.countDown()

  test("detects new tenant after poll") {
    val store = new InMemoryAclStore()
    store.addTenant("existing")
    store.addFile("existing", "acl.yaml", "grants: []")

    val listener = new RecordingListener()
    listener.latch = new CountDownLatch(1)
    val detector = new PollingChangeDetector(store, pollIntervalMs = 100)

    try
      detector.start(listener)
      // Add new tenant after start
      store.addTenant("newone")
      store.addFile("newone", "acl.yaml", "grants: []")

      listener.latch.await(2, TimeUnit.SECONDS) shouldBe true
      listener.newTenants should contain("newone")
    finally detector.close()
  }

  test("detects deleted tenant after poll") {
    val store = new InMemoryAclStore()
    store.addTenant("will-delete")
    store.addFile("will-delete", "acl.yaml", "grants: []")

    val listener = new RecordingListener()
    listener.latch = new CountDownLatch(1)
    val detector = new PollingChangeDetector(store, pollIntervalMs = 100)

    try
      detector.start(listener)
      store.removeTenant("will-delete")

      listener.latch.await(2, TimeUnit.SECONDS) shouldBe true
      listener.deletedTenants should contain("will-delete")
    finally detector.close()
  }

  test("detects file changes and invalidates tenant") {
    val store = new InMemoryAclStore()
    store.addTenant("tenant-a")
    store.addFile("tenant-a", "acl.yaml", "grants: []")

    val listener = new RecordingListener()
    listener.latch = new CountDownLatch(1)
    val detector = new PollingChangeDetector(store, pollIntervalMs = 100)

    try
      detector.start(listener)
      // Add a new file to trigger change
      store.addFile("tenant-a", "extra.yaml", "grants: []")

      listener.latch.await(2, TimeUnit.SECONDS) shouldBe true
      listener.invalidated should contain("tenant-a")
    finally detector.close()
  }

  test("detects lastModified change") {
    val store = new InMemoryAclStore()
    store.addTenant("tenant-b")
    store.addFile("tenant-b", "acl.yaml", "grants: []")
    store.setLastModified("tenant-b", "acl.yaml", Instant.parse("2025-01-01T00:00:00Z"))

    val listener = new RecordingListener()
    listener.latch = new CountDownLatch(1)
    val detector = new PollingChangeDetector(store, pollIntervalMs = 100)

    try
      detector.start(listener)
      // Update lastModified to trigger change
      store.setLastModified("tenant-b", "acl.yaml", Instant.parse("2025-06-01T00:00:00Z"))

      listener.latch.await(2, TimeUnit.SECONDS) shouldBe true
      listener.invalidated should contain("tenant-b")
    finally detector.close()
  }

  test("status is Healthy when polling succeeds") {
    val store = new InMemoryAclStore()
    val detector = new PollingChangeDetector(store, pollIntervalMs = 100)

    try
      detector.status shouldBe WatcherStatus.Healthy
      val listener = new RecordingListener()
      detector.start(listener)
      Thread.sleep(200)
      detector.status shouldBe WatcherStatus.Healthy
    finally detector.close()
  }

  test("close stops polling") {
    val store = new InMemoryAclStore()
    val listener = new RecordingListener()
    val detector = new PollingChangeDetector(store, pollIntervalMs = 50)

    detector.start(listener)
    detector.close()

    // Add tenant after close - should not be detected
    store.addTenant("after-close")
    Thread.sleep(200)
    listener.newTenants.size() shouldBe 0
  }
