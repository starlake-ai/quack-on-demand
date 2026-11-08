package ai.starlake.quack.ondemand.security.checks

import ai.starlake.quack.ondemand.security.*
import cats.effect.IO
import java.io.FileInputStream
import java.security.cert.{CertificateFactory, X509Certificate}
import scala.util.Using

object NetworkChecks:

  private val flightOff = SecurityCheck(
    "tls.flight-off",
    Area.Network,
    "FlightSQL edge accepts plain-text connections",
    List("PROXY_TLS_ENABLED"),
    "/operating/tls#secure-in-production",
    ctx =>
      IO.pure(
        if ctx.bool("quack-flightsql.tlsEnabled") then
          Outcome(Status.Ok, "TLS is on for the FlightSQL edge.")
        else
          Outcome(
            Status.Critical,
            "PROXY_TLS_ENABLED is false: passwords and query results cross the network in clear text.",
            "Set PROXY_TLS_ENABLED=true with a CA-signed certificate (PROXY_TLS_CERT_CHAIN, PROXY_TLS_PRIVATE_KEY)."
          )
      )
  )

  private val flightSelfSigned = SecurityCheck(
    "tls.flight-self-signed",
    Area.Network,
    "FlightSQL certificate is self-signed",
    List("PROXY_TLS_CERT_CHAIN", "PROXY_TLS_PRIVATE_KEY"),
    "/operating/tls#use-a-ca-signed-certificate",
    ctx =>
      if !ctx.bool("quack-flightsql.tlsEnabled") then
        IO.pure(Outcome(Status.Na, "TLS is off (see tls.flight-off)."))
      else
        IO.blocking {
          val path = ctx.string("quack-flightsql.tlsCertChain")
          val cert = Using.resource(new FileInputStream(path))(in =>
            CertificateFactory
              .getInstance("X.509")
              .generateCertificate(in)
              .asInstanceOf[X509Certificate]
          )
          if cert.getSubjectX500Principal == cert.getIssuerX500Principal then
            Outcome(
              Status.Warn,
              "The certificate is self-signed: clients either skip verification or must import it by hand.",
              "Point PROXY_TLS_CERT_CHAIN and PROXY_TLS_PRIVATE_KEY at a CA-signed pair whose name matches the address clients dial."
            )
          else Outcome(Status.Ok, "The certificate is issued by a certificate authority.")
        }
  )

  private val quackOffExposed = SecurityCheck(
    "tls.quack-off-exposed",
    Area.Network,
    "Native Quack front door is plain HTTP on a reachable address",
    List("QOD_QUACK_TLS_ENABLED", "QOD_QUACK_HOST"),
    "/operating/tls#the-native-quack-front-door",
    ctx =>
      IO.pure {
        val host = ctx.string("quack-native.host")
        if !ctx.bool("quack-native.enabled") then
          Outcome(Status.Na, "The native front door is disabled.")
        else if ctx.bool("quack-native.tlsEnabled") then
          Outcome(Status.Ok, "TLS is on for the native front door.")
        else if AuditContext.isLoopback(host) then
          Outcome(Status.Ok, s"TLS is off, but the front door binds the loopback address $host.")
        else
          Outcome(
            Status.Critical,
            s"The native front door binds $host with TLS off: DuckDB clients send their token in clear text.",
            "Set QOD_QUACK_TLS_ENABLED=true, or QOD_QUACK_HOST=127.0.0.1 behind a TLS proxy."
          )
      }
  )

  private val cookieInsecure = SecurityCheck(
    "net.cookie-insecure",
    Area.Network,
    "Console session cookie may travel over plain HTTP",
    List("QOD_SESSION_COOKIE_SECURE"),
    "/operating/authentication#secure-in-production",
    ctx =>
      IO.pure(
        ctx
          .string("quack-on-demand.auth.management.sessionCookieSecure")
          .toLowerCase(java.util.Locale.ROOT) match
          case "false" =>
            Outcome(
              Status.Critical,
              "QOD_SESSION_COOKIE_SECURE is false: browsers send the session cookie over plain HTTP.",
              "Remove QOD_SESSION_COOKIE_SECURE (auto) behind a proxy that sends X-Forwarded-Proto, or set it to true."
            )
          case "true" => Outcome(Status.Ok, "The session cookie is always marked Secure.")
          case _      =>
            Outcome(Status.Ok, "auto: the cookie is marked Secure when X-Forwarded-Proto is https.")
      )
  )

  val all: List[SecurityCheck] = List(flightOff, flightSelfSigned, quackOffExposed, cookieInsecure)
