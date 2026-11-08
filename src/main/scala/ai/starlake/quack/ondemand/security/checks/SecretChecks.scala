package ai.starlake.quack.ondemand.security.checks

import ai.starlake.quack.ondemand.security.*
import cats.effect.IO

object SecretChecks:

  /** The control-plane password `application.conf` ships with. */
  val ShippedPgPassword = "azizam"

  private val pgDefaultPassword = SecurityCheck(
    "secret.pg-default-password",
    Area.Secrets,
    "Postgres password is the shipped default",
    List("QOD_PG_PASSWORD", "QOD_AUTH_DB_PASSWORD"),
    "/operating/deploy-single-server#secure-in-production",
    ctx =>
      IO.pure {
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
    List("QOD_PG_USER"),
    "/operating/tenants-databases#secure-in-production",
    ctx =>
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
