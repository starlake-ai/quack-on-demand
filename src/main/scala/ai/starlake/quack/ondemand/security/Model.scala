package ai.starlake.quack.ondemand.security

import cats.effect.IO

/** How much a finding matters. `rank` orders a report: critical first, na last. */
enum Status(val wire: String, val rank: Int):
  case Critical extends Status("critical", 0)
  case Warn     extends Status("warn", 1)
  case Error    extends Status("error", 2)
  case Ok       extends Status("ok", 3)
  case Na       extends Status("na", 4)

enum Area(val wire: String):
  case Network  extends Area("network")
  case Secrets  extends Area("secrets")
  case Identity extends Area("identity")
  case Data     extends Area("data")
  case Fleet    extends Area("fleet")

/** What one check concluded. `detail` and `fix` name settings, counts and object names only, never
  * a value of a secret.
  */
final case class Outcome(status: Status, detail: String, fix: String = "")

/** One check of the audit. `docsPath` is relative to [[SecurityAudit.DocsBase]]. */
final case class SecurityCheck(
    id: String,
    area: Area,
    title: String,
    settings: List[String],
    docsPath: String,
    run: AuditContext => IO[Outcome]
)

final case class Finding(
    id: String,
    area: Area,
    status: Status,
    title: String,
    detail: String,
    fix: String,
    settings: List[String],
    docs: String
)

final case class AuditSummary(critical: Int, warn: Int, ok: Int, na: Int, error: Int)

final case class AuditReport(
    generatedAt: java.time.Instant,
    replica: String,
    version: String,
    summary: AuditSummary,
    findings: List[Finding]
)
