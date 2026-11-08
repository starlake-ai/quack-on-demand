package ai.starlake.quack.ondemand.security

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*

class SecurityAuditSpec extends AnyFlatSpec with Matchers:

  private val ctx = AuditContext(ConfigFactory.empty(), StubAuditFacts())

  private def check(id: String, status: Status, area: Area = Area.Network): SecurityCheck =
    SecurityCheck(
      id,
      area,
      s"title $id",
      List("X"),
      "/operating/tls",
      _ => IO.pure(Outcome(status, s"d $id"))
    )

  private def audit(checks: List[SecurityCheck], timeout: FiniteDuration = 5.seconds) =
    SecurityAudit(checks, ctx, replica = "r1", version = "v1", timeout = timeout)
      .run()
      .unsafeRunSync()

  "SecurityAudit" should "order critical, warn, error, ok, na, then by id" in {
    val r = audit(
      List(
        check("b.ok", Status.Ok),
        check("z.na", Status.Na),
        check("a.ok", Status.Ok),
        check("w.warn", Status.Warn),
        check("c.crit", Status.Critical),
        check("e.err", Status.Error)
      )
    )
    r.findings.map(_.id) shouldBe List("c.crit", "w.warn", "e.err", "a.ok", "b.ok", "z.na")
    r.summary shouldBe AuditSummary(critical = 1, warn = 1, ok = 2, na = 1, error = 1)
    r.replica shouldBe "r1"
    r.version shouldBe "v1"
  }

  it should "turn a throwing check into error with the fixed detail, and run the others" in {
    val boom = SecurityCheck(
      "x.boom",
      Area.Secrets,
      "t",
      Nil,
      "/operating/tls",
      _ => IO.raiseError(new RuntimeException("password=hunter2"))
    )
    val r = audit(List(boom, check("a.ok", Status.Ok)))
    val f = r.findings.find(_.id == "x.boom").get
    f.status shouldBe Status.Error
    f.detail shouldBe SecurityAudit.FailedDetail
    f.detail should not include "hunter2"
    r.findings.find(_.id == "a.ok").get.status shouldBe Status.Ok
  }

  it should "turn a check past its timeout into error" in {
    val slow = SecurityCheck(
      "x.slow",
      Area.Data,
      "t",
      Nil,
      "/operating/tls",
      _ => IO.sleep(2.seconds).as(Outcome(Status.Ok, "late"))
    )
    val f = audit(List(slow), timeout = 100.millis).findings.head
    f.status shouldBe Status.Error
    f.detail shouldBe SecurityAudit.FailedDetail
  }

  it should "build docs links from DocsBase" in {
    audit(List(check("a.ok", Status.Ok))).findings.head.docs shouldBe
      "https://docs.starlake.ai/qod/operating/tls"
  }

  it should "bound a check stuck in a blocking call by its timeout" in {
    val stuck = SecurityCheck(
      "x.blocked",
      Area.Data,
      "t",
      Nil,
      "/operating/tls",
      _ => IO.blocking(Thread.sleep(2000)).as(Outcome(Status.Ok, "late"))
    )
    val t0 = System.nanoTime()
    val f  = audit(List(stuck), timeout = 100.millis).findings.head
    val ms = (System.nanoTime() - t0) / 1000000
    f.status shouldBe Status.Error
    f.detail shouldBe SecurityAudit.FailedDetail
    ms should be < 1500L
  }

  it should "turn a check that throws synchronously into error with the fixed detail" in {
    val boom = SecurityCheck(
      "x.sync",
      Area.Secrets,
      "t",
      Nil,
      "/operating/tls",
      _ => throw new RuntimeException("password=x")
    )
    val f = audit(List(boom)).findings.head
    f.status shouldBe Status.Error
    f.detail shouldBe SecurityAudit.FailedDetail
    f.detail should not include "password"
  }
