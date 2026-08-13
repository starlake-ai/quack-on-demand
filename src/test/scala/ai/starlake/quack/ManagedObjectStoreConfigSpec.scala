package ai.starlake.quack

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

class ManagedObjectStoreConfigSpec extends AnyFlatSpec with Matchers:
  import Main.given

  "ManagedObjectStoreConfig" should "load defaults from application.conf" in:
    val cfg = ConfigSource.default.at("quack-on-demand").loadOrThrow[ManagerConfig]
    cfg.managedObjectStore.enabled shouldBe false
    cfg.managedObjectStore.endpoint shouldBe ""
    cfg.managedObjectStore.region shouldBe "us-east-1"
    cfg.managedObjectStore.bucket shouldBe "qod-managed"
    cfg.managedObjectStore.urlStyle shouldBe "path"
    cfg.managedObjectStore.retainDays shouldBe 7
    cfg.managedObjectStore.purgeSweepSec shouldBe 300

  it should "honor camelCase overlays" in:
    val cfg = ConfigSource
      .string("""quack-on-demand { managedObjectStore { retainDays = 30, urlStyle = "vhost" } }""")
      .withFallback(ConfigSource.default)
      .at("quack-on-demand")
      .loadOrThrow[ManagerConfig]
    cfg.managedObjectStore.retainDays shouldBe 30
    cfg.managedObjectStore.urlStyle shouldBe "vhost"

  it should "clamp the purge sweep to the 60s floor" in:
    ManagedObjectStoreConfig(purgeSweepSec = 5).purgeSweepInterval.toSeconds shouldBe 60L

  it should "refuse invalid bounds" in:
    an[IllegalArgumentException] should be thrownBy ManagedObjectStoreConfig(retainDays = -1)
    an[IllegalArgumentException] should be thrownBy ManagedObjectStoreConfig(urlStyle = "weird")
