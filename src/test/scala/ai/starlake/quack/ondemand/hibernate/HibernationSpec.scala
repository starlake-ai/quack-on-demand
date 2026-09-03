package ai.starlake.quack.ondemand.hibernate

import ai.starlake.quack.model.PoolKey
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.duration.DurationInt

class HibernationSpec extends AnyFlatSpec with Matchers:
  private val poolKey           = PoolKey("t", "db", "p")
  private val idle          = 30.minutes
  private val now           = Instant.parse("2026-09-03T12:00:00Z")
  private def view(
      k: PoolKey = poolKey,
      suspended: Boolean = false,
      disabled: Boolean = false,
      size: Int = 2
  ) = HibernationView(k, suspended, disabled, size, idle)

  "candidates" should "suspend a pool idle for at least its window" in:
    val activity     = Map(poolKey -> now.minusSeconds(31 * 60))
    val (out, first) = Hibernation.candidates(now, List(view()), activity, Map.empty)
    out shouldBe List(poolKey)
    first shouldBe empty

  it should "leave a recently active pool running" in:
    val activity  = Map(poolKey -> now.minusSeconds(29 * 60))
    val (out, _)  = Hibernation.candidates(now, List(view()), activity, Map.empty)
    out shouldBe empty

  it should "respect a per-pool window shorter than another pool's" in:
    val k2       = PoolKey("t", "db", "q")
    val views    = List(view(), HibernationView(k2, false, false, 1, 10.minutes))
    val activity = Map(poolKey -> now.minusSeconds(15 * 60), k2 -> now.minusSeconds(15 * 60))
    val (out, _) = Hibernation.candidates(now, views, activity, Map.empty)
    out shouldBe List(k2)

  it should "baseline a never-active pool at first sight, then suspend after the window" in:
    val (out1, first1) = Hibernation.candidates(now, List(view()), Map.empty, Map.empty)
    out1 shouldBe empty
    first1 shouldBe Map(poolKey -> now)
    val later          = now.plusSeconds(31 * 60)
    val (out2, first2) = Hibernation.candidates(later, List(view()), Map.empty, first1)
    out2 shouldBe List(poolKey)
    first2 shouldBe Map(poolKey -> now)

  it should "prune the first-seen entry once the pool gains an activity row" in:
    val first        = Map(poolKey -> now.minusSeconds(3600))
    val activity     = Map(poolKey -> now.minusSeconds(60))
    val (out, first2) = Hibernation.candidates(now, List(view()), activity, first)
    out shouldBe empty
    first2 shouldBe empty

  it should "ignore suspended, disabled and zero-size pools and prune their baselines" in:
    val views = List(
      view(suspended = true),
      view(k = PoolKey("t", "db", "d"), disabled = true),
      view(k = PoolKey("t", "db", "z"), size = 0)
    )
    val first        = views.map(v => v.key -> now.minusSeconds(7200)).toMap
    val (out, first2) = Hibernation.candidates(now, views, Map.empty, first)
    out shouldBe empty
    first2 shouldBe empty
