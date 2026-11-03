package ai.starlake.quack.ondemand.runtime

/** The manager's own SQL fragments put into one node init stream (`NodeSpec.extraSetupSql`): a
  * federation blob, its blocks, a merge node's branch attach.
  *
  * qod-node splits the whole stream on DuckDB's own tokenizer, so a fragment whose last statement
  * has no `;` would run into the first statement of the fragment after it, and both would be lost:
  * a `sql` federated source's text is the operator's own and may end that way, or in a `-- comment`
  * with no newline. [[join]] puts a `;` on a line of its own between two fragments (a `;` written
  * after the comment would be swallowed by it), never inside either, so no fragment's text is
  * edited. After a fragment that already ends in `;` it is an empty statement, which qod-node's
  * splitter drops. The spawn scripts close the text they splice the same way (`SQL_CLOSE`,
  * `Add-ClosedSql`), and the last fragment is theirs to close.
  */
object InitSql:

  private val Close = "\n;\n"

  /** The non-empty `fragments`, each closed by a `;` line where another follows; "" for none. */
  def join(fragments: Seq[String]): String = fragments.filter(_.nonEmpty).mkString(Close)
