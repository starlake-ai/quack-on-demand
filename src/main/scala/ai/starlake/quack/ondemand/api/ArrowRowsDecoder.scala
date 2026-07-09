package ai.starlake.quack.ondemand.api

import io.circe.Json
import org.apache.arrow.vector.ipc.ArrowReader
import org.apache.arrow.vector.{BitVector, FieldVector, FloatingPointVector}
import org.apache.arrow.vector.util.Text

import scala.jdk.CollectionConverters._

/** Bounded Arrow-to-JSON row decoder for the catalog preview endpoint ([[CatalogPreviewHandlers]]).
  * `decode` drains an [[ArrowReader]] one `loadNextBatch()` at a time and stops as soon as
  * `maxRows` rows have been collected, so a query that would return millions of rows never fully
  * buffers in the manager process -- once the cap is hit, no further batch is pulled from the
  * reader.
  *
  * Value rendering: DuckDB integers/floats decode through Arrow's numeric vectors and render as
  * native `Json` numbers; booleans render as `Json` booleans; strings (`Text`/`VarCharVector`)
  * render as `Json` strings. Every other Arrow type (dates, decimals, lists, structs, ...) falls
  * back to `getObject(i).toString`, which is lossy for structured types but keeps the preview
  * viewer functional for the full DuckDB type zoo without a per-type branch here. Null cells always
  * render as `Json.Null`, checked before the type dispatch.
  */
object ArrowRowsDecoder:

  /** Decodes at most `maxRows` rows from `reader`. Returns the column list (name + Arrow type
    * name), the decoded rows, and whether more rows existed beyond the cap (`truncated`).
    *
    * `reader.getVectorSchemaRoot` is stable across `loadNextBatch()` calls (Arrow re-populates the
    * same root's vectors per batch), so the schema is read once before the loop.
    */
  def decode(reader: ArrowReader, maxRows: Int): (List[PreviewColumn], List[List[Json]], Boolean) =
    val root    = reader.getVectorSchemaRoot
    val columns = root.getSchema.getFields.asScala.toList.map { f =>
      PreviewColumn(f.getName, f.getType.toString)
    }

    val rows      = List.newBuilder[List[Json]]
    var collected = 0
    var truncated = false
    var hasMore   = reader.loadNextBatch()

    while hasMore && collected < maxRows do
      val vecs     = root.getFieldVectors.asScala.toList
      val rowCount = root.getRowCount
      var i        = 0
      while i < rowCount && collected < maxRows do
        rows += vecs.map(v => renderCell(v, i))
        collected += 1
        i += 1
      if collected >= maxRows then
        // Either this batch had more rows left (i < rowCount), or a further batch is
        // still pending: either way the cap -- not exhaustion of the reader -- stopped us.
        truncated = i < rowCount || reader.loadNextBatch()
        hasMore = false
      else hasMore = reader.loadNextBatch()

    (columns, rows.result(), truncated)

  private def renderCell(vector: FieldVector, i: Int): Json =
    if vector.isNull(i) then Json.Null
    else
      vector match
        case bit: BitVector          => Json.fromBoolean(bit.get(i) != 0)
        case fp: FloatingPointVector =>
          Json
            .fromBigDecimal(java.math.BigDecimal.valueOf(fp.getValueAsDouble(i)))
        case _ =>
          vector.getObject(i) match
            case null      => Json.Null
            case n: Number =>
              Json
                .fromBigDecimal(new java.math.BigDecimal(n.toString))
            case t: Text   => Json.fromString(t.toString)
            case s: String => Json.fromString(s)
            case other     => Json.fromString(other.toString)
