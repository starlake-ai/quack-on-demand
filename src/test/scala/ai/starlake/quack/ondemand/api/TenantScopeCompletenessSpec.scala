package ai.starlake.quack.ondemand.api

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.tapir.AnyEndpoint

/** Fail-safe guardrail for perimeter URL-tenant scoping.
  *
  * apiKeyGuard authorizes a URL-tenant route for cookie/session callers by resolving the tenant
  * from the path via TenantScopeGuard.extractTenant. If a new path-tenant route is added and
  * extractTenant is not extended to match it, the route silently fails open. This spec enumerates
  * every endpoint, and for each whose path template carries a `{tenant}` capture, asserts
  * extractTenant resolves it. A new uncovered path-tenant route fails here.
  */
class TenantScopeCompletenessSpec extends AnyFlatSpec with Matchers:

  private val Sentinel = "SENTINELTENANT"

  /** Public, zero-arg vals of `module` whose runtime return type is a tapir Endpoint. Reflection
    * (not a hand-maintained list) so a new endpoint is covered automatically. Private vals (base,
    * fedBase, apiKey) are excluded by getMethods; authToken is excluded by the Endpoint return-type
    * filter.
    */
  private def endpointsOf(module: AnyRef): List[(String, AnyEndpoint)] =
    module.getClass.getMethods.toList
      .filter(_.getParameterCount == 0)
      .filter(m => classOf[sttp.tapir.Endpoint[?, ?, ?, ?, ?]].isAssignableFrom(m.getReturnType))
      .map(m => m.getName -> m.invoke(module).asInstanceOf[AnyEndpoint])

  private val allEndpoints: List[(String, AnyEndpoint)] =
    endpointsOf(Endpoints) ++ endpointsOf(RbacEndpoints)

  private val CaptureRe = "\\{([^}]+)\\}".r

  /** Return only the path portion of a showPathTemplate result (everything before the first `?`).
    * Query captures (e.g. `?tenant={tenant}`) are handled by the `queryTenant` fallback in
    * extractTenant and need no per-route arm; this spec only guards PATH captures.
    */
  private def pathPart(template: String): String = template.takeWhile(_ != '?')

  /** Concrete path from a path template: the tenant capture becomes the sentinel, every other `{x}`
    * becomes a filler segment.
    */
  private def concretePath(template: String): String =
    CaptureRe.replaceAllIn(template, m => if m.group(1) == "tenant" then Sentinel else "x")

  "the endpoint reflection" should "find a non-trivial set of endpoints" in {
    // Guards against the reflection filter silently matching nothing (which
    // would make the real assertion below vacuously pass).
    allEndpoints.size should be > 30
    allEndpoints.map(_._1) should contain("poolStatus")
  }

  "TenantScopeGuard" should "extract the tenant from every path-tenant endpoint (none may fail open)" in {
    val failures: List[String] = allEndpoints.flatMap { case (name, ep) =>
      val template = ep.showPathTemplate()
      val pathTpl  = pathPart(template)
      val captures = CaptureRe.findAllMatchIn(pathTpl).map(_.group(1)).toList
      // A capture is "misnamed" if it looks like it should be the tenant
      // identifier (contains "tenant", case-insensitive) but is NOT named
      // "tenant". Exception: when the template already carries a proper
      // "tenant" capture, companion captures like "tenantDb" are legitimate
      // secondary dimensions and must NOT be flagged.
      val hasTenantCapture = captures.contains("tenant")
      val misnamed         =
        if hasTenantCapture then Nil
        else captures.filter(c => c != "tenant" && c.toLowerCase.contains("tenant"))
      if misnamed.nonEmpty then
        Some(
          s"$name [$template]: tenant path capture(s) ${misnamed.mkString(", ")} must be named exactly 'tenant'"
        )
      else if captures.contains("tenant") then
        val resolved = TenantScopeGuard.extractTenant(concretePath(pathTpl), None)
        if resolved.contains(Sentinel) then None
        else
          Some(
            s"$name [$template]: TenantScopeGuard.extractTenant did not resolve the tenant " +
              s"(got $resolved) -- this path-tenant route would fail open at the perimeter; " +
              "add its path shape to TenantScopeGuard.extractTenant"
          )
      else None
    }
    withClue(s"uncovered path-tenant routes:\n${failures.mkString("\n")}\n") {
      failures shouldBe empty
    }
  }
