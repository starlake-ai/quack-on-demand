package ai.starlake.quack.edge.admin

import ai.starlake.quack.edge.QueryResult
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.ipc.{ArrowStreamReader, ArrowStreamWriter}
import org.apache.arrow.vector.types.pojo.{ArrowType, Field, FieldType, Schema}
import org.apache.arrow.vector.{VarCharVector, VectorSchemaRoot}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.nio.channels.Channels
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*

/** Synthesizes manager-answered admin results as tiny in-memory Arrow streams so the producer's
  * existing streamArrow pump serves them exactly like node-proxied QueryResults. All columns are
  * nullable UTF8: admin output is human-facing metadata, not typed data.
  */
object AdminResults:

  val ManagerNodeId = "manager"

  /** Single source of truth for the two-column shape every SQL admin dialect MUTATION delivers.
    * `ok()` builds its row from this list, and `FlightProducerImpl.adminStatusSchema` derives its
    * Arrow field names from it, so the advertised Prepare/FlightInfo schema and the actual result
    * columns can never drift apart.
    */
  val MutationColumns: List[String] = List("status", "detail")

  private val allocator = new RootAllocator(Long.MaxValue)

  def table(columns: List[String], rows: List[List[Option[String]]]): QueryResult =
    require(rows.forall(_.size == columns.size), "row arity must match column count")
    val utf8   = new ArrowType.Utf8()
    val schema = new Schema(
      columns.map(c => new Field(c, FieldType.nullable(utf8), null)).asJava
    )
    val bytes =
      val root                      = VectorSchemaRoot.create(schema, allocator)
      var writer: ArrowStreamWriter = null
      try
        root.allocateNew()
        columns.indices.foreach { cIdx =>
          val vec = root.getVector(cIdx).asInstanceOf[VarCharVector]
          rows.zipWithIndex.foreach { case (row, rIdx) =>
            row(cIdx) match
              case Some(v) => vec.setSafe(rIdx, v.getBytes(StandardCharsets.UTF_8))
              case None    => vec.setNull(rIdx)
          }
        }
        root.setRowCount(rows.size)
        val out = new ByteArrayOutputStream()
        writer = new ArrowStreamWriter(root, null, Channels.newChannel(out))
        writer.start()
        writer.writeBatch()
        writer.end()
        out.toByteArray
      finally
        if writer != null then writer.close()
        root.close()
    val reader = new ArrowStreamReader(new ByteArrayInputStream(bytes), allocator)
    QueryResult(
      rows = reader,
      close = () => reader.close(),
      nodeId = ManagerNodeId,
      durationMs = 0L
    )

  def ok(detail: String): QueryResult =
    table(MutationColumns, List(List(Some("ok"), Some(detail))))
