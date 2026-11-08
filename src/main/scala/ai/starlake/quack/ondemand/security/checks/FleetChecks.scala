package ai.starlake.quack.ondemand.security.checks

import ai.starlake.quack.RuntimeType
import ai.starlake.quack.ondemand.fleet.Cidr
import ai.starlake.quack.ondemand.security.*
import cats.effect.IO

object FleetChecks:

  private def onFleet(ctx: AuditContext): Boolean =
    RuntimeType.isFleet(ctx.string("quack-on-demand.runtimeType"))
  private val NotFleet = Outcome(Status.Na, "The manager does not run the fleet runtime.")

  private val autoApproveOpen = SecurityCheck(
    "fleet.auto-approve-open",
    Area.Fleet,
    "Fleet servers are approved from any address",
    List("QOD_FLEET_AUTO_APPROVE"),
    "/operating/deploy-fleet#join-approval",
    ctx =>
      IO.delay(
        if !onFleet(ctx) then NotFleet
        else
          // The manager's own rule (FleetConfig.openAutoApproveWarning): any /0 entry. A list that
          // does not parse throws, and the runner reports it as an error finding.
          val open = Cidr
            .parseList(ctx.string("quack-on-demand.fleet.autoApprove"))
            .fold(e => throw new IllegalArgumentException(e), identity)
            .filter(_.isEverything)
          if open.isEmpty then Outcome(Status.Ok, "Auto-approval is limited to listed networks.")
          else
            Outcome(
              Status.Critical,
              s"QOD_FLEET_AUTO_APPROVE contains ${open.map(_.toString).mkString(" and ")}, so any host holding the join token receives node credentials.",
              "Set QOD_FLEET_AUTO_APPROVE to the servers' private CIDRs, or to empty and approve each server with `qod fleet approve`."
            )
      )
  )

  private val unbound = SecurityCheck(
    "fleet.unbound-approvals",
    Area.Fleet,
    "Approved fleet servers are not bound to an address",
    Nil,
    "/operating/deploy-fleet#approval-is-bound-to-the-address-it-was-given-to",
    ctx =>
      if !onFleet(ctx) then IO.pure(NotFleet)
      else
        IO.blocking(ctx.facts.unboundApprovedServers()).map { names =>
          if names.isEmpty then
            Outcome(Status.Ok, "Every approved server is bound to its source address.")
          else
            Outcome(
              Status.Warn,
              s"Approved servers with no bound source address: ${names.mkString(", ")}.",
              "Drain each one, approve it again once it shows as pending, then undrain it."
            )
        }
  )

  val all: List[SecurityCheck] = List(autoApproveOpen, unbound)
