package ai.starlake.quack.boot

import io.netty.buffer.UnpooledByteBufAllocator
import io.netty.util.internal.PlatformDependent
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.lang.management.{BufferPoolMXBean, ManagementFactory}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.concurrent.TimeUnit
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** A full direct memory must not halt the manager. Netty counts its buffers in the JDK's own direct
  * budget unless it takes the allocator of its own, and then a socket read on the REST port fails
  * with an `OutOfMemoryError` on a compute thread, which is fatal. These cases pin the three things
  * that keep the two budgets apart: the property is set when nobody set it, the forked test JVM
  * runs with it (the suite runs what production runs), and `Main` sets it before any Netty class
  * loads (a launch from the jar has no `-D` flag, so that is the only thing production has).
  */
class NettyMemorySpec extends AnyFlatSpec with Matchers:

  private val Key = "io.netty.tryReflectionSetAccessible"

  "configure" should "ask Netty for its own allocator when nobody set the property" in {
    val set = mutable.Map.empty[String, String]
    NettyMemory.configure((k, v) => set.update(k, v), _ => None)
    set.toMap shouldBe Map(Key -> "true")
  }

  it should "leave an operator's value alone, whatever it is" in
    List("false", "true").foreach { value =>
      val set = mutable.Map.empty[String, String]
      NettyMemory.configure(
        (k, v) => set.update(k, v),
        k => Option.when(k == Key)(value)
      )
      withClue(s"operator value '$value': ")(set shouldBe empty)
    }

  it should "set the JVM property when none is set, and only then" in {
    val before = Option(System.getProperty(Key))
    try
      System.clearProperty(Key)
      NettyMemory.configure()
      System.getProperty(Key) shouldBe "true"
      System.setProperty(Key, "false")
      NettyMemory.configure()
      System.getProperty(Key) shouldBe "false"
    finally before.fold(System.clearProperty(Key))(v => System.setProperty(Key, v))
  }

  "Netty in the forked test JVM" should "have taken the allocator of its own" in {
    NettyMemory.separate shouldBe true
  }

  it should "count its buffers in its own budget and leave the JDK's alone" in {
    val direct: BufferPoolMXBean =
      ManagementFactory
        .getPlatformMXBeans(classOf[BufferPoolMXBean])
        .asScala
        .find(_.getName == "direct")
        .getOrElse(fail("the JVM reports no direct buffer pool"))
    val size       = 8L * 1024 * 1024
    val jdkBefore  = direct.getMemoryUsed
    val nettyStart = PlatformDependent.usedDirectMemory()
    val buf        = UnpooledByteBufAllocator.DEFAULT.directBuffer(size.toInt)
    try
      // usedDirectMemory is -1 when Netty keeps no count of its own: it then shares the JDK's.
      (PlatformDependent.usedDirectMemory() - nettyStart) should be >= size
      (direct.getMemoryUsed - jdkBefore) should be < (size / 2)
    finally buf.release()
  }

  "Main" should "ask for Netty's own allocator before anything loads Netty" in {
    // A launch from the jar has only the manifest's Add-Opens and no -D flag, which the forked test
    // JVM has: run a JVM without it that does nothing but load Main.
    val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString
    val proc = new ProcessBuilder(
      java,
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      "-cp",
      System.getProperty("java.class.path"),
      NettyMemoryChild.getClass.getName.stripSuffix("$")
    ).redirectErrorStream(true).start()
    val out      = new String(proc.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    val finished = proc.waitFor(120, TimeUnit.SECONDS)
    if !finished then proc.destroyForcibly()
    withClue(s"child JVM output:\n$out\n") {
      finished shouldBe true
      proc.exitValue() shouldBe 0
      out.linesIterator.toList should contain("separate=true")
    }
  }

  it should "make that call the first statement of its body" in {
    val src   = Files.readAllLines(Paths.get("src/main/scala/ai/starlake/quack/Main.scala")).asScala
    val start = src.indexWhere(_.startsWith("object Main extends IOApp"))
    start should be >= 0
    val first = src
      .drop(start + 1)
      .map(_.trim)
      .find(l => l.nonEmpty && !l.startsWith("//") && !l.startsWith("/*") && !l.startsWith("*"))
    first shouldBe Some("NettyMemory.configure()")
  }

  "warning" should "say nothing when Netty has the allocator of its own" in {
    NettyMemory.warning(isSeparate = true) shouldBe None
  }

  it should "say that the budget is shared, what that can do, and what a launch must pass" in {
    val w = NettyMemory.warning(isSeparate = false).getOrElse(fail("no warning"))
    w should include("shares the JDK's direct memory")
    w should include("can halt the manager")
    w should include("--add-opens=java.base/java.nio=ALL-UNNAMED")
  }

  it should "follow this JVM's real state by default" in {
    NettyMemory.warning().isEmpty shouldBe NettyMemory.separate
  }

/** The JVM `NettyMemorySpec` starts with no `-D` flag. Touching `Main`'s object runs its body,
  * whose first statement is the call under test; nothing here may load a Netty class before it.
  */
object NettyMemoryChild:
  def main(args: Array[String]): Unit =
    val _ = ai.starlake.quack.Main.hashCode
    println(s"separate=${NettyMemory.separate}")
