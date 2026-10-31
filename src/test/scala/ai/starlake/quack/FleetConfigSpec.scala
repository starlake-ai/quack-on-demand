package ai.starlake.quack

import ai.starlake.quack.boot.BootFactories
import ai.starlake.quack.ondemand.api.ConfigRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

class FleetConfigSpec extends AnyFlatSpec with Matchers:
  import Main.given

  "FleetConfig" should "round-trip its defaults from the bundled application.conf" in {
    val cfg = ConfigSource.default.at("quack-on-demand").loadOrThrow[ManagerConfig]
    cfg.fleet.joinToken shouldBe ""
    cfg.fleet.heartbeatSec shouldBe 5
    cfg.fleet.heartbeatTimeoutSec shouldBe 30
    cfg.fleet.reassignAfterSec shouldBe 600
    cfg.fleet.startupTimeoutSec shouldBe 120
    cfg.fleet.stopTimeoutSec shouldBe 60
    cfg.fleet.ephemeral shouldBe "fleet"
    cfg.fleet.autoApprove shouldBe "0.0.0.0/0,::/0"
    cfg.fleet.trustedProxies shouldBe ""
    cfg.fleet.nodePgHost shouldBe ""
    cfg.fleet.nodePgPort shouldBe ""
  }

  it should "read the camelCase keys of an overlay, not only the defaults" in {
    // Without a camelCase ProductHint the derived reader looks for kebab-case keys, silently
    // ignores every configured value (QOD_FLEET_JOIN_TOKEN included) and keeps the defaults.
    val cfg = ConfigSource
      .string(
        """quack-on-demand.fleet { joinToken = "t", heartbeatSec = 1, heartbeatTimeoutSec = 5,
          |reassignAfterSec = 10, startupTimeoutSec = 7, stopTimeoutSec = 8, ephemeral = "local" }""".stripMargin
      )
      .withFallback(ConfigSource.default)
      .at("quack-on-demand")
      .loadOrThrow[ManagerConfig]
    cfg.fleet shouldBe FleetConfig("t", 1, 5, 10, 7, 8, "local")
  }

  it should "read the node-facing Postgres address from camelCase keys" in {
    val cfg = ConfigSource
      .string(
        """quack-on-demand.fleet { nodePgHost = "host.docker.internal", nodePgPort = "15432" }"""
      )
      .withFallback(ConfigSource.default)
      .at("quack-on-demand")
      .loadOrThrow[ManagerConfig]
    (cfg.fleet.nodePgHost, cfg.fleet.nodePgPort) shouldBe ("host.docker.internal", "15432")
  }

  it should "flag joinToken as sensitive so ConfigHandlers masks it" in {
    val entries = ConfigRegistry.collect(List("quack-on-demand" -> classOf[ManagerConfig]))
    entries
      .find(_.path == "quack-on-demand.fleet.joinToken")
      .map(_.sensitive) shouldBe Some(true)
  }

  it should "default to the spec values" in {
    val c = FleetConfig()
    (
      c.heartbeatSec,
      c.heartbeatTimeoutSec,
      c.reassignAfterSec,
      c.startupTimeoutSec,
      c.stopTimeoutSec
    ) shouldBe (5, 30, 600, 120, 60)
  }

  it should "refuse a heartbeat timeout at or below the interval" in {
    an[IllegalArgumentException] should be thrownBy
      FleetConfig(heartbeatSec = 30, heartbeatTimeoutSec = 30)
  }

  it should "refuse a reassign grace below the heartbeat timeout unless 0 or -1" in {
    an[IllegalArgumentException] should be thrownBy FleetConfig(reassignAfterSec = 10)
    noException should be thrownBy FleetConfig(reassignAfterSec = 0)
    noException should be thrownBy FleetConfig(reassignAfterSec = -1)
  }

  it should "expose validateForRuntime that refuses an empty join token in fleet mode only" in {
    FleetConfig().validateForRuntime("fleet") shouldBe
      Left("QOD_FLEET_JOIN_TOKEN must be set when runtimeType=fleet")
    FleetConfig().validateForRuntime("local") shouldBe Right(())
    FleetConfig(joinToken = "x").validateForRuntime("fleet") shouldBe Right(())
  }

  // Ephemeral nodes on the manager host run qod-node, which Main resolves (and refuses to boot
  // without) for every manager that runs local nodes.
  it should
    "accept only fleet or local for ephemeral, local running its nodes on the manager host" in {
      an[IllegalArgumentException] should be thrownBy FleetConfig(ephemeral = "k8s")
      FleetConfig(ephemeral = "local").ephemeralLocal shouldBe true
      val base  = ConfigSource.default.at("quack-on-demand").loadOrThrow[ManagerConfig]
      val fleet = base.copy(runtimeType = "fleet", fleet = FleetConfig(joinToken = "x"))
      BootFactories.runsLocalNodes(fleet) shouldBe false
      BootFactories.runsLocalNodes(
        fleet.copy(fleet = FleetConfig(joinToken = "x", ephemeral = "local"))
      ) shouldBe true
      BootFactories.runsLocalNodes(base.copy(runtimeType = "local")) shouldBe true
      BootFactories.runsLocalNodes(base.copy(runtimeType = "kubernetes")) shouldBe false
    }

  it should "read autoApprove and trustedProxies from an overlay" in {
    val cfg = ConfigSource
      .string(
        """quack-on-demand.fleet { autoApprove = "10.0.0.0/8", trustedProxies = "192.168.1.1" }"""
      )
      .withFallback(ConfigSource.default)
      .at("quack-on-demand")
      .loadOrThrow[ManagerConfig]
    cfg.fleet.autoApproveCidrs.map(_.toString) shouldBe List("10.0.0.0/8")
    cfg.fleet.trustedProxyCidrs.map(_.toString) shouldBe List("192.168.1.1")
  }

  it should "refuse a bad CIDR, naming the env var" in {
    intercept[IllegalArgumentException](
      FleetConfig(autoApprove = "10.0.0.0/8,nope")
    ).getMessage should
      include("QOD_FLEET_AUTO_APPROVE")
    intercept[IllegalArgumentException](FleetConfig(trustedProxies = "300.0.0.1")).getMessage should
      include("QOD_FLEET_TRUSTED_PROXIES")
  }

  it should "treat an empty autoApprove as approving nobody" in {
    FleetConfig(autoApprove = "").autoApproveCidrs shouldBe Nil
  }

  it should "warn only when auto-approval admits a whole address family" in {
    FleetConfig().openAutoApproveWarning.get should include("QOD_FLEET_AUTO_APPROVE")
    FleetConfig(autoApprove = "::/0").openAutoApproveWarning should not be empty
    FleetConfig(autoApprove = "10.0.0.0/8,::1").openAutoApproveWarning shouldBe None
    FleetConfig(autoApprove = "").openAutoApproveWarning shouldBe None
  }

  it should "register both keys with their env vars" in {
    val entries = ConfigRegistry.collect(List("quack-on-demand" -> classOf[ManagerConfig]))
    entries.find(_.path == "quack-on-demand.fleet.autoApprove").map(_.envVar) shouldBe
      Some("QOD_FLEET_AUTO_APPROVE")
    entries.find(_.path == "quack-on-demand.fleet.trustedProxies").map(_.envVar) shouldBe
      Some("QOD_FLEET_TRUSTED_PROXIES")
  }
