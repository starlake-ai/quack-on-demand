package ai.starlake.quack.ondemand.maintenance

import ai.starlake.quack.model.{EffectivePolicy, MaintenancePolicy, Names}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PolicyMathSpec extends AnyFlatSpec with Matchers:

  private def row(
      kind: String,
      schema: Option[String],
      table: Option[String],
      retention: Option[Int] = None,
      enabled: Option[Boolean] = None
  ) =
    MaintenancePolicy(
      Names.newSurrogateId("mpol"),
      "acme",
      "acme_db",
      kind,
      schema,
      table,
      enabled = enabled,
      retentionDays = retention,
      compactionEnabled = None,
      targetFileSize = None,
      smallFileMinCount = None,
      rewriteDeleteThreshold = None,
      cleanupGraceDays = None,
      orphanMinAgeDays = None,
      cron = None
    )

  "effective" should "fall back to defaults with no rows" in {
    PolicyMath.effective(Nil, None, None) shouldBe EffectivePolicy.defaults
  }

  it should "apply precedence table > schema > tenantdb > defaults per field" in {
    val rows = List(
      row("tenantdb", None, None, retention = Some(30), enabled = Some(true)),
      row("schema", Some("tpch1"), None, retention = Some(14)),
      row("table", Some("tpch1"), Some("region"), retention = Some(3))
    )
    PolicyMath.effective(rows, Some("tpch1"), Some("region")).retentionDays shouldBe 3
    PolicyMath.effective(rows, Some("tpch1"), Some("nation")).retentionDays shouldBe 14
    PolicyMath.effective(rows, Some("other"), Some("x")).retentionDays shouldBe 30
    // enabled only set at tenantdb level flows through everywhere
    PolicyMath.effective(rows, Some("tpch1"), Some("region")).enabled shouldBe true
    // unset fields everywhere -> defaults
    PolicyMath.effective(rows, Some("tpch1"), Some("region")).cron shouldBe
      EffectivePolicy.defaults.cron
  }

  "staggerMinutes" should "be deterministic and in 0..59" in {
    val a = PolicyMath.staggerMinutes("acme", "acme_db")
    a shouldBe PolicyMath.staggerMinutes("acme", "acme_db")
    a should (be >= 0 and be <= 59)
  }
