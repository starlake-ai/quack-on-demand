package ai.starlake.quack.edge

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PreparedHandlesSpec extends AnyFlatSpec with Matchers:

  private final case class Exec(sql: String, conn: String)

  private final class Clock:
    @volatile var now: Long = 1000L

  private def handles(clock: Clock, ttlMillis: Long = 10000L) =
    new PreparedHandles[Exec](ttlMillis, () => clock.now, _.conn)

  "PreparedHandles" should "drop every handle of an evicted session and keep the others" in:
    val c = new Clock
    val h = handles(c)
    h.put("a1", Exec("SELECT 1", "conn-a"))
    h.put("a2", Exec("SELECT 2", "conn-a"))
    h.put("b1", Exec("SELECT 3", "conn-b"))
    h.dropConnection("conn-a") shouldBe 2
    h.get("a1") shouldBe None
    h.get("a2") shouldBe None
    h.get("b1") shouldBe Some(Exec("SELECT 3", "conn-b"))
    h.size shouldBe 1

  it should "expire a handle unused for the idle TTL and keep one used within it" in:
    val c = new Clock
    val h = handles(c)
    h.put("idle", Exec("SELECT 1", "conn"))
    h.put("used", Exec("SELECT 2", "conn"))
    c.now += 6000
    h.get("used") shouldBe defined
    c.now += 4000
    h.expireIdle() shouldBe 1
    h.get("idle") shouldBe None
    h.get("used") shouldBe defined
    h.size shouldBe 1

  it should "refuse an idle handle on access even before a sweep ran" in:
    val c = new Clock
    val h = handles(c)
    h.put("idle", Exec("SELECT 1", "conn"))
    c.now += 10000
    h.get("idle") shouldBe None
    h.size shouldBe 0

  it should "forget a closed handle" in:
    val c = new Clock
    val h = handles(c)
    h.put("x", Exec("SELECT 1", "conn"))
    h.remove("x")
    h.get("x") shouldBe None
    h.size shouldBe 0

  it should "refuse a non-positive idle TTL" in:
    an[IllegalArgumentException] should be thrownBy handles(new Clock, ttlMillis = 0L)
