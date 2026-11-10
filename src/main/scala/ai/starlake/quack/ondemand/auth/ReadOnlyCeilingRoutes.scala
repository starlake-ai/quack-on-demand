package ai.starlake.quack.ondemand.auth

import org.http4s.Method

/** What a token with a ceiling of `RO` reaches on `/api`: every `GET`, and the few reads the API
  * exposes as `POST`. Every other method is refused, so a write route added later stays closed to
  * these tokens. A new `POST` that only reads has to be added here on purpose.
  */
object ReadOnlyCeilingRoutes:

  private val ReadsByPost: Set[List[String]] = Set(
    List("api", "auth", "pat", "list"),
    List("api", "profile", "s3", "list"),
    List("api", "tenant", "location", "list"),
    List("api", "user", "s3", "list")
  )

  /** `segments` must be the path as the router will see it: decoded, leading empty segments gone.
    */
  def admits(method: Method, segments: Seq[String]): Boolean =
    method == Method.GET || (method == Method.POST && ReadsByPost.contains(segments.toList))
