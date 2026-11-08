package ai.starlake.quack.ondemand.security.checks

import ai.starlake.quack.ondemand.security.*
import cats.effect.IO

object SecretChecks:

  /** `qod serve`'s embedded control plane or the demo's: trust authentication on loopback. */
  private def embedded(ctx: AuditContext): Boolean =
    ctx.bool(AuditConfig.TrustAuthControlPlane)

  /** The control-plane password `application.conf` ships with. */
  val ShippedPgPassword = "azizam"

  private val pgDefaultPassword = SecurityCheck(
    "secret.pg-default-password",
    Area.Secrets,
    "Control-plane Postgres login",
    List("QOD_PG_PASSWORD", "QOD_AUTH_DB_PASSWORD", "QOD_PG_EMBEDDED"),
    "/operating/tenants-databases#secure-in-production",
    ctx =>
      IO.pure {
        // The embedded server (`qod serve`, the demo) runs trust authentication on loopback: the
        // passwords the manager holds are placeholders the server never checks.
        if embedded(ctx) then
          Outcome(
            Status.Warn,
            "The embedded control-plane Postgres lets any local OS user connect as its superuser without a password (trust authentication on loopback).",
            "Run the manager against a Postgres of your own (QOD_PG_*) in production."
          )
        else
          val which = List(
            "QOD_PG_PASSWORD"      -> "quack-on-demand.defaultMetastore.pgPassword",
            "QOD_AUTH_DB_PASSWORD" -> "quack-flightsql.auth.database.password"
          ).collect { case (env, path) if ctx.string(path) == ShippedPgPassword => env }
          if which.isEmpty then Outcome(Status.Ok, "No Postgres password is the shipped default.")
          else
            Outcome(
              Status.Critical,
              s"${which.mkString(" and ")} still ${
                  if which.size == 1 then "holds" else "hold"
                } the password application.conf ships with.",
              "Give the control-plane login a password of its own and set it in the manager's environment."
            )
      }
  )

  private val pgSuperuser = SecurityCheck(
    "secret.pg-superuser",
    Area.Secrets,
    "Control-plane login is a Postgres superuser",
    List("QOD_PG_USER", "QOD_PG_EMBEDDED"),
    "/operating/tenants-databases#secure-in-production",
    ctx =>
      if embedded(ctx) then
        IO.pure(Outcome(Status.Na, "The manager runs the embedded control plane."))
      else
        IO.blocking(ctx.facts.controlPlaneLoginIsSuperuser()).map {
          case true =>
            Outcome(
              Status.Warn,
              "The control-plane login is a Postgres superuser: whoever reads it reaches every database on the server.",
              "Use a role with CREATEDB and no SUPERUSER for QOD_PG_USER."
            )
          case false => Outcome(Status.Ok, "The control-plane login is not a Postgres superuser.")
        }
  )

  private def storedSecret(
      id: String,
      title: String,
      env: String,
      path: String,
      docs: String,
      why: String
  ) =
    SecurityCheck(
      id,
      Area.Secrets,
      title,
      List(env),
      docs,
      ctx =>
        IO.pure(
          if ctx.string(path).nonEmpty then Outcome(Status.Ok, s"$env is configured.")
          else
            Outcome(
              Status.Warn,
              s"$env is unset, so the secret is stored in the control-plane database: $why",
              s"Pin $env from an external secret store, the same on every replica."
            )
        )
    )

  val all: List[SecurityCheck] = List(
    pgDefaultPassword,
    pgSuperuser,
    storedSecret(
      "secret.session-jwt-stored",
      "Session signing secret is stored in the control plane",
      "QOD_SESSION_JWT_SECRET",
      "quack-on-demand.auth.management.sessionJwtSecret",
      "/operating/authentication#secure-in-production",
      "whoever reads a control-plane backup can forge a superuser session."
    ),
    storedSecret(
      "secret.node-key-stored",
      "Node-key secret is stored in the control plane",
      "QOD_NODE_KEY_SECRET",
      "quack-on-demand.nodeAuthz.keySecret",
      "/operating/hardening#node-keys-and-the-decide-url",
      "whoever reads a control-plane backup can mint a session handle for any principal on any node."
    )
  )
