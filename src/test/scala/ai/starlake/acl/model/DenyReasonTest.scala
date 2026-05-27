package ai.starlake.acl.model

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import io.circe.syntax.*

class DenyReasonTest extends AnyFunSuite with Matchers {

  // Import the encoder from AccessResult companion
  import AccessResult.denyReasonEncoder

  test("all DenyReason cases can be exhaustively matched") {
    val reasons: List[DenyReason] = List(
      DenyReason.ParseError("test"),
      DenyReason.NoMatchingGrant(TableRef("db", "schema", "table"), UserIdentity("user", Set.empty)),
      DenyReason.ViewResolutionCycle(List(TableRef("db", "schema", "view"))),
      DenyReason.UnknownView(TableRef("db", "schema", "view")),
      DenyReason.UnsupportedStatement("UPDATE"),
      DenyReason.UnqualifiedTable("mytable", "database"),
      DenyReason.ViewParseError(TableRef("db", "schema", "view"), "parse error"),
      DenyReason.CallbackError(TableRef("db", "schema", "table"), "callback failed"),
      DenyReason.ExpiredGrant(TableRef("db", "schema", "table"), UserIdentity("bob", Set.empty), java.time.Instant.parse("2025-01-01T00:00:00Z")),
      DenyReason.MaxViewDepthExceeded(List(TableRef("db", "schema", "v1"), TableRef("db", "schema", "v2")))
    )

    // All cases should be matchable
    val descriptions = reasons.map {
      case DenyReason.ParseError(msg)            => s"ParseError: $msg"
      case DenyReason.NoMatchingGrant(t, u)      => s"NoMatchingGrant: ${t.canonical} for ${u.name}"
      case DenyReason.ViewResolutionCycle(chain) => s"ViewResolutionCycle: ${chain.size} views"
      case DenyReason.UnknownView(v)             => s"UnknownView: ${v.canonical}"
      case DenyReason.UnsupportedStatement(s)    => s"UnsupportedStatement: $s"
      case DenyReason.UnqualifiedTable(n, m)     => s"UnqualifiedTable: $n missing $m"
      case DenyReason.ViewParseError(v, msg)     => s"ViewParseError: ${v.canonical}"
      case DenyReason.CallbackError(t, msg)      => s"CallbackError: ${t.canonical}"
      case DenyReason.ExpiredGrant(t, u, exp)    => s"ExpiredGrant: ${t.canonical} for ${u.name} expired at $exp"
      case DenyReason.MaxViewDepthExceeded(path) => s"MaxViewDepthExceeded: ${path.map(_.canonical).mkString(" -> ")}"
    }

    descriptions should have length 10
    descriptions.last shouldBe "MaxViewDepthExceeded: db.schema.v1 -> db.schema.v2"
  }

  test("MaxViewDepthExceeded JSON encoding produces correct structure") {
    val path = List(TableRef("db", "sch", "v1"), TableRef("db", "sch", "v2"), TableRef("db", "sch", "v3"))
    val reason: DenyReason = DenyReason.MaxViewDepthExceeded(path)

    val json = reason.asJson

    json.hcursor.downField("type").as[String] shouldBe Right("maxViewDepthExceeded")
    val pathArray = json.hcursor.downField("path").as[List[io.circe.Json]]
    pathArray.isRight shouldBe true
    pathArray.toOption.get should have length 3
    pathArray.toOption.get.head.hcursor.downField("canonical").as[String] shouldBe Right("db.sch.v1")
  }
}
