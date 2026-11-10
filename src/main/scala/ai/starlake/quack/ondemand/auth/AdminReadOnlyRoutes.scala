package ai.starlake.quack.ondemand.auth

import org.http4s.Method

/** The only routes an `adminReadOnly` token reaches: the reads a monitoring agent needs. The list
  * denies by default, so a route added to the manager later stays closed to these tokens until
  * someone adds it here on purpose. Adding one is an authorization change.
  */
object AdminReadOnlyRoutes:

  private val Reads: Set[List[String]] = Set(
    List("api", "tenant", "list"),
    List("api", "database", "list"),
    List("api", "pool", "list"),
    List("api", "security", "audit"),
    List("api", "audit", "list"),
    List("api", "history", "statements"),
    List("api", "manifest", "export")
  )

  /** `segments` must be the path as the router will see it: decoded, leading empty segments gone.
    */
  def admits(method: Method, segments: Seq[String], endsWithSlash: Boolean): Boolean =
    method == Method.GET && !endsWithSlash && Reads.contains(segments.toList)
