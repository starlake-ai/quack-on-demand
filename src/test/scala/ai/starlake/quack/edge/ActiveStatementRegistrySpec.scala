package ai.starlake.quack.edge

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class ActiveStatementRegistrySpec extends AnyFlatSpec with Matchers:

  "register/list" should "expose entries newest first and truncate long SQL" in:
    val r  = new ActiveStatementRegistry(sqlPreviewChars = 10)
    val t0 = Instant.parse("2026-07-03T10:00:00Z")
    r.register("u1", "acme", "bi", "n1", "SELECT 1", now = t0)
    r.register("u2", "acme", "bi", "n2", "x" * 50, now = t0.plusSeconds(5))
    val listed = r.list()
    listed.map(_.nodeId) shouldBe List("n2", "n1")
    listed.head.sql.length shouldBe 11 // 10 chars + ellipsis

  "kill" should "invoke the cancel handle exactly once and remove the entry" in:
    val r     = new ActiveStatementRegistry()
    val calls = new AtomicInteger(0)
    val id    = r.register("u1", "acme", "bi", "n1", "SELECT 1")
    r.attachCancel(id, () => { calls.incrementAndGet(); () })
    r.kill(id).map(_.user) shouldBe Some("u1")
    calls.get() shouldBe 1
    r.kill(id) shouldBe None
    r.list() shouldBe Nil

  it should "swallow exceptions thrown by the cancel handle" in:
    val r  = new ActiveStatementRegistry()
    val id = r.register("u1", "acme", "bi", "n1", "SELECT 1")
    r.attachCancel(id, () => throw new IllegalStateException("already closed"))
    r.kill(id).isDefined shouldBe true

  "deregister" should "be a no-op for unknown ids and drop known ones" in:
    val r  = new ActiveStatementRegistry()
    val id = r.register("u1", "acme", "bi", "n1", "SELECT 1")
    r.deregister("unknown")
    r.deregister(id)
    r.list() shouldBe Nil
