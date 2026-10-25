package ai.starlake.quack.ondemand.telemetry

/** Who a control-plane change is audited as. `Bearer` is a REST or MCP caller, attributed from its
  * credential exactly as [[AuditRecorder.rest]] attributes it; `System` is the manager acting on
  * its own behalf (boot-time provisioning for `qod serve`), recorded with actor and realm `system`
  * like the maintenance runner's rows.
  */
enum AuditActor:
  case Bearer(token: Option[String])
  case System

  /** One `control-plane` audit row for this actor. */
  def controlPlane(
      audit: AuditRecorder,
      action: String,
      outcome: String,
      tenant: Option[String] = None,
      target: Option[String] = None,
      detail: Map[String, String] = Map.empty
  ): Unit = this match
    case AuditActor.Bearer(token) =>
      audit.rest(token, "control-plane", action, outcome, tenant, target, detail)
    case AuditActor.System =>
      audit.restAs("system", "system", "control-plane", action, outcome, tenant, target, detail)
