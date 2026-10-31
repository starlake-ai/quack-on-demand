package ai.starlake.quack.edge.authz

import io.circe.Json

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** The canonical JSON both qod-node (json_writer.cpp) and the manager write when bytes must agree:
  * no insignificant whitespace, object keys sorted by their UTF-8 bytes, strings escaped per RFC
  * 8259 with `\"`, `\\`, `\b`, `\f`, `\n`, `\r`, `\t` and every other control character as `\u00XX`
  * in lowercase hex, everything else literal UTF-8, integers only. Arrays keep their order;
  * [[sortedArray]] builds the order-free ones.
  */
object CanonicalJson:

  def render(j: Json): String =
    j.fold(
      "null",
      b => if b then "true" else "false",
      n =>
        n.toLong
          .map(_.toString)
          .getOrElse(throw new IllegalArgumentException(s"canonical JSON holds integers only: $n")),
      s => quote(s),
      arr => arr.map(render).mkString("[", ",", "]"),
      obj =>
        obj.toList
          .sortWith((a, b) => lessBytes(a._1, b._1))
          .map((k, v) => quote(k) + ":" + render(v))
          .mkString("{", ",", "}")
    )

  /** Lowercase hex SHA-256 of [[render]]'s UTF-8 bytes. */
  def sha256Hex(j: Json): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(render(j).getBytes(StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  /** An order-free array: its elements sorted by their canonical bytes, exact duplicates removed.
    */
  def sortedArray(xs: Iterable[Json]): Json =
    Json.fromValues(
      xs.toList
        .map(x => render(x) -> x)
        .distinctBy(_._1)
        .sortWith((a, b) => lessBytes(a._1, b._1))
        .map(_._2)
    )

  private def lessBytes(a: String, b: String): Boolean =
    java.util.Arrays.compareUnsigned(
      a.getBytes(StandardCharsets.UTF_8),
      b.getBytes(StandardCharsets.UTF_8)
    ) < 0

  private def quote(s: String): String =
    val sb = new StringBuilder("\"")
    s.foreach {
      case '"'           => sb.append("\\\"")
      case '\\'          => sb.append("\\\\")
      case '\b'          => sb.append("\\b")
      case '\f'          => sb.append("\\f")
      case '\n'          => sb.append("\\n")
      case '\r'          => sb.append("\\r")
      case '\t'          => sb.append("\\t")
      case c if c < 0x20 => sb.append(f"\\u${c.toInt}%04x")
      case c             => sb.append(c)
    }
    sb.append('"').toString
