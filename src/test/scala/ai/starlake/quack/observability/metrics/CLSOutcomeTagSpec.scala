package ai.starlake.quack.observability.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.jdk.CollectionConverters._

/** Pins the wire-level tag values emitted on `column_policy_rewrites_total`. The rewriter splits
  * `passthrough` into (`passthrough` | `parse_failed`) and `denied` into (`denied` |
  * `unresolved_deny`); this spec just verifies the counter accepts the new tag values without
  * mangling them.
  */
class CLSOutcomeTagSpec extends AnyFlatSpec with Matchers:

  "StatementInstruments" should "emit parse_failed and unresolved_deny tags" in {
    val registry = new SimpleMeterRegistry
    val si       = new StatementInstruments(registry)
    si.recordColumnPolicyRewrite("acme", "bi", "parse_failed")
    si.recordColumnPolicyRewrite("acme", "bi", "unresolved_deny")
    val meters   = registry.find("column_policy_rewrites_total").meters().asScala
    val outcomes = meters.map(_.getId.getTag("outcome")).toSet
    outcomes should contain allOf ("parse_failed", "unresolved_deny")
  }