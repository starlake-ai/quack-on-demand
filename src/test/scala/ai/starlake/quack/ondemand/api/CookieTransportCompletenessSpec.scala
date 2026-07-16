package ai.starlake.quack.ondemand.api

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.tapir.{AnyEndpoint, EndpointIO, EndpointInput}
import sttp.tapir.internal._

/** Fail-safe guardrail for the session-cookie transport.
  *
  * Secured endpoints must take their token via [[Endpoints.authToken]], which resolves the
  * `X-API-Key` header OR the `qod_session` cookie. An endpoint declaring a bare
  * `header[Option[String]]("X-API-Key")` input silently drops the cookie transport: browser
  * sessions reach its handler with `apiKey = None`, so per-request scope checks
  * ([[TenantScopeCheck]]) degrade to unauthenticated behavior for cookie callers. This spec
  * enumerates every endpoint and asserts that any endpoint reading the `X-API-Key` header also
  * reads the session cookie.
  */
class CookieTransportCompletenessSpec extends AnyFlatSpec with Matchers:

  /** Public, zero-arg vals of `module` whose runtime return type is a tapir Endpoint. Reflection
    * (not a hand-maintained list) so a new endpoint is covered automatically.
    */
  private def endpointsOf(module: AnyRef): List[(String, AnyEndpoint)] =
    module.getClass.getMethods.toList
      .filter(_.getParameterCount == 0)
      .filter(m => classOf[sttp.tapir.Endpoint[?, ?, ?, ?, ?]].isAssignableFrom(m.getReturnType))
      .map(m => m.getName -> m.invoke(module).asInstanceOf[AnyEndpoint])

  private val allEndpoints: List[(String, AnyEndpoint)] =
    EndpointModules.all.flatMap(endpointsOf)

  private def basicInputs(ep: AnyEndpoint) =
    ep.securityInput.asVectorOfBasicInputs() ++ ep.input.asVectorOfBasicInputs()

  private def readsApiKeyHeader(ep: AnyEndpoint): Boolean =
    basicInputs(ep).exists {
      case h: EndpointIO.Header[?] => h.name.equalsIgnoreCase("X-API-Key")
      case _                       => false
    }

  private def readsSessionCookie(ep: AnyEndpoint): Boolean =
    basicInputs(ep).exists {
      case c: EndpointInput.Cookie[?] => c.name == SessionTokenStore.CookieName
      case _                          => false
    }

  "the endpoint reflection" should "find a non-trivial set of endpoints" in {
    // Guards against the reflection filter silently matching nothing (which
    // would make the real assertion below vacuously pass).
    allEndpoints.size should be > 30
    allEndpoints.map(_._1) should contain("listRoles")
  }

  "every endpoint reading X-API-Key" should "also read the qod_session cookie" in {
    val offenders = allEndpoints.collect {
      case (name, ep) if readsApiKeyHeader(ep) && !readsSessionCookie(ep) =>
        s"$name [${ep.showPathTemplate()}]"
    }
    withClue(
      "endpoints taking a bare X-API-Key header (use Endpoints.authToken instead):\n" +
        s"${offenders.mkString("\n")}\n"
    ) {
      offenders shouldBe empty
    }
  }
