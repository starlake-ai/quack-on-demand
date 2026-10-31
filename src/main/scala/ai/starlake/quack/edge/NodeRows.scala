package ai.starlake.quack.edge

import org.apache.arrow.vector.FieldVector
import org.apache.arrow.vector.ipc.ArrowReader
import org.apache.arrow.vector.types.pojo.{ArrowType, Field}

import scala.jdk.CollectionConverters.*

/** One cell of a result the manager reads back itself ([[NodeRows]]). */
enum NodeValue:
  case Null

  /** A VARCHAR. */
  case Text(value: String)

  /** A LIST of VARCHAR; an element is None when NULL. */
  case TextList(values: List[Option[String]])

  /** Any other type, named as Arrow names it. */
  case Other(arrowType: String)

/** The rows of a small result a statement the manager runs on a principal's behalf returns to the
  * manager itself instead of to the client (Task 19): the columns of a table for SUMMARIZE, the
  * value of a computed file location. Read from Arrow on both front doors: the native client
  * decodes the node's chunks into Arrow (the Arrow path's reader, or the native front door's
  * response bytes through the same bridge), and the manager never encodes a chunk.
  */
final case class NodeRows(rows: List[List[NodeValue]])

object NodeRows:

  /** The most rows the manager reads back; a longer result is not read. */
  val MaxRows: Int = 65536

  /** Every row `reader` yields, or Left when there are more than [[MaxRows]]. Does not close it. */
  def read(reader: ArrowReader): Either[String, NodeRows] = readAll(List(reader))

  /** The rows of `readers`, in order, as one result (a PREPARE_RESPONSE's chunks, then each
    * FETCH_RESPONSE's). Does not close them.
    */
  def readAll(readers: List[ArrowReader]): Either[String, NodeRows] =
    val rows = List.newBuilder[List[NodeValue]]
    var n    = 0
    readers.foreach { reader =>
      val root = reader.getVectorSchemaRoot
      while n <= MaxRows && reader.loadNextBatch() do
        val vectors = root.getFieldVectors.asScala.toList
        var i       = 0
        while n <= MaxRows && i < root.getRowCount do
          rows += vectors.map(value(_, i))
          n += 1
          i += 1
    }
    if n > MaxRows then Left(s"the result holds more than $MaxRows rows")
    else Right(NodeRows(rows.result()))

  private def text(field: Field): Boolean = field.getType match
    case _: ArrowType.Utf8 | _: ArrowType.LargeUtf8 | _: ArrowType.Utf8View => true
    case _                                                                  => false

  private def value(v: FieldVector, i: Int): NodeValue =
    if v.isNull(i) then NodeValue.Null
    else
      val field = v.getField
      field.getType match
        case _ if text(field) => NodeValue.Text(v.getObject(i).toString)
        case _: ArrowType.List | _: ArrowType.LargeList | _: ArrowType.ListView |
            _: ArrowType.LargeListView
            if field.getChildren.size == 1 && text(field.getChildren.get(0)) =>
          v.getObject(i) match
            case items: java.util.List[?] =>
              NodeValue.TextList(items.asScala.toList.map(e => Option(e).map(_.toString)))
            case other => NodeValue.Other(field.getType.toString)
        case other => NodeValue.Other(other.toString)
