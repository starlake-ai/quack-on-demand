package ai.starlake.quack.boot

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer

/** The leader's boot duties run in one order: serve provisioning after the supervisor has loaded
  * the store, and before DuckLake init, pod discovery and reconcile, so what it creates is
  * initialized and spawned in the same boot.
  */
class LeaderDutiesSpec extends AnyFlatSpec with Matchers:

  "LeaderDuties" should "run serve provisioning after restore and before reconcile" in {
    val ran                          = ListBuffer.empty[String]
    def duty(name: String): IO[Unit] = IO(ran += name).void
    LeaderDuties(
      bootstrap = duty("bootstrap"),
      restore = duty("restore"),
      provision = duty("provision"),
      initDuckLake = duty("initDuckLake"),
      discover = duty("discover"),
      reconcile = duty("reconcile"),
      seedRevoked = duty("seedRevoked")
    ).unsafeRunSync()
    ran.toList shouldBe List(
      "bootstrap",
      "restore",
      "provision",
      "initDuckLake",
      "discover",
      "reconcile",
      "seedRevoked"
    )
  }
