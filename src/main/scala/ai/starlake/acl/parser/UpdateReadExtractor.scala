package ai.starlake.acl.parser

import net.sf.jsqlparser.statement.update.Update

import scala.jdk.CollectionConverters.*

/** Walks an UPDATE statement's SET-clause value subqueries, WHERE clause, and FROM/JOIN list for
  * read-side table references. Reuses `TableExtractorVisitor` so the same CTE-skipping and
  * sub-query handling applies.
  */
object UpdateReadExtractor:
  def extract(upd: Update): TableExtraction =
    val v = new TableExtractorVisitor()
    // SET-clause value subqueries: UPDATE t SET c = (SELECT ... FROM secret)
    Option(upd.getUpdateSets).foreach(_.asScala.foreach { us =>
      Option(us.getValues).foreach(v.visitExpression)
    })
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
    v.result
