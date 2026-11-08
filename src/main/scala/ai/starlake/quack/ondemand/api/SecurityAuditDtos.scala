package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.security.{AuditReport, Finding}
import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

/** One finding of the security audit, as the console and the CLI read it. */
final case class SecurityFindingView(
    id: String,
    area: String,
    status: String,
    title: String,
    detail: String,
    fix: String,
    settings: List[String],
    docs: String
)

final case class SecurityAuditSummaryView(critical: Int, warn: Int, ok: Int, na: Int, error: Int)

/** The answer of `GET /api/security/audit`. Built from an [[AuditReport]] field by field, so it
  * carries what the checks wrote and nothing else.
  */
final case class SecurityAuditResponse(
    generatedAt: String,
    replica: String,
    version: String,
    summary: SecurityAuditSummaryView,
    findings: List[SecurityFindingView]
)

object SecurityAuditResponse:
  given Codec[SecurityFindingView]      = deriveCodec
  given Codec[SecurityAuditSummaryView] = deriveCodec
  given Codec[SecurityAuditResponse]    = deriveCodec

  def of(r: AuditReport): SecurityAuditResponse =
    SecurityAuditResponse(
      r.generatedAt.toString,
      r.replica,
      r.version,
      SecurityAuditSummaryView(
        r.summary.critical,
        r.summary.warn,
        r.summary.ok,
        r.summary.na,
        r.summary.error
      ),
      r.findings.map(view)
    )

  private def view(f: Finding) =
    SecurityFindingView(
      f.id,
      f.area.wire,
      f.status.wire,
      f.title,
      f.detail,
      f.fix,
      f.settings,
      f.docs
    )
