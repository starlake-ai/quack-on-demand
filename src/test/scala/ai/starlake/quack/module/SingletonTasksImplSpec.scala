package ai.starlake.quack.module

import ai.starlake.quack.ondemand.module.SingletonTasksImpl
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.concurrent.duration.*

class SingletonTasksImplSpec extends AnyFlatSpec with Matchers:

  "SingletonTasksImpl" should "run tasks only while leader and survive task failures" in {
    val tasks   = new SingletonTasksImpl
    val leader  = new AtomicBoolean(false)
    val ticks   = new AtomicInteger(0)
    val boomers = new AtomicInteger(0)

    tasks.register("counter", 20.millis)(IO(ticks.incrementAndGet()).void)
    tasks.register("boomer", 20.millis)(
      IO(boomers.incrementAndGet()) *> IO.raiseError(new RuntimeException("boom"))
    )

    val fibers = tasks.loops(() => leader.get()).map(_.start.unsafeRunSync())
    try
      Thread.sleep(200)
      ticks.get() shouldBe 0 // follower: gated off

      leader.set(true)
      Thread.sleep(300)
      ticks.get() should be > 2   // leader: ticking
      boomers.get() should be > 2 // failing task keeps rescheduling
    finally fibers.foreach(_.cancel.unsafeRunSync())
  }
