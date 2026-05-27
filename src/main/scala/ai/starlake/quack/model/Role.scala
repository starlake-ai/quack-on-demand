package ai.starlake.quack.model

enum Role:
  case ReadOnly, WriteOnly, Dual

object Role:
  def parse(s: String): Either[String, Role] = s.toUpperCase match
    case "READONLY"  => Right(ReadOnly)
    case "WRITEONLY" => Right(WriteOnly)
    case "DUAL"      => Right(Dual)
    case other       => Left(s"unknown role: $other")