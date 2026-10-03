package ai.starlake.quack.edge.rest

import scala.util.Try

/** The response formats the edge serves. JSON and CSV are encoded in full before the answer goes
  * out (the row cap bounds them); Arrow IPC and Parquet are `streamed`: written as the node
  * delivers its batches. Asking for any other format is a 406, not a silent JSON.
  */
enum RestFormat(val contentType: String, val streamed: Boolean):
  case Json    extends RestFormat("application/json", streamed = false)
  case Csv     extends RestFormat("text/csv; charset=utf-8", streamed = false)
  case Arrow   extends RestFormat("application/vnd.apache.arrow.stream", streamed = true)
  case Parquet extends RestFormat("application/vnd.apache.parquet", streamed = true)

/** Format negotiation: the `format` parameter first, then `Accept`, then JSON.
  *
  * Once `format` is given, `Accept` is not consulted at all, so a client (or a low-code tool that
  * sends a fixed `Accept`) can always get CSV by URL alone. An `Accept` that names only types the
  * route cannot serve is a 406 rather than a JSON body the client did not ask for; a missing or
  * blank `Accept` means "anything", as HTTP says. Each route says which formats it serves: the
  * listings and the table detail are small documents, JSON or CSV only; `/rows` adds Arrow and,
  * where the host can stream it, Parquet.
  */
object RestFormat:

  /** What the listings and the table detail serve. */
  val Documents: Set[RestFormat] = Set(Json, Csv)

  /** What `/rows` serves where Parquet is available ([[RestParquetWriter.available]]). */
  val Rows: Set[RestFormat] = Set(Json, Csv, Arrow, Parquet)

  private def byName(name: String): Option[RestFormat] = RestResolver.asciiLower(name) match
    case "json"    => Some(Json)
    case "csv"     => Some(Csv)
    case "arrow"   => Some(Arrow)
    case "parquet" => Some(Parquet)
    case _         => None

  /** Media range -> format. Wildcards resolve to JSON, the default, except the text wildcard. */
  private def byMediaRange(range: String): Option[RestFormat] = range match
    case "application/json" | "application/*" | "*/*" => Some(Json)
    case "text/csv" | "text/*"                        => Some(Csv)
    case "application/vnd.apache.arrow.stream"        => Some(Arrow)
    case "application/vnd.apache.parquet"             => Some(Parquet)
    case _                                            => None

  def negotiate(
      format: Option[String],
      accept: Option[String],
      allowed: Set[RestFormat] = Documents
  ): Either[RestError, RestFormat] =
    format match
      case Some(f) => byName(f).filter(allowed).toRight(RestError.UnsupportedFormat)
      case None    =>
        accept.map(_.trim).filter(_.nonEmpty) match
          case None    => Right(Json)
          case Some(a) => fromAccept(a, allowed).toRight(RestError.UnsupportedFormat)

  /** The served range with the highest q-value (the first on a tie); `q=0` excludes. */
  private def fromAccept(accept: String, allowed: Set[RestFormat]): Option[RestFormat] =
    val ranked = accept.split(",").toList.zipWithIndex.flatMap { case (part, idx) =>
      val pieces = part.split(";").map(_.trim)
      val range  = RestResolver.asciiLower(pieces.head)
      val q      = pieces.tail
        .collectFirst {
          case p if RestResolver.asciiLower(p).startsWith("q=") =>
            Try(p.drop(2).trim.toDouble).getOrElse(0.0)
        }
        .getOrElse(1.0)
      byMediaRange(range).filter(allowed).filter(_ => q > 0).map(f => (f, q, idx))
    }
    ranked.sortBy { case (_, q, idx) => (-q, idx) }.headOption.map(_._1)
