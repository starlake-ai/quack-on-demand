package ai.starlake.quack.edge.authz

import io.circe.{Decoder, HCursor}
import io.circe.parser.parse

import java.nio.charset.StandardCharsets

/** One object a bound plan touches (description v1, `objects[]`). For `kind = schema`, `schema` is
  * the schema's name and `name` is empty. `via`: the innermost secure view (`catalog.schema.name`)
  * through which a read reaches it; `columns`: the columns read, sorted. A DuckLake read at any
  * snapshot is described as the table its stable id names now, under its current names (node
  * protocol 6); `renamed` (on the wire only when true) when the statement named the table, or a
  * column read, by a name it had at that snapshot and has no longer.
  */
final case class DescribedObject(
    kind: String,
    access: String,
    catalog: String,
    catalogType: String,
    schema: String,
    name: String,
    columns: List[String],
    via: Option[String],
    renamed: Boolean = false
):
  def qualified: String =
    if kind == "schema" then s"$catalog.$schema" else s"$catalog.$schema.$name"

/** One function a bound plan calls (`functions[]`). `args`: for a file reader (a `File` function,
  * the names of native/qod-node/file-functions.txt) its resolved locations, None for a NULL; empty
  * for every other function, so no credential a statement names (a connection string, a secret in a
  * literal) leaves the node (CONTRACT Addendum 4 item 6). `via`: as for objects (absent on the wire
  * reads as None).
  */
final case class DescribedFunction(
    catalog: String,
    schema: String,
    name: String,
    tpe: String,
    args: List[Option[String]],
    via: Option[String]
)

/** One setting a statement changes (`settings[]`); `value` None for a RESET. */
final case class DescribedSetting(name: String, scope: String, value: Option[String])

/** One control step (`control[]`): ATTACH, COPY TO, a transaction verb, an extension operator. */
final case class DescribedControl(op: String, target: Option[String], tpe: Option[String])

/** What qod-node resolved for one bound plan (description v1, `kind = plan`). */
final case class Description(
    statement: String,
    objects: List[DescribedObject],
    functions: List[DescribedFunction],
    settings: List[DescribedSetting],
    control: List[DescribedControl]
)

/** One location a statement names before it binds (`kind = screen`). `written` is the wire's
  * `given` (a Scala keyword): the location as the statement spelled it.
  */
final case class DescribedLocation(access: String, written: String, resolved: String, via: String)

/** What qod-node's parse screen extracted from one statement (screen description v1). */
final case class ScreenDescription(locations: List[DescribedLocation])

object Description:

  given Decoder[DescribedObject] = Decoder.instance { c =>
    for
      kind        <- c.get[String]("kind")
      access      <- c.get[String]("access")
      catalog     <- c.get[String]("catalog")
      catalogType <- c.get[String]("catalogType")
      schema      <- c.get[String]("schema")
      name        <- c.get[String]("name")
      columns     <- c.get[List[String]]("columns")
      via         <- c.get[Option[String]]("via")
      renamed     <- c.getOrElse[Boolean]("renamed")(false)
    yield DescribedObject(kind, access, catalog, catalogType, schema, name, columns, via, renamed)
  }

  given Decoder[DescribedFunction] = Decoder.instance { c =>
    for
      catalog <- c.get[String]("catalog")
      schema  <- c.get[String]("schema")
      name    <- c.get[String]("name")
      tpe     <- c.get[String]("type")
      args    <- c.get[List[Option[String]]]("args")
      via     <- c.get[Option[String]]("via")
    yield DescribedFunction(catalog, schema, name, tpe, args, via)
  }

  given Decoder[DescribedSetting] = Decoder.instance { c =>
    for
      name  <- c.get[String]("name")
      scope <- c.get[String]("scope")
      value <- c.get[Option[String]]("value")
    yield DescribedSetting(name, scope, value)
  }

  given Decoder[DescribedControl] = Decoder.instance { c =>
    for
      op     <- c.get[String]("op")
      target <- c.get[Option[String]]("target")
      tpe    <- c.get[Option[String]]("type")
    yield DescribedControl(op, target, tpe)
  }

  given Decoder[Description] = Decoder.instance { c =>
    for
      statement <- c.get[String]("statement")
      objects   <- c.get[List[DescribedObject]]("objects")
      functions <- c.get[List[DescribedFunction]]("functions")
      settings  <- c.get[List[DescribedSetting]]("settings")
      control   <- c.get[List[DescribedControl]]("control")
    yield Description(statement, objects, functions, settings, control)
  }

  /** The node's canonical description bytes as a description v1; Left names what is malformed. */
  def decode(bytes: Array[Byte]): Either[String, Description] = Versioned.decode[Description](bytes)

object ScreenDescription:

  given Decoder[DescribedLocation] = Decoder.instance { c =>
    for
      access   <- c.get[String]("access")
      written  <- c.get[String]("given")
      resolved <- c.get[String]("resolved")
      via      <- c.get[String]("via")
    yield DescribedLocation(access, written, resolved, via)
  }

  given Decoder[ScreenDescription] =
    Decoder.instance(_.get[List[DescribedLocation]]("locations").map(ScreenDescription(_)))

  /** The node's canonical screen description bytes; Left names what is malformed. */
  def decode(bytes: Array[Byte]): Either[String, ScreenDescription] =
    Versioned.decode[ScreenDescription](bytes)

/** A JSON object with `"v": 1`, decoded as `A`. */
private object Versioned:

  def decode[A: Decoder](bytes: Array[Byte]): Either[String, A] =
    parse(new String(bytes, StandardCharsets.UTF_8)) match
      case Left(_)     => Left("not JSON")
      case Right(json) =>
        val c: HCursor = json.hcursor
        c.get[Int]("v") match
          case Right(1)     => json.as[A].left.map(f => f.getMessage)
          case Right(other) => Left(s"unsupported version $other")
          case Left(_)      => Left("no version")
