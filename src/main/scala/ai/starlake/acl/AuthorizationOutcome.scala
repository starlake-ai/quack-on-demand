package ai.starlake.acl

import ai.starlake.acl.model.{AccessResult, Decision, TableAccess, TableRef, ViewResolution}
import ai.starlake.acl.model.AccessResult.{tableRefEncoder, tableAccessEncoder, viewResolutionEncoder}
import io.circe.{Encoder, Json}
import io.circe.syntax.*

import scala.concurrent.duration.{FiniteDuration, NANOSECONDS}

/** Timing breakdown for authorization.
  *
  * @param parseNanos
  *   Time spent parsing SQL in nanoseconds
  * @param evaluateAndResolveNanos
  *   Time spent evaluating grants and resolving views in nanoseconds
  */
final case class Timing(
    parseNanos: Long,
    evaluateAndResolveNanos: Long
) {
  def totalNanos: Long = parseNanos + evaluateAndResolveNanos

  def parseDuration: FiniteDuration = FiniteDuration(parseNanos, NANOSECONDS)

  def evaluateAndResolveDuration: FiniteDuration = FiniteDuration(evaluateAndResolveNanos, NANOSECONDS)

  def totalDuration: FiniteDuration = FiniteDuration(totalNanos, NANOSECONDS)

  private[acl] def parseMs: Double = parseNanos / 1_000_000.0
  private[acl] def evaluateAndResolveMs: Double = evaluateAndResolveNanos / 1_000_000.0
  private[acl] def totalMs: Double = totalNanos / 1_000_000.0
}

/** Trace information for debugging and observability.
  *
  * Only populated when trace=true is passed to authorize/authorizeAll.
  *
  * @param viewResolutions
  *   List of view resolution details
  * @param resolutionMap
  *   Map from view to its resolved base tables
  * @param tableAccesses
  *   List of per-table access decisions
  */
final case class TraceInfo(
    viewResolutions: List[ViewResolution],
    resolutionMap: Map[TableRef, Set[TableRef]],
    tableAccesses: List[TableAccess]
)

/** Result of an authorization check.
  *
  * Wraps an AccessResult with timing information and optional trace data.
  *
  * @param result
  *   The underlying access result with decision, tables, and details
  * @param timing
  *   Timing breakdown (parse vs evaluate/resolve)
  * @param trace
  *   Optional trace information when trace=true was requested
  */
