package ai.starlake.acl.parser

import net.sf.jsqlparser.schema.Table
import net.sf.jsqlparser.statement.update.Update

import scala.jdk.CollectionConverters.*

/** Walks an UPDATE statement's WHERE clause, FROM/JOIN list for read-side table
  * references. Reuses `TableExtractorVisitor` so the same CTE-skipping and
  * sub-query handling applies. */
object UpdateReadExtractor:
  def extract(upd: Update): List[Table] =
    val v = new TableExtractorVisitor()
    // WHERE clause sub-queries
    val where = upd.getWhere
    if where != null then v.visitExpression(where)
    // START JOINs (UPDATE t JOIN other ... before SET)
    Option(upd.getStartJoins).foreach(_.asScala.foreach { j =>
      val ri = j.getRightItem
      if ri != null then v.visitFromItem(ri)
      Option(j.getOnExpressions).foreach(_.asScala.foreach(v.visitExpression))
    })
    // Regular JOINs (UPDATE ... FROM other JOIN ...)
    Option(upd.getJoins).foreach(_.asScala.foreach { j =>
      val ri = j.getRightItem
      if ri != null then v.visitFromItem(ri)
      Option(j.getOnExpressions).foreach(_.asScala.foreach(v.visitExpression))
    })
    // FROM item (UPDATE ... FROM other WHERE ...)
    Option(upd.getFromItem).foreach(v.visitFromItem)
    v.tables.toList