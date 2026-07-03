package ai.starlake.acl.parser

import net.sf.jsqlparser.statement.delete.Delete

import scala.jdk.CollectionConverters.*

/** Walks a DELETE statement's WHERE clause, USING list, and JOIN list for read-side table
  * references. Reuses `TableExtractorVisitor` so the same CTE-skipping and sub-query handling
  * applies.
  */
object DeleteReadExtractor:
  def extract(del: Delete): TableExtraction =
    val v = new TableExtractorVisitor()
    // WHERE clause sub-queries
    val where = del.getWhere
    if where != null then v.visitExpression(where)
    // USING clause (PostgreSQL DELETE ... USING other_table)
    Option(del.getUsingFromItemList).foreach(_.asScala.foreach(v.visitFromItem))
    // JOIN list
    Option(del.getJoins).foreach(_.asScala.foreach { j =>
      val ri = j.getRightItem
      if ri != null then v.visitFromItem(ri)
      Option(j.getOnExpressions).foreach(_.asScala.foreach(v.visitExpression))
    })
    v.result