final case class AuthorizationOutcome(
    result: AccessResult,
    timing: Timing,
    trace: Option[TraceInfo] = None
) {

  def isAllowed: Boolean = result.isAllowed

  def isDenied: Boolean = !result.isAllowed

  /** One-line summary of the authorization decision.
    *
    * Examples:
    *   - "ALLOWED: SELECT on mydb.public.orders (2 tables, 1.23ms)"
    *   - "DENIED: 1 of 3 tables denied (NoMatchingGrant on mydb.public.secret)"
    */
  def summary: String = {
    val tableCount = result.tableAccesses.size
    val timeStr = f"${timing.totalMs}%.2fms"

    result.decision match {
      case Decision.Allowed =>
        val tableDesc = result.tableAccesses.headOption
          .map(_.table.canonical)
          .getOrElse("(no tables)")
        s"ALLOWED: SELECT on $tableDesc ($tableCount tables, $timeStr)"

      case Decision.Denied =>
        val deniedCount = result.deniedTables.size
        val deniedAccesses = result.tableAccesses.filter(_.decision == Decision.Denied)
        val reasonDesc = deniedAccesses.flatMap(ta => ta.denyReason.map(r => formatDenyReason(r))) match {
          case Nil    => "unknown reason"
          case reasons => reasons.mkString(", ")
        }
        s"DENIED: $deniedCount of $tableCount tables denied ($reasonDesc)"
    }
  }

  /** Multi-line detailed explanation of the authorization.
    *
    * Includes SQL, user, each table access with decision/reason, and timing breakdown.
    */
  def explain: String = {
    val sb = new StringBuilder
    sb.append(s"=== Authorization Result ===\n")
    sb.append(s"Decision: ${if isAllowed then "ALLOWED" else "DENIED"}\n")
    sb.append(s"SQL: ${result.sql}\n")
    sb.append(s"User: ${result.user.name} (groups: ${result.user.groups.mkString(", ")})\n")
    sb.append(s"Timestamp: ${result.timestamp}\n\n")

    sb.append("--- Table Accesses ---\n")
    result.tableAccesses.foreach { ta =>
      val decisionStr = if ta.decision == Decision.Allowed then "[ALLOWED]" else "[DENIED]"
      val viewStr = if ta.isView then " (view)" else ""
      val grantTypeStr = ta.grantType.map(g => s" [$g]").getOrElse("")
      sb.append(s"  $decisionStr ${ta.table.canonical}$viewStr$grantTypeStr\n")
      ta.denyReason.foreach { r =>
        sb.append(s"    Reason: ${formatDenyReasonDetail(r)}\n")
      }
      ta.warnings.foreach { w =>
        sb.append(s"    Warning: $w\n")
      }
    }

    if result.viewResolutions.nonEmpty then
      sb.append("\n--- View Resolutions ---\n")
      result.viewResolutions.foreach { vr =>
        sb.append(s"  ${vr.view.canonical} -> ${vr.underlyingTables.map(_.canonical).mkString(", ")}\n")
      }

    sb.append(s"\n--- Timing ---\n")
    sb.append(f"  Parse: ${timing.parseMs}%.3fms\n")
    sb.append(f"  Evaluate/Resolve: ${timing.evaluateAndResolveMs}%.3fms\n")
    sb.append(f"  Total: ${timing.totalMs}%.3fms\n")

    sb.toString()
  }

  /** JSON representation of the authorization outcome. */
  def toJson: Json = this.asJson(using AuthorizationOutcome.authorizationOutcomeEncoder)

  private def formatDenyReason(r: model.DenyReason): String = r match {
    case model.DenyReason.NoMatchingGrant(t, _)   => s"NoMatchingGrant on ${t.canonical}"
    case model.DenyReason.UnknownView(v)          => s"UnknownView: ${v.canonical}"
    case model.DenyReason.ViewResolutionCycle(c)  => s"ViewResolutionCycle: ${c.map(_.canonical).mkString(" -> ")}"
    case model.DenyReason.ViewParseError(v, msg)  => s"ViewParseError on ${v.canonical}"
    case model.DenyReason.CallbackError(t, _)     => s"CallbackError on ${t.canonical}"
    case model.DenyReason.UnqualifiedTable(n, m)  => s"UnqualifiedTable: $n (missing $m)"
    case model.DenyReason.UnsupportedStatement(t) => s"UnsupportedStatement: $t"
    case model.DenyReason.ParseError(msg)         => s"ParseError"

    case model.DenyReason.ExpiredGrant(t, _, exp) => s"ExpiredGrant on ${t.canonical} (expired at $exp)"
    case model.DenyReason.MaxViewDepthExceeded(p) => s"MaxViewDepthExceeded: ${p.map(_.canonical).mkString(" -> ")}"
  }

  private def formatDenyReasonDetail(r: model.DenyReason): String = r match {
    case model.DenyReason.NoMatchingGrant(t, u)   => s"No matching grant for ${u.name} on ${t.canonical}"
    case model.DenyReason.UnknownView(v)          => s"Unknown view: ${v.canonical}"
    case model.DenyReason.ViewResolutionCycle(c)  => s"View resolution cycle: ${c.map(_.canonical).mkString(" -> ")}"
    case model.DenyReason.ViewParseError(v, msg)  => s"View parse error on ${v.canonical}: $msg"
    case model.DenyReason.CallbackError(t, msg)   => s"Callback error on ${t.canonical}: $msg"
    case model.DenyReason.UnqualifiedTable(n, m)  => s"Unqualified table '$n': missing $m"
    case model.DenyReason.UnsupportedStatement(t) => s"Unsupported statement type: $t"
    case model.DenyReason.ParseError(msg)         => s"Parse error: $msg"

    case model.DenyReason.ExpiredGrant(t, u, exp) => s"Expired grant for ${u.name} on ${t.canonical}: expired at $exp"
    case model.DenyReason.MaxViewDepthExceeded(p) => s"Max view depth exceeded: ${p.map(_.canonical).mkString(" -> ")}"
  }
}

object AuthorizationOutcome {

  given timingEncoder: Encoder[Timing] = Encoder.instance { t =>
    Json.obj(
      "parseMs"              -> t.parseMs.asJson,
      "evaluateAndResolveMs" -> t.evaluateAndResolveMs.asJson,
      "totalMs"              -> t.totalMs.asJson
    )
  }

  private given resolutionMapEntryEncoder: Encoder[(TableRef, Set[TableRef])] = Encoder.instance { case (view, baseTables) =>
    Json.obj(
      "view"       -> view.asJson,
      "baseTables" -> baseTables.toList.asJson
    )
  }

  given traceInfoEncoder: Encoder[TraceInfo] = Encoder.instance { ti =>
    Json.obj(
      "viewResolutions" -> ti.viewResolutions.asJson,
      "resolutionMap"   -> ti.resolutionMap.toList.asJson,
      "tableAccesses"   -> ti.tableAccesses.asJson
    )
  }

  given authorizationOutcomeEncoder: Encoder[AuthorizationOutcome] = Encoder.instance { ao =>
    val base = Json.obj(
      "decision" -> (if ao.isAllowed then "allowed" else "denied").asJson,
      "sql"      -> ao.result.sql.asJson,
      "user" -> Json.obj(
        "name"   -> ao.result.user.name.asJson,
        "groups" -> ao.result.user.groups.toList.asJson
      ),
      "timing" -> ao.timing.asJson,
      "result" -> ao.result.asJson(using AccessResult.accessResultEncoder)
    )
    ao.trace match {
      case Some(t) => base.deepMerge(Json.obj("trace" -> t.asJson))
      case None    => base
    }
  }
}
