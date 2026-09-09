package ai.starlake.quack.edge.admin

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.apache.arrow.vector.VarCharVector

class AdminResultsSpec extends AnyFlatSpec with Matchers:

  private def readAll(qr: ai.starlake.quack.edge.QueryResult): List[List[Option[String]]] =
    try
      val reader = qr.rows
      val root   = reader.getVectorSchemaRoot
      val out    = List.newBuilder[List[Option[String]]]
      while reader.loadNextBatch() do
        (0 until root.getRowCount).foreach { r =>
          out += (0 until root.getSchema.getFields.size).toList.map { c =>
            val vec = root.getVector(c).asInstanceOf[VarCharVector]
            if vec.isNull(r) then None else Some(new String(vec.get(r), "UTF-8"))
          }
        }
      out.result()
    finally qr.close()

  "AdminResults.table" should "round-trip rows with nulls through Arrow IPC" in:
    val qr = AdminResults.table(
      List("name", "description"),
      List(List(Some("analyst"), None), List(Some("etl"), Some("loader")))
    )
    qr.nodeId shouldBe AdminResults.ManagerNodeId
    readAll(qr) shouldBe List(List(Some("analyst"), None), List(Some("etl"), Some("loader")))

  it should "emit a zero-row result with the schema intact" in:
    val qr = AdminResults.table(List("a", "b"), Nil)
    readAll(qr) shouldBe Nil

  "AdminResults.ok" should "carry status=ok plus detail" in:
    readAll(AdminResults.ok("role analyst created")) shouldBe
      List(List(Some("ok"), Some("role analyst created")))
