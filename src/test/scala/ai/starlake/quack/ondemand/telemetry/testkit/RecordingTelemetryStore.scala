package ai.starlake.quack.ondemand.telemetry.testkit

import ai.starlake.quack.ondemand.telemetry.{AuditEvent, AuditQuery, AuditRow, TelemetryStore}

import java.time.Instant

/** In-memory [[TelemetryStore]] for use in unit tests. Collects every appended event into a flat
  * mutable list. Set `failNext = true` to make the next `appendAudit` call throw a
  * RuntimeException (simulates a storage failure) and then auto-reset to false.
  */
class RecordingTelemetryStore extends TelemetryStore:
  val enabled                                                   = true
  val events: scala.collection.mutable.ListBuffer[AuditEvent]  =
    scala.collection.mutable.ListBuffer.empty
  var failNext: Boolean                                         = false
  def appendAudit(es: List[AuditEvent]): Unit =
    if failNext then
      failNext = false
      throw new RuntimeException("store down")
    events ++= es
  def listAudit(q: AuditQuery): List[AuditRow] = Nil
  def purgeAudit(olderThan: Instant): Int      = 0
