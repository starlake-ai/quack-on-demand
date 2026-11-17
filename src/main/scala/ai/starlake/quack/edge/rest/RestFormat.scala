package ai.starlake.quack.edge.rest

import scala.util.Try

/** The response formats the edge serves. Asking for any other (Arrow IPC, Parquet) is a 406, not a
  * silent JSON.
  */
enum RestFormat(val contentType: String):
  case Json extends RestFormat("application/json")
  case Csv  extends RestFormat("text/csv; charset=utf-8")

/** Format negotiation: the `format` parameter first, then `Accept`, then JSON.
  *
  * Once `format` is given, `Accept` is not consulted at all, so a client (or a low-code tool that
  * sends a fixed `Accept`) can always get CSV by URL alone. An `Accept` that names only types the
  * edge cannot serve is a 406 rather than a JSON body the client did not ask for; a missing or
  * blank `Accept` means "anything", as HTTP says.
  */
object RestFormat:

  private def byName(name: String): Option[RestFormat] = RestResolver.asciiLower(name) match
    case "json" => Some(Json)
    case "csv"  => Some(Csv)
    case _      => None

  /** Media range -> format. Wildcards resolve to JSON, the default, except the text wildcard. */
  private def byMediaRange(range: String): Option[RestFormat] = range match
    case "application/json" | "application/*" | "*/*" => Some(Json)
    case "text/csv" | "text/*"                        => Some(Csv)
    case _                                            => None

  def negotiate(format: Option[String], accept: Option[String]): Either[RestError, RestFormat] =
    format match
      case Some(f) => byName(f).toRight(RestError.UnsupportedFormat)
      case None    =>
        accept.map(_.trim).filter(_.nonEmpty) match
          case None    => Right(Json)
          case Some(a) => fromAccept(a).toRight(RestError.UnsupportedFormat)

  /** The supported range with the highest q-value (the first on a tie); `q=0` excludes. */
  private def fromAccept(accept: String): Option[RestFormat] =
    val ranked = accept.split(",").toList.zipWithIndex.flatMap { case (part, idx) =>
      val pieces = part.split(";").map(_.trim)
      val range  = RestResolver.asciiLower(pieces.head)
      val q      = pieces.tail
        .collectFirst {
          case p if RestResolver.asciiLower(p).startsWith("q=") =>
            Try(p.drop(2).trim.toDouble).getOrElse(0.0)
        }
        .getOrElse(1.0)
      byMediaRange(range).filter(_ => q > 0).map(f => (f, q, idx))
    }
    ranked.sortBy { case (_, q, idx) => (-q, idx) }.headOption.map(_._1)
