package ai.starlake.quack.ondemand.security.checks

import ai.starlake.quack.ondemand.security.*
import cats.effect.IO

object IdentityChecks:

  private val lockoutOff = SecurityCheck(
    "id.lockout-off",
    Area.Identity,
    "Account lockout is off",
    List("QOD_AUTH_LOCKOUT_ENABLED", "QOD_SMTP_HOST"),
    "/operating/auth-providers#secure-in-production",
    ctx =>
      IO.pure(
        if ctx.bool("quack-on-demand.auth.lockout.enabled") then
          Outcome(Status.Ok, "Account lockout is on.")
        else
          Outcome(
            Status.Warn,
            "Account lockout is off: a password can be guessed without limit on the console and the data plane.",
            "Set QOD_SMTP_HOST, then QOD_AUTH_LOCKOUT_ENABLED=true (boot refuses lockout without SMTP)."
          )
      )
  )

  private val resetLinkRelative = SecurityCheck(
    "id.reset-link-relative",
    Area.Identity,
    "Password-reset links are host-relative",
    List("QOD_PUBLIC_BASE_URL"),
    "/operating/auth-providers#secure-in-production",
    ctx =>
      IO.pure(
        if ctx.string("quack-on-demand.smtp.host").isEmpty then
          Outcome(Status.Na, "No SMTP relay: no mail is sent.")
        else if ctx.string("quack-on-demand.publicBaseUrl").nonEmpty then
          Outcome(Status.Ok, "Reset links use QOD_PUBLIC_BASE_URL.")
        else
          Outcome(
            Status.Warn,
            "QOD_SMTP_HOST is set and QOD_PUBLIC_BASE_URL is empty: mailed reset links carry no host.",
            "Set QOD_PUBLIC_BASE_URL to the console's public https origin."
          )
      )
  )

  private val ssoRedirectDefault = SecurityCheck(
    "id.sso-redirect-default",
    Area.Identity,
    "SSO redirect URI falls back to localhost",
    List("QOD_MGMT_PUBLIC_BASE_URL"),
    "/operating/authentication#secure-in-production",
    ctx =>
      IO.blocking(ctx.facts.oidcTenants()).map { tenants =>
        if tenants.isEmpty then Outcome(Status.Na, "No tenant signs in through OIDC.")
        else if ctx.string("quack-on-demand.auth.management.publicBaseUrl").nonEmpty then
          Outcome(Status.Ok, "QOD_MGMT_PUBLIC_BASE_URL is set.")
        else
          Outcome(
            Status.Warn,
            s"Tenants signing in through OIDC (${tenants.mkString(", ")}) get a http://localhost redirect URI.",
            "Set QOD_MGMT_PUBLIC_BASE_URL to the console's public https origin and register it with each identity provider."
          )
      }
  )

  private val permissiveBuiltins = SecurityCheck(
    "id.permissive-builtins",
    Area.Identity,
    "Users hold only the full-access built-ins",
    Nil,
    "/operating/rbac-model#secure-in-production",
    ctx =>
      IO.blocking(ctx.facts.permissiveBuiltinUsers()).map { counts =>
        if counts.isEmpty then
          Outcome(Status.Ok, "No user relies on the full-access built-ins alone.")
        else
          Outcome(
            Status.Warn,
            "Users whose only role is qod_all_tables and only group qod_all_pools (every table of every pool): " +
              counts.map((t, n) => s"$n in $t").mkString(", ") + ".",
            "Give these users roles and groups of their own (qod user create --role/--group, qod membership)."
          )
      }
  )

  private val disabledPats = SecurityCheck(
    "id.disabled-superuser-pats",
    Area.Identity,
    "Disabled accounts still own live tokens",
    Nil,
    "/operating/rbac-admin#secure-in-production",
    ctx =>
      IO.blocking(ctx.facts.dormantPatsOfDisabledUsers()).map { n =>
        if n == 0 then Outcome(Status.Ok, "No disabled account owns a live token.")
        else
          Outcome(
            Status.Warn,
            s"$n unrevoked, unexpired personal access tokens belong to disabled accounts: they work again if an account is re-enabled.",
            "Revoke them (qod auth pat list, qod auth pat revoke --id) or delete the accounts."
          )
      }
  )

  val all: List[SecurityCheck] =
    List(lockoutOff, resetLinkRelative, ssoRedirectDefault, permissiveBuiltins, disabledPats)
