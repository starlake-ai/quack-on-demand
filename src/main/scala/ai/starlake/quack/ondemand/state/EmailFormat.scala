package ai.starlake.quack.ondemand.state

/** The single definition of "looks like an email", used by the user-write rule ([[EmailPolicy]])
  * and mirrored as a Postgres POSIX regex in the `0031-user-email-from-username` backfill (keep the
  * two patterns in sync).
  */
object EmailFormat:
  // local@domain.tld -- one @, non-empty parts, a dotted domain, no whitespace.
  // Pragmatic, NOT full RFC 5322. Mirror: ^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$
  private val Pattern = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$".r

  def matches(s: String): Boolean =
    Pattern.matches(s.trim)

/** The rule: an email-format username IS its own email and cannot carry a different one. */
object EmailPolicy:
  /** Resolve the email to persist for `(username, suppliedEmail)`.
    *   - username email-format: supplied None/empty/equal -> Right(Some(username)); a different
    *     non-empty value -> Left(error message).
    *   - username not email-format: supplied value passes through unchanged.
    */
  def resolve(username: String, supplied: Option[String]): Either[String, Option[String]] =
    if EmailFormat.matches(username) then
      val u = username.trim
      supplied.map(_.trim).filter(_.nonEmpty) match
        case None              => Right(Some(u))
        case Some(e) if e == u => Right(Some(u))
        case Some(_)           =>
          Left("email is derived from an email-format username and cannot be set separately")
    else Right(supplied)
