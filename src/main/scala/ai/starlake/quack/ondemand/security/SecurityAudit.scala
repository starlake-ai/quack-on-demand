package ai.starlake.quack.ondemand.security

import cats.effect.IO
import cats.syntax.all.*
import com.typesafe.scalalogging.LazyLogging
import scala.concurrent.duration.*

/** Runs every check in parallel, each under `timeout`. A check that fails or times out becomes an
  * `error` finding with [[SecurityAudit.FailedDetail]]: its exception never reaches the answer,
  * since a message can quote a value. The cause goes to the log at ERROR with the check's id.
  */
final case class SecurityAudit(
    checks: List[SecurityCheck],
    ctx: AuditContext,
    replica: String,
    version: String,
    timeout: FiniteDuration = 5.seconds
) extends LazyLogging:

  def run(): IO[AuditReport] =
    for
      findings <- checks.parTraverse(runOne)
      now      <- IO.realTimeInstant
    yield
      val sorted       = findings.sortBy(f => (f.status.rank, f.id))
      def n(s: Status) = sorted.count(_.status == s)
      AuditReport(
        now,
        replica,
        version,
        AuditSummary(
          n(Status.Critical),
          n(Status.Warn),
          n(Status.Ok),
          n(Status.Na),
          n(Status.Error)
        ),
        sorted
      )

  private def runOne(c: SecurityCheck): IO[Finding] =
    IO.defer(c.run(ctx))
      .timeoutAndForget(timeout)
      .handleErrorWith { e =>
        IO(
          logger.error(s"security audit check ${c.id} could not complete: ${e.getClass.getName}", e)
        )
          .as(Outcome(Status.Error, SecurityAudit.FailedDetail))
      }
      .map(o =>
        Finding(
          c.id,
          c.area,
          o.status,
          c.title,
          o.detail,
          o.fix,
          c.settings,
          SecurityAudit.DocsBase + c.docsPath
        )
      )

object SecurityAudit:
  val DocsBase     = "https://docs.starlake.ai/qod"
  val FailedDetail = "the check could not complete; see the manager log"
